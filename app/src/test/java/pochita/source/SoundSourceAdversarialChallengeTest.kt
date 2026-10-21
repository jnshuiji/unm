package pochita.source

import java.io.IOException
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import javax.net.ssl.SSLHandshakeException
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import pochita.common.UnmHttpClient
import pochita.model.SongInfo
import pochita.rule.RuleManager

/**
 * Empirical adversarial stress and challenge test suite for Milestone 3:
 * - Offline deterministic execution with [UnmHttpClient.setMockHandler].
 * - Boundary fuzzing of [AudioValidator] (teaser IDs, URL schemes, durations, keywords).
 * - Thread-safety, host protection, and fallback robustness of [QuickJsEngine].
 * - Fault injection and edge cases in [NativeSources] (Bodian, Kuwo, Migu, Kugou).
 * - Multi-tier dry-run and error rollback in [RuleManager].
 */
class SoundSourceAdversarialChallengeTest {

    @Before
    fun setUp() {
        UnmHttpClient.reset()
        AudioValidator.reset()
        QuickJsEngine.resetForTesting()
        RuleManager.resetForTest()
    }

    @After
    fun tearDown() {
        RuleManager.resetForTest()
        QuickJsEngine.resetForTesting()
        AudioValidator.reset()
        UnmHttpClient.reset()
    }

    // =========================================================================
    // PART 1: AudioValidator Adversarial Fuzzing & Boundary Challenges
    // =========================================================================

    @Test
    fun challengeAudioValidatorKnownTeaserIdsAndVariations() {
        // 1. Exact default IDs (numeric)
        assertTrue(AudioValidator.isBlacklistedAudioId(588957081L))
        assertTrue(AudioValidator.isBlacklistedAudioId(563182854L))

        // 2. String representation with whitespace
        assertTrue(AudioValidator.isBlacklistedAudioId("588957081"))
        assertTrue(AudioValidator.isBlacklistedAudioId("563182854"))
        assertTrue(AudioValidator.isBlacklistedAudioId("  588957081\t"))
        assertTrue(AudioValidator.isBlacklistedAudioId("\n563182854  "))

        // 3. URLs embedding teaser IDs in path, query, or filename
        assertFalse(AudioValidator.isValidAudioUrl("http://antiserver.kuwo.cn/anti.s?rid=MUSIC_588957081&format=mp3"))
        assertFalse(AudioValidator.isValidAudioUrl("https://bd-api.kuwo.cn/api/play/563182854.mp3?token=abc"))
        assertFalse(AudioValidator.isValidAudioUrl("http://cdn.example.com/audio/588957081/stream.flac"))
        assertFalse(AudioValidator.isValidAudioUrl("http://cdn.example.com/play?id=563182854#section"))

        // 4. Non-blacklisted IDs pass
        assertFalse(AudioValidator.isBlacklistedAudioId(0L))
        assertFalse(AudioValidator.isBlacklistedAudioId(-1L))
        assertFalse(AudioValidator.isBlacklistedAudioId(1851652156L))
        assertFalse(AudioValidator.isBlacklistedAudioId(Long.MAX_VALUE))
        assertFalse(AudioValidator.isBlacklistedAudioId(Long.MIN_VALUE))
        assertFalse(AudioValidator.isBlacklistedAudioId("NOT_A_NUMBER"))
        assertFalse(AudioValidator.isBlacklistedAudioId(""))
    }

    @Test
    fun challengeAudioValidatorPathologicalUrlSchemes() {
        // Valid schemes
        assertTrue(AudioValidator.isValidAudioUrl("http://stream.music.com/audio.mp3"))
        assertTrue(AudioValidator.isValidAudioUrl("https://stream.music.com/audio.mp3"))
        assertTrue(AudioValidator.isValidAudioUrl("HTTP://STREAM.MUSIC.COM/AUDIO.MP3"))
        assertTrue(AudioValidator.isValidAudioUrl("HTTPS://STREAM.MUSIC.COM/AUDIO.MP3"))
        assertTrue(AudioValidator.isValidAudioUrl("Http://Stream.Music.Com/Audio.Mp3"))

        // Pathological / prohibited schemes
        val forbiddenSchemes = listOf(
            "ftp://files.example.com/audio.mp3",
            "file:///sdcard/Music/secret.mp3",
            "file:///etc/passwd",
            "data:audio/mp3;base64,SUQzBAAAAAAAI1RTU0UAAAAPAAADTGF2ZjU4Ljc2LjEwMAAAAAAAAAAAAAAA",
            "javascript:alert(document.cookie)",
            "content://media/external/audio/media/12345",
            "blob:http://example.com/d3b07384-d113-4a11-8e01-224641d40131",
            "ws://live.example.com/stream",
            "wss://live.example.com/stream",
            "tcp://127.0.0.1:8080/audio",
            "intent://view#Intent;scheme=http;package=com.evil;end",
            "market://details?id=com.netease.cloudmusic"
        )
        for (url in forbiddenSchemes) {
            assertFalse("Prohibited scheme must be rejected: $url", AudioValidator.isValidAudioUrl(url))
        }

        // Malformed strings & edge cases
        val degenerateUrls = listOf(
            null,
            "",
            "   ",
            "\t\r\n",
            "null",
            "NULL",
            "undefined",
            "UNDEFINED",
            "error",
            "ERROR",
            "http:",
            "http:/",
            "http://",
            "http:///",
            "https:",
            "https:/",
            "https://",
            "https:///",
            "://missing.scheme.com/audio.mp3",
            "/relative/path/to/song.mp3",
            "stream.music.com/audio.mp3"
        )
        for (url in degenerateUrls) {
            assertFalse("Degenerate URL must be rejected: '$url'", AudioValidator.isValidAudioUrl(url))
        }
    }

