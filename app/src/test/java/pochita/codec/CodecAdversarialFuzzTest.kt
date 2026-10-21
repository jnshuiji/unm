package pochita.codec

import java.io.ByteArrayOutputStream
import java.security.SecureRandom
import java.util.Random
import java.util.zip.GZIPOutputStream
import javax.crypto.Cipher
import javax.crypto.spec.SecretKeySpec
import org.json.JSONObject
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * Adversarial fuzzing and empirical stress test suite for:
 * - [GzipCodec]
 * - [EApiCrypto]
 * - [ProtocolCodec]
 *
 * Covers:
 * - Truncated, corrupted, zero-byte, huge (megabytes), random noise streams
 * - Malformed hex strings (odd lengths, invalid chars, unicode, symbols, whitespace)
 * - Bad PKCS5 padding, non-block-size AES payloads
 * - Full multi-layer symmetrical roundtrip across all protocol combinations
 */
class CodecAdversarialFuzzTest {

    private val targetEApiUrl = "https://interface.music.163.com/eapi/song/enhance/player/url/v1"
    private val targetXeApiUrl = "https://interface.music.163.com/xeapi/v1/playlist/detail"
    private val rng = Random(42_1337) // Deterministic seed for reproducible fuzzing

    // =========================================================================
    // SECTION 1: GzipCodec Adversarial Fuzzing & Stress Tests
    // =========================================================================

    @Test
    fun fuzzGzipDecompress_truncatedStreamsAtEveryByteOffset() {
        val sampleJson = """{"code":200,"message":"fuzzing truncated gzip streams","data":[1,2,3,4,5]}"""
        val compressed = GzipCodec.compress(sampleJson.toByteArray(Charsets.UTF_8))
        assertTrue("Compressed data should have length > 20", compressed.size > 20)

        // Truncate at EVERY single byte index from 0 to compressed.size - 1
        for (truncateLen in 0 until compressed.size) {
            val truncated = compressed.copyOf(truncateLen)
            try {
                val result = GzipCodec.decompress(truncated)
                assertNotNull("Result should never be null on truncation at len $truncateLen", result)
                if (truncateLen < 2 || !GzipCodec.isGzip(truncated)) {
                    assertArrayEquals("Non-gzip or <2 bytes must return exact input", truncated, result)
                } else {
                    // Truncated gzip stream must fall back gracefully to raw bytes
                    assertArrayEquals("Truncated gzip must fall back to raw input bytes", truncated, result)
                }
            } catch (t: Throwable) {
                fail("GzipCodec.decompress threw uncaught exception on truncateLen $truncateLen: ${t.javaClass.name}: ${t.message}")
            }
        }
    }

    @Test
    fun fuzzGzipDecompress_corruptedBitFlipsInPayload() {
        val originalText = """{"status":"ok","playlist":{"id":9999,"name":"Adversarial Playlist Fuzzing"}}"""
        val validGzip = GzipCodec.compress(originalText.toByteArray(Charsets.UTF_8))

        // Perform 200 randomized single-byte and multi-byte corruptions
        for (i in 0 until 200) {
            val corrupted = validGzip.clone()
            // Random corruption index (excluding magic bytes to test payload & trailer corruption)
            val corruptPos = 2 + rng.nextInt(validGzip.size - 2)
            corrupted[corruptPos] = (corrupted[corruptPos].toInt() xor (rng.nextInt(255) + 1)).toByte()

            try {
                val result = GzipCodec.decompress(corrupted)
                assertNotNull("Decompressed result should not be null", result)
                // If it fails decompression, it should safely return the input byte array
                assertTrue("Result must have bytes", result.isNotEmpty())
            } catch (t: Throwable) {
                fail("GzipCodec.decompress crashed on corruption at index $corruptPos: ${t.message}")
            }
        }
    }

    @Test
    fun fuzzGzipDecompress_randomByteNoise() {
        // Fuzz with 300 random byte arrays of various lengths
        for (i in 0 until 300) {
            val len = rng.nextInt(4096)
            val noise = ByteArray(len)
            rng.nextBytes(noise)

            // Half of the time, force valid GZIP magic bytes at the beginning
            if (i % 2 == 0 && len >= 2) {
                noise[0] = GzipCodec.GZIP_MAGIC_0
                noise[1] = GzipCodec.GZIP_MAGIC_1
            }

            try {
                val result = GzipCodec.decompress(noise)
                assertNotNull(result)
            } catch (t: Throwable) {
                fail("GzipCodec.decompress crashed on random noise of length $len: ${t.message}")
            }
        }
    }

