package pochita.event

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import pochita.model.ReplaceEvent
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * Empirical Adversarial Challenger Test for [ReplaceEventBus].
 *
 * Focus areas:
 * 1. 100+ concurrent publishers and subscribers (massive concurrency stress).
 * 2. Subscriber exception resilience (Throwables, RuntimeExceptions, Errors do not impede other subscribers or publishers).
 * 3. Sticky event race conditions and re-entrancy.
 * 4. High churn dynamic subscribe / unsubscribe lifecycle safety.
 */
class ReplaceEventBusEmpiricalChallengerTest {

    @Before
    fun setUp() {
        ReplaceEventBus.resetForTesting()
    }

    @After
    fun tearDown() {
        ReplaceEventBus.resetForTesting()
    }

    private fun sampleEvent(songId: Long, title: String = "Track-$songId") = ReplaceEvent(
        songId = songId,
        originalTitle = title,
        originalArtist = "Artist-$songId",
        matchedSource = "kuwo",
        matchedTitle = title,
        matchedArtist = "Artist-$songId",
        br = 320000
    )

    @Test
    fun test100ConcurrentPublishersAndSubscribers() {
        val publisherCount = 100
        val eventsPerPublisher = 30
        val totalExpectedEvents = publisherCount * eventsPerPublisher

        val subscriberCount = 100
        val totalThreads = publisherCount + subscriberCount

        val executor = Executors.newFixedThreadPool(totalThreads)
        val startLatch = CountDownLatch(1)
        val doneLatch = CountDownLatch(totalThreads)

        // Master subscriber to capture all events
        val masterReceivedCount = AtomicInteger(0)
        ReplaceEventBus.subscribe {
            masterReceivedCount.incrementAndGet()
        }

        // 100 concurrent subscribers that churn (subscribe, wait, unsubscribe)
        val ephemeralEventCount = AtomicInteger(0)
        for (s in 0 until subscriberCount) {
            executor.submit {
                try {
                    startLatch.await(5, TimeUnit.SECONDS)
                    for (loop in 0 until 5) {
                        val unreg = ReplaceEventBus.subscribe {
                            ephemeralEventCount.incrementAndGet()
                        }
                        Thread.yield()
                        unreg()
                    }
                } finally {
                    doneLatch.countDown()
                }
            }
        }

        // 100 concurrent publishers
        for (p in 0 until publisherCount) {
            val pubId = p
            executor.submit {
                try {
                    startLatch.await(5, TimeUnit.SECONDS)
                    for (e in 0 until eventsPerPublisher) {
                        val songId = (pubId * 1000L) + e
                        ReplaceEventBus.publish(sampleEvent(songId, "Song-$pubId-$e"))
                    }
                } finally {
                    doneLatch.countDown()
                }
            }
        }

        startLatch.countDown()
        assertTrue("All 200 threads must complete within 15 seconds without deadlock",
            doneLatch.await(15, TimeUnit.SECONDS))
        executor.shutdown()

        assertEquals("Master subscriber must observe every single published event",
            totalExpectedEvents, masterReceivedCount.get())
        assertNotNull("Latest event must not be null after publishers finish",
            ReplaceEventBus.latestEvent)
    }

    @Test
    fun testSubscriberExceptionResilienceNeverImpairsOtherSubscribers() {
        val totalSubscribers = 20
        val successfulCounts = ConcurrentHashMap<Int, AtomicInteger>()

        // Alternating throwing and healthy subscribers
        for (i in 0 until totalSubscribers) {
            val index = i
            when (index % 4) {
                0 -> ReplaceEventBus.subscribe {
                    throw RuntimeException("Simulated RuntimeException at subscriber $index")
                }
                1 -> ReplaceEventBus.subscribe {
                    throw AssertionError("Simulated AssertionError at subscriber $index")
                }
                2 -> ReplaceEventBus.subscribe {
                    throw NullPointerException("Simulated NPE at subscriber $index")
                }
                3 -> {
                    successfulCounts[index] = AtomicInteger(0)
                    ReplaceEventBus.subscribe {
                        successfulCounts[index]!!.incrementAndGet()
                    }
                }
            }
        }

        // Publish 50 events
        for (i in 1..50) {
            ReplaceEventBus.publish(sampleEvent(i.toLong(), "Title-$i"))
        }

        val healthySubscribers = successfulCounts.keys
        assertEquals("There should be 5 healthy subscribers (20 / 4)", 5, healthySubscribers.size)
        for (index in healthySubscribers) {
            assertEquals("Healthy subscriber $index must receive all 50 events regardless of throwing peers",
                50, successfulCounts[index]!!.get())
        }
    }

    @Test
    fun testSubscribeStickyExceptionResilience() {
        ReplaceEventBus.publish(sampleEvent(999L, "StickyTitle"))

        var unreg: (() -> Unit)? = null
        // Should not throw out of subscribeSticky
        unreg = ReplaceEventBus.subscribeSticky {
            throw IllegalStateException("Failure in sticky callback")
        }

        assertNotNull("subscribeSticky must return an unregister lambda even if listener throws", unreg)
        // Ensure unregistering works
        unreg()
    }

    @Test
    fun testHighChurnSubscribeAndUnsubscribeConsistency() {
        val threadCount = 64
        val iterationsPerThread = 100
        val executor = Executors.newFixedThreadPool(threadCount)
        val startLatch = CountDownLatch(1)
        val doneLatch = CountDownLatch(threadCount)

        for (t in 0 until threadCount) {
            executor.submit {
                try {
                    startLatch.await(5, TimeUnit.SECONDS)
                    for (i in 0 until iterationsPerThread) {
                        val unsub = ReplaceEventBus.subscribe { }
                        ReplaceEventBus.publish(sampleEvent(100L))
                        unsub()
                    }
                } finally {
                    doneLatch.countDown()
                }
            }
        }

        startLatch.countDown()
        assertTrue("Churn test completed in time", doneLatch.await(10, TimeUnit.SECONDS))
        executor.shutdown()

        assertEquals("After all threads unsubscribe, subscriberCount must return to 0",
            0, ReplaceEventBus.subscriberCount)
    }

    @Test
    fun testReentrantEventPublishingSafety() {
        val recordedEvents = CopyOnWriteArrayList<String>()
        val maxDepth = 3

        ReplaceEventBus.subscribe { event ->
            recordedEvents.add(event.originalTitle)
            val currentDepth = event.songId.toInt()
            if (currentDepth < maxDepth) {
                // Reentrant publication
                ReplaceEventBus.publish(sampleEvent((currentDepth + 1).toLong(), "Reentrant-$currentDepth"))
            }
        }

        ReplaceEventBus.publish(sampleEvent(1L, "RootEvent"))

        assertTrue("RootEvent should be recorded", recordedEvents.contains("RootEvent"))
        assertTrue("Reentrant-1 should be recorded", recordedEvents.contains("Reentrant-1"))
        assertTrue("Reentrant-2 should be recorded", recordedEvents.contains("Reentrant-2"))
    }
}
