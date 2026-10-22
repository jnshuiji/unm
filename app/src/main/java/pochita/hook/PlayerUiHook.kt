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
import pochita.ui.ToastNotifier

/**
 * NetEase PlayerActivity and Playback UI hook coordinator.
 *
 * Responsibilities:
 * - Intercepts PlayerActivity lifecycle via [Application.registerActivityLifecycleCallbacks].
 * - Observes track changes via songName [TextWatcher] in PlayerActivity and tv_music in MainActivity.
 * - Suppresses background prefetch notifications and prevents ghost toasts.
 * - Manages dynamic badge injection ([PlayerBadgeManager.updateBadge] and [PlayerBadgeManager.showNativeBadge]).
 * - Restores host UI state cleanly across playback switches and lifecycle events.
 */
object PlayerUiHook {

    private const val TAG = "PlayerUiHook"
    const val TARGET_ACTIVITY = "com.netease.cloudmusic.activity.PlayerActivity"
    private const val RES_SONG_NAME = "songName"
    private const val RES_AUTHOR_NAME = "authorName"
    private const val RES_TV_MUSIC = "tv_music"

    private val mainHandler = Handler(Looper.getMainLooper())

    @Volatile
    private var currentActivityRef: WeakReference<Activity>? = null

    @Volatile
    var latestEvent: ReplaceEvent? = null
        internal set

    @Volatile
    var currentPlayingTitle: String = ""
        internal set

    private var songNameId = 0
    private var authorNameId = 0
    private var tvMusicId = 0

    private val activeWatchers = WeakHashMap<Activity, TextWatcher>()
    private var isSubscribedToBus = false
    private var isSubscribedToConfig = false
    private var configListenerUnregister: (() -> Unit)? = null

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
     * Public lifecycle hooks called by Instrumentation hook or ActivityLifecycleCallbacks.
     */
    fun onActivityCreated(activity: Activity) {
        val name = activity.javaClass.name
        UnmLogger.i(TAG, "onActivityCreated: $name")
        if (name.contains("PlayerActivity")) {
            onPlayerActivityCreated(activity)
        } else if (name.contains("MainActivity") || name.contains("DefaultAlias")) {
            onMainActivityCreated(activity)
        }
    }

    fun onActivityResumed(activity: Activity) {
        val name = activity.javaClass.name
        UnmLogger.i(TAG, "onActivityResumed: $name")
        if (name.contains("PlayerActivity")) {
            onPlayerActivityResumed(activity)
        } else if (name.contains("MainActivity") || name.contains("DefaultAlias")) {
            onMainActivityResumed(activity)
        }
    }

    fun onActivityDestroyed(activity: Activity) {
        val name = activity.javaClass.name
        UnmLogger.i(TAG, "onActivityDestroyed: $name")
        if (name.contains("PlayerActivity")) {
            onPlayerActivityDestroyed(activity)
        } else if (name.contains("MainActivity") || name.contains("DefaultAlias")) {
            onMainActivityDestroyed(activity)
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
                onActivityCreated(activity)
            }

            override fun onActivityStarted(activity: Activity) {}

            override fun onActivityResumed(activity: Activity) {
                onActivityResumed(activity)
            }

            override fun onActivityPaused(activity: Activity) {}

            override fun onActivityStopped(activity: Activity) {}

            override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) {}

