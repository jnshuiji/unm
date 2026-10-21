package pochita.codec

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GzipCodecTest {

    // --- Tier 1: Happy Path ---

    @Test
    fun testIsGzipValidMagicBytes() {
        val validHeader = byteArrayOf(0x1f.toByte(), 0x8b.toByte(), 0x08, 0x00)
        assertTrue(GzipCodec.isGzip(validHeader))
    }

    @Test
    fun testCompressAndDecompressRoundTrip() {
        val originalText = """{"code":200,"msg":"ok","data":{"id":12345,"name":"song"}}"""
        val rawBytes = originalText.toByteArray(Charsets.UTF_8)

        val compressed = GzipCodec.compress(rawBytes)
        assertTrue(compressed.isNotEmpty())
        assertTrue(GzipCodec.isGzip(compressed))

        val decompressed = GzipCodec.decompress(compressed)
        assertArrayEquals(rawBytes, decompressed)
        assertEquals(originalText, String(decompressed, Charsets.UTF_8))
    }

    @Test
    fun testLargePayloadRoundTrip() {
        // Construct 256 KB repetitive JSON payload simulating a 1000-track playlist
        val sb = StringBuilder()
        sb.append("""{"code":200,"playlist":{"tracks":[""")
        for (i in 0 until 1000) {
            if (i > 0) sb.append(",")
            sb.append("""{"id":$i,"name":"Track Title Number $i","artist":"Artist Name $i","fee":0,"st":0}""")
        }
        sb.append("""]}}""")
        val jsonStr = sb.toString()
        val rawBytes = jsonStr.toByteArray(Charsets.UTF_8)
        assertTrue("Payload should be large (>50KB)", rawBytes.size > 50_000)

        val compressed = GzipCodec.compress(rawBytes)
        assertTrue("Compression should shrink size significantly", compressed.size < rawBytes.size / 2)
        assertTrue(GzipCodec.isGzip(compressed))

        val decompressed = GzipCodec.decompress(compressed)
        assertArrayEquals(rawBytes, decompressed)
        assertEquals(jsonStr, String(decompressed, Charsets.UTF_8))
    }

    @Test
    fun testCompressProducesGzipHeader() {
        val sample = "Hello NetEase UNM Gzip Codec".toByteArray(Charsets.UTF_8)
        val compressed = GzipCodec.compress(sample)

        assertTrue(compressed.size >= 10)
        assertEquals(0x1f.toByte(), compressed[0])
        assertEquals(0x8b.toByte(), compressed[1])
        assertTrue(GzipCodec.isGzip(compressed))
    }

    @Test
    fun testDecompressPlainBytesBypasses() {
        val plainJson = """{"code":200,"message":"plain"}""".toByteArray(Charsets.UTF_8)
        assertFalse(GzipCodec.isGzip(plainJson))

        val result = GzipCodec.decompress(plainJson)
        assertArrayEquals(plainJson, result)
    }

    // --- Tier 2: Boundary, Negative & Corrupted Cases ---

    @Test
    fun testIsGzipNullAndEmptySafe() {
        assertFalse(GzipCodec.isGzip(null))
        assertFalse(GzipCodec.isGzip(ByteArray(0)))
        assertFalse(GzipCodec.isGzip(byteArrayOf(0x1f.toByte())))
    }

    @Test
    fun testIsGzipWrongMagicBytes() {
        assertFalse(GzipCodec.isGzip(byteArrayOf(0x1f.toByte(), 0x00.toByte())))
        assertFalse(GzipCodec.isGzip(byteArrayOf(0x00.toByte(), 0x8b.toByte())))
        // zlib header 0x78 0x9c should return false
        assertFalse(GzipCodec.isGzip(byteArrayOf(0x78.toByte(), 0x9c.toByte())))
    }

    @Test
    fun testDecompressEmptyByteArray() {
        val result = GzipCodec.decompress(ByteArray(0))
        assertEquals(0, result.size)
    }

    @Test
    fun testDecompressTruncatedGzipStream() {
        // Valid 10-byte GZIP header followed by truncated/partial DEFLATE block
        val compressed = GzipCodec.compress("Some payload to compress".toByteArray(Charsets.UTF_8))
        // Truncate halfway through
        val truncated = compressed.copyOf(compressed.size / 2)
        assertTrue(GzipCodec.isGzip(truncated))

        // Must not throw EOFException or ZipException, returns truncated bytes
        val result = GzipCodec.decompress(truncated)
        assertArrayEquals(truncated, result)
    }

    @Test
    fun testDecompressCorruptedPayload() {
        // Valid magic bytes followed by garbage data
        val corrupted = byteArrayOf(0x1f.toByte(), 0x8b.toByte(), 0x08, 0x00, 0x55, 0xaa.toByte(), 0x12, 0x34)
        assertTrue(GzipCodec.isGzip(corrupted))

        val result = GzipCodec.decompress(corrupted)
        assertArrayEquals(corrupted, result)
    }

    @Test
    fun testDecompressCorruptedTrailerCrc() {
        val original = "Payload with valid deflate but broken trailer".toByteArray(Charsets.UTF_8)
        val compressed = GzipCodec.compress(original)
        assertTrue(compressed.size >= 8)

        // Invert the last byte (part of CRC or ISIZE)
        val corrupted = compressed.clone()
        corrupted[corrupted.size - 1] = (corrupted[corrupted.size - 1].toInt() xor 0xFF).toByte()

        // Must not throw ZipException: Corrupt GZIP trailer
        val result = GzipCodec.decompress(corrupted)
        assertNotNull(result)
    }

    @Test
    fun testCompressEmptyByteArray() {
        val empty = ByteArray(0)
        val result = GzipCodec.compress(empty)
        assertEquals(0, result.size)
    }
}
