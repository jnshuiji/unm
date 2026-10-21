package pochita.event

import pochita.common.UnmLogger
import pochita.model.ReplaceEvent
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicReference

/**
 * Thread-safe event bus for track replacement events.
 *
 * Decouples network resolution (PlayerUrlResolver) from UI feedback (ToastNotifier, PlayerUiHook).
 * Provides strict error isolation and sticky event caching for host UI lifecycle.
 */
object ReplaceEventBus {
    private const val TAG = "ReplaceEventBus"

    fun interface Listener {
        fun onReplace(event: ReplaceEvent)
    }

    private val listeners = CopyOnWriteArrayList<Listener>()
    private val latestEventRef = AtomicReference<ReplaceEvent?>(null)

    /**
     * The most recently published [ReplaceEvent], or null if no track has been replaced yet.
     */
    val latestEvent: ReplaceEvent?
        get() = latestEventRef.get()

    /**
     * Publishes a [ReplaceEvent] to all registered listeners.
     * Thread-safe and isolated against subscriber exceptions.
     */
    fun publish(event: ReplaceEvent) {
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
     * Testing seam: clears all listeners and state.
     */
    fun resetForTesting() {
        listeners.clear()
        latestEventRef.set(null)
    }
}
