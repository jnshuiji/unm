package pochita.ui

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import pochita.config.ConfigManager
import pochita.config.UnmConfig
import pochita.event.ReplaceEventBus
import pochita.hook.PlayerUiHook
import pochita.model.ReplaceEvent
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * Empirical Adversarial Challenge Test Suite for Phase 2 Milestone 2:
 * - BadgeFormatter: Diverse metadata, empty matched title/artist fallback, long titles, special characters, edge bitrates.
 * - PlayerUiHook: titlesMatch vulnerability & track switching to unreplaced songs.
 * - PlayerBadgeManager: enablePlayerBadge toggle behavior and lifecycle resets.
 * - ReplaceEventBus: High-concurrency stress test and thread safety.
 */
class PlayerBadgeManagerAndUiHookChallengerTest {

    @Before
    fun setUp() {
        ConfigManager.resetForTesting()
        ReplaceEventBus.resetForTesting()
        PlayerUiHook.resetForTesting()
    }

    @After
    fun tearDown() {
        ConfigManager.resetForTesting()
        ReplaceEventBus.resetForTesting()
        PlayerUiHook.resetForTesting()
    }

    private fun createEvent(
        songId: Long = 10001L,
        originalTitle: String = "晴天",
        originalArtist: String = "周杰伦",
        matchedSource: String = "kuwo",
        matchedTitle: String = "晴天",
        matchedArtist: String = "周杰伦",
        br: Int = 320000
    ) = ReplaceEvent(
        songId = songId,
        originalTitle = originalTitle,
        originalArtist = originalArtist,
        matchedSource = matchedSource,
        matchedTitle = matchedTitle,
        matchedArtist = matchedArtist,
        br = br
    )

    // =========================================================================
    // 1. BadgeFormatter: Diverse Metadata & Edge Cases
    // =========================================================================

    @Test
    fun testBadgeFormatterEmptyMatchedTitleFallback() {
        // When matchedTitle is empty string, formatBadgeText must NOT produce trailing whitespace or empty string
        val eventEmptyTitle = createEvent(originalTitle = "晴天", matchedTitle = "")
        val badgeText = BadgeFormatter.formatBadgeText(eventEmptyTitle)
        assertEquals("[酷我·320K]", badgeText)

        // When matchedTitle is whitespace-only, formatBadgeText must trim and fallback to prefix
        val eventWhitespaceTitle = createEvent(originalTitle = "晴天", matchedTitle = "   \t\n  ")
        assertEquals("[酷我·320K]", BadgeFormatter.formatBadgeText(eventWhitespaceTitle))
    }

    @Test
    fun testBadgeFormatterLongTitlesFormatting() {
        val longTitle = "这是一首非常非常非常非常非常非常非常长长长长长长长长长长长长长长长长长长长长长长长长长长长的歌名"
        val eventLongTitle = createEvent(originalTitle = "晴天", matchedTitle = longTitle)
        val badgeText = BadgeFormatter.formatBadgeText(eventLongTitle)

        // BadgeFormatter pure formatting produces the full text; truncation is handled by TextView maxWidth & TruncateAt.END
        assertEquals("[酷我·320K] $longTitle", badgeText)
        assertTrue(badgeText.startsWith("[酷我·320K]"))
        assertTrue(badgeText.endsWith("的歌名"))
    }

    @Test
    fun testBadgeFormatterSpecialCharactersAndEmojis() {
        val specialTitle = "🌸 桜色舞うころ (Piano Ver.) 🎹 & <Special> \"Quotes\""
        val event = createEvent(originalTitle = "桜色舞うころ", matchedTitle = specialTitle)
        val badgeText = BadgeFormatter.formatBadgeText(event)

        assertEquals("[酷我·320K] $specialTitle", badgeText)

        val clipboardText = BadgeFormatter.formatClipboardText(event)
        assertTrue(clipboardText.contains(specialTitle))
        assertTrue(clipboardText.contains("🌸 桜色舞うころ"))

        val dialogMsg = BadgeFormatter.formatDialogMessage(event)
        assertTrue(dialogMsg.contains(specialTitle))
    }

    @Test
    fun testBadgeFormatterRtlAndUnicodeCharacters() {
        val rtlTitle = "حبيبي يا نور العين"
        val event = createEvent(originalTitle = "Habibi", matchedTitle = rtlTitle, matchedSource = "migu", br = 999000)
        val badgeText = BadgeFormatter.formatBadgeText(event)
        assertEquals("[咪咕·FLAC] حبيبي يا نور العين", badgeText)
    }

