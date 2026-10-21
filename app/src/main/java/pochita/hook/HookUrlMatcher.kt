package pochita.hook

/**
 * Pure URL route matching and classification engine for NetEase Cloud Music hooks.
 *
 * Responsibilities:
 * - Identifies EAPI, XEAPI, and legacy API endpoints.
 * - Identifies audio stream resolution endpoints (/player/url).
 * - Identifies and excludes static media CDN assets (covers, avatars, audio files).
 * - Completely pure and thread-safe: zero side effects, zero external dependencies.
 */
object HookUrlMatcher {

    private val STATIC_EXTENSIONS = hashSetOf(
        "jpg", "jpeg", "png", "webp", "gif", "bmp", "svg",
        "mp3", "flac", "m4a", "aac", "ogg", "wav", "wma",
        "mp4", "mkv", "webm",
        "woff", "woff2", "ttf", "css", "js"
    )

    /**
     * Determines whether [url] points to a static media or web asset.
     * Strips query parameters and fragments before evaluating the file extension.
     */
    fun isStaticAsset(url: String?): Boolean {
        if (url.isNullOrBlank()) return false
        val cleanPath = url.substringBefore('?').substringBefore('#')
        val dotIndex = cleanPath.lastIndexOf('.')
        if (dotIndex != -1 && dotIndex < cleanPath.length - 1) {
            val extension = cleanPath.substring(dotIndex + 1).lowercase()
            if (STATIC_EXTENSIONS.contains(extension)) {
                return true
            }
        }
        // Fallback for CDN URLs with embedded extension tokens before query
        val lower = url.lowercase()
        return lower.contains(".jpg") || lower.contains(".webp") ||
                lower.contains(".png") || lower.contains(".gif") ||
                lower.contains(".mp3") || lower.contains(".flac")
    }

    /**
     * Determines whether [url] is a NetEase EAPI, XEAPI, or standard API endpoint.
     */
    fun isEApiUrl(url: String?): Boolean {
        if (url.isNullOrBlank() || isStaticAsset(url)) return false
        val lower = url.lowercase()
        return lower.contains("/eapi/") ||
                lower.contains("/xeapi/") ||
                lower.contains("/api/")
    }

    /**
     * Determines whether [url] is an audio player stream resolution endpoint.
     */
    fun isPlayerUrl(url: String?): Boolean {
        if (url.isNullOrBlank() || isStaticAsset(url)) return false
        val lower = url.lowercase()
        return lower.contains("player/url") ||
                lower.contains("/song/enhance/player/url")
    }

    /**
     * Master gatekeeper: Determines whether [url] should be intercepted by UNM.
     * Returns true if and only if:
     * 1. URL is non-blank.
     * 2. URL is not a static asset.
     * 3. URL is either an EAPI/API endpoint or a Player URL endpoint.
     */
    fun isRelevantUrl(url: String?): Boolean {
        if (url.isNullOrBlank()) return false
        if (isStaticAsset(url)) return false
        return isEApiUrl(url) || isPlayerUrl(url)
    }
}
