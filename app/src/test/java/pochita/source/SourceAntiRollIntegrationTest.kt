package pochita.source

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import pochita.common.UnmHttpClient
import pochita.common.UnmLruCache
import pochita.config.ConfigManager
import pochita.config.UnmConfig
import pochita.model.ReplaceEvent
import pochita.model.SongInfo
import pochita.resolver.PlayerUrlResolver
import pochita.rule.RuleManager

/**
 * End-to-end integration tests for Anti-Roll candidate ranking, metadata propagation,
 * and Two-Pass RuleManager orchestration across Bodian, Kuwo, Migu, Kugou, and QuickJS.
 */
class SourceAntiRollIntegrationTest {

    @Before
    fun setUp() {
        UnmHttpClient.reset()
        AudioValidator.reset()
        RuleManager.resetForTest()
        ConfigManager.resetForTesting()
    }

    @After
    fun tearDown() {
        UnmHttpClient.reset()
        AudioValidator.reset()
        RuleManager.resetForTest()
        ConfigManager.resetForTesting()
    }

    // =========================================================================
    // 1. BodianSource: Selects Studio Candidate over Live Candidate
    // =========================================================================

    @Test
    fun testBodianSelectsStudioOverLiveCandidate() {
        UnmHttpClient.setMockHandler { request ->
            val url = request.url.toString()
            when {
                url.contains("search.kuwo.cn") -> {
                    // Candidate 0: Live version (285s)
                    // Candidate 1: Official studio version (269s)
                    val searchJson = """
                        {
                            "content": [
                                {},
                                {
                                    "musicpage": {
                                        "abslist": [
                                            {
                                                "MUSICRID": "MUSIC_1001_LIVE",
                                                "SONGNAME": "晴天 (Live演唱会版)",
                                                "ARTIST": "周杰伦",
                                                "DURATION": 285
                                            },
                                            {
                                                "MUSICRID": "MUSIC_1002_STUDIO",
                                                "SONGNAME": "晴天",
                                                "ARTIST": "周杰伦",
                                                "DURATION": 269
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
                    UnmHttpClient.createMockResponse(request, 200, """{"code":200}""")
                }
                url.contains("musicId=1002_STUDIO") -> {
                    UnmHttpClient.createMockResponse(
                        request, 200,
                        """{"code":200,"data":{"audioUrl":"http://bd-stream.kuwo.cn/audio/studio_1002.mp3","duration":269}}"""
                    )
                }
                url.contains("musicId=1001_LIVE") -> {
                    UnmHttpClient.createMockResponse(
                        request, 200,
                        """{"code":200,"data":{"audioUrl":"http://bd-stream.kuwo.cn/audio/live_1001.mp3","duration":285}}"""
                    )
                }
                else -> UnmHttpClient.createMockResponse(request, 404, "not found")
            }
        }

        val song = SongInfo(id = 100L, name = "晴天", artist = "周杰伦", durationMs = 269_000L)
        val matched = BodianSource.match(song)

        assertNotNull("Bodian must successfully resolve a match", matched)
        assertEquals("http://bd-stream.kuwo.cn/audio/studio_1002.mp3", matched?.url)
        assertEquals("晴天", matched?.matchedTitle)
        assertEquals("周杰伦", matched?.matchedArtist)
        assertEquals(269_000L, matched?.durationMs)
        assertEquals("bodian", matched?.source)
    }

    // =========================================================================
    // 2. KuwoSource: Selects Studio Candidate over Cover Candidate
    // =========================================================================

    @Test
    fun testKuwoSelectsStudioOverCoverCandidate() {
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
                                                "MUSICRID": "MUSIC_2001_COVER",
                                                "SONGNAME": "起风了 (翻唱版)",
                                                "ARTIST": "网络歌手",
                                                "DURATION": 325
                                            },
                                            {
                                                "MUSICRID": "MUSIC_2002_STUDIO",
                                                "SONGNAME": "起风了",
                                                "ARTIST": "买辣椒也用券",
                                                "DURATION": 325
                                            }
                                        ]
                                    }
                                }
                            ]
                        }
                    """.trimIndent()
                    UnmHttpClient.createMockResponse(request, 200, searchJson)
                }
                url.contains("rid=MUSIC_2002_STUDIO") -> {
                    UnmHttpClient.createMockResponse(
                        request, 200,
                        "http://antiserver.kuwo.cn/play/studio_2002.mp3",
                        contentType = "text/plain"
                    )
                }
                url.contains("rid=MUSIC_2001_COVER") -> {
                    UnmHttpClient.createMockResponse(
                        request, 200,
                        "http://antiserver.kuwo.cn/play/cover_2001.mp3",
                        contentType = "text/plain"
                    )
                }
                else -> UnmHttpClient.createMockResponse(request, 404, "not found")
            }
        }

        val song = SongInfo(id = 200L, name = "起风了", artist = "买辣椒也用券", durationMs = 325_000L)
        val matched = KuwoSource.match(song)

        assertNotNull("Kuwo must successfully resolve a match", matched)
        assertEquals("http://antiserver.kuwo.cn/play/studio_2002.mp3", matched?.url)
        assertEquals("起风了", matched?.matchedTitle)
        assertEquals("买辣椒也用券", matched?.matchedArtist)
        assertEquals(325_000L, matched?.durationMs)
        assertEquals("kuwo", matched?.source)
    }

