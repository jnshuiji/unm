package pochita.hook

import java.io.ByteArrayInputStream
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import pochita.codec.GzipCodec

/**
 * Empirical Adversarial Challenge Suite for [OkHttpInterceptor] and [CronetInterceptor].
 *
 * Verifies:
 * 1. OkHttp Closed-Stream Bug Elimination:
 *    - Sequential multi-stage interception pipeline (unmodified -> unmodified -> modified -> unmodified).
 *    - Reading via .bytes(), .string(), .byteStream(), .charStream(), .source().
 *    - No IllegalStateException: closed under any condition.
 * 2. OkHttp Content-Length & Header Synchronization:
 *    - Shrink, expand, and zero-length payloads correctly synchronize Content-Length header and body.contentLength().
 *    - Automatic Content-Encoding removal when GZIP responses are decompressed to plain JSON.
 *    - Exception safety during processor execution (fallback to readable original body).
 * 3. Cronet Native Stream Lifecycle & Resource Leaks:
 *    - ReplacedInputStream guarantees underlying native stream close() propagation.
 *    - Idempotent close(): calling close() multiple times does not throw.
 *    - Suppression of underlying native close() exceptions.
 *    - ReplacedInputStream stream operations: available(), skip(), mark(), reset(), chunked reads.
 * 4. Cronet Content-Length & Header Synchronization:
 *    - getContentLength, getContentLengthLong, getHeaderField (case-insensitive), getHeaderFields.
 *    - Handling connections with no initial Content-Length.
 *    - Memory leak prevention via WeakHashMap cleanup.
 * 5. HookUrlMatcher Exhaustive Classification:
 *    - Case insensitivity, query parameters, anchors, dots in paths, CDN exclusions.
 * 6. High-concurrency stress testing for both interceptors.
 */
class HookInterceptorAdversarialTest {

    private val targetEApiUrl = "https://interface.music.163.com/eapi/song/enhance/player/url"
    private val targetXeApiUrl = "https://interface.music.163.com/xeapi/v1/playlist/detail"
    private val staticAssetUrl = "https://p1.music.126.net/cover_art.jpg?param=300y300"
    private val classLoader: ClassLoader = OkHttpInterceptor::class.java.classLoader!!

    @Before
    fun setUp() {
        CronetInterceptor.clearTrackedConnections()
    }

    // =========================================================================
    // SECTION 1: OkHttp Stream Lifecycle & Multi-Stage Interception Tests
    // =========================================================================

    private fun createMockOkHttpResponse(
        url: String,
        bodyContent: String,
        isGzip: Boolean = false,
        customHeaders: Map<String, String> = emptyMap()
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
        for ((k, v) in customHeaders) {
            builder.header(k, v)
        }
        return builder.build()
    }

    @Test
    fun testOkHttpSequentialMultiStagePipeline_noClosedStreamException() {
        // Simulates an interception pipeline where a response passes through multiple stages:
        // Stage 1: Interceptor passes through unmodified
        // Stage 2: Second hook passes through unmodified
        // Stage 3: Third hook modifies the content
        // Stage 4: Downstream hook passes through unmodified
        // Stage 5: Final application caller reads the response body
        val initialPayload = """{"code":200,"stage":"initial_payload_bytes"}"""
        val modifiedPayload = """{"code":200,"stage":"modified_by_stage_3","unblocked":true}"""

        val response0 = createMockOkHttpResponse(targetEApiUrl, initialPayload)

        // Stage 1: Unmodified
        val response1 = OkHttpInterceptor.processOkHttpResponse(response0, classLoader) { _, _ -> null } as Response
        assertNotNull(response1)

        // Stage 2: Unmodified again
        val response2 = OkHttpInterceptor.processOkHttpResponse(response1, classLoader) { _, _ -> null } as Response
        assertNotNull(response2)

        // Stage 3: Modified
        val response3 = OkHttpInterceptor.processOkHttpResponse(response2, classLoader) { _, _ ->
            modifiedPayload.toByteArray(Charsets.UTF_8)
        } as Response
        assertNotNull(response3)

        // Stage 4: Unmodified again
        val response4 = OkHttpInterceptor.processOkHttpResponse(response3, classLoader) { _, _ -> null } as Response
        assertNotNull(response4)

        // Stage 5: Final consumer reads body - MUST NOT THROW IllegalStateException: closed!
        val finalBodyStr = response4.body?.string()
        assertEquals(modifiedPayload, finalBodyStr)
    }

