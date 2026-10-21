package pochita.hook

import java.io.ByteArrayInputStream
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class CronetInterceptorTest {

    private class FakeHttpURLConnection(
        url: URL,
        private val headerMap: MutableMap<String?, List<String>> = LinkedHashMap()
    ) : HttpURLConnection(url) {
        override fun connect() {}
        override fun disconnect() {}
        override fun usingProxy(): Boolean = false
        override fun getHeaderFields(): Map<String?, List<String>> = headerMap
        override fun getHeaderField(name: String?): String? = headerMap[name]?.firstOrNull()
        override fun getContentLength(): Int = headerMap["Content-Length"]?.firstOrNull()?.toIntOrNull() ?: -1
        override fun getContentLengthLong(): Long = headerMap["Content-Length"]?.firstOrNull()?.toLongOrNull() ?: -1L
    }

    private class TrackableInputStream(private val delegate: InputStream) : InputStream() {
        var isClosed = false
        override fun read(): Int = delegate.read()
        override fun read(b: ByteArray, off: Int, len: Int): Int = delegate.read(b, off, len)
        override fun close() {
            isClosed = true
            delegate.close()
        }
    }

    @Before
    fun setUp() {
        CronetInterceptor.clearTrackedConnections()
    }

    @Test
    fun testInterceptInputStreamUnmodified() {
        val url = URL("https://interface.music.163.com/eapi/v3/playlist/detail")
        val conn = FakeHttpURLConnection(url)
        val originalBytes = "Original Data Payload".toByteArray(Charsets.UTF_8)
        val stream = ByteArrayInputStream(originalBytes)

        val resultStream = CronetInterceptor.interceptInputStream(conn, stream) { _, _ -> null }

        assertNotNull(resultStream)
        assertArrayEquals(originalBytes, resultStream?.readBytes())
        assertEquals(-1, CronetInterceptor.interceptContentLength(conn, -1))
    }

    @Test
    fun testInterceptInputStreamModifiedSynchronizesLengthAndHeaders() {
        val url = URL("https://interface.music.163.com/eapi/v3/playlist/detail")
        val headers = mutableMapOf<String?, List<String>>(
            "Content-Length" to listOf("50"),
            "Server" to listOf("Nginx")
        )
        val conn = FakeHttpURLConnection(url, headers)
        val originalBytes = "Original Data Payload".toByteArray(Charsets.UTF_8)
        val modifiedBytes = "Modified By UNM Decoupled Hook Engine".toByteArray(Charsets.UTF_8)
        val stream = ByteArrayInputStream(originalBytes)

        val resultStream = CronetInterceptor.interceptInputStream(conn, stream) { _, _ -> modifiedBytes }

        assertNotNull(resultStream)
        assertArrayEquals(modifiedBytes, resultStream?.readBytes())

        // Verify Content-Length synchronization
        assertEquals(modifiedBytes.size, CronetInterceptor.interceptContentLength(conn, 50))
        assertEquals(modifiedBytes.size.toLong(), CronetInterceptor.interceptContentLengthLong(conn, 50L))
        assertEquals(modifiedBytes.size.toString(), CronetInterceptor.interceptHeaderField(conn, "Content-Length", "50"))
        assertEquals(modifiedBytes.size.toString(), CronetInterceptor.interceptHeaderField(conn, "content-length", "50"))

        // Verify getHeaderFields() map updating
        val updatedHeaders = CronetInterceptor.interceptHeaderFields(conn, headers)
        assertNotNull(updatedHeaders)
        assertEquals(listOf(modifiedBytes.size.toString()), updatedHeaders?.get("Content-Length"))
        assertEquals(listOf("Nginx"), updatedHeaders?.get("Server"))
    }

    @Test
    fun testInterceptInputStreamNonTargetUrlUntouched() {
        val url = URL("https://p1.music.126.net/api/cover.jpg")
        val conn = FakeHttpURLConnection(url)
        val originalBytes = "image bytes".toByteArray()
        val stream = ByteArrayInputStream(originalBytes)

        var processorCalled = false
        val resultStream = CronetInterceptor.interceptInputStream(conn, stream) { _, _ ->
            processorCalled = true
            null
        }

        assertFalse(processorCalled)
        assertEquals(stream, resultStream)
    }

    @Test
    fun testCleanStreamLifecycleClosePropagation() {
        val url = URL("https://interface.music.163.com/eapi/v3/playlist/detail")
        val conn = FakeHttpURLConnection(url)
        val raw = TrackableInputStream(ByteArrayInputStream("Payload".toByteArray()))

        val intercepted = CronetInterceptor.interceptInputStream(conn, raw) { _, bytes -> bytes }
        assertNotNull(intercepted)
        intercepted?.close()

        // Underlying native stream must be closed when replaced stream is closed
        assertTrue(raw.isClosed)
    }

    @Test
    fun testExceptionIsolationInInputStream() {
        val url = URL("https://interface.music.163.com/eapi/v3/playlist/detail")
        val conn = FakeHttpURLConnection(url)
        val originalBytes = "Fallback Content".toByteArray()
        val stream = ByteArrayInputStream(originalBytes)

        val resultStream = CronetInterceptor.interceptInputStream(conn, stream) { _, _ ->
            throw RuntimeException("Simulated processor explosion")
        }

        // Must fall back to original bytes and not throw
        assertNotNull(resultStream)
        assertArrayEquals(originalBytes, resultStream?.readBytes())
    }
}