    // =========================================================================
    // 3. MiguSource: Selects Studio Candidate over DJ Candidate
    // =========================================================================

    @Test
    fun testMiguSelectsStudioOverDjCandidate() {
        UnmHttpClient.setMockHandler { request ->
            val url = request.url.toString()
            when {
                url.contains("scr_search_tag") -> {
                    val searchJson = """
                        {
                            "musics": [
                                {
                                    "id": "migu_dj_3001",
                                    "songName": "七里香 (DJ加快版)",
                                    "singerName": "周杰伦",
                                    "length": "03:10"
                                },
                                {
                                    "id": "migu_studio_3002",
                                    "songName": "七里香",
                                    "singerName": "周杰伦",
                                    "length": "04:59"
                                }
                            ]
                        }
                    """.trimIndent()
                    UnmHttpClient.createMockResponse(request, 200, searchJson)
                }
                url.contains("songId=migu_studio_3002") -> {
                    UnmHttpClient.createMockResponse(
                        request, 200,
                        """{"code":"000000","data":{"playUrl":"https://freetyst.nf.migu.cn/audio/studio_3002.mp3"}}"""
                    )
                }
                url.contains("songId=migu_dj_3001") -> {
                    UnmHttpClient.createMockResponse(
                        request, 200,
                        """{"code":"000000","data":{"playUrl":"https://freetyst.nf.migu.cn/audio/dj_3001.mp3"}}"""
                    )
                }
                else -> UnmHttpClient.createMockResponse(request, 404, "not found")
            }
        }

        val song = SongInfo(id = 300L, name = "七里香", artist = "周杰伦", durationMs = 299_000L)
        val matched = MiguSource.match(song)

        assertNotNull("Migu must successfully resolve a match", matched)
        assertEquals("https://freetyst.nf.migu.cn/audio/studio_3002.mp3", matched?.url)
        assertEquals("七里香", matched?.matchedTitle)
        assertEquals("周杰伦", matched?.matchedArtist)
        assertEquals(299_000L, matched?.durationMs)
        assertEquals("migu", matched?.source)
    }

    // =========================================================================
    // 4. KugouSource: Selects Studio Candidate over Live Candidate
    // =========================================================================