    @Test
    fun challengeAudioValidatorDurationHeuristicBoundaries() {
        // Case 1: expected > 60,000ms and actual in 1..30s -> TEASER!
        assertTrue(AudioValidator.isTeaserDuration(expectedDurationMs = 60_001L, actualDurationSec = 1))
        assertTrue(AudioValidator.isTeaserDuration(expectedDurationMs = 60_001L, actualDurationSec = 15))
        assertTrue(AudioValidator.isTeaserDuration(expectedDurationMs = 60_001L, actualDurationSec = 30))
        assertTrue(AudioValidator.isTeaserDuration(expectedDurationMs = 300_000L, actualDurationSec = 29))

        // Boundary: expected exactly 60,000ms (NOT > 60,000ms) -> NOT classified as teaser
        assertFalse(AudioValidator.isTeaserDuration(expectedDurationMs = 60_000L, actualDurationSec = 15))
        assertFalse(AudioValidator.isTeaserDuration(expectedDurationMs = 59_999L, actualDurationSec = 15))

        // Boundary: actual = 0 (unreported duration) -> NOT teaser
        assertFalse(AudioValidator.isTeaserDuration(expectedDurationMs = 180_000L, actualDurationSec = 0))

        // Boundary: actual = 31s (exceeds 30s teaser window) -> NOT teaser
        assertFalse(AudioValidator.isTeaserDuration(expectedDurationMs = 180_000L, actualDurationSec = 31))

        // Negative / extreme values
        assertFalse(AudioValidator.isTeaserDuration(expectedDurationMs = -1L, actualDurationSec = 15))
        assertFalse(AudioValidator.isTeaserDuration(expectedDurationMs = 180_000L, actualDurationSec = -1))
        assertFalse(AudioValidator.isTeaserDuration(expectedDurationMs = 0L, actualDurationSec = 20))
    }

    @Test
    fun challengeAudioValidatorDurationToleranceSymmetricStress() {
        val target = 200_000L // 200s

        // Within default tolerance 6000ms
        assertTrue(AudioValidator.isDurationAcceptable(target, 200_000L)) // 0ms diff
        assertTrue(AudioValidator.isDurationAcceptable(target, 206_000L)) // +6000ms diff
        assertTrue(AudioValidator.isDurationAcceptable(target, 194_000L)) // -6000ms diff
        assertTrue(AudioValidator.isDurationAcceptable(target, 205_999L)) // +5999ms diff
        assertTrue(AudioValidator.isDurationAcceptable(target, 194_001L)) // -5999ms diff

        // Outside default tolerance 6000ms
        assertFalse(AudioValidator.isDurationAcceptable(target, 206_001L)) // +6001ms diff
        assertFalse(AudioValidator.isDurationAcceptable(target, 193_999L)) // -6001ms diff
        assertFalse(AudioValidator.isDurationAcceptable(target, 300_000L)) // +100s diff
        assertFalse(AudioValidator.isDurationAcceptable(target, 50_000L))  // -150s diff

        // Missing / zero durations pass through safely
        assertTrue(AudioValidator.isDurationAcceptable(targetDurationMs = 0L, candidateDurationMs = 200_000L))
        assertTrue(AudioValidator.isDurationAcceptable(targetDurationMs = 200_000L, candidateDurationMs = 0L))
        assertTrue(AudioValidator.isDurationAcceptable(targetDurationMs = -1L, candidateDurationMs = 200_000L))
        assertTrue(AudioValidator.isDurationAcceptable(targetDurationMs = 200_000L, candidateDurationMs = -1L))

        // Custom tolerance
        assertTrue(AudioValidator.isDurationAcceptable(100_000L, 101_000L, toleranceMs = 1500L))
        assertFalse(AudioValidator.isDurationAcceptable(100_000L, 102_000L, toleranceMs = 1500L))
        assertTrue(AudioValidator.isDurationAcceptable(100_000L, 100_000L, toleranceMs = 0L))
    }

