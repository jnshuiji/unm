package pochita.codec

import android.annotation.SuppressLint
import java.security.MessageDigest
import javax.crypto.Cipher
import javax.crypto.spec.SecretKeySpec
import org.json.JSONObject
import pochita.common.UnmLogger

/**
 * 网易云音乐 EAPI (AES-128-ECB) 加解密与协议封装工具类。
 * 遵循网易云服务端 AES/ECB/PKCS5Padding 协议，密钥为 "e82ckenh8dichen8"。
 */
object EApiCrypto {
    private const val TAG = "EApiCrypto"
    private const val ALGORITHM = "AES"
    private const val TRANSFORMATION = "AES/ECB/PKCS5Padding"
    private val EAPI_KEY = "e82ckenh8dichen8".toByteArray(Charsets.UTF_8)
    private val KEY_SPEC = SecretKeySpec(EAPI_KEY, ALGORITHM)
    private val HEX_CHARS = "0123456789ABCDEF".toCharArray()
    const val EAPI_DELIMITER = "-36cd479b6b5-"

    /**
     * 原始字节解密 (标准底层方法，异常向上抛出)
     */
    @SuppressLint("GetInstance")
    fun decrypt(data: ByteArray): ByteArray {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, KEY_SPEC)
        return cipher.doFinal(data)
    }

    /**
     * 原始字节安全解密 (异常时静默捕获并返回 null)
     */
    fun decryptOrNull(data: ByteArray): ByteArray? {
        if (data.isEmpty() || data.size % 16 != 0) return null
        return try {
            decrypt(data)
        } catch (t: Throwable) {
            UnmLogger.w(TAG, "AES decryptOrNull failed: ${t.message}")
            null
        }
    }

    /**
     * 原始字节加密
     */
    @SuppressLint("GetInstance")
    fun encrypt(data: ByteArray): ByteArray {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, KEY_SPEC)
        return cipher.doFinal(data)
    }

    /**
     * 十六进制密文字符串解密为明文字符串。
     * 若包含非十六进制字符、奇数长度或解密失败，安全返回 null。
     */
    fun decryptHex(hexStr: String): String? {
        val cleanHex = hexStr.trim()
        val bytes = hexStringToByteArray(cleanHex) ?: return null
        val decrypted = decryptOrNull(bytes) ?: return null
        return try {
            String(decrypted, Charsets.UTF_8)
        } catch (t: Throwable) {
            UnmLogger.w(TAG, "String conversion failed: ${t.message}")
            null
        }
    }

    /**
     * 明文字符串加密为十六进制密文字符串
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
            val trimmed = body.trim()
            val hex = when {
                trimmed.startsWith("params=") -> trimmed.substringAfter("params=").substringBefore("&")
                else -> trimmed
            }
            val decrypted = decryptHex(hex) ?: return null
            val parts = decrypted.split(EAPI_DELIMITER)
            if (parts.size >= 2) {
                val path = parts[0]
                val json = JSONObject(parts[1])
                Pair(path, json)
            } else null
        } catch (t: Throwable) {
            UnmLogger.w(TAG, "Failed to parse EAPI request body: ${t.message}")
            null
        }
    }

    /**
     * 构造标准 EAPI 请求体 (params=HEXSTRING)
     */
    fun buildEApiRequestBody(path: String, jsonStr: String): String {
        val digest = md5("nobody${path}use${jsonStr}md5forencrypt")
        val payload = "$path$EAPI_DELIMITER$jsonStr$EAPI_DELIMITER$digest"
        return "params=" + encryptHex(payload)
    }

    /**
     * MD5 计算 (32 位小写 Hex)
     */
    fun md5(input: String): String {
        val bytes = MessageDigest.getInstance("MD5").digest(input.toByteArray(Charsets.UTF_8))
        return byteArrayToHexString(bytes).lowercase()
    }

    /**
     * 十六进制字符串转换为字节数组，严格校验偶数长度与合法字符
     */
    fun hexStringToByteArray(s: String): ByteArray? {
        val clean = s.trim()
        if (clean.length % 2 != 0) return null
        val len = clean.length
        val data = ByteArray(len / 2)
        var i = 0
        while (i < len) {
            val high = Character.digit(clean[i], 16)
            val low = Character.digit(clean[i + 1], 16)
            if (high == -1 || low == -1) return null
            data[i / 2] = ((high shl 4) + low).toByte()
            i += 2
        }
        return data
    }

    /**
     * 字节数组转换为大写十六进制字符串
     */
    fun byteArrayToHexString(bytes: ByteArray): String {
        val chars = CharArray(bytes.size * 2)
        for (i in bytes.indices) {
            val v = bytes[i].toInt() and 0xFF
            chars[i * 2] = HEX_CHARS[v ushr 4]
            chars[i * 2 + 1] = HEX_CHARS[v and 0x0F]
        }
        return String(chars)
    }
}
