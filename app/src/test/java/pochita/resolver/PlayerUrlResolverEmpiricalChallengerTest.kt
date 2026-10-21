package pochita.resolver

import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import pochita.common.UnmLruCache
import pochita.config.ConfigManager
import pochita.config.FakeSharedPreferences
import pochita.config.UnmConfig
import pochita.data.PreferenceKeys
import pochita.model.MatchedAudio
import pochita.model.ReplaceEvent
import pochita.model.SongInfo

/**
 * Independent Empirical Challenge Suite for [PlayerUrlResolver] dynamic configuration integration.
 *
 * Authored by: M1 Challenger 2
 * Scope:
 * 1. Dynamic sound source priority switching and immediate alteration of candidate selection.
 * 2. Audio quality level mapping (flac, 320k, 192k, 128k) and immediate br/level/type rewriting.
 * 3. Empty, missing, corrupted, and adversarial configuration fallback to default 320k.
 * 4. ReplaceEvent metadata integrity and feedback flag filtering.
 * 5. High-concurrency race condition and mutation stress testing.
 */
class PlayerUrlResolverEmpiricalChallengerTest {

    private lateinit var songCache: UnmLruCache<Long, SongInfo>
    private lateinit var fakePrefs: FakeSharedPreferences
    private val playerUrlEndpoint = "https://interface.music.163.com/eapi/song/enhance/player/url"

    @Before
    fun setUp() {
        ConfigManager.resetForTesting()
        fakePrefs = FakeSharedPreferences()
        ConfigManager.init(fakePrefs)
        songCache = UnmLruCache(200)
    }

    @After
    fun tearDown() {
        ConfigManager.resetForTesting()
    }

    private fun createRestrictedPayload(songId: Long, currentBr: Int = 0, currentCode: Int = 404): String {
        return JSONObject().apply {
            put("code", 200)
            put("data", JSONArray().apply {
                put(JSONObject().apply {
                    put("id", songId)
                    put("url", "")
                    put("br", currentBr)
                    put("code", currentCode)
                    put("fee", 8)
                    put("trialMode", 1)
                    put("freeTrialInfo", JSONObject("""{"start":0,"end":30}"""))
                })
            })
        }.toString()
    }

    // =========================================================================
    // SECTION 1: Dynamic Sound Source Priority Switching
    // =========================================================================