    @Test
    fun challengeAudioValidatorBannedKeywordExhaustiveCombinations() {
        val testCases = listOf(
            "晴天 (试听版)" to true,
            "新歌预告片" to true,
            "歌曲由于版权原因已下架" to true,
            "七里香 铃声剪辑" to true,
            "Taylor Swift - Blank Space (Teaser)" to true,
            "Song Preview (Demo Version)" to true,
            "Audio Clip Short Sample" to true,
            "RING-TONE VERSION" to true,
            "DEMO TRACK 2026" to true,
            "PREVIEW EXCLUSIVE" to true,
            "海阔天空" to false,
            "Hotel California - Eagles" to false,
            "忘不掉的你" to false,
            "Simple Love" to false // Contains "sample"? No, "Simple" should not match "sample"
        )

        for ((title, expectedBanned) in testCases) {
            assertEquals("Banned check failed for '$title'", expectedBanned, AudioValidator.isBannedKeyword(title))
        }
    }

    @Test
    fun challengeAudioValidatorThreadSafetyUnderHighConcurrency() {
        val threadCount = 16
        val operationsPerThread = 200
        val executor = Executors.newFixedThreadPool(threadCount)
        val latch = CountDownLatch(threadCount)
        val errorCounter = AtomicInteger(0)

        for (t in 0 until threadCount) {
            executor.submit {
                try {
                    for (i in 0 until operationsPerThread) {
                        val id = 900000000L + (t * 1000 + i)
                        if (i % 4 == 0) {
                            AudioValidator.addBlacklistedAudioId(id)
                            assertTrue(AudioValidator.isBlacklistedAudioId(id))
                            AudioValidator.removeBlacklistedAudioId(id)
                        } else if (i % 4 == 1) {
                            val kw = "custom_teaser_$t"
                            AudioValidator.addBannedKeyword(kw)
                            assertTrue(AudioValidator.isBannedKeyword("Song $kw title"))
                            AudioValidator.removeBannedKeyword(kw)
                        } else {
                            // Concurrent reads
                            assertTrue(AudioValidator.isBlacklistedAudioId(588957081L))
                            assertFalse(AudioValidator.isValidAudioUrl("http://cdn.com/play/563182854.mp3"))
                            assertTrue(AudioValidator.isValidAudioUrl("http://cdn.com/play/normal_song.mp3"))
                            assertTrue(AudioValidator.isBannedKeyword("测试试听"))
                        }
                    }
                } catch (t: Throwable) {
                    errorCounter.incrementAndGet()
                } finally {
                    latch.countDown()
                }
            }
        }

        assertTrue("Concurrent validation tasks should complete promptly", latch.await(10, TimeUnit.SECONDS))
        executor.shutdown()
        assertEquals("Zero concurrency exceptions expected", 0, errorCounter.get())
    }

    // =========================================================================
    // PART 2: Offline Deterministic Execution & Sound Source Adversarial Mocking
    // =========================================================================

    @Test
    fun challengeOfflineDeterminismBodianSourceMockBypassCheck() {
        val requestUrls = mutableListOf<String>()

        UnmHttpClient.setMockHandler { req ->
            val url = req.url.toString()
            requestUrls.add(url)
            when {
                url.contains("/advert/watch") -> {
                    UnmHttpClient.createMockResponse(req, code = 200, body = """{"code":200}""")
                }
                url.contains("search.kuwo.cn") -> {
                    val searchJson = """
                        {
                            "content": [
                                {},
                                {
                                    "musicpage": {
                                        "abslist": [
                                            { "MUSICRID": "MUSIC_998877", "DURATION": "180" }
                                        ]
                                    }
                                }
                            ]
                        }
                    """.trimIndent()
                    UnmHttpClient.createMockResponse(req, code = 200, body = searchJson)
                }
                url.contains("/api/play/music/v2/audioUrl") -> {
                    val trackJson = """
                        {
                            "code": 200,
                            "data": {
                                "audioUrl": "http://bd-stream.kuwo.cn/audio/998877_320k.mp3",
                                "duration": 180
                            }
                        }
                    """.trimIndent()
                    UnmHttpClient.createMockResponse(req, code = 200, body = trackJson)
                }
                else -> UnmHttpClient.createMockResponse(req, code = 404, body = "Not Found")
            }
        }

        val song = SongInfo(id = 100L, name = "晴天", artist = "周杰伦", durationMs = 180_000L)
        val matched = BodianSource.match(song)

        assertNotNull("Bodian should match via offline mock", matched)
        assertEquals("http://bd-stream.kuwo.cn/audio/998877_320k.mp3", matched?.url)
        assertEquals(320000, matched?.br)
        assertEquals("bodian", matched?.source)

        // Verify that all HTTP calls went through the mock handler
        assertTrue("Must make at least search and track requests", requestUrls.size >= 2)
        assertTrue(requestUrls.any { it.contains("search.kuwo.cn") })
        assertTrue(requestUrls.any { it.contains("audioUrl") })
    }