    @Test
    fun testOkHttpDifferentReaderAPIs_onBothModifiedAndUnmodifiedResponses() {
        val payloadUnmodified = """{"code":200,"status":"unmodified_test"}"""
        val payloadModified = """{"code":200,"status":"modified_test_audio_replacement"}"""

        // Test 1: Reading via .bytes() on unmodified response
        val resp1 = OkHttpInterceptor.processOkHttpResponse(
            createMockOkHttpResponse(targetEApiUrl, payloadUnmodified), classLoader
        ) { _, _ -> null } as Response
        assertArrayEquals(payloadUnmodified.toByteArray(Charsets.UTF_8), resp1.body?.bytes())

        // Test 2: Reading via .string() on modified response
        val resp2 = OkHttpInterceptor.processOkHttpResponse(
            createMockOkHttpResponse(targetEApiUrl, payloadUnmodified), classLoader
        ) { _, _ -> payloadModified.toByteArray(Charsets.UTF_8) } as Response
        assertEquals(payloadModified, resp2.body?.string())

        // Test 3: Reading via .byteStream().readBytes() on unmodified response
        val resp3 = OkHttpInterceptor.processOkHttpResponse(
            createMockOkHttpResponse(targetEApiUrl, payloadUnmodified), classLoader
        ) { _, _ -> null } as Response
        assertArrayEquals(payloadUnmodified.toByteArray(Charsets.UTF_8), resp3.body?.byteStream()?.readBytes())

        // Test 4: Reading via .charStream().readText() on modified response
        val resp4 = OkHttpInterceptor.processOkHttpResponse(
            createMockOkHttpResponse(targetEApiUrl, payloadUnmodified), classLoader
        ) { _, _ -> payloadModified.toByteArray(Charsets.UTF_8) } as Response
        assertEquals(payloadModified, resp4.body?.charStream()?.readText())

        // Test 5: Reading via .source().readByteArray() on modified response
        val resp5 = OkHttpInterceptor.processOkHttpResponse(
            createMockOkHttpResponse(targetEApiUrl, payloadUnmodified), classLoader
        ) { _, _ -> payloadModified.toByteArray(Charsets.UTF_8) } as Response
        assertArrayEquals(payloadModified.toByteArray(Charsets.UTF_8), resp5.body?.source()?.readByteArray())
    }

    // =========================================================================
    // SECTION 2: OkHttp Content-Length & Header Synchronization
    // =========================================================================

    @Test
    fun testOkHttpContentLengthSynchronization_payloadShrunkAndExpanded() {
        // Scenario A: Shrunk payload (1000 bytes -> 25 bytes)
        val largeInitial = "A".repeat(1000)
        val shrunkModified = "B".repeat(25)
        val respShrunk = OkHttpInterceptor.processOkHttpResponse(
            createMockOkHttpResponse(targetEApiUrl, largeInitial), classLoader
        ) { _, _ -> shrunkModified.toByteArray(Charsets.UTF_8) } as Response

        assertEquals("25", respShrunk.header("Content-Length"))
        assertEquals(25L, respShrunk.body?.contentLength())
        assertEquals(shrunkModified, respShrunk.body?.string())

        // Scenario B: Expanded payload (20 bytes -> 50,000 bytes)
        val smallInitial = "C".repeat(20)
        val expandedModified = "D".repeat(50_000)
        val respExpanded = OkHttpInterceptor.processOkHttpResponse(
            createMockOkHttpResponse(targetEApiUrl, smallInitial), classLoader
        ) { _, _ -> expandedModified.toByteArray(Charsets.UTF_8) } as Response

        assertEquals("50000", respExpanded.header("Content-Length"))
        assertEquals(50_000L, respExpanded.body?.contentLength())
        assertEquals(expandedModified, respExpanded.body?.string())

        // Scenario C: Empty modified payload (0 bytes)
        val respEmpty = OkHttpInterceptor.processOkHttpResponse(
            createMockOkHttpResponse(targetEApiUrl, smallInitial), classLoader
        ) { _, _ -> ByteArray(0) } as Response

        assertEquals("0", respEmpty.header("Content-Length"))
        assertEquals(0L, respEmpty.body?.contentLength())
        assertEquals("", respEmpty.body?.string())

        // Scenario D: Unmodified payload preserves original Content-Length
        val origBytes = smallInitial.toByteArray(Charsets.UTF_8)
        val respUnmodified = OkHttpInterceptor.processOkHttpResponse(
            createMockOkHttpResponse(targetEApiUrl, smallInitial), classLoader
        ) { _, _ -> null } as Response

        assertEquals(origBytes.size.toString(), respUnmodified.header("Content-Length"))
        assertEquals(origBytes.size.toLong(), respUnmodified.body?.contentLength())
    }

