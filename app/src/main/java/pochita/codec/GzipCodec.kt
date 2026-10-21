package pochita.codec

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream
import pochita.common.UnmLogger

/**
 * GZIP 编解码器：负责透明 GZIP 检测、解压缩与压缩流处理。
 * 具备对截断流、损坏头部及校验和错误的静默容错保护。
 */
object GzipCodec {
    private const val TAG = "GzipCodec"
    private const val BUFFER_SIZE = 8192
    const val GZIP_MAGIC_0: Byte = 0x1f.toByte()
    const val GZIP_MAGIC_1: Byte = 0x8b.toByte()

    /**
     * 判断字节数组是否具备合法的 GZIP 头部 Magic Bytes (0x1f, 0x8b)
     */
    fun isGzip(bytes: ByteArray?): Boolean {
        if (bytes == null || bytes.size < 2) return false
        return bytes[0] == GZIP_MAGIC_0 && bytes[1] == GZIP_MAGIC_1
    }

    /**
     * 解压 GZIP 报文字节流。
     * 若数据非 GZIP 或在解压过程中遇到截断/损坏，自动回退并返回原始字节数组，不抛出异常。
     */
    fun decompress(bytes: ByteArray): ByteArray {
        if (bytes.size < 2 || !isGzip(bytes)) {
            return bytes
        }
        return try {
            ByteArrayInputStream(bytes).use { bais ->
                GZIPInputStream(bais, BUFFER_SIZE).use { gzis ->
                    gzis.readBytes()
                }
            }
        } catch (t: Throwable) {
            UnmLogger.w(TAG, "GZIP decompression failed: ${t.message}, fallback to raw bytes")
            bytes
        }
    }

    /**
     * 将原始字节数组压缩为标准 GZIP 格式
     */
    fun compress(bytes: ByteArray): ByteArray {
        if (bytes.isEmpty()) return ByteArray(0)
        return try {
            val baos = ByteArrayOutputStream(maxOf(32, bytes.size / 2))
            GZIPOutputStream(baos).use { gzos ->
                gzos.write(bytes)
            }
            baos.toByteArray()
        } catch (t: Throwable) {
            UnmLogger.e(TAG, "GZIP compression failed: ${t.message}", t)
            bytes
        }
    }
}
