package pochita.source

import java.net.SocketTimeoutException
import java.net.UnknownHostException
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import pochita.common.UnmHttpClient
import pochita.model.SongInfo
import pochita.rule.RuleManager

class NativeSourcesTest {

    @Before
    fun setUp() {
        UnmHttpClient.reset()
        AudioValidator.reset()
        RuleManager.resetForTest()
    }

    @After
    fun tearDown() {
        UnmHttpClient.reset()
        AudioValidator.reset()
        RuleManager.resetForTest()
    }

    // =========================================================================
    // Tier 1: Happy-Path Mocked Sound Source Matches (>= 5 cases)
    // =========================================================================

    @Test
    fun testBodianSourceMatchSuccess() {
        UnmHttpClient.setMockHandler { request ->
            val url = request.url.toString()
            when {
                url.contains("search.kuwo.cn") -> {
                    val searchJson = """
                        {
                            "content": [
                                {},
                                {
                                    "musicpage": {
                                        "abslist": [
                                            {
                                                "MUSICRID": "MUSIC_1001",
                                                "DURATION": 180
                                            }
                                        ]
                                    }
                                }
                            ]
                        }
                    """.trimIndent()
                    UnmHttpClient.createMockResponse(request, 200, searchJson)
                }
                url.contains("advert/watch") -> {
                    UnmHttpClient.createMockResponse(request, 200, """{"code":200,"message":"ok"}""")
                }
                url.contains("/api/play/music/v2/audioUrl") -> {
                    val trackJson = """
                        {
                            "code": 200,
                            "data": {
                                "audioUrl": "http://bd-stream.kuwo.cn/audio/1001.mp3",
                                "duration": 180
                            }
                        }
                    """.trimIndent()
                    UnmHttpClient.createMockResponse(request, 200, trackJson)
                }
                else -> UnmHttpClient.createMockResponse(request, 404, "not found")
            }
        }

        val song = SongInfo(id = 1L, name = "海阔天空", artist = "Beyond", durationMs = 180000L)
        val matched = BodianSource.match(song)

        assertNotNull(matched)
        assertEquals("http://bd-stream.kuwo.cn/audio/1001.mp3", matched?.url)
        assertEquals(320000, matched?.br)
        assertEquals("bodian", matched?.source)
    }

    @Test
    fun testKuwoSourceMatchSuccess() {
        UnmHttpClient.setMockHandler { request ->
            val url = request.url.toString()
            when {
                url.contains("search.kuwo.cn") -> {
                    val searchJson = """
                        {
                            "content": [
                                {},
                                {
                                    "musicpage": {
                                        "abslist": [
                                            {
                                                "MUSICRID": "MUSIC_2002",
                                                "DURATION": 240
                                            }
                                        ]
                                    }
                                }
                            ]
                        }
                    """.trimIndent()
                    UnmHttpClient.createMockResponse(request, 200, searchJson)
                }
                url.contains("antiserver.kuwo.cn") -> {
                    UnmHttpClient.createMockResponse(
                        request = request,
                        code = 200,
                        body = "http://antiserver.kuwo.cn/play/MUSIC_2002.mp3",
                        contentType = "text/plain"
                    )
                }
                else -> UnmHttpClient.createMockResponse(request, 404, "not found")
            }
        }

        val song = SongInfo(id = 2L, name = "晴天", artist = "周杰伦", durationMs = 240000L)
        val matched = KuwoSource.match(song)

        assertNotNull(matched)
        assertEquals("http://antiserver.kuwo.cn/play/MUSIC_2002.mp3", matched?.url)
        assertEquals(320000, matched?.br)
        assertEquals("kuwo", matched?.source)
    }

