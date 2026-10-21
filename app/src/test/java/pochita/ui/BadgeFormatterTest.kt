package pochita.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import pochita.model.ReplaceEvent

class BadgeFormatterTest {

    private fun sampleEvent(
        songId: Long = 123456L,
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

    @Test
    fun testFormatSourceDisplayName() {
        assertEquals("酷我音乐", BadgeFormatter.formatSourceDisplayName("kuwo"))
        assertEquals("波点音乐", BadgeFormatter.formatSourceDisplayName("bodian"))
        assertEquals("咪咕音乐", BadgeFormatter.formatSourceDisplayName("migu"))
        assertEquals("酷狗音乐", BadgeFormatter.formatSourceDisplayName("kugou"))
        assertEquals("网易云音乐", BadgeFormatter.formatSourceDisplayName("netease"))
        assertEquals("网易云音乐", BadgeFormatter.formatSourceDisplayName("cloudmusic"))
        assertEquals("JOOX", BadgeFormatter.formatSourceDisplayName("joox"))
        assertEquals("PyNCM", BadgeFormatter.formatSourceDisplayName("pyncm"))
        assertEquals("PyNCM", BadgeFormatter.formatSourceDisplayName("pyncmd"))
        assertEquals("自定义JS", BadgeFormatter.formatSourceDisplayName("customjs"))
        assertEquals("自定义JS", BadgeFormatter.formatSourceDisplayName("custom_js"))
        assertEquals("自定义JS", BadgeFormatter.formatSourceDisplayName("js"))
        assertEquals("未知音源", BadgeFormatter.formatSourceDisplayName(""))
        assertEquals("Spotify", BadgeFormatter.formatSourceDisplayName("spotify"))
    }

    @Test
    fun testFormatShortSource() {
        assertEquals("酷我", BadgeFormatter.formatShortSource("kuwo"))
        assertEquals("波点", BadgeFormatter.formatShortSource("bodian"))
        assertEquals("咪咕", BadgeFormatter.formatShortSource("migu"))
        assertEquals("酷狗", BadgeFormatter.formatShortSource("kugou"))
        assertEquals("网易", BadgeFormatter.formatShortSource("netease"))
        assertEquals("JOOX", BadgeFormatter.formatShortSource("joox"))
        assertEquals("PyNCM", BadgeFormatter.formatShortSource("pyncm"))
        assertEquals("JS", BadgeFormatter.formatShortSource("customjs"))
        assertEquals("JS", BadgeFormatter.formatShortSource("custom_js"))
        assertEquals("未知", BadgeFormatter.formatShortSource(""))
        assertEquals("spot", BadgeFormatter.formatShortSource("spotify"))
    }

    @Test
    fun testFormatBitrate() {
        assertEquals("FLAC", BadgeFormatter.formatBitrate(999000))
        assertEquals("FLAC", BadgeFormatter.formatBitrate(900000))
        assertEquals("320K", BadgeFormatter.formatBitrate(320000))
        assertEquals("192K", BadgeFormatter.formatBitrate(192000))
        assertEquals("128K", BadgeFormatter.formatBitrate(128000))
        assertEquals("标准", BadgeFormatter.formatBitrate(0))
        assertEquals("标准", BadgeFormatter.formatBitrate(-1))
    }

    @Test
    fun testResolveFormat() {
        assertEquals("FLAC", BadgeFormatter.resolveFormat(999000))
        assertEquals("FLAC", BadgeFormatter.resolveFormat(900000))
        assertEquals("MP3", BadgeFormatter.resolveFormat(320000))
        assertEquals("MP3", BadgeFormatter.resolveFormat(192000))
        assertEquals("MP3", BadgeFormatter.resolveFormat(0))
    }

    @Test
    fun testFormatBadgeTextSameTitle() {
        val event = sampleEvent(originalTitle = "晴天", matchedTitle = "晴天", matchedSource = "kuwo", br = 320000)
        assertEquals("[酷我·320K]", BadgeFormatter.formatBadgeText(event))
    }

    @Test
    fun testFormatBadgeTextDifferentTitle() {
        val event = sampleEvent(originalTitle = "晴天", matchedTitle = "晴天 (Live)", matchedSource = "kuwo", br = 320000)
        assertEquals("[酷我·320K] 晴天 (Live)", BadgeFormatter.formatBadgeText(event))
    }

    @Test
    fun testFormatBadgeTextCaseInsensitiveSameTitle() {
        val event = sampleEvent(originalTitle = "TiK ToK", matchedTitle = "tik tok", matchedSource = "bodian", br = 192000)
        assertEquals("[波点·192K]", BadgeFormatter.formatBadgeText(event))
    }

    @Test
    fun testFormatClipboardText() {
        val event = sampleEvent(
            songId = 98765L,
            originalTitle = "夜曲",
            originalArtist = "周杰伦",
            matchedSource = "migu",
            matchedTitle = "夜曲 (2005)",
            matchedArtist = "周杰伦",
            br = 999000
        )
        val text = BadgeFormatter.formatClipboardText(event)

        assertTrue(text.contains("【云村换源详情】"))
        assertTrue(text.contains("原曲: 《夜曲》 - 周杰伦"))
        assertTrue(text.contains("换源: 《夜曲 (2005)》 - 周杰伦"))
        assertTrue(text.contains("平台: 咪咕音乐"))
        assertTrue(text.contains("规格: 999 kbps (FLAC)"))
        assertTrue(text.contains("歌曲ID: 98765"))
    }

    @Test
    fun testFormatDialogMessage() {
        val event = sampleEvent(
            originalTitle = "七里香",
            originalArtist = "周杰伦",
            matchedSource = "kuwo",
            matchedTitle = "七里香",
            matchedArtist = "周杰伦",
            br = 320000
        )
        val message = BadgeFormatter.formatDialogMessage(event)

        assertTrue(message.contains("原曲: 《七里香》 - 周杰伦"))
        assertTrue(message.contains("换源: 《七里香》 - 周杰伦"))
        assertTrue(message.contains("平台: 酷我音乐"))
        assertTrue(message.contains("音质: 320K (320 kbps)"))
    }
}
