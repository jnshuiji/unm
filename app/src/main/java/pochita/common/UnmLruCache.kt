package pochita.common

import java.util.LinkedHashMap
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionException
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * High-performance, thread-safe Least Recently Used (LRU) cache.
 *
 * Key Architectural Characteristics:
 * - Pure JVM compatibility: Zero Android SDK dependencies (`android.util.LruCache` decoupled).
 * - Cache Stampede Prevention: Atomic [getOrPut] guarantees that concurrent requests for
 *   the same missing key execute the computation lambda exactly once.
 * - Non-blocking Computation: The internal lock is released during [defaultValue] execution,
 *   allowing concurrent operations on different keys to proceed without lock contention.
 * - Recursion Safe: Detects direct self-referential recursive computations to prevent deadlocks.
 *
 * @param K Non-null key type.
 * @param V Non-null value type.
 * @param maxSize Maximum number of entries before LRU eviction occurs (must be > 0).
 * @param onEntryEvicted Optional callback invoked when an entry is evicted.
 */
class UnmLruCache<K : Any, V : Any>(
    val maxSize: Int,
    private val onEntryEvicted: ((key: K, value: V) -> Unit)? = null
) {
    init {
        require(maxSize > 0) { "maxSize must be positive, but was: $maxSize" }
    }

    private val lock = ReentrantLock()

    // Internal access-order LinkedHashMap: head is least recently used, tail is most recently used.
    private val map = object : LinkedHashMap<K, V>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<K, V>?): Boolean {
            val shouldEvict = size > maxSize
            if (shouldEvict && eldest != null) {
                onEntryEvicted?.invoke(eldest.key, eldest.value)
            }
            return shouldEvict
        }
    }

    // In-flight computation tracking to eliminate cache stampedes
    private class InFlightRecord<V>(
        val future: CompletableFuture<V>,
        val owningThread: Thread
    )
    private val inFlight = HashMap<K, InFlightRecord<V>>()

    // Monotonic generation counter to invalidate in-flight inserts after cache clear()
    private var epoch: Long = 0L

    /**
     * Retrieves an entry by [key], updating its position to Most Recently Used (MRU).
     * Returns `null` if the key is not present.
     */
    fun get(key: K): V? = lock.withLock {
        map[key]
    }

    /**
     * Stores [value] for [key], marking it as MRU.
     * Evicts the eldest entry if cache size exceeds [maxSize].
     * Returns the previous value associated with [key], or `null`.
     */
    fun put(key: K, value: V): V? = lock.withLock {
        map.put(key, value)
    }

    /**
     * Removes an entry by [key]. Returns the removed value, or `null`.
     */
    fun remove(key: K): V? = lock.withLock {
        map.remove(key)
    }

    /**
     * Atomically returns the value for [key] if present.
     * If absent, executes [defaultValue] and caches the result.
     *
     * Guarantee: If multiple threads concurrently invoke [getOrPut] for the same missing key,
     * [defaultValue] is executed EXACTLY ONCE. Waiting threads block only on the computation
     * future, while unrelated cache keys remain fully accessible without lock contention.
     */
    fun getOrPut(key: K, defaultValue: () -> V): V {
        var futureToWait: CompletableFuture<V>? = null
        var myFuture: CompletableFuture<V>? = null
        val currentEpoch: Long

        lock.withLock {
            val existing = map[key]
            if (existing != null) {
                return existing
            }

            val existingInFlight = inFlight[key]
            if (existingInFlight != null) {
                if (existingInFlight.owningThread === Thread.currentThread()) {
                    throw IllegalStateException("Recursive getOrPut detected for key: $key on thread: ${Thread.currentThread().name}")
                }
                futureToWait = existingInFlight.future
                currentEpoch = epoch
            } else {
                val future = CompletableFuture<V>()
                inFlight[key] = InFlightRecord(future, Thread.currentThread())
                myFuture = future
                currentEpoch = epoch
            }
        }

        // Wait path: Another thread is already computing this key
        if (futureToWait != null) {
            return try {
                futureToWait.join()
            } catch (e: CompletionException) {
                throw e.cause ?: e
            }
        }

        // Computing path: This thread is responsible for executing defaultValue()
        val computedValue: V
        try {
            computedValue = defaultValue()
        } catch (t: Throwable) {
            lock.withLock {
                inFlight.remove(key)
            }
            myFuture!!.completeExceptionally(t)
            throw t
        }

        lock.withLock {
            // Only insert into map if the cache was not cleared during computation
            if (epoch == currentEpoch) {
                map[key] = computedValue
            }
            inFlight.remove(key)
        }
        myFuture!!.complete(computedValue)

        return computedValue
    }

    /**
     * Clears all entries from the cache and invalidates active in-flight insertions.
     */
    fun clear() = lock.withLock {
        epoch++
        map.clear()
        inFlight.clear()
    }

    /**
     * Returns the current number of cached entries.
     */
    fun size(): Int = lock.withLock {
        map.size
    }

    /**
     * Returns `true` if the cache contains [key].
     */
    fun containsKey(key: K): Boolean = lock.withLock {
        map.containsKey(key)
    }

    /**
     * Returns a snapshot copy of the current entries in LRU order (least to most recent).
     */
    fun snapshot(): Map<K, V> = lock.withLock {
        LinkedHashMap(map)
    }

    /**
     * Returns `true` if the cache is empty.
     */
    fun isEmpty(): Boolean = lock.withLock {
        map.isEmpty()
    }
}