    @Test
    fun testPriorityReorderingAltersCandidateSelectionImmediately() {
        val songId = 90001L
        val song = SongInfo(songId, "晴天", "周杰伦", 269000L)
        songCache.put(songId, song)

        // Multiple available sound sources providing mock audio
        val mockAudioCandidates = mapOf(
            "bodian" to MatchedAudio("https://audio.bodian.cn/qt.mp3", source = "bodian", br = 320000),
            "kuwo" to MatchedAudio("https://audio.kuwo.cn/qt.mp3", source = "kuwo", br = 320000),
            "migu" to MatchedAudio("https://audio.migu.cn/qt.mp3", source = "migu", br = 320000),
            "kugou" to MatchedAudio("https://audio.kugou.com/qt.mp3", source = "kugou", br = 320000)
        )

        val resolver = PlayerUrlResolver(
            songCache = songCache,
            audioMatcher = { _, config ->
                // First candidate present in config.enabledSources wins
                val chosenSource = config.enabledSources.firstOrNull { mockAudioCandidates.containsKey(it) }
                chosenSource?.let { mockAudioCandidates[it] }
            },
            songDetailFetcher = null,
            configProvider = { ConfigManager.getConfig() }
        )

        // Scenario 1: Default priority [bodian, kuwo, migu, kugou] -> Bodian must win
        val result1 = resolver.processPlayerUrl(playerUrlEndpoint, createRestrictedPayload(songId))
        assertNotNull(result1)
        val item1 = JSONObject(result1!!).getJSONArray("data").getJSONObject(0)
        assertEquals("https://audio.bodian.cn/qt.mp3", item1.getString("url"))

        // Scenario 2: Dynamic mutation to [kuwo, bodian] -> Kuwo must win immediately
        fakePrefs.edit()
            .putString(PreferenceKeys.KEY_ENABLED_SOURCES, "kuwo,bodian")
            .apply()

        val result2 = resolver.processPlayerUrl(playerUrlEndpoint, createRestrictedPayload(songId))
        assertNotNull(result2)
        val item2 = JSONObject(result2!!).getJSONArray("data").getJSONObject(0)
        assertEquals("https://audio.kuwo.cn/qt.mp3", item2.getString("url"))

        // Scenario 3: Dynamic mutation to [kugou, migu] -> Kugou must win immediately
        fakePrefs.edit()
            .putString(PreferenceKeys.KEY_ENABLED_SOURCES, "kugou,migu")
            .apply()

        val result3 = resolver.processPlayerUrl(playerUrlEndpoint, createRestrictedPayload(songId))
        assertNotNull(result3)
        val item3 = JSONObject(result3!!).getJSONArray("data").getJSONObject(0)
        assertEquals("https://audio.kugou.com/qt.mp3", item3.getString("url"))

        // Scenario 4: Dynamic mutation to [migu] -> Migu must win
        fakePrefs.edit()
            .putString(PreferenceKeys.KEY_ENABLED_SOURCES, "migu")
            .apply()

        val result4 = resolver.processPlayerUrl(playerUrlEndpoint, createRestrictedPayload(songId))
        assertNotNull(result4)
        val item4 = JSONObject(result4!!).getJSONArray("data").getJSONObject(0)
        assertEquals("https://audio.migu.cn/qt.mp3", item4.getString("url"))
    }

    @Test
    fun testDisabledSourceCandidatesAreCompletelySkipped() {
        val songId = 90002L
        songCache.put(songId, SongInfo(songId, "七里香", "周杰伦", 299000L))

        var evaluatedSources = mutableListOf<String>()

        val resolver = PlayerUrlResolver(
            songCache = songCache,
            audioMatcher = { _, config ->
                evaluatedSources.addAll(config.enabledSources)
                if (config.enabledSources.contains("migu")) {
                    MatchedAudio("https://audio.migu.cn/qlx.mp3", source = "migu", br = 320000)
                } else {
                    null
                }
            },
            songDetailFetcher = null,
            configProvider = { ConfigManager.getConfig() }
        )

        // Only kugou enabled; migu disabled -> resolution should return null
        fakePrefs.edit()
            .putString(PreferenceKeys.KEY_ENABLED_SOURCES, "kugou")
            .apply()

        val result = resolver.processPlayerUrl(playerUrlEndpoint, createRestrictedPayload(songId))
        assertNull("When available source is disabled, resolver must return null", result)
        assertEquals(listOf("kugou"), evaluatedSources)
    }

    @Test
    fun testEmptySourcesConfigShortCircuitsImmediatelyWithoutInvokingMatcher() {
        val songId = 90003L
        songCache.put(songId, SongInfo(songId, "夜曲", "周杰伦", 226000L))

        var matcherInvoked = false

        // Provide an explicit empty config override
        val resolver = PlayerUrlResolver(
            songCache = songCache,
            audioMatcher = { _, _ ->
                matcherInvoked = true
                MatchedAudio("https://audio.kuwo.cn/yq.mp3")
            },
            songDetailFetcher = null,
            configProvider = { UnmConfig(enabledSources = emptyList()) }
        )

        val result = resolver.processPlayerUrl(playerUrlEndpoint, createRestrictedPayload(songId))
        assertNull("Empty sources list must short-circuit resolution", result)
        assertFalse("Matcher must never be invoked when sources list is empty", matcherInvoked)
    }

    // =========================================================================
    // SECTION 2: Audio Quality Level Mapping (flac, 320k, 192k, 128k)
    // =========================================================================

