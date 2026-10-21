package pochita.common

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * Adversarial Concurrency and Stress Test Harness for [UnmLruCache].
 *
 * Evaluates:
 * 1. High-load cache stampede with 50-100 concurrent threads on single and multi-keys.
 * 2. Extreme concurrency stampede with throwing/failing computations.
 * 3. High-throughput multi-threaded read/write/remove contention and invariants.
 * 4. Massive concurrent evictions with exact eviction accounting and bounds checking.
 * 5. Lifecycle interactions: clear() during in-flight computation, put() and remove() during compute.
 * 6. Edge scenarios: maxSize = 1, direct and indirect recursion, re-entrancy.
 */
class UnmLruCacheAdversarialTest {

    // =========================================================================
    // 1. Stampede Adversarial Testing (50-100 threads)
    // =========================================================================

    @Test
    fun testStampedeWith100ThreadsOnSingleKey() {
        val cache = UnmLruCache<String, String>(maxSize = 50)
        val threadCount = 100
        val executor = Executors.newFixedThreadPool(threadCount)
        val readyLatch = CountDownLatch(threadCount)
        val startGate = CountDownLatch(1)
        val finishLatch = CountDownLatch(threadCount)

        val computeCount = AtomicInteger(0)
        val results = ConcurrentHashMap<Int, String>()
        val exceptions = ConcurrentHashMap<Int, Throwable>()

        for (i in 0 until threadCount) {
            val threadId = i
            executor.execute {
                readyLatch.countDown()
                startGate.await()
                try {
                    val result = cache.getOrPut("hot-key-100") {
                        computeCount.incrementAndGet()
                        Thread.sleep(30) // simulate network latency
                        "computed-payload-for-hot-key"
                    }
                    results[threadId] = result
                } catch (t: Throwable) {
                    exceptions[threadId] = t
                } finally {
                    finishLatch.countDown()
                }
            }
        }

        assertTrue("Threads failed to get ready", readyLatch.await(5, TimeUnit.SECONDS))
        startGate.countDown()
        assertTrue("100-thread stampede timed out after 10s", finishLatch.await(10, TimeUnit.SECONDS))
        executor.shutdown()

        assertTrue("No thread should have failed with exception: $exceptions", exceptions.isEmpty())
        assertEquals("Stampede deduplication failed! computeCount must be exactly 1", 1, computeCount.get())
        assertEquals(threadCount, results.size)
        results.values.forEach {
            assertEquals("computed-payload-for-hot-key", it)
        }
        assertEquals(1, cache.size())
        assertEquals("computed-payload-for-hot-key", cache.get("hot-key-100"))
    }

    @Test
    fun testStampedeWith100ThreadsAcross10DistinctKeys() {
        val cache = UnmLruCache<Int, String>(maxSize = 20)
        val threadCount = 100
        val keyCount = 10
        val executor = Executors.newFixedThreadPool(threadCount)
        val readyLatch = CountDownLatch(threadCount)
        val startGate = CountDownLatch(1)
        val finishLatch = CountDownLatch(threadCount)

        val computeCounts = ConcurrentHashMap<Int, AtomicInteger>()
        for (k in 0 until keyCount) {
            computeCounts[k] = AtomicInteger(0)
        }

        val results = ConcurrentHashMap<Int, String>()
        val exceptions = ConcurrentHashMap<Int, Throwable>()

        for (i in 0 until threadCount) {
            val threadId = i
            val targetKey = i % keyCount
            executor.execute {
                readyLatch.countDown()
                startGate.await()
                try {
                    val res = cache.getOrPut(targetKey) {
                        computeCounts[targetKey]!!.incrementAndGet()
                        Thread.sleep(25)
                        "payload-$targetKey"
                    }
                    results[threadId] = res
                } catch (t: Throwable) {
                    exceptions[threadId] = t
                } finally {
                    finishLatch.countDown()
                }
            }
        }

        readyLatch.await(5, TimeUnit.SECONDS)
        startGate.countDown()
        assertTrue("Multi-key stampede timed out after 10s", finishLatch.await(10, TimeUnit.SECONDS))
        executor.shutdown()

        assertTrue("Exceptions encountered: $exceptions", exceptions.isEmpty())
        for (k in 0 until keyCount) {
            assertEquals("Key $k should be computed exactly once", 1, computeCounts[k]!!.get())
        }
        for (i in 0 until threadCount) {
            assertEquals("payload-${i % keyCount}", results[i])
        }
    }

