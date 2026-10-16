package pochita.hook

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import pochita.crypto.EApiCrypto

class NetEaseHookTest {

    @Test
    fun testUnblockPrivilegesInPlaylist() {
        val testJson = JSONObject("""
            {
                "code": 200,
                "playlist": {
                    "id": 1001,
                    "tracks": [
                        {
                            "id": 54321,
                            "name": "测试歌曲",
                            "fee": 1,
                            "st": -200,
                            "cp": 0,
                            "ar": [{"id": 1, "name": "测试歌手"}],
                            "dt": 240000,
                            "privilege": {
                                "id": 54321,
                                "fee": 1,
                                "st": -200,
                                "pl": 0,
                                "dl": 0,
                                "cp": 0,
                                "sp": 0,
                                "subp": 0
                            }
                        }
                    ],
                    "privileges": [
                        {
                            "id": 54321,
                            "fee": 1,
                            "st": -200,
                            "pl": 0,
                            "dl": 0,
                            "cp": 0,
                            "sp": 0,
                            "subp": 0
                        }
                    ]
                }
            }
        """.trimIndent())

        // 加密为 EAPI 数据
        val encryptedPayload = EApiCrypto.encrypt(testJson.toString().toByteArray(Charsets.UTF_8))

        // 通过 NetEaseHook 处理
        val modifiedBytes = NetEaseHook.processEApiResponse(
            "https://interface.music.163.com/eapi/v3/playlist/detail",
            encryptedPayload
        )

        assertNotNull(modifiedBytes)
        val decryptedJson = JSONObject(String(EApiCrypto.decrypt(modifiedBytes!!), Charsets.UTF_8))

        val track = decryptedJson.getJSONObject("playlist").getJSONArray("tracks").getJSONObject(0)
        assertEquals(0, track.getInt("st"))
        assertEquals(0, track.getInt("fee"))
        assertEquals(1, track.getInt("cp"))

        val trackPriv = track.getJSONObject("privilege")
        assertEquals(0, trackPriv.getInt("st"))
        assertEquals(1, trackPriv.getInt("cp"))
        assertEquals(0, trackPriv.getInt("fee"))
        assertEquals(320000, trackPriv.getInt("pl"))
        assertEquals(320000, trackPriv.getInt("dl"))

        val listPriv = decryptedJson.getJSONObject("playlist").getJSONArray("privileges").getJSONObject(0)
        assertEquals(0, listPriv.getInt("st"))
        assertEquals(1, listPriv.getInt("cp"))
        assertEquals(0, listPriv.getInt("fee"))
        assertEquals(320000, listPriv.getInt("pl"))
    }

    @Test
    fun testGzipCompressedPlaylistUnblock() {
        val testJson = JSONObject("""
            {
                "code": 200,
                "playlist": {
                    "id": 2002,
                    "tracks": [
                        {
                            "id": 99999,
                            "name": "Gzip歌曲",
                            "fee": 1,
                            "st": -200,
                            "cp": 0,
                            "ar": [{"id": 2, "name": "Gzip歌手"}],
                            "dt": 180000,
                            "privilege": {
                                "id": 99999,
                                "fee": 1,
                                "st": -200,
                                "pl": 0,
                                "cp": 0
                            }
                        }
                    ]
                }
            }
        """.trimIndent())

        // 模拟 GZIP 压缩
        val baos = java.io.ByteArrayOutputStream()
        java.util.zip.GZIPOutputStream(baos).use {
            it.write(testJson.toString().toByteArray(Charsets.UTF_8))
        }
        val gzippedBytes = baos.toByteArray()

        val modifiedBytes = NetEaseHook.processEApiResponse(
            "https://interface3.music.163.com/xeapi/playlist/v4/detail",
            gzippedBytes
        )

        assertNotNull(modifiedBytes)
        // 验证输出仍是合法的 GZIP
        val decompressedBytes = java.util.zip.GZIPInputStream(modifiedBytes!!.inputStream()).use { it.readBytes() }
        val resultJson = JSONObject(String(decompressedBytes, Charsets.UTF_8))

        val track = resultJson.getJSONObject("playlist").getJSONArray("tracks").getJSONObject(0)
        assertEquals(0, track.getInt("st"))
        assertEquals(0, track.getInt("fee"))
        assertEquals(1, track.getInt("cp"))

        val trackPriv = track.getJSONObject("privilege")
        assertEquals(0, trackPriv.getInt("st"))
        assertEquals(1, trackPriv.getInt("cp"))
        assertEquals(0, trackPriv.getInt("fee"))
        assertEquals(320000, trackPriv.getInt("pl"))
    }

    @Test
    fun testPlayableSongNotProxied() {
        val testJson = JSONObject("""
            {
                "code": 200,
                "data": [
                    {
                        "id": 1851652156,
                        "url": "http://m701.music.126.net/playable/song.mp3",
                        "br": 320000,
                        "size": 8000000,
                        "code": 200,
                        "fee": 8,
                        "payed": 0
                    }
                ]
            }
        """.trimIndent())

        val encryptedPayload = EApiCrypto.encrypt(testJson.toString().toByteArray(Charsets.UTF_8))
        val result = NetEaseHook.processEApiResponse(
            "https://interface.music.163.com/eapi/song/enhance/player/url/v1",
            encryptedPayload
        )
        // 网易云具备有效可播放链接时，不进行音源替换
        org.junit.Assert.assertNull(result)
    }
}