    @Test
    fun testMiguSourceMatchSuccess() {
        UnmHttpClient.setMockHandler { request ->
            val url = request.url.toString()
            when {
                url.contains("scr_search_tag") -> {
                    val searchJson = """
                        {
                            "musics": [
                                {
                                    "id": "3003",
                                    "songName": "七里香"
                                }
                            ]
                        }
                    """.trimIndent()
                    UnmHttpClient.createMockResponse(request, 200, searchJson)
                }
                url.contains("listen-url") -> {
                    val listenJson = """
                        {
                            "code": "000000",
                            "data": {
                                "playUrl": "https://freetyst.nf.migu.cn/audio/3003.mp3"
                            }
                        }
                    """.trimIndent()
                    UnmHttpClient.createMockResponse(request, 200, listenJson)
                }
                else -> UnmHttpClient.createMockResponse(request, 404, "not found")
            }
        }

        val song = SongInfo(id = 3L, name = "七里香", artist = "周杰伦", durationMs = 300000L)
        val matched = MiguSource.match(song)

        assertNotNull(matched)
        assertEquals("https://freetyst.nf.migu.cn/audio/3003.mp3", matched?.url)
        assertEquals(320000, matched?.br)
        assertEquals("migu", matched?.source)
    }

    @Test
    fun testKugouSourceMatchSuccess() {
        UnmHttpClient.setMockHandler { request ->
            val url = request.url.toString()
            when {
                url.contains("mobilecdn.kugou.com") -> {
                    val searchJson = """
                        {
                            "status": 1,
                            "data": {
                                "info": [
                                    {
                                        "320hash": "AABBCCDDEEFF11223344",
                                        "album_id": "888"
                                    }
                                ]
                            }
                        }
                    """.trimIndent()
                    UnmHttpClient.createMockResponse(request, 200, searchJson)
                }
                url.contains("trackercdn.kugou.com") -> {
                    val trackJson = """
                        {
                            "status": 1,
                            "url": [
                                "http://fs.kgcdn.com/audio/AABBCCDDEEFF11223344.mp3"
                            ]
                        }
                    """.trimIndent()
                    UnmHttpClient.createMockResponse(request, 200, trackJson)
                }
                else -> UnmHttpClient.createMockResponse(request, 404, "not found")
            }
        }

        val song = SongInfo(id = 4L, name = "起风了", artist = "买辣椒也用券", durationMs = 325000L)
        val matched = KugouSource.match(song)

        assertNotNull(matched)
        assertEquals("http://fs.kgcdn.com/audio/AABBCCDDEEFF11223344.mp3", matched?.url)
        assertEquals(320000, matched?.br)
        assertEquals("kugou", matched?.source)
    }

    @Test
    fun testHttpGetAndHttpPostHelpers() {
        var observedPostPayload = ""
        UnmHttpClient.setMockHandler { request ->
            if (request.method == "POST") {
                val buffer = okio.Buffer()
                request.body?.writeTo(buffer)
                observedPostPayload = buffer.readUtf8()
                UnmHttpClient.createMockResponse(request, 200, """{"result":"post_ok"}""")
            } else {
                UnmHttpClient.createMockResponse(request, 200, "get_ok")
            }
        }

        val getResult = httpGet("https://example.com/test_get")
        val postResult = httpPost("https://example.com/test_post", "{\"key\":\"val\"}")

        assertEquals("get_ok", getResult)
        assertEquals("""{"result":"post_ok"}""", postResult)
        assertEquals("{\"key\":\"val\"}", observedPostPayload)
    }

    // =========================================================================
    // Tier 2: Boundary, Negative & Fault Injection Tests (>= 5 cases)
    // =========================================================================