    @Test
    fun testQualityLevelMappingMatrix() {
        data class QualityTestCase(
            val quality: String,
            val expectedBitrate: Int,
            val expectedLevel: String,
            val expectedFormat: String,
            val matchedUrl: String
        )

        val testCases = listOf(
            QualityTestCase("flac", 999000, "lossless", "flac", "https://audio.kuwo.cn/track.flac"),
            QualityTestCase("320k", 320000, "exhigh", "mp3", "https://audio.kuwo.cn/track.mp3"),
            QualityTestCase("192k", 192000, "higher", "mp3", "https://audio.kuwo.cn/track.mp3"),
            QualityTestCase("128k", 128000, "standard", "mp3", "https://audio.kuwo.cn/track.mp3")
        )

        for (tc in testCases) {
            val songId = 91000L + tc.expectedBitrate
            songCache.put(songId, SongInfo(songId, "TestSong_${tc.quality}", "Artist", 200000L))

            fakePrefs.edit()
                .putString(PreferenceKeys.KEY_PREFERRED_QUALITY, tc.quality)
                .apply()

            val resolver = PlayerUrlResolver(
                songCache = songCache,
                audioMatcher = { _, config ->
                    MatchedAudio(tc.matchedUrl, source = "kuwo", br = config.targetBitrate())
                },
                songDetailFetcher = null,
                configProvider = { ConfigManager.getConfig() }
            )

            val resultJson = resolver.processPlayerUrl(playerUrlEndpoint, createRestrictedPayload(songId))
            assertNotNull("Processing for quality ${tc.quality} must succeed", resultJson)

            val item = JSONObject(resultJson!!).getJSONArray("data").getJSONObject(0)
            assertEquals("Quality ${tc.quality} bitrate mismatch", tc.expectedBitrate, item.getInt("br"))
            assertEquals("Quality ${tc.quality} level mismatch", tc.expectedLevel, item.getString("level"))
            assertEquals("Quality ${tc.quality} type mismatch", tc.expectedFormat, item.getString("type"))
            assertEquals("URL must match", tc.matchedUrl, item.getString("url"))
            assertEquals("code must be 200", 200, item.getInt("code"))
            assertEquals("fee must be 0", 0, item.getInt("fee"))
            assertEquals("payed must be 1", 1, item.getInt("payed"))
        }
    }

    @Test
    fun testQualitySwitchingDynamicallyUpdatesNextResolutionImmediately() {
        val songId = 91500L
        songCache.put(songId, SongInfo(songId, "枫", "周杰伦", 275000L))

        val resolver = PlayerUrlResolver(
            songCache = songCache,
            audioMatcher = { _, config ->
                MatchedAudio("https://audio.kuwo.cn/feng.mp3", source = "kuwo", br = 0)
            },
            songDetailFetcher = null,
            configProvider = { ConfigManager.getConfig() }
        )

        // 1. Initial quality: 320k
        fakePrefs.edit().putString(PreferenceKeys.KEY_PREFERRED_QUALITY, "320k").apply()
        var item = JSONObject(resolver.processPlayerUrl(playerUrlEndpoint, createRestrictedPayload(songId))!!)
            .getJSONArray("data").getJSONObject(0)
        assertEquals(320000, item.getInt("br"))
        assertEquals("exhigh", item.getString("level"))
        assertEquals("mp3", item.getString("type"))

        // 2. Hot-switch to FLAC
        fakePrefs.edit().putString(PreferenceKeys.KEY_PREFERRED_QUALITY, "flac").apply()
        item = JSONObject(resolver.processPlayerUrl(playerUrlEndpoint, createRestrictedPayload(songId))!!)
            .getJSONArray("data").getJSONObject(0)
        assertEquals(999000, item.getInt("br"))
        assertEquals("lossless", item.getString("level"))
        assertEquals("flac", item.getString("type"))

        // 3. Hot-switch to 128k
        fakePrefs.edit().putString(PreferenceKeys.KEY_PREFERRED_QUALITY, "128k").apply()
        item = JSONObject(resolver.processPlayerUrl(playerUrlEndpoint, createRestrictedPayload(songId))!!)
            .getJSONArray("data").getJSONObject(0)
        assertEquals(128000, item.getInt("br"))
        assertEquals("standard", item.getString("level"))
        assertEquals("mp3", item.getString("type"))

        // 4. Hot-switch to 192k
        fakePrefs.edit().putString(PreferenceKeys.KEY_PREFERRED_QUALITY, "192k").apply()
        item = JSONObject(resolver.processPlayerUrl(playerUrlEndpoint, createRestrictedPayload(songId))!!)
            .getJSONArray("data").getJSONObject(0)
        assertEquals(192000, item.getInt("br"))
        assertEquals("higher", item.getString("level"))
        assertEquals("mp3", item.getString("type"))
    }

