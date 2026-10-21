package pochita.hook

import android.content.Context
import io.github.libxposed.api.XposedModule
import java.util.concurrent.atomic.AtomicBoolean
import org.json.JSONObject
import pochita.codec.ProtocolCodec
import pochita.common.UnmHttpClient
import pochita.common.UnmLogger
import pochita.common.UnmLruCache
import pochita.config.ConfigManager
import pochita.domain.transformer.SongRightsTransformer
import pochita.model.SongInfo
import pochita.resolver.PlayerUrlResolver
import pochita.rule.RuleManager

/**
 * NetEaseHook: Slim orchestrating facade for NetEase Cloud Music LSPosed hook.
 *
 * Architecture:
 * - Module lifecycle coordinator (Logger, RuleManager, CronetInterceptor, OkHttpInterceptor).
 * - Central thread-safe LRU cache manager for discovered song metadata ([songCache]).
 * - Processing pipeline orchestrator for EAPI/XEAPI/Player responses:
 *     1. URL relevance filtering via [HookUrlMatcher]
 *     2. Protocol decoding & decompression via [ProtocolCodec]
 *     3. Playback audio replacement via [PlayerUrlResolver]
 *     4. Rights unblocking & song metadata discovery via [SongRightsTransformer]
 *     5. Protocol re-encoding & re-compression via [ProtocolCodec]
 *
 * Guarantees:
 * - 100% backward compatibility for [MainHook] and test suites ([NetEaseHookTest]).
 * - Strict exception safety: all exceptions are logged via [UnmLogger] and fall back silently to raw bytes.
 */
object NetEaseHook {
    private const val TAG = "NetEaseHook"
    private const val CACHE_CAPACITY = 500

    private val initialized = AtomicBoolean(false)

    /**
     * High-performance, thread-safe LRU cache storing discovered song metadata.
     * Decoupled from Android SDK dependencies via [UnmLruCache].
     */
    val songCache: UnmLruCache<Long, SongInfo> = UnmLruCache(maxSize = CACHE_CAPACITY)

    /**
     * Player URL resolver instance delegating track replacement to [RuleManager].
     */
    @Volatile
    private var playerUrlResolver: PlayerUrlResolver = PlayerUrlResolver(
        songCache = songCache,
        audioMatcher = { song, config ->
            RuleManager.matchAudio(song, priority = config.enabledSources)
        },
        songDetailFetcher = { songId -> fetchSongInfoRemote(songId) },
        configProvider = { ConfigManager.getConfig() },
        onTrackReplaced = { event ->
            // Dispatched to ToastNotifier and PlayerUiHook (Milestone 2)
        }
    )

    /**
     * Initializes the UNM hook subsystem.
     *
     * Backward-compatible overload accepting [module], [classLoader], and optional [context].
     * Invoked by [MainHook] in the host process and by test harnesses.
     */
    @JvmStatic
    @JvmOverloads
    fun init(module: XposedModule, classLoader: ClassLoader, context: Context? = null) {
        if (!initialized.compareAndSet(false, true)) {
            UnmLogger.i(TAG, "NetEaseHook already initialized in this process")
            return
        }

        // 1. Configure UnmLogger with XposedAdapter for host logging
        try {
            UnmLogger.setAdapter(UnmLogger.XposedAdapter(module))
            UnmLogger.i(TAG, "UnmLogger initialized with XposedAdapter")
        } catch (t: Throwable) {
            UnmLogger.w(TAG, "Failed to attach XposedAdapter to UnmLogger: ${t.message}")
        }

        // 2. Initialize ConfigManager & RuleManager (dynamic rules from RemotePreferences or embedded assets)
        try {
            var customScript: String? = null
            try {
                val prefs = module.getRemotePreferences("config")
                ConfigManager.init(prefs)
                customScript = prefs.getString("cached_rule_script", null)
                UnmLogger.i(TAG, "ConfigManager attached to RemotePreferences")
            } catch (t: Throwable) {
                UnmLogger.w(TAG, "RemotePreferences not available: ${t.message}")
            }
            if (context != null) {
                RuleManager.init(context, customScript)
                UnmLogger.i(TAG, "RuleManager initialized")
            } else {
                UnmLogger.w(TAG, "Context is null, skipping RuleManager.init")
            }
        } catch (t: Throwable) {
            UnmLogger.e(TAG, "Failed to init RuleManager", t)
        }

        // 3. Delegate Cronet network stack hook
        try {
            CronetInterceptor.install(module, classLoader, ::processEApiResponse)
            UnmLogger.i(TAG, "CronetInterceptor installed successfully")
        } catch (t: Throwable) {
            UnmLogger.e(TAG, "Failed to install CronetInterceptor", t)
        }

        // 4. Delegate OkHttp network stack hook
        try {
            OkHttpInterceptor.install(module, classLoader, ::processEApiResponse)
            UnmLogger.i(TAG, "OkHttpInterceptor installed successfully")
        } catch (t: Throwable) {
            UnmLogger.e(TAG, "Failed to install OkHttpInterceptor", t)
        }
    }

