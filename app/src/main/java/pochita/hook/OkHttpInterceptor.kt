package pochita.hook

import io.github.libxposed.api.XposedInterface
import io.github.libxposed.api.XposedModule
import java.lang.reflect.InvocationHandler
import java.lang.reflect.Method
import java.lang.reflect.Proxy
import pochita.codec.GzipCodec
import pochita.common.UnmLogger

/**
 * Hook interceptor for OkHttp network stack (okhttp3.RealCall).
 *
 * Key Architectural Highlights:
 * - Intercepts RealCall.getResponseWithInterceptorChain$okhttp() to unify sync & async handling.
 * - Closed-Stream Bug Fix: ALWAYS reconstructs a new ResponseBody when reading bytes,
 *   preventing IllegalStateException: closed on both modified and unmodified paths.
 * - Dynamically updates Content-Length and removes Content-Encoding on uncompressed responses.
 * - Multi-version reflection engine supporting OkHttp 3.x and 4.x ResponseBody creation.
 * - Total exception safety: silent recovery and preservation of readable body.
 */
object OkHttpInterceptor {

    private const val TAG = "OkHttpInterceptor"

    typealias PayloadProcessor = (url: String, rawBytes: ByteArray) -> ByteArray?

    /**
     * Installs method hooks into RealCall via LSPosed.
     */
    fun install(module: XposedModule, classLoader: ClassLoader, processor: PayloadProcessor) {
        val realCallClass = try {
            classLoader.loadClass("okhttp3.RealCall")
        } catch (t: Throwable) {
            UnmLogger.w(TAG, "okhttp3.RealCall not found in classLoader: ${t.message}")
            return
        }

        // Strategy A: Unified interceptor chain (covers both execute and enqueue in OkHttp 3 & 4)
        val chainMethod = realCallClass.declaredMethods.firstOrNull {
            it.name.startsWith("getResponseWithInterceptorChain") && it.parameterTypes.isEmpty()
        }

        if (chainMethod != null) {
            try {
                module.hook(chainMethod)
                .setPriority(XposedInterface.PRIORITY_DEFAULT)
                .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                .intercept { chain ->
                    val response = chain.proceed()
                    processOkHttpResponse(response, classLoader, processor) ?: response
                }
                UnmLogger.i(TAG, "Hooked RealCall.${chainMethod.name}() successfully (unified sync/async)")
                return
            } catch (t: Throwable) {
                UnmLogger.w(TAG, "Failed to hook ${chainMethod.name}, falling back to execute/enqueue: ${t.message}")
            }
        }

        // Strategy B: Fallback to separate execute() and enqueue() hooks
        try {
            val executeMethod = realCallClass.getDeclaredMethod("execute")
            module.hook(executeMethod)
                .setPriority(XposedInterface.PRIORITY_DEFAULT)
                .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                .intercept { chain ->
                    val response = chain.proceed()
                    processOkHttpResponse(response, classLoader, processor) ?: response
                }
            UnmLogger.i(TAG, "Hooked RealCall.execute() successfully")
        } catch (t: Throwable) {
            UnmLogger.w(TAG, "Failed to hook RealCall.execute: ${t.message}")
        }

        try {
            val callbackClass = classLoader.loadClass("okhttp3.Callback")
            val enqueueMethod = realCallClass.getDeclaredMethod("enqueue", callbackClass)
            module.hook(enqueueMethod)
                .setPriority(XposedInterface.PRIORITY_DEFAULT)
                .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                .intercept { chain ->
                    val originalCallback = chain.args.getOrNull(0)
                    if (originalCallback != null) {
                        val proxyCallback = Proxy.newProxyInstance(
                            classLoader,
                            arrayOf(callbackClass),
                            CallbackProxy(originalCallback, classLoader, processor)
                        )
                        chain.args[0] = proxyCallback
                    }
                    chain.proceed()
                }
            UnmLogger.i(TAG, "Hooked RealCall.enqueue() successfully")
        } catch (t: Throwable) {
            UnmLogger.w(TAG, "Failed to hook RealCall.enqueue: ${t.message}")
        }
    }

    private class CallbackProxy(
        private val originalCallback: Any,
        private val classLoader: ClassLoader,
        private val processor: PayloadProcessor
    ) : InvocationHandler {
        override fun invoke(proxy: Any?, method: Method, args: Array<out Any>?): Any? {
            if (method.name == "onResponse" && args != null && args.size >= 2) {
                val call = args[0]
                val originalResponse = args[1]
                val modifiedResponse = processOkHttpResponse(originalResponse, classLoader, processor) ?: originalResponse
                return method.invoke(originalCallback, call, modifiedResponse)
            }
            return if (args == null) {
                method.invoke(originalCallback)
            } else {
                method.invoke(originalCallback, *args)
            }
        }
    }

