package pochita.hook

import io.github.libxposed.api.XposedInterface
import io.github.libxposed.api.XposedModule
import java.io.ByteArrayInputStream
import java.io.InputStream
import java.net.HttpURLConnection
import java.util.Collections
import java.util.LinkedHashMap
import java.util.WeakHashMap
import pochita.common.UnmLogger

/**
 * Hook interceptor for Chromium network stack (CronetHttpURLConnection).
 *
 * Responsibilities:
 * - Intercepts getInputStream() to rewrite EAPI / Player URL payloads.
 * - Manages clean stream lifecycle via [ReplacedInputStream] to prevent native buffer leaks.
 * - Tracks modified payload lengths per connection via weak references.
 * - Synchronizes getContentLength(), getContentLengthLong(), getHeaderField(), and getHeaderFields().
 * - Strict exception isolation: all errors fall back to the original stream.
 */
object CronetInterceptor {

    private const val TAG = "CronetInterceptor"

    /**
     * Functional callback for processing intercepted payloads.
     * Takes (url: String, rawBytes: ByteArray) and returns modified ByteArray, or null if unchanged.
     */
    typealias PayloadProcessor = (url: String, rawBytes: ByteArray) -> ByteArray?

    // Weak connection-to-length map: automatically collected when HttpURLConnection is garbage collected
    private val modifiedLengths = Collections.synchronizedMap(WeakHashMap<HttpURLConnection, Int>())

    /**
     * Installs method hooks into the host's CronetHttpURLConnection class via LSPosed.
     */
    fun install(module: XposedModule, classLoader: ClassLoader, processor: PayloadProcessor) {
        val cronetClass = try {
            classLoader.loadClass("org.chromium.net.urlconnection.CronetHttpURLConnection")
        } catch (t: Throwable) {
            UnmLogger.w(TAG, "CronetHttpURLConnection not found in classLoader: ${t.message}")
            return
        }

        // 1. Hook getInputStream()
        try {
            val method = cronetClass.getDeclaredMethod("getInputStream")
            module.hook(method)
                .setPriority(XposedInterface.PRIORITY_DEFAULT)
                .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                .intercept { chain ->
                    val conn = chain.thisObject as? HttpURLConnection
                    val originalStream = chain.proceed() as? InputStream
                    interceptInputStream(conn, originalStream, processor)
                }
            UnmLogger.i(TAG, "Hooked CronetHttpURLConnection.getInputStream() successfully")
        } catch (t: Throwable) {
            UnmLogger.e(TAG, "Failed to hook Cronet getInputStream", t)
        }

        // 2. Hook getContentLength()
        try {
            val method = cronetClass.getDeclaredMethod("getContentLength")
            module.hook(method)
                .setPriority(XposedInterface.PRIORITY_DEFAULT)
                .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                .intercept { chain ->
                    val conn = chain.thisObject as? HttpURLConnection
                    val original = chain.proceed() as? Int ?: -1
                    interceptContentLength(conn, original)
                }
        } catch (t: Throwable) {
            UnmLogger.w(TAG, "Failed to hook Cronet getContentLength: ${t.message}")
        }

        // 3. Hook getContentLengthLong()
        try {
            val method = cronetClass.getDeclaredMethod("getContentLengthLong")
            module.hook(method)
                .setPriority(XposedInterface.PRIORITY_DEFAULT)
                .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                .intercept { chain ->
                    val conn = chain.thisObject as? HttpURLConnection
                    val original = chain.proceed() as? Long ?: -1L
                    interceptContentLengthLong(conn, original)
                }
        } catch (t: Throwable) {
            UnmLogger.w(TAG, "Failed to hook Cronet getContentLengthLong: ${t.message}")
        }

        // 4. Hook getHeaderField(String)
        try {
            val method = cronetClass.getDeclaredMethod("getHeaderField", String::class.java)
            module.hook(method)
                .setPriority(XposedInterface.PRIORITY_DEFAULT)
                .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                .intercept { chain ->
                    val name = chain.args.getOrNull(0) as? String
                    val conn = chain.thisObject as? HttpURLConnection
                    val original = chain.proceed() as? String
                    interceptHeaderField(conn, name, original)
                }
        } catch (t: Throwable) {
            UnmLogger.w(TAG, "Failed to hook Cronet getHeaderField: ${t.message}")
        }

        // 5. Hook getHeaderFields()
        try {
            val method = cronetClass.getDeclaredMethod("getHeaderFields")
            module.hook(method)
                .setPriority(XposedInterface.PRIORITY_DEFAULT)
                .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                .intercept { chain ->
                    val conn = chain.thisObject as? HttpURLConnection
                    @Suppress("UNCHECKED_CAST")
                    val original = chain.proceed() as? Map<String?, List<String>>
                    interceptHeaderFields(conn, original)
                }
        } catch (t: Throwable) {
            UnmLogger.w(TAG, "Failed to hook Cronet getHeaderFields: ${t.message}")
        }
    }

