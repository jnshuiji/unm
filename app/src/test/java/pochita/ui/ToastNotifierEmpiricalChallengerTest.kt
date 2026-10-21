package pochita.ui

import android.content.Context
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import pochita.config.ConfigManager
import pochita.config.UnmConfig
import pochita.event.ReplaceEventBus
import pochita.model.ReplaceEvent
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * Empirical Adversarial Challenger Test for [ToastNotifier].
 *
 * Focus areas:
 * 1. Debounce 3-second boundary testing (<3000ms vs >=3000ms).
 * 2. Concurrency stress: 100+ concurrent threads calling notify().
 * 3. Multi-wave cooldown enforcement (t=10s, t=11.5s, t=13s, t=15.999s, t=16s).
 * 4. Boundary behavior when clock < 3000ms (e.g. system uptime near 0).
 * 5. Out-of-order execution / negative time delta analysis.
 * 6. Config toggle interleaving & error isolation during Toast rendering.
 */
class ToastNotifierEmpiricalChallengerTest {

    private val recordedToasts = CopyOnWriteArrayList<String>()
    private var virtualTimeMs: Long = 10_000L
    private val fakeContext: Context = FakeContext()

    private class FakeContext : android.content.ContextWrapper(null)

    @Before
    fun setUp() {
        ConfigManager.resetForTesting()
        ToastNotifier.resetForTesting()
        ReplaceEventBus.resetForTesting()
        recordedToasts.clear()
        virtualTimeMs = 10_000L

        ToastNotifier.setTimeProviderForTesting { virtualTimeMs }
        ToastNotifier.setMainThreadDispatcherForTesting { action -> action() }
        ToastNotifier.setToastShowerForTesting { _, text, _ ->
            recordedToasts.add(text.toString())
        }
    }

    @After
    fun tearDown() {
        ConfigManager.resetForTesting()
        ToastNotifier.resetForTesting()
        ReplaceEventBus.resetForTesting()
    }

    private fun sampleEvent(
        songId: Long = 101L,
        title: String = "晴天",
        matchedTitle: String = title,
        source: String = "kuwo",
        br: Int = 320000
    ) = ReplaceEvent(
        songId = songId,
        originalTitle = title,
        originalArtist = "周杰伦",
        matchedSource = source,
        matchedTitle = matchedTitle,
        matchedArtist = "周杰伦",
        br = br
    )

    // =========================================================================
    // 1. Precise Debounce 3-Second Boundary Testing
    // =========================================================================

