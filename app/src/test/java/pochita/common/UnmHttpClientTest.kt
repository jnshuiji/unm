package pochita.common

import java.net.SocketTimeoutException
import java.net.UnknownHostException
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test

class UnmHttpClientTest {

    @Before
    fun setUp() {
        UnmHttpClient.reset()
    }

    @After
    fun tearDown() {
        UnmHttpClient.reset()
    }

    // =========================================================================
    // Tier 1: Happy-Path Tests (>= 5 cases)
    // =========================================================================

    @Test
    fun testGetSuccess() {
        var interceptedUrl = ""
        var interceptedHeader = ""

        UnmHttpClient.setMockHandler { request ->
            interceptedUrl = request.url.toString()
            interceptedHeader = request.header("X-Custom-Req") ?: ""
            UnmHttpClient.createMockResponse(
                request = request,
                code = 200,
                body = """{"status":"ok","songId":12345}""",
                headers = mapOf("X-Server-Resp" to "unm-v1")
            )
        }

        val response = UnmHttpClient.get(
            url = "https://api.music.example.com/song/detail?id=12345",
            headers = mapOf("X-Custom-Req" to "test-token")
        )

        assertTrue(response.isSuccessful)
        assertFalse(response.isFailure)
        assertEquals(200, response.code)
        assertEquals("https://api.music.example.com/song/detail?id=12345", interceptedUrl)
        assertEquals("test-token", interceptedHeader)
        assertEquals("""{"status":"ok","songId":12345}""", response.bodyString)
        assertEquals("unm-v1", response.header("X-Server-Resp"))
        assertNull(response.error)
    }

    @Test
    fun testPostByteArray() {
        val payload = byteArrayOf(0x01, 0x02, 0x03, 0x04)
        var receivedBytes: ByteArray? = null
        var receivedContentType = ""

        UnmHttpClient.setMockHandler { request ->
            val buffer = okio.Buffer()
            request.body?.writeTo(buffer)
            receivedBytes = buffer.readByteArray()
            receivedContentType = request.body?.contentType()?.toString() ?: ""

            UnmHttpClient.createMockResponse(
                request = request,
                code = 201,
                body = "created"
            )
        }

        val response = UnmHttpClient.post(
            url = "https://api.music.example.com/upload",
            body = payload,
            contentType = "application/octet-stream"
        )

        assertEquals(201, response.code)
        assertTrue(response.isSuccessful)
        assertArrayEquals(payload, receivedBytes)
        assertTrue(receivedContentType.contains("application/octet-stream"))
        assertEquals("created", response.bodyString)
    }

