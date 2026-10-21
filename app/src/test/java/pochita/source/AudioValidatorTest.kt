package pochita.source

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class AudioValidatorTest {

    @Before
    fun setUp() {
        AudioValidator.reset()
    }

    @After
    fun tearDown() {
        AudioValidator.reset()
    }

    // =========================================================================
    // Tier 1: Happy-Path Isolated Unit Tests (>= 5 cases)
    // =========================================================================

    @Test
    fun testDefaultBlacklistedIdsContainsKnownPromptIds() {
        // 588957081 and 563182854 are known Kuwo offline/teaser prompt audio IDs
        assertTrue(AudioValidator.isBlacklistedAudioId(588957081L))
        assertTrue(AudioValidator.isBlacklistedAudioId(563182854L))
        assertTrue(AudioValidator.isBlacklistedAudioId("588957081"))
        assertTrue(AudioValidator.isBlacklistedAudioId("563182854"))
    }

    @Test
    fun testLegitimateSongIdNotBlacklisted() {
        assertFalse(AudioValidator.isBlacklistedAudioId(1851652156L))
        assertFalse(AudioValidator.isBlacklistedAudioId(123456L))
        assertFalse(AudioValidator.isBlacklistedAudioId("1851652156"))
    }

    @Test
    fun testValidHttpAudioUrlPasses() {
        val validHttp = "http://bd-stream.kuwo.cn/audio/valid_track_320k.mp3"
        assertTrue(AudioValidator.isValidAudioUrl(validHttp))
    }

    @Test
    fun testValidHttpsAudioUrlPasses() {
        val validHttps = "https://freetyst.nf.migu.cn/group1/M00/valid_track.mp3?token=xyz"
        assertTrue(AudioValidator.isValidAudioUrl(validHttps))
    }

    @Test
    fun testBannedKeywordsIdentified() {
        assertTrue(AudioValidator.isBannedKeyword("周杰伦 - 晴天 (试听版)"))
        assertTrue(AudioValidator.isBannedKeyword("新歌预告片段"))
        assertTrue(AudioValidator.isBannedKeyword("该音频已下架"))
        assertTrue(AudioValidator.isBannedKeyword("Cool Song (Teaser Trailer)"))
        assertTrue(AudioValidator.isBannedKeyword("Preview Demo Ringtone"))
    }

    @Test
    fun testCleanSongTitleNotBanned() {
        assertFalse(AudioValidator.isBannedKeyword("晴天"))
        assertFalse(AudioValidator.isBannedKeyword("忘不掉的你 - h3R3"))
        assertFalse(AudioValidator.isBannedKeyword("Shape of You - Ed Sheeran"))
        assertFalse(AudioValidator.isBannedKeyword("海阔天空 - Beyond"))
    }

    // =========================================================================
    // Tier 2: Boundary, Negative & Malformed Cases (>= 5 cases)
    // =========================================================================

    @Test
    fun testNullEmptyOrBlankAudioUrlRejected() {
        assertFalse(AudioValidator.isValidAudioUrl(null))
        assertFalse(AudioValidator.isValidAudioUrl(""))
        assertFalse(AudioValidator.isValidAudioUrl("   "))
        assertFalse(AudioValidator.isValidAudioUrl("\t\n"))
    }

    @Test
    fun testUrlContainingBlacklistedIdRejected() {
        val kuwoTeaser1 = "http://antiserver.kuwo.cn/anti.s?format=mp3&rid=588957081"
        val kuwoTeaser2 = "https://bd-api.kuwo.cn/play/563182854.mp3"
        assertFalse(AudioValidator.isValidAudioUrl(kuwoTeaser1))
        assertFalse(AudioValidator.isValidAudioUrl(kuwoTeaser2))
    }

    @Test
    fun testInvalidSchemeAndProtocolRejected() {
        assertFalse(AudioValidator.isValidAudioUrl("ftp://example.com/audio.mp3"))
        assertFalse(AudioValidator.isValidAudioUrl("file:///sdcard/audio.mp3"))
        assertFalse(AudioValidator.isValidAudioUrl("javascript:void(0)"))
        assertFalse(AudioValidator.isValidAudioUrl("data:audio/mp3;base64,AAAA"))
        assertFalse(AudioValidator.isValidAudioUrl("/local/relative/path.mp3"))
    }

    @Test
    fun testMalformedOrCorruptUrlStringRejected() {
        assertFalse(AudioValidator.isValidAudioUrl("null"))
        assertFalse(AudioValidator.isValidAudioUrl("undefined"))
        assertFalse(AudioValidator.isValidAudioUrl("error"))
        assertFalse(AudioValidator.isValidAudioUrl("http://"))
        assertFalse(AudioValidator.isValidAudioUrl("https://"))
        assertFalse(AudioValidator.isValidAudioUrl("http:///invalid-domain"))
    }

    @Test
    fun testTeaserDurationDetection() {
        // Track expected 3 mins (180s), returned clip is 20s -> TEASER!
        assertTrue(AudioValidator.isTeaserDuration(expectedDurationMs = 180_000L, actualDurationSec = 20))
        assertTrue(AudioValidator.isTeaserDuration(expectedDurationMs = 120_000L, actualDurationSec = 30))
        assertTrue(AudioValidator.isTeaserDuration(expectedDurationMs = 70_000L, actualDurationSec = 1))

        // Full duration track -> NOT teaser
        assertFalse(AudioValidator.isTeaserDuration(expectedDurationMs = 180_000L, actualDurationSec = 179))
        assertFalse(AudioValidator.isTeaserDuration(expectedDurationMs = 180_000L, actualDurationSec = 35))

        // Short sound effect or jingle (duration <= 60s) -> NOT teaser
        assertFalse(AudioValidator.isTeaserDuration(expectedDurationMs = 25_000L, actualDurationSec = 25))
        assertFalse(AudioValidator.isTeaserDuration(expectedDurationMs = 0L, actualDurationSec = 15))
    }

    @Test
    fun testDurationToleranceMatching() {
        // Target 180s (180000ms), Candidate 183s (183000ms) -> diff 3000ms <= 6000ms -> ACCEPTABLE
        assertTrue(AudioValidator.isDurationAcceptable(180_000L, 183_000L, 6000L))
        // Candidate 190s -> diff 10000ms > 6000ms -> REJECTED
        assertFalse(AudioValidator.isDurationAcceptable(180_000L, 190_000L, 6000L))
        // Missing duration (0) -> pass through
        assertTrue(AudioValidator.isDurationAcceptable(0L, 180_000L))
        assertTrue(AudioValidator.isDurationAcceptable(180_000L, 0L))
    }

    // =========================================================================
    // Tier 3: Dynamic Registration & Test Isolation
    // =========================================================================

    @Test
    fun testDynamicAddAndRemoveBlacklistId() {
        val customBadId = 999888777L
        assertFalse(AudioValidator.isBlacklistedAudioId(customBadId))

        AudioValidator.addBlacklistedAudioId(customBadId)
        assertTrue(AudioValidator.isBlacklistedAudioId(customBadId))
        assertFalse(AudioValidator.isValidAudioUrl("http://cdn.example.com/play/$customBadId.mp3"))

        AudioValidator.removeBlacklistedAudioId(customBadId)
        assertFalse(AudioValidator.isBlacklistedAudioId(customBadId))
        assertTrue(AudioValidator.isValidAudioUrl("http://cdn.example.com/play/$customBadId.mp3"))
    }

    @Test
    fun testDynamicAddAndRemoveBannedKeyword() {
        val customBanned = "内测限定"
        assertFalse(AudioValidator.isBannedKeyword("某歌曲 (内测限定)"))

        AudioValidator.addBannedKeyword(customBanned)
        assertTrue(AudioValidator.isBannedKeyword("某歌曲 (内测限定)"))

        AudioValidator.removeBannedKeyword(customBanned)
        assertFalse(AudioValidator.isBannedKeyword("某歌曲 (内测限定)"))
    }

    @Test
    fun testResetRestoresDefaults() {
        AudioValidator.addBlacklistedAudioId(111222333L)
        AudioValidator.addBannedKeyword("测试禁用词")
        assertTrue(AudioValidator.isBlacklistedAudioId(111222333L))
        assertTrue(AudioValidator.isBannedKeyword("测试禁用词"))

        AudioValidator.reset()
        assertFalse(AudioValidator.isBlacklistedAudioId(111222333L))
        assertFalse(AudioValidator.isBannedKeyword("测试禁用词"))
        // Defaults still intact
        assertTrue(AudioValidator.isBlacklistedAudioId(588957081L))
        assertTrue(AudioValidator.isBlacklistedAudioId(563182854L))
        assertTrue(AudioValidator.isBannedKeyword("试听"))
    }

    // =========================================================================
    // Tier 4: Real-World UNM Application Scenarios
    // =========================================================================

    @Test
    fun testKuwoAntiserverTeaserUrlFilteringScenario() {
        val rawResponseUrl = "http://antiserver.kuwo.cn/anti.s?type=convert_url&format=mp3&response=url&rid=MUSIC_563182854"
        assertFalse(AudioValidator.isValidAudioUrl(rawResponseUrl))
    }

    @Test
    fun testBodianApiUnavailableClipFilteringScenario() {
        val bodianUnavailableUrl = "http://bd-api.kuwo.cn/api/play/music/v2/audioUrl?musicId=588957081&br=320kmp3"
        assertFalse(AudioValidator.isValidAudioUrl(bodianUnavailableUrl))
    }
}