    @Test
    fun testBodianSourceAdUnlockCode20018Retry() {
        var callCount = 0
        UnmHttpClient.setMockHandler { request ->
            val url = request.url.toString()
            when {
                url.contains("search.kuwo.cn") -> {
                    UnmHttpClient.createMockResponse(
                        request,
                        200,
                        """{"content":[{},{"musicpage":{"abslist":[{"MUSICRID":"MUSIC_555","DURATION":180}]}}]}"""
                    )
                }
                url.contains("advert/watch") -> {
                    UnmHttpClient.createMockResponse(request, 200, """{"code":200}""")
                }
                url.contains("/api/play/music/v2/audioUrl") -> {
                    callCount++
                    if (callCount == 1) {
                        // First attempt fails with 20018 (ad token expired)
                        UnmHttpClient.createMockResponse(request, 200, """{"code":20018,"msg":"ad required"}""")
                    } else {
                        // Second attempt after ad unlock succeeds
                        UnmHttpClient.createMockResponse(
                            request,
                            200,
                            """{"code":200,"data":{"audioUrl":"http://bd-stream.kuwo.cn/audio/555.mp3","duration":180}}"""
                        )
                    }
                }
                else -> UnmHttpClient.createMockResponse(request, 404, "not found")
            }
        }

        val song = SongInfo(id = 5L, name = "测试重试", artist = "测试歌手", durationMs = 180000L)
        val matched = BodianSource.match(song)

        assertNotNull(matched)
        assertEquals("http://bd-stream.kuwo.cn/audio/555.mp3", matched?.url)
        assertEquals(2, callCount)
    }

    @Test
    fun testKuwoSourceBlacklistedPromptAudioFiltered() {
        UnmHttpClient.setMockHandler { request ->
            val url = request.url.toString()
            when {
                url.contains("search.kuwo.cn") -> {
                    UnmHttpClient.createMockResponse(
                        request,
                        200,
                        """{"content":[{},{"musicpage":{"abslist":[{"MUSICRID":"MUSIC_999","DURATION":172}]}}]}"""
                    )
                }
                url.contains("antiserver.kuwo.cn") -> {
                    // Antiserver returns offline prompt audio containing blacklisted ID 563182854
                    UnmHttpClient.createMockResponse(
                        request = request,
                        code = 200,
                        body = "http://antiserver.kuwo.cn/anti.s?type=convert_url&format=mp3&response=url&rid=MUSIC_563182854"
                    )
                }
                else -> UnmHttpClient.createMockResponse(request, 404, "not found")
            }
        }

        val song = SongInfo(id = 1851652156L, name = "忘不掉的你", artist = "h3R3", durationMs = 172173L)
        val matched = KuwoSource.match(song)

        // AudioValidator blocks the blacklisted ID, returning null
        assertNull(matched)
    }

    @Test
    fun testBodianSourceTeaserDurationFiltered() {
        UnmHttpClient.setMockHandler { request ->
            val url = request.url.toString()
            when {
                url.contains("search.kuwo.cn") -> {
                    UnmHttpClient.createMockResponse(
                        request,
                        200,
                        """{"content":[{},{"musicpage":{"abslist":[{"MUSICRID":"MUSIC_777","DURATION":180}]}}]}"""
                    )
                }
                url.contains("advert/watch") -> {
                    UnmHttpClient.createMockResponse(request, 200, """{"code":200}""")
                }
                url.contains("/api/play/music/v2/audioUrl") -> {
                    // Audio duration is only 20 seconds, but song is 180s -> TEASER!
                    UnmHttpClient.createMockResponse(
                        request,
                        200,
                        """{"code":200,"data":{"audioUrl":"http://bd-stream.kuwo.cn/audio/777.mp3","duration":20}}"""
                    )
                }
                else -> UnmHttpClient.createMockResponse(request, 404, "not found")
            }
        }

        val song = SongInfo(id = 7L, name = "长曲目", artist = "歌手", durationMs = 180000L)
        val matched = BodianSource.match(song)

        assertNull(matched)
    }

    @Test
    fun testMalformedJsonInAllSourcesGracefullyReturnsNull() {
        UnmHttpClient.setMockHandler { request ->
            UnmHttpClient.createMockResponse(
                request = request,
                code = 200,
                body = "{ corrupted json payload <<<"
            )
        }

        val song = SongInfo(id = 8L, name = "畸形测试", artist = "歌手")

        assertNull(BodianSource.match(song))
        assertNull(KuwoSource.match(song))
        assertNull(MiguSource.match(song))
        assertNull(KugouSource.match(song))
    }