    @Test
    fun testStampedeWithFailingComputationPropagatesToAllWaitersAndAllowsRetry() {
        val cache = UnmLruCache<String, String>(maxSize = 10)
        val threadCount = 50
        val executor = Executors.newFixedThreadPool(threadCount)
        val readyLatch = CountDownLatch(threadCount)
        val startGate = CountDownLatch(1)
        val finishLatch = CountDownLatch(threadCount)

        val computeCount = AtomicInteger(0)
        val caughtExceptions = ConcurrentHashMap<Int, Throwable>()

        for (i in 0 until threadCount) {
            val threadId = i
            executor.execute {
                readyLatch.countDown()
                startGate.await()
                try {
                    cache.getOrPut("failing-key") {
                        computeCount.incrementAndGet()
                        Thread.sleep(20)
                        throw IllegalStateException("Upstream server timeout (synthetic)")
                    }
                } catch (t: Throwable) {
                    caughtExceptions[threadId] = t
                } finally {
                    finishLatch.countDown()
                }
            }
        }

        readyLatch.await(5, TimeUnit.SECONDS)
        startGate.countDown()
        assertTrue("Stampede with failure timed out", finishLatch.await(8, TimeUnit.SECONDS))
        executor.shutdown()

        assertEquals("Failed computation should have executed exactly once", 1, computeCount.get())
        assertEquals("All 50 threads must catch the failure", threadCount, caughtExceptions.size)
        caughtExceptions.values.forEach { ex ->
            val rootCause = if (ex is java.util.concurrent.CompletionException) ex.cause else ex
            assertTrue(
                "Exception must be IllegalStateException: ${rootCause?.javaClass}",
                rootCause is IllegalStateException
            )
            assertEquals("Upstream server timeout (synthetic)", rootCause?.message)
        }

        // Verify cache state is not poisoned and subsequent attempt succeeds
        assertNull("Cache must not retain failed entry", cache.get("failing-key"))
        val recovered = cache.getOrPut("failing-key") { "recovered-value" }
        assertEquals("recovered-value", recovered)
        assertEquals("recovered-value", cache.get("failing-key"))
    }

    // =========================================================================
    // 2. High-Throughput Concurrent Read/Write & Invariants
    // =========================================================================

    @Test
    fun testHighThroughputConcurrentReadWriteEviction() {
        val maxSize = 25
        val cache = UnmLruCache<Int, String>(maxSize = maxSize)
        val threadCount = 16
        val iterationsPerThread = 2000
        val executor = Executors.newFixedThreadPool(threadCount)
        val latch = CountDownLatch(threadCount)
        val errors = ConcurrentHashMap<Int, Throwable>()

        for (t in 0 until threadCount) {
            val threadId = t
            executor.execute {
                try {
                    for (i in 0 until iterationsPerThread) {
                        val key = (i + threadId) % 100 // Working set 100 exceeds maxSize 25
                        when (i % 6) {
                            0 -> cache.put(key, "val-$key-$threadId")
                            1 -> cache.get(key)
                            2 -> cache.getOrPut(key) { "computed-$key" }
                            3 -> cache.remove(key)
                            4 -> cache.containsKey(key)
                            5 -> {
                                val snap = cache.snapshot()
                                assertTrue("Snapshot must not exceed maxSize", snap.size <= maxSize)
                            }
                        }
                        assertTrue("Cache size ${cache.size()} must not exceed maxSize $maxSize", cache.size() <= maxSize)
                    }
                } catch (t: Throwable) {
                    errors[threadId] = t
                } finally {
                    latch.countDown()
                }
            }
        }

        assertTrue("High throughput stress test timed out", latch.await(12, TimeUnit.SECONDS))
        executor.shutdown()

        assertTrue("Concurrent operations threw unexpected exceptions: $errors", errors.isEmpty())
        assertTrue("Final cache size must be <= maxSize", cache.size() <= maxSize)
    }

