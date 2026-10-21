package pochita.source

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import pochita.common.UnmHttpClient
import pochita.common.UnmLruCache
import pochita.config.ConfigManager
import pochita.config.UnmConfig
import pochita.model.MatchedAudio
import pochita.model.ReplaceEvent
import pochita.model.SongInfo
import pochita.resolver.PlayerUrlResolver
import pochita.rule.RuleManager
import pochita.ui.BadgeFormatter
import pochita.ui.ToastNotifier
import java.util.concurrent.atomic.AtomicInteger

/**
 * Empirical Challenge Test Suite for Milestone 3 (Phase 2):
 * - Priority Competition: Lower-priority source with Studio beats higher-priority source with Live in Pass 1.
 * - Multi-source priority chains across Bodian, Kuwo, Migu, and Kugou.
 * - Fallback Resolution: Graceful degradation to Live/Cover candidate in Pass 2 when no studio version exists.
 * - Teaser and Extreme Duration Drift hard-rejection in both Pass 1 and Pass 2.
 * - Metadata Transparency: matchedTitle and matchedArtist propagation across MatchedAudio, ReplaceEvent,
 *   ToastNotifier, BadgeFormatter, and PlayerBadgeManager.
 * - Word boundary false-positive defenses (e.g. "Alive", "Radioactive").
 * - HTML entity unescaping in third-party metadata.
 */
class AntiRollEmpiricalChallengeTest {

    private val fakeContext = FakeContext()
    private class FakeContext : android.content.ContextWrapper(null)

    @Before
    fun setUp() {
        UnmHttpClient.reset()
        AudioValidator.reset()
        RuleManager.resetForTest()
        ConfigManager.resetForTesting()
        ToastNotifier.resetForTesting()
    }

    @After
    fun tearDown() {
        UnmHttpClient.reset()
        AudioValidator.reset()
        RuleManager.resetForTest()
        ConfigManager.resetForTesting()
        ToastNotifier.resetForTesting()
    }

    // =========================================================================
    // SECTION 1: PRIORITY COMPETITION TESTS (Pass 1 Studio Preference)
    // =========================================================================

    /**
     * Challenge 1.1: Kuwo (P1, Live) vs Migu (P2, Studio)
     * Kuwo is higher priority (P1) but only has a Live concert recording.
     * Migu is lower priority (P2) and has the authentic Studio version.
     * Pass 1 must bypass Kuwo and choose Migu!
     */
    @Test
    fun testPriorityCompetition_KuwoLiveVsMiguStudio() {
        UnmHttpClient.setMockHandler { request ->
            val url = request.url.toString()
            when {
                // Kuwo search: returns Live recording
                url.contains("search.kuwo.cn") -> {
                    val kuwoJson = """
                        {
                            "content": [
                                {},
                                {
                                    "musicpage": {
                                        "abslist": [
                                            { "MUSICRID": "MUSIC_KW_LIVE", "SONGNAME": "晴天 (Live现场)", "ARTIST": "周杰伦", "DURATION": 285 }
                                        ]
                                    }
                                }
                            ]
                        }
                    """.trimIndent()
                    UnmHttpClient.createMockResponse(request, 200, kuwoJson)
                }
                // Migu search: returns authentic Studio version
                url.contains("scr_search_tag") -> {
                    val miguJson = """
                        {
                            "musics": [
                                { "id": "migu_qt_studio", "songName": "晴天", "singerName": "周杰伦", "length": "04:29" }
                            ]
                        }
                    """.trimIndent()
                    UnmHttpClient.createMockResponse(request, 200, miguJson)
                }
                url.contains("songId=migu_qt_studio") -> {
                    UnmHttpClient.createMockResponse(
                        request, 200,
                        """{"code":"000000","data":{"playUrl":"https://freetyst.nf.migu.cn/audio/qt_studio.mp3"}}"""
                    )
                }
                url.contains("MUSIC_KW_LIVE") -> {
                    UnmHttpClient.createMockResponse(request, 200, "http://antiserver.kuwo.cn/play/live.mp3")
                }
                else -> UnmHttpClient.createMockResponse(request, 404, "not found")
            }
        }

        val song = SongInfo(id = 101L, name = "晴天", artist = "周杰伦", durationMs = 269_000L)
        // Kuwo is P1, Migu is P2
        val matched = RuleManager.matchAudio(song, priority = listOf("kuwo", "migu"))

        assertNotNull("Must find a match", matched)
        assertEquals("migu", matched?.source)
        assertEquals("晴天", matched?.matchedTitle)
        assertEquals("周杰伦", matched?.matchedArtist)
        assertEquals("https://freetyst.nf.migu.cn/audio/qt_studio.mp3", matched?.url)
    }

