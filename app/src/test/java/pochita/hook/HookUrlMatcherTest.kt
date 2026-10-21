package pochita.hook

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HookUrlMatcherTest {

    @Test
    fun testIsEApiUrlMatchesValidPatterns() {
        assertTrue(HookUrlMatcher.isEApiUrl("https://interface.music.163.com/eapi/v3/playlist/detail"))
        assertTrue(HookUrlMatcher.isEApiUrl("https://interface3.music.163.com/xeapi/playlist/v4/detail"))
        assertTrue(HookUrlMatcher.isEApiUrl("http://music.163.com/api/song/detail?id=123"))
        assertTrue(HookUrlMatcher.isEApiUrl("https://INTERFACE.MUSIC.163.COM/EAPI/SONG/DETAIL"))
        assertFalse(HookUrlMatcher.isEApiUrl("https://music.163.com/other/path"))
    }

    @Test
    fun testIsPlayerUrlMatchesPlayerPatterns() {
        assertTrue(HookUrlMatcher.isPlayerUrl("https://interface.music.163.com/eapi/song/enhance/player/url/v1"))
        assertTrue(HookUrlMatcher.isPlayerUrl("https://music.163.com/player/url?id=123"))
        assertTrue(HookUrlMatcher.isPlayerUrl("http://interface.music.163.com/song/enhance/player/url"))
        assertFalse(HookUrlMatcher.isPlayerUrl("https://interface.music.163.com/eapi/v3/playlist/detail"))
    }

    @Test
    fun testIsStaticAssetRecognizesAllFormats() {
        assertTrue(HookUrlMatcher.isStaticAsset("https://p1.music.126.net/cover.jpg"))
        assertTrue(HookUrlMatcher.isStaticAsset("https://p1.music.126.net/cover.jpg?param=300y300"))
        assertTrue(HookUrlMatcher.isStaticAsset("https://p2.music.126.net/avatar.png"))
        assertTrue(HookUrlMatcher.isStaticAsset("https://p2.music.126.net/banner.webp"))
        assertTrue(HookUrlMatcher.isStaticAsset("https://p3.music.126.net/loading.gif"))
        assertTrue(HookUrlMatcher.isStaticAsset("https://m701.music.126.net/playable/song.mp3"))
        assertTrue(HookUrlMatcher.isStaticAsset("https://m801.music.126.net/playable/song.flac"))
        assertFalse(HookUrlMatcher.isStaticAsset("https://interface.music.163.com/eapi/v3/playlist/detail"))
    }

    @Test
    fun testIsRelevantUrlFiltering() {
        // Standard API URLs -> true
        assertTrue(HookUrlMatcher.isRelevantUrl("https://interface.music.163.com/eapi/v3/playlist/detail"))
        assertTrue(HookUrlMatcher.isRelevantUrl("https://interface.music.163.com/eapi/song/enhance/player/url/v1"))
        assertTrue(HookUrlMatcher.isRelevantUrl("https://interface3.music.163.com/xeapi/playlist/v4/detail"))

        // Static URLs with embedded /api/ token -> false
        assertFalse(HookUrlMatcher.isRelevantUrl("https://p1.music.126.net/api/cover.jpg"))
        assertFalse(HookUrlMatcher.isRelevantUrl("https://m701.music.126.net/api/song.mp3"))

        // Unrelated domains / paths -> false
        assertFalse(HookUrlMatcher.isRelevantUrl("https://www.google.com/search?q=music"))
        assertFalse(HookUrlMatcher.isRelevantUrl("https://music.163.com/st/index.html"))
    }

    @Test
    fun testNullAndBlankSafety() {
        assertFalse(HookUrlMatcher.isEApiUrl(null))
        assertFalse(HookUrlMatcher.isEApiUrl(""))
        assertFalse(HookUrlMatcher.isEApiUrl("   "))

        assertFalse(HookUrlMatcher.isPlayerUrl(null))
        assertFalse(HookUrlMatcher.isPlayerUrl(""))
        assertFalse(HookUrlMatcher.isPlayerUrl("   "))

        assertFalse(HookUrlMatcher.isStaticAsset(null))
        assertFalse(HookUrlMatcher.isStaticAsset(""))
        assertFalse(HookUrlMatcher.isStaticAsset("   "))

        assertFalse(HookUrlMatcher.isRelevantUrl(null))
        assertFalse(HookUrlMatcher.isRelevantUrl(""))
        assertFalse(HookUrlMatcher.isRelevantUrl("   "))
    }
}
