package pochita.common

import com.sun.net.httpserver.HttpServer
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.IOException
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.ProtocolException
import java.net.Socket
import java.net.SocketTimeoutException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * Adversarial Testing Suite for [UnmHttpClient].
 *
 * Stresses:
 * 1. Real HTTP Server integration via standard JDK [HttpServer].
 * 2. Redirect cycles (302 loop) and OkHttp redirect limits.
 * 3. Timeout enforcement (connect/read timeouts under slow servers).
 * 4. Boundary cases: negative timeout, zero timeout, malformed URLs, empty/null payloads.
 * 5. Socket abrupt drops / truncated response streams.
 * 6. High-concurrency connection pool contention (50 threads).
 * 7. Error isolation against arbitrary runtime exceptions and JVM errors.
 */
class UnmHttpClientAdversarialTest {

    private var localServer: HttpServer? = null
    private var serverPort: Int = 0

    @Before
    fun setUp() {
        UnmHttpClient.reset()
    }

    @After
    fun tearDown() {
        UnmHttpClient.reset()
        localServer?.stop(0)
        localServer = null
    }

    private fun startLocalServer(handlerConfig: (HttpServer) -> Unit): String {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.executor = Executors.newCachedThreadPool()
        handlerConfig(server)
        server.start()
        localServer = server
        serverPort = server.address.port
        return "http://127.0.0.1:$serverPort"
    }

    // =========================================================================
    // 1. Redirect Cycle & OkHttp Redirect Limit Handling
    // =========================================================================

    @Test
    fun testRedirectCycleTerminatesWithFailureCode() {
        var port = 0
        val baseUrl = startLocalServer { server ->
            port = server.address.port
            server.createContext("/loop1") { exchange ->
                exchange.responseHeaders.add("Location", "http://127.0.0.1:$port/loop2")
                exchange.sendResponseHeaders(302, -1)
                exchange.close()
            }
            server.createContext("/loop2") { exchange ->
                exchange.responseHeaders.add("Location", "http://127.0.0.1:$port/loop1")
                exchange.sendResponseHeaders(302, -1)
                exchange.close()
            }
        }

        val response = UnmHttpClient.get("$baseUrl/loop1")

        // OkHttp follows redirects up to 20 times, then throws ProtocolException("Too many follow-up requests: 21")
        assertEquals(-1, response.code)
        assertFalse(response.isSuccessful)
        assertTrue(response.isFailure)
        assertNotNull(response.error)
        assertTrue(
            "Expected ProtocolException on redirect cycle, but got: ${response.error?.javaClass}",
            response.error is ProtocolException
        )
        assertTrue(response.error?.message?.contains("follow-up") == true)
    }

    // =========================================================================
    // 2. Real Socket Timeout Enforcement
    // =========================================================================

    @Test
    fun testReadTimeoutEnforcedOnSlowServer() {
        val baseUrl = startLocalServer { server ->
            server.createContext("/slow") { exchange ->
                // Sleep for 3 seconds before responding
                try {
                    Thread.sleep(3000)
                } catch (_: InterruptedException) {}
                val body = "too-late".toByteArray()
                exchange.sendResponseHeaders(200, body.size.toLong())
                exchange.responseBody.write(body)
                exchange.close()
            }
        }

        val startTime = System.currentTimeMillis()
        val response = UnmHttpClient.get("$baseUrl/slow", timeoutMs = 800L)
        val elapsed = System.currentTimeMillis() - startTime

        assertEquals(-1, response.code)
        assertFalse(response.isSuccessful)
        assertNotNull(response.error)
        assertTrue(
            "Expected SocketTimeoutException, got: ${response.error?.javaClass}",
            response.error is SocketTimeoutException
        )
        assertTrue("Timeout must abort within 2.5s, took: ${elapsed}ms", elapsed < 2500L)
    }

    @Test
    fun testNegativeTimeoutIsHandledGracefullyWithoutCrashing() {
        // OkHttp Builder throws IllegalStateException for negative durations: timeout < 0
        val response = UnmHttpClient.get("http://127.0.0.1:1/invalid", timeoutMs = -100L)
        assertEquals(-1, response.code)
        assertFalse(response.isSuccessful)
        assertNotNull(response.error)
        assertTrue(response.error is IllegalStateException || response.error is IllegalArgumentException)
    }

