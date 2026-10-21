package pochita.hook

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import pochita.config.ConfigManager
import pochita.config.UnmConfig
import pochita.event.ReplaceEventBus
import pochita.model.ReplaceEvent

class PlayerUiHookTest {

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

    private fun sampleEvent(title: String = "晴天") = ReplaceEvent(
        songId = 12345L,
        originalTitle = title,
        originalArtist = "周杰伦",
        matchedSource = "kuwo",
        matchedTitle = title,
        matchedArtist = "周杰伦",
        br = 320000
    )

    // =========================================================================
    // 1. titlesMatch Logic Tests
    // =========================================================================

    @Test
    fun testTitlesMatchExact() {
        assertTrue(PlayerUiHook.titlesMatch("晴天", "晴天"))
        assertTrue(PlayerUiHook.titlesMatch("In The End", "In The End"))
    }

    @Test
    fun testTitlesMatchCaseInsensitive() {
        assertTrue(PlayerUiHook.titlesMatch("TiK ToK", "tik tok"))
        assertTrue(PlayerUiHook.titlesMatch("Numb", "NUMB"))
    }

    @Test
    fun testTitlesMatchWhitespaceVariations() {
        assertTrue(PlayerUiHook.titlesMatch("晴 天", "晴天"))
        assertTrue(PlayerUiHook.titlesMatch("  七里香  ", "七里香"))
        assertTrue(PlayerUiHook.titlesMatch("A B C", "ABC"))
    }

    @Test
    fun testTitlesMatchSubstringContainment() {
        assertTrue(PlayerUiHook.titlesMatch("晴天", "晴天 (Live)"))
        assertTrue(PlayerUiHook.titlesMatch("晴天 (Live)", "晴天"))
    }

    @Test
    fun testTitlesMatchDifferentSongsReturnsFalse() {
        assertFalse(PlayerUiHook.titlesMatch("晴天", "七里香"))
        assertFalse(PlayerUiHook.titlesMatch("Hello", "World"))
    }

    @Test
    fun testTitlesMatchEmptyReturnsFalse() {
        assertFalse(PlayerUiHook.titlesMatch("", ""))
        assertFalse(PlayerUiHook.titlesMatch("   ", ""))
        assertFalse(PlayerUiHook.titlesMatch("晴天", ""))
        assertFalse(PlayerUiHook.titlesMatch("", "晴天"))
    }

    // =========================================================================
    // 2. Event Bus & State Integration Tests
    // =========================================================================

    @Test
    fun testOnTrackReplacedStoresLatestEvent() {
        assertNull(PlayerUiHook.latestEvent)

        val event = sampleEvent("夜曲")
        PlayerUiHook.onTrackReplaced(event)

        assertEquals(event, PlayerUiHook.latestEvent)
        assertEquals("夜曲", PlayerUiHook.latestEvent?.originalTitle)
    }

    @Test
    fun testResetForTestingCleansState() {
        val event = sampleEvent("枫")
        PlayerUiHook.onTrackReplaced(event)
        assertEquals(event, PlayerUiHook.latestEvent)

        PlayerUiHook.resetForTesting()
        assertNull(PlayerUiHook.latestEvent)
    }

    @Test
    fun testEventBusPublishTriggersOnTrackReplacedWhenSubscribed() {
        // Mock bus listener attachment
        ReplaceEventBus.subscribe { event ->
            PlayerUiHook.onTrackReplaced(event)
        }

        val event = sampleEvent("发如雪")
        ReplaceEventBus.publish(event)

        assertEquals("发如雪", PlayerUiHook.latestEvent?.originalTitle)
    }
}