            override fun onActivityDestroyed(activity: Activity) {
                onActivityDestroyed(activity)
            }
        })
        UnmLogger.i(TAG, "PlayerUiHook installed via ActivityLifecycleCallbacks")
    }

    private fun ensureBusSubscription() {
        if (!isSubscribedToBus) {
            ToastNotifier.currentSongFilter = { event ->
                isCurrentPlayingSong(event)
            }
            ReplaceEventBus.subscribe { event ->
                onTrackReplaced(event)
            }
            isSubscribedToBus = true
            UnmLogger.i(TAG, "PlayerUiHook subscribed to ReplaceEventBus with prefetch filter")
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
                refreshUiForActivity(act)
            }
        }
    }

    /**
     * Handles replacement events published by [ReplaceEventBus].
     * Suppresses background prefetch tracks so they do not disturb current playback or wipe badges.
     */
    fun onTrackReplaced(event: ReplaceEvent) {
        latestEvent = event
        dispatchToMain {
            val activity = currentActivityRef?.get()
            val currentTitle = if (activity != null) getCurrentSongTitle(activity) else currentPlayingTitle

            if (currentTitle.isNotEmpty() && titlesMatch(event.originalTitle, currentTitle)) {
                // Event matches actively playing song
                if (activity != null && !activity.isFinishing && !activity.isDestroyed) {
                    updateBadge(activity, event)
                }
            } else if (currentTitle.isEmpty()) {
                // Cold start when no title has been detected yet
                if (activity != null && !activity.isFinishing && !activity.isDestroyed) {
                    updateBadge(activity, event)
                }
            } else {
                // Background prefetch for upcoming tracks:
                // Caching is already recorded in ReplaceEventBus.
                // Do NOT touch on-screen badge or reset UI.
                UnmLogger.d(TAG, "Prefetch replacement received for upcoming track: ${event.originalTitle} (active: $currentTitle)")
            }
        }
    }

    /**
     * Determines whether [event] belongs to the actively playing track.
     */
    fun isCurrentPlayingSong(event: ReplaceEvent): Boolean {
        val title = currentPlayingTitle.takeIf { it.isNotBlank() }
            ?: currentActivityRef?.get()?.let { getCurrentSongTitle(it) }?.takeIf { it.isNotBlank() }
            ?: return true
        return titlesMatch(event.originalTitle, title) || titlesMatch(event.matchedTitle, title)
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
        UnmLogger.i(TAG, "onPlayerActivityCreated: ${activity.javaClass.name}")
        ensureBusSubscription()
        ensureConfigSubscription()
        currentActivityRef = WeakReference(activity)
        resolveResourceIds(activity)
        attachSongNameWatcher(activity)
    }

    fun onPlayerActivityResumed(activity: Activity) {
        UnmLogger.i(TAG, "onPlayerActivityResumed: ${activity.javaClass.name}")
        ensureBusSubscription()
        ensureConfigSubscription()
        currentActivityRef = WeakReference(activity)
        resolveResourceIds(activity)
        attachSongNameWatcher(activity)

        val config = ConfigManager.getConfig()
        if (!config.enablePlayerBadge) {
            resetBadge(activity)
            return
        }

        refreshUiForActivity(activity)

        // Post-delayed refresh to handle delayed data binding on resume
        activity.window?.decorView?.postDelayed({
            if (!activity.isFinishing && !activity.isDestroyed) {
                refreshUiForActivity(activity)
            }
        }, 200)
    }

    private fun refreshUiForActivity(activity: Activity) {
        val currentTitle = getCurrentSongTitle(activity)
        val currentArtist = getCurrentSongArtist(activity)
        UnmLogger.i(TAG, "refreshUiForActivity: title='$currentTitle', artist='$currentArtist'")

        if (currentTitle.isNotEmpty()) {
            currentPlayingTitle = currentTitle
            val replaced = ReplaceEventBus.findReplacementByTitle(currentTitle)
                ?: latestEvent?.takeIf { titlesMatch(it.originalTitle, currentTitle) }

            if (replaced != null) {
                latestEvent = replaced
                updateBadge(activity, replaced)
            } else {
                PlayerBadgeManager.showNativeBadge(activity, currentTitle, currentArtist)
            }
        } else {
            resetBadge(activity)
        }
    }

    fun onPlayerActivityDestroyed(activity: Activity) {
        UnmLogger.i(TAG, "onPlayerActivityDestroyed: ${activity.javaClass.name}")
        detachSongNameWatcher(activity)
        if (currentActivityRef?.get() == activity) {
            currentActivityRef = null
        }
        resetBadge(activity)
    }

    fun attachSongNameWatcher(activity: Activity) {
        val songNameView = PlayerBadgeManager.findViewByStringId(activity, RES_SONG_NAME) as? TextView ?: return
        if (activeWatchers.containsKey(activity)) return

        val watcher = object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                val newTitle = s?.toString()?.trim() ?: ""
                UnmLogger.i(TAG, "songName text changed: '$newTitle'")
                if (newTitle.isEmpty()) {
                    resetBadge(activity)
                    return
                }

                currentPlayingTitle = newTitle
                val currentArtist = getCurrentSongArtist(activity)
                val replaced = ReplaceEventBus.findReplacementByTitle(newTitle)

                if (replaced != null) {
                    latestEvent = replaced
                    updateBadge(activity, replaced)
                    ToastNotifier.notify(activity, replaced)
                } else {
                    latestEvent = null
                    PlayerBadgeManager.showNativeBadge(activity, newTitle, currentArtist)
                }
            }
        }
        songNameView.addTextChangedListener(watcher)
        activeWatchers[activity] = watcher
        UnmLogger.i(TAG, "Attached songName TextWatcher successfully")
    }

    fun detachSongNameWatcher(activity: Activity) {
        val watcher = activeWatchers.remove(activity) ?: return
        val songNameView = PlayerBadgeManager.findViewByStringId(activity, RES_SONG_NAME) as? TextView
        songNameView?.removeTextChangedListener(watcher)
    }

    // =========================================================================
    // MainActivity (Mini Player Bar) Observers
    // =========================================================================

    fun onMainActivityCreated(activity: Activity) {
        resolveMainActivityResourceIds(activity)
        attachMainActivityWatcher(activity)
    }

    fun onMainActivityResumed(activity: Activity) {
        resolveMainActivityResourceIds(activity)
        attachMainActivityWatcher(activity)
    }

    fun onMainActivityDestroyed(activity: Activity) {
        detachMainActivityWatcher(activity)
    }

    fun attachMainActivityWatcher(activity: Activity) {
        val tvMusic = PlayerBadgeManager.findViewByStringId(activity, RES_TV_MUSIC) as? TextView ?: return
        if (activeWatchers.containsKey(activity)) return

        val initialText = tvMusic.text?.toString()?.trim() ?: ""
        if (initialText.isNotEmpty()) {
            val title = initialText.substringBefore(" - ").trim()
            if (title.isNotEmpty()) {
                currentPlayingTitle = title
            }
        }

        val watcher = object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                val text = s?.toString()?.trim() ?: ""
                if (text.isEmpty()) return
                val title = text.substringBefore(" - ").trim()
                if (title.isEmpty()) return

                currentPlayingTitle = title
                val replaced = ReplaceEventBus.findReplacementByTitle(title)
                if (replaced != null) {
                    ToastNotifier.notify(activity, replaced)
                }
            }
        }
        tvMusic.addTextChangedListener(watcher)
        activeWatchers[activity] = watcher
    }

    fun detachMainActivityWatcher(activity: Activity) {
        val watcher = activeWatchers.remove(activity) ?: return
        val tvMusic = PlayerBadgeManager.findViewByStringId(activity, RES_TV_MUSIC) as? TextView
        tvMusic?.removeTextChangedListener(watcher)
    }

    // =========================================================================
    // View Resolvers & Text Matching
    // =========================================================================

    fun getCurrentSongTitle(activity: Activity): String {
        val songNameView = PlayerBadgeManager.findViewByStringId(activity, RES_SONG_NAME) as? TextView ?: return ""
        return songNameView.text?.toString()?.trim() ?: ""
    }

    fun getCurrentSongArtist(activity: Activity): String {
        val authorNameView = PlayerBadgeManager.findViewByStringId(activity, RES_AUTHOR_NAME) as? TextView ?: return ""
        return authorNameView.text?.toString()?.trim() ?: ""
    }

    fun titlesMatch(a: String, b: String): Boolean {
        return ReplaceEventBus.titlesMatch(a, b)
    }

    private fun resolveResourceIds(context: Context) {
        if (songNameId == 0) {
            songNameId = context.resources.getIdentifier(RES_SONG_NAME, "id", context.packageName)
        }
        if (authorNameId == 0) {
            authorNameId = context.resources.getIdentifier(RES_AUTHOR_NAME, "id", context.packageName)
        }
    }

    private fun resolveMainActivityResourceIds(context: Context) {
        if (tvMusicId == 0) {
            tvMusicId = context.resources.getIdentifier(RES_TV_MUSIC, "id", context.packageName)
        }
    }

    /**
     * Testing seam: Resets static state between unit tests.
     */
    fun resetForTesting() {
        unregisterConfigListener()
        currentActivityRef = null
        latestEvent = null
        currentPlayingTitle = ""
        songNameId = 0
        authorNameId = 0
        tvMusicId = 0
        activeWatchers.clear()
        isSubscribedToBus = false
        onResetBadgeForTesting = null
    }

    fun setCurrentActivityForTesting(activity: Activity?) {
        currentActivityRef = activity?.let { WeakReference(it) }
    }
}