    @Test
    fun stressGzip_multiMegabytePayloadRoundTrip() {
        // Construct 4 MB of compressible repetitive JSON
        val chunk = """{"id":12345,"title":"Stress Testing Gzip Stream MultiMegabyte Payload","bitrate":320000},"""
        val repeats = 40_000 // ~3.5 MB
        val sb = StringBuilder(repeats * chunk.length + 32)
        sb.append("""{"tracks":[""")
        for (i in 0 until repeats) {
            sb.append(chunk)
        }
        sb.append("""{"id":0,"title":"end"}]}""")
        val largeJson = sb.toString()
        val rawBytes = largeJson.toByteArray(Charsets.UTF_8)
        assertTrue("Raw bytes should be > 2MB", rawBytes.size > 2_000_000)

        // Compress
        val compressed = GzipCodec.compress(rawBytes)
        assertTrue("Compression should achieve high ratio (> 10x)", compressed.size < rawBytes.size / 10)
        assertTrue(GzipCodec.isGzip(compressed))

        // Decompress
        val decompressed = GzipCodec.decompress(compressed)
        assertArrayEquals(rawBytes, decompressed)
        assertEquals(largeJson.length, String(decompressed, Charsets.UTF_8).length)
    }

    @Test
    fun stressGzip_highEntropyRandomBytes() {
        // 256 KB of cryptographically random non-compressible bytes
        val randomBytes = ByteArray(256 * 1024)
        SecureRandom().nextBytes(randomBytes)

        val compressed = GzipCodec.compress(randomBytes)
        assertTrue(GzipCodec.isGzip(compressed))

        val decompressed = GzipCodec.decompress(compressed)
        assertArrayEquals(randomBytes, decompressed)
    }

    @Test
    fun testGzipHeaderEdgeCases() {
        // 1. Exactly 2 bytes matching magic bytes (incomplete header)
        val twoBytes = byteArrayOf(0x1f.toByte(), 0x8b.toByte())
        assertTrue(GzipCodec.isGzip(twoBytes))
        val decomp2 = GzipCodec.decompress(twoBytes)
        assertArrayEquals(twoBytes, decomp2)

        // 2. 9 bytes with magic bytes (header requires 10 bytes)
        val nineBytes = byteArrayOf(0x1f.toByte(), 0x8b.toByte(), 8, 0, 0, 0, 0, 0, 0)
        assertTrue(GzipCodec.isGzip(nineBytes))
        val decomp9 = GzipCodec.decompress(nineBytes)
        assertArrayEquals(nineBytes, decomp9)

        // 3. Flags set (e.g. FEXTRA | FNAME) with truncated metadata
        val flagBytes = byteArrayOf(0x1f.toByte(), 0x8b.toByte(), 8, 0x1F.toByte(), 0, 0, 0, 0, 0, 0)
        val decompFlags = GzipCodec.decompress(flagBytes)
        assertArrayEquals(flagBytes, decompFlags)
    }

    // =========================================================================
    // SECTION 2: EApiCrypto Adversarial Fuzzing & Stress Tests
    // =========================================================================

    @Test
    fun fuzzHexParsing_oddLengthStrings() {
        // Odd length hex strings cannot form whole byte pairs
        val testOddHexes = listOf(
            "1",
            "123",
            "ABCDE",
            "0123456",
            "a".repeat(15),
            "F".repeat(33),
            "0123456789a".repeat(91) // 1001 chars (odd)
        )

        for (hex in testOddHexes) {
            val bytes = EApiCrypto.hexStringToByteArray(hex)
            assertNull("Odd length hex '$hex' must return null", bytes)

            val decrypted = EApiCrypto.decryptHex(hex)
            assertNull("decryptHex on odd length string must return null", decrypted)
        }
    }

    @Test
    fun fuzzHexParsing_malformedAndNonHexCharacters() {
        val invalidHexStrings = listOf(
            "0123GG",
            "0123zz",
            "0x1234",
            "0XABCD",
            "12 34",
            "12\t34",
            "12\n34",
            "12\r\n34",
            "12\u000034",
            "1234!@#$",
            "1234😀56",
            "1234中文56",
            "1234абвг",
            "----",
            "::;;",
            "-36cd479b6b5-"
        )

        for (invalid in invalidHexStrings) {
            val bytes = EApiCrypto.hexStringToByteArray(invalid)
            assertNull("Malformed hex string '$invalid' must return null", bytes)

            val decrypted = EApiCrypto.decryptHex(invalid)
            assertNull("decryptHex on malformed hex '$invalid' must return null", decrypted)
        }
    }

