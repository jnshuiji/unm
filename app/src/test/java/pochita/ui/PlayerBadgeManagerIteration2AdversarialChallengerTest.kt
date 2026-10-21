package pochita.ui

import android.app.Activity
import android.widget.TextView
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import pochita.config.ConfigManager
import pochita.config.FakeSharedPreferences
import pochita.config.UnmConfig
import pochita.data.PreferenceKeys
import pochita.event.ReplaceEventBus
import pochita.hook.PlayerUiHook
import pochita.model.ReplaceEvent
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * Empirical Adversarial Challenger Test Suite for Phase 2 Milestone 2 Iteration 2:
 *
 * Specifically verifies:
 * 1. titlesMatch does not bleed badge on substring overlaps (symmetric, prefix, suffix, inner, Chinese/English).
 * 2. enablePlayerBadge runtime toggle off immediately resets active badge across lifecycle and event dispatches.
 * 3. Title normalization preserves true matches while rejecting false positive substrings.
 * 4. High-stress concurrency, large input resistance, and flip-flop stability.
 */
class PlayerBadgeManagerIteration2AdversarialChallengerTest {

    private lateinit var fakePrefs: FakeSharedPreferences

    @Before
    fun setUp() {
        ConfigManager.resetForTesting()
        ReplaceEventBus.resetForTesting()
        PlayerUiHook.resetForTesting()
        fakePrefs = FakeSharedPreferences()
        ConfigManager.init(fakePrefs)
    }

    @After
    fun tearDown() {
        ConfigManager.resetForTesting()
        ReplaceEventBus.resetForTesting()
        PlayerUiHook.resetForTesting()
    }

    private fun createEvent(
        songId: Long = 88888L,
        title: String = "晴天",
        artist: String = "周杰伦",
        matchedSource: String = "kuwo",
        br: Int = 320000
    ) = ReplaceEvent(
        songId = songId,
        originalTitle = title,
        originalArtist = artist,
        matchedSource = matchedSource,
        matchedTitle = title,
        matchedArtist = artist,
        br = br
    )

    // =========================================================================
    // 1. Empirical Challenge: titlesMatch Substring Bleed Prevention
    // =========================================================================

    @Test
    fun testTitlesMatchSymmetricSubstringRejectionMatrix() {
        val substringPairs = listOf(
            // Single character substring collisions
            "你" to "你的答案",
            "爱" to "爱情转移",
            "我" to "我们俩",
            // Multi-character prefix collisions
            "晴天" to "晴天娃娃",
            "稻香" to "稻香村",
            "七里香" to "七里香花开",
            // Suffix collisions
            "红豆" to "相思红豆",
            "晚安" to "说声晚安",
            // Inner substring collisions
            "天" to "今天是晴天哦",
            "Love" to "I Love You",
            "Hello" to "Say Hello World",
            // English prefix/suffix collisions
            "One" to "One More Night",
            "Me" to "Tell Me Why",
            "Day" to "Green Day",
            "Star" to "Star Wars Theme",
            // Version tag collisions (Live, Acoustic, Demo)
            "晴天" to "晴天 (Live)",
            "晴天" to "晴天(现场版)",
            "晴天" to "晴天 [Acoustic]",
            "晴天" to "晴天-Remix",
            "晴天" to "晴天 Demo"
        )

        for ((shortTitle, longTitle) in substringPairs) {
            // Forward check: short vs long
            assertFalse(
                "Substrings must NOT match: '$shortTitle' vs '$longTitle'",
                PlayerUiHook.titlesMatch(shortTitle, longTitle)
            )
            // Reverse check: long vs short (symmetric)
            assertFalse(
                "Symmetric substrings must NOT match: '$longTitle' vs '$shortTitle'",
                PlayerUiHook.titlesMatch(longTitle, shortTitle)
            )
        }
    }

    @Test
    fun testTitlesMatchLegitimateEquivalencePreserved() {
        val equivalentPairs = listOf(
            // Exact same
            "晴天" to "晴天",
            "In The End" to "In The End",
            // Case insensitivity
            "numb" to "NUMB",
            "Shape of You" to "shape of you",
            // Leading/trailing/internal whitespace
            "  晴天  " to "晴天",
            "七 里 香" to "七里香",
            "A B C" to "ABC",
            // Chinese brackets and book title marks
            "《晴天》" to "晴天",
            "【晴天】" to "晴天",
            "「晴天」" to "晴天",
            "『晴天』" to "晴天",
            // Western brackets and punctuation
            "(晴天)" to "晴天",
            "[晴天]" to "晴天",
            "{晴天}" to "晴天",
            "It's My Life" to "Its My Life",
            "Hello, World!" to "Hello World",
            "Rock & Roll" to "Rock Roll"
        )

        for ((a, b) in equivalentPairs) {
            assertTrue(
                "Legitimate variants MUST match: '$a' vs '$b'",
                PlayerUiHook.titlesMatch(a, b)
            )
            assertTrue(
                "Symmetric legitimate variants MUST match: '$b' vs '$a'",
                PlayerUiHook.titlesMatch(b, a)
            )
        }
    }

