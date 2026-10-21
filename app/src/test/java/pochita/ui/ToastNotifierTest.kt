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
import pochita.model.ReplaceEvent

class ToastNotifierTest {

    private val recordedToasts = mutableListOf<String>()
    private var virtualTimeMs: Long = 10_000L
    private val fakeContext: Context = FakeContext()

    private class FakeContext : android.content.ContextWrapper(null)

    @Before
    fun setUp() {
        ConfigManager.resetForTesting()
        ToastNotifier.resetForTesting()
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
    }

    private fun sampleEvent(
        songId: Long = 101L,
        originalTitle: String = "晴天",
        matchedTitle: String = originalTitle,
        matchedSource: String = "kuwo",
        br: Int = 320000
    ) = ReplaceEvent(
        songId = songId,
        originalTitle = originalTitle,
        originalArtist = "周杰伦",
        matchedSource = matchedSource,
        matchedTitle = matchedTitle,
        matchedArtist = "周杰伦",
        br = br
    )

    // =========================================================================
    // 1. Message Formatting Tests
    // =========================================================================

    @Test
    fun testFormatMessageKuwo320k() {
        val event = sampleEvent(matchedSource = "kuwo", matchedTitle = "晴天 (Live)", br = 320000)
        val formatted = ToastNotifier.formatMessage(event)
        assertEquals("🎵 云村换源: 已替换为 [酷我]《晴天 (Live)》· 320k", formatted)
    }

    @Test
    fun testFormatMessageBodian192k() {
        val event = sampleEvent(matchedSource = "bodian", matchedTitle = "七里香", br = 192000)
        val formatted = ToastNotifier.formatMessage(event)
        assertEquals("🎵 云村换源: 已替换为 [波点]《七里香》· 192k", formatted)
    }

    @Test
    fun testFormatMessageMiguFlac() {
        val event = sampleEvent(matchedSource = "migu", matchedTitle = "夜曲", br = 999000)
        val formatted = ToastNotifier.formatMessage(event)
        assertEquals("🎵 云村换源: 已替换为 [咪咕]《夜曲》· FLAC", formatted)
    }

    @Test
    fun testFormatMessageKugou128k() {
        val event = sampleEvent(matchedSource = "kugou", matchedTitle = "一路向北", br = 128000)
        val formatted = ToastNotifier.formatMessage(event)
        assertEquals("🎵 云村换源: 已替换为 [酷狗]《一路向北》· 128k", formatted)
    }

    @Test
    fun testFormatMessageFallbackToOriginalTitleWhenMatchedTitleBlank() {
        val event = sampleEvent(originalTitle = "青花瓷", matchedTitle = "   ", matchedSource = "kuwo", br = 320000)
        val formatted = ToastNotifier.formatMessage(event)
        assertEquals("🎵 云村换源: 已替换为 [酷我]《青花瓷》· 320k", formatted)
    }

    @Test
    fun testFormatMessageCustomSource() {
        val event = sampleEvent(matchedSource = "custom_js", matchedTitle = "枫", br = 320000)
        val formatted = ToastNotifier.formatMessage(event)
        assertEquals("🎵 云村换源: 已替换为 [Custom_js]《枫》· 320k", formatted)
    }

    @Test
    fun testFormatBitrateEdgeCases() {
        assertEquals("320k", ToastNotifier.formatBitrate(0))
        assertEquals("320k", ToastNotifier.formatBitrate(-1))
        assertEquals("FLAC", ToastNotifier.formatBitrate(999000))
        assertEquals("320k", ToastNotifier.formatBitrate(320000))
        assertEquals("192k", ToastNotifier.formatBitrate(192000))
        assertEquals("128k", ToastNotifier.formatBitrate(128000))
    }

    // =========================================================================
    // 2. Debouncing Logic (3-Second Cooldown Window)
    // =========================================================================

