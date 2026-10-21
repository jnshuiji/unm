package pochita.hook

import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import pochita.codec.EApiCrypto
import pochita.codec.GzipCodec
import pochita.model.MatchedAudio
import pochita.model.SongInfo
import pochita.resolver.PlayerUrlResolver

class NetEaseHookTest {

    @Before
    fun setUp() {
        NetEaseHook.resetForTesting()
    }

    @After
    fun tearDown() {
        NetEaseHook.resetForTesting()
    }

    // =========================================================================
    // 1. Existing Legacy Tests (Preserved 100% Backward Compatibility)
    // =========================================================================

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

        // Encrypt as EAPI data
        val encryptedPayload = EApiCrypto.encrypt(testJson.toString().toByteArray(Charsets.UTF_8))

        // Process through NetEaseHook facade
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

        val gzippedBytes = GzipCodec.compress(testJson.toString().toByteArray(Charsets.UTF_8))

        val modifiedBytes = NetEaseHook.processEApiResponse(
            "https://interface3.music.163.com/xeapi/playlist/v4/detail",
            gzippedBytes
        )

        assertNotNull(modifiedBytes)
        assertTrue(GzipCodec.isGzip(modifiedBytes!!))

        val decompressedBytes = GzipCodec.decompress(modifiedBytes)
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
        // Valid playable audio must not be proxied or replaced
        assertNull(result)
    }

    // =========================================================================
    // 2. Integration Tests: PlayerUrlResolver & Sound Source Replacement
    // =========================================================================

    @Test
    fun testPlayerUrlTrialTrackReplacement() {
        // Pre-populate songCache with metadata for song 12345
        val songInfo = SongInfo(id = 12345L, name = "Trial Song", artist = "Trial Artist", durationMs = 210000L)
        NetEaseHook.songCache.put(12345L, songInfo)

        // Inject custom resolver with mock audioMatcher
        val mockMatcher: (SongInfo) -> MatchedAudio? = { song ->
            MatchedAudio(
                url = "http://mock.source/audio/12345.mp3",
                br = 320000,
                size = 5200000L,
                md5 = "e10adc3949ba59abbe56e057f20f883e",
                source = "mock-kuwo"
            )
        }
        val customResolver = PlayerUrlResolver(
            songCache = NetEaseHook.songCache,
            audioMatcher = mockMatcher,
            songDetailFetcher = null
        )
        NetEaseHook.setPlayerUrlResolverForTesting(customResolver)

        // Simulate a trial track response from NetEase
        val testJson = JSONObject("""
            {
                "code": 200,
                "data": [
                    {
                        "id": 12345,
                        "url": "http://m701.music.126.net/trial/short.mp3",
                        "br": 128000,
                        "size": 1000000,
                        "code": 200,
                        "fee": 1,
                        "payed": 0,
                        "freeTrialInfo": {
                            "start": 0,
                            "end": 30
                        },
                        "trialMode": 1
                    }
                ]
            }
        """.trimIndent())

        val encryptedPayload = EApiCrypto.encrypt(testJson.toString().toByteArray(Charsets.UTF_8))
        val modifiedBytes = NetEaseHook.processEApiResponse(
            "https://interface.music.163.com/eapi/song/enhance/player/url",
            encryptedPayload
        )

        assertNotNull(modifiedBytes)
        val decryptedJson = JSONObject(String(EApiCrypto.decrypt(modifiedBytes!!), Charsets.UTF_8))
        val item = decryptedJson.getJSONArray("data").getJSONObject(0)

        assertEquals("http://mock.source/audio/12345.mp3", item.getString("url"))
        assertEquals(320000, item.getInt("br"))
        assertEquals(200, item.getInt("code"))
        assertEquals(0, item.getInt("fee"))
        assertEquals(1, item.getInt("payed"))
        assertEquals("mp3", item.getString("type"))
        assertEquals("exhigh", item.getString("level"))
        assertEquals(5200000L, item.getLong("size"))
        assertEquals("e10adc3949ba59abbe56e057f20f883e", item.getString("md5"))
        assertFalse("freeTrialInfo must be removed", item.has("freeTrialInfo"))
        if (item.has("trialMode")) {
            assertEquals(0, item.getInt("trialMode"))
        }
    }

    // =========================================================================
    // 3. Integration Tests: SongRightsTransformer Discovered Songs Caching
    // =========================================================================

    @Test
    fun testSongMetadataDiscoveredAndPopulatedInCache() {
        val testJson = JSONObject("""
            {
                "code": 200,
                "songs": [
                    {
                        "id": 77777,
                        "name": "Discovered Song",
                        "fee": 1,
                        "st": -100,
                        "cp": 0,
                        "dt": 195000,
                        "ar": [{"id": 10, "name": "Discovered Artist"}],
                        "privilege": {
                            "id": 77777,
                            "fee": 1,
                            "st": -100,
                            "pl": 0,
                            "cp": 0
                        }
                    }
                ]
            }
        """.trimIndent())

        val encryptedPayload = EApiCrypto.encrypt(testJson.toString().toByteArray(Charsets.UTF_8))
        val modifiedBytes = NetEaseHook.processEApiResponse(
            "https://interface.music.163.com/eapi/v3/song/detail",
            encryptedPayload
        )

        assertNotNull(modifiedBytes)

        // Verify song was ingested into NetEaseHook.songCache
        val cached = NetEaseHook.songCache.get(77777L)
        assertNotNull("Song 77777 must be cached in songCache", cached)
        assertEquals(77777L, cached!!.id)
        assertEquals("Discovered Song", cached.name)
        assertEquals("Discovered Artist", cached.artist)
        assertEquals(195000L, cached.durationMs)
    }

    // =========================================================================
    // 4. Exception Isolation & Graceful Degradation Tests
    // =========================================================================

    @Test
    fun testCorruptedPayloadSilentFallback() {
        // Pass garbage bytes that cannot be decrypted or decompressed
        val corruptedBytes = byteArrayOf(0x01, 0x02, 0x03, 0x04, 0x05)
        val result = NetEaseHook.processEApiResponse(
            "https://interface.music.163.com/eapi/v3/playlist/detail",
            corruptedBytes
        )
        // Must return null without throwing any exception
        assertNull(result)
    }

    @Test
    fun testMalformedJsonSilentFallback() {
        // Encrypt a non-JSON string
        val badJsonString = "{ this is completely malformed json :("
        val encrypted = EApiCrypto.encrypt(badJsonString.toByteArray(Charsets.UTF_8))

        val result = NetEaseHook.processEApiResponse(
            "https://interface.music.163.com/eapi/v3/playlist/detail",
            encrypted
        )
        // Must return null without throwing any exception
        assertNull(result)
    }

    // =========================================================================
    // 5. URL Classification & Boundary Filtering Tests
    // =========================================================================

    @Test
    fun testStaticAssetUrlIgnored() {
        val testJson = JSONObject("""{"code": 200, "privilege": {"id": 1, "st": -1}}""")
        val encrypted = EApiCrypto.encrypt(testJson.toString().toByteArray(Charsets.UTF_8))

        // Image URL matching .jpg should be filtered immediately by HookUrlMatcher
        val result = NetEaseHook.processEApiResponse(
            "https://p1.music.126.net/eapi/cover.jpg?param=300y300",
            encrypted
        )
        assertNull(result)
    }

    @Test
    fun testEmptyPayloadReturnsNull() {
        val result = NetEaseHook.processEApiResponse(
            "https://interface.music.163.com/eapi/v3/playlist/detail",
            byteArrayOf()
        )
        assertNull(result)
    }

    @Test
    fun testUnrelatedApiUrlIgnored() {
        val testJson = JSONObject("""{"code": 200, "profile": {"nickname": "Tester"}}""")
        val encrypted = EApiCrypto.encrypt(testJson.toString().toByteArray(Charsets.UTF_8))

        val result = NetEaseHook.processEApiResponse(
            "https://music.163.com/api/user/profile",
            encrypted
        )
        // No unblocking rules match, unmodified payload returns null
        assertNull(result)
    }

    // =========================================================================
    // 6. Advanced Protocol & Traversal Symmetry Tests
    // =========================================================================

    @Test
    fun testDoubleGzipAndEapiSymmetricReEncoding() {
        val testJson = JSONObject("""
            {
                "code": 200,
                "privilege": {
                    "id": 8888,
                    "st": -200,
                    "cp": 0,
                    "fee": 1,
                    "pl": 0
                }
            }
        """.trimIndent())

        // Composite envelope: inner GZIP -> AES-128 encrypt -> outer GZIP
        val innerGzip = GzipCodec.compress(testJson.toString().toByteArray(Charsets.UTF_8))
        val encrypted = EApiCrypto.encrypt(innerGzip)
        val outerGzip = GzipCodec.compress(encrypted)

        val modifiedBytes = NetEaseHook.processEApiResponse(
            "https://interface.music.163.com/eapi/batch",
            outerGzip
        )

        assertNotNull(modifiedBytes)
        // Verify output retained outer GZIP envelope
        assertTrue(GzipCodec.isGzip(modifiedBytes!!))

        val outerDecompressed = GzipCodec.decompress(modifiedBytes)
        val decrypted = EApiCrypto.decrypt(outerDecompressed)
        assertTrue(GzipCodec.isGzip(decrypted))

        val innerDecompressed = GzipCodec.decompress(decrypted)
        val resultJson = JSONObject(String(innerDecompressed, Charsets.UTF_8))

        val priv = resultJson.getJSONObject("privilege")
        assertEquals(0, priv.getInt("st"))
        assertEquals(1, priv.getInt("cp"))
        assertEquals(0, priv.getInt("fee"))
        assertEquals(320000, priv.getInt("pl"))
    }
}