    /**
     * Intercepts and rewrites the input stream of an HttpURLConnection.
     * Decoupled from Xposed runtime for direct JVM unit testing.
     */
    fun interceptInputStream(
        conn: HttpURLConnection?,
        originalStream: InputStream?,
        processor: PayloadProcessor
    ): InputStream? {
        if (conn == null || originalStream == null) return originalStream
        val url = try { conn.url?.toString() ?: "" } catch (_: Throwable) { "" }

        if (!HookUrlMatcher.isRelevantUrl(url)) {
            return originalStream
        }

        UnmLogger.d(TAG, "Cronet target API detected: $url")
        var rawBytes: ByteArray? = null
        return try {
            rawBytes = originalStream.readBytes()
            val modifiedBytes = processor(url, rawBytes)
            if (modifiedBytes != null) {
                UnmLogger.i(TAG, "Cronet API modified: $url (${rawBytes.size} -> ${modifiedBytes.size} bytes)")
                modifiedLengths[conn] = modifiedBytes.size
                ReplacedInputStream(ByteArrayInputStream(modifiedBytes), originalStream)
            } else {
                UnmLogger.d(TAG, "Cronet API unchanged: $url (${rawBytes.size} bytes)")
                ReplacedInputStream(ByteArrayInputStream(rawBytes), originalStream)
            }
        } catch (t: Throwable) {
            UnmLogger.e(TAG, "Error processing Cronet stream for $url: ${t.message}", t)
            if (rawBytes != null && rawBytes.isNotEmpty()) {
                ReplacedInputStream(ByteArrayInputStream(rawBytes), originalStream)
            } else {
                originalStream
            }
        }
    }

    fun interceptContentLength(conn: HttpURLConnection?, originalLength: Int): Int {
        if (conn != null && modifiedLengths.containsKey(conn)) {
            return modifiedLengths[conn]!!
        }
        return originalLength
    }

    fun interceptContentLengthLong(conn: HttpURLConnection?, originalLength: Long): Long {
        if (conn != null && modifiedLengths.containsKey(conn)) {
            return modifiedLengths[conn]!!.toLong()
        }
        return originalLength
    }

    fun interceptHeaderField(conn: HttpURLConnection?, name: String?, originalValue: String?): String? {
        if (conn != null && "Content-Length".equals(name, ignoreCase = true) && modifiedLengths.containsKey(conn)) {
            return modifiedLengths[conn]!!.toString()
        }
        return originalValue
    }

    fun interceptHeaderFields(
        conn: HttpURLConnection?,
        originalFields: Map<String?, List<String>>?
    ): Map<String?, List<String>>? {
        if (conn != null && modifiedLengths.containsKey(conn) && originalFields != null) {
            val newLengthStr = modifiedLengths[conn]!!.toString()
            val updated = LinkedHashMap<String?, List<String>>(originalFields.size + 1)
            var replaced = false
            for ((key, values) in originalFields) {
                if ("Content-Length".equals(key, ignoreCase = true)) {
                    updated[key] = listOf(newLengthStr)
                    replaced = true
                } else {
                    updated[key] = values
                }
            }
            if (!replaced) {
                updated["Content-Length"] = listOf(newLengthStr)
            }
            return updated
        }
        return originalFields
    }

    fun clearTrackedConnections() {
        modifiedLengths.clear()
    }

    /**
     * InputStream wrapper that reads from [delegate] and forwards [close] to [originalStream].
     */
    class ReplacedInputStream(
        private val delegate: ByteArrayInputStream,
        private val originalStream: InputStream?
    ) : InputStream() {
        override fun read(): Int = delegate.read()
        override fun read(b: ByteArray): Int = delegate.read(b, 0, b.size)
        override fun read(b: ByteArray, off: Int, len: Int): Int = delegate.read(b, off, len)
        override fun available(): Int = delegate.available()
        override fun skip(n: Long): Long = delegate.skip(n)
        override fun mark(readlimit: Int) = delegate.mark(readlimit)
        override fun reset() = delegate.reset()
        override fun markSupported(): Boolean = delegate.markSupported()

        override fun close() {
            try {
                delegate.close()
            } finally {
                try {
                    originalStream?.close()
                } catch (_: Throwable) {
                    // Suppress any native socket close error
                }
            }
        }
    }
}
