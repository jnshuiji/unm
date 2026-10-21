package pochita.codec

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ProtocolCodecTest {

    private val targetEApiUrl = "https://interface.music.163.com/eapi/song/enhance/player/url/v1"
    private val targetXeApiUrl = "https://interface.music.163.com/xeapi/v1/playlist/detail"
    private val sampleJson = """{"code":200,"data":[{"id":12345,"url":"http://music.163.com/song.mp3"}]}"""

    // =========================================================================
    // Tier 1: Happy-Path Tests (>= 5 cases)
    // =========================================================================

    @Test
    fun testDecodePlainJsonResponse() {
        val plainBytes = sampleJson.toByteArray(Charsets.UTF_8)
        val decoded = ProtocolCodec.decodeResponse(targetEApiUrl, plainBytes)

        assertNotNull(decoded)
        assertEquals(sampleJson, decoded?.jsonString)
        assertFalse(decoded!!.isEncrypted)
        assertFalse(decoded.wasOuterGzip)
        assertFalse(decoded.wasInnerGzip)
    }

    @Test
    fun testDecodeGzipJsonResponse() {
        val plainBytes = sampleJson.toByteArray(Charsets.UTF_8)
        val gzipBytes = GzipCodec.compress(plainBytes)

        val decoded = ProtocolCodec.decodeResponse(targetEApiUrl, gzipBytes)

        assertNotNull(decoded)
        assertEquals(sampleJson, decoded?.jsonString)
        assertFalse(decoded!!.isEncrypted)
        assertTrue(decoded.wasOuterGzip)
        assertFalse(decoded.wasInnerGzip)
    }

    @Test
    fun testDecodeEApiEncryptedResponse() {
        val plainBytes = sampleJson.toByteArray(Charsets.UTF_8)
        val encryptedBytes = EApiCrypto.encrypt(plainBytes)

        val decoded = ProtocolCodec.decodeResponse(targetEApiUrl, encryptedBytes)

        assertNotNull(decoded)
        assertEquals(sampleJson, decoded?.jsonString)
        assertTrue(decoded!!.isEncrypted)
        assertFalse(decoded.wasOuterGzip)
        assertFalse(decoded.wasInnerGzip)
    }

    @Test
    fun testDecodeGzipEApiEncryptedResponse() {
        // XEAPI format: Outer GZIP containing AES-128 ciphertext
        val plainBytes = sampleJson.toByteArray(Charsets.UTF_8)
        val encryptedBytes = EApiCrypto.encrypt(plainBytes)
        val outerGzipBytes = GzipCodec.compress(encryptedBytes)

        val decoded = ProtocolCodec.decodeResponse(targetXeApiUrl, outerGzipBytes)

        assertNotNull(decoded)
        assertEquals(sampleJson, decoded?.jsonString)
        assertTrue(decoded!!.isEncrypted)
        assertTrue(decoded.wasOuterGzip)
        assertFalse(decoded.wasInnerGzip)
    }

    @Test
    fun testDecodeDoubleGzipEApiEncryptedResponse() {
        // Double-GZIP format: Outer GZIP + AES-128 + Inner GZIP
        val plainBytes = sampleJson.toByteArray(Charsets.UTF_8)
        val innerGzipBytes = GzipCodec.compress(plainBytes)
        val encryptedBytes = EApiCrypto.encrypt(innerGzipBytes)
        val outerGzipBytes = GzipCodec.compress(encryptedBytes)

        val decoded = ProtocolCodec.decodeResponse(targetEApiUrl, outerGzipBytes)

        assertNotNull(decoded)
        assertEquals(sampleJson, decoded?.jsonString)
        assertTrue(decoded!!.isEncrypted)
        assertTrue(decoded.wasOuterGzip)
        assertTrue(decoded.wasInnerGzip)
    }

    @Test
    fun testEncodeResponseSymmetry() {
        val originalJson = """{"code":200,"status":"original"}"""
        val modifiedJson = """{"code":200,"status":"modified-by-unm"}"""

        // Test with Double-GZIP format to ensure complete multi-layer symmetry
        val innerGzip = GzipCodec.compress(originalJson.toByteArray(Charsets.UTF_8))
        val encrypted = EApiCrypto.encrypt(innerGzip)
        val outerGzip = GzipCodec.compress(encrypted)

        val decoded = ProtocolCodec.decodeResponse(targetEApiUrl, outerGzip)
        assertNotNull(decoded)

        // Encode the modified JSON
        val encodedBytes = ProtocolCodec.encodeResponse(decoded!!, modifiedJson)

        // Decode the encoded bytes back; should match modifiedJson with identical flags
        val reDecoded = ProtocolCodec.decodeResponse(targetEApiUrl, encodedBytes)
        assertNotNull(reDecoded)
        assertEquals(modifiedJson, reDecoded?.jsonString)
        assertEquals(decoded.isEncrypted, reDecoded?.isEncrypted)
        assertEquals(decoded.wasOuterGzip, reDecoded?.wasOuterGzip)
        assertEquals(decoded.wasInnerGzip, reDecoded?.wasInnerGzip)
    }

    @Test
    fun testProjectContractMethods() {
        // Validate ProtocolCodec methods specifically documented in PROJECT.md
        val text = "Test Contract Verification"
        val cipherHex = ProtocolCodec.encryptEApiResponse(text)
        val decrypted = ProtocolCodec.decryptEApiResponse(cipherHex)
        assertEquals(text, decrypted)

        val raw = text.toByteArray(Charsets.UTF_8)
        val compressed = ProtocolCodec.compressGzip(raw)
        assertTrue(GzipCodec.isGzip(compressed))
        val decompressed = ProtocolCodec.decompressGzip(compressed)
        assertArrayEquals(raw, decompressed)
    }

    // =========================================================================
    // Tier 2: Boundary, Negative & Edge Cases
    // =========================================================================

    @Test
    fun testDecodeNonTargetUrlReturnsNull() {
        val imageJpg = "https://p1.music.126.net/cover.jpg"
        val musicMp3 = "https://m701.music.126.net/audio.mp3"
        val plainBytes = sampleJson.toByteArray(Charsets.UTF_8)

        assertNull(ProtocolCodec.decodeResponse(imageJpg, plainBytes))
        assertNull(ProtocolCodec.decodeResponse(musicMp3, plainBytes))
        assertFalse(ProtocolCodec.isTargetUrl(imageJpg))
        assertFalse(ProtocolCodec.isTargetUrl(musicMp3))
    }

    @Test
    fun testDecodeEmptyBodyReturnsNull() {
        assertNull(ProtocolCodec.decodeResponse(targetEApiUrl, ByteArray(0)))
    }

    @Test
    fun testDecodeCorruptedCiphertextReturnsNull() {
        // 32 bytes of non-decryptable noise (multiple of 16 so passes size check, but bad padding)
        val noise = ByteArray(32) { (it + 7).toByte() }
        val result = ProtocolCodec.decodeResponse(targetEApiUrl, noise)
        assertNull(result)
    }

    @Test
    fun testDecodeCorruptedGzipStreamGraceful() {
        // Magic bytes followed by garbage
        val badGzip = byteArrayOf(0x1f.toByte(), 0x8b.toByte(), 0x01, 0x02, 0x03)
        // Corrupted Gzip decompress returns raw bytes, which is non-target or non-JSON
        val result = ProtocolCodec.decodeResponse(targetEApiUrl, badGzip)
        // Handled gracefully without crash
        assertTrue(result == null || result.jsonString.isNotEmpty())
    }

    @Test
    fun testIsEApiEncryptedHeuristicEdgeCases() {
        // Not a multiple of 16 -> false
        assertFalse(ProtocolCodec.isEApiEncrypted(targetEApiUrl, ByteArray(15)))
        // Empty array -> false
        assertFalse(ProtocolCodec.isEApiEncrypted(targetEApiUrl, ByteArray(0)))

        // Multiple of 16 starting with '{' or '[' -> false (plain JSON)
        val jsonPadded = """{"code":200}    """.toByteArray(Charsets.UTF_8) // 16 bytes
        assertEquals(16, jsonPadded.size)
        assertFalse(ProtocolCodec.isEApiEncrypted(targetEApiUrl, jsonPadded))

        // Multiple of 16 with leading spaces before '{' -> false
        val spacedJson = """  {"a":1}       """.toByteArray(Charsets.UTF_8) // 16 bytes
        assertEquals(16, spacedJson.size)
        assertFalse(ProtocolCodec.isEApiEncrypted(targetEApiUrl, spacedJson))
    }

    @Test
    fun testEncodeModifiedJsonEmpty() {
        val encoded = ProtocolCodec.encodeResponse(
            url = targetEApiUrl,
            modifiedJson = "",
            isEncrypted = false,
            wasOuterGzip = false,
            wasInnerGzip = false
        )
        assertEquals(0, encoded.size)
    }
}