    @Test
    fun testDebounceStrictSub3000msVersusEqualOrGreater3000msBoundaries() {
        ConfigManager.setConfigForTesting(UnmConfig(enableToast = true))

        // Step 1: Base event at t = 10,000ms -> Must fire
        virtualTimeMs = 10_000L
        val res1 = ToastNotifier.notify(fakeContext, sampleEvent(1L, "Song-1"))
        assertTrue("Base event at t=10,000ms must fire", res1)
        assertEquals(1, recordedToasts.size)

        // Step 2: Boundary test: t = 10,001ms (+1ms) -> Must debounce (<3000ms)
        virtualTimeMs = 10_001L
        val res2 = ToastNotifier.notify(fakeContext, sampleEvent(2L, "Song-2"))
        assertFalse("Event at +1ms must be debounced", res2)
        assertEquals(1, recordedToasts.size)

        // Step 3: Mid-window test: t = 11,500ms (+1500ms) -> Must debounce (<3000ms)
        virtualTimeMs = 11_500L
        val res3 = ToastNotifier.notify(fakeContext, sampleEvent(3L, "Song-3"))
        assertFalse("Event at +1500ms must be debounced", res3)
        assertEquals(1, recordedToasts.size)

        // Step 4: Critical boundary test: t = 12,999ms (+2999ms) -> Must debounce (<3000ms)
        virtualTimeMs = 12_999L
        val res4 = ToastNotifier.notify(fakeContext, sampleEvent(4L, "Song-4"))
        assertFalse("Event at +2999ms must strictly be debounced (<3000ms)", res4)
        assertEquals(1, recordedToasts.size)

        // Step 5: Exact boundary test: t = 13,000ms (+3000ms) -> Must fire (>=3000ms)
        virtualTimeMs = 13_000L
        val res5 = ToastNotifier.notify(fakeContext, sampleEvent(5L, "Song-5"))
        assertTrue("Event at exact +3000ms boundary must fire (>=3000ms)", res5)
        assertEquals(2, recordedToasts.size)
        assertTrue(recordedToasts[1].contains("Song-5"))

        // Step 6: Immediately following Toast #2: t = 13,001ms (+1ms) -> Must debounce
        virtualTimeMs = 13_001L
        val res6 = ToastNotifier.notify(fakeContext, sampleEvent(6L, "Song-6"))
        assertFalse("Event at +1ms after 2nd toast must be debounced", res6)
        assertEquals(2, recordedToasts.size)

        // Step 7: Boundary before 3rd toast: t = 15,999ms (+2999ms after Toast #2) -> Must debounce
        virtualTimeMs = 15_999L
        val res7 = ToastNotifier.notify(fakeContext, sampleEvent(7L, "Song-7"))
        assertFalse("Event at +2999ms after 2nd toast must be debounced", res7)
        assertEquals(2, recordedToasts.size)

        // Step 8: Exact boundary for 3rd toast: t = 16,000ms (+3000ms after Toast #2) -> Must fire
        virtualTimeMs = 16_000L
        val res8 = ToastNotifier.notify(fakeContext, sampleEvent(8L, "Song-8"))
        assertTrue("Event at exact +3000ms boundary after 2nd toast must fire", res8)
        assertEquals(3, recordedToasts.size)
        assertTrue(recordedToasts[2].contains("Song-8"))

        // Step 9: Long interval: t = 25,000ms (+9000ms) -> Must fire
        virtualTimeMs = 25_000L
        val res9 = ToastNotifier.notify(fakeContext, sampleEvent(9L, "Song-9"))
        assertTrue("Event well after cooldown must fire", res9)
        assertEquals(4, recordedToasts.size)
        assertTrue(recordedToasts[3].contains("Song-9"))
    }

    // =========================================================================
    // 2. Concurrency Stress: 100+ Concurrent Threads Calling notify()
    // =========================================================================

    @Test
    fun test100ConcurrentThreadsAtSameMillisecondOnlyOneToastFires() {
        ConfigManager.setConfigForTesting(UnmConfig(enableToast = true))
        virtualTimeMs = 10_000L

        val threadCount = 100
        val executor = Executors.newFixedThreadPool(threadCount)
        val startLatch = CountDownLatch(1)
        val doneLatch = CountDownLatch(threadCount)
        val successCount = AtomicInteger(0)

        for (i in 0 until threadCount) {
            val songId = i.toLong()
            executor.submit {
                try {
                    startLatch.await(5, TimeUnit.SECONDS)
                    val scheduled = ToastNotifier.notify(fakeContext, sampleEvent(songId, "ConcurrentSong-$songId"))
                    if (scheduled) {
                        successCount.incrementAndGet()
                    }
                } finally {
                    doneLatch.countDown()
                }
            }
        }

        startLatch.countDown()
        assertTrue("All 100 threads must complete within 5 seconds", doneLatch.await(5, TimeUnit.SECONDS))
        executor.shutdown()

        assertEquals("Exactly 1 thread out of 100 must succeed in firing a Toast at t=10,000ms",
            1, successCount.get())
        assertEquals("Exactly 1 Toast message must be recorded", 1, recordedToasts.size)
    }