    @Test
    fun testUnicodeFullWidthNumeralBehavior() {
        // Document empirical behavior of Character.digit(c, 16):
        // Java Character.digit parses full-width digits (e.g. '\uFF10'..' \uFF19')
        val fullWidthDigits = "０１２３４５６７"
        val bytes = EApiCrypto.hexStringToByteArray(fullWidthDigits)
        // Character.digit converts full-width numerals to numeric values
        assertNotNull(bytes)
        assertEquals(4, bytes?.size)

        // However, decryptHex safely rejects because AES block size or PKCS5 padding will fail
        assertNull(EApiCrypto.decryptHex(fullWidthDigits))

        // Even with 32 full-width digits (16 bytes), AES padding rejects garbage plaintext
        val fullWidth32 = "０１２３４５６７８９０１２３４５６７８９０１２３４５６７８９０１"
        assertNull(EApiCrypto.decryptHex(fullWidth32))
    }

    @Test
    fun fuzzHexParsing_whitespaceHandling() {
        val validPair = "ABCD"
        val expectedBytes = byteArrayOf(0xAB.toByte(), 0xCD.toByte())

        // Leading and trailing whitespace should be trimmed and accepted
        assertArrayEquals(expectedBytes, EApiCrypto.hexStringToByteArray("  ABCD  "))
        assertArrayEquals(expectedBytes, EApiCrypto.hexStringToByteArray("\t\nABCD\r\n"))

        // Embedded whitespace must be rejected
        assertNull(EApiCrypto.hexStringToByteArray("AB CD"))
        assertNull(EApiCrypto.hexStringToByteArray("A BCD"))

        // Blank or empty strings
        val emptyBytes = EApiCrypto.hexStringToByteArray("")
        assertEquals(0, emptyBytes?.size ?: -1)
        assertNull(EApiCrypto.decryptHex(""))
        assertNull(EApiCrypto.decryptHex("   "))
    }

    @Test
    fun fuzzAesDecrypt_invalidBlockSizes() {
        val invalidSizes = listOf(1, 2, 3, 7, 15, 17, 31, 33, 47, 49, 100, 1001)
        for (size in invalidSizes) {
            val nonBlockData = ByteArray(size) { 0x42 }
            val result = EApiCrypto.decryptOrNull(nonBlockData)
            assertNull("decryptOrNull must return null for non-block-size data ($size bytes)", result)
        }
    }

    @Test
    fun fuzzAesDecrypt_corruptedPkcs5Padding() {
        val rawPlaintext = "Exact 16 Byte---" // 16 bytes
        val encrypted = EApiCrypto.encrypt(rawPlaintext.toByteArray(Charsets.UTF_8))
        // AES adds a 16-byte padding block with all 0x10 bytes -> total 32 bytes
        assertEquals(32, encrypted.size)

        // Raw decrypt without unpadding to inspect or manually test bad padding
        val rawCipher = Cipher.getInstance("AES/ECB/NoPadding")
        val keySpec = SecretKeySpec("e82ckenh8dichen8".toByteArray(Charsets.UTF_8), "AES")
        rawCipher.init(Cipher.DECRYPT_MODE, keySpec)
        val decryptedBlocks = rawCipher.doFinal(encrypted)

        // Test vectors for bad PKCS5 padding
        val badPaddingPatterns = listOf(
            0x00, // Padding byte cannot be 0
            0x11, // Padding byte cannot be 17 (> block size 16)
            0xFF, // Padding byte cannot be 255
            0x05  // Padding byte is 5, but previous 4 bytes are not 0x05
        )

        val encryptCipher = Cipher.getInstance("AES/ECB/NoPadding")
        encryptCipher.init(Cipher.ENCRYPT_MODE, keySpec)

        for (badByte in badPaddingPatterns) {
            val tamperedPlain = decryptedBlocks.clone()
            tamperedPlain[tamperedPlain.size - 1] = badByte.toByte()

            // Re-encrypt the tampered block
            val tamperedCiphertext = encryptCipher.doFinal(tamperedPlain)
            assertEquals(32, tamperedCiphertext.size)

            // decryptOrNull must catch BadPaddingException and return null
            val result = EApiCrypto.decryptOrNull(tamperedCiphertext)
            assertNull("Tampered padding with byte 0x${Integer.toHexString(badByte)} must return null", result)
        }
    }