    @Test
    fun challengeOfflineDeterminismKuwoSourceMockBypassCheck() {
        val requestUrls = mutableListOf<String>()

        UnmHttpClient.setMockHandler { req ->
            val url = req.url.toString()
            requestUrls.add(url)
            when {
                url.contains("search.kuwo.cn") -> {
                    val body = """
                        {
                            "content": [
                                {},
                                {
                                    "musicpage": {
                                        "abslist": [
                                            { "MUSICRID": "MUSIC_554433", "DURATION": "240" }
                                        ]
                                    }
                                }
                            ]
                        }
                    """.trimIndent()
                    UnmHttpClient.createMockResponse(req, code = 200, body = body)
                }
                url.contains("antiserver.kuwo.cn") -> {
                    UnmHttpClient.createMockResponse(req, code = 200, body = "http://sy.music.kuwo.cn/play/554433.mp3")
                }
                else -> UnmHttpClient.createMockResponse(req, code = 404, body = "")
            }
        }

        val song = SongInfo(id = 200L, name = "七里香", artist = "周杰伦", durationMs = 240_000L)
        val matched = KuwoSource.match(song)

        assertNotNull("Kuwo should match via offline mock", matched)
        assertEquals("http://sy.music.kuwo.cn/play/554433.mp3", matched?.url)
        assertEquals(320000, matched?.br)
        assertEquals("kuwo", matched?.source)
        assertEquals(2, requestUrls.size)
    }

    @Test
    fun challengeOfflineDeterminismMiguSourceMockBypassCheck() {
        UnmHttpClient.setMockHandler { req ->
            val url = req.url.toString()
            when {
                url.contains("scr_search_tag") -> {
                    val body = """
                        {
                            "musics": [
                                { "id": "migu_song_001", "songName": "稻香" }
                            ]
                        }
                    """.trimIndent()
                    UnmHttpClient.createMockResponse(req, code = 200, body = body)
                }
                url.contains("strategy/listen-url") -> {
                    val body = """
                        {
                            "code": "000000",
                            "data": {
                                "url": "https://freetyst.nf.migu.cn/audio/migu_song_001.mp3"
                            }
                        }
                    """.trimIndent()
                    UnmHttpClient.createMockResponse(req, code = 200, body = body)
                }
                else -> UnmHttpClient.createMockResponse(req, code = 404, body = "")
            }
        }

        val song = SongInfo(id = 300L, name = "稻香", artist = "周杰伦", durationMs = 210_000L)
        val matched = MiguSource.match(song)

        assertNotNull("Migu should match via offline mock", matched)
        assertEquals("https://freetyst.nf.migu.cn/audio/migu_song_001.mp3", matched?.url)
        assertEquals(320000, matched?.br)
        assertEquals("migu", matched?.source)
    }

    @Test
    fun challengeOfflineDeterminismKugouSourceMockBypassCheck() {
        UnmHttpClient.setMockHandler { req ->
            val url = req.url.toString()
            when {
                url.contains("mobilecdn.kugou.com") -> {
                    val body = """
                        {
                            "data": {
                                "info": [
                                    { "320hash": "AABBCCDDEEFF00112233445566778899", "album_id": "12345" }
                                ]
                            }
                        }
                    """.trimIndent()
                    UnmHttpClient.createMockResponse(req, code = 200, body = body)
                }
                url.contains("trackercdn.kugou.com") -> {
                    val body = """
                        {
                            "status": 1,
                            "url": [
                                "http://fs.open.kugou.com/audio/kugou_track.mp3"
                            ]
                        }
                    """.trimIndent()
                    UnmHttpClient.createMockResponse(req, code = 200, body = body)
                }
                else -> UnmHttpClient.createMockResponse(req, code = 404, body = "")
            }
        }

        val song = SongInfo(id = 400L, name = "夜曲", artist = "周杰伦", durationMs = 220_000L)
        val matched = KugouSource.match(song)

        assertNotNull("Kugou should match via offline mock", matched)
        assertEquals("http://fs.open.kugou.com/audio/kugou_track.mp3", matched?.url)
        assertEquals(320000, matched?.br)
        assertEquals("kugou", matched?.source)
    }

    @Test
    fun challengeSoundSourcesUnderHttpFailureCodes() {
        val errorCodes = listOf(400, 401, 403, 404, 500, 502, 503, 504)
        val song = SongInfo(id = 500L, name = "枫", artist = "周杰伦", durationMs = 180_000L)

        for (code in errorCodes) {
            UnmHttpClient.setMockHandler { req ->
                UnmHttpClient.createMockResponse(req, code = code, body = "Error $code response payload")
            }

            assertNull("Bodian must return null on HTTP $code", BodianSource.match(song))
            assertNull("Kuwo must return null on HTTP $code", KuwoSource.match(song))
            assertNull("Migu must return null on HTTP $code", MiguSource.match(song))
            assertNull("Kugou must return null on HTTP $code", KugouSource.match(song))
        }
    }

