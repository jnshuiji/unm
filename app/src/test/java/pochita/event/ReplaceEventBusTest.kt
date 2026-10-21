package pochita.event

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import pochita.model.ReplaceEvent
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class ReplaceEventBusTest {

    @Before
    fun setUp() {
        ReplaceEventBus.resetForTesting()
    }

    @After
    fun tearDown() {
        ReplaceEventBus.resetForTesting()
    }

    private fun sampleEvent(songId: Long = 1001L, title: String = "晴天") = ReplaceEvent(
        songId = songId,
        originalTitle = title,
        originalArtist = "周杰伦",
        matchedSource = "kuwo",
        matchedTitle = title,
        matchedArtist = "周杰伦",
        br = 320000
    )

    @Test
    fun testSingleSubscriberReceivesPublishedEvent() {
        var received: ReplaceEvent? = null
        ReplaceEventBus.subscribe { event ->
            received = event
        }

        val event = sampleEvent()
        ReplaceEventBus.publish(event)

        assertNotNull(received)
        assertEquals(1001L, received?.songId)
        assertEquals("晴天", received?.originalTitle)
        assertEquals("kuwo", received?.matchedSource)
    }

    @Test
    fun testMultipleSubscribersReceivePublishedEvent() {
        val list1 = mutableListOf<ReplaceEvent>()
        val list2 = mutableListOf<ReplaceEvent>()

        ReplaceEventBus.subscribe { list1.add(it) }
        ReplaceEventBus.subscribe { list2.add(it) }

        ReplaceEventBus.publish(sampleEvent(1L, "七里香"))
        ReplaceEventBus.publish(sampleEvent(2L, "东风破"))

        assertEquals(2, list1.size)
        assertEquals(2, list2.size)
        assertEquals("七里香", list1[0].originalTitle)
        assertEquals("东风破", list2[1].originalTitle)
    }

    @Test
    fun testUnsubscribeStopsEventDelivery() {
        val events = mutableListOf<ReplaceEvent>()
        val unsubscribe = ReplaceEventBus.subscribe { events.add(it) }

        ReplaceEventBus.publish(sampleEvent(1L, "夜曲"))
        assertEquals(1, events.size)

        unsubscribe()

        ReplaceEventBus.publish(sampleEvent(2L, "发如雪"))
        assertEquals("Unsubscribed listener must receive no further events", 1, events.size)
    }

    @Test
    fun testSubscriberCountReflectsRegistrations() {
        assertEquals(0, ReplaceEventBus.subscriberCount)
        val unsub1 = ReplaceEventBus.subscribe { }
        assertEquals(1, ReplaceEventBus.subscriberCount)
        val unsub2 = ReplaceEventBus.subscribe { }
        assertEquals(2, ReplaceEventBus.subscriberCount)

        unsub1()
        assertEquals(1, ReplaceEventBus.subscriberCount)
        unsub2()
        assertEquals(0, ReplaceEventBus.subscriberCount)
    }

    @Test
    fun testErrorIsolationDoesNotAffectOtherSubscribers() {
        val successfulEvents = mutableListOf<ReplaceEvent>()

        ReplaceEventBus.subscribe {
            throw RuntimeException("Simulated crashing subscriber A")
        }
        ReplaceEventBus.subscribe {
            successfulEvents.add(it)
        }
        ReplaceEventBus.subscribe {
            throw IllegalStateException("Simulated crashing subscriber B")
        }
        ReplaceEventBus.subscribe {
            successfulEvents.add(it)
        }

        // Publish must not throw exception to caller
        ReplaceEventBus.publish(sampleEvent(101L, "枫"))

        assertEquals("Healthy subscribers must still receive events despite failures in other subscribers",
            2, successfulEvents.size)
        assertEquals("枫", successfulEvents[0].originalTitle)
    }

    @Test
    fun testStickyEventDeliveryOnSubscribe() {
        assertNull(ReplaceEventBus.latestEvent)

        val priorEvent = sampleEvent(888L, "花海")
        ReplaceEventBus.publish(priorEvent)

        assertEquals(priorEvent, ReplaceEventBus.latestEvent)

        var stickyReceived: ReplaceEvent? = null
        ReplaceEventBus.subscribeSticky { event ->
            stickyReceived = event
        }

        assertNotNull("Sticky subscriber must immediately receive latest cached event", stickyReceived)
        assertEquals(888L, stickyReceived?.songId)
        assertEquals("花海", stickyReceived?.originalTitle)
    }

    @Test
    fun testSubscribeStickyWhenNoPriorEventDoesNotFireImmediately() {
        var stickyReceived: ReplaceEvent? = null
        ReplaceEventBus.subscribeSticky { event ->
            stickyReceived = event
        }

        assertNull("Sticky subscriber must not fire when latestEvent is null", stickyReceived)

        ReplaceEventBus.publish(sampleEvent(999L, "告白气球"))
        assertNotNull(stickyReceived)
        assertEquals("告白气球", stickyReceived?.originalTitle)
    }

    @Test
    fun testClearLatestEvent() {
        ReplaceEventBus.publish(sampleEvent())
        assertNotNull(ReplaceEventBus.latestEvent)

        ReplaceEventBus.clearLatestEvent()
        assertNull(ReplaceEventBus.latestEvent)
    }

    @Test
    fun testConcurrentPublishAndSubscribeWithoutDeadlock() {
        val threadCount = 8
        val eventsPerThread = 50
        val executor = Executors.newFixedThreadPool(threadCount)
        val latch = CountDownLatch(threadCount)
        val receivedCount = AtomicInteger(0)

        ReplaceEventBus.subscribe {
            receivedCount.incrementAndGet()
        }

        for (i in 0 until threadCount) {
            executor.submit {
                try {
                    for (j in 0 until eventsPerThread) {
                        val unreg = ReplaceEventBus.subscribe { }
                        ReplaceEventBus.publish(sampleEvent(i.toLong() * 1000 + j, "Track-$i-$j"))
                        unreg()
                    }
                } finally {
                    latch.countDown()
                }
            }
        }

        assertTrue("Execution must complete within 5 seconds", latch.await(5, TimeUnit.SECONDS))
        executor.shutdown()
        assertEquals(threadCount * eventsPerThread, receivedCount.get())
    }
}