    @Test
    fun fuzzAesDecrypt_randomCiphertextNoise() {
        // Fuzz with 300 random byte arrays where length is a multiple of 16
        for (i in 0 until 300) {
            val blocks = 1 + rng.nextInt(10) // 1 to 10 blocks (16 to 160 bytes)
            val noise = ByteArray(blocks * 16)
            rng.nextBytes(noise)

            try {
                // Must never throw uncaught BadPaddingException or other exception
                val result = EApiCrypto.decryptOrNull(noise)
                // Result is either null (in ~99.6% of cases) or valid bytes (if noise randomly ended in valid padding)
                if (result != null) {
                    assertTrue(result.size <= noise.size)
                }
            } catch (t: Throwable) {
                fail("EApiCrypto.decryptOrNull crashed on random noise: ${t.message}")
            }
        }
    }

    @Test
    fun stressAesCrypto_largePayloadRoundTrip() {
        // 1 MB payload with varied characters, Chinese text, emojis, and symbols
        val baseChunk = """{"track":{"id":1234567,"name":"夜曲 (Nocturne) 🎵 - 周杰伦","artist":"Jay Chou","album":"11月的萧邦","fee":0,"st":0,"pl":320000}},"""
        val repeats = 10_000 // ~1.2 MB
        val sb = StringBuilder(repeats * baseChunk.length + 32)
        sb.append("""{"code":200,"tracks":[""")
        for (i in 0 until repeats) {
            sb.append(baseChunk)
        }
        sb.append("""{"id":0}]}""")
        val largeText = sb.toString()
        assertTrue(largeText.length > 1_000_000)

        // Raw bytes roundtrip
        val rawBytes = largeText.toByteArray(Charsets.UTF_8)
        val encryptedBytes = EApiCrypto.encrypt(rawBytes)
        assertEquals(0, encryptedBytes.size % 16)
        val decryptedBytes = EApiCrypto.decrypt(encryptedBytes)
        assertArrayEquals(rawBytes, decryptedBytes)

        // Hex string roundtrip
        val encryptedHex = EApiCrypto.encryptHex(largeText)
        val decryptedText = EApiCrypto.decryptHex(encryptedHex)
        assertEquals(largeText, decryptedText)
    }

    @Test
    fun fuzzEApiRequestBody_adversarialEnvelopes() {
        // 1. Missing or corrupted params prefix
        assertNull(EApiCrypto.parseEApiRequestBody("not_params=1234"))
        assertNull(EApiCrypto.parseEApiRequestBody("params="))
        assertNull(EApiCrypto.parseEApiRequestBody("params=123")) // odd length
        assertNull(EApiCrypto.parseEApiRequestBody("params=ZZZZ")) // invalid hex

        // 2. Extra parameters
        val validPath = "/api/v1/test"
        val validJson = """{"id":42}"""
        val validBody = EApiCrypto.buildEApiRequestBody(validPath, validJson)
        val bodyWithExtras = "$validBody&extra_param=true&timestamp=123456"
        val parsedExtra = EApiCrypto.parseEApiRequestBody(bodyWithExtras)
        assertNotNull(parsedExtra)
        assertEquals(validPath, parsedExtra?.first)
        assertEquals(42, parsedExtra?.second?.optInt("id"))

        // 3. Payload with corrupted delimiters
        val payloadNoDelimiter = EApiCrypto.encryptHex("/api/v1/test_no_delimiter_at_all")
        assertNull(EApiCrypto.parseEApiRequestBody("params=$payloadNoDelimiter"))

        // 4. Broken JSON payload between delimiters
        val brokenJsonPayload = "/api/test${EApiCrypto.EAPI_DELIMITER}NOT_VALID_JSON{:::}${EApiCrypto.EAPI_DELIMITER}digest"
        val brokenJsonHex = EApiCrypto.encryptHex(brokenJsonPayload)
        assertNull(EApiCrypto.parseEApiRequestBody("params=$brokenJsonHex"))

        // 5. Delimiter inside JSON string value
        val jsonWithEmbeddedDelimiter = """{"text":"Contains -36cd479b6b5- inside JSON"}"""
        val requestWithEmbedded = EApiCrypto.buildEApiRequestBody(validPath, jsonWithEmbeddedDelimiter)
        // Check behavior: does it parse or handle gracefully?
        val parsedEmbedded = EApiCrypto.parseEApiRequestBody(requestWithEmbedded)
        // Handled gracefully without crash
        assertTrue(parsedEmbedded == null || parsedEmbedded.first == validPath)
    }