    @Test
    fun challengeSoundSourcesUnderNetworkExceptions() {
        val exceptions = listOf(
            SocketTimeoutException("Read timed out"),
            ConnectException("Connection refused"),
            UnknownHostException("search.kuwo.cn: Name or service not known"),
            SSLHandshakeException("Certificate validation failed"),
            IOException("Broken pipe")
        )
        val song = SongInfo(id = 600L, name = "借口", artist = "周杰伦", durationMs = 180_000L)

        for (ex in exceptions) {
            UnmHttpClient.setMockHandler { _ -> throw ex }

            assertNull("Bodian must safely isolate ${ex.javaClass.simpleName}", BodianSource.match(song))
            assertNull("Kuwo must safely isolate ${ex.javaClass.simpleName}", KuwoSource.match(song))
            assertNull("Migu must safely isolate ${ex.javaClass.simpleName}", MiguSource.match(song))
            assertNull("Kugou must safely isolate ${ex.javaClass.simpleName}", KugouSource.match(song))
        }
    }

    @Test
    fun challengeSoundSourcesUnderMalformedPayloads() {
        val malformedBodies = listOf(
            "",
            "   ",
            "<!DOCTYPE html><html><body>502 Bad Gateway</body></html>",
            "{",
            "{ \"code\": 200, \"data\": ",
            "{\"content\": \"not_an_array\"}",
            "{\"musics\": \"not_an_array\"}",
            "{\"data\": {\"info\": \"not_an_array\"}}"
        )
        val song = SongInfo(id = 700L, name = "退后", artist = "周杰伦", durationMs = 180_000L)

        for (body in malformedBodies) {
            UnmHttpClient.setMockHandler { req ->
                UnmHttpClient.createMockResponse(req, code = 200, body = body)
            }

            assertNull("Bodian must handle malformed response without crashing", BodianSource.match(song))
            assertNull("Kuwo must handle malformed response without crashing", KuwoSource.match(song))
            assertNull("Migu must handle malformed response without crashing", MiguSource.match(song))
            assertNull("Kugou must handle malformed response without crashing", KugouSource.match(song))
        }
    }

    @Test
    fun challengeSoundSourcesTeaserIdFilteringAtSourceLevel() {
        // Kuwo Antiserver returns blacklisted teaser audio ID 563182854
        UnmHttpClient.setMockHandler { req ->
            val url = req.url.toString()
            when {
                url.contains("search.kuwo.cn") -> {
                    val body = """
                        {
                            "content": [
                                {},
                                {
                                    "musicpage": {
                                        "abslist": [
                                            { "MUSICRID": "MUSIC_563182854", "DURATION": "180" }
                                        ]
                                    }
                                }
                            ]
                        }
                    """.trimIndent()
                    UnmHttpClient.createMockResponse(req, code = 200, body = body)
                }
                url.contains("antiserver.kuwo.cn") -> {
                    UnmHttpClient.createMockResponse(req, code = 200, body = "http://antiserver.kuwo.cn/anti.s?rid=MUSIC_563182854&format=mp3")
                }
                else -> UnmHttpClient.createMockResponse(req, code = 404, body = "")
            }
        }

        val song = SongInfo(id = 800L, name = "下架提示曲", artist = "未知", durationMs = 180_000L)
        val matchedKuwo = KuwoSource.match(song)
        assertNull("KuwoSource must filter out blacklisted teaser ID 563182854", matchedKuwo)

        // Bodian returns blacklisted teaser ID 588957081
        UnmHttpClient.setMockHandler { req ->
            val url = req.url.toString()
            when {
                url.contains("/advert/watch") -> UnmHttpClient.createMockResponse(req, code = 200, body = "{}")
                url.contains("search.kuwo.cn") -> {
                    val body = """
                        {
                            "content": [
                                {},
                                {
                                    "musicpage": {
                                        "abslist": [
                                            { "MUSICRID": "MUSIC_588957081", "DURATION": "180" }
                                        ]
                                    }
                                }
                            ]
                        }
                    """.trimIndent()
                    UnmHttpClient.createMockResponse(req, code = 200, body = body)
                }
                url.contains("audioUrl") -> {
                    val body = """
                        {
                            "code": 200,
                            "data": {
                                "audioUrl": "http://bd-api.kuwo.cn/play/588957081.mp3",
                                "duration": 180
                            }
                        }
                    """.trimIndent()
                    UnmHttpClient.createMockResponse(req, code = 200, body = body)
                }
                else -> UnmHttpClient.createMockResponse(req, code = 404, body = "")
            }
        }

        val matchedBodian = BodianSource.match(song)
        assertNull("BodianSource must filter out blacklisted teaser ID 588957081", matchedBodian)
    }

