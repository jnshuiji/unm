package pochita.common

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class UnmLoggerTest {

    private data class LogEvent(
        val level: Int,
        val tag: String,
        val message: String,
        val throwable: Throwable?
    )

    private val capturedLogs = ConcurrentLinkedQueue<LogEvent>()

    @Before
    fun setUp() {
        capturedLogs.clear()
        UnmLogger.setAdapter { level, tag, message, throwable ->
            capturedLogs.add(LogEvent(level, tag, message, throwable))
        }
    }

    @After
    fun tearDown() {
        UnmLogger.resetAdapter()
    }

    // --- Tier 1: Happy Path ---

    @Test
    fun testLevelDispatching() {
        UnmLogger.v("TestTag", "Verbose message")
        UnmLogger.d("TestTag", "Debug message")
        UnmLogger.i("TestTag", "Info message")
        UnmLogger.w("TestTag", "Warn message")
        UnmLogger.e("TestTag", "Error message")

        val logs = capturedLogs.toList()
        assertEquals(5, logs.size)

        assertEquals(UnmLogger.LEVEL_VERBOSE, logs[0].level)
        assertEquals("Verbose message", logs[0].message)

        assertEquals(UnmLogger.LEVEL_DEBUG, logs[1].level)
        assertEquals("Debug message", logs[1].message)

        assertEquals(UnmLogger.LEVEL_INFO, logs[2].level)
        assertEquals("Info message", logs[2].message)

        assertEquals(UnmLogger.LEVEL_WARN, logs[3].level)
        assertEquals("Warn message", logs[3].message)

        assertEquals(UnmLogger.LEVEL_ERROR, logs[4].level)
        assertEquals("Error message", logs[4].message)
    }

    @Test
    fun testThrowablePropagation() {
        val testEx = RuntimeException("Crypto decoding failed")
        UnmLogger.w("CryptoTag", "Malformed payload", testEx)
        UnmLogger.e("CryptoTag", "Fatal decryption crash", testEx)

        val logs = capturedLogs.toList()
        assertEquals(2, logs.size)
        assertEquals(testEx, logs[0].throwable)
        assertEquals(testEx, logs[1].throwable)
    }

    @Test
    fun testConsoleAdapterRouting() {
        val outBytes = ByteArrayOutputStream()
        val errBytes = ByteArrayOutputStream()
        val outStream = PrintStream(outBytes)
        val errStream = PrintStream(errBytes)

        val adapter = UnmLogger.ConsoleAdapter(outStream, errStream, minLevel = UnmLogger.LEVEL_DEBUG)
        UnmLogger.setAdapter(adapter)

        UnmLogger.d("TestTag", "Stdout message")
        UnmLogger.e("TestTag", "Stderr message")

        val outStr = outBytes.toString("UTF-8")
        val errStr = errBytes.toString("UTF-8")

        assertTrue("Stdout should contain debug log", outStr.contains("[D][TestTag]"))
        assertTrue("Stdout should contain message", outStr.contains("Stdout message"))
        assertTrue("Stderr should contain error log", errStr.contains("[E][TestTag]"))
        assertTrue("Stderr should contain message", errStr.contains("Stderr message"))
    }

    @Test
    fun testConsoleAdapterMinLevelFiltering() {
        val outBytes = ByteArrayOutputStream()
        val errBytes = ByteArrayOutputStream()
        val outStream = PrintStream(outBytes)
        val errStream = PrintStream(errBytes)

        val adapter = UnmLogger.ConsoleAdapter(outStream, errStream, minLevel = UnmLogger.LEVEL_INFO)
        UnmLogger.setAdapter(adapter)

        UnmLogger.v("TestTag", "Verbose dropped")
        UnmLogger.d("TestTag", "Debug dropped")
        UnmLogger.i("TestTag", "Info logged")

        val outStr = outBytes.toString("UTF-8")
        assertTrue(!outStr.contains("Verbose dropped"))
        assertTrue(!outStr.contains("Debug dropped"))
        assertTrue(outStr.contains("Info logged"))
    }

    @Test
    fun testGetAndResetAdapter() {
        val customAdapter = object : UnmLogger.Adapter {
            override fun log(level: Int, tag: String, message: String, throwable: Throwable?) {}
        }
        UnmLogger.setAdapter(customAdapter)
        assertEquals(customAdapter, UnmLogger.getAdapter())

        UnmLogger.resetAdapter()
        assertTrue(UnmLogger.getAdapter() is UnmLogger.ConsoleAdapter)
    }

    // --- Tier 2: Boundary & Corner Cases ---

    @Test
    fun testSilentErrorSuppressionWhenAdapterThrowsRuntimeException() {
        UnmLogger.setAdapter { _, _, _, _ ->
            throw RuntimeException("Faulty adapter internal explosion")
        }

        // Must complete without throwing any exception
        UnmLogger.d("CrashTag", "Should not throw")
        UnmLogger.i("CrashTag", "Should not throw")
        UnmLogger.w("CrashTag", "Should not throw")
        UnmLogger.e("CrashTag", "Should not throw")
    }

    @Test
    fun testSilentErrorSuppressionWhenAdapterThrowsError() {
        UnmLogger.setAdapter { _, _, _, _ ->
            throw AssertionError("Severe assertion failure inside adapter")
        }

        UnmLogger.e("ErrorTag", "Should not propagate Error")
    }

    @Test
    fun testNullThrowableSafety() {
        UnmLogger.w("Tag", "Warn without throwable", null)
        UnmLogger.e("Tag", "Error without throwable", null)

        val logs = capturedLogs.toList()
        assertEquals(2, logs.size)
        assertNull(logs[0].throwable)
        assertNull(logs[1].throwable)
    }

    @Test
    fun testEmptyStrings() {
        UnmLogger.d("", "")
        val log = capturedLogs.poll()
        assertNotNull(log)
        assertEquals("", log!!.tag)
        assertEquals("", log.message)
    }

    // --- Tier 3: Concurrency & Stress ---

    @Test
    fun testConcurrentLoggingUnderThreadContention() {
        val threadCount = 16
        val logsPerThread = 500
        val executor = Executors.newFixedThreadPool(threadCount)
        val latch = CountDownLatch(threadCount)

        for (t in 0 until threadCount) {
            executor.execute {
                try {
                    for (i in 0 until logsPerThread) {
                        UnmLogger.i("Thread-$t", "Log entry #$i")
                    }
                } finally {
                    latch.countDown()
                }
            }
        }

        assertTrue("All threads should finish within 5s", latch.await(5, TimeUnit.SECONDS))
        executor.shutdown()
        assertEquals(threadCount * logsPerThread, capturedLogs.size)
    }

    @Test
    fun testConcurrentAdapterSwapping() {
        val threadCount = 8
        val executor = Executors.newFixedThreadPool(threadCount)
        val latch = CountDownLatch(threadCount)
        val counter = AtomicInteger(0)

        // Worker logging threads
        for (t in 0 until threadCount - 1) {
            executor.execute {
                try {
                    for (i in 0 until 1000) {
                        UnmLogger.d("SwapTest", "Message $i")
                    }
                } finally {
                    latch.countDown()
                }
            }
        }

        // Adapter swapper thread
        executor.execute {
            try {
                for (i in 0 until 100) {
                    UnmLogger.setAdapter { _, _, _, _ -> counter.incrementAndGet() }
                    UnmLogger.resetAdapter()
                }
            } finally {
                latch.countDown()
            }
        }

        assertTrue("Swapping under load should not deadlock or crash", latch.await(5, TimeUnit.SECONDS))
        executor.shutdown()
    }
}