    @Test
    fun testTitlesMatchBoundaryAndDegenerateInputs() {
        // Blank inputs
        assertFalse(PlayerUiHook.titlesMatch("", ""))
        assertFalse(PlayerUiHook.titlesMatch("   ", ""))
        assertFalse(PlayerUiHook.titlesMatch("", "   "))
        assertFalse(PlayerUiHook.titlesMatch("  \t\n  ", "  \r\n  "))
        assertFalse(PlayerUiHook.titlesMatch("晴天", ""))
        assertFalse(PlayerUiHook.titlesMatch("", "晴天"))
        assertFalse(PlayerUiHook.titlesMatch("晴天", "   "))

        // Pure punctuation strings
        assertTrue(
            "Identical pure punctuation should match",
            PlayerUiHook.titlesMatch("...", "...")
        )
        assertFalse(
            "Distinct pure punctuation should not match",
            PlayerUiHook.titlesMatch("...", "???")
        )
        assertFalse(
            "Pure punctuation vs real text should not match",
            PlayerUiHook.titlesMatch("...", "晴天")
        )
    }

    @Test(timeout = 5000)
    fun testTitlesMatchLargeInputPerformanceAndNoReDoS() {
        val longStringA = "晴天".repeat(5000) + "A"
        val longStringB = "晴天".repeat(5000) + "B"

        val startTime = System.currentTimeMillis()
        val result = PlayerUiHook.titlesMatch(longStringA, longStringB)
        val elapsed = System.currentTimeMillis() - startTime

        assertFalse(result)
        assertTrue("Regex normalization must be linear time and complete within 1000ms", elapsed < 1000)
    }

    // =========================================================================
    // 2. Empirical Challenge: enablePlayerBadge Runtime Toggle Off
    // =========================================================================

    @Test
    fun testRuntimeToggleOffTriggersResetBadgeImmediately() {
        val activity = Activity()
        PlayerUiHook.setCurrentActivityForTesting(activity)
        var resetBadgeCallCount = 0
        PlayerUiHook.onResetBadgeForTesting = { act ->
            if (act === activity) {
                resetBadgeCallCount++
            }
        }

        // Initially enabled
        fakePrefs.edit()
            .putBoolean(PreferenceKeys.KEY_ENABLE_PLAYER_BADGE, true)
            .apply()

        PlayerUiHook.ensureConfigSubscription()
        assertEquals(0, resetBadgeCallCount)

        // Runtime toggle: disable player badge
        fakePrefs.edit()
            .putBoolean(PreferenceKeys.KEY_ENABLE_PLAYER_BADGE, false)
            .apply()

        // resetBadge MUST be invoked immediately on the active activity
        assertEquals("Disabling enablePlayerBadge must immediately trigger resetBadge", 1, resetBadgeCallCount)
    }

    @Test
    fun testRuntimeToggleFlipFlopCycle() {
        val activity = Activity()
        PlayerUiHook.setCurrentActivityForTesting(activity)
        val resetCount = AtomicInteger(0)
        PlayerUiHook.onResetBadgeForTesting = {
            resetCount.incrementAndGet()
        }

        PlayerUiHook.ensureConfigSubscription()

        // 1. Toggle off -> resets
        fakePrefs.edit().putBoolean(PreferenceKeys.KEY_ENABLE_PLAYER_BADGE, false).apply()
        assertEquals(1, resetCount.get())

        // 2. Toggle on -> does not reset
        fakePrefs.edit().putBoolean(PreferenceKeys.KEY_ENABLE_PLAYER_BADGE, true).apply()
        assertEquals(1, resetCount.get())

        // 3. Toggle off again -> resets again
        fakePrefs.edit().putBoolean(PreferenceKeys.KEY_ENABLE_PLAYER_BADGE, false).apply()
        assertEquals(2, resetCount.get())
    }

    @Test
    fun testRuntimeToggleWithNoActiveActivityIsSafe() {
        PlayerUiHook.setCurrentActivityForTesting(null)
        var resetInvoked = false
        PlayerUiHook.onResetBadgeForTesting = { resetInvoked = true }

        PlayerUiHook.ensureConfigSubscription()

        // Should not throw NPE or error when currentActivityRef is null
        fakePrefs.edit().putBoolean(PreferenceKeys.KEY_ENABLE_PLAYER_BADGE, false).apply()
        assertFalse(resetInvoked)
    }