    @Test
    fun challengeSoundSourcesTeaserDurationFiltering() {
        // Bodian returns an audio track with duration = 20 seconds for an expected 240s song
        UnmHttpClient.setMockHandler { req ->
            val url = req.url.toString()
            when {
                url.contains("/advert/watch") -> UnmHttpClient.createMockResponse(req, code = 200, body = "{}")
                url.contains("search.kuwo.cn") -> {
                    val body = """
                        {
                            "content": [
                                {},
                                {
                                    "musicpage": {
                                        "abslist": [
                                            { "MUSICRID": "MUSIC_112233", "DURATION": "240" }
                                        ]
                                    }
                                }
                            ]
                        }
                    """.trimIndent()
                    UnmHttpClient.createMockResponse(req, code = 200, body = body)
                }
                url.contains("audioUrl") -> {
                    val body = """
                        {
                            "code": 200,
                            "data": {
                                "audioUrl": "http://bd-stream.kuwo.cn/play/valid_track.mp3",
                                "duration": 20
                            }
                        }
                    """.trimIndent()
                    UnmHttpClient.createMockResponse(req, code = 200, body = body)
                }
                else -> UnmHttpClient.createMockResponse(req, code = 404, body = "")
            }
        }

        val song = SongInfo(id = 850L, name = "搁浅", artist = "周杰伦", durationMs = 240_000L)
        val matched = BodianSource.match(song)
        assertNull("BodianSource must reject 20s teaser clip for 240s song", matched)
    }

    @Test
    fun challengeBodianAdUnlockRetryFailure() {
        // Bodian returns error 20018 (ad required) on initial AND retry requests
        var callCount = 0
        UnmHttpClient.setMockHandler { req ->
            val url = req.url.toString()
            when {
                url.contains("/advert/watch") -> UnmHttpClient.createMockResponse(req, code = 200, body = "{}")
                url.contains("search.kuwo.cn") -> {
                    val body = """
                        {
                            "content": [
                                {},
                                {
                                    "musicpage": {
                                        "abslist": [
                                            { "MUSICRID": "MUSIC_123", "DURATION": "180" }
                                        ]
                                    }
                                }
                            ]
                        }
                    """.trimIndent()
                    UnmHttpClient.createMockResponse(req, code = 200, body = body)
                }
                url.contains("audioUrl") -> {
                    callCount++
                    // Returns 20018 persistently
                    val body = """{"code": 20018, "message": "Ad watch required"}"""
                    UnmHttpClient.createMockResponse(req, code = 200, body = body)
                }
                else -> UnmHttpClient.createMockResponse(req, code = 404, body = "")
            }
        }

        val song = SongInfo(id = 860L, name = "开不了口", artist = "周杰伦", durationMs = 180_000L)
        val matched = BodianSource.match(song)
        assertNull("BodianSource must return null when ad unlock retry also fails", matched)
        // Must retry at most once (total 2 audioUrl calls), avoiding infinite recursion/loop
        assertEquals(2, callCount)
    }

    // =========================================================================
    // PART 3: QuickJsEngine Host Protection, Bridge Safety & Fallback Stress
    // =========================================================================

    @Test
    fun challengeQuickJsEngineBridgeHttpGetPathologicalInputs() {
        // 1. Empty args
        assertEquals("", QuickJsEngine.bridgeHttpGet(emptyArray()))
        assertEquals("", QuickJsEngine.bridgeHttpGet(arrayOf(null)))
        assertEquals("", QuickJsEngine.bridgeHttpGet(arrayOf("")))
        assertEquals("", QuickJsEngine.bridgeHttpGet(arrayOf("   ")))

        // 2. Malformed headers JSON
        UnmHttpClient.setMockHandler { req ->
            UnmHttpClient.createMockResponse(req, code = 200, body = "ok")
        }
        val resultMalformedHeaders = QuickJsEngine.bridgeHttpGet(
            arrayOf<Any?>("http://api.com/test", "MyUA", "{:::not_valid_json}")
        )
        assertEquals("ok", resultMalformedHeaders)

        // 3. Network error returns empty string without throwing
        UnmHttpClient.setMockHandler { _ -> throw IOException("Network unreachable") }
        val resultError = QuickJsEngine.bridgeHttpGet(arrayOf<Any?>("http://api.com/error", null, null))
        assertEquals("", resultError)
    }

    @Test
    fun challengeQuickJsEngineBridgeHttpPostPathologicalInputs() {
        // 1. Empty args
        assertEquals("", QuickJsEngine.bridgeHttpPost(emptyArray()))
        assertEquals("", QuickJsEngine.bridgeHttpPost(arrayOf(null)))
        assertEquals("", QuickJsEngine.bridgeHttpPost(arrayOf("")))

        // 2. Large body payload
        val largeBody = "x".repeat(50_000)
        UnmHttpClient.setMockHandler { req ->
            val receivedBody = req.body?.let {
                val buffer = okio.Buffer()
                it.writeTo(buffer)
                buffer.readUtf8()
            } ?: ""
            assertEquals(50_000, receivedBody.length)
            UnmHttpClient.createMockResponse(req, code = 200, body = "accepted")
        }

        val result = QuickJsEngine.bridgeHttpPost(
            arrayOf<Any?>("http://api.com/upload", largeBody, """{"Content-Type":"text/plain"}""", "CustomUA")
        )
        assertEquals("accepted", result)

        // 3. Network failure returns empty string
        UnmHttpClient.setMockHandler { _ -> throw SocketTimeoutException("Post timed out") }
        val failResult = QuickJsEngine.bridgeHttpPost(arrayOf<Any?>("http://api.com/post", "data", null, null))
        assertEquals("", failResult)
    }

