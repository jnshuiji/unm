package pochita.ui

import pochita.model.ReplaceEvent
import java.util.Locale

/**
 * Pure Kotlin formatting utilities for UNM Player Badge and Detail Dialog.
 *
 * 100% unit-testable in JVM without Android SDK dependencies.
 */
object BadgeFormatter {

    /**
     * Maps internal source identifier to human-readable Chinese display name.
     */
    fun formatSourceDisplayName(source: String): String = when (source.lowercase().trim()) {
        "kuwo" -> "酷我音乐"
        "bodian" -> "波点音乐"
        "migu" -> "咪咕音乐"
        "kugou" -> "酷狗音乐"
        "netease", "cloudmusic" -> "网易云音乐"
        "joox" -> "JOOX"
        "pyncmd", "pyncm" -> "PyNCM"
        "customjs", "js", "custom_js" -> "自定义JS"
        "" -> "未知音源"
        else -> source.trim().replaceFirstChar {
            if (it.isLowerCase()) it.titlecase(Locale.ROOT) else it.toString()
        }
    }

    /**
     * Short source name for compact badge display.
     */
    fun formatShortSource(source: String): String = when (source.lowercase().trim()) {
        "kuwo" -> "酷我"
        "bodian" -> "波点"
        "migu" -> "咪咕"
        "kugou" -> "酷狗"
        "netease", "cloudmusic" -> "网易"
        "joox" -> "JOOX"
        "pyncmd", "pyncm" -> "PyNCM"
        "customjs", "js", "custom_js" -> "JS"
        "" -> "未知"
        else -> source.trim().take(4)
    }

    /**
     * Formats audio bitrate into compact quality label.
     */
    fun formatBitrate(br: Int): String = when {
        br >= 900000 -> "FLAC"
        br >= 300000 -> "320K"
        br >= 150000 -> "192K"
        br > 0 -> "${br / 1000}K"
        else -> "标准"
    }

    /**
     * Resolves audio container format string from bitrate.
     */
    fun resolveFormat(br: Int): String = if (br >= 900000) "FLAC" else "MP3"

    /**
     * Formats compact pill badge text strictly containing source and bitrate (e.g. "[波点·320K]").
     * Used exclusively by PlayerBadgeManager for on-screen status pills to prevent layout overflow.
     */
    fun formatPillBadgeText(event: ReplaceEvent): String {
        val shortSource = formatShortSource(event.matchedSource)
        val quality = formatBitrate(event.br)
        return "[$shortSource·$quality]"
    }

    /**
     * Formats badge text (e.g. "[酷我·320K]" or "[酷我·320K] 晴天 (Live)").
     */
    fun formatBadgeText(event: ReplaceEvent, includeMatchedTitle: Boolean = true): String {
        val shortSource = formatShortSource(event.matchedSource)
        val quality = formatBitrate(event.br)
        val prefix = "[$shortSource·$quality]"

        if (!includeMatchedTitle) {
            return prefix
        }

        val matchedTitle = event.matchedTitle.trim()
        val originalTitle = event.originalTitle.trim()

        return if (matchedTitle.isNotBlank() && !matchedTitle.equals(originalTitle, ignoreCase = true)) {
            "$prefix $matchedTitle"
        } else {
            prefix
        }
    }

    /**
     * Compiles detailed replacement summary for clipboard copy.
     */
    fun formatClipboardText(event: ReplaceEvent): String = buildString {
        appendLine("【云村换源详情】")
        appendLine("原曲: 《${event.originalTitle}》 - ${event.originalArtist}")
        appendLine("换源: 《${event.matchedTitle}》 - ${event.matchedArtist}")
        appendLine("平台: ${formatSourceDisplayName(event.matchedSource)}")
        appendLine("规格: ${event.br / 1000} kbps (${resolveFormat(event.br)})")
        append("歌曲ID: ${event.songId}")
    }

    /**
     * Compiles user-facing multi-line dialog message.
     */
    fun formatDialogMessage(event: ReplaceEvent): String = buildString {
        append("原曲: 《").append(event.originalTitle).append("》 - ").append(event.originalArtist).append("\n")
        append("换源: 《").append(event.matchedTitle).append("》 - ").append(event.matchedArtist).append("\n")
        append("平台: ").append(formatSourceDisplayName(event.matchedSource)).append("\n")
        append("音质: ").append(formatBitrate(event.br)).append(" (").append(event.br / 1000).append(" kbps)")
    }
}
