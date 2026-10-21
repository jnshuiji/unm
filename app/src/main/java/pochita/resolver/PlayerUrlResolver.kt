package pochita.resolver

import org.json.JSONObject
import org.json.JSONTokener
import pochita.common.UnmHttpClient
import pochita.common.UnmLogger
import pochita.common.UnmLruCache
import pochita.config.ConfigManager
import pochita.config.UnmConfig
import pochita.model.MatchedAudio
import pochita.model.ReplaceEvent
import pochita.model.SongInfo

/**
 * Player URL interceptor and sound source resolver.
 *
 * Responsibilities:
 * - Detects audio player URL endpoints (/player/url and /song/enhance/player/url).
 * - Preserves already valid, playable, non-trial audio without modifying or proxying it.
 * - Identifies restricted, unplayable, zero-bitrate, or trial audio tracks.
 * - Resolves metadata from [songCache] or fallback [songDetailFetcher].
 * - Queries [audioMatcher] to find replacement audio streams from alternative sources based on [configProvider].
 * - Rewrites audio metadata (url, br, code, size, md5) according to target quality preference and strips all trial restrictions.
 * - Dispatches [ReplaceEvent] upon successful replacement to feedback listeners.
 */
class PlayerUrlResolver(
    private val songCache: UnmLruCache<Long, SongInfo>,
    private val audioMatcher: (SongInfo, UnmConfig) -> MatchedAudio?,
    private val songDetailFetcher: ((Long) -> SongInfo?)? = { fetchSongDetail(it) },
    private val configProvider: () -> UnmConfig = { ConfigManager.getConfig() },
    private val onTrackReplaced: ((ReplaceEvent) -> Unit)? = null
) {
    /**
     * Secondary constructor for backward compatibility with 3-argument callers.
     */
    constructor(
        songCache: UnmLruCache<Long, SongInfo>,
        audioMatcher: (SongInfo) -> MatchedAudio?,
        songDetailFetcher: ((Long) -> SongInfo?)? = { fetchSongDetail(it) }
    ) : this(
        songCache = songCache,
        audioMatcher = { song, _ -> audioMatcher(song) },
        songDetailFetcher = songDetailFetcher,
        configProvider = { ConfigManager.getConfig() },
        onTrackReplaced = null
    )

    /**
     * Secondary constructor allowing custom config provider and 1-argument audioMatcher.
     */
    constructor(
        songCache: UnmLruCache<Long, SongInfo>,
        audioMatcher: (SongInfo) -> MatchedAudio?,
        songDetailFetcher: ((Long) -> SongInfo?)? = { fetchSongDetail(it) },
        configProvider: () -> UnmConfig,
        onTrackReplaced: ((ReplaceEvent) -> Unit)? = null
    ) : this(
        songCache = songCache,
        audioMatcher = { song, _ -> audioMatcher(song) },
        songDetailFetcher = songDetailFetcher,
        configProvider = configProvider,
        onTrackReplaced = onTrackReplaced
    )

    companion object {
        private const val TAG = "PlayerUrlResolver"

        /**
         * Fallback detail fetcher using [UnmHttpClient].
         */
        fun fetchSongDetail(songId: Long): SongInfo? {
            return try {
                val url = "https://music.163.com/api/song/detail?ids=[$songId]"
                val response = UnmHttpClient.get(url, timeoutMs = 5000)
                if (!response.isSuccessful) {
                    UnmLogger.w(TAG, "Song detail HTTP failure for $songId: code=${response.code}")
                    return null
                }
                val json = JSONObject(response.bodyString)
                val songs = json.optJSONArray("songs") ?: return null
                if (songs.length() == 0) return null
                val songObj = songs.getJSONObject(0)
                val name = songObj.optString("name", "")
                val duration = songObj.optLong("duration", songObj.optLong("dt", 0L))
                val artists = songObj.optJSONArray("artists") ?: songObj.optJSONArray("ar")
                val artistName = if (artists != null && artists.length() > 0) {
                    val first = artists.optJSONObject(0)
                    first?.optString("name", "")?.takeIf { it.isNotEmpty() }
                        ?: artists.optString(0, "")
                } else {
                    songObj.optString("artist", "")
                }
                if (name.isEmpty()) return null
                SongInfo(id = songId, name = name, artist = artistName, durationMs = duration)
            } catch (t: Throwable) {
                UnmLogger.w(TAG, "Exception fetching song detail for $songId: ${t.message}", t)
                null
            }
        }
    }

    /**
     * Determines whether [url] is a player audio resolution endpoint.
     */
    fun isPlayerUrl(url: String): Boolean {
        val isTarget = url.contains("player/url")
        val isStatic = url.contains(".jpg") || url.contains(".webp") ||
                url.contains(".png") || url.contains(".gif")
        return isTarget && !isStatic
    }

    /**
     * Inspects and rewrites player URL response JSON.
     *
     * @param url The intercepted request URL.
     * @param responseJson The decrypted/decompressed JSON string.
     * @return The updated JSON string if any tracks were replaced, or null if unmodified.
     */
    fun processPlayerUrl(url: String, responseJson: String): String? {
        if (!isPlayerUrl(url) || responseJson.isBlank()) {
            return null
        }

        val config = configProvider()
        if (config.enabledSources.isEmpty()) {
            UnmLogger.w(TAG, "All sound sources are disabled in configuration, skipping matching.")
            return null
        }

        return try {
            val root = JSONTokener(responseJson).nextValue()
            if (root !is JSONObject) return null

            val dataArray = root.optJSONArray("data") ?: return null
            var modified = false

            for (i in 0 until dataArray.length()) {
                val item = dataArray.optJSONObject(i) ?: continue
                val songId = item.optLong("id", 0L)
                if (songId <= 0) continue

                val currentUrl = item.optString("url", "")
                val hasValidUrl = currentUrl.isNotBlank() && currentUrl != "null" &&
                        (currentUrl.startsWith("http://") || currentUrl.startsWith("https://"))
                val br = item.optInt("br", 0)
                val code = item.optInt("code", 200)
                val hasTrial = (item.has("freeTrialInfo") && !item.isNull("freeTrialInfo")) ||
                        (item.has("trialMode") && item.optInt("trialMode", 0) != 0) ||
                        (item.has("freeTrialPrivilege") && !item.isNull("freeTrialPrivilege")) ||
                        (item.has("freeTimeTrialPrivilege") && !item.isNull("freeTimeTrialPrivilege"))

                val needUnblock = !hasValidUrl || br <= 0 || code != 200 || hasTrial
                if (!needUnblock) {
                    // Valid playable track, strictly preserve without modification
                    continue
                }

                UnmLogger.d(TAG, "Restricted track detected: id=$songId (hasValidUrl=$hasValidUrl, br=$br, code=$code, hasTrial=$hasTrial)")

                // Resolve metadata: Cache first, fallback to fetcher
                val songInfo = songCache.get(songId) ?: run {
                    val fetched = songDetailFetcher?.invoke(songId)
                    if (fetched != null) {
                        songCache.put(songId, fetched)
                    }
                    fetched
                }

                if (songInfo == null) {
                    UnmLogger.w(TAG, "Could not resolve SongInfo for restricted songId=$songId")
                    continue
                }

                val matched = audioMatcher(songInfo, config)
                if (matched != null && matched.url.isNotBlank()) {
                    val targetBr = config.targetBitrate()
                    val actualBr = if (config.preferredQuality == "320k" && matched.br > 0) matched.br else targetBr
                    val level = when {
                        actualBr >= 900000 -> "lossless"
                        actualBr >= 300000 -> "exhigh"
                        actualBr >= 150000 -> "higher"
                        else -> "standard"
                    }
                    val type = when {
                        matched.url.contains(".flac", ignoreCase = true) || actualBr >= 900000 -> "flac"
                        else -> "mp3"
                    }

                    item.put("url", matched.url)
                    item.put("br", actualBr)
                    item.put("code", 200)
                    item.put("fee", 0)
                    item.put("payed", 1)
                    item.put("level", level)
                    item.put("type", type)
                    if (matched.size > 0) item.put("size", matched.size)
                    if (matched.md5.isNotEmpty()) item.put("md5", matched.md5)

                    item.remove("freeTrialInfo")
                    item.remove("freeTrialPrivilege")
                    item.remove("freeTimeTrialPrivilege")
                    if (item.has("urlTrial")) item.remove("urlTrial")
                    item.put("trialMode", 0)

                    modified = true
                    UnmLogger.i(TAG, "Replaced audio for song $songId (${songInfo.name} - ${songInfo.artist}) via ${matched.source} at ${actualBr}bps ($level)")

                    if (onTrackReplaced != null && (config.enableToast || config.enablePlayerBadge)) {
                        val event = ReplaceEvent(
                            songId = songId,
                            originalTitle = songInfo.name,
                            originalArtist = songInfo.artist,
                            matchedSource = matched.source,
                            matchedTitle = matched.matchedTitle.ifBlank { songInfo.name },
                            matchedArtist = matched.matchedArtist.ifBlank { songInfo.artist },
                            br = actualBr
                        )
                        onTrackReplaced.invoke(event)
                    }
                } else {
                    UnmLogger.w(TAG, "Audio matcher returned no result for song $songId (${songInfo.name})")
                }
            }

            if (modified) root.toString() else null
        } catch (t: Throwable) {
            UnmLogger.w(TAG, "Error in processPlayerUrl for $url: ${t.message}", t)
            null
        }
    }
}