    @Test
    fun challengeQuickJsEngineBridgeMd5PathologicalInputs() {
        // Empty string
        assertEquals("d41d8cd98f00b204e9800998ecf8427e", QuickJsEngine.bridgeMd5(arrayOf<Any?>("")))

        // Null / empty array
        assertEquals("", QuickJsEngine.bridgeMd5(emptyArray()))
        assertEquals("", QuickJsEngine.bridgeMd5(arrayOf(null)))

        // Non-string argument
        assertEquals("", QuickJsEngine.bridgeMd5(arrayOf(12345)))

        // Unicode / CJK strings and emojis
        val unicodeHash = QuickJsEngine.bridgeMd5(arrayOf<Any?>("周杰伦 - 晴天 🎵🎶"))
        assertTrue("MD5 must produce 32 hex characters", unicodeHash.matches(Regex("^[a-f0-9]{32}$")))

        // 100KB string
        val largeStr = "a".repeat(100_000)
        val largeHash = QuickJsEngine.bridgeMd5(arrayOf<Any?>(largeStr))
        assertTrue("Large string MD5 must succeed", largeHash.matches(Regex("^[a-f0-9]{32}$")))
    }

    @Test
    fun challengeQuickJsEngineConcurrentBridgeInvocations() {
        val threadCount = 12
        val itersPerThread = 50
        val executor = Executors.newFixedThreadPool(threadCount)
        val latch = CountDownLatch(threadCount)
        val successCount = AtomicInteger(0)

        UnmHttpClient.setMockHandler { req ->
            UnmHttpClient.createMockResponse(req, code = 200, body = """{"code":200,"echo":"${req.url}"}""")
        }

        for (t in 0 until threadCount) {
            executor.submit {
                try {
                    for (i in 0 until itersPerThread) {
                        val getRes = QuickJsEngine.bridgeHttpGet(arrayOf<Any?>("http://api.com/get?t=$t&i=$i", null, null))
                        val postRes = QuickJsEngine.bridgeHttpPost(arrayOf<Any?>("http://api.com/post?t=$t&i=$i", "body", null, null))
                        val md5Res = QuickJsEngine.bridgeMd5(arrayOf<Any?>("hash_$t-$i"))

                        if (getRes.contains("echo") && postRes.contains("echo") && md5Res.length == 32) {
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
        assertEquals(threadCount * itersPerThread, successCount.get())
    }

    @Test
    fun challengeQuickJsEngineFallbackRobustness() {
        // When native library is unavailable or disabled, engine must never throw LinkageError
        QuickJsEngine.setNativeAvailableForTesting(false)
        assertTrue(QuickJsEngine.isNativeUnavailable())
        assertFalse(QuickJsEngine.isReady())

        // loadScript returns false gracefully
        val loaded = QuickJsEngine.loadScript("function unmMatch() { return { url: 'http://foo.com/1.mp3' }; }")
        assertFalse(loaded)
        assertTrue(QuickJsEngine.hasScript())

        // match returns null gracefully
        val song = SongInfo(id = 999L, name = "测试歌曲", artist = "测试歌手", durationMs = 180_000L)
        val result = QuickJsEngine.match(song)
        assertNull("Fallback match must return null safely", result)

        // validateScript performs structural check without throwing
        assertTrue(QuickJsEngine.validateScript("function unmMatch() { return null; }"))
        assertFalse(QuickJsEngine.validateScript("function brokenWithoutMatch() {}"))

        // Reset restores default probe state
        QuickJsEngine.resetForTesting()
    }

    @Test
    fun challengeQuickJsEngineCorruptSongInfoParameters() {
        // Pathological song info containing quotes, backslashes, line breaks, and unicode
        val pathologicalSong = SongInfo(
            id = -999999L,
            name = "Evil \" Song ' With \\ Escapes \n \r \t \b and Unicode: \u0000 \uFEFF 🎵",
            artist = "Artist <script>alert(1)</script>",
            durationMs = -1000L
        )

        // Calling match on pathological metadata must never throw unhandled JSON or JS syntax exceptions
        val result = QuickJsEngine.match(pathologicalSong)
        assertNull(result)
    }

    // =========================================================================
    // PART 4: RuleManager Multi-Tier Dry Run & Rollback Stress
    // =========================================================================

    @Test
    fun challengeRuleManagerDryRunRejectsHtmlPayloads() {
        val htmlResponses = listOf(
            "<!DOCTYPE html><html><head><title>404 Not Found</title></head><body><h1>404 Not Found</h1></body></html>",
            "<html><body>function unmMatch() {}</body></html>",
            "<script>function unmMatch() {}</script>",
            "<HTML><BODY>GitHub Error</BODY></HTML>"
        )

        for (html in htmlResponses) {
            val res = RuleManager.validateRuleScript(html)
            assertFalse("RuleManager dry-run must reject HTML payload", res.isSuccess)
        }
    }

    @Test
    fun challengeRuleManagerDryRunRejectsTruncatedAndUnbalancedScripts() {
        val invalidScripts = listOf(
            "", // empty
            "   ", // blank
            "function unmMatch() {", // unclosed brace
            "function unmMatch() { var s = \"unclosed string; }", // unclosed quote
            "function unmMatch() { var a = [1, 2, 3; }", // bracket mismatch
            "function wrongName() { return null; }", // missing unmMatch
            "a".repeat(30), // too short (< 50 chars)
            "function unmMatch() { var x = (1 + 2; }" // parenthesis mismatch
        )

        for (script in invalidScripts) {
            val res = RuleManager.validateRuleScript(script)
            assertFalse("RuleManager dry-run must reject invalid script: '$script'", res.isSuccess)
        }

        // Valid script must pass
        val validScript = """
            function unmMatch(id, name, artist, duration) {
                return null;
            }
        """.trimIndent()
        assertTrue(RuleManager.validateRuleScript(validScript).isSuccess)
    }

    @Test
    fun challengeRuleManagerConsecutiveErrorRollback() {
        RuleManager.resetForTest()
        assertEquals(0, RuleManager.getConsecutiveErrorCount())

        val tempDir = java.io.File(System.getProperty("java.io.tmpdir"), "unm_challenge_${System.nanoTime()}")
        tempDir.mkdirs()
        try {
            val fakeContext = ChallengeFakeContext(tempDir)
            val customScript = """
                function unmMatch(id, name, artist, duration) {
                    return { url: "http://example.com/custom.mp3" };
                }
            """.trimIndent()

            RuleManager.init(fakeContext, customScript = customScript)
            assertEquals(customScript, RuleManager.getActiveScript())
            assertFalse(RuleManager.isUsingDefault())

            // Simulate reaching 3 consecutive errors and trigger rollback
            RuleManager.setConsecutiveErrorCountForTest(2)
            assertEquals(2, RuleManager.getConsecutiveErrorCount())

            val rolledBack = RuleManager.rollbackToDefault(fakeContext)
            assertTrue(rolledBack)
            assertEquals(0, RuleManager.getConsecutiveErrorCount())
            assertTrue(RuleManager.isUsingDefault())
            assertEquals(RuleManager.DEFAULT_EMBEDDED_RULE, RuleManager.getActiveScript())
        } finally {
            tempDir.deleteRecursively()
        }
    }
}

private class ChallengeFakeContext(
    private val filesDirFile: java.io.File
) : android.content.ContextWrapper(null) {
    private val memoryPrefs = ChallengeFakePrefs()
    override fun getFilesDir(): java.io.File = filesDirFile
    override fun getApplicationContext(): android.content.Context = this
    override fun getSharedPreferences(name: String, mode: Int): android.content.SharedPreferences = memoryPrefs
}

private class ChallengeFakePrefs : android.content.SharedPreferences {
    private val map = mutableMapOf<String, Any?>()
    override fun getAll(): MutableMap<String, *> = HashMap(map)
    override fun getString(key: String?, defValue: String?): String? = map[key] as? String ?: defValue
    @Suppress("UNCHECKED_CAST")
    override fun getStringSet(key: String?, defValues: MutableSet<String>?): MutableSet<String>? = map[key] as? MutableSet<String> ?: defValues
    override fun getInt(key: String?, defValue: Int): Int = map[key] as? Int ?: defValue
    override fun getLong(key: String?, defValue: Long): Long = map[key] as? Long ?: defValue
    override fun getFloat(key: String?, defValue: Float): Float = map[key] as? Float ?: defValue
    override fun getBoolean(key: String?, defValue: Boolean): Boolean = map[key] as? Boolean ?: defValue
    override fun contains(key: String?): Boolean = map.containsKey(key)
    override fun edit(): android.content.SharedPreferences.Editor = Editor()
    override fun registerOnSharedPreferenceChangeListener(l: android.content.SharedPreferences.OnSharedPreferenceChangeListener?) {}
    override fun unregisterOnSharedPreferenceChangeListener(l: android.content.SharedPreferences.OnSharedPreferenceChangeListener?) {}

    inner class Editor : android.content.SharedPreferences.Editor {
        private val temp = mutableMapOf<String, Any?>()
        private val removed = mutableSetOf<String>()
        override fun putString(k: String?, v: String?) = apply { k?.let { temp[it] = v } }
        override fun putStringSet(k: String?, v: MutableSet<String>?) = apply { k?.let { temp[it] = v } }
        override fun putInt(k: String?, v: Int) = apply { k?.let { temp[it] = v } }
        override fun putLong(k: String?, v: Long) = apply { k?.let { temp[it] = v } }
        override fun putFloat(k: String?, v: Float) = apply { k?.let { temp[it] = v } }
        override fun putBoolean(k: String?, v: Boolean) = apply { k?.let { temp[it] = v } }
        override fun remove(k: String?) = apply { k?.let { removed.add(it) } }
        override fun clear() = apply { map.clear() }
        override fun commit(): Boolean { apply(); return true }
        override fun apply() {
            removed.forEach { map.remove(it) }
            map.putAll(temp)
        }
    }
}