    // =========================================================================
    // 3. Massive Concurrent Evictions & Accounting
    // =========================================================================

    @Test
    fun testMassiveSequentialEvictionExactAccounting() {
        val maxSize = 10
        val totalKeys = 500
        val evictedList = Collections.synchronizedList(mutableListOf<Pair<Int, String>>())

        val cache = UnmLruCache<Int, String>(maxSize = maxSize) { k, v ->
            evictedList.add(k to v)
        }

        for (i in 0 until totalKeys) {
            cache.put(i, "v$i")
        }

        assertEquals(maxSize, cache.size())
        assertEquals("Must evict exactly totalKeys - maxSize entries", totalKeys - maxSize, evictedList.size)

        // Evictions should have occurred strictly in LRU order [0, 1, ..., 489]
        for (i in 0 until totalKeys - maxSize) {
            assertEquals(i to "v$i", evictedList[i])
        }

        // Remaining keys in cache must be [490..499]
        for (i in (totalKeys - maxSize) until totalKeys) {
            assertEquals("v$i", cache.get(i))
        }
    }

    @Test
    fun testConcurrentMassiveEvictionsNeverExceedMaxSize() {
        val maxSize = 5
        val evictedCount = AtomicInteger(0)
        val cache = UnmLruCache<String, String>(maxSize = maxSize) { _, _ ->
            evictedCount.incrementAndGet()
        }

        val threadCount = 20
        val keysPerThread = 200
        val executor = Executors.newFixedThreadPool(threadCount)
        val latch = CountDownLatch(threadCount)
        val maxObservedSize = AtomicInteger(0)

        for (t in 0 until threadCount) {
            val threadId = t
            executor.execute {
                try {
                    for (i in 0 until keysPerThread) {
                        val key = "t${threadId}-k$i"
                        cache.put(key, "val")
                        val currentSize = cache.size()
                        maxObservedSize.accumulateAndGet(currentSize) { a, b -> maxOf(a, b) }
                    }
                } finally {
                    latch.countDown()
                }
            }
        }

        assertTrue(latch.await(10, TimeUnit.SECONDS))
        executor.shutdown()

        assertTrue("Cache size must never exceed maxSize ($maxSize). Max observed: ${maxObservedSize.get()}", maxObservedSize.get() <= maxSize)
        assertEquals(maxSize, cache.size())
        assertEquals("Total evicted + remaining must equal total inserted entries", threadCount * keysPerThread - maxSize, evictedCount.get())
    }

    // =========================================================================
    // 4. clear() During In-Flight Computation
    // =========================================================================

    @Test
    fun testClearDuringInFlightComputationDoesNotPolluteClearedCache() {
        val cache = UnmLruCache<String, String>(maxSize = 10)
        val computeStarted = CountDownLatch(1)
        val allowComputeFinish = CountDownLatch(1)
        val threadFinished = CountDownLatch(1)

        var computedResult: String? = null

        val thread = Thread {
            try {
                computedResult = cache.getOrPut("k1") {
                    computeStarted.countDown()
                    allowComputeFinish.await()
                    "computed-v1"
                }
            } finally {
                threadFinished.countDown()
            }
        }
        thread.start()

        // Wait until computation starts
        assertTrue(computeStarted.await(3, TimeUnit.SECONDS))

        // Clear cache while computation is actively in flight
        cache.clear()
        assertEquals(0, cache.size())

        // Let the in-flight computation finish
        allowComputeFinish.countDown()
        assertTrue(threadFinished.await(3, TimeUnit.SECONDS))

        // Caller receives computed result
        assertEquals("computed-v1", computedResult)

        // But cache must NOT contain the stale entry because clear() invalidated the insertion epoch
        assertNull("Cleared cache must not retain entry computed from previous epoch", cache.get("k1"))
        assertEquals(0, cache.size())
    }