    /**
     * Challenge 1.2: Bodian (P1, Live) vs Kuwo (P2, Studio) using atomic counter
     * Bodian searches search.kuwo.cn first (returns Live candidate).
     * Kuwo searches search.kuwo.cn second (returns Studio candidate).
     * Pass 1 must reject Bodian's Live candidate and select Kuwo's Studio candidate.
     */
    @Test
    fun testPriorityCompetition_BodianLiveVsKuwoStudio() {
        val searchCounter = AtomicInteger(0)

        UnmHttpClient.setMockHandler { request ->
            val url = request.url.toString()
            when {
                url.contains("search.kuwo.cn") -> {
                    val callNum = searchCounter.incrementAndGet()
                    if (callNum == 1) {
                        // Bodian search: returns Live recording
                        val bodianJson = """
                            {
                                "content": [ {}, {
                                    "musicpage": {
                                        "abslist": [
                                            { "MUSICRID": "MUSIC_BD_LIVE", "SONGNAME": "枫 (Live现场)", "ARTIST": "周杰伦", "DURATION": 300 }
                                        ]
                                    }
                                }]
                            }
                        """.trimIndent()
                        UnmHttpClient.createMockResponse(request, 200, bodianJson)
                    } else {
                        // Kuwo search: returns Studio version
                        val kuwoJson = """
                            {
                                "content": [ {}, {
                                    "musicpage": {
                                        "abslist": [
                                            { "MUSICRID": "MUSIC_KW_STUDIO", "SONGNAME": "枫", "ARTIST": "周杰伦", "DURATION": 275 }
                                        ]
                                    }
                                }]
                            }
                        """.trimIndent()
                        UnmHttpClient.createMockResponse(request, 200, kuwoJson)
                    }
                }
                url.contains("MUSIC_KW_STUDIO") -> {
                    UnmHttpClient.createMockResponse(request, 200, "http://antiserver.kuwo.cn/play/kw_studio.mp3")
                }
                url.contains("MUSIC_BD_LIVE") -> {
                    UnmHttpClient.createMockResponse(request, 200, """{"code":200,"data":{"audioUrl":"http://bd.cn/live.mp3","duration":300}}""")
                }
                url.contains("advert/watch") -> UnmHttpClient.createMockResponse(request, 200, "{}")
                else -> UnmHttpClient.createMockResponse(request, 404, "not found")
            }
        }

        val song = SongInfo(id = 102L, name = "枫", artist = "周杰伦", durationMs = 275_000L)
        val matched = RuleManager.matchAudio(song, priority = listOf("bodian", "kuwo"))

        assertNotNull("Kuwo studio match must succeed", matched)
        assertEquals("kuwo", matched?.source)
        assertEquals("枫", matched?.matchedTitle)
        assertEquals("http://antiserver.kuwo.cn/play/kw_studio.mp3", matched?.url)
    }