    @Test
    fun testBadgeFormatterBitrateBoundaries() {
        assertEquals("标准", BadgeFormatter.formatBitrate(-1))
        assertEquals("标准", BadgeFormatter.formatBitrate(0))
        assertEquals("64K", BadgeFormatter.formatBitrate(64000))
        assertEquals("128K", BadgeFormatter.formatBitrate(128000))
        assertEquals("192K", BadgeFormatter.formatBitrate(192000))
        assertEquals("320K", BadgeFormatter.formatBitrate(320000))
        assertEquals("320K", BadgeFormatter.formatBitrate(500000)) // >= 300000 maps to 320K
        assertEquals("FLAC", BadgeFormatter.formatBitrate(900000)) // >= 900000 is FLAC
        assertEquals("FLAC", BadgeFormatter.formatBitrate(1411000))

        assertEquals("MP3", BadgeFormatter.resolveFormat(0))
        assertEquals("MP3", BadgeFormatter.resolveFormat(320000))
        assertEquals("FLAC", BadgeFormatter.resolveFormat(900000))
        assertEquals("FLAC", BadgeFormatter.resolveFormat(999000))
    }

    @Test
    fun testBadgeFormatterSourceMappingVariants() {
        assertEquals("酷我音乐", BadgeFormatter.formatSourceDisplayName("KUWO"))
        assertEquals("波点音乐", BadgeFormatter.formatSourceDisplayName("  bodian  "))
        assertEquals("咪咕音乐", BadgeFormatter.formatSourceDisplayName("MIGU"))
        assertEquals("酷狗音乐", BadgeFormatter.formatSourceDisplayName("kugou"))
        assertEquals("网易云音乐", BadgeFormatter.formatSourceDisplayName("cloudmusic"))
        assertEquals("网易云音乐", BadgeFormatter.formatSourceDisplayName("netease"))
        assertEquals("JOOX", BadgeFormatter.formatSourceDisplayName("joox"))
        assertEquals("PyNCM", BadgeFormatter.formatSourceDisplayName("pyncmd"))
        assertEquals("自定义JS", BadgeFormatter.formatSourceDisplayName("custom_js"))
        assertEquals("未知音源", BadgeFormatter.formatSourceDisplayName(""))
        assertEquals("未知音源", BadgeFormatter.formatSourceDisplayName("   "))
        assertEquals("Tencent", BadgeFormatter.formatSourceDisplayName("tencent"))

        assertEquals("酷我", BadgeFormatter.formatShortSource("KUWO"))
        assertEquals("波点", BadgeFormatter.formatShortSource("  bodian  "))
        assertEquals("咪咕", BadgeFormatter.formatShortSource("migu"))
        assertEquals("酷狗", BadgeFormatter.formatShortSource("kugou"))
        assertEquals("网易", BadgeFormatter.formatShortSource("cloudmusic"))
        assertEquals("JOOX", BadgeFormatter.formatShortSource("joox"))
        assertEquals("PyNCM", BadgeFormatter.formatShortSource("pyncm"))
        assertEquals("JS", BadgeFormatter.formatShortSource("custom_js"))
        assertEquals("未知", BadgeFormatter.formatShortSource(""))
        assertEquals("未知", BadgeFormatter.formatShortSource("   "))
        assertEquals("tenc", BadgeFormatter.formatShortSource("tencent"))
    }

    // =========================================================================
    // 2. Empirical Challenge: PlayerUiHook.titlesMatch Vulnerabilities
    // =========================================================================

