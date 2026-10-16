package pochita.crypto

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class EApiCryptoTest {

    @Test
    fun testAesEncryptDecrypt() {
        val originalText = "{\"ids\":[\"123456\"],\"level\":\"standard\"}"
        val encryptedHex = EApiCrypto.encryptHex(originalText)
        val decryptedText = EApiCrypto.decryptHex(encryptedHex)
        assertEquals(originalText, decryptedText)
    }

    @Test
    fun testMd5Hash() {
        val hash = EApiCrypto.md5("nobody/api/song/enhance/player/urluse{}md5forencrypt")
        assertNotNull(hash)
        assertEquals(32, hash.length)
    }

    @Test
    fun testParseEApiRequestBody() {
        // 构建标准 EAPI 请求体结构: ${path}-36cd479b6b5-${json}-36cd479b6b5-${md5}
        val path = "/api/song/enhance/player/url/v1"
        val jsonStr = "{\"ids\":\"[123456]\",\"level\":\"lossless\"}"
        val md5 = EApiCrypto.md5(jsonStr)
        val payload = "$path-36cd479b6b5-$jsonStr-36cd479b6b5-$md5"

        val hexBody = "params=" + EApiCrypto.encryptHex(payload)
        val parsed = EApiCrypto.parseEApiRequestBody(hexBody)

        assertNotNull(parsed)
        assertEquals(path, parsed?.first)
        assertTrue(parsed?.second?.has("ids") == true)
        assertEquals("[123456]", parsed?.second?.optString("ids"))
    }
}