    // =========================================================================
    // 3. Boundary & Malformed Inputs
    // =========================================================================

    @Test
    fun testEmptyUrlAndNonHttpProtocols() {
        val emptyRes = UnmHttpClient.get("")
        assertEquals(-1, emptyRes.code)
        assertTrue(emptyRes.error is IllegalArgumentException)

        val ftpRes = UnmHttpClient.get("ftp://files.example.com/song.mp3")
        assertEquals(-1, ftpRes.code)
        assertTrue(ftpRes.error is IllegalArgumentException)

        val fileRes = UnmHttpClient.get("file:///etc/hosts")
        assertEquals(-1, fileRes.code)
        assertTrue(fileRes.error is IllegalArgumentException)

        // Truly malformed URL (scheme with no host)
        val noHostRes = UnmHttpClient.get("https://")
        assertEquals(-1, noHostRes.code)
        assertTrue(noHostRes.error is IllegalArgumentException)
    }

    @Test
    fun testEmptyAndBinaryPayloads() {
        val baseUrl = startLocalServer { server ->
            server.createContext("/echo") { exchange ->
                val bytes = exchange.requestBody.readBytes()
                val contentType = exchange.requestHeaders.getFirst("Content-Type") ?: "none"
                exchange.responseHeaders.add("X-Echo-Type", contentType)
                exchange.sendResponseHeaders(200, bytes.size.toLong())
                exchange.responseBody.write(bytes)
                exchange.close()
            }
        }

        // Empty byte array
        val emptyBytes = ByteArray(0)
        val emptyRes = UnmHttpClient.post("$baseUrl/echo", emptyBytes, contentType = "application/octet-stream")
        assertEquals(200, emptyRes.code)
        assertEquals(0, emptyRes.bodyBytes.size)
        assertEquals("", emptyRes.bodyString)

        // Binary payload with null bytes and high-order bytes
        val binaryData = byteArrayOf(0x00, 0x01, 0x7F, 0x80.toByte(), 0xFF.toByte())
        val binaryRes = UnmHttpClient.post("$baseUrl/echo", binaryData, contentType = "application/octet-stream")
        assertEquals(200, binaryRes.code)
        assertArrayEquals(binaryData, binaryRes.bodyBytes)
    }

    @Test
    fun testLargePayloadTransfer() {
        val baseUrl = startLocalServer { server ->
            server.createContext("/large") { exchange ->
                val body = exchange.requestBody.readBytes()
                exchange.sendResponseHeaders(200, body.size.toLong())
                exchange.responseBody.write(body)
                exchange.close()
            }
        }

        // 1 MB binary payload
        val largeBytes = ByteArray(1024 * 1024) { (it % 256).toByte() }
        val res = UnmHttpClient.post("$baseUrl/large", largeBytes, contentType = "application/octet-stream")
        assertEquals(200, res.code)
        assertEquals(1024 * 1024, res.bodyBytes.size)
        assertArrayEquals(largeBytes, res.bodyBytes)
    }

    // =========================================================================
    // 4. Abrupt Stream Termination & Truncation
    // =========================================================================

    @Test
    fun testTruncatedBodyIsHandledWithoutCrash() {
        // Open a raw ServerSocket that sends HTTP headers declaring Content-Length: 1000
        // but closes the connection after sending 10 bytes
        val serverSocket = java.net.ServerSocket(0)
        val port = serverSocket.localPort

        val thread = Thread {
            try {
                val client = serverSocket.accept()
                val reader = client.getInputStream().bufferedReader()
                // Read request headers
                while (true) {
                    val line = reader.readLine()
                    if (line.isNullOrEmpty()) break
                }
                val out = client.getOutputStream()
                out.write("HTTP/1.1 200 OK\r\nContent-Length: 1000\r\n\r\n1234567890".toByteArray())
                out.flush()
                client.close() // abrupt termination before 1000 bytes
            } catch (_: Exception) {
            } finally {
                serverSocket.close()
            }
        }
        thread.start()

        val response = UnmHttpClient.get("http://127.0.0.1:$port/truncated", timeoutMs = 2000L)
        // OkHttp detects unexpected EOF / premature close
        assertEquals(-1, response.code)
        assertFalse(response.isSuccessful)
        assertNotNull(response.error)
        assertTrue("Error should be IOException: ${response.error?.javaClass}", response.error is IOException)
    }

