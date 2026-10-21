package pochita.source

import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import pochita.common.UnmHttpClient
import pochita.common.UnmLogger
import pochita.model.SongInfo

class QuickJsEngineTest {

    @Before
    fun setUp() {
        UnmHttpClient.reset()
        QuickJsEngine.resetForTesting()
    }

    @After
    fun tearDown() {
        QuickJsEngine.resetForTesting()
        UnmHttpClient.reset()
    }

    // =========================================================================
    // 1. Native Bridge Unit Tests (100% JVM runnable without native library)
    // =========================================================================

    @Test
    fun testBridgeHttpGetSuccess() {
        UnmHttpClient.setMockHandler { req ->
            assertEquals("https://api.example.com/search?q=test", req.url.toString())
            assertEquals("CustomUA/1.0", req.header("User-Agent"))
            assertEquals("bar", req.header("X-Foo"))
            UnmHttpClient.createMockResponse(req, code = 200, body = """{"code":200,"data":"ok"}""")
        }

        val args = arrayOf<Any?>(
            "https://api.example.com/search?q=test",
            "CustomUA/1.0",
            """{"X-Foo":"bar"}"""
        )

        val result = QuickJsEngine.bridgeHttpGet(args)
        assertEquals("""{"code":200,"data":"ok"}""", result)
    }

    @Test
    fun testBridgeHttpGetHttpError() {
        UnmHttpClient.setMockHandler { req ->
            UnmHttpClient.createMockResponse(req, code = 404, body = "Not Found")
        }

        val args = arrayOf<Any?>("https://api.example.com/missing", null, null)
        val result = QuickJsEngine.bridgeHttpGet(args)
        assertEquals("", result)
    }

    @Test
    fun testBridgeHttpGetNetworkException() {
        UnmHttpClient.setMockHandler { _ ->
            throw IOException("Simulated network timeout")
        }

        val args = arrayOf<Any?>("https://api.example.com/error", null, null)
        val result = QuickJsEngine.bridgeHttpGet(args)
        assertEquals("", result)
    }

    @Test
    fun testBridgeHttpPostSuccess() {
        UnmHttpClient.setMockHandler { req ->
            assertEquals("https://api.example.com/watch", req.url.toString())
            assertEquals("application/json; charset=utf-8", req.body?.contentType()?.toString())
            assertEquals("Dart/2.19", req.header("User-Agent"))
            assertEquals("1.0.1.114", req.header("X-Forwarded-For"))
            UnmHttpClient.createMockResponse(req, code = 200, body = """{"status":"success"}""")
        }

        val args = arrayOf<Any?>(
            "https://api.example.com/watch",
            """{"type":5,"musicId":123}""",
            """{"X-Forwarded-For":"1.0.1.114"}""",
            "Dart/2.19"
        )

        val result = QuickJsEngine.bridgeHttpPost(args)
        assertEquals("""{"status":"success"}""", result)
    }

    @Test
    fun testBridgeHttpPostMalformedHeaders() {
        UnmHttpClient.setMockHandler { req ->
            UnmHttpClient.createMockResponse(req, code = 200, body = "OK")
        }

        val args = arrayOf<Any?>(
            "https://api.example.com/post",
            "test body",
            "NOT_VALID_JSON{:::}",
            "TestUA"
        )

        val result = QuickJsEngine.bridgeHttpPost(args)
        assertEquals("OK", result)
    }

    @Test
    fun testBridgeMd5() {
        val hash = QuickJsEngine.bridgeMd5(arrayOf<Any?>("hello world"))
        assertEquals("5eb63bbbe01eeed093cb22bb8f5acdc3", hash)

        val emptyHash = QuickJsEngine.bridgeMd5(arrayOf<Any?>(""))
        assertEquals("d41d8cd98f00b204e9800998ecf8427e", emptyHash)
    }