    @Test
    fun testFlacContainerDetectionOverridesMp3WhenUrlEndsWithFlac() {
        val songId = 91600L
        songCache.put(songId, SongInfo(songId, "借口", "周杰伦", 258000L))

        // Preferred quality is 192k (mp3), but matched URL has .flac stream
        fakePrefs.edit().putString(PreferenceKeys.KEY_PREFERRED_QUALITY, "192k").apply()

        val resolver = PlayerUrlResolver(
            songCache = songCache,
            audioMatcher = { _, _ ->
                MatchedAudio("https://audio.migu.cn/stream.flac", source = "migu", br = 192000)
            },
            songDetailFetcher = null,
            configProvider = { ConfigManager.getConfig() }
        )

        val resultJson = resolver.processPlayerUrl(playerUrlEndpoint, createRestrictedPayload(songId))
        assertNotNull(resultJson)
        val item = JSONObject(resultJson!!).getJSONArray("data").getJSONObject(0)
        assertEquals(192000, item.getInt("br"))
        assertEquals("higher", item.getString("level"))
        assertEquals("Container type must be 'flac' when stream URL contains .flac", "flac", item.getString("type"))
    }

    @Test
    fun testCaseInsensitiveQualityHandling() {
        val uppercaseQualities = listOf("FLAC", "320K", "192K", "128K")
        for (uq in uppercaseQualities) {
            val cfg = UnmConfig(preferredQuality = uq)
            when (uq) {
                "FLAC" -> {
                    assertEquals(999000, cfg.targetBitrate())
                    assertEquals("lossless", cfg.targetLevel())
                    assertEquals("flac", cfg.targetFormat())
                }
                "320K" -> {
                    assertEquals(320000, cfg.targetBitrate())
                    assertEquals("exhigh", cfg.targetLevel())
                    assertEquals("mp3", cfg.targetFormat())
                }
                "192K" -> {
                    assertEquals(192000, cfg.targetBitrate())
                    assertEquals("higher", cfg.targetLevel())
                    assertEquals("mp3", cfg.targetFormat())
                }
                "128K" -> {
                    assertEquals(128000, cfg.targetBitrate())
                    assertEquals("standard", cfg.targetLevel())
                    assertEquals("mp3", cfg.targetFormat())
                }
            }
        }
    }

    // =========================================================================
    // SECTION 3: Empty, Corrupt, and Adversarial Configuration Fallback
    // =========================================================================

