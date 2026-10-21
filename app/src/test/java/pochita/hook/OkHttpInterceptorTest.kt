package pochita.hook

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import pochita.codec.GzipCodec

class OkHttpInterceptorTest {

    private val targetUrl = "https://interface.music.163.com/eapi/v3/playlist/detail"
    private val staticUrl = "https://p1.music.126.net/api/cover.jpg"
    private val classLoader: ClassLoader = OkHttpInterceptor::class.java.classLoader!!

    private fun createMockResponse(
        url: String,
        bodyContent: String,
        isGzip: Boolean = false
    ): Response {
        val request = Request.Builder().url(url).build()
        val rawBytes = bodyContent.toByteArray(Charsets.UTF_8)
        val bodyBytes = if (isGzip) GzipCodec.compress(rawBytes) else rawBytes

        val builder = Response.Builder()
            .request(request)
            .protocol(Protocol.HTTP_1_1)
            .code(200)
            .message("OK")
            .header("Content-Length", bodyBytes.size.toString())
            .body(bodyBytes.toResponseBody("application/json; charset=utf-8".toMediaType()))

        if (isGzip) {
            builder.header("Content-Encoding", "gzip")
        }
        return builder.build()
    }

    @Test
    fun testClosedStreamBugFixOnUnmodifiedResponse() {
        // REGRESSION TEST: Unmodified target response must NOT throw IllegalStateException: closed
        val response = createMockResponse(targetUrl, """{"code":200,"status":"unmodified"}""")

        val result = OkHttpInterceptor.processOkHttpResponse(response, classLoader) { _, _ -> null }

        assertNotNull(result)
        val resultResponse = result as Response
        // Crucial assertion: Reading body must succeed without throwing IllegalStateException: closed!
        val bodyStr = resultResponse.body?.string()
        assertEquals("""{"code":200,"status":"unmodified"}""", bodyStr)
    }

    @Test
    fun testModifiedResponseContentAndHeaders() {
        val originalJson = """{"code":200,"status":"original"}"""
        val modifiedJson = """{"code":200,"status":"unblocked-by-unm"}"""
        val response = createMockResponse(targetUrl, originalJson)

        val result = OkHttpInterceptor.processOkHttpResponse(response, classLoader) { _, _ ->
            modifiedJson.toByteArray(Charsets.UTF_8)
        }

        assertNotNull(result)
        val resultResponse = result as Response
        assertEquals(modifiedJson, resultResponse.body?.string())
        assertEquals(modifiedJson.toByteArray(Charsets.UTF_8).size.toString(), resultResponse.header("Content-Length"))
    }

    @Test
    fun testGzipContentEncodingRemovalWhenDecompressed() {
        val compressedResponse = createMockResponse(targetUrl, "gzip-payload", isGzip = true)
        val plainDecompressedJson = """{"code":200,"uncompressed":true}"""

        val result = OkHttpInterceptor.processOkHttpResponse(compressedResponse, classLoader) { _, _ ->
            plainDecompressedJson.toByteArray(Charsets.UTF_8)
        }

        assertNotNull(result)
        val resultResponse = result as Response
        assertEquals(plainDecompressedJson, resultResponse.body?.string())
        assertNull("Content-Encoding header must be stripped when response is uncompressed", resultResponse.header("Content-Encoding"))
        assertEquals(plainDecompressedJson.toByteArray(Charsets.UTF_8).size.toString(), resultResponse.header("Content-Length"))
    }

    @Test
    fun testNonTargetUrlUntouched() {
        val response = createMockResponse(staticUrl, "image-content")
        var processorCalled = false

        val result = OkHttpInterceptor.processOkHttpResponse(response, classLoader) { _, _ ->
            processorCalled = true
            null
        }

        assertFalse(processorCalled)
        assertEquals(response, result)
        assertEquals("image-content", (result as Response).body?.string())
    }

    @Test
    fun testExceptionIsolationInOkHttpProcessing() {
        val response = createMockResponse(targetUrl, "safe-content")

        val result = OkHttpInterceptor.processOkHttpResponse(response, classLoader) { _, _ ->
            throw RuntimeException("Decoder crash simulation")
        }

        // Must recover safely, returning response with readable body
        assertNotNull(result)
        val resultResponse = result as Response
        assertEquals("safe-content", resultResponse.body?.string())
    }

    @Test
    fun testCreateResponseBodyCompatibility() {
        val bodyBytes = "test-bytes".toByteArray()
        val mediaType = "text/plain".toMediaType()
        val bodyClass = okhttp3.ResponseBody::class.java

        val createdBody = OkHttpInterceptor.createResponseBody(bodyClass, mediaType, bodyBytes)
        assertNotNull(createdBody)
        assertTrue(createdBody is okhttp3.ResponseBody)
        assertEquals(10L, (createdBody as okhttp3.ResponseBody).contentLength())
        assertEquals("test-bytes", createdBody.string())
    }
}