    @Test
    fun testBridgeNullOrEmptyArgs() {
        assertEquals("", QuickJsEngine.bridgeHttpGet(emptyArray()))
        assertEquals("", QuickJsEngine.bridgeHttpGet(arrayOf(null)))
        assertEquals("", QuickJsEngine.bridgeHttpPost(emptyArray()))
        assertEquals("", QuickJsEngine.bridgeHttpPost(arrayOf(null)))
        assertEquals("", QuickJsEngine.bridgeMd5(emptyArray()))
        assertEquals("", QuickJsEngine.bridgeMd5(arrayOf(null)))
    }

    // =========================================================================
    // 2. Lifecycle & State Machine Tests
    // =========================================================================

    @Test
    fun testInitialState() {
        assertFalse(QuickJsEngine.hasScript())
        assertFalse(QuickJsEngine.isReady())
        val song = SongInfo(id = 1L, name = "Song", artist = "Artist")
        assertNull(QuickJsEngine.match(song))
    }

    @Test
    fun testLoadBlankScript() {
        assertFalse(QuickJsEngine.loadScript(""))
        assertFalse(QuickJsEngine.loadScript("   \n\t  "))
        assertFalse(QuickJsEngine.hasScript())
    }

    @Test
    fun testCloseCleanup() {
        val script = """
            function unmMatch(id, name, artist, duration) {
                return { url: "http://example.com/audio.mp3" };
            }
        """.trimIndent()

        QuickJsEngine.loadScript(script)
        assertTrue(QuickJsEngine.hasScript())

        QuickJsEngine.close()
        assertFalse(QuickJsEngine.isReady())
        assertNull(QuickJsEngine.match(SongInfo(1L, "N", "A")))
    }

    @Test
    fun testScriptReloading() {
        if (QuickJsEngine.isNativeUnavailable()) {
            println("Skipping testScriptReloading: Native QuickJS unavailable on this JVM.")
            return
        }

        val scriptA = """
            function unmMatch(id, name, artist, duration) {
                return { url: "http://example.com/a.mp3", source: "source-a" };
            }
        """.trimIndent()

        val scriptB = """
            function unmMatch(id, name, artist, duration) {
                return { url: "http://example.com/b.mp3", source: "source-b" };
            }
        """.trimIndent()

        assertTrue(QuickJsEngine.loadScript(scriptA))
        val resA = QuickJsEngine.match(SongInfo(1L, "Test", "Singer"))
        assertNotNull(resA)
        assertEquals("source-a", resA?.source)

        // Reload with script B
        assertTrue(QuickJsEngine.loadScript(scriptB))
        val resB = QuickJsEngine.match(SongInfo(1L, "Test", "Singer"))
        assertNotNull(resB)
        assertEquals("source-b", resB?.source)
    }

    @Test
    fun testValidateScriptDryRun() {
        val validScript = """
            function unmMatch(id, name, artist, duration) {
                return null;
            }
        """.trimIndent()

        val invalidScript = "function broken( { {;"

        assertTrue(QuickJsEngine.validateScript(validScript))
        if (!QuickJsEngine.isNativeUnavailable()) {
            assertFalse(QuickJsEngine.validateScript(invalidScript))
        }
    }

    // =========================================================================
    // 3. Concurrency & Thread Safety Tests
    // =========================================================================

    @Test
    fun testConcurrentMatchExecution() {
        if (QuickJsEngine.isNativeUnavailable()) {
            println("Skipping testConcurrentMatchExecution: Native QuickJS unavailable on this JVM.")
            return
        }

        val script = """
            function unmMatch(id, name, artist, duration) {
                return { url: "http://example.com/" + id + ".mp3", br: 320000, source: "mock-js" };
            }
        """.trimIndent()

        assertTrue(QuickJsEngine.loadScript(script))

        val threadCount = 10
        val iterationsPerThread = 15
        val executor = Executors.newFixedThreadPool(threadCount)
        val latch = CountDownLatch(threadCount)
        val successCount = AtomicInteger(0)

        for (t in 0 until threadCount) {
            executor.submit {
                try {
                    for (i in 0 until iterationsPerThread) {
                        val songId = (t * 1000 + i).toLong()
                        val song = SongInfo(id = songId, name = "Name$i", artist = "Artist$t")
                        val match = QuickJsEngine.match(song)
                        if (match != null && match.url == "http://example.com/$songId.mp3") {
                            successCount.incrementAndGet()
                        }
                    }
                } finally {
                    latch.countDown()
                }
            }
        }

        assertTrue(latch.await(10, TimeUnit.SECONDS))
        executor.shutdown()
        assertEquals(threadCount * iterationsPerThread, successCount.get())
    }