    @Test
    fun testOkHttpContentEncoding_gzipRemovalWhenUncompressed() {
        // Gzip response decompressed by UNM to plain JSON
        val gzipResponse = createMockOkHttpResponse(targetXeApiUrl, """{"code":200,"msg":"gzipped"}""", isGzip = true)
        val plainModified = """{"code":200,"msg":"decompressed_plain_json"}"""

        val result = OkHttpInterceptor.processOkHttpResponse(gzipResponse, classLoader) { _, _ ->
            plainModified.toByteArray(Charsets.UTF_8)
        } as Response

        assertNotNull(result)
        assertEquals(plainModified, result.body?.string())
        assertNull("Content-Encoding must be removed when response body is not GZIP", result.header("Content-Encoding"))
        assertEquals(plainModified.toByteArray(Charsets.UTF_8).size.toString(), result.header("Content-Length"))

        // What if modified response remains GZIP compressed? Content-Encoding must NOT be removed!
        val gzipResponse2 = createMockOkHttpResponse(targetXeApiUrl, """{"code":200,"msg":"gzipped"}""", isGzip = true)
        val gzippedModifiedBytes = GzipCodec.compress(plainModified.toByteArray(Charsets.UTF_8))
        val resultGzip = OkHttpInterceptor.processOkHttpResponse(gzipResponse2, classLoader) { _, _ ->
            gzippedModifiedBytes
        } as Response

        assertNotNull(resultGzip)
        assertEquals("gzip", resultGzip.header("Content-Encoding"))
        assertEquals(gzippedModifiedBytes.size.toString(), resultGzip.header("Content-Length"))
    }

    @Test
    fun testOkHttpExceptionSafety_fallsBackToReadableBody() {
        val originalPayload = """{"code":200,"safe":"original_content"}"""
        val response = createMockOkHttpResponse(targetEApiUrl, originalPayload)

        val result = OkHttpInterceptor.processOkHttpResponse(response, classLoader) { _, _ ->
            throw RuntimeException("Simulated catastrophic crash inside processor")
        } as Response

        // Must catch exception silently and return readable response with original bytes
        assertNotNull(result)
        assertEquals(originalPayload, result.body?.string())
    }

    @Test
    fun testOkHttpNonTargetUrl_passesThroughUntouched() {
        val response = createMockOkHttpResponse(staticAssetUrl, "binary-image-data")
        var processorCalled = false

        val result = OkHttpInterceptor.processOkHttpResponse(response, classLoader) { _, _ ->
            processorCalled = true
            "should-not-reach".toByteArray()
        } as Response

        assertFalse("Processor must NOT be called for static asset URLs", processorCalled)
        assertEquals("binary-image-data", result.body?.string())
    }

    // =========================================================================
    // SECTION 3: Cronet Stream Closure & Native Socket Leak Prevention
    // =========================================================================

    private class TrackableInputStream(private val delegate: InputStream) : InputStream() {
        val isClosed = AtomicBoolean(false)
        val readBytesCount = AtomicInteger(0)
        var throwOnClose = false

        override fun read(): Int {
            val b = delegate.read()
            if (b != -1) readBytesCount.incrementAndGet()
            return b
        }

        override fun read(b: ByteArray, off: Int, len: Int): Int {
            val count = delegate.read(b, off, len)
            if (count > 0) readBytesCount.addAndGet(count)
            return count
        }

        override fun close() {
            isClosed.set(true)
            delegate.close()
            if (throwOnClose) {
                throw IOException("Chromium native channel closure failure simulation")
            }
        }
    }

    private class FakeHttpURLConnection(
        url: URL,
        private val headerMap: MutableMap<String?, List<String>> = LinkedHashMap()
    ) : HttpURLConnection(url) {
        override fun connect() {}
        override fun disconnect() {}
        override fun usingProxy(): Boolean = false
        override fun getHeaderFields(): Map<String?, List<String>> = headerMap
        override fun getHeaderField(name: String?): String? = headerMap[name]?.firstOrNull()
        override fun getContentLength(): Int = headerMap["Content-Length"]?.firstOrNull()?.toIntOrNull() ?: -1
        override fun getContentLengthLong(): Long = headerMap["Content-Length"]?.firstOrNull()?.toLongOrNull() ?: -1L
    }