    @Test
    fun testMultiWave100ThreadStressAcrossCooldownCycle() {
        ConfigManager.setConfigForTesting(UnmConfig(enableToast = true))
        val threadCount = 100

        // Wave 1: t = 10,000ms -> Exactly 1 fires
        virtualTimeMs = 10_000L
        runConcurrentWave(threadCount, 100, 1000L, expectedSuccesses = 1)
        assertEquals("Wave 1 must produce exactly 1 toast", 1, recordedToasts.size)

        // Wave 2: t = 11,500ms (+1500ms) -> 0 fires (all 100 debounced)
        virtualTimeMs = 11_500L
        runConcurrentWave(threadCount, 100, 2000L, expectedSuccesses = 0)
        assertEquals("Wave 2 must be 100% debounced, keeping total toasts at 1", 1, recordedToasts.size)

        // Wave 3: t = 12,999ms (+2999ms) -> 0 fires (all 100 debounced)
        virtualTimeMs = 12_999L
        runConcurrentWave(threadCount, 100, 3000L, expectedSuccesses = 0)
        assertEquals("Wave 3 at 2999ms must be 100% debounced, keeping total toasts at 1", 1, recordedToasts.size)

        // Wave 4: t = 13,000ms (+3000ms) -> Exactly 1 fires
        virtualTimeMs = 13_000L
        runConcurrentWave(threadCount, 100, 4000L, expectedSuccesses = 1)
        assertEquals("Wave 4 at exact 3000ms boundary must produce second toast", 2, recordedToasts.size)

        // Wave 5: t = 16,000ms (+3000ms after Wave 4) -> Exactly 1 fires
        virtualTimeMs = 16_000L
        runConcurrentWave(threadCount, 100, 5000L, expectedSuccesses = 1)
        assertEquals("Wave 5 at exact 3000ms boundary must produce third toast", 3, recordedToasts.size)
    }

    private fun runConcurrentWave(threadCount: Int, threads: Int, baseSongId: Long, expectedSuccesses: Int) {
        val executor = Executors.newFixedThreadPool(threadCount)
        val startLatch = CountDownLatch(1)
        val doneLatch = CountDownLatch(threadCount)
        val successes = AtomicInteger(0)

        for (i in 0 until threadCount) {
            val songId = baseSongId + i
            executor.submit {
                try {
                    startLatch.await(5, TimeUnit.SECONDS)
                    if (ToastNotifier.notify(fakeContext, sampleEvent(songId, "Song-$songId"))) {
                        successes.incrementAndGet()
                    }
                } finally {
                    doneLatch.countDown()
                }
            }
        }

        startLatch.countDown()
        assertTrue(doneLatch.await(5, TimeUnit.SECONDS))
        executor.shutdown()
        assertEquals("Wave must have expected successes", expectedSuccesses, successes.get())
    }

    // =========================================================================
    // 3. Initial State & Early Uptime Boundary Investigation
    // =========================================================================

    @Test
    fun testInitialUptimeBoundaryWhenTimeIsUnder3000ms() {
        ConfigManager.setConfigForTesting(UnmConfig(enableToast = true))

        // When device uptime or virtual time starts at < 3000ms (e.g. 1500ms after boot):
        // lastToastTimestamp is initialized to 0L.
        // If notify() checks `now - lastToastTimestamp in 0 until 3000`,
        // then 1500L - 0L = 1500L, which is inside [0, 2999]!
        // This test empirically verifies whether ToastNotifier permits or blocks the FIRST toast.
        virtualTimeMs = 1500L
        val result = ToastNotifier.notify(fakeContext, sampleEvent(1L, "FirstSongEver"))

        // Empirically observe the behavior:
        println("Empirical finding: ToastNotifier.notify at virtualTimeMs=1500L returned: $result")
    }

    // =========================================================================
    // 4. Out-of-Order Lock Acquisition / Negative Delta Simulation
    // =========================================================================

    @Test
    fun testNegativeDeltaBehaviorWhenClockAdjustsOrThreadsArriveOutOfOrder() {
        ConfigManager.setConfigForTesting(UnmConfig(enableToast = true))

        // Toast 1 fires at t = 10,005ms
        virtualTimeMs = 10_005L
        val res1 = ToastNotifier.notify(fakeContext, sampleEvent(1L, "Song-1"))
        assertTrue("Toast at 10,005ms fires", res1)

        // Suppose clock shifts back or a delayed thread arrives with evaluated now = 10,000ms (-5ms relative to last)
        virtualTimeMs = 10_000L
        val res2 = ToastNotifier.notify(fakeContext, sampleEvent(2L, "Song-2"))

        // Empirically observe: does it treat negative delta as debounced or bypass debounce?
        println("Empirical finding: When now (10,000ms) < lastToastTimestamp (10,005ms), notify() returned: $res2")
    }

    // =========================================================================
    // 5. Config Toggle and Error Resilience
    // =========================================================================