    @Test
    fun testDebouncingSuppressesToastsWithin3Seconds() {
        ConfigManager.setConfigForTesting(UnmConfig(enableToast = true))

        // First toast at t = 10,000ms -> should display
        virtualTimeMs = 10_000L
        val res1 = ToastNotifier.notify(fakeContext, sampleEvent(1L, "晴天"))
        assertTrue("First toast must be displayed", res1)
        assertEquals(1, recordedToasts.size)

        // Rapid duplicate at t = 10,500ms (500ms later) -> should be debounced
        virtualTimeMs = 10_500L
        val res2 = ToastNotifier.notify(fakeContext, sampleEvent(1L, "晴天"))
        assertFalse("Rapid duplicate must be debounced", res2)
        assertEquals(1, recordedToasts.size)

        // Next song at t = 12_500ms (2500ms later < 3000ms) -> should be debounced
        virtualTimeMs = 12_500L
        val res3 = ToastNotifier.notify(fakeContext, sampleEvent(2L, "七里香"))
        assertFalse("Toast within 3 seconds must be debounced", res3)
        assertEquals(1, recordedToasts.size)

        // Song at t = 13_001ms (3001ms later > 3000ms) -> should display
        virtualTimeMs = 13_001L
        val res4 = ToastNotifier.notify(fakeContext, sampleEvent(3L, "夜曲"))
        assertTrue("Toast after 3-second cooldown must be displayed", res4)
        assertEquals(2, recordedToasts.size)
        assertTrue(recordedToasts[1].contains("夜曲"))
    }

    @Test
    fun testDebounceExact3000msBoundary() {
        ConfigManager.setConfigForTesting(UnmConfig(enableToast = true))

        virtualTimeMs = 10_000L
        assertTrue(ToastNotifier.notify(fakeContext, sampleEvent(1L, "晴天")))

        // Exactly 3000ms boundary: 10_000 + 3000 = 13_000ms
        virtualTimeMs = 13_000L
        val res = ToastNotifier.notify(fakeContext, sampleEvent(2L, "七里香"))
        assertTrue("Toast exactly at 3000ms cooldown boundary must be permitted", res)
        assertEquals(2, recordedToasts.size)
    }

    // =========================================================================
    // 3. Configuration Toggle (enableToast)
    // =========================================================================

    @Test
    fun testNotifySuppressedWhenEnableToastIsFalse() {
        ConfigManager.setConfigForTesting(UnmConfig(enableToast = false))

        val result = ToastNotifier.notify(fakeContext, sampleEvent())
        assertFalse("Toast must not be shown when enableToast is false", result)
        assertTrue("No toast text recorded", recordedToasts.isEmpty())
    }

    @Test
    fun testDisabledToastDoesNotConsumeDebounceWindow() {
        // At t = 10,000ms, enableToast is false
        ConfigManager.setConfigForTesting(UnmConfig(enableToast = false))
        virtualTimeMs = 10_000L
        val res1 = ToastNotifier.notify(fakeContext, sampleEvent(1L, "晴天"))
        assertFalse(res1)
        assertEquals(0, recordedToasts.size)

        // At t = 11,000ms (1 second later), enableToast is switched to true
        ConfigManager.setConfigForTesting(UnmConfig(enableToast = true))
        virtualTimeMs = 11_000L
        val res2 = ToastNotifier.notify(fakeContext, sampleEvent(2L, "七里香"))
        assertTrue("Toast must display immediately without being blocked by prior disabled call", res2)
        assertEquals(1, recordedToasts.size)
    }

    // =========================================================================
    // 4. Fault Tolerance & Exception Isolation
    // =========================================================================

    @Test
    fun testToastShowerExceptionDoesNotPropagate() {
        ConfigManager.setConfigForTesting(UnmConfig(enableToast = true))
        ToastNotifier.setToastShowerForTesting { _, _, _ ->
            throw RuntimeException("Simulated WindowManager.BadTokenException")
        }

        // Must not throw exception
        val res = ToastNotifier.notify(fakeContext, sampleEvent())
        assertTrue("Notify must return true indicating event was processed despite display error", res)
    }

    @Test
    fun testNotifyWithoutContextUsesInitContext() {
        ConfigManager.setConfigForTesting(UnmConfig(enableToast = true))
        ToastNotifier.init(fakeContext)

        val res = ToastNotifier.notify(sampleEvent(555L, "稻香"))
        assertTrue("Notify with cached appContext must succeed", res)
        assertEquals(1, recordedToasts.size)
        assertTrue(recordedToasts[0].contains("稻香"))
    }
}