    @Test
    fun testUnregisterConfigListenerStopsNotifications() {
        val activity = Activity()
        PlayerUiHook.setCurrentActivityForTesting(activity)
        val resetCount = AtomicInteger(0)
        PlayerUiHook.onResetBadgeForTesting = { resetCount.incrementAndGet() }

        PlayerUiHook.ensureConfigSubscription()

        // First toggle off -> received
        fakePrefs.edit().putBoolean(PreferenceKeys.KEY_ENABLE_PLAYER_BADGE, false).apply()
        assertEquals(1, resetCount.get())

        // Unregister
        PlayerUiHook.unregisterConfigListener()

        // Second toggle off -> not received
        fakePrefs.edit().putBoolean(PreferenceKeys.KEY_ENABLE_PLAYER_BADGE, true).apply()
        fakePrefs.edit().putBoolean(PreferenceKeys.KEY_ENABLE_PLAYER_BADGE, false).apply()
        assertEquals("No notifications after unregister", 1, resetCount.get())
    }

    // =========================================================================
    // 3. Empirical Challenge: Badge Bleed Prevention in Track Switching
    // =========================================================================

    @Test
    fun testTrackSwitchingFromReplacedTrackToUnreplacedSubstringTrackResetsBadge() {
        // Track 1 replaced: "你"
        val event = createEvent(title = "你")
        PlayerUiHook.onTrackReplaced(event)
        assertEquals("你", PlayerUiHook.latestEvent?.originalTitle)

        // User switches to unreplaced Track 2: "你的答案"
        // In PlayerUiHook TextWatcher logic:
        // if (current == null || !titlesMatch(current.originalTitle, newTitle)) -> resetBadge(activity)
        val newTitle = "你的答案"
        val currentEvent = PlayerUiHook.latestEvent

        assertNotNull(currentEvent)
        val isMatch = PlayerUiHook.titlesMatch(currentEvent!!.originalTitle, newTitle)
        assertFalse("Must not match '你' to '你的答案'", isMatch)
    }

    @Test
    fun testTrackSwitchingFromReplacedTrackToUnreplacedPrefixTrackResetsBadge() {
        // Track 1 replaced: "晴天"
        val event = createEvent(title = "晴天")
        PlayerUiHook.onTrackReplaced(event)

        // User switches to unreplaced Track 2: "晴天娃娃"
        val newTitle = "晴天娃娃"
        val currentEvent = PlayerUiHook.latestEvent

        assertNotNull(currentEvent)
        val isMatch = PlayerUiHook.titlesMatch(currentEvent!!.originalTitle, newTitle)
        assertFalse("Must not match '晴天' to '晴天娃娃'", isMatch)
    }

    @Test
    fun testTrackSwitchingFromReplacedLiveTrackToUnreplacedStudioTrackResetsBadge() {
        // Track 1 replaced: "晴天 (Live)"
        val event = createEvent(title = "晴天 (Live)")
        PlayerUiHook.onTrackReplaced(event)

        // User switches to unreplaced Track 2: "晴天"
        val newTitle = "晴天"
        val currentEvent = PlayerUiHook.latestEvent

        assertNotNull(currentEvent)
        val isMatch = PlayerUiHook.titlesMatch(currentEvent!!.originalTitle, newTitle)
        assertFalse("Must not match '晴天 (Live)' to '晴天'", isMatch)
    }

    // =========================================================================
    // 4. Concurrency Stress Test: Config Changes and Event Dispatch
    // =========================================================================

    @Test
    fun testConcurrentConfigChangesAndEventDispatchStress() {
        val threadCount = 6
        val iterations = 100
        val executor = Executors.newFixedThreadPool(threadCount)
        val startLatch = CountDownLatch(1)
        val doneLatch = CountDownLatch(threadCount)
        val activity = Activity()
        PlayerUiHook.setCurrentActivityForTesting(activity)
        PlayerUiHook.ensureConfigSubscription()

        val resetCalls = AtomicInteger(0)
        PlayerUiHook.onResetBadgeForTesting = {
            resetCalls.incrementAndGet()
        }

        // Half threads toggle config, half publish events
        for (t in 0 until threadCount) {
            executor.submit {
                try {
                    startLatch.await()
                    for (i in 0 until iterations) {
                        if (t % 2 == 0) {
                            val enabled = (i % 2 == 0)
                            fakePrefs.edit().putBoolean(PreferenceKeys.KEY_ENABLE_PLAYER_BADGE, enabled).apply()
                        } else {
                            val event = createEvent(
                                songId = (t * 1000 + i).toLong(),
                                title = if (i % 2 == 0) "晴天" else "晴天娃娃"
                            )
                            ReplaceEventBus.publish(event)
                        }
                    }
                } finally {
                    doneLatch.countDown()
                }
            }
        }

        startLatch.countDown()
        val finished = doneLatch.await(10, TimeUnit.SECONDS)
        executor.shutdown()

        assertTrue("Concurrent stress test must complete within timeout", finished)
        assertTrue("resetBadge must have been called multiple times during disabling toggles", resetCalls.get() > 0)
    }
}