    @Test
    fun testNetworkErrorsAndTimeoutGracefullyReturnNull() {
        UnmHttpClient.setMockHandler {
            throw SocketTimeoutException("connect timed out")
        }

        val song = SongInfo(id = 9L, name = "超时测试", artist = "歌手")

        assertNull(BodianSource.match(song))
        assertNull(KuwoSource.match(song))
        assertNull(MiguSource.match(song))
        assertNull(KugouSource.match(song))
    }

    @Test
    fun testHttpErrorStatusGracefullyReturnsNull() {
        UnmHttpClient.setMockHandler { request ->
            UnmHttpClient.createMockResponse(request, 503, "Service Unavailable")
        }

        val song = SongInfo(id = 10L, name = "服务器错误", artist = "歌手")

        assertNull(BodianSource.match(song))
        assertNull(KuwoSource.match(song))
        assertNull(MiguSource.match(song))
        assertNull(KugouSource.match(song))
    }

    // =========================================================================
    // Tier 3: Cross-Source Fallback via RuleManager
    // =========================================================================

    @Test
    fun testSourcePriorityFallbackFromBodianToKuwoToMigu() {
        UnmHttpClient.setMockHandler { request ->
            val url = request.url.toString()
            when {
                // Bodian search returns empty abslist -> Bodian fails
                url.contains("bd-api.kuwo.cn") -> {
                    UnmHttpClient.createMockResponse(request, 500, "error")
                }
                // Kuwo search returns abslist, but antiserver returns 588957081 -> Kuwo fails
                url.contains("antiserver.kuwo.cn") -> {
                    UnmHttpClient.createMockResponse(
                        request,
                        200,
                        "http://antiserver.kuwo.cn/play/588957081.mp3"
                    )
                }
                url.contains("search.kuwo.cn") -> {
                    UnmHttpClient.createMockResponse(
                        request,
                        200,
                        """{"content":[{},{"musicpage":{"abslist":[{"MUSICRID":"MUSIC_111","DURATION":180}]}}]}"""
                    )
                }
                // Migu search succeeds and returns valid URL -> Migu matches!
                url.contains("scr_search_tag") -> {
                    UnmHttpClient.createMockResponse(
                        request,
                        200,
                        """{"musics":[{"id":"migu_999"}]}"""
                    )
                }
                url.contains("listen-url") -> {
                    UnmHttpClient.createMockResponse(
                        request,
                        200,
                        """{"data":{"playUrl":"https://freetyst.nf.migu.cn/audio/migu_999.mp3"}}"""
                    )
                }
                else -> UnmHttpClient.createMockResponse(request, 404, "not found")
            }
        }

        val song = SongInfo(id = 11L, name = "回退测试", artist = "歌手", durationMs = 180000L)
        val matched = RuleManager.matchAudio(song, priority = listOf("bodian", "kuwo", "migu"))

        assertNotNull(matched)
        assertEquals("migu", matched?.source)
        assertEquals("https://freetyst.nf.migu.cn/audio/migu_999.mp3", matched?.url)
    }

    // =========================================================================
    // Tier 4: Real-World UNM Application Scenarios
    // =========================================================================

    @Test
    fun testOfflineDeterminismZeroExternalSockets() {
        // Enforces that even with non-existent fake domain names, the entire pipeline executes
        // deterministically without network access.
        var requestCount = 0
        UnmHttpClient.setMockHandler { request ->
            requestCount++
            UnmHttpClient.createMockResponse(
                request = request,
                code = 200,
                body = """{"code":200,"data":{"audioUrl":"http://offline-cache.local/audio.mp3","duration":200}}"""
            )
        }

        val song = SongInfo(id = 12L, name = "离线歌曲", artist = "离线歌手", durationMs = 200000L)
        val matched = BodianSource.match(song)

        assertTrue(requestCount > 0)
    }
}