    // =========================================================================
    // SECTION 3: ProtocolCodec Adversarial Fuzzing & Symmetrical Stress Tests
    // =========================================================================

    @Test
    fun fuzzProtocolCodec_isEApiEncryptedAdversarialBytes() {
        // 1. Length not multiple of 16
        assertFalse(ProtocolCodec.isEApiEncrypted(targetEApiUrl, ByteArray(0)))
        assertFalse(ProtocolCodec.isEApiEncrypted(targetEApiUrl, ByteArray(15)))
        assertFalse(ProtocolCodec.isEApiEncrypted(targetEApiUrl, ByteArray(17)))

        // 2. Exact 16 bytes starting with '{' or '[' (Unencrypted JSON)
        val jsonBlock16 = """{"k":"v"}       """.toByteArray(Charsets.UTF_8)
        assertEquals(16, jsonBlock16.size)
        assertFalse(ProtocolCodec.isEApiEncrypted(targetEApiUrl, jsonBlock16))

        val arrayBlock16 = """[1,2,3,4,5]     """.toByteArray(Charsets.UTF_8)
        assertEquals(16, arrayBlock16.size)
        assertFalse(ProtocolCodec.isEApiEncrypted(targetEApiUrl, arrayBlock16))

        // 3. Leading whitespace before '{'
        val spacedJson16 = """  {"k":"v"}     """.toByteArray(Charsets.UTF_8)
        assertEquals(16, spacedJson16.size)
        assertFalse(ProtocolCodec.isEApiEncrypted(targetEApiUrl, spacedJson16))

        // 4. 16 bytes of zeros or binary control characters
        val zeroBlock16 = ByteArray(16)
        val isEncryptedZeros = ProtocolCodec.isEApiEncrypted(targetEApiUrl, zeroBlock16)
        // Should evaluate to true (since non-JSON), but decodeResponse must handle it safely
        assertTrue(isEncryptedZeros)
        val decoded = ProtocolCodec.decodeResponse(targetEApiUrl, zeroBlock16)
        assertNull("decodeResponse on 16 zeros must return null safely", decoded)
    }

    @Test
    fun fuzzProtocolCodec_decodeResponseRandomNoise() {
        // Fuzz decodeResponse with 300 random byte arrays
        for (i in 0 until 300) {
            val len = rng.nextInt(2048)
            val noise = ByteArray(len)
            rng.nextBytes(noise)

            try {
                val decoded = ProtocolCodec.decodeResponse(targetEApiUrl, noise)
                // Result must either be null or a valid DecodedResponse (never an uncaught exception)
                if (decoded != null) {
                    assertNotNull(decoded.jsonString)
                }
            } catch (t: Throwable) {
                fail("ProtocolCodec.decodeResponse crashed on noise array of len $len: ${t.message}")
            }
        }
    }

    @Test
    fun fuzzProtocolCodec_corruptedMultiLayerPipelines() {
        // Case 1: Valid outer GZIP wrapping corrupted ciphertext
        val corruptedCiphertext = ByteArray(32) { (it + 5).toByte() }
        val outerGzipCorruptedCipher = GzipCodec.compress(corruptedCiphertext)
        val res1 = ProtocolCodec.decodeResponse(targetEApiUrl, outerGzipCorruptedCipher)
        assertNull("Valid GZIP wrapping corrupted ciphertext should return null", res1)

        // Case 2: Valid outer GZIP + valid ciphertext + corrupted inner GZIP
        val corruptedInnerGzip = byteArrayOf(0x1f.toByte(), 0x8b.toByte(), 0x01, 0x02, 0x03, 0x04)
        val encryptedInner = EApiCrypto.encrypt(corruptedInnerGzip)
        val outerGzipCase2 = GzipCodec.compress(encryptedInner)
        val res2 = ProtocolCodec.decodeResponse(targetEApiUrl, outerGzipCase2)
        // Handled gracefully without crash
        assertNotNull(res2)
    }

