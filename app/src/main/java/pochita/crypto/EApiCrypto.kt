package pochita.crypto

import android.annotation.SuppressLint
import java.security.MessageDigest
import javax.crypto.Cipher
import javax.crypto.spec.SecretKeySpec
import org.json.JSONObject

/**
 * 网易云音乐 EAPI (AES-128-ECB) 加解密工具类。
 * 网易云音乐服务端固定采用 AES-128-ECB (PKCS5Padding) 协议通信。
 */
object EApiCrypto {
    private const val ALGORITHM = "AES"
    private const val TRANSFORMATION = "AES/ECB/PKCS5Padding"
    private val EAPI_KEY = "e82ckenh8dichen8".toByteArray(Charsets.UTF_8)
    private val KEY_SPEC = SecretKeySpec(EAPI_KEY, ALGORITHM)
    private val HEX_CHARS = "0123456789ABCDEF".toCharArray()

    /**
     * 对原始字节解密
     */
    @SuppressLint("GetInstance")
    fun decrypt(data: ByteArray): ByteArray {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, KEY_SPEC)
        return cipher.doFinal(data)
    }

    /**
     * 对原始字节加密
     */
    @SuppressLint("GetInstance")
    fun encrypt(data: ByteArray): ByteArray {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, KEY_SPEC)
        return cipher.doFinal(data)
    }

    /**
     * 十六进制字符串解密为明文字符串
     */
    fun decryptHex(hexStr: String): String {
        val cleanHex = hexStr.trim()
        val bytes = hexStringToByteArray(cleanHex)
        val decrypted = decrypt(bytes)
        return String(decrypted, Charsets.UTF_8)
    }

    /**
     * 明文字符串加密为十六进制字符串
     */
    fun encryptHex(plainText: String): String {
        val encrypted = encrypt(plainText.toByteArray(Charsets.UTF_8))
        return byteArrayToHexString(encrypted)
    }

    /**
     * 解析 EAPI 请求体 (通常为 params=HEXSTRING)
     * 返回: Pair(内部路径, JSON参数对象) 或 null
     */
    fun parseEApiRequestBody(body: String): Pair<String, JSONObject>? {
        return try {
            val hex = when {
                body.startsWith("params=") -> body.substringAfter("params=").substringBefore("&")
                else -> body
            }
            val decrypted = decryptHex(hex)
            // EAPI 数据格式: ${path}-36cd479b6b5-${json}-36cd479b6b5-${md5}
            val parts = decrypted.split("-36cd479b6b5-")
            if (parts.size >= 2) {
                val path = parts[0]
                val json = JSONObject(parts[1])
                Pair(path, json)
            } else null
        } catch (_: Throwable) {
            null
        }
    }

    /**
     * MD5 计算
     */
    fun md5(input: String): String {
        val bytes = MessageDigest.getInstance("MD5").digest(input.toByteArray(Charsets.UTF_8))
        return byteArrayToHexString(bytes).lowercase()
    }

    private fun hexStringToByteArray(s: String): ByteArray {
        val len = s.length
        val data = ByteArray(len / 2)
        var i = 0
        while (i < len - 1) {
            val high = Character.digit(s[i], 16)
            val low = Character.digit(s[i + 1], 16)
            if (high != -1 && low != -1) {
                data[i / 2] = ((high shl 4) + low).toByte()
            }
            i += 2
        }
        return data
    }

    private fun byteArrayToHexString(bytes: ByteArray): String {
        val chars = CharArray(bytes.size * 2)
        for (i in bytes.indices) {
            val v = bytes[i].toInt() and 0xFF
            chars[i * 2] = HEX_CHARS[v ushr 4]
            chars[i * 2 + 1] = HEX_CHARS[v and 0x0F]
        }
        return String(chars)
    }
}
