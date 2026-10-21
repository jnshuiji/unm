package pochita.hook

import android.app.Activity
import android.app.Application
import android.content.Context
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.Editable
import android.text.TextWatcher
import android.widget.TextView
import java.lang.ref.WeakReference
import java.util.WeakHashMap
import pochita.common.UnmLogger
import pochita.config.ConfigManager
import pochita.config.UnmConfig
import pochita.event.ReplaceEventBus
import pochita.model.ReplaceEvent
import pochita.ui.PlayerBadgeManager

/**
 * NetEase PlayerActivity UI hook coordinator.
 *
 * Intercepts PlayerActivity lifecycle via [Application.registerActivityLifecycleCallbacks],
 * observes track changes via songName [TextWatcher], and manages dynamic badge injection
 * and state resets.
 */
object PlayerUiHook {

    private const val TAG = "PlayerUiHook"
    const val TARGET_ACTIVITY = "com.netease.cloudmusic.activity.PlayerActivity"
    private const val RES_SONG_NAME = "songName"

    private val mainHandler = Handler(Looper.getMainLooper())

    @Volatile
    private var currentActivityRef: WeakReference<Activity>? = null

    @Volatile
    var latestEvent: ReplaceEvent? = null
        internal set

    private var songNameId = 0
    private val activeWatchers = WeakHashMap<Activity, TextWatcher>()
    private var isSubscribedToBus = false
    private var isSubscribedToConfig = false
    private var configListenerUnregister: (() -> Unit)? = null

    private val TITLE_NORMALIZE_REGEX = "[\\p{Punct}\\p{P}\\p{S}\\s]+".toRegex()

    /**
     * Initializes hook using target ClassLoader (satisfies interface contract).
     */
    fun init(classLoader: ClassLoader) {
        try {
            classLoader.loadClass(TARGET_ACTIVITY)
            ensureBusSubscription()
            ensureConfigSubscription()
            UnmLogger.i(TAG, "PlayerUiHook initialized with classLoader")
        } catch (t: Throwable) {
            UnmLogger.w(TAG, "PlayerActivity not immediately found in classLoader: ${t.message}")
        }
    }