    @Test
    fun testClearDuringInFlightComputation_StampedeVulnerability() {
        val cache = UnmLruCache<String, String>(maxSize = 10)

        val t1Started = CountDownLatch(1)
        val allowT1Finish = CountDownLatch(1)
        val t1Done = CountDownLatch(1)

        val t2Started = CountDownLatch(1)
        val allowT2Finish = CountDownLatch(1)
        val t2Done = CountDownLatch(1)

        val t3Started = CountDownLatch(1)
        val t3Done = CountDownLatch(1)

        val concurrentExecutionDetected = AtomicBoolean(false)
        val t2IsRunning = AtomicBoolean(false)

        // T1 starts computing "key-x"
        val thread1 = Thread {
            cache.getOrPut("key-x") {
                t1Started.countDown()
                allowT1Finish.await()
                "val-1"
            }
            t1Done.countDown()
        }
        thread1.start()

        assertTrue(t1Started.await(3, TimeUnit.SECONDS))

        // Main thread clears the cache while T1 is computing
        cache.clear()

        // T2 starts computing "key-x" (since cache was cleared, T2 enters compute path)
        val thread2 = Thread {
            cache.getOrPut("key-x") {
                t2IsRunning.set(true)
                t2Started.countDown()
                allowT2Finish.await()
                t2IsRunning.set(false)
                "val-2"
            }
            t2Done.countDown()
        }
        thread2.start()

        assertTrue(t2Started.await(3, TimeUnit.SECONDS))
        assertTrue("T2 must be actively running defaultValue()", t2IsRunning.get())

        // Now allow T1 to finish its computation.
        // T1 finishes and executes inFlight.remove("key-x") unconditionally.
        allowT1Finish.countDown()
        assertTrue(t1Done.await(3, TimeUnit.SECONDS))

        // While T2 is STILL running, start T3 requesting "key-x".
        val thread3 = Thread {
            cache.getOrPut("key-x") {
                if (t2IsRunning.get()) {
                    concurrentExecutionDetected.set(true)
                }
                t3Started.countDown()
                "val-3"
            }
            t3Done.countDown()
        }
        thread3.start()

        // Wait a short moment to see if T3 runs concurrently while T2 is still running
        t3Started.await(500, TimeUnit.MILLISECONDS)

        allowT2Finish.countDown()
        assertTrue(t2Done.await(3, TimeUnit.SECONDS))
        assertTrue(t3Done.await(3, TimeUnit.SECONDS))

        // Assertion: T3 should NEVER have executed defaultValue() concurrently with T2!
        assertFalse(
            "CRITICAL BUG: Cache stampede deduplication broke! T3 executed defaultValue() while T2 was still computing key-x!",
            concurrentExecutionDetected.get()
        )
    }

    @Test
    fun testContinuousClearStressDuringHighConcurrency() {
        val cache = UnmLruCache<Int, String>(maxSize = 10)
        val threadCount = 12
        val running = AtomicBoolean(true)
        val executor = Executors.newFixedThreadPool(threadCount + 1)
        val latch = CountDownLatch(threadCount)
        val errors = ConcurrentHashMap<Int, Throwable>()

        // 1 clearer thread repeatedly calling clear()
        executor.execute {
            while (running.get()) {
                cache.clear()
                Thread.yield()
            }
        }

        // 12 worker threads repeatedly calling getOrPut
        for (t in 0 until threadCount) {
            val threadId = t
            executor.execute {
                try {
                    for (i in 0 until 500) {
                        val key = i % 20
                        val v = cache.getOrPut(key) { "val-$key-$threadId" }
                        assertNotNull(v)
                    }
                } catch (t: Throwable) {
                    errors[threadId] = t
                } finally {
                    latch.countDown()
                }
            }
        }

        assertTrue("Continuous clear stress test timed out", latch.await(10, TimeUnit.SECONDS))
        running.set(false)
        executor.shutdown()

        assertTrue("No exceptions should be thrown during concurrent clear and getOrPut: $errors", errors.isEmpty())
        assertTrue("Cache size must be <= maxSize", cache.size() <= 10)
    }