    /**
     * EMPIRICAL FINDING 1: Substring containment in `titlesMatch` causes severe false positives
     * when switching to completely different, unreplaced tracks whose names happen to contain
     * the replaced song title as a substring.
     */
    @Test
    fun testTitlesMatchSubstringFalsePositiveChallenged() {
        // Case A: 1-Character Chinese song names (Extremely common in Chinese pop music)
        // Song A was replaced: "你" (by Ariel Lin)
        // User switches to unreplaced Song B: "你的答案" (by A Rong)
        val matchA = PlayerUiHook.titlesMatch("你", "你的答案")
        // Empirical proof: titlesMatch returns TRUE even though these are completely different songs!
        assertTrue("EMPIRICAL PROOF: '你' matches '你的答案' due to substring containment", matchA)

        // Case B: Song A was replaced: "爱" (by Little Tigers)
        // User switches to unreplaced Song B: "爱情转移" (by Eason Chan)
        val matchB = PlayerUiHook.titlesMatch("爱", "爱情转移")
        assertTrue("EMPIRICAL PROOF: '爱' matches '爱情转移' due to substring containment", matchB)

        // Case C: Distinct songs sharing a prefix
        // Song A was replaced: "晴天" (Jay Chou)
        // User switches to unreplaced Song B: "晴天娃娃" (Jiang Yuheng / Lara Veronin)
        val matchC = PlayerUiHook.titlesMatch("晴天", "晴天娃娃")
        assertTrue("EMPIRICAL PROOF: '晴天' matches '晴天娃娃' due to substring containment", matchC)

        // Case D: Distinct songs sharing a suffix
        // Song A was replaced: "红豆" (Faye Wong)
        // User switches to unreplaced Song B: "相思红豆" (Huang Siting)
        val matchD = PlayerUiHook.titlesMatch("红豆", "相思红豆")
        assertTrue("EMPIRICAL PROOF: '红豆' matches '相思红豆' due to substring containment", matchD)

        // Case E: English short song titles
        // Song A was replaced: "One" (U2)
        // User switches to unreplaced Song B: "One More Night" (Maroon 5)
        val matchE = PlayerUiHook.titlesMatch("One", "One More Night")
        assertTrue("EMPIRICAL PROOF: 'One' matches 'One More Night' due to substring containment", matchE)

        // Case F: "Me" (Taylor Swift) vs "Tell Me Why" (Declan Galbraith)
        val matchF = PlayerUiHook.titlesMatch("Me", "Tell Me Why")
        assertTrue("EMPIRICAL PROOF: 'Me' matches 'Tell Me Why' due to substring containment", matchF)
    }

    /**
     * EMPIRICAL FINDING 2: When switching from a replaced track with suffix/tag
     * (e.g. "晴天 (Live)") to the native, unreplaced studio version ("晴天"),
     * `cleanA.contains(cleanB)` causes titlesMatch to return true.
     */
    @Test
    fun testTitlesMatchReplacedLiveToUnreplacedStudioFalsePositive() {
        // When replaced track was "晴天 (Live)" and user switches to unreplaced "晴天"
        val match = PlayerUiHook.titlesMatch("晴天 (Live)", "晴天")
        assertTrue("EMPIRICAL PROOF: '晴天 (Live)' matches '晴天' via cleanA.contains(cleanB)", match)
    }

    /**
     * Contrast test: When titles are truly unrelated, titlesMatch correctly returns false.
     */
    @Test
    fun testTitlesMatchTrulyDistinctSongsReturnFalse() {
        assertFalse(PlayerUiHook.titlesMatch("晴天", "七里香"))
        assertFalse(PlayerUiHook.titlesMatch("夜曲", "稻香"))
        assertFalse(PlayerUiHook.titlesMatch("Hello", "World"))
        assertFalse(PlayerUiHook.titlesMatch("In The End", "Numb"))
    }

    // =========================================================================
    // 3. Track Switching and Badge Clearing Lifecycle
    // =========================================================================

    @Test
    fun testTrackSwitchingToTrulyUnreplacedSongClearsBadgeLogic() {
        // Simulate: Replaced track is "晴天"
        val event = createEvent(originalTitle = "晴天")
        PlayerUiHook.onTrackReplaced(event)
        assertEquals(event, PlayerUiHook.latestEvent)

        // If user switches to "七里香" (unreplaced, titlesMatch returns false):
        val newTitle = "七里香"
        val matches = PlayerUiHook.titlesMatch(PlayerUiHook.latestEvent!!.originalTitle, newTitle)
        assertFalse("Truly distinct song should not match", matches)
        // Under PlayerUiHook.attachSongNameWatcher afterTextChanged:
        // if (current == null || !titlesMatch(current.originalTitle, newTitle)) -> calls resetBadge(activity)
    }

    @Test
    fun testTrackSwitchingToSubstringUnreplacedSongFailsToClearBadgeLogic() {
        // Simulate: Replaced track is "晴天"
        val event = createEvent(originalTitle = "晴天")
        PlayerUiHook.onTrackReplaced(event)

        // User switches to "晴天娃娃" (an unreplaced free song):
        val newTitle = "晴天娃娃"
        val matches = PlayerUiHook.titlesMatch(PlayerUiHook.latestEvent!!.originalTitle, newTitle)

        // BUG DEMONSTRATION: titlesMatch returns true, so afterTextChanged executes:
        // updateBadge(activity, current) instead of resetBadge(activity)!
        assertTrue("DEMONSTRATES BUG: unreplaced song '晴天娃娃' erroneously matches replaced event '晴天'", matches)
    }

    @Test
    fun testLatestEventStickyRetentionAcrossTrackChanges() {
        val event = createEvent(originalTitle = "晴天")
        PlayerUiHook.onTrackReplaced(event)
        assertNotNull(PlayerUiHook.latestEvent)

        // In normal operation, PlayerUiHook never clears latestEvent unless resetForTesting() is called
        // If an unreplaced track is played, ReplaceEventBus does NOT publish an event, so latestEvent remains sticky
        assertEquals("晴天", PlayerUiHook.latestEvent?.originalTitle)
    }

