package pochita.ui

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import pochita.common.UnmLogger
import pochita.config.ConfigManager
import pochita.event.ReplaceEventBus
import pochita.model.ReplaceEvent
import java.util.Locale

/**
 * Main-thread debounced Toast notification coordinator for audio replacement.
 *
 * Features:
 * - Checks [ConfigManager.getConfig().enableToast] toggle before displaying.
 * - 3-second (3000ms) anti-flood cooldown window preventing rapid successive toasts.
 * - Supports [currentSongFilter] to prevent background prefetch songs from triggering ghost toasts.
 * - Formats message: "🎵 云村换源: 已替换为 [酷我]《具体曲名》· 320k".
 * - Dispatched to Android main thread via [Handler(Looper.getMainLooper())].
 * - Full test seam support for local JVM testing.
 */
object ToastNotifier {
    private const val TAG = "ToastNotifier"
    const val DEBOUNCE_WINDOW_MS = 3000L

    @Volatile
    private var appContext: Context? = null

    @Volatile
    private var lastToastTimestamp: Long = 0L

    /**
     * Optional filter predicate for automatic event bus subscriptions.
     * When set, only events for the actively playing song produce a Toast.
     */
    @Volatile
    internal var currentSongFilter: ((ReplaceEvent) -> Boolean)? = null

    // Testing and execution seams
    internal var timeProvider: () -> Long = {
        try {
            android.os.SystemClock.uptimeMillis().takeIf { it > 0 } ?: System.currentTimeMillis()
        } catch (_: Throwable) {
            System.currentTimeMillis()
        }
    }

    internal var mainThreadDispatcher: ((() -> Unit) -> Unit) = { action ->
        try {
            val looper = Looper.getMainLooper()
            if (looper != null) {
                Handler(looper).post(action)
            } else {
                action()
            }
        } catch (t: Throwable) {
            try {
                action()
            } catch (ignored: Throwable) {
                UnmLogger.w(TAG, "Action failed in mainThreadDispatcher fallback: ${ignored.message}")
            }
        }
    }

    internal var toastShower: (Context, CharSequence, Int) -> Unit = { ctx, text, duration ->
        Toast.makeText(ctx, text, duration).show()
    }

    /**
     * Initializes ToastNotifier with host application context and auto-attaches to [ReplaceEventBus].
     */
    fun init(context: Context) {
        this.appContext = context.applicationContext ?: context
        ReplaceEventBus.subscribe { event ->
            val filter = currentSongFilter
            if (filter == null || filter(event)) {
                this.appContext?.let { ctx ->
                    notify(ctx, event)
                }
            } else {
                UnmLogger.d(TAG, "Toast suppressed by currentSongFilter: songId=${event.songId}, title=${event.originalTitle}")
            }
        }
        UnmLogger.i(TAG, "ToastNotifier initialized and subscribed to ReplaceEventBus")
    }

    /**
     * Triggers a debounced Toast notification for [event].
     *
     * @param context Android context for Toast creation.
     * @param event The replacement event to display.
     * @return True if Toast was scheduled, false if debounced, disabled, or suppressed.
     */
    fun notify(context: Context, event: ReplaceEvent): Boolean {
        // 1. Config toggle guard
        val config = ConfigManager.getConfig()
        if (!config.enableToast) {
            UnmLogger.d(TAG, "Toast notification suppressed: enableToast is false")
            return false
        }

        // 2. Debounce cooldown guard
        val now = timeProvider()
        synchronized(this) {
            if (now - lastToastTimestamp in 0 until DEBOUNCE_WINDOW_MS) {
                UnmLogger.d(TAG, "Toast debounced (within ${DEBOUNCE_WINDOW_MS}ms): songId=${event.songId}")
                return false
            }
            lastToastTimestamp = now
        }

        // 3. Format message
        val message = formatMessage(event)

        // 4. Dispatch to main thread
        mainThreadDispatcher {
            try {
                toastShower(context, message, Toast.LENGTH_SHORT)
                UnmLogger.i(TAG, "Toast displayed: $message")
            } catch (t: Throwable) {
                UnmLogger.w(TAG, "Failed to display Toast: ${t.message}", t)
            }
        }
        return true
    }

    /**
     * Overload using cached [appContext].
     */
    fun notify(event: ReplaceEvent): Boolean {
        val ctx = appContext ?: run {
            UnmLogger.w(TAG, "ToastNotifier.notify called before init(context); skipping toast")
            return false
        }
        return notify(ctx, event)
    }

    /**
     * Formats the replacement toast text:
     * Format: 🎵 云村换源: 已替换为 [酷我]《具体曲名》· 320k
     */
    fun formatMessage(event: ReplaceEvent): String {
        val source = formatSourceName(event.matchedSource)
        val title = event.matchedTitle.trim().ifBlank { event.originalTitle.trim() }
        val bitrate = formatBitrate(event.br)
        return "🎵 云村换源: 已替换为 [$source]《$title》· $bitrate"
    }

    /**
     * Maps internal source identifiers to localized display names.
     */
    fun formatSourceName(rawSource: String): String {
        return when (rawSource.trim().lowercase()) {
            "kuwo" -> "酷我"
            "bodian" -> "波点"
            "migu" -> "咪咕"
            "kugou" -> "酷狗"
            "pyncm", "netease", "cloudmusic" -> "网易云"
            "joox" -> "JOOX"
            "" -> "未知音源"
            else -> rawSource.trim().replaceFirstChar {
                if (it.isLowerCase()) it.titlecase(Locale.ROOT) else it.toString()
            }
        }
    }

    /**
     * Formats audio bitrate for display (e.g. 320000 -> 320k, 999000 -> FLAC).
     */
    fun formatBitrate(br: Int): String = when {
        br >= 900000 -> "FLAC"
        br >= 300000 -> "320k"
        br >= 180000 -> "192k"
        br >= 120000 -> "128k"
        br > 0 -> "${br / 1000}k"
        else -> "320k"
    }

    // Testing seams
    fun setTimeProviderForTesting(provider: () -> Long) {
        timeProvider = provider
    }

    fun setToastShowerForTesting(shower: (Context, CharSequence, Int) -> Unit) {
        toastShower = shower
    }

    fun setMainThreadDispatcherForTesting(dispatcher: ((() -> Unit) -> Unit)) {
        mainThreadDispatcher = dispatcher
    }

    fun resetForTesting() {
        lastToastTimestamp = 0L
        appContext = null
        currentSongFilter = null
        timeProvider = {
            try {
                android.os.SystemClock.uptimeMillis().takeIf { it > 0 } ?: System.currentTimeMillis()
            } catch (_: Throwable) {
                System.currentTimeMillis()
            }
        }
        mainThreadDispatcher = { action ->
            try {
                val looper = Looper.getMainLooper()
                if (looper != null) {
                    Handler(looper).post(action)
                } else {
                    action()
                }
            } catch (_: Throwable) {
                action()
            }
        }
        toastShower = { ctx, text, duration ->
            Toast.makeText(ctx, text, duration).show()
        }
    }
}