    @Test
    fun stressProtocolCodec_symmetricalRoundTripAll5Permutations() {
        val originalJson = """{"code":200,"data":{"songId":98765,"title":"晴天","artist":"周杰伦","fee":0,"st":0}}"""
        val modifiedJson = """{"code":200,"data":{"songId":98765,"title":"晴天 (UNM Unblocked) 🎵","artist":"周杰伦","fee":0,"st":0,"pl":320000}}"""

        data class ProtocolConfig(
            val name: String,
            val isEncrypted: Boolean,
            val wasOuterGzip: Boolean,
            val wasInnerGzip: Boolean
        )

        val configs = listOf(
            ProtocolConfig("1. Plain JSON", isEncrypted = false, wasOuterGzip = false, wasInnerGzip = false),
            ProtocolConfig("2. Outer GZIP only", isEncrypted = false, wasOuterGzip = true, wasInnerGzip = false),
            ProtocolConfig("3. EAPI AES only", isEncrypted = true, wasOuterGzip = false, wasInnerGzip = false),
            ProtocolConfig("4. XEAPI (Outer GZIP + EAPI)", isEncrypted = true, wasOuterGzip = true, wasInnerGzip = false),
            ProtocolConfig("5. Double-GZIP (Outer GZIP + EAPI + Inner GZIP)", isEncrypted = true, wasOuterGzip = true, wasInnerGzip = true)
        )

        for (cfg in configs) {
            // Encode initial payload
            val encodedInitial = ProtocolCodec.encodeResponse(
                url = targetEApiUrl,
                modifiedJson = originalJson,
                isEncrypted = cfg.isEncrypted,
                wasOuterGzip = cfg.wasOuterGzip,
                wasInnerGzip = cfg.wasInnerGzip
            )

            // Decode initial payload
            val decodedInitial = ProtocolCodec.decodeResponse(targetEApiUrl, encodedInitial)
            assertNotNull("${cfg.name}: initial decode must not be null", decodedInitial)
            assertEquals("${cfg.name}: json must match original", originalJson, decodedInitial?.jsonString)
            assertEquals("${cfg.name}: isEncrypted flag", cfg.isEncrypted, decodedInitial?.isEncrypted)
            assertEquals("${cfg.name}: wasOuterGzip flag", cfg.wasOuterGzip, decodedInitial?.wasOuterGzip)
            assertEquals("${cfg.name}: wasInnerGzip flag", cfg.wasInnerGzip, decodedInitial?.wasInnerGzip)

            // Re-encode with modified JSON
            val reEncoded = ProtocolCodec.encodeResponse(decodedInitial!!, modifiedJson)

            // Decode the re-encoded payload
            val reDecoded = ProtocolCodec.decodeResponse(targetEApiUrl, reEncoded)
            assertNotNull("${cfg.name}: re-decode must not be null", reDecoded)
            assertEquals("${cfg.name}: modified JSON must match exactly", modifiedJson, reDecoded?.jsonString)
            assertEquals("${cfg.name}: isEncrypted flag preserved", cfg.isEncrypted, reDecoded?.isEncrypted)
            assertEquals("${cfg.name}: wasOuterGzip flag preserved", cfg.wasOuterGzip, reDecoded?.wasOuterGzip)
            assertEquals("${cfg.name}: wasInnerGzip flag preserved", cfg.wasInnerGzip, reDecoded?.wasInnerGzip)
        }
    }

    @Test
    fun stressProtocolCodec_multiMegabyteDoubleGzipRoundTrip() {
        // Construct 1.5 MB complex JSON payload
        val sb = StringBuilder()
        sb.append("""{"code":200,"playlist":{"tracks":[""")
        for (i in 0 until 15000) {
            if (i > 0) sb.append(",")
            sb.append("""{"id":$i,"title":"Track No. $i 🎵 歌曲名","artist":"歌手名 $i","fee":1,"st":-200,"trialMode":true}""")
        }
        sb.append("""]}}""")
        val largeJson = sb.toString()
        assertTrue(largeJson.length > 1_000_000)

        // Encode as Double-GZIP format
        val encodedBytes = ProtocolCodec.encodeResponse(
            url = targetXeApiUrl,
            modifiedJson = largeJson,
            isEncrypted = true,
            wasOuterGzip = true,
            wasInnerGzip = true
        )
        assertTrue(encodedBytes.isNotEmpty())

        // Decode
        val decoded = ProtocolCodec.decodeResponse(targetXeApiUrl, encodedBytes)
        assertNotNull(decoded)
        assertEquals(largeJson, decoded?.jsonString)
        assertTrue(decoded!!.isEncrypted)
        assertTrue(decoded.wasOuterGzip)
        assertTrue(decoded.wasInnerGzip)
    }
}