    @Test
    fun testCorruptedOrUnknownQualityFallsBackTo320kSafely() {
        val weirdQualities = listOf(
            "unknown_master",
            "99999k",
            "lossless_ultra",
            "",
            "   ",
            "null",
            "undefined",
            "!@#$%^&*",
            "0k",
            "320"
        )

        val songId = 92000L
        songCache.put(songId, SongInfo(songId, "测试歌曲", "歌手", 200000L))

        for (weird in weirdQualities) {
            fakePrefs.edit()
                .putString(PreferenceKeys.KEY_PREFERRED_QUALITY, weird)
                .apply()

            val activeConfig = ConfigManager.getConfig()
            assertEquals("Corrupt quality '$weird' must fallback to 320k in ConfigManager", "320k", activeConfig.preferredQuality)
            assertEquals(320000, activeConfig.targetBitrate())
            assertEquals("exhigh", activeConfig.targetLevel())
            assertEquals("mp3", activeConfig.targetFormat())

            val resolver = PlayerUrlResolver(
                songCache = songCache,
                audioMatcher = { _, config ->
                    MatchedAudio("https://audio.fallback/track.mp3", source = "fallback", br = 0)
                },
                songDetailFetcher = null,
                configProvider = { activeConfig }
            )

            val resultJson = resolver.processPlayerUrl(playerUrlEndpoint, createRestrictedPayload(songId))
            assertNotNull("Processing with corrupted quality '$weird' must succeed via fallback", resultJson)
            val item = JSONObject(resultJson!!).getJSONArray("data").getJSONObject(0)
            assertEquals(320000, item.getInt("br"))
            assertEquals("exhigh", item.getString("level"))
            assertEquals("mp3", item.getString("type"))
        }
    }

    @Test
    fun testTypeMismatchInPreferencesHandledWithoutCrash() {
        val songId = 92100L
        songCache.put(songId, SongInfo(songId, "倒带", "蔡依林", 240000L))

        // Intentionally corrupt SharedPreferences by putting Integers and Booleans
        // where Strings are expected, and vice versa
        fakePrefs.putRaw(PreferenceKeys.KEY_PREFERRED_QUALITY, 320000) // Int instead of String
        fakePrefs.putRaw(PreferenceKeys.KEY_ENABLED_SOURCES, true) // Boolean instead of String
        fakePrefs.putRaw(PreferenceKeys.KEY_ENABLE_TOAST, "invalid_boolean") // String instead of Boolean
        fakePrefs.putRaw(PreferenceKeys.KEY_ENABLE_PLAYER_BADGE, 9999) // Int instead of Boolean

        val config = ConfigManager.loadFromPreferences(fakePrefs)
        assertEquals("320k", config.preferredQuality)
        assertEquals(UnmConfig.DEFAULT_ENABLED_SOURCES, config.enabledSources)
        assertEquals(320000, config.targetBitrate())
        assertEquals("exhigh", config.targetLevel())

        val resolver = PlayerUrlResolver(
            songCache = songCache,
            audioMatcher = { _, _ ->
                MatchedAudio("https://audio.kuwo.cn/dd.mp3", source = "kuwo", br = 320000)
            },
            songDetailFetcher = null,
            configProvider = { config }
        )

        val resultJson = resolver.processPlayerUrl(playerUrlEndpoint, createRestrictedPayload(songId))
        assertNotNull("Type mismatch in prefs must be handled defensively without throwing", resultJson)
        val item = JSONObject(resultJson!!).getJSONArray("data").getJSONObject(0)
        assertEquals(320000, item.getInt("br"))
        assertEquals("exhigh", item.getString("level"))
    }

    @Test
    fun testCorruptedSourcesStringFallsBackToDefaultSources() {
        val corruptSourcesInputs = listOf(
            "[broken json {array",
            "[,],,,",
            "unsupported1,unsupported2,unsupported3",
            "   ",
            ""
        )

        for (input in corruptSourcesInputs) {
            fakePrefs.edit()
                .putString(PreferenceKeys.KEY_ENABLED_SOURCES, input)
                .apply()

            val config = ConfigManager.loadFromPreferences(fakePrefs)
            assertEquals(
                "Input '$input' must safely fall back to default sound sources",
                UnmConfig.DEFAULT_ENABLED_SOURCES,
                config.enabledSources
            )
        }
    }

    // =========================================================================
    // SECTION 4: ReplaceEvent Integrity and Feedback Gating
    // =========================================================================