    /**
     * Challenge 1.3: Bodian (P1, DJ) vs Kuwo (P2, Live) vs Migu (P3, Radio) vs Kugou (P4, Studio)
     * Kugou has lowest priority (4th). All other 3 sources have non-studio variants (DJ, Live, Radio).
     * Pass 1 must evaluate all sources and select Kugou's Studio version.
     */
    @Test
    fun testPriorityCompetition_AllNonStudioExceptLowestPriorityKugou() {
        val kuwoSearchCounter = AtomicInteger(0)

        UnmHttpClient.setMockHandler { request ->
            val url = request.url.toString()
            when {
                // Bodian & Kuwo search
                url.contains("search.kuwo.cn") -> {
                    val count = kuwoSearchCounter.incrementAndGet()
                    val candidateTitle = if (count == 1) "夜曲 (DJ慢摇版)" else "夜曲 (Concert Live)"
                    val rid = if (count == 1) "MUSIC_BD_DJ" else "MUSIC_KW_LIVE"
                    val json = """
                        {
                            "content": [ {}, {
                                "musicpage": {
                                    "abslist": [
                                        { "MUSICRID": "$rid", "SONGNAME": "$candidateTitle", "ARTIST": "周杰伦", "DURATION": 226 }
                                    ]
                                }
                            }]
                        }
                    """.trimIndent()
                    UnmHttpClient.createMockResponse(request, 200, json)
                }
                // Migu: Radio candidate
                url.contains("scr_search_tag") -> {
                    val json = """
                        {
                            "musics": [
                                { "id": "migu_radio", "songName": "夜曲 (情感电台FM)", "singerName": "周杰伦", "length": "03:46" }
                            ]
                        }
                    """.trimIndent()
                    UnmHttpClient.createMockResponse(request, 200, json)
                }
                // Kugou: Pure studio candidate
                url.contains("mobilecdn.kugou.com") -> {
                    val json = """
                        {
                            "status": 1,
                            "data": {
                                "info": [
                                    { "songname": "夜曲", "singername": "周杰伦", "duration": 226, "320hash": "HASH_KG_STUDIO", "album_id": "99" }
                                ]
                            }
                        }
                    """.trimIndent()
                    UnmHttpClient.createMockResponse(request, 200, json)
                }
                url.contains("HASH_KG_STUDIO") -> {
                    UnmHttpClient.createMockResponse(
                        request, 200,
                        """{"status":1,"url":["http://fs.kgcdn.com/audio/yequ_studio.mp3"]}"""
                    )
                }
                url.contains("advert/watch") -> UnmHttpClient.createMockResponse(request, 200, "{}")
                else -> UnmHttpClient.createMockResponse(request, 404, "not found")
            }
        }

        val song = SongInfo(id = 103L, name = "夜曲", artist = "周杰伦", durationMs = 226_000L)
        val matched = RuleManager.matchAudio(song, priority = listOf("bodian", "kuwo", "migu", "kugou"))

        assertNotNull("Kugou studio version must be selected", matched)
        assertEquals("kugou", matched?.source)
        assertEquals("夜曲", matched?.matchedTitle)
        assertEquals("http://fs.kgcdn.com/audio/yequ_studio.mp3", matched?.url)
    }

