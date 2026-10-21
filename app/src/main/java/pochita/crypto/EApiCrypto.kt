package pochita.crypto

import org.json.JSONObject

/**
 * Backward compatibility bridge for [pochita.codec.EApiCrypto].
 *
 * All cryptographic operations have migrated to [pochita.codec.EApiCrypto].
 */
@Deprecated(
    message = "Migrated to pochita.codec.EApiCrypto",
    replaceWith = ReplaceWith("EApiCrypto", "pochita.codec.EApiCrypto")
)
object EApiCrypto {
    fun decrypt(data: ByteArray): ByteArray = pochita.codec.EApiCrypto.decrypt(data)
    fun encrypt(data: ByteArray): ByteArray = pochita.codec.EApiCrypto.encrypt(data)
    fun decryptHex(hexStr: String): String? = pochita.codec.EApiCrypto.decryptHex(hexStr)
    fun encryptHex(plainText: String): String = pochita.codec.EApiCrypto.encryptHex(plainText)
    fun parseEApiRequestBody(body: String): Pair<String, JSONObject>? = pochita.codec.EApiCrypto.parseEApiRequestBody(body)
    fun md5(input: String): String = pochita.codec.EApiCrypto.md5(input)
}