    @Test
    fun testReplaceEventReceivesExactMetadataWithFallbacks() {
        val songId = 93001L
        songCache.put(songId, SongInfo(songId, "以父之名", "周杰伦", 342000L))

        var dispatchedEvent: ReplaceEvent? = null

        val resolver = PlayerUrlResolver(
            songCache = songCache,
            audioMatcher = { song, config ->
                MatchedAudio(
                    url = "https://audio.kuwo.cn/yfzm.mp3",
                    source = "kuwo",
                    br = 320000,
                    matchedTitle = "", // Blank matchedTitle -> should fallback to song.name
                    matchedArtist = "  " // Blank matchedArtist -> should fallback to song.artist
                )
            },
            songDetailFetcher = null,
            configProvider = { UnmConfig(enableToast = true, enablePlayerBadge = false) },
            onTrackReplaced = { event -> dispatchedEvent = event }
        )

        val result = resolver.processPlayerUrl(playerUrlEndpoint, createRestrictedPayload(songId))
        assertNotNull(result)
        assertNotNull(dispatchedEvent)

        assertEquals(songId, dispatchedEvent?.songId)
        assertEquals("以父之名", dispatchedEvent?.originalTitle)
        assertEquals("周杰伦", dispatchedEvent?.originalArtist)
        assertEquals("kuwo", dispatchedEvent?.matchedSource)
        assertEquals("Blank matchedTitle should fallback to originalTitle", "以父之名", dispatchedEvent?.matchedTitle)
        assertEquals("Blank matchedArtist should fallback to originalArtist", "周杰伦", dispatchedEvent?.matchedArtist)
        assertEquals(320000, dispatchedEvent?.br)
    }

    @Test
    fun testReplaceEventGatedProperlyByFeedbackFlags() {
        val songId = 93002L
        songCache.put(songId, SongInfo(songId, "轨迹", "周杰伦", 330000L))

        // Subcase 1: toast=true, badge=false -> Should dispatch
        var dispatchCount = 0
        var resolver = PlayerUrlResolver(
            songCache = songCache,
            audioMatcher = { _, _ -> MatchedAudio("https://audio/track.mp3", source = "kuwo") },
            songDetailFetcher = null,
            configProvider = { UnmConfig(enableToast = true, enablePlayerBadge = false) },
            onTrackReplaced = { dispatchCount++ }
        )
        resolver.processPlayerUrl(playerUrlEndpoint, createRestrictedPayload(songId))
        assertEquals(1, dispatchCount)

        // Subcase 2: toast=false, badge=true -> Should dispatch
        resolver = PlayerUrlResolver(
            songCache = songCache,
            audioMatcher = { _, _ -> MatchedAudio("https://audio/track.mp3", source = "kuwo") },
            songDetailFetcher = null,
            configProvider = { UnmConfig(enableToast = false, enablePlayerBadge = true) },
            onTrackReplaced = { dispatchCount++ }
        )
        resolver.processPlayerUrl(playerUrlEndpoint, createRestrictedPayload(songId))
        assertEquals(2, dispatchCount)

        // Subcase 3: toast=false, badge=false -> Must NOT dispatch
        resolver = PlayerUrlResolver(
            songCache = songCache,
            audioMatcher = { _, _ -> MatchedAudio("https://audio/track.mp3", source = "kuwo") },
            songDetailFetcher = null,
            configProvider = { UnmConfig(enableToast = false, enablePlayerBadge = false) },
            onTrackReplaced = { dispatchCount++ }
        )
        resolver.processPlayerUrl(playerUrlEndpoint, createRestrictedPayload(songId))
        assertEquals("Must not dispatch when both feedback switches are false", 2, dispatchCount)
    }

    // =========================================================================
    // SECTION 5: High-Concurrency Race Condition and Live Mutation Stress
    // =========================================================================

