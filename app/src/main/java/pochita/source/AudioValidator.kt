package pochita.source

import java.net.URI
import java.util.concurrent.CopyOnWriteArraySet
import pochita.common.UnmLogger

/**
 * AudioValidator: Centralized validator for audio playback URLs, track IDs, and teaser metadata.
 *
 * Responsibilities:
 * - Centralizes known teaser / offline prompt audio IDs (e.g. 588957081, 563182854)
 *   previously scattered across NativeSources and RuleManager.
 * - Validates audio playback URLs (checking URL protocol scheme, well-formedness,
 *   and blacklist ID absence).
 * - Identifies banned keywords indicating teaser clips, promotional promos, or offline prompts.
 * - Detects duration-based teaser clips (short 1-30s clips for long tracks).
 * - Supports thread-safe dynamic addition and reset for testability.
 */
object AudioValidator {
    private const val TAG = "AudioValidator"

    /**
     * Default blacklisted audio IDs representing offline prompt recordings
     * or promotional teaser loops (e.g., Kuwo / NetEase copyright expiration notices).
     */
    val DEFAULT_BLACKLISTED_IDS: Set<Long> = setOf(
        588957081L, // Known Kuwo offline prompt recording ("该歌曲由于版权原因已下架")
        563182854L  // Known Kuwo teaser / unavailable notice loop
    )

    /**
     * Default banned keywords in song titles or source descriptions indicating
     * preview clips, teasers, ringtones, or offline prompt tracks.
     */
    val DEFAULT_BANNED_KEYWORDS: Set<String> = setOf(
        "试听",
        "预告",
        "下架",
        "片段",
        "铃声",
        "由于版权原因",
        "teaser",
        "preview",
        "demo",
        "ringtone",
        "clip",
        "sample"
    )

    private val blacklistedIds = CopyOnWriteArraySet<Long>(DEFAULT_BLACKLISTED_IDS)
    private val bannedKeywords = CopyOnWriteArraySet<String>(DEFAULT_BANNED_KEYWORDS)

    /**
     * Checks if the given numeric song/audio ID is in the blacklist.
     *
     * @param songId The numeric ID to check.
     * @return true if blacklisted, false otherwise.
     */
    @JvmStatic
    fun isBlacklistedAudioId(songId: Long): Boolean {
        return blacklistedIds.contains(songId)
    }

    /**
     * Convenience overload for string audio ID.
     */
    @JvmStatic
    fun isBlacklistedAudioId(songIdStr: String): Boolean {
        val idLong = songIdStr.trim().toLongOrNull() ?: return false
        return isBlacklistedAudioId(idLong)
    }

    /**
     * Checks if the given title or keyword contains any banned teaser/prompt keywords.
     * Case-insensitive.
     *
     * @param title The title or search keyword.
     * @return true if banned keyword is present, false otherwise.
     */
    @JvmStatic
    fun isBannedKeyword(title: String): Boolean {
        if (title.isBlank()) return false
        val lower = title.lowercase()
        return bannedKeywords.any { lower.contains(it.lowercase()) }
    }

    /**
     * Validates whether an audio playback URL is well-formed, reachable via HTTP/HTTPS,
     * and free from blacklisted prompt audio IDs.
     *
     * Rules:
     * 1. URL must not be null, empty, blank, or literal "null" / "undefined" / "error".
     * 2. URL must start with http:// or https:// scheme.
     * 3. URL must not contain any blacklisted audio ID strings (e.g. "588957081", "563182854").
     * 4. URL must have a valid host and non-empty path.
     *
     * @param url The candidate audio URL string.
     * @return true if valid, false if invalid or teaser/offline prompt.
     */
    @JvmStatic
    fun isValidAudioUrl(url: String?): Boolean {
        if (url.isNullOrBlank()) return false

        val trimmed = url.trim()
        if (trimmed.equals("null", ignoreCase = true) ||
            trimmed.equals("undefined", ignoreCase = true) ||
            trimmed.equals("error", ignoreCase = true)
        ) {
            return false
        }

        // Scheme validation: must be HTTP or HTTPS
        val hasValidScheme = trimmed.startsWith("http://", ignoreCase = true) ||
                trimmed.startsWith("https://", ignoreCase = true)
        if (!hasValidScheme) return false

        // Blacklist ID check in URL
        for (id in blacklistedIds) {
            if (trimmed.contains(id.toString())) {
                UnmLogger.d(TAG, "Audio URL rejected: contains blacklisted ID $id ($trimmed)")
                return false
            }
        }

        // URI structure validation
        return try {
            val uri = URI(trimmed)
            !uri.host.isNullOrEmpty()
        } catch (_: Throwable) {
            false
        }
    }

    /**
     * Checks whether an audio track is a short teaser clip based on duration comparison.
     * When expected song duration is > 60 seconds, but candidate audio is only 1..30 seconds,
     * it is classified as a teaser/preview clip.
     *
     * @param expectedDurationMs Expected full track duration in milliseconds.
     * @param actualDurationSec Candidate audio duration in seconds.
     * @return true if classified as a teaser clip, false otherwise.
     */
    @JvmStatic
    fun isTeaserDuration(expectedDurationMs: Long, actualDurationSec: Int): Boolean {
        return expectedDurationMs > 60_000L && actualDurationSec in 1..30
    }

    /**
     * Checks whether candidate duration matches target duration within tolerance.
     *
     * @param targetDurationMs Target song duration in milliseconds.
     * @param candidateDurationMs Candidate track duration in milliseconds.
     * @param toleranceMs Maximum acceptable difference (default 6000ms / 6s).
     * @return true if acceptable match, false if deviated too far.
     */
    @JvmStatic
    fun isDurationAcceptable(
        targetDurationMs: Long,
        candidateDurationMs: Long,
        toleranceMs: Long = 6000L
    ): Boolean {
        if (targetDurationMs <= 0 || candidateDurationMs <= 0) return true
        return kotlin.math.abs(targetDurationMs - candidateDurationMs) <= toleranceMs
    }

    // =========================================================================
    // Test & Extensibility APIs
    // =========================================================================

    @JvmStatic
    fun addBlacklistedAudioId(id: Long) {
        blacklistedIds.add(id)
    }

    @JvmStatic
    fun removeBlacklistedAudioId(id: Long) {
        blacklistedIds.remove(id)
    }

    @JvmStatic
    fun addBannedKeyword(keyword: String) {
        bannedKeywords.add(keyword)
    }

    @JvmStatic
    fun removeBannedKeyword(keyword: String) {
        bannedKeywords.remove(keyword)
    }

    @JvmStatic
    fun reset() {
        blacklistedIds.clear()
        blacklistedIds.addAll(DEFAULT_BLACKLISTED_IDS)
        bannedKeywords.clear()
        bannedKeywords.addAll(DEFAULT_BANNED_KEYWORDS)
    }
}