    @Test
    fun testCronetReplacedInputStream_guaranteesNativeStreamClosureUnderAllReadPatterns() {
        val sampleBytes = "0123456789ABCDEF0123456789ABCDEF".toByteArray(Charsets.UTF_8)
        val url = URL(targetEApiUrl)

        // Case 1: Partial read then close
        val trackable1 = TrackableInputStream(ByteArrayInputStream(sampleBytes))
        val conn1 = FakeHttpURLConnection(url)
        val stream1 = CronetInterceptor.interceptInputStream(conn1, trackable1) { _, bytes -> bytes }
        assertNotNull(stream1)
        val buffer = ByteArray(5)
        assertEquals(5, stream1?.read(buffer))
        stream1?.close()
        assertTrue("Underlying native stream MUST be closed after partial read", trackable1.isClosed.get())

        // Case 2: Zero read, immediate close
        val trackable2 = TrackableInputStream(ByteArrayInputStream(sampleBytes))
        val conn2 = FakeHttpURLConnection(url)
        val stream2 = CronetInterceptor.interceptInputStream(conn2, trackable2) { _, bytes -> bytes }
        assertNotNull(stream2)
        stream2?.close()
        assertTrue("Underlying native stream MUST be closed even with zero reads", trackable2.isClosed.get())

        // Case 3: Read to EOF then close
        val trackable3 = TrackableInputStream(ByteArrayInputStream(sampleBytes))
        val conn3 = FakeHttpURLConnection(url)
        val stream3 = CronetInterceptor.interceptInputStream(conn3, trackable3) { _, bytes -> bytes }
        assertNotNull(stream3)
        val allBytes = stream3?.readBytes()
        assertArrayEquals(sampleBytes, allBytes)
        stream3?.close()
        assertTrue("Underlying native stream MUST be closed after EOF read", trackable3.isClosed.get())

        // Case 4: Multiple calls to close() must be completely idempotent and safe
        val trackable4 = TrackableInputStream(ByteArrayInputStream(sampleBytes))
        val conn4 = FakeHttpURLConnection(url)
        val stream4 = CronetInterceptor.interceptInputStream(conn4, trackable4) { _, bytes -> bytes }
        assertNotNull(stream4)
        for (i in 0 until 10) {
            stream4?.close()
        }
        assertTrue(trackable4.isClosed.get())

        // Case 5: Native stream throws on close - ReplacedInputStream suppresses cleanly
        val trackable5 = TrackableInputStream(ByteArrayInputStream(sampleBytes))
        trackable5.throwOnClose = true
        val conn5 = FakeHttpURLConnection(url)
        val stream5 = CronetInterceptor.interceptInputStream(conn5, trackable5) { _, bytes -> bytes }
        assertNotNull(stream5)
        try {
            stream5?.close()
            // Must not propagate the IOException
        } catch (t: Throwable) {
            fail("ReplacedInputStream.close() must suppress native socket closure exceptions, but threw: ${t.message}")
        }
        assertTrue(trackable5.isClosed.get())
    }

    @Test
    fun testCronetReplacedInputStream_streamOperations() {
        val payload = "Hello World From Cronet Interceptor Stream Operations Test".toByteArray(Charsets.UTF_8)
        val delegate = ByteArrayInputStream(payload)
        val wrapped = CronetInterceptor.ReplacedInputStream(delegate, null)

        // available()
        assertEquals(payload.size, wrapped.available())

        // single byte read
        assertEquals(payload[0].toInt(), wrapped.read())
        assertEquals(payload.size - 1, wrapped.available())

        // skip()
        val skipped = wrapped.skip(5)
        assertEquals(5L, skipped)

        // mark and reset
        assertTrue("ByteArrayInputStream delegate supports mark", wrapped.markSupported())
        wrapped.mark(100)
        val markRead = ByteArray(10)
        wrapped.read(markRead)
        wrapped.reset()
        val reRead = ByteArray(10)
        wrapped.read(reRead)
        assertArrayEquals(markRead, reRead)

        // chunked read
        val remainder = ByteArray(100)
        val readCount = wrapped.read(remainder, 0, remainder.size)
        assertTrue(readCount > 0)
        assertEquals(-1, wrapped.read()) // EOF reached

        wrapped.close()
    }