    /**
     * Orchestrates response unblocking and audio replacement pipeline:
     * 1. URL relevance check via [HookUrlMatcher]
     * 2. Transparent GZIP decompression & AES-128 decryption via [ProtocolCodec]
     * 3. Audio replacement for /player/url via [PlayerUrlResolver]
     * 4. Recursive rights unblocking & song discovery via [SongRightsTransformer]
     * 5. Populating discovered songs into [songCache]
     * 6. Symmetrical re-encryption & re-compression via [ProtocolCodec]
     *
     * Guarantee: Returns null if unmodified, empty, or on any internal exception.
     */
    @JvmStatic
    fun processEApiResponse(url: String, rawBytes: ByteArray): ByteArray? {
        if (rawBytes.isEmpty()) return null
        if (!HookUrlMatcher.isRelevantUrl(url)) return null

        return try {
            // 1. Decode multi-layer protocol envelope (outer GZIP -> AES-128 decrypt -> inner GZIP)
            val decoded = ProtocolCodec.decodeResponse(url, rawBytes) ?: return null
            val originalJson = decoded.jsonString
            if (originalJson.isBlank()) return null

            var currentJson = originalJson
            var hasModified = false

            // 2. Resolve audio playback URLs if endpoint matches /player/url
            if (HookUrlMatcher.isPlayerUrl(url)) {
                val resolvedJson = playerUrlResolver.processPlayerUrl(url, currentJson)
                if (resolvedJson != null && resolvedJson != currentJson) {
                    currentJson = resolvedJson
                    hasModified = true
                }
            } else {
                // 3. Transform rights and unblock copyright restrictions for /eapi/ and /xeapi/
                val transformResult = SongRightsTransformer.transformResponseJson(currentJson)
                if (transformResult.hasModified) {
                    currentJson = transformResult.modifiedJson
                    hasModified = true
                }
                // 4. Ingest discovered song metadata into thread-safe LRU cache
                for (song in transformResult.discoveredSongs) {
                    songCache.put(song.id, song)
                }
            }

            // If no mutations took place, return null to preserve original bytes
            if (!hasModified) {
                return null
            }

            // 5. Re-encode multi-layer protocol envelope symmetrically
            val finalPayload = ProtocolCodec.encodeResponse(decoded, currentJson)
            UnmLogger.i(TAG, "Successfully processed $url (${rawBytes.size} -> ${finalPayload.size} bytes)")
            finalPayload
        } catch (t: Throwable) {
            // Strict exception isolation: log silently and fallback to unmodified stream
            UnmLogger.e(TAG, "Error processing response for $url: ${t.message}", t)
            null
        }
    }

    /**
     * Fallback remote song detail resolver using unified [UnmHttpClient].
     * Query: https://music.163.com/api/song/detail?ids=[$songId]
     */
    private fun fetchSongInfoRemote(songId: Long): SongInfo? {
        return try {
            val url = "https://music.163.com/api/song/detail?ids=[$songId]"
            val response = UnmHttpClient.get(url, timeoutMs = 5000)
            if (!response.isSuccessful) {
                UnmLogger.w(TAG, "Remote song detail fetch failed with HTTP ${response.code} for $songId")
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

            val info = SongInfo(id = songId, name = name, artist = artistName, durationMs = duration)
            songCache.put(songId, info)
            info
        } catch (t: Throwable) {
            UnmLogger.w(TAG, "Failed to fetch remote song info for $songId: ${t.message}")
            null
        }
    }

    /**
     * Testing seam: Allows injecting custom [PlayerUrlResolver] for isolated test scenarios.
     */
    @JvmStatic
    fun setPlayerUrlResolverForTesting(resolver: PlayerUrlResolver) {
        playerUrlResolver = resolver
    }

    /**
     * Testing seam: Resets cache and state between test executions.
     */
    @JvmStatic
    fun resetForTesting() {
        songCache.clear()
        initialized.set(false)
        ConfigManager.resetForTesting()
        playerUrlResolver = PlayerUrlResolver(
            songCache = songCache,
            audioMatcher = { song, config -> RuleManager.matchAudio(song, priority = config.enabledSources) },
            songDetailFetcher = { songId -> fetchSongInfoRemote(songId) },
            configProvider = { ConfigManager.getConfig() }
        )
    }
}
