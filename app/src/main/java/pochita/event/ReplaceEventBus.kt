package pochita.event

import pochita.common.UnmLogger
import pochita.model.ReplaceEvent
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicReference

/**
 * Thread-safe event bus for track replacement events.
 *
 * Decouples network resolution (PlayerUrlResolver) from UI feedback (ToastNotifier, PlayerUiHook).
 * Provides strict error isolation, sticky event caching, and multi-track replacement history.
 */
object ReplaceEventBus {
    private const val TAG = "ReplaceEventBus"
    private const val MAX_CACHE_ENTRIES = 200

    private val TITLE_NORMALIZE_REGEX = "[\\p{Punct}\\p{P}\\p{S}\\s]+".toRegex()

    fun interface Listener {
        fun onReplace(event: ReplaceEvent)
    }

    private val listeners = CopyOnWriteArrayList<Listener>()
    private val latestEventRef = AtomicReference<ReplaceEvent?>(null)

    private val cacheLock = Any()

    private val titleCache = object : LinkedHashMap<String, ReplaceEvent>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, ReplaceEvent>?): Boolean {
            return size > MAX_CACHE_ENTRIES
        }
    }

    private val idCache = object : LinkedHashMap<Long, ReplaceEvent>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Long, ReplaceEvent>?): Boolean {
            return size > MAX_CACHE_ENTRIES
        }
    }

    /**
     * The most recently published [ReplaceEvent], or null if no track has been replaced yet.
     */
    val latestEvent: ReplaceEvent?
        get() = latestEventRef.get()

    /**
     * Publishes a [ReplaceEvent] to all registered listeners and caches it.
     * Thread-safe and isolated against subscriber exceptions.
     */
    fun publish(event: ReplaceEvent) {
        recordReplacement(event)
        latestEventRef.set(event)
        for (listener in listeners) {
            try {
                listener.onReplace(event)
            } catch (t: Throwable) {
                UnmLogger.w(TAG, "Uncaught exception in ReplaceEventBus listener: ${t.message}", t)
            }
        }
    }

    /**
     * Caches [event] by song ID and normalized titles for fast retrieval.
     */
    fun recordReplacement(event: ReplaceEvent) {
        synchronized(cacheLock) {
            idCache[event.songId] = event
            val normOrig = normalizeTitle(event.originalTitle)
            if (normOrig.isNotEmpty()) {
                titleCache[normOrig] = event
            }
            if (event.matchedTitle.isNotBlank() && event.matchedTitle != event.originalTitle) {
                val normMatched = normalizeTitle(event.matchedTitle)
                if (normMatched.isNotEmpty()) {
                    titleCache[normMatched] = event
                }
            }
        }
    }

    /**
     * Looks up a cached replacement by NetEase song ID.
     */
    fun findReplacementById(songId: Long): ReplaceEvent? {
        synchronized(cacheLock) {
            return idCache[songId]
        }
    }

    /**
     * Looks up a cached replacement by track title (supports normalized and fuzzy titlesMatch).
     */
    fun findReplacementByTitle(title: String?): ReplaceEvent? {
        if (title.isNullOrBlank()) return null
        val normalized = normalizeTitle(title)
        synchronized(cacheLock) {
            if (normalized.isNotEmpty()) {
                titleCache[normalized]?.let { return it }
            }
            // Fallback: full titlesMatch across cache
            for (event in titleCache.values) {
                if (titlesMatch(event.originalTitle, title) || titlesMatch(event.matchedTitle, title)) {
                    return event
                }
            }
        }
        return null
    }

    /**
     * Normalizes title for robust indexing and comparison (strips punctuation and whitespace).
     */
    fun normalizeTitle(title: String): String {
        val clean = title.replace(TITLE_NORMALIZE_REGEX, "").lowercase()
        return if (clean.isEmpty()) title.trim().lowercase() else clean
    }

    /**
     * Symmetrical title matcher rejecting substring overlaps while normalizing punctuation.
     */
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

    /**
     * Subscribes a [listener] to replacement events.
     * Returns an unregister callback `() -> Unit` for safe lifecycle cleanup.
     */
    fun subscribe(listener: Listener): () -> Unit {
        listeners.add(listener)
        return { unsubscribe(listener) }
    }

    /**
     * Functional overload for lambda subscription.
     */
    fun subscribe(listener: (ReplaceEvent) -> Unit): () -> Unit {
        val wrapped = Listener { listener(it) }
        listeners.add(wrapped)
        return { unsubscribe(wrapped) }
    }

    /**
     * Subscribes a [listener] and immediately dispatches the [latestEvent]
     * if present (sticky event dispatch).
     */
    fun subscribeSticky(listener: (ReplaceEvent) -> Unit): () -> Unit {
        val wrapped = Listener { listener(it) }
        listeners.add(wrapped)
        val current = latestEventRef.get()
        if (current != null) {
            try {
                wrapped.onReplace(current)
            } catch (t: Throwable) {
                UnmLogger.w(TAG, "Uncaught exception in sticky listener: ${t.message}", t)
            }
        }
        return { unsubscribe(wrapped) }
    }

    /**
     * Removes a previously registered [listener].
     */
    fun unsubscribe(listener: Listener) {
        listeners.remove(listener)
    }

    /**
     * Clears the cached latest event (e.g. when track finishes or resets).
     */
    fun clearLatestEvent() {
        latestEventRef.set(null)
    }

    /**
     * Current number of registered subscribers.
     */
    val subscriberCount: Int
        get() = listeners.size

    /**
     * Testing seam: clears all listeners, state, and caches.
     */
    fun resetForTesting() {
        listeners.clear()
        latestEventRef.set(null)
        synchronized(cacheLock) {
            idCache.clear()
            titleCache.clear()
        }
    }
}
