package pochita.codec

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class EApiCryptoTest {

    // --- Tier 1: Happy Path ---

    @Test
    fun testAesEncryptDecryptRoundTrip() {
        val originalText = """{"ids":["123456"],"level":"standard"}"""
        val encryptedHex = EApiCrypto.encryptHex(originalText)
        val decryptedText = EApiCrypto.decryptHex(encryptedHex)
        assertEquals(originalText, decryptedText)
    }

    @Test
    fun testRawByteEncryptDecryptRoundTrip() {
        val originalBytes = "Direct raw bytes for AES encryption test".toByteArray(Charsets.UTF_8)
        val encrypted = EApiCrypto.encrypt(originalBytes)
        val decrypted = EApiCrypto.decrypt(encrypted)
        assertArrayEquals(originalBytes, decrypted)
    }

    @Test
    fun testMd5StandardVector() {
        val hash = EApiCrypto.md5("nobody/api/song/enhance/player/urluse{}md5forencrypt")
        assertNotNull(hash)
        assertEquals(32, hash.length)
        // Verify lowercase hex
        assertTrue(hash.all { it in '0'..'9' || it in 'a'..'f' })
    }

    @Test
    fun testParseEApiRequestBodyWithParams() {
        val path = "/api/song/enhance/player/url/v1"
        val jsonStr = """{"ids":"[123456]","level":"lossless"}"""
        val md5 = EApiCrypto.md5(jsonStr)
        val payload = "$path${EApiCrypto.EAPI_DELIMITER}$jsonStr${EApiCrypto.EAPI_DELIMITER}$md5"

        val hexBody = "params=" + EApiCrypto.encryptHex(payload) + "&extra=123"
        val parsed = EApiCrypto.parseEApiRequestBody(hexBody)

        assertNotNull(parsed)
        assertEquals(path, parsed?.first)
        assertTrue(parsed?.second?.has("ids") == true)
        assertEquals("[123456]", parsed?.second?.optString("ids"))
    }

    @Test
    fun testParseEApiRequestBodyWithoutParamsPrefix() {
        val path = "/api/v3/song/detail"
        val jsonStr = """{"c":"[{\"id\":999}]"}"""
        val md5 = EApiCrypto.md5(jsonStr)
        val payload = "$path${EApiCrypto.EAPI_DELIMITER}$jsonStr${EApiCrypto.EAPI_DELIMITER}$md5"

        val rawHex = EApiCrypto.encryptHex(payload)
        val parsed = EApiCrypto.parseEApiRequestBody(rawHex)

        assertNotNull(parsed)
        assertEquals(path, parsed?.first)
        assertEquals("""[{"id":999}]""", parsed?.second?.optString("c"))
    }

    @Test
    fun testBuildEApiRequestBodyRoundTrip() {
        val path = "/api/song/enhance/player/url"
        val jsonStr = """{"id":54321,"bitrate":320000}"""

        val requestBody = EApiCrypto.buildEApiRequestBody(path, jsonStr)
        assertTrue(requestBody.startsWith("params="))

        val parsed = EApiCrypto.parseEApiRequestBody(requestBody)
        assertNotNull(parsed)
        assertEquals(path, parsed?.first)
        assertEquals(54321, parsed?.second?.optInt("id"))
        assertEquals(320000, parsed?.second?.optInt("bitrate"))
    }

    // --- Tier 2: Boundary & Corrupted Inputs ---

    @Test
    fun testDecryptHexMalformedOddLength() {
        // Odd length hex string cannot be valid byte sequence
        val oddHex = "12345"
        val result = EApiCrypto.decryptHex(oddHex)
        assertNull(result)
    }

    @Test
    fun testDecryptHexNonHexCharacters() {
        // Hex containing invalid non-hex letters like G, H, Z
        val invalidHex = "0123456789ABCDEFGGHH"
        val result = EApiCrypto.decryptHex(invalidHex)
        assertNull(result)
    }

    @Test
    fun testDecryptCorruptedCiphertextReturnsNull() {
        // 32 random bytes with invalid PKCS5 padding
        val randomNoise = ByteArray(32) { (it * 37 + 13).toByte() }
        val result = EApiCrypto.decryptOrNull(randomNoise)
        assertNull(result)
    }

    @Test
    fun testDecryptInvalidBlockSizeReturnsNull() {
        // 17 bytes: not a multiple of 16 (AES block size)
        val nonBlockSize = ByteArray(17) { 0x01 }
        val result = EApiCrypto.decryptOrNull(nonBlockSize)
        assertNull(result)
    }

    @Test
    fun testParseEApiRequestBodyMissingDelimiter() {
        // Payload lacks the standard -36cd479b6b5- delimiter
        val invalidPayload = "JustAPlainStringWithoutDelimiter"
        val hexBody = "params=" + EApiCrypto.encryptHex(invalidPayload)
        val parsed = EApiCrypto.parseEApiRequestBody(hexBody)
        assertNull(parsed)
    }

    @Test
    fun testParseEApiRequestBodyInvalidJsonBody() {
        // Valid delimiter but JSON is broken
        val payload = "/api/path${EApiCrypto.EAPI_DELIMITER}NotAValidJson{${EApiCrypto.EAPI_DELIMITER}digest"
        val hexBody = "params=" + EApiCrypto.encryptHex(payload)
        val parsed = EApiCrypto.parseEApiRequestBody(hexBody)
        assertNull(parsed)
    }

    @Suppress("DEPRECATION")
    @Test
    fun testLegacyCryptoPackageCompatibility() {
        // Verify pochita.crypto.EApiCrypto delegates accurately to pochita.codec.EApiCrypto
        val text = """{"test":"legacy bridge compatibility"}"""
        val hexFromCodec = EApiCrypto.encryptHex(text)
        val hexFromLegacy = pochita.crypto.EApiCrypto.encryptHex(text)
        assertEquals(hexFromCodec, hexFromLegacy)

        val decryptedFromLegacy = pochita.crypto.EApiCrypto.decryptHex(hexFromCodec)
        assertEquals(text, decryptedFromLegacy)

        val md5FromCodec = EApiCrypto.md5(text)
        val md5FromLegacy = pochita.crypto.EApiCrypto.md5(text)
        assertEquals(md5FromCodec, md5FromLegacy)
    }
}