    // =========================================================================
    // 5. In-flight Race Conditions: getOrPut() vs put() and remove()
    // =========================================================================

    @Test
    fun testPutDuringInFlightComputeInteractions() {
        val cache = UnmLruCache<String, String>(maxSize = 5)
        val computeStarted = CountDownLatch(1)
        val allowComputeFinish = CountDownLatch(1)
        val computeFinished = CountDownLatch(1)

        val executor = Executors.newSingleThreadExecutor()
        var getOrPutResult: String? = null

        executor.execute {
            try {
                getOrPutResult = cache.getOrPut("shared-key") {
                    computeStarted.countDown()
                    allowComputeFinish.await()
                    "slow-computed-val"
                }
            } finally {
                computeFinished.countDown()
            }
        }

        assertTrue(computeStarted.await(3, TimeUnit.SECONDS))

        // While getOrPut is in flight, put() is invoked
        cache.put("shared-key", "explicit-put-val")
        assertEquals("explicit-put-val", cache.get("shared-key"))

        // Allow computation to finish
        allowComputeFinish.countDown()
        assertTrue(computeFinished.await(3, TimeUnit.SECONDS))
        executor.shutdown()

        assertEquals("slow-computed-val", getOrPutResult)
    }

    @Test
    fun testRemoveDuringInFlightCompute() {
        val cache = UnmLruCache<String, String>(maxSize = 5)
        val computeStarted = CountDownLatch(1)
        val allowComputeFinish = CountDownLatch(1)
        val computeFinished = CountDownLatch(1)

        val executor = Executors.newSingleThreadExecutor()
        var getOrPutResult: String? = null

        executor.execute {
            try {
                getOrPutResult = cache.getOrPut("key-to-remove") {
                    computeStarted.countDown()
                    allowComputeFinish.await()
                    "computed-val"
                }
            } finally {
                computeFinished.countDown()
            }
        }

        assertTrue(computeStarted.await(3, TimeUnit.SECONDS))

        // Remove the key while compute is in flight
        val removed = cache.remove("key-to-remove")
        assertNull("Key was not yet in map", removed)

        allowComputeFinish.countDown()
        assertTrue(computeFinished.await(3, TimeUnit.SECONDS))
        executor.shutdown()

        assertEquals("computed-val", getOrPutResult)
    }

    // =========================================================================
    // 6. Extreme Boundary Conditions
    // =========================================================================

    @Test
    fun testMaxSizeOneHighContention() {
        val cache = UnmLruCache<Int, String>(maxSize = 1)
        val threadCount = 10
        val opsPerThread = 500
        val executor = Executors.newFixedThreadPool(threadCount)
        val latch = CountDownLatch(threadCount)
        val errors = ConcurrentHashMap<Int, Throwable>()

        for (t in 0 until threadCount) {
            val threadId = t
            executor.execute {
                try {
                    for (i in 0 until opsPerThread) {
                        val key = i % 10
                        if (i % 2 == 0) {
                            cache.put(key, "val-$key")
                        } else {
                            cache.getOrPut(key) { "val-$key" }
                        }
                        assertEquals(1, cache.size())
                    }
                } catch (t: Throwable) {
                    errors[threadId] = t
                } finally {
                    latch.countDown()
                }
            }
        }

        assertTrue(latch.await(8, TimeUnit.SECONDS))
        executor.shutdown()

        assertTrue("Errors during maxSize 1 stress: $errors", errors.isEmpty())
        assertEquals(1, cache.size())
    }

    @Test
    fun testDirectSelfRecursionThrowsFast() {
        val cache = UnmLruCache<String, String>(maxSize = 5)

        try {
            cache.getOrPut("loop") {
                cache.getOrPut("loop") { "nested" }
            }
            fail("Expected IllegalStateException for recursive getOrPut")
        } catch (e: IllegalStateException) {
            assertTrue(e.message?.contains("Recursive getOrPut detected") == true)
        }

        // Cache state must remain intact after recursive failure
        assertNull(cache.get("loop"))
        val recovered = cache.getOrPut("loop") { "recovered" }
        assertEquals("recovered", recovered)
    }
}