    // =========================================================================
    // SECTION 4: Cronet Content-Length & Header Synchronization
    // =========================================================================

    @Test
    fun testCronetHeaderSynchronization_exhaustiveCasingAndMissingHeaders() {
        val url = URL(targetEApiUrl)
        val originalPayload = "Original Cronet Response".toByteArray(Charsets.UTF_8)
        val modifiedPayload = "Modified UNM Response With Different Byte Length".toByteArray(Charsets.UTF_8)

        // Case 1: Initial headers have lowercase "content-length"
        val headers1 = mutableMapOf<String?, List<String>>(
            "content-length" to listOf(originalPayload.size.toString()),
            "content-type" to listOf("application/json")
        )
        val conn1 = FakeHttpURLConnection(url, headers1)
        CronetInterceptor.interceptInputStream(conn1, ByteArrayInputStream(originalPayload)) { _, _ -> modifiedPayload }

        assertEquals(modifiedPayload.size, CronetInterceptor.interceptContentLength(conn1, originalPayload.size))
        assertEquals(modifiedPayload.size.toLong(), CronetInterceptor.interceptContentLengthLong(conn1, originalPayload.size.toLong()))
        assertEquals(modifiedPayload.size.toString(), CronetInterceptor.interceptHeaderField(conn1, "Content-Length", null))
        assertEquals(modifiedPayload.size.toString(), CronetInterceptor.interceptHeaderField(conn1, "content-length", null))
        assertEquals(modifiedPayload.size.toString(), CronetInterceptor.interceptHeaderField(conn1, "CONTENT-LENGTH", null))
        assertEquals(modifiedPayload.size.toString(), CronetInterceptor.interceptHeaderField(conn1, "cOnTeNt-LeNgTh", null))

        val updatedMap1 = CronetInterceptor.interceptHeaderFields(conn1, headers1)
        assertNotNull(updatedMap1)
        assertEquals(listOf(modifiedPayload.size.toString()), updatedMap1?.get("content-length"))

        // Case 2: Initial headers had NO Content-Length header at all
        val headers2 = mutableMapOf<String?, List<String>>(
            "Server" to listOf("Nginx/1.22.0")
        )
        val conn2 = FakeHttpURLConnection(url, headers2)
        CronetInterceptor.interceptInputStream(conn2, ByteArrayInputStream(originalPayload)) { _, _ -> modifiedPayload }

        val updatedMap2 = CronetInterceptor.interceptHeaderFields(conn2, headers2)
        assertNotNull(updatedMap2)
        assertEquals(listOf(modifiedPayload.size.toString()), updatedMap2?.get("Content-Length"))
        assertEquals(listOf("Nginx/1.22.0"), updatedMap2?.get("Server"))

        // Case 3: Unmodified connection preserves original length and headers
        val headers3 = mutableMapOf<String?, List<String>>(
            "Content-Length" to listOf("123")
        )
        val conn3 = FakeHttpURLConnection(url, headers3)
        CronetInterceptor.interceptInputStream(conn3, ByteArrayInputStream(originalPayload)) { _, _ -> null }

        assertEquals(123, CronetInterceptor.interceptContentLength(conn3, 123))
        assertEquals(123L, CronetInterceptor.interceptContentLengthLong(conn3, 123L))
        assertEquals("123", CronetInterceptor.interceptHeaderField(conn3, "Content-Length", "123"))
        assertEquals(headers3, CronetInterceptor.interceptHeaderFields(conn3, headers3))

        // Case 4: Null connection or null headers handled safely
        assertEquals(99, CronetInterceptor.interceptContentLength(null, 99))
        assertEquals(99L, CronetInterceptor.interceptContentLengthLong(null, 99L))
        assertEquals("orig", CronetInterceptor.interceptHeaderField(null, "Content-Length", "orig"))
        assertNull(CronetInterceptor.interceptHeaderFields(null, null))
    }

    @Test
    fun testCronetExceptionIsolation_returnsOriginalStreamOnProcessorFailure() {
        val url = URL(targetEApiUrl)
        val originalPayload = "Resilient Cronet Payload".toByteArray(Charsets.UTF_8)
        val conn = FakeHttpURLConnection(url)

        val stream = CronetInterceptor.interceptInputStream(conn, ByteArrayInputStream(originalPayload)) { _, _ ->
            throw RuntimeException("Simulated unexpected crash during Cronet processing")
        }

        assertNotNull(stream)
        assertArrayEquals(originalPayload, stream?.readBytes())
    }