    /**
     * Challenge 1.4: Word Boundary Protection for English Titles during Priority Competition.
     * Song title: "Alive" by Sia.
     * Bodian returns "Alive" (Studio).
     * Verify that "Alive" is NOT penalized by the `\b(live)\b` regex pattern!
     * Bodian (P1) must match immediately in Pass 1.
     */
    @Test
    fun testWordBoundary_AliveTitleNotPenalizedAsLive() {
        UnmHttpClient.setMockHandler { request ->
            val url = request.url.toString()
            when {
                url.contains("search.kuwo.cn") -> {
                    val json = """
                        {
                            "content": [ {}, {
                                "musicpage": {
                                    "abslist": [
                                        { "MUSICRID": "BD_ALIVE", "SONGNAME": "Alive", "ARTIST": "Sia", "DURATION": 258 }
                                    ]
                                }
                            }]
                        }
                    """.trimIndent()
                    UnmHttpClient.createMockResponse(request, 200, json)
                }
                url.contains("musicId=ALIVE") || url.contains("BD_ALIVE") -> {
                    UnmHttpClient.createMockResponse(
                        request, 200,
                        """{"code":200,"data":{"audioUrl":"http://bd-stream.kuwo.cn/sia_alive.mp3","duration":258}}"""
                    )
                }
                url.contains("advert/watch") -> UnmHttpClient.createMockResponse(request, 200, "{}")
                else -> UnmHttpClient.createMockResponse(request, 404, "not found")
            }
        }

        val song = SongInfo(id = 104L, name = "Alive", artist = "Sia", durationMs = 258_000L)
        val matched = RuleManager.matchAudio(song, priority = listOf("bodian"))

        assertNotNull("Bodian must match Sia - Alive in Pass 1", matched)
        assertEquals("bodian", matched?.source)
        assertEquals("Alive", matched?.matchedTitle)
        assertEquals("http://bd-stream.kuwo.cn/sia_alive.mp3", matched?.url)
    }

    // =========================================================================
    // SECTION 2: FALLBACK RESOLUTION TESTS (Pass 2 Graceful Audio Preservation)
    // =========================================================================

    /**
     * Challenge 2.1: Graceful fallback when NO source has a studio version.
     * Kuwo only has a Live version (delta = 5s, score >= -150).
     * In Pass 1, Kuwo's Live candidate is rejected because score < 0.
     * In Pass 2, Kuwo's Live version is accepted so playback does NOT fail!
     */
    @Test
    fun testFallbackResolution_Pass2AcceptsLiveWhenNoStudioExists() {
        UnmHttpClient.setMockHandler { request ->
            val url = request.url.toString()
            when {
                url.contains("search.kuwo.cn") -> {
                    val json = """
                        {
                            "content": [ {}, {
                                "musicpage": {
                                    "abslist": [
                                        { "MUSICRID": "MUSIC_KW_RARE_LIVE", "SONGNAME": "山海 (Live现场)", "ARTIST": "草东没有派对", "DURATION": 260 }
                                    ]
                                }
                            }]
                        }
                    """.trimIndent()
                    UnmHttpClient.createMockResponse(request, 200, json)
                }
                url.contains("MUSIC_KW_RARE_LIVE") -> {
                    UnmHttpClient.createMockResponse(request, 200, "http://antiserver.kuwo.cn/play/shanhai_live.mp3")
                }
                else -> UnmHttpClient.createMockResponse(request, 404, "not found")
            }
        }

        val song = SongInfo(id = 201L, name = "山海", artist = "草东没有派对", durationMs = 255_000L)
        val matched = RuleManager.matchAudio(song, priority = listOf("kuwo"))

        assertNotNull("Pass 2 must recover and return Live track to prevent silence", matched)
        assertEquals("kuwo", matched?.source)
        assertEquals("山海 (Live现场)", matched?.matchedTitle)
        assertEquals("http://antiserver.kuwo.cn/play/shanhai_live.mp3", matched?.url)
    }