    /**
     * Installs lifecycle monitoring and event subscriptions via host [Application].
     */
    fun install(app: Application) {
        ensureBusSubscription()
        ensureConfigSubscription()
        app.registerActivityLifecycleCallbacks(object : Application.ActivityLifecycleCallbacks {
            override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {
                if (activity.javaClass.name == TARGET_ACTIVITY) {
                    onPlayerActivityCreated(activity)
                }
            }

            override fun onActivityStarted(activity: Activity) {}

            override fun onActivityResumed(activity: Activity) {
                if (activity.javaClass.name == TARGET_ACTIVITY) {
                    onPlayerActivityResumed(activity)
                }
            }

            override fun onActivityPaused(activity: Activity) {}

            override fun onActivityStopped(activity: Activity) {}

            override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) {}

            override fun onActivityDestroyed(activity: Activity) {
                if (activity.javaClass.name == TARGET_ACTIVITY) {
                    onPlayerActivityDestroyed(activity)
                }
            }
        })
        UnmLogger.i(TAG, "PlayerUiHook installed via ActivityLifecycleCallbacks")
    }

    private fun ensureBusSubscription() {
        if (!isSubscribedToBus) {
            ReplaceEventBus.subscribe { event ->
                onTrackReplaced(event)
            }
            isSubscribedToBus = true
        }
    }

    fun ensureConfigSubscription() {
        if (!isSubscribedToConfig) {
            configListenerUnregister = ConfigManager.addOnConfigChangeListener { config ->
                onConfigChanged(config)
            }
            isSubscribedToConfig = true
            UnmLogger.i(TAG, "PlayerUiHook subscribed to ConfigManager")
        }
    }

    fun unregisterConfigListener() {
        configListenerUnregister?.invoke()
        configListenerUnregister = null
        isSubscribedToConfig = false
    }

    internal var onResetBadgeForTesting: ((Activity) -> Unit)? = null

    private fun dispatchToMain(block: () -> Unit) {
        try {
            if (Looper.myLooper() != null && Looper.myLooper() == Looper.getMainLooper()) {
                block()
                return
            }
            if (!mainHandler.post(block)) {
                block()
            }
        } catch (_: Throwable) {
            block()
        }
    }

    fun onConfigChanged(config: UnmConfig) {
        val activity = currentActivityRef?.get() ?: return
        if (activity.isFinishing || activity.isDestroyed) return

        if (!config.enablePlayerBadge) {
            dispatchToMain {
                val act = currentActivityRef?.get() ?: return@dispatchToMain
                if (act.isFinishing || act.isDestroyed) return@dispatchToMain
                resetBadge(act)
            }
        } else {
            dispatchToMain {
                val act = currentActivityRef?.get() ?: return@dispatchToMain
                if (act.isFinishing || act.isDestroyed) return@dispatchToMain
                val event = latestEvent
                val currentTitle = getCurrentSongTitle(act)
                if (event != null && currentTitle.isNotEmpty() && titlesMatch(event.originalTitle, currentTitle)) {
                    updateBadge(act, event)
                }
            }
        }
    }

    /**
     * Handles replacement events published by [ReplaceEventBus].
     */
    fun onTrackReplaced(event: ReplaceEvent) {
        latestEvent = event
        mainHandler.post {
            val activity = currentActivityRef?.get() ?: return@post
            if (activity.isFinishing || activity.isDestroyed) return@post
            updateBadge(activity, event)
        }
    }

    /**
     * Updates or injects the dynamic badge for the given [activity] and [event].
     */
    fun updateBadge(activity: Activity, event: ReplaceEvent?) {
        PlayerBadgeManager.updateBadge(activity, event)
    }

    /**
     * Resets authorNameTag and hides badge for the given [activity].
     */
    fun resetBadge(activity: Activity) {
        onResetBadgeForTesting?.invoke(activity)
        PlayerBadgeManager.resetBadge(activity)
    }

    fun onPlayerActivityCreated(activity: Activity) {
        ensureBusSubscription()
        ensureConfigSubscription()
        currentActivityRef = WeakReference(activity)
        resolveResourceIds(activity)
        attachSongNameWatcher(activity)
    }

    fun onPlayerActivityResumed(activity: Activity) {
        ensureConfigSubscription()
        currentActivityRef = WeakReference(activity)
        resolveResourceIds(activity)

        val config = ConfigManager.getConfig()
        if (!config.enablePlayerBadge) {
            resetBadge(activity)
            return
        }

        val event = latestEvent
        val currentTitle = getCurrentSongTitle(activity)
        if (event != null && currentTitle.isNotEmpty() && titlesMatch(event.originalTitle, currentTitle)) {
            updateBadge(activity, event)
        } else {
            resetBadge(activity)
        }
    }

    fun onPlayerActivityDestroyed(activity: Activity) {
        detachSongNameWatcher(activity)
        if (currentActivityRef?.get() == activity) {
            currentActivityRef = null
        }
        resetBadge(activity)
    }

    fun attachSongNameWatcher(activity: Activity) {
        if (songNameId == 0) return
        val songNameView = activity.findViewById<TextView>(songNameId) ?: return
        if (activeWatchers.containsKey(activity)) return

        val watcher = object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                val newTitle = s?.toString()?.trim() ?: ""
                val current = latestEvent
                if (current == null || !titlesMatch(current.originalTitle, newTitle)) {
                    // Song changed to an unreplaced or different track
                    resetBadge(activity)
                } else {
                    updateBadge(activity, current)
                }
            }
        }
        songNameView.addTextChangedListener(watcher)
        activeWatchers[activity] = watcher
    }

    fun detachSongNameWatcher(activity: Activity) {
        val watcher = activeWatchers.remove(activity) ?: return
        if (songNameId != 0) {
            val songNameView = activity.findViewById<TextView>(songNameId)
            songNameView?.removeTextChangedListener(watcher)
        }
    }

    fun getCurrentSongTitle(activity: Activity): String {
        if (songNameId == 0) return ""
        val songNameView = activity.findViewById<TextView>(songNameId) ?: return ""
        return songNameView.text?.toString()?.trim() ?: ""
    }

    fun titlesMatch(a: String, b: String): Boolean {
        if (a.isBlank() || b.isBlank()) return false
        if (a.equals(b, ignoreCase = true)) return true
        val cleanA = a.replace(TITLE_NORMALIZE_REGEX, "").lowercase()
        val cleanB = b.replace(TITLE_NORMALIZE_REGEX, "").lowercase()
        if (cleanA.isEmpty() && cleanB.isEmpty()) {
            return a.trim().equals(b.trim(), ignoreCase = true)
        }
        return cleanA.isNotEmpty() && cleanA == cleanB
    }

    private fun resolveResourceIds(context: Context) {
        if (songNameId == 0) {
            songNameId = context.resources.getIdentifier(RES_SONG_NAME, "id", context.packageName)
        }
    }

    /**
     * Testing seam: Resets static state between unit tests.
     */
    fun resetForTesting() {
        unregisterConfigListener()
        currentActivityRef = null
        latestEvent = null
        songNameId = 0
        activeWatchers.clear()
        isSubscribedToBus = false
        onResetBadgeForTesting = null
    }

    fun setCurrentActivityForTesting(activity: Activity?) {
        currentActivityRef = activity?.let { WeakReference(it) }
    }
}