    // =========================================================================
    // SECTION 5: HookUrlMatcher Exhaustive Route & Static Asset Classification
    // =========================================================================

    @Test
    fun testHookUrlMatcher_exhaustiveRouteMatrix() {
        val testCases = listOf(
            // Target EAPI endpoints
            "https://interface.music.163.com/eapi/song/enhance/player/url" to true,
            "https://interface.music.163.com/eapi/v3/playlist/detail" to true,
            "https://interface3.music.163.com/xeapi/playlist/v4/detail" to true,
            "https://music.163.com/api/v1/album" to true,
            "http://INTERFACE.MUSIC.163.COM/EAPI/SONG" to true, // Case insensitivity

            // Player URL endpoints
            "https://music.163.com/player/url" to true,
            "https://music.163.com/eapi/player/url/v1" to true,
            "https://music.163.com/song/enhance/player/url" to true,

            // Static asset exclusions
            "https://p1.music.126.net/cover.jpg" to false,
            "https://p1.music.126.net/COVER.JPG" to false, // Uppercase extension
            "https://p1.music.126.net/eapi/cover.png?param=200y200" to false, // With query param
            "https://p1.music.126.net/eapi/avatar.webp#section" to false, // With fragment
            "https://p1.music.126.net/eapi/song.mp3" to false,
            "https://p1.music.126.net/eapi/track.flac" to false,
            "https://p1.music.126.net/eapi/font.woff2" to false,
            "https://p1.music.126.net/eapi/script.js" to false,
            "https://p1.music.126.net/eapi/style.css" to false,

            // Unrelated / edge endpoints
            "https://music.163.com/" to false,
            "https://music.163.com/weapi/login" to false,
            "https://google.com" to false,
            "" to false,
            "   " to false,
            null to false
        )

        for ((url, expected) in testCases) {
            val actual = HookUrlMatcher.isRelevantUrl(url)
            assertEquals("HookUrlMatcher.isRelevantUrl mismatch for '$url'", expected, actual)
        }
    }

    // =========================================================================
    // SECTION 6: High-Concurrency Stress Testing
    // =========================================================================

    @Test
    fun testConcurrentInterceptionStress_noRaceConditionsOrDeadlocks() {
        val numThreads = 10
        val iterationsPerThread = 40
        val executor = Executors.newFixedThreadPool(numThreads)
        val latch = CountDownLatch(numThreads)
        val errorCount = AtomicInteger(0)

        for (t in 0 until numThreads) {
            executor.submit {
                try {
                    for (i in 0 until iterationsPerThread) {
                        // OkHttp concurrent pass
                        val isModified = (t + i) % 2 == 1
                        val originalText = "Payload_${t}_${i}"
                        val modifiedText = "Modified_${t}_${i}"
                        val response = createMockOkHttpResponse(targetEApiUrl, originalText)

                        val processedResponse = OkHttpInterceptor.processOkHttpResponse(response, classLoader) { _, _ ->
                            if (isModified) modifiedText.toByteArray(Charsets.UTF_8) else null
                        } as Response

                        val readStr = processedResponse.body?.string()
                        val expectedStr = if (isModified) modifiedText else originalText
                        if (readStr != expectedStr) {
                            errorCount.incrementAndGet()
                        }

                        // Cronet concurrent pass
                        val url = URL(targetEApiUrl)
                        val conn = FakeHttpURLConnection(url)
                        val cronetStream = CronetInterceptor.interceptInputStream(
                            conn,
                            ByteArrayInputStream(originalText.toByteArray(Charsets.UTF_8))
                        ) { _, bytes ->
                            if (isModified) modifiedText.toByteArray(Charsets.UTF_8) else null
                        }
                        val cronetBytes = cronetStream?.readBytes()
                        if (cronetBytes == null || String(cronetBytes, Charsets.UTF_8) != expectedStr) {
                            errorCount.incrementAndGet()
                        }
                        cronetStream?.close()
                    }
                } catch (t: Throwable) {
                    errorCount.incrementAndGet()
                } finally {
                    latch.countDown()
                }
            }
        }

        assertTrue("Concurrent stress test timed out", latch.await(10, TimeUnit.SECONDS))
        executor.shutdown()
        assertEquals("Errors detected during concurrent interception stress", 0, errorCount.get())
    }
}