    // =========================================================================
    // 5. High-Concurrency Stress (50 Threads against Real Server)
    // =========================================================================

    @Test
    fun testHighConcurrencyAgainstRealServer() {
        val requestCount = AtomicInteger(0)
        val baseUrl = startLocalServer { server ->
            server.createContext("/concurrent") { exchange ->
                val count = requestCount.incrementAndGet()
                val resp = "resp-$count".toByteArray()
                exchange.sendResponseHeaders(200, resp.size.toLong())
                exchange.responseBody.write(resp)
                exchange.close()
            }
        }

        val threadCount = 50
        val requestsPerThread = 20
        val totalRequests = threadCount * requestsPerThread
        val executor = Executors.newFixedThreadPool(threadCount)
        val latch = CountDownLatch(threadCount)
        val failedRequests = ConcurrentHashMap<Int, Throwable>()
        val successCount = AtomicInteger(0)

        for (t in 0 until threadCount) {
            val threadId = t
            executor.execute {
                try {
                    for (i in 0 until requestsPerThread) {
                        val res = UnmHttpClient.get("$baseUrl/concurrent")
                        if (res.isSuccessful && res.code == 200) {
                            successCount.incrementAndGet()
                        } else {
                            failedRequests[threadId * 1000 + i] = res.error ?: IOException("HTTP ${res.code}")
                        }
                    }
                } catch (t: Throwable) {
                    failedRequests[threadId] = t
                } finally {
                    latch.countDown()
                }
            }
        }

        assertTrue("50-thread concurrent HTTP stress timed out", latch.await(15, TimeUnit.SECONDS))
        executor.shutdown()

        assertTrue("Failed requests during concurrency stress: $failedRequests", failedRequests.isEmpty())
        assertEquals(totalRequests, successCount.get())
        assertEquals(totalRequests, requestCount.get())
    }

    // =========================================================================
    // 6. Error & Exception Isolation in Mock Handlers
    // =========================================================================

    @Test
    fun testMockHandlerThrowingRuntimeExceptionNeverEscapes() {
        UnmHttpClient.setMockHandler {
            throw RuntimeException("Simulated catastrophic crash inside mock handler")
        }

        val response = UnmHttpClient.get("https://music.example.com/api")
        assertEquals(-1, response.code)
        assertFalse(response.isSuccessful)
        assertNotNull(response.error)
        assertTrue(response.error is RuntimeException)
        assertEquals("Simulated catastrophic crash inside mock handler", response.error?.message)
    }

    @Test
    fun testMockHandlerThrowingJvmErrorIsCaughtSilently() {
        UnmHttpClient.setMockHandler {
            throw OutOfMemoryError("Simulated JVM OOM in network interceptor")
        }

        val response = UnmHttpClient.post("https://music.example.com/api", "payload")
        assertEquals(-1, response.code)
        assertFalse(response.isSuccessful)
        assertNotNull(response.error)
        assertTrue(response.error is OutOfMemoryError)
        assertEquals("Simulated JVM OOM in network interceptor", response.error?.message)
    }

    @Test
    fun testMockResponseWithNullBodyBytes() {
        // OkHttp requires non-null ResponseBody; if an interceptor returns null body,
        // OkHttp throws IllegalStateException. UnmHttpClient must isolate this and return -1.
        UnmHttpClient.setMockInterceptor { chain ->
            Response.Builder()
                .request(chain.request())
                .protocol(Protocol.HTTP_1_1)
                .code(204)
                .message("No Content")
                .body(null)
                .build()
        }

        val response = UnmHttpClient.get("https://example.com/no-content")
        assertEquals(-1, response.code)
        assertFalse(response.isSuccessful)
        assertNotNull(response.error)
        assertTrue(response.error is IllegalStateException)

        // Valid HTTP 204 No Content with empty body
        UnmHttpClient.setMockHandler { request ->
            UnmHttpClient.createMockResponse(request, code = 204, body = "")
        }
        val valid204 = UnmHttpClient.get("https://example.com/valid-204")
        assertEquals(204, valid204.code)
        assertTrue(valid204.isSuccessful)
        assertEquals(0, valid204.bodyBytes.size)
        assertEquals("", valid204.bodyString)
    }
}