    /**
     * Challenge 2.2: Extreme duration drift (>45s) is rejected in BOTH Pass 1 and Pass 2.
     * Source 1 (Kuwo) has a Live version with +120s drift (disqualified, score < -300).
     * Source 2 (Migu) has a Live version with +6s drift (score ~ -87 >= -150).
     * Pass 1: Both rejected.
     * Pass 2: Kuwo is rejected due to extreme drift! Migu is accepted!
     */
    @Test
    fun testFallbackResolution_ExtremeDriftRejectedInPass2_LowerPriorityPicked() {
        UnmHttpClient.setMockHandler { request ->
            val url = request.url.toString()
            when {
                // Kuwo: extreme drift (+120s: 360s vs 240s)
                url.contains("search.kuwo.cn") -> {
                    val json = """
                        {
                            "content": [ {}, {
                                "musicpage": {
                                    "abslist": [
                                        { "MUSICRID": "KW_DRIFT", "SONGNAME": "安和桥 (Live加长版)", "ARTIST": "宋冬野", "DURATION": 360 }
                                    ]
                                }
                            }]
                        }
                    """.trimIndent()
                    UnmHttpClient.createMockResponse(request, 200, json)
                }
                // Migu: close Live version (+6s: 246s vs 240s)
                url.contains("scr_search_tag") -> {
                    val json = """
                        {
                            "musics": [
                                { "id": "migu_close_live", "songName": "安和桥 (Live)", "singerName": "宋冬野", "length": "04:06" }
                            ]
                        }
                    """.trimIndent()
                    UnmHttpClient.createMockResponse(request, 200, json)
                }
                url.contains("songId=migu_close_live") -> {
                    UnmHttpClient.createMockResponse(
                        request, 200,
                        """{"code":"000000","data":{"playUrl":"https://freetyst.nf.migu.cn/audio/anheqiao_live.mp3"}}"""
                    )
                }
                else -> UnmHttpClient.createMockResponse(request, 404, "not found")
            }
        }

        // Song is 240s
        val song = SongInfo(id = 202L, name = "安和桥", artist = "宋冬野", durationMs = 240_000L)
        val matched = RuleManager.matchAudio(song, priority = listOf("kuwo", "migu"))

        assertNotNull("Pass 2 must reject extreme drift on Kuwo and accept Migu's close Live version", matched)
        assertEquals("migu", matched?.source)
        assertEquals("安和桥 (Live)", matched?.matchedTitle)
        assertEquals("https://freetyst.nf.migu.cn/audio/anheqiao_live.mp3", matched?.url)
    }

    /**
     * Challenge 2.3: Teaser clip (<30s when expected >60s) is strictly disqualified in Pass 1 and Pass 2.
     * All sources only offer teaser clips (e.g. 28s ringtone / preview).
     * RuleManager must return null rather than replacing user's music with a 28s snippet.
     */
    @Test
    fun testFallbackResolution_TeaserClipsDisqualifiedEvenInPass2() {
        UnmHttpClient.setMockHandler { request ->
            val url = request.url.toString()
            when {
                url.contains("search.kuwo.cn") -> {
                    val json = """
                        {
                            "content": [ {}, {
                                "musicpage": {
                                    "abslist": [
                                        { "MUSICRID": "KW_TEASER", "SONGNAME": "孤勇者 (铃声片段截取)", "ARTIST": "陈奕迅", "DURATION": 28 }
                                    ]
                                }
                            }]
                        }
                    """.trimIndent()
                    UnmHttpClient.createMockResponse(request, 200, json)
                }
                else -> UnmHttpClient.createMockResponse(request, 404, "not found")
            }
        }

        val song = SongInfo(id = 203L, name = "孤勇者", artist = "陈奕迅", durationMs = 256_000L)
        val matched = RuleManager.matchAudio(song, priority = listOf("kuwo"))

        assertNull("Teaser clips must be completely rejected in both Pass 1 and Pass 2", matched)
    }