    /**
     * Inspects, rewrites, and safely rebuilds an OkHttp Response object.
     * Operates purely via reflection across ClassLoaders.
     */
    fun processOkHttpResponse(
        responseObj: Any?,
        classLoader: ClassLoader,
        processor: PayloadProcessor
    ): Any? {
        if (responseObj == null) return null
        var rawBytes: ByteArray? = null
        var responseBodyClass: Class<*>? = null
        var mediaType: Any? = null
        var responseBuilder: Any? = null

        try {
            val requestMethod = responseObj.javaClass.getMethod("request")
            val request = requestMethod.invoke(responseObj) ?: return responseObj

            val urlMethod = request.javaClass.getMethod("url")
            val url = urlMethod.invoke(request)?.toString() ?: return responseObj

            // Filter non-target URLs before touching body stream
            if (!HookUrlMatcher.isRelevantUrl(url)) {
                return responseObj
            }

            val bodyMethod = responseObj.javaClass.getMethod("body")
            val responseBody = bodyMethod.invoke(responseObj) ?: return responseObj

            responseBodyClass = try {
                classLoader.loadClass("okhttp3.ResponseBody")
            } catch (_: Throwable) {
                responseBody.javaClass
            }

            val contentTypeMethod = responseBody.javaClass.getMethod("contentType")
            mediaType = contentTypeMethod.invoke(responseBody)

            val bytesMethod = responseBody.javaClass.getMethod("bytes")
            rawBytes = bytesMethod.invoke(responseBody) as? ByteArray ?: return responseObj

            // Stream is now consumed. MUST guarantee replacement ResponseBody.
            val newBuilderMethod = responseObj.javaClass.getMethod("newBuilder")
            responseBuilder = newBuilderMethod.invoke(responseObj) ?: return responseObj

            val modifiedBytes = try {
                processor(url, rawBytes)
            } catch (t: Throwable) {
                UnmLogger.e(TAG, "Error executing processor for $url: ${t.message}", t)
                null
            }

            val finalBytes = modifiedBytes ?: rawBytes
            val hasModified = modifiedBytes != null

            val newBody = createResponseBody(responseBodyClass, mediaType, finalBytes)
            if (newBody != null) {
                val builderBodyMethod = responseBuilder.javaClass.getMethod("body", responseBodyClass)
                builderBodyMethod.invoke(responseBuilder, newBody)

                if (hasModified) {
                    // 1. Remove Content-Encoding if uncompressed
                    if (!GzipCodec.isGzip(finalBytes)) {
                        try {
                            val removeHeaderMethod = responseBuilder.javaClass.getMethod("removeHeader", String::class.java)
                            removeHeaderMethod.invoke(responseBuilder, "Content-Encoding")
                        } catch (_: Throwable) {}
                    }
                    // 2. Update Content-Length header
                    try {
                        val headerMethod = responseBuilder.javaClass.getMethod("header", String::class.java, String::class.java)
                        headerMethod.invoke(responseBuilder, "Content-Length", finalBytes.size.toString())
                    } catch (_: Throwable) {}
                }

                val buildMethod = responseBuilder.javaClass.getMethod("build")
                return buildMethod.invoke(responseBuilder)
            } else {
                return responseObj
            }
        } catch (t: Throwable) {
            UnmLogger.e(TAG, "Error in processOkHttpResponse: ${t.message}", t)
            // Error recovery path: Ensure caller does not receive a closed stream
            if (rawBytes != null && responseBodyClass != null && responseBuilder != null) {
                try {
                    val fallbackBody = createResponseBody(responseBodyClass, mediaType, rawBytes)
                    if (fallbackBody != null) {
                        val builderBodyMethod = responseBuilder.javaClass.getMethod("body", responseBodyClass)
                        builderBodyMethod.invoke(responseBuilder, fallbackBody)
                        val buildMethod = responseBuilder.javaClass.getMethod("build")
                        return buildMethod.invoke(responseBuilder)
                    }
                } catch (_: Throwable) {}
            }
            return responseObj
        }
    }

    /**
     * Dynamic reflection helper creating an okhttp3.ResponseBody compatible with both OkHttp 3 & 4.
     */
    fun createResponseBody(
        responseBodyClass: Class<*>,
        mediaType: Any?,
        contentBytes: ByteArray
    ): Any? {
        // OkHttp 3.x static method: ResponseBody.create(MediaType, byte[])
        try {
            val methodOkHttp3 = responseBodyClass.methods.firstOrNull {
                it.name == "create" && it.parameterTypes.size == 2 &&
                        it.parameterTypes[1] == ByteArray::class.java
            }
            if (methodOkHttp3 != null) {
                return methodOkHttp3.invoke(null, mediaType, contentBytes)
            }
        } catch (_: Throwable) {}

        // OkHttp 4.x Kotlin companion: ResponseBody.Companion.create(byte[], MediaType)
        try {
            val companionField = responseBodyClass.getField("Companion")
            val companionObj = companionField.get(null)
            val companionClass = companionObj.javaClass
            val methodOkHttp4 = companionClass.methods.firstOrNull {
                it.name == "create" && it.parameterTypes.size == 2 &&
                        it.parameterTypes[0] == ByteArray::class.java
            }
            if (methodOkHttp4 != null) {
                return methodOkHttp4.invoke(companionObj, contentBytes, mediaType)
            }
        } catch (_: Throwable) {}

        // Generic static fallback: any 2-argument create method matching (byte[], MediaType) or vice versa
        try {
            for (m in responseBodyClass.methods) {
                if (m.name == "create" && m.parameterTypes.size == 2) {
                    val p0 = m.parameterTypes[0]
                    val p1 = m.parameterTypes[1]
                    if (p0 == ByteArray::class.java) {
                        return m.invoke(null, contentBytes, mediaType)
                    } else if (p1 == ByteArray::class.java) {
                        return m.invoke(null, mediaType, contentBytes)
                    }
                }
            }
        } catch (_: Throwable) {}

        return null
    }
}
