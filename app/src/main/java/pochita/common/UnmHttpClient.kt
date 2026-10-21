package pochita.common

import java.io.IOException
import java.util.concurrent.TimeUnit
import okhttp3.ConnectionPool
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody

/**
 * Lightweight, unified OkHttp 4.12.0 client wrapper for NetEase UNM.
 *
 * Provides connection pooling, automatic redirect handling (HTTP 301..308),
 * unified User-Agent management, configurable timeouts, error isolation, and
 * deterministic mock interceptor hooks for offline unit testing.
 */
object UnmHttpClient {
    private const val TAG = "UnmHttpClient"

    /** Default timeout in milliseconds for connect, read, and write operations. */
    const val DEFAULT_TIMEOUT_MS = 10000L

    /** Realistic mobile User-Agent simulating Android NetEase Cloud Music environment. */
    const val DEFAULT_USER_AGENT =
        "Mozilla/5.0 (Linux; Android 14; NetEase-UNM-LSPosed) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/128.0.0.0 Mobile Safari/537.36"

    @Volatile
    var defaultUserAgent: String = DEFAULT_USER_AGENT

    private val defaultOkHttpClient: OkHttpClient = OkHttpClient.Builder()
        .connectionPool(ConnectionPool(5, 5, TimeUnit.MINUTES))
        .connectTimeout(DEFAULT_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        .readTimeout(DEFAULT_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        .writeTimeout(DEFAULT_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        .followRedirects(true)
        .followSslRedirects(true)
        .retryOnConnectionFailure(true)
        .build()

    @Volatile
    private var customClient: OkHttpClient? = null

    @Volatile
    private var mockInterceptor: Interceptor? = null

    private val effectiveClient: OkHttpClient
        get() {
            val base = customClient ?: defaultOkHttpClient
            val interceptor = mockInterceptor
            return if (interceptor != null) {
                base.newBuilder().addInterceptor(interceptor).build()
            } else {
                base
            }
        }

    /**
     * Executes a synchronous HTTP GET request.
     *
     * @param url Target HTTP/HTTPS URL
     * @param headers Custom request headers
     * @param timeoutMs Timeout in milliseconds for this specific request
     * @return [HttpResponse] containing status code, headers, and body bytes, or code -1 on failure
     */
    fun get(
        url: String,
        headers: Map<String, String> = emptyMap(),
        timeoutMs: Long = DEFAULT_TIMEOUT_MS
    ): HttpResponse {
        return try {
            val request = buildRequest(url, headers) { it.get() }
            executeRequest(request, timeoutMs)
        } catch (t: Throwable) {
            logW(TAG, "GET request error for $url: ${t.message}", t)
            HttpResponse(code = -1, headers = emptyMap(), bodyBytes = ByteArray(0), error = t)
        }
    }

    /**
     * Executes a synchronous HTTP POST request with a [ByteArray] payload.
     *
     * @param url Target HTTP/HTTPS URL
     * @param body Raw request body bytes
     * @param contentType Content-Type header (e.g., "application/json; charset=utf-8")
     * @param headers Custom request headers
     * @param timeoutMs Timeout in milliseconds for this specific request
     * @return [HttpResponse] containing status code, headers, and body bytes, or code -1 on failure
     */
    fun post(
        url: String,
        body: ByteArray,
        contentType: String = "application/json",
        headers: Map<String, String> = emptyMap(),
        timeoutMs: Long = DEFAULT_TIMEOUT_MS
    ): HttpResponse {
        return try {
            val mediaType = contentType.toMediaTypeOrNull()
            val requestBody = body.toRequestBody(mediaType)
            val request = buildRequest(url, headers) { it.post(requestBody) }
            executeRequest(request, timeoutMs)
        } catch (t: Throwable) {
            logW(TAG, "POST request error for $url: ${t.message}", t)
            HttpResponse(code = -1, headers = emptyMap(), bodyBytes = ByteArray(0), error = t)
        }
    }

    /**
     * Convenience overload for HTTP POST with a UTF-8 [String] payload.
     */
    fun post(
        url: String,
        body: String,
        contentType: String = "application/json",
        headers: Map<String, String> = emptyMap(),
        timeoutMs: Long = DEFAULT_TIMEOUT_MS
    ): HttpResponse {
        return post(url, body.toByteArray(Charsets.UTF_8), contentType, headers, timeoutMs)
    }

    /**
     * Convenience helper executing GET and returning the UTF-8 body string if successful (2xx), or null otherwise.
     */
    fun getString(
        url: String,
        headers: Map<String, String> = emptyMap(),
        timeoutMs: Long = DEFAULT_TIMEOUT_MS
    ): String? {
        return get(url, headers, timeoutMs).bodyStringOrNull()
    }

    /**
     * Convenience helper executing POST and returning the UTF-8 body string if successful (2xx), or null otherwise.
     */
    fun postString(
        url: String,
        body: String,
        contentType: String = "application/json",
        headers: Map<String, String> = emptyMap(),
        timeoutMs: Long = DEFAULT_TIMEOUT_MS
    ): String? {
        return post(url, body, contentType, headers, timeoutMs).bodyStringOrNull()
    }

    // =========================================================================
    // Mocking & Testing APIs
    // =========================================================================

    /**
     * Sets a functional mock handler for testing. Incoming requests are intercepted
     * before network transport and served by this handler.
     */
    fun setMockHandler(handler: (Request) -> Response) {
        this.mockInterceptor = Interceptor { chain ->
            handler(chain.request())
        }
    }

    /**
     * Sets a custom OkHttp [Interceptor] for request/response inspection or mutation.
     */
    fun setMockInterceptor(interceptor: Interceptor?) {
        this.mockInterceptor = interceptor
    }

    /**
     * Injects a custom [OkHttpClient] instance (e.g. for specialized SSL/proxy tests).
     */
    fun setCustomClient(client: OkHttpClient?) {
        this.customClient = client
    }

    /**
     * Resets all custom clients, mock interceptors, and restores default settings.
     */
    fun reset() {
        this.customClient = null
        this.mockInterceptor = null
        this.defaultUserAgent = DEFAULT_USER_AGENT
    }

    /**
     * Helper to construct an OkHttp [Response] for test mocks.
     */
    fun createMockResponse(
        request: Request,
        code: Int = 200,
        body: String = "",
        contentType: String = "application/json; charset=utf-8",
        headers: Map<String, String> = emptyMap(),
        message: String = if (code in 200..299) "OK" else "Error"
    ): Response {
        val mediaType = contentType.toMediaTypeOrNull()
        val responseBody = body.toByteArray(Charsets.UTF_8).toResponseBody(mediaType)
        val builder = Response.Builder()
            .request(request)
            .protocol(Protocol.HTTP_1_1)
            .code(code)
            .message(message)
            .body(responseBody)
        headers.forEach { (k, v) -> builder.addHeader(k, v) }
        return builder.build()
    }

    /**
     * Helper to construct a binary OkHttp [Response] for test mocks.
     */
    fun createMockResponse(
        request: Request,
        code: Int,
        bodyBytes: ByteArray,
        contentType: String = "application/octet-stream",
        headers: Map<String, String> = emptyMap()
    ): Response {
        val mediaType = contentType.toMediaTypeOrNull()
        val responseBody = bodyBytes.toResponseBody(mediaType)
        val builder = Response.Builder()
            .request(request)
            .protocol(Protocol.HTTP_1_1)
            .code(code)
            .message(if (code in 200..299) "OK" else "Error")
            .body(responseBody)
        headers.forEach { (k, v) -> builder.addHeader(k, v) }
        return builder.build()
    }

    // =========================================================================
    // Internal Request Handling
    // =========================================================================

    private inline fun buildRequest(
        url: String,
        headers: Map<String, String>,
        methodBlock: (Request.Builder) -> Request.Builder
    ): Request {
        val builder = Request.Builder().url(url)
        val hasUserAgent = headers.keys.any { it.equals("User-Agent", ignoreCase = true) }
        if (!hasUserAgent) {
            builder.header("User-Agent", defaultUserAgent)
        }
        headers.forEach { (k, v) ->
            builder.header(k, v)
        }
        return methodBlock(builder).build()
    }

    private fun executeRequest(request: Request, timeoutMs: Long): HttpResponse {
        val client = if (timeoutMs == DEFAULT_TIMEOUT_MS) {
            effectiveClient
        } else {
            effectiveClient.newBuilder()
                .connectTimeout(timeoutMs, TimeUnit.MILLISECONDS)
                .readTimeout(timeoutMs, TimeUnit.MILLISECONDS)
                .writeTimeout(timeoutMs, TimeUnit.MILLISECONDS)
                .build()
        }

        return try {
            client.newCall(request).execute().use { response ->
                val bodyBytes = response.body?.bytes() ?: ByteArray(0)
                val headersMap = response.headers.toMultimap()
                HttpResponse(
                    code = response.code,
                    headers = headersMap,
                    bodyBytes = bodyBytes,
                    error = null
                )
            }
        } catch (t: Throwable) {
            logW(TAG, "Network call failed for ${request.url}: ${t.message}", t)
            HttpResponse(
                code = -1,
                headers = emptyMap(),
                bodyBytes = ByteArray(0),
                error = t
            )
        }
    }

    private fun logW(tag: String, msg: String, tr: Throwable? = null) {
        try {
            UnmLogger.w(tag, msg, tr)
        } catch (_: Throwable) {
            System.err.println("[$tag] $msg ${tr?.message ?: ""}")
        }
    }
}

/**
 * Representation of an HTTP response returned by [UnmHttpClient].
 *
 * @property code HTTP status code (e.g. 200, 404), or -1 if the network request failed
 * @property headers Map of response headers (multimap supporting multiple values per header)
 * @property bodyBytes Raw body payload as a byte array
 * @property error Underlying exception if the request failed with an exception, null otherwise
 */
data class HttpResponse(
    val code: Int,
    val headers: Map<String, List<String>> = emptyMap(),
    val bodyBytes: ByteArray = ByteArray(0),
    val error: Throwable? = null
) {
    /** True if [code] is in the 200..299 range. */
    val isSuccessful: Boolean get() = code in 200..299

    /** True if [isSuccessful] is false. */
    val isFailure: Boolean get() = !isSuccessful

    /** UTF-8 representation of [bodyBytes]. */
    val bodyString: String get() = if (bodyBytes.isNotEmpty()) String(bodyBytes, Charsets.UTF_8) else ""

    /**
     * Returns [bodyString] if [isSuccessful] is true, or null otherwise.
     */
    fun bodyStringOrNull(): String? = if (isSuccessful) bodyString else null

    /**
     * Returns the first header value matching [name] case-insensitively, or null.
     */
    fun header(name: String): String? {
        val entry = headers.entries.firstOrNull { it.key.equals(name, ignoreCase = true) }
        return entry?.value?.firstOrNull()
    }

    /**
     * Returns all header values matching [name] case-insensitively, or an empty list.
     */
    fun headers(name: String): List<String> {
        val entry = headers.entries.firstOrNull { it.key.equals(name, ignoreCase = true) }
        return entry?.value ?: emptyList()
    }

    /**
     * Throws [UnmHttpException] if [isSuccessful] is false, otherwise returns `this`.
     */
    fun getOrThrow(): HttpResponse {
        if (!isSuccessful) {
            throw UnmHttpException(code, "HTTP request failed with status code $code", error)
        }
        return this
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false

        other as HttpResponse

        if (code != other.code) return false
        if (headers != other.headers) return false
        if (!bodyBytes.contentEquals(other.bodyBytes)) return false
        if (error != other.error) return false

        return true
    }

    override fun hashCode(): Int {
        var result = code
        result = 31 * result + headers.hashCode()
        result = 31 * result + bodyBytes.contentHashCode()
        result = 31 * result + (error?.hashCode() ?: 0)
        return result
    }
}

/**
 * Typed exception thrown when an HTTP request fails and explicit error propagation is requested.
 */
class UnmHttpException(
    val code: Int,
    message: String,
    cause: Throwable? = null
) : IOException(message, cause)
