package pochita.codec

import pochita.common.UnmLogger

/**
 * 协议编解码调度器：统一管理网易云音乐报文的多层编解码与协议解包。
 * 透明支持 GZIP 压缩、EAPI AES-128 加解密及复合封包。
 */
object ProtocolCodec {
    private const val TAG = "ProtocolCodec"

    /**
     * 解码结果封装，保留原始报文的协议特征以便完美对称重编码
     */
    data class DecodedResponse(
        val url: String,
        val jsonString: String,
        val isEncrypted: Boolean,
        val wasOuterGzip: Boolean,
        val wasInnerGzip: Boolean = false
    )

    /**
     * 判断 URL 是否属于需要拦截与编解码的目标网易云 API 路径
     */
    fun isTargetUrl(url: String): Boolean {
        val isTarget = (url.contains("/eapi/") || url.contains("/xeapi/") ||
                url.contains("/api/") || url.contains("player/url"))
        val isStatic = url.contains(".jpg") || url.contains(".webp") ||
                url.contains(".png") || url.contains(".gif") ||
                url.contains(".mp3") || url.contains(".flac")
        return isTarget && !isStatic
    }

    /**
     * 判断数据载荷是否属于 EAPI AES-128 加密数据
     */
    fun isEApiEncrypted(url: String, body: ByteArray): Boolean {
        if (body.isEmpty() || body.size % 16 != 0) return false
        val firstChar = body.firstOrNull { it.toInt() in 33..126 }
        return firstChar != '{'.code.toByte() && firstChar != '['.code.toByte()
    }

    /**
     * 解码十六进制 EAPI 响应密文 (兼容 PROJECT.md 接口契约)
     */
    fun decryptEApiResponse(cipherTextHex: String): String? {
        return EApiCrypto.decryptHex(cipherTextHex)
    }

    /**
     * 加密明文字符串为十六进制 EAPI 响应密文 (兼容 PROJECT.md 接口契约)
     */
    fun encryptEApiResponse(plainText: String): String {
        return EApiCrypto.encryptHex(plainText)
    }

    /**
     * 解压 GZIP 报文 (兼容 PROJECT.md 接口契约)
     */
    fun decompressGzip(compressed: ByteArray): ByteArray {
        return GzipCodec.decompress(compressed)
    }

    /**
     * 压缩为 GZIP 报文 (兼容 PROJECT.md 接口契约)
     */
    fun compressGzip(raw: ByteArray): ByteArray {
        return GzipCodec.compress(raw)
    }

    /**
     * 统一解码响应报文：支持透明解压 GZIP 与解密 EAPI
     * 返回结构化 DecodedResponse 对象；若 URL 不匹配、数据为空或解密解析失败则返回 null。
     */
    fun decodeResponse(url: String, bodyBytes: ByteArray): DecodedResponse? {
        if (bodyBytes.isEmpty() || !isTargetUrl(url)) return null

        val wasOuterGzip = GzipCodec.isGzip(bodyBytes)
        val outerPayload = if (wasOuterGzip) GzipCodec.decompress(bodyBytes) else bodyBytes
        if (outerPayload.isEmpty()) return null

        val isEncrypted = isEApiEncrypted(url, outerPayload)

        val (jsonString, wasInnerGzip) = if (isEncrypted) {
            val decrypted = EApiCrypto.decryptOrNull(outerPayload)
            if (decrypted == null) {
                UnmLogger.w(TAG, "Failed to decrypt EAPI payload for $url")
                return null
            }
            val innerGzip = GzipCodec.isGzip(decrypted)
            val innerPayload = if (innerGzip) GzipCodec.decompress(decrypted) else decrypted
            try {
                Pair(String(innerPayload, Charsets.UTF_8), innerGzip)
            } catch (t: Throwable) {
                UnmLogger.w(TAG, "Failed to decode UTF-8 string for $url: ${t.message}")
                return null
            }
        } else {
            try {
                Pair(String(outerPayload, Charsets.UTF_8), false)
            } catch (t: Throwable) {
                UnmLogger.w(TAG, "Failed to decode UTF-8 string for $url: ${t.message}")
                return null
            }
        }

        return DecodedResponse(
            url = url,
            jsonString = jsonString,
            isEncrypted = isEncrypted,
            wasOuterGzip = wasOuterGzip,
            wasInnerGzip = wasInnerGzip
        )
    }

    /**
     * 统一重编码响应报文：根据解码时保留的元数据状态，精准还原双层压缩与加密封装
     */
    fun encodeResponse(decoded: DecodedResponse, modifiedJson: String): ByteArray {
        val rawJsonBytes = modifiedJson.toByteArray(Charsets.UTF_8)
        val innerPayload = if (decoded.wasInnerGzip) GzipCodec.compress(rawJsonBytes) else rawJsonBytes
        val encryptedPayload = if (decoded.isEncrypted) EApiCrypto.encrypt(innerPayload) else innerPayload
        return if (decoded.wasOuterGzip) GzipCodec.compress(encryptedPayload) else encryptedPayload
    }

    /**
     * 重编码重载：适用于直接传入 URL、明文字符串与显式协议标记
     */
    fun encodeResponse(
        url: String,
        modifiedJson: String,
        isEncrypted: Boolean,
        wasOuterGzip: Boolean,
        wasInnerGzip: Boolean = false
    ): ByteArray {
        val rawJsonBytes = modifiedJson.toByteArray(Charsets.UTF_8)
        val innerPayload = if (wasInnerGzip) GzipCodec.compress(rawJsonBytes) else rawJsonBytes
        val encryptedPayload = if (isEncrypted) EApiCrypto.encrypt(innerPayload) else innerPayload
        return if (wasOuterGzip) GzipCodec.compress(encryptedPayload) else encryptedPayload
    }
}