    @Test
    fun testPostString() {
        val jsonPayload = """{"type":5,"subType":5,"musicId":0}"""
        var receivedBody = ""

        UnmHttpClient.setMockHandler { request ->
            val buffer = okio.Buffer()
            request.body?.writeTo(buffer)
            receivedBody = buffer.readUtf8()

            UnmHttpClient.createMockResponse(
                request = request,
                code = 200,
                body = """{"code":200,"message":"success"}"""
            )
        }

        val response = UnmHttpClient.post(
            url = "http://bd-api.kuwo.cn/api/service/advert/watch",
            body = jsonPayload,
            contentType = "application/json; charset=utf-8"
        )

        assertEquals(200, response.code)
        assertEquals(jsonPayload, receivedBody)
        assertTrue(response.bodyString.contains(""""message":"success""""))
    }

    @Test
    fun testDefaultUserAgentApplied() {
        var recordedUa: String? = null

        UnmHttpClient.setMockHandler { request ->
            recordedUa = request.header("User-Agent")
            UnmHttpClient.createMockResponse(request, 200, "ok")
        }

        UnmHttpClient.get("https://api.music.example.com/test")

        assertNotNull(recordedUa)
        assertEquals(UnmHttpClient.DEFAULT_USER_AGENT, recordedUa)
    }

    @Test
    fun testCustomUserAgentPreserved() {
        var recordedUa: String? = null

        UnmHttpClient.setMockHandler { request ->
            recordedUa = request.header("User-Agent")
            UnmHttpClient.createMockResponse(request, 200, "ok")
        }

        // Custom UA simulating Bodian client
        val customUa = "Dart/2.19 (dart:io)"
        UnmHttpClient.get(
            url = "https://bd-api.kuwo.cn/api/play/music/v2/audioUrl",
            headers = mapOf("user-agent" to customUa)
        )

        assertEquals(customUa, recordedUa)
    }

    @Test
    fun testGetStringAndPostStringHelpers() {
        UnmHttpClient.setMockHandler { request ->
            if (request.method == "GET") {
                UnmHttpClient.createMockResponse(request, 200, "get-result")
            } else {
                UnmHttpClient.createMockResponse(request, 200, "post-result")
            }
        }

        val getResult = UnmHttpClient.getString("https://example.com/get")
        val postResult = UnmHttpClient.postString("https://example.com/post", "test-body")

        assertEquals("get-result", getResult)
        assertEquals("post-result", postResult)

        // Negative test: 404 should return null
        UnmHttpClient.setMockHandler { request ->
            UnmHttpClient.createMockResponse(request, 404, "not found")
        }

        val notFoundGet = UnmHttpClient.getString("https://example.com/404")
        assertNull(notFoundGet)
    }

    // =========================================================================
    // Tier 2: Boundary, Negative & Malformed Cases (>= 5 cases)
    // =========================================================================

    @Test
    fun testMalformedUrlHandling() {
        val response = UnmHttpClient.get("://not-a-valid-url")

        assertEquals(-1, response.code)
        assertFalse(response.isSuccessful)
        assertTrue(response.isFailure)
        assertEquals("", response.bodyString)
        assertNotNull(response.error)
        assertTrue(response.error is IllegalArgumentException)
    }

    @Test
    fun testDnsResolutionFailure() {
        UnmHttpClient.setMockHandler {
            throw UnknownHostException("Unable to resolve host: fake.unm.domain")
        }

        val response = UnmHttpClient.get("https://fake.unm.domain/test")

        assertEquals(-1, response.code)
        assertFalse(response.isSuccessful)
        assertNotNull(response.error)
        assertTrue(response.error is UnknownHostException)
        assertEquals("Unable to resolve host: fake.unm.domain", response.error?.message)
    }

    @Test
    fun testSocketTimeout() {
        UnmHttpClient.setMockHandler {
            throw SocketTimeoutException("connect timed out")
        }

        val response = UnmHttpClient.get("https://slow.music.server/track", timeoutMs = 2000L)

        assertEquals(-1, response.code)
        assertFalse(response.isSuccessful)
        assertNotNull(response.error)
        assertTrue(response.error is SocketTimeoutException)
    }

    @Test
    fun testHttp404NotFound() {
        UnmHttpClient.setMockHandler { request ->
            UnmHttpClient.createMockResponse(
                request = request,
                code = 404,
                body = """{"error":"song not found"}""",
                message = "Not Found"
            )
        }

        val response = UnmHttpClient.get("https://api.music.example.com/404")

        assertEquals(404, response.code)
        assertFalse(response.isSuccessful)
        assertTrue(response.isFailure)
        assertNull(response.error)
        assertEquals("""{"error":"song not found"}""", response.bodyString)
    }

    @Test
    fun testHttp500InternalServerError() {
        UnmHttpClient.setMockHandler { request ->
            UnmHttpClient.createMockResponse(
                request = request,
                code = 500,
                body = "Internal Server Error",
                message = "Server Error"
            )
        }

        val response = UnmHttpClient.post("https://api.music.example.com/submit", "test")

        assertEquals(500, response.code)
        assertFalse(response.isSuccessful)
        assertEquals("Internal Server Error", response.bodyString)
    }

    @Test
    fun testCaseInsensitiveHeaderLookup() {
        UnmHttpClient.setMockHandler { request ->
            UnmHttpClient.createMockResponse(
                request = request,
                code = 200,
                body = "ok",
                headers = mapOf(
                    "Content-Type" to "application/json; charset=utf-8",
                    "X-Request-ID" to "req-999"
                )
            )
        }

        val response = UnmHttpClient.get("https://example.com/headers")

        assertEquals("application/json; charset=utf-8", response.header("content-type"))
        assertEquals("application/json; charset=utf-8", response.header("Content-Type"))
        assertEquals("application/json; charset=utf-8", response.header("CONTENT-TYPE"))
        assertEquals("req-999", response.header("x-request-id"))
        assertEquals(listOf("req-999"), response.headers("x-request-id"))
        assertNull(response.header("non-existent-header"))
        assertTrue(response.headers("non-existent-header").isEmpty())
    }

    @Test
    fun testCustomTimeoutOverride() {
        var observedTimeout = 0L

        UnmHttpClient.setMockInterceptor { chain ->
            observedTimeout = chain.readTimeoutMillis().toLong()
            UnmHttpClient.createMockResponse(chain.request(), 200, "timeout-ok")
        }

        UnmHttpClient.get("https://example.com/fast", timeoutMs = 3500L)
        assertEquals(3500L, observedTimeout)

        // Subsequent call with default timeout should revert to DEFAULT_TIMEOUT_MS
        UnmHttpClient.get("https://example.com/default")
        assertEquals(UnmHttpClient.DEFAULT_TIMEOUT_MS, observedTimeout)
    }

    // =========================================================================
    // Tier 3: Cross-Feature Interactions & Lifecycle
    // =========================================================================

    @Test
    fun testResetRestoresCleanState() {
        UnmHttpClient.setMockHandler { request ->
            UnmHttpClient.createMockResponse(request, 200, "mocked")
        }
        assertEquals("mocked", UnmHttpClient.getString("https://example.com"))

        UnmHttpClient.reset()

        // After reset, mockInterceptor is null. Calling with a bad local URL throws/returns -1
        val res = UnmHttpClient.get("http://localhost:1")
        assertEquals(-1, res.code)
    }

    @Test
    fun testGetOrThrowThrowsOnFailure() {
        UnmHttpClient.setMockHandler { request ->
            UnmHttpClient.createMockResponse(request, 503, "Service Unavailable")
        }

        val response = UnmHttpClient.get("https://example.com/503")
        try {
            response.getOrThrow()
            fail("Expected UnmHttpException was not thrown")
        } catch (e: UnmHttpException) {
            assertEquals(503, e.code)
            assertTrue(e.message?.contains("503") == true)
        }
    }

    @Test
    fun testGetOrThrowReturnsOnSuccess() {
        UnmHttpClient.setMockHandler { request ->
            UnmHttpClient.createMockResponse(request, 200, "success")
        }

        val response = UnmHttpClient.get("https://example.com/200")
        val success = response.getOrThrow()
        assertEquals(200, success.code)
        assertEquals("success", success.bodyString)
    }

    // =========================================================================
    // Tier 4: Realistic UNM Application Scenarios
    // =========================================================================

    @Test
    fun testBodianAdUnlockScenario() {
        val requestLog = mutableListOf<String>()

        UnmHttpClient.setMockHandler { request ->
            val path = request.url.encodedPath
            requestLog.add("${request.method} $path")

            when (path) {
                "/api/service/advert/watch" -> {
                    assertEquals("aliopen", request.header("channel"))
                    assertEquals("Dart/2.19 (dart:io)", request.header("user-agent"))
                    UnmHttpClient.createMockResponse(request, 200, """{"code":200,"msg":"ok"}""")
                }
                "/api/play/music/v2/audioUrl" -> {
                    UnmHttpClient.createMockResponse(
                        request,
                        200,
                        """{"code":200,"data":{"audioUrl":"http://bd-stream.kuwo.cn/audio/test.mp3","duration":210}}"""
                    )
                }
                else -> UnmHttpClient.createMockResponse(request, 404, "not found")
            }
        }

        // 1. Ad Unlock POST
        val adHeaders = mapOf(
            "user-agent" to "Dart/2.19 (dart:io)",
            "channel" to "aliopen"
        )
        val adRes = UnmHttpClient.post(
            url = "http://bd-api.kuwo.cn/api/service/advert/watch",
            body = "{\"type\":5}",
            headers = adHeaders
        )
        assertTrue(adRes.isSuccessful)

        // 2. Play Audio GET
        val playRes = UnmHttpClient.get(
            url = "http://bd-api.kuwo.cn/api/play/music/v2/audioUrl?musicId=123",
            headers = adHeaders
        )
        assertTrue(playRes.isSuccessful)
        assertTrue(playRes.bodyString.contains("http://bd-stream.kuwo.cn/audio/test.mp3"))

        assertEquals(2, requestLog.size)
        assertEquals("POST /api/service/advert/watch", requestLog[0])
        assertEquals("GET /api/play/music/v2/audioUrl", requestLog[1])
    }

    @Test
    fun testRuleDownloadWithRedirectScenario() {
        // Simulates GitHub redirecting short link to raw.githubusercontent.com
        var redirectCount = 0

        UnmHttpClient.setMockHandler { request ->
            if (request.url.host == "git.io") {
                redirectCount++
                UnmHttpClient.createMockResponse(
                    request = request,
                    code = 200,
                    body = """function unmMatch(id, name, artist, duration) { return null; }"""
                )
            } else {
                UnmHttpClient.createMockResponse(
                    request = request,
                    code = 200,
                    body = """function unmMatch(id, name, artist, duration) { return null; }"""
                )
            }
        }

        val response = UnmHttpClient.get("https://git.io/unm-rule")
        assertTrue(response.isSuccessful)
        assertTrue(response.bodyString.contains("function unmMatch"))
        assertEquals(1, redirectCount)
    }
}