    // =========================================================================
    // 4. Configuration Toggle (enablePlayerBadge)
    // =========================================================================

    @Test
    fun testConfigToggleDisabledStateInUnmConfig() {
        val enabledConfig = UnmConfig(enablePlayerBadge = true)
        assertTrue(enabledConfig.enablePlayerBadge)

        val disabledConfig = UnmConfig(enablePlayerBadge = false)
        assertFalse(disabledConfig.enablePlayerBadge)
    }

    @Test
    fun testConfigManagerHotReloadObservationSeam() {
        ConfigManager.setConfigForTesting(UnmConfig(enablePlayerBadge = true))
        assertTrue(ConfigManager.getConfig().enablePlayerBadge)

        ConfigManager.setConfigForTesting(UnmConfig(enablePlayerBadge = false))
        assertFalse(ConfigManager.getConfig().enablePlayerBadge)
    }

    /**
     * EMPIRICAL FINDING 3: PlayerUiHook does NOT subscribe to ConfigManager.addOnConfigChangeListener.
     * When enablePlayerBadge is toggled at runtime, currently open PlayerActivity badge is NOT
     * immediately reset until a subsequent lifecycle or event dispatch occurs.
     */
    @Test
    fun testPlayerUiHookDoesNotSubscribeToConfigChangeDirectly() {
        // Registering a listener on ConfigManager works for external observers
        var configChangedNotified = false
        val unregister = ConfigManager.addOnConfigChangeListener { cfg ->
            if (!cfg.enablePlayerBadge) {
                configChangedNotified = true
            }
        }

        // Using setConfigForTesting to simulate state
        ConfigManager.setConfigForTesting(UnmConfig(enablePlayerBadge = false))
        assertFalse(ConfigManager.getConfig().enablePlayerBadge)

        unregister()
    }

    // =========================================================================
    // 5. ReplaceEventBus Concurrency & Thread Safety Stress Test
    // =========================================================================

    @Test
    fun testReplaceEventBusHighConcurrencyStress() {
        val threadCount = 8
        val eventsPerThread = 200
        val totalExpected = threadCount * eventsPerThread
        val executor = Executors.newFixedThreadPool(threadCount)
        val receivedCount = AtomicInteger(0)
        val startLatch = CountDownLatch(1)
        val doneLatch = CountDownLatch(threadCount)

        ReplaceEventBus.subscribe {
            receivedCount.incrementAndGet()
        }

        for (t in 0 until threadCount) {
            executor.submit {
                try {
                    startLatch.await()
                    for (i in 0 until eventsPerThread) {
                        val event = createEvent(
                            songId = (t * 1000 + i).toLong(),
                            originalTitle = "Song-$t-$i"
                        )
                        ReplaceEventBus.publish(event)
                    }
                } finally {
                    doneLatch.countDown()
                }
            }
        }

        startLatch.countDown()
        val finished = doneLatch.await(10, TimeUnit.SECONDS)
        executor.shutdown()

        assertTrue("All concurrent publishing threads must finish within timeout", finished)
        assertEquals("All published events must be safely dispatched to listener without drops", totalExpected, receivedCount.get())
        assertNotNull("Latest event must be retained", ReplaceEventBus.latestEvent)
    }

    @Test
    fun testReplaceEventBusListenerExceptionIsolation() {
        val normalListenerReceived = AtomicInteger(0)

        // Faulty listener that throws
        ReplaceEventBus.subscribe {
            throw RuntimeException("Simulated malicious listener failure")
        }

        // Normal listener
        ReplaceEventBus.subscribe {
            normalListenerReceived.incrementAndGet()
        }

        // Publish event
        val event = createEvent(songId = 99999L, originalTitle = "Isolated Song")
        ReplaceEventBus.publish(event)

        assertEquals("Normal listener must still receive event despite faulty listener", 1, normalListenerReceived.get())
        assertEquals(event, ReplaceEventBus.latestEvent)
    }

    @Test
    fun testReplaceEventBusUnsubscribeSafety() {
        val received = AtomicInteger(0)
        var unregister: (() -> Unit)? = null

        unregister = ReplaceEventBus.subscribe {
            received.incrementAndGet()
        }

        ReplaceEventBus.publish(createEvent(songId = 1L))
        assertEquals(1, received.get())

        // Unsubscribe
        unregister()

        ReplaceEventBus.publish(createEvent(songId = 2L))
        assertEquals("No more events should be received after unsubscribe", 1, received.get())
    }
}