    @Test
    fun testConcurrentResolutionsUnderRapidConfigMutations() {
        val threadCount = 8
        val iterationsPerThread = 100
        val executor = Executors.newFixedThreadPool(threadCount + 1)
        val startLatch = CountDownLatch(1)
        val doneLatch = CountDownLatch(threadCount)
        val isMutating = AtomicBoolean(true)
        val errorCounter = AtomicInteger(0)

        // Seed cache with 50 songs
        for (i in 0 until 50) {
            val id = 95000L + i
            songCache.put(id, SongInfo(id, "ConcurrentTrack_$id", "Artist_$id", 200000L))
        }

        val resolver = PlayerUrlResolver(
            songCache = songCache,
            audioMatcher = { song, config ->
                val primarySource = config.enabledSources.firstOrNull() ?: "fallback"
                MatchedAudio(
                    url = "https://cdn.live/${song.id}_${config.preferredQuality}.mp3",
                    source = primarySource,
                    br = config.targetBitrate()
                )
            },
            songDetailFetcher = null,
            configProvider = { ConfigManager.getConfig() }
        )

        // Thread 0: Rapidly mutates configuration
        executor.submit {
            startLatch.await()
            val qualities = listOf("320k", "flac", "192k", "128k")
            val sourceOrders = listOf(
                "bodian,kuwo,migu,kugou",
                "kuwo,bodian",
                "migu,kugou,kuwo",
                "kugou,bodian"
            )
            var step = 0
            while (isMutating.get()) {
                val q = qualities[step % qualities.size]
                val s = sourceOrders[step % sourceOrders.size]
                fakePrefs.edit()
                    .putString(PreferenceKeys.KEY_PREFERRED_QUALITY, q)
                    .putString(PreferenceKeys.KEY_ENABLED_SOURCES, s)
                    .putBoolean(PreferenceKeys.KEY_ENABLE_TOAST, step % 2 == 0)
                    .putBoolean(PreferenceKeys.KEY_ENABLE_PLAYER_BADGE, step % 3 == 0)
                    .apply()
                step++
                Thread.sleep(2)
            }
        }

        // Worker threads: Continuously resolve player URLs under active mutation
        for (t in 0 until threadCount) {
            executor.submit {
                try {
                    startLatch.await()
                    for (i in 0 until iterationsPerThread) {
                        val songId = 95000L + (i % 50)
                        val payload = createRestrictedPayload(songId)
                        val res = resolver.processPlayerUrl(playerUrlEndpoint, payload)
                        if (res == null) {
                            errorCounter.incrementAndGet()
                            continue
                        }

                        val obj = JSONObject(res)
                        val data = obj.getJSONArray("data")
                        val item = data.getJSONObject(0)

                        val br = item.getInt("br")
                        val level = item.getString("level")
                        val type = item.getString("type")

                        // Invariant: br, level, and type must be mutually consistent
                        when (br) {
                            999000 -> {
                                if (level != "lossless" || type != "flac") errorCounter.incrementAndGet()
                            }
                            320000 -> {
                                if (level != "exhigh" || type != "mp3") errorCounter.incrementAndGet()
                            }
                            192000 -> {
                                if (level != "higher" || type != "mp3") errorCounter.incrementAndGet()
                            }
                            128000 -> {
                                if (level != "standard" || type != "mp3") errorCounter.incrementAndGet()
                            }
                            else -> {
                                errorCounter.incrementAndGet()
                            }
                        }
                    }
                } catch (t: Throwable) {
                    errorCounter.incrementAndGet()
                } finally {
                    doneLatch.countDown()
                }
            }
        }

        startLatch.countDown()
        assertTrue("Concurrent stress test timed out after 10s", doneLatch.await(10, TimeUnit.SECONDS))
        isMutating.set(false)
        executor.shutdown()

        assertEquals("Zero errors expected during concurrent resolution under rapid mutation", 0, errorCounter.get())
    }
}