    @Test
    fun testConfigToggleInterleavingDoesNotPolluteCooldown() {
        // Disabled at t=10,000ms
        ConfigManager.setConfigForTesting(UnmConfig(enableToast = false))
        virtualTimeMs = 10_000L
        assertFalse(ToastNotifier.notify(fakeContext, sampleEvent(1L)))

        // Enabled at t=10,500ms (500ms later)
        ConfigManager.setConfigForTesting(UnmConfig(enableToast = true))
        virtualTimeMs = 10_500L
        assertTrue("Enabling config should allow immediate toast if none was previously fired",
            ToastNotifier.notify(fakeContext, sampleEvent(2L)))
        assertEquals(1, recordedToasts.size)

        // Event at t=11,000ms while enabled -> debounced
        virtualTimeMs = 11_000L
        assertFalse(ToastNotifier.notify(fakeContext, sampleEvent(3L)))

        // Event at t=13,500ms (3000ms after t=10,500ms) -> fires
        virtualTimeMs = 13_500L
        assertTrue(ToastNotifier.notify(fakeContext, sampleEvent(4L)))
        assertEquals(2, recordedToasts.size)
    }

    @Test
    fun testToastShowerExceptionIsolation() {
        ConfigManager.setConfigForTesting(UnmConfig(enableToast = true))
        ToastNotifier.setToastShowerForTesting { _, _, _ ->
            throw IllegalStateException("Simulated WindowManager BadTokenException or ServiceNotFoundException")
        }

        virtualTimeMs = 10_000L
        val res = ToastNotifier.notify(fakeContext, sampleEvent(1L))
        assertTrue("notify() must return true and not throw despite toastShower throwing", res)
    }

    // =========================================================================
    // 6. Init Idempotency & Lifecycle Safety
    // =========================================================================

    @Test
    fun testInitMultipleTimesAccumulatesSubscribersInEventBus() {
        ReplaceEventBus.resetForTesting()
        ToastNotifier.resetForTesting()

        assertEquals(0, ReplaceEventBus.subscriberCount)
        ToastNotifier.init(fakeContext)
        val initialCount = ReplaceEventBus.subscriberCount
        assertEquals(1, initialCount)

        // Calling init again:
        ToastNotifier.init(fakeContext)
        val secondCount = ReplaceEventBus.subscriberCount

        // Empirical observation of whether ToastNotifier.init is idempotent
        println("Empirical finding: ToastNotifier.init called twice resulted in $secondCount subscribers in ReplaceEventBus")
    }

    // =========================================================================
    // 7. Message Formatting Exhaustive Edge Cases
    // =========================================================================

    @Test
    fun testFormatMessageExhaustiveMatrix() {
        // Unknown source
        val e1 = sampleEvent(source = "unknown_plat", matchedTitle = "TestSong", br = 128000)
        assertEquals("🎵 云村换源: 已替换为 [Unknown_plat]《TestSong》· 128k", ToastNotifier.formatMessage(e1))

        // Empty source
        val e2 = sampleEvent(source = "", matchedTitle = "TestSong", br = 320000)
        assertEquals("🎵 云村换源: 已替换为 [未知音源]《TestSong》· 320k", ToastNotifier.formatMessage(e2))

        // Blank matchedTitle fallbacks to originalTitle
        val e3 = sampleEvent(title = "OriginalOnly", matchedTitle = "   ", source = "kuwo", br = 320000)
        assertEquals("🎵 云村换源: 已替换为 [酷我]《OriginalOnly》· 320k", ToastNotifier.formatMessage(e3))

        // Both titles blank
        val e4 = sampleEvent(title = "   ", matchedTitle = "   ", source = "bodian", br = 320000)
        assertEquals("🎵 云村换源: 已替换为 [波点]《》· 320k", ToastNotifier.formatMessage(e4))

        // Bitrates
        assertEquals("FLAC", ToastNotifier.formatBitrate(999000))
        assertEquals("FLAC", ToastNotifier.formatBitrate(920000))
        assertEquals("320k", ToastNotifier.formatBitrate(320000))
        assertEquals("192k", ToastNotifier.formatBitrate(192000))
        assertEquals("128k", ToastNotifier.formatBitrate(128000))
        assertEquals("64k", ToastNotifier.formatBitrate(64000))
        assertEquals("320k", ToastNotifier.formatBitrate(0))
        assertEquals("320k", ToastNotifier.formatBitrate(-100))
    }
}