    // =========================================================================
    // 4. Fallback & Resilience Tests
    // =========================================================================

    @Test
    fun testGracefulFallbackWhenNativeUnavailable() {
        val script = "function unmMatch() { return null; }"
        QuickJsEngine.loadScript(script)

        // Regardless of native availability, match() must never throw unhandled exceptions
        val song = SongInfo(1001L, "Any", "Any")
        val result = QuickJsEngine.match(song)
        if (QuickJsEngine.isNativeUnavailable()) {
            assertNull(result)
        }
    }

    @Test
    fun testExecutionTimeoutProtection() {
        if (QuickJsEngine.isNativeUnavailable()) {
            println("Skipping testExecutionTimeoutProtection: Native QuickJS unavailable.")
            return
        }

        val infiniteLoopScript = """
            function unmMatch(id, name, artist, duration) {
                var start = Date.now();
                while (true) {
                    // Burn CPU until timeout interrupts
                }
                return null;
            }
        """.trimIndent()

        assertTrue(QuickJsEngine.loadScript(infiniteLoopScript))
        val startTime = System.currentTimeMillis()
        val result = QuickJsEngine.match(SongInfo(1L, "Freeze", "Singer"))
        val duration = System.currentTimeMillis() - startTime

        assertNull(result)
        // Verify timeout interrupted within a reasonable window (~5000ms limit + slack)
        assertTrue("Execution should be aborted within 7.5 seconds, took $duration ms", duration < 7500L)
    }

    @Test
    fun testFullMatchFlowWithMockedNetwork() {
        if (QuickJsEngine.isNativeUnavailable()) {
            println("Skipping testFullMatchFlowWithMockedNetwork: Native QuickJS unavailable.")
            return
        }

        UnmHttpClient.setMockHandler { req ->
            when {
                req.url.toString().contains("search.kuwo.cn") -> {
                    val body = """
                        {
                            "content": [
                                {},
                                {
                                    "musicpage": {
                                        "abslist": [
                                            { "MUSICRID": "MUSIC_12345" }
                                        ]
                                    }
                                }
                            ]
                        }
                    """.trimIndent()
                    UnmHttpClient.createMockResponse(req, code = 200, body = body)
                }
                req.url.toString().contains("antiserver.kuwo.cn") -> {
                    UnmHttpClient.createMockResponse(req, code = 200, body = "http://kuwo.cdn/audio12345.mp3")
                }
                else -> UnmHttpClient.createMockResponse(req, code = 404, body = "")
            }
        }

        val ruleScript = """
            function unmMatch(id, name, artist, duration) {
                var searchRes = nativeBridge.httpGet('http://search.kuwo.cn/search?q=' + encodeURIComponent(name), 'okhttp/3.10.0');
                if (!searchRes) return null;
                var json = JSON.parse(searchRes);
                var rid = json.content[1].musicpage.abslist[0].MUSICRID.replace('MUSIC_', '');
                var trackRes = nativeBridge.httpGet('http://antiserver.kuwo.cn/track?rid=' + rid, 'okhttp/3.10.0');
                if (trackRes) {
                    return { url: trackRes, br: 320000, source: 'kuwo-js' };
                }
                return null;
            }
        """.trimIndent()

        assertTrue(QuickJsEngine.loadScript(ruleScript))
        val matched = QuickJsEngine.match(SongInfo(99L, "TestSong", "TestArtist"))
        assertNotNull(matched)
        assertEquals("http://kuwo.cdn/audio12345.mp3", matched?.url)
        assertEquals(320000, matched?.br)
        assertEquals("kuwo-js", matched?.source)
    }
}
