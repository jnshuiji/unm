package pochita.config

import pochita.data.PreferenceKeys

/**
 * Immutable configuration snapshot for NetEase UNM module.
 *
 * @property enabledSources Ordered list of sound sources to query during resolution.
 *                          Sources earlier in the list take higher priority.
 *                          Default: listOf("bodian", "kuwo", "migu", "kugou").
 * @property preferredQuality Target audio bitrate preference: "320k" (default), "flac", "192k", "128k".
 * @property enableToast Whether to display an instant debounced Toast upon track replacement (default: true).
 * @property enablePlayerBadge Whether to inject and display a dynamic badge in NetEase PlayerActivity (default: true).
 * @property enableAntiRoll Whether to penalize non-studio (Live/Radio/Cover/DJ) candidate tracks (default: true).
 */
data class UnmConfig(
    val enabledSources: List<String> = PreferenceKeys.DEFAULT_ENABLED_SOURCES,
    val preferredQuality: String = PreferenceKeys.DEFAULT_PREFERRED_QUALITY,
    val enableToast: Boolean = PreferenceKeys.DEFAULT_ENABLE_TOAST,
    val enablePlayerBadge: Boolean = PreferenceKeys.DEFAULT_ENABLE_PLAYER_BADGE,
    val enableAntiRoll: Boolean = PreferenceKeys.DEFAULT_ENABLE_ANTI_ROLL,
) {
    companion object {
        val ALL_AVAILABLE_SOURCES: List<String> = PreferenceKeys.ALL_KNOWN_SOURCES
        val DEFAULT_ENABLED_SOURCES: List<String> = PreferenceKeys.DEFAULT_ENABLED_SOURCES
        const val DEFAULT_PREFERRED_QUALITY: String = PreferenceKeys.DEFAULT_PREFERRED_QUALITY
        const val DEFAULT_ENABLE_TOAST: Boolean = PreferenceKeys.DEFAULT_ENABLE_TOAST
        const val DEFAULT_ENABLE_PLAYER_BADGE: Boolean = PreferenceKeys.DEFAULT_ENABLE_PLAYER_BADGE
        const val DEFAULT_ENABLE_ANTI_ROLL: Boolean = PreferenceKeys.DEFAULT_ENABLE_ANTI_ROLL
        val SUPPORTED_QUALITIES: List<String> = PreferenceKeys.SUPPORTED_QUALITIES
    }

    /**
     * Resolves target bitrate in bps corresponding to [preferredQuality].
     * "flac" -> 999000 bps
     * "320k" -> 320000 bps
     * "192k" -> 192000 bps
     * "128k" -> 128000 bps
     */
    fun targetBitrate(): Int = when (preferredQuality.lowercase()) {
        "flac" -> 999000
        "192k" -> 192000
        "128k" -> 128000
        else -> 320000
    }

    /**
     * NetEase audio level code corresponding to target bitrate.
     * >= 900000 -> "lossless"
     * >= 300000 -> "exhigh"
     * >= 150000 -> "higher"
     * else -> "standard"
     */
    fun targetLevel(): String = when {
        targetBitrate() >= 900000 -> "lossless"
        targetBitrate() >= 300000 -> "exhigh"
        targetBitrate() >= 150000 -> "higher"
        else -> "standard"
    }

    /**
     * NetEase audio container type format corresponding to target bitrate.
     * "flac" when preferredQuality is "flac", otherwise "mp3".
     */
    fun targetFormat(): String = if (preferredQuality.equals("flac", ignoreCase = true)) "flac" else "mp3"
}
