package pochita.common

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class UnmLruCacheTest {

    // --- Tier 1: Happy Path ---

    @Test
    fun testPutAndGet() {
        val cache = UnmLruCache<String, Int>(maxSize = 3)
        cache.put("k1", 100)
        cache.put("k2", 200)

        assertEquals(2, cache.size())
        assertEquals(100, cache.get("k1"))
        assertEquals(200, cache.get("k2"))
        assertNull(cache.get("k3"))
    }

    @Test
    fun testUpdateExistingKey() {
        val cache = UnmLruCache<String, String>(maxSize = 2)
        val prev1 = cache.put("a", "alpha")
        assertNull(prev1)
        assertEquals(1, cache.size())

        val prev2 = cache.put("a", "alpha-updated")
        assertEquals("alpha", prev2)
        assertEquals("alpha-updated", cache.get("a"))
        assertEquals(1, cache.size())
    }

    @Test
    fun testRemove() {
        val cache = UnmLruCache<Int, String>(maxSize = 5)
        cache.put(1, "one")
        cache.put(2, "two")

        assertEquals("one", cache.remove(1))
        assertNull(cache.get(1))
        assertEquals(1, cache.size())
        assertNull(cache.remove(99))
    }

    @Test
    fun testClear() {
        val cache = UnmLruCache<Int, Int>(maxSize = 5)
        for (i in 1..5) cache.put(i, i * 10)
        assertEquals(5, cache.size())

        cache.clear()
        assertEquals(0, cache.size())
        assertTrue(cache.isEmpty())
        assertNull(cache.get(1))
    }

    @Test
    fun testGetOrPutOnHitAndMiss() {
        val cache = UnmLruCache<String, String>(maxSize = 3)
        val invocationCount = AtomicInteger(0)

        // Miss -> executes computation
        val val1 = cache.getOrPut("key1") {
            invocationCount.incrementAndGet()
            "computed-val1"
        }
        assertEquals("computed-val1", val1)
        assertEquals(1, invocationCount.get())

        // Hit -> returns cached without executing computation
        val val2 = cache.getOrPut("key1") {
            invocationCount.incrementAndGet()
            "should-not-run"
        }
        assertEquals("computed-val1", val2)
        assertEquals(1, invocationCount.get())
    }

    // --- Tier 2: Boundary, Eviction & Order ---

    @Test(expected = IllegalArgumentException::class)
    fun testZeroMaxSizeThrows() {
        UnmLruCache<String, String>(maxSize = 0)
    }

    @Test(expected = IllegalArgumentException::class)
    fun testNegativeMaxSizeThrows() {
        UnmLruCache<String, String>(maxSize = -5)
    }

    @Test
    fun testLruEvictionWhenCapacityExceeded() {
        val evictedEntries = mutableListOf<Pair<String, Int>>()
        val cache = UnmLruCache<String, Int>(maxSize = 3) { k, v ->
            evictedEntries.add(k to v)
        }

        cache.put("A", 1)
        cache.put("B", 2)
        cache.put("C", 3)

        // Cache full [A, B, C]. Inserting D must evict A (least recently used)
        cache.put("D", 4)
        assertEquals(3, cache.size())
        assertNull(cache.get("A"))
        assertEquals(2, cache.get("B"))
        assertEquals(3, cache.get("C"))
        assertEquals(4, cache.get("D"))

        assertEquals(1, evictedEntries.size)
        assertEquals("A" to 1, evictedEntries[0])
    }

    @Test
    fun testLruOrderPromotedOnGet() {
        val cache = UnmLruCache<String, String>(maxSize = 3)
        cache.put("A", "a")
        cache.put("B", "b")
        cache.put("C", "c")

        // Access "A" -> moves A to MRU. Order is now [B, C, A]
        cache.get("A")

        // Inserting D must evict B
        cache.put("D", "d")
        assertNull(cache.get("B"))
        assertNotNull(cache.get("A"))
        assertNotNull(cache.get("C"))
        assertNotNull(cache.get("D"))
    }

    @Test
    fun testMaxSizeOne() {
        val cache = UnmLruCache<String, Int>(maxSize = 1)
        cache.put("X", 10)
        assertEquals(10, cache.get("X"))

        cache.put("Y", 20)
        assertNull(cache.get("X"))
        assertEquals(20, cache.get("Y"))
        assertEquals(1, cache.size())
    }

    @Test
    fun testSnapshotAndContainsKey() {
        val cache = UnmLruCache<String, Int>(maxSize = 3)
        cache.put("p", 1)
        cache.put("q", 2)

        assertTrue(cache.containsKey("p"))
        assertFalse(cache.containsKey("z"))

        val snapshot = cache.snapshot()
        assertEquals(2, snapshot.size)
        assertEquals(1, snapshot["p"])
        assertEquals(2, snapshot["q"])
    }

    // --- Tier 3: Concurrency, Stampede Prevention & Safety ---

    @Test
    fun testCacheStampedeElimination() {
        val cache = UnmLruCache<Long, String>(maxSize = 10)
        val threadCount = 20
        val executor = Executors.newFixedThreadPool(threadCount)
        val readyLatch = CountDownLatch(threadCount)
        val startGate = CountDownLatch(1)
        val finishLatch = CountDownLatch(threadCount)

        val computeCounter = AtomicInteger(0)
        val results = ConcurrentHashMap<Int, String>()

        for (i in 0 until threadCount) {
            val threadId = i
            executor.execute {
                readyLatch.countDown()
                startGate.await() // Wait for all threads to align
                try {
                    val value = cache.getOrPut(54321L) {
                        computeCounter.incrementAndGet()
                        Thread.sleep(50) // Simulate heavy computation / network fetch
                        "audio-url-for-54321"
                    }
                    results[threadId] = value
                } finally {
                    finishLatch.countDown()
                }
            }
        }

        readyLatch.await()
        startGate.countDown() // Release all 20 threads simultaneously
        assertTrue("All threads must finish within 5s", finishLatch.await(5, TimeUnit.SECONDS))
        executor.shutdown()

        // Critical Assertion: The computation MUST execute exactly 1 time
        assertEquals("Cache stampede detected! defaultValue ran more than once", 1, computeCounter.get())
        assertEquals(threadCount, results.size)
        results.values.forEach { assertEquals("audio-url-for-54321", it) }
    }

    @Test
    fun testUnrelatedKeysDoNotBlockDuringCompute() {
        val cache = UnmLruCache<String, String>(maxSize = 5)
        val executor = Executors.newFixedThreadPool(2)
        val computeStarted = CountDownLatch(1)
        val allowComputeToFinish = CountDownLatch(1)
        val key2Finished = CountDownLatch(1)

        // Thread 1 computes key1 slowly
        executor.execute {
            cache.getOrPut("key1") {
                computeStarted.countDown()
                allowComputeToFinish.await()
                "val1"
            }
        }

        computeStarted.await()

        // Thread 2 computes key2; must NOT be blocked by key1's lock
        var key2Result: String? = null
        executor.execute {
            key2Result = cache.getOrPut("key2") { "val2" }
            key2Finished.countDown()
        }

        assertTrue("Key 2 must complete quickly without waiting on Key 1", key2Finished.await(500, TimeUnit.MILLISECONDS))
        assertEquals("val2", key2Result)

        // Release Thread 1
        allowComputeToFinish.countDown()
        executor.shutdown()
    }

    @Test
    fun testGetOrPutExceptionPropagationAndNoCorruptedState() {
        val cache = UnmLruCache<String, String>(maxSize = 5)
        val callCount = AtomicInteger(0)

        // First attempt fails
        try {
            cache.getOrPut("errKey") {
                callCount.incrementAndGet()
                throw IllegalStateException("API connection refused")
            }
            fail("Should have thrown IllegalStateException")
        } catch (e: IllegalStateException) {
            assertEquals("API connection refused", e.message)
        }

        assertEquals(1, callCount.get())
        assertNull("Cache should not store null or failed entry", cache.get("errKey"))

        // Second attempt succeeds
        val recovered = cache.getOrPut("errKey") {
            callCount.incrementAndGet()
            "recovered-val"
        }
        assertEquals("recovered-val", recovered)
        assertEquals(2, callCount.get())
    }

    @Test
    fun testRecursiveGetOrPutFailsFastWithoutDeadlock() {
        val cache = UnmLruCache<String, String>(maxSize = 5)

        try {
            cache.getOrPut("recursiveKey") {
                cache.getOrPut("recursiveKey") { "nested" }
            }
            fail("Should have detected recursion")
        } catch (e: IllegalStateException) {
            assertTrue("Exception message should indicate recursion", e.message?.contains("Recursive getOrPut") == true)
        }
    }

    // --- Tier 4: Heavy Concurrent Stress ---

    @Test
    fun testHighContentionReadWriteStress() {
        val cache = UnmLruCache<Int, String>(maxSize = 20)
        val threadCount = 16
        val opsPerThread = 5000
        val executor = Executors.newFixedThreadPool(threadCount)
        val latch = CountDownLatch(threadCount)

        for (t in 0 until threadCount) {
            executor.execute {
                try {
                    for (i in 0 until opsPerThread) {
                        val key = (i % 50)
                        when (i % 4) {
                            0 -> cache.put(key, "val-$key")
                            1 -> cache.get(key)
                            2 -> cache.getOrPut(key) { "computed-$key" }
                            3 -> cache.remove(key)
                        }
                    }
                } finally {
                    latch.countDown()
                }
            }
        }

        assertTrue("Stress test must complete within 10s without deadlock", latch.await(10, TimeUnit.SECONDS))
        executor.shutdown()

        assertTrue("Cache size must never exceed maxSize", cache.size() <= 20)
    }
}