    @Test
    fun testKugouSelectsStudioOverLiveCandidate() {
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
                                        "songname": "夜曲 (现场Live版)",
                                        "singername": "周杰伦",
                                        "duration": 280,
                                        "320hash": "HASH_LIVE_4001",
                                        "album_id": "111"
                                    },
                                    {
                                        "songname": "夜曲",
                                        "singername": "周杰伦",
                                        "duration": 226,
                                        "320hash": "HASH_STUDIO_4002",
                                        "album_id": "222"
                                    }
                                ]
                            }
                        }
                    """.trimIndent()
                    UnmHttpClient.createMockResponse(request, 200, searchJson)
                }
                url.contains("HASH_STUDIO_4002") -> {
                    UnmHttpClient.createMockResponse(
                        request, 200,
                        """{"status":1,"url":["http://fs.kgcdn.com/audio/studio_4002.mp3"]}"""
                    )
                }
                url.contains("HASH_LIVE_4001") -> {
                    UnmHttpClient.createMockResponse(
                        request, 200,
                        """{"status":1,"url":["http://fs.kgcdn.com/audio/live_4001.mp3"]}"""
                    )
                }
                else -> UnmHttpClient.createMockResponse(request, 404, "not found")
            }
        }

        val song = SongInfo(id = 400L, name = "夜曲", artist = "周杰伦", durationMs = 226_000L)
        val matched = KugouSource.match(song)

        assertNotNull("Kugou must successfully resolve a match", matched)
        assertEquals("http://fs.kgcdn.com/audio/studio_4002.mp3", matched?.url)
        assertEquals("夜曲", matched?.matchedTitle)
        assertEquals("周杰伦", matched?.matchedArtist)
        assertEquals(226_000L, matched?.durationMs)
        assertEquals("kugou", matched?.source)
    }

    // =========================================================================
    // 5. Cross-Source Priority Fallback: Source 1 Live vs Source 2 Studio
    // =========================================================================

    @Test
    fun testTwoPassBypassesLiveOnBodianAndPicksStudioOnKuwo() {
        UnmHttpClient.setMockHandler { request ->
            val url = request.url.toString()
            when {
                // Bodian search only has Live candidate
                url.contains("search.kuwo.cn") && request.header("User-Agent")?.contains("Dart") == true -> {
                    val bodianJson = """
                        {
                            "content": [
                                {},
                                {
                                    "musicpage": {
                                        "abslist": [
                                            { "MUSICRID": "MUSIC_BD_LIVE", "SONGNAME": "枫 (Live)", "ARTIST": "周杰伦", "DURATION": 300 }
                                        ]
                                    }
                                }
                            ]
                        }
                    """.trimIndent()
                    UnmHttpClient.createMockResponse(request, 200, bodianJson)
                }
                // Kuwo search has Studio candidate
                url.contains("search.kuwo.cn") -> {
                    val kuwoJson = """
                        {
                            "content": [
                                {},
                                {
                                    "musicpage": {
                                        "abslist": [
                                            { "MUSICRID": "MUSIC_KW_STUDIO", "SONGNAME": "枫", "ARTIST": "周杰伦", "DURATION": 275 }
                                        ]
                                    }
                                }
                            ]
                        }
                    """.trimIndent()
                    UnmHttpClient.createMockResponse(request, 200, kuwoJson)
                }
                url.contains("MUSIC_KW_STUDIO") -> {
                    UnmHttpClient.createMockResponse(request, 200, "http://antiserver.kuwo.cn/play/kw_studio.mp3")
                }
                url.contains("MUSIC_BD_LIVE") -> {
                    UnmHttpClient.createMockResponse(request, 200, """{"code":200,"data":{"audioUrl":"http://bd-stream.kuwo.cn/live.mp3","duration":300}}""")
                }
                url.contains("advert/watch") -> UnmHttpClient.createMockResponse(request, 200, "{}")
                else -> UnmHttpClient.createMockResponse(request, 404, "not found")
            }
        }

        val song = SongInfo(id = 500L, name = "枫", artist = "周杰伦", durationMs = 275_000L)
        // Bodian is first in priority list, Kuwo is second
        val matched = RuleManager.matchAudio(song, priority = listOf("bodian", "kuwo"))

        assertNotNull("Two-pass resolution must match", matched)
        // Pass 1 rejects Bodian's Live candidate and accepts Kuwo's Studio candidate!
        assertEquals("kuwo", matched?.source)
        assertEquals("http://antiserver.kuwo.cn/play/kw_studio.mp3", matched?.url)
        assertEquals("枫", matched?.matchedTitle)
    }

    // =========================================================================
    // 6. Graceful Degradation: When Only Live Version Exists Anywhere, Pass 2 Accepts It
    // =========================================================================

    @Test
    fun testPass2AcceptsLiveCandidateWhenNoStudioVersionExists() {
        UnmHttpClient.setMockHandler { request ->
            val url = request.url.toString()
            when {
                url.contains("search.kuwo.cn") -> {
                    val json = """
                        {
                            "content": [
                                {},
                                {
                                    "musicpage": {
                                        "abslist": [
                                            { "MUSICRID": "MUSIC_RARE_LIVE", "SONGNAME": "罕见歌曲 (Live现场)", "ARTIST": "地下乐队", "DURATION": 250 }
                                        ]
                                    }
                                }
                            ]
                        }
                    """.trimIndent()
                    UnmHttpClient.createMockResponse(request, 200, json)
                }
                url.contains("antiserver.kuwo.cn") -> {
                    UnmHttpClient.createMockResponse(request, 200, "http://antiserver.kuwo.cn/play/rare_live.mp3")
                }
                else -> UnmHttpClient.createMockResponse(request, 404, "not found")
            }
        }

        val song = SongInfo(id = 600L, name = "罕见歌曲", artist = "地下乐队", durationMs = 245_000L)
        val matched = RuleManager.matchAudio(song, priority = listOf("kuwo"))

        assertNotNull("When no studio version exists, Pass 2 must fall back to the Live version", matched)
        assertEquals("http://antiserver.kuwo.cn/play/rare_live.mp3", matched?.url)
        assertEquals("罕见歌曲 (Live现场)", matched?.matchedTitle)
    }

    // =========================================================================
    // 7. Hard Disqualification: Extreme Drift (>45s) Rejected Even in Pass 2
    // =========================================================================

    @Test
    fun testExtremeDurationDriftDisqualifiedInBothPasses() {
        UnmHttpClient.setMockHandler { request ->
            val url = request.url.toString()
            when {
                url.contains("search.kuwo.cn") -> {
                    val json = """
                        {
                            "content": [
                                {},
                                {
                                    "musicpage": {
                                        "abslist": [
                                            { "MUSICRID": "MUSIC_WRONG_TRACK", "SONGNAME": "同一首歌", "ARTIST": "群星", "DURATION": 500 }
                                        ]
                                    }
                                }
                            ]
                        }
                    """.trimIndent()
                    UnmHttpClient.createMockResponse(request, 200, json)
                }
                else -> UnmHttpClient.createMockResponse(request, 404, "not found")
            }
        }

        // Expected 180s, but candidate is 500s (+320s drift)
        val song = SongInfo(id = 700L, name = "同一首歌", artist = "群星", durationMs = 180_000L)
        val matched = RuleManager.matchAudio(song, priority = listOf("kuwo"))

        assertNull("Extreme duration drift must be rejected in both Pass 1 and Pass 2", matched)
    }

    // =========================================================================
    // 8. ReplaceEvent Downstream Propagation: matchedTitle, matchedArtist, matchedAudio
    // =========================================================================

    @Test
    fun testReplaceEventDownstreamMetadataPropagation() {
        var dispatchedEvent: ReplaceEvent? = null
        val cache = UnmLruCache<Long, SongInfo>(10)
        val originalSong = SongInfo(id = 888L, name = "晴天", artist = "周杰伦", durationMs = 269_000L)
        cache.put(888L, originalSong)

        val resolver = PlayerUrlResolver(
            songCache = cache,
            audioMatcher = { _, _ ->
                pochita.model.MatchedAudio(
                    url = "http://audio.kuwo.cn/qts_live.mp3",
                    br = 320000,
                    source = "kuwo",
                    matchedTitle = "晴天 (2007演唱会现场版)",
                    matchedArtist = "周杰伦",
                    durationMs = 285_000L
                )
            },
            configProvider = { UnmConfig(enableToast = true, enablePlayerBadge = true) },
            onTrackReplaced = { event -> dispatchedEvent = event }
        )

        val playerUrlJson = """
            {
                "code": 200,
                "data": [
                    {
                        "id": 888,
                        "url": null,
                        "br": 0,
                        "fee": 1,
                        "trialMode": 1
                    }
                ]
            }
        """.trimIndent()

        val result = resolver.processPlayerUrl("http://interface.music.163.com/eapi/song/enhance/player/url", playerUrlJson)

        assertNotNull(result)
        assertNotNull("ReplaceEvent must be dispatched", dispatchedEvent)
        assertEquals("晴天 (2007演唱会现场版)", dispatchedEvent?.matchedTitle)
        assertEquals("周杰伦", dispatchedEvent?.matchedArtist)
        assertEquals("kuwo", dispatchedEvent?.matchedSource)
        assertEquals(320000, dispatchedEvent?.br)
        assertNotNull(dispatchedEvent?.matchedAudio)
        assertEquals(285_000L, dispatchedEvent?.matchedAudio?.durationMs)
    }
}