    /**
     * Challenge 2.4: Behavior when enableAntiRoll == false.
     * When anti-roll is turned off, candidate 0 (even if Live) is selected in Pass 1 without penalty.
     */
    @Test
    fun testDisableAntiRoll_AcceptsCandidateImmediatelyWithoutPenalty() {
        ConfigManager.setConfigForTesting(UnmConfig(enableAntiRoll = false))

        UnmHttpClient.setMockHandler { request ->
            val url = request.url.toString()
            when {
                url.contains("search.kuwo.cn") -> {
                    val json = """
                        {
                            "content": [ {}, {
                                "musicpage": {
                                    "abslist": [
                                        { "MUSICRID": "KW_0", "SONGNAME": "晴天 (Live现场)", "ARTIST": "周杰伦", "DURATION": 285 }
                                    ]
                                }
                            }]
                        }
                    """.trimIndent()
                    UnmHttpClient.createMockResponse(request, 200, json)
                }
                url.contains("KW_0") -> {
                    UnmHttpClient.createMockResponse(request, 200, "http://antiserver.kuwo.cn/play/kw_0.mp3")
                }
                else -> UnmHttpClient.createMockResponse(request, 404, "not found")
            }
        }

        val song = SongInfo(id = 204L, name = "晴天", artist = "周杰伦", durationMs = 269_000L)
        val matched = RuleManager.matchAudio(song, priority = listOf("kuwo"))

        assertNotNull("When enableAntiRoll is false, candidate 0 is accepted immediately", matched)
        assertEquals("http://antiserver.kuwo.cn/play/kw_0.mp3", matched?.url)
    }

    // =========================================================================
    // SECTION 3: METADATA TRANSPARENCY TESTS (Propagation to UI & Events)
    // =========================================================================

    /**
     * Challenge 3.1: Verify third-party metadata and HTML entities are accurately sanitized
     * and propagated to MatchedAudio, ReplaceEvent, and ToastNotifier.
     */
    @Test
    fun testMetadataTransparency_HTMLSanitizationAndEventPropagation() {
        var observedToast: String? = null
        var observedEvent: ReplaceEvent? = null

        ToastNotifier.init(fakeContext)
        ToastNotifier.setMainThreadDispatcherForTesting { action -> action() }
        ToastNotifier.setToastShowerForTesting { _, text, _ ->
            observedToast = text.toString()
        }
        ToastNotifier.setTimeProviderForTesting { 100000L }

        val cache = UnmLruCache<Long, SongInfo>(10)
        val originalSong = SongInfo(id = 999L, name = "红豆", artist = "王菲", durationMs = 259_000L)
        cache.put(999L, originalSong)

        // Candidate with HTML entities: &lt;红豆&gt; &amp; 经典现场版&#39;s
        val matchedAudio = MatchedAudio(
            url = "http://stream.kuwo.cn/hongdou.mp3",
            br = 320000,
            source = "kuwo",
            matchedTitle = unescapeHtml("&lt;红豆&gt; &amp; 经典现场版&#39;s"),
            matchedArtist = unescapeHtml("王菲&amp;朋友们"),
            durationMs = 265_000L
        )

        val resolver = PlayerUrlResolver(
            songCache = cache,
            audioMatcher = { _, _ -> matchedAudio },
            configProvider = { UnmConfig(enableToast = true, enablePlayerBadge = true) },
            onTrackReplaced = { event ->
                observedEvent = event
                ToastNotifier.notify(event)
            }
        )

        val playerUrlJson = """
            {
                "code": 200,
                "data": [
                    { "id": 999, "url": null, "br": 0, "trialMode": 1 }
                ]
            }
        """.trimIndent()

        resolver.processPlayerUrl("http://interface.music.163.com/eapi/song/enhance/player/url", playerUrlJson)

        // 1. Verify ReplaceEvent
        assertNotNull("ReplaceEvent must be dispatched", observedEvent)
        assertEquals("<红豆> & 经典现场版's", observedEvent?.matchedTitle)
        assertEquals("王菲&朋友们", observedEvent?.matchedArtist)
        assertEquals("kuwo", observedEvent?.matchedSource)
        assertEquals(320000, observedEvent?.br)
        assertEquals(matchedAudio, observedEvent?.matchedAudio)

        // 2. Verify ToastNotifier message format
        assertNotNull("ToastNotifier must show message", observedToast)
        assertEquals("🎵 云村换源: 已替换为 [酷我]《<红豆> & 经典现场版's》· 320k", observedToast)
    }

    /**
     * Challenge 3.2: BadgeFormatter text when matched title differs from original title.
     * When replaced with a different title/version, the badge should show both source and matched title:
     * "[酷我·320K] 晴天 (Live)"
     * When identical, it should be compact: "[酷我·320K]"
     */
    @Test
    fun testMetadataTransparency_BadgeFormatterDifferentTitleVsSameTitle() {
        val eventDifferent = ReplaceEvent(
            songId = 1L,
            originalTitle = "晴天",
            originalArtist = "周杰伦",
            matchedSource = "kuwo",
            matchedTitle = "晴天 (Live演唱会版)",
            matchedArtist = "周杰伦",
            br = 320000
        )
        val badgeTextDiff = BadgeFormatter.formatBadgeText(eventDifferent)
        assertEquals("[酷我·320K] 晴天 (Live演唱会版)", badgeTextDiff)

        val eventSame = ReplaceEvent(
            songId = 2L,
            originalTitle = "晴天",
            originalArtist = "周杰伦",
            matchedSource = "kuwo",
            matchedTitle = "晴天",
            matchedArtist = "周杰伦",
            br = 320000
        )
        val badgeTextSame = BadgeFormatter.formatBadgeText(eventSame)
        assertEquals("[酷我·320K]", badgeTextSame)
    }

    /**
     * Challenge 3.3: BadgeFormatter dialog and clipboard message accuracy.
     * Verify both dialog and clipboard text contain original and matched title & artist accurately.
     */
    @Test
    fun testMetadataTransparency_DialogAndClipboardContents() {
        val event = ReplaceEvent(
            songId = 777L,
            originalTitle = "七里香",
            originalArtist = "周杰伦",
            matchedSource = "migu",
            matchedTitle = "七里香 (高清母带)",
            matchedArtist = "周杰伦",
            br = 999000
        )

        val dialogMsg = BadgeFormatter.formatDialogMessage(event)
        assertTrue("Dialog must contain original title", dialogMsg.contains("原曲: 《七里香》 - 周杰伦"))
        assertTrue("Dialog must contain matched title", dialogMsg.contains("换源: 《七里香 (高清母带)》 - 周杰伦"))
        assertTrue("Dialog must contain platform", dialogMsg.contains("平台: 咪咕音乐"))
        assertTrue("Dialog must contain FLAC quality", dialogMsg.contains("音质: FLAC (999 kbps)"))

        val clipboardText = BadgeFormatter.formatClipboardText(event)
        assertTrue("Clipboard must contain original title", clipboardText.contains("原曲: 《七里香》 - 周杰伦"))
        assertTrue("Clipboard must contain matched title", clipboardText.contains("换源: 《七里香 (高清母带)》 - 周杰伦"))
        assertTrue("Clipboard must contain platform", clipboardText.contains("平台: 咪咕音乐"))
        assertTrue("Clipboard must contain song ID", clipboardText.contains("歌曲ID: 777"))
    }

    /**
     * Challenge 3.4: ReplaceEvent constructor fallback when matched metadata is null or blank.
     */
    @Test
    fun testMetadataTransparency_FallbackWhenMatchedTitleIsNull() {
        val audioWithoutMetadata = MatchedAudio(
            url = "http://stream.bodian.cn/audio.mp3",
            br = 320000,
            source = "bodian",
            matchedTitle = null,
            matchedArtist = "",
            durationMs = null
        )

        val event = ReplaceEvent(
            songId = 888L,
            originalTitle = "夜曲",
            originalArtist = "周杰伦",
            matchedAudio = audioWithoutMetadata
        )

        assertEquals("When matchedTitle is null, fallback to originalTitle", "夜曲", event.matchedTitle)
        assertEquals("When matchedArtist is blank, fallback to originalArtist", "周杰伦", event.matchedArtist)
        assertEquals("bodian", event.matchedSource)
        assertEquals(320000, event.br)
    }
}
