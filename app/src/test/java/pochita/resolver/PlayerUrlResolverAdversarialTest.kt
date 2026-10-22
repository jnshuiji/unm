package pochita.resolver

import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import pochita.common.UnmLruCache
import pochita.model.MatchedAudio
import pochita.model.SongInfo

/**
 * Empirical Adversarial Challenge Suite for [PlayerUrlResolver].
 *
 * Verifies:
 * 1. Playable audio preservation invariant:
 *    - In large mixed batches (100 songs), playable songs are NEVER proxied or replaced.
 *    - In all-playable batches, processPlayerUrl returns null with zero mutations.
 * 2. Unplayable / restricted track replacement:
 *    - Empty URL (""), JSON null URL, string "null", whitespace URL.
 *    - 0 bitrate, negative bitrate.
 *    - Non-200 status codes (404, 500, -100).
 *    - Non-HTTP/HTTPS URLs (ftp, file, javascript, content).
 *    - Trial modes (trialMode != 0, freeTrialInfo, freeTrialPrivilege, freeTimeTrialPrivilege).
 *    - Trial info stripping (freeTrialInfo, freeTrialPrivilege, freeTimeTrialPrivilege, urlTrial removed, trialMode=0).
 * 3. Cache fallback and miss handling:
 *    - Cache hit uses cached SongInfo without calling fetcher.
 *    - Cache miss triggers fallback fetcher; successful fetch updates cache and replaces track.
 *    - Cache miss with fetcher returning null or throwing leaves track untouched.
 *    - Matcher returning null or empty URL leaves track untouched.
 * 4. Fuzzing & Malformed payloads:
 *    - Non-JSON, non-object root, missing data array, non-array data, data array containing primitives.
 *    - Negative song IDs, 0 song ID, huge Long IDs (Long.MAX_VALUE).
 * 5. Concurrent multi-threaded stress:
 *    - High-concurrency simultaneous calls to processPlayerUrl on shared resolver.
 */
class PlayerUrlResolverAdversarialTest {

    private lateinit var songCache: UnmLruCache<Long, SongInfo>
    private val playerUrlEndpoint = "https://interface.music.163.com/eapi/song/enhance/player/url"

    @Before
    fun setUp() {
        songCache = UnmLruCache(200)
    }

    // =========================================================================
    // SECTION 1: Playable Audio Strict Preservation Invariant (Mixed Batches)
    // =========================================================================

    @Test
    fun testLargeMixedBatch_strictlyPreservesPlayableTracksAndReplacesRestricted() {
        val totalSongs = 100
        val replacedSongIds = mutableSetOf<Long>()
        val preservedSongIds = mutableSetOf<Long>()
        val matcherCallCount = AtomicInteger(0)

        // Pre-populate songCache for unplayable tracks (odd IDs)
        for (i in 0 until totalSongs) {
            val songId = 1000L + i
            if (i % 2 == 1) {
                // Restricted track: add to cache
                songCache.put(songId, SongInfo(songId, "RestrictedTrack_$songId", "Artist_$songId", 200000L))
                replacedSongIds.add(songId)
            } else {
                // Playable track
                preservedSongIds.add(songId)
            }
        }

        val resolver = PlayerUrlResolver(
            songCache = songCache,
            audioMatcher = { song ->
                matcherCallCount.incrementAndGet()
                if (preservedSongIds.contains(song.id)) {
                    fail("ADVERSARIAL VIOLATION: audioMatcher was invoked for playable song id=${song.id}!")
                }
                MatchedAudio(
                    url = "https://cdn.replacement.audio/stream_${song.id}.mp3",
                    br = 320000,
                    size = 5500000L,
                    md5 = "md5_hash_${song.id}",
                    source = "adversarial-mock"
                )
            },
            songDetailFetcher = null
        )

        // Construct 100-track JSON payload with alternating playable and restricted tracks
        val rootJson = JSONObject()
        rootJson.put("code", 200)
        val dataArray = JSONArray()

        for (i in 0 until totalSongs) {
            val songId = 1000L + i
            val item = JSONObject()
            item.put("id", songId)

            if (i % 2 == 0) {
                // 100% Playable track with distinct legitimate attributes
                item.put("url", "https://m701.music.126.net/original_playable_$songId.mp3")
                item.put("br", 320000)
                item.put("code", 200)
                item.put("fee", 8)
                item.put("payed", 0)
                item.put("size", 8400000L)
                item.put("md5", "orig_md5_$songId")
                item.put("type", "flac")
                item.put("level", "lossless")
                item.put("trialMode", 0)
            } else {
                // Restricted track with varying defect types
                when (i % 8) {
                    1 -> { item.put("url", ""); item.put("br", 0); item.put("code", 404) }
                    3 -> { item.put("url", JSONObject.NULL); item.put("br", 0); item.put("code", 200) }
                    5 -> {
                        item.put("url", "https://m701.music.126.net/trial_$songId.mp3")
                        item.put("br", 128000)
                        item.put("code", 200)
                        item.put("trialMode", 1)
                        item.put("freeTrialInfo", JSONObject("""{"start":0,"end":30}"""))
                    }
                    7 -> {
                        item.put("url", "https://m701.music.126.net/privilege_trial_$songId.mp3")
                        item.put("br", 128000)
                        item.put("freeTrialPrivilege", JSONObject("""{"userConsumable":false,"cannotListenReason":1}"""))
                    }
                }
            }
            dataArray.put(item)
        }
        rootJson.put("data", dataArray)

        val resultStr = resolver.processPlayerUrl(playerUrlEndpoint, rootJson.toString())
        assertNotNull("Result should not be null when restricted tracks are present", resultStr)

        val resultJson = JSONObject(resultStr!!)
        val resultData = resultJson.getJSONArray("data")
        assertEquals(totalSongs, resultData.length())

        // Validate each track independently
        for (i in 0 until totalSongs) {
            val songId = 1000L + i
            val item = resultData.getJSONObject(i)
            assertEquals(songId, item.getLong("id"))

            if (i % 2 == 0) {
                // PLAYABLE TRACK: Must be 100% untouched
                assertEquals("Playable track url must NOT be modified", "https://m701.music.126.net/original_playable_$songId.mp3", item.getString("url"))
                assertEquals("Playable track br must NOT be modified", 320000, item.getInt("br"))
                assertEquals("Playable track code must NOT be modified", 200, item.getInt("code"))
                assertEquals("Playable track fee must NOT be modified", 8, item.getInt("fee"))
                assertEquals("Playable track payed must NOT be modified", 0, item.getInt("payed"))
                assertEquals("Playable track size must NOT be modified", 8400000L, item.getLong("size"))
                assertEquals("Playable track md5 must NOT be modified", "orig_md5_$songId", item.getString("md5"))
                assertEquals("Playable track type must NOT be modified", "flac", item.getString("type"))
                assertEquals("Playable track level must NOT be modified", "lossless", item.getString("level"))
            } else {
                // RESTRICTED TRACK: Must be replaced properly
                assertEquals("https://cdn.replacement.audio/stream_$songId.mp3", item.getString("url"))
                assertEquals(320000, item.getInt("br"))
                assertEquals(200, item.getInt("code"))
                assertEquals(0, item.getInt("fee"))
                assertEquals(1, item.getInt("payed"))
                assertEquals("exhigh", item.getString("level"))
                assertEquals("mp3", item.getString("type"))
                assertEquals(5500000L, item.getLong("size"))
                assertEquals("md5_hash_$songId", item.getString("md5"))
                assertFalse("freeTrialInfo must be removed", item.has("freeTrialInfo"))
                assertFalse("freeTrialPrivilege must be removed", item.has("freeTrialPrivilege"))
                assertFalse("freeTimeTrialPrivilege must be removed", item.has("freeTimeTrialPrivilege"))
                assertEquals(0, item.optInt("trialMode", 0))
            }
        }

        assertEquals(50, matcherCallCount.get())
    }

    @Test
    fun testAllPlayableBatch_returnsNullWithZeroAllocations() {
        var matcherInvoked = false
        val resolver = PlayerUrlResolver(
            songCache = songCache,
            audioMatcher = {
                matcherInvoked = true
                MatchedAudio("https://should.not.be.used/audio.mp3")
            },
            songDetailFetcher = null
        )

        val rootJson = JSONObject()
        rootJson.put("code", 200)
        val dataArray = JSONArray()

        for (i in 1..25) {
            val item = JSONObject()
            item.put("id", i.toLong())
            item.put("url", "https://m801.music.126.net/valid_song_$i.mp3")
            item.put("br", 320000)
            item.put("code", 200)
            item.put("trialMode", 0)
            dataArray.put(item)
        }
        rootJson.put("data", dataArray)

        val result = resolver.processPlayerUrl(playerUrlEndpoint, rootJson.toString())
        assertNull("Batch of all-playable songs MUST return null to allow fast zero-copy passthrough", result)
        assertFalse("Audio matcher must NEVER be called when all songs are playable", matcherInvoked)
    }

    // =========================================================================
    // SECTION 2: Adversarial Defect & Trial Pattern Matrix
    // =========================================================================

    @Test
    fun testUnplayableVariationsMatrix() {
        data class DefectCase(
            val name: String,
            val songId: Long,
            val setupItem: (JSONObject) -> Unit
        )

        val defectCases = listOf(
            DefectCase("1. Empty URL", 2001L) {
                it.put("url", "")
                it.put("br", 320000)
                it.put("code", 200)
            },
            DefectCase("2. Blank Whitespace URL", 2002L) {
                it.put("url", "   \t  ")
                it.put("br", 320000)
                it.put("code", 200)
            },
            DefectCase("3. JSON Null URL", 2003L) {
                it.put("url", JSONObject.NULL)
                it.put("br", 320000)
                it.put("code", 200)
            },
            DefectCase("4. Literal String 'null' URL", 2004L) {
                it.put("url", "null")
                it.put("br", 320000)
                it.put("code", 200)
            },
            DefectCase("5. Zero Bitrate with Valid URL", 2005L) {
                it.put("url", "https://valid.url/song.mp3")
                it.put("br", 0)
                it.put("code", 200)
            },
            DefectCase("6. Negative Bitrate with Valid URL", 2006L) {
                it.put("url", "https://valid.url/song.mp3")
                it.put("br", -128000)
                it.put("code", 200)
            },
            DefectCase("7. Error Code 404", 2007L) {
                it.put("url", "https://valid.url/song.mp3")
                it.put("br", 320000)
                it.put("code", 404)
            },
            DefectCase("8. Negative Error Code -100", 2008L) {
                it.put("url", "https://valid.url/song.mp3")
                it.put("br", 320000)
                it.put("code", -100)
            },
            DefectCase("9. Non-HTTP Scheme (FTP)", 2009L) {
                it.put("url", "ftp://music.storage/song.mp3")
                it.put("br", 320000)
                it.put("code", 200)
            },
            DefectCase("10. Non-HTTP Scheme (File)", 2010L) {
                it.put("url", "file:///system/audio.mp3")
                it.put("br", 320000)
                it.put("code", 200)
            },
            DefectCase("11. trialMode = 1 with valid URL", 2011L) {
                it.put("url", "https://valid.url/trial.mp3")
                it.put("br", 320000)
                it.put("code", 200)
                it.put("trialMode", 1)
            },
            DefectCase("12. trialMode = 2 with valid URL", 2012L) {
                it.put("url", "https://valid.url/trial.mp3")
                it.put("br", 320000)
                it.put("code", 200)
                it.put("trialMode", 2)
            },
            DefectCase("13. freeTrialInfo Present", 2013L) {
                it.put("url", "https://valid.url/trial.mp3")
                it.put("br", 320000)
                it.put("code", 200)
                it.put("freeTrialInfo", JSONObject("""{"start":0,"end":30}"""))
            },
            DefectCase("14. freeTrialPrivilege with cannotListenReason Present", 2014L) {
                it.put("url", "https://valid.url/trial.mp3")
                it.put("br", 320000)
                it.put("code", 200)
                it.put("freeTrialPrivilege", JSONObject("""{"resConsumable":false,"cannotListenReason":1}"""))
            },
            DefectCase("15. freeTimeTrialPrivilege Present", 2015L) {
                it.put("url", "https://valid.url/trial.mp3")
                it.put("br", 320000)
                it.put("code", 200)
                it.put("freeTimeTrialPrivilege", JSONObject("""{"type":1}"""))
            },
            DefectCase("16. urlTrial Present with freeTrialInfo", 2016L) {
                it.put("url", "https://valid.url/trial.mp3")
                it.put("br", 320000)
                it.put("code", 200)
                it.put("freeTrialInfo", JSONObject("""{"start":0,"end":30}"""))
                it.put("urlTrial", "https://valid.url/trial.mp3")
            }
        )

        for (defect in defectCases) {
            songCache.put(defect.songId, SongInfo(defect.songId, "Song_${defect.songId}", "Artist_${defect.songId}", 240000L))

            val resolver = PlayerUrlResolver(
                songCache = songCache,
                audioMatcher = { song ->
                    MatchedAudio(
                        url = "https://unm.replaced/${song.id}.mp3",
                        br = 320000,
                        size = 6000000L,
                        md5 = "hash_${song.id}"
                    )
                },
                songDetailFetcher = null
            )

            val rootJson = JSONObject()
            rootJson.put("code", 200)
            val dataArray = JSONArray()
            val item = JSONObject()
            item.put("id", defect.songId)
            defect.setupItem(item)
            dataArray.put(item)
            rootJson.put("data", dataArray)

            val result = resolver.processPlayerUrl(playerUrlEndpoint, rootJson.toString())
            assertNotNull("${defect.name} MUST trigger replacement and return non-null", result)

            val resItem = JSONObject(result!!).getJSONArray("data").getJSONObject(0)
            assertEquals("${defect.name}: URL replacement", "https://unm.replaced/${defect.songId}.mp3", resItem.getString("url"))
            assertEquals("${defect.name}: br reset to 320000", 320000, resItem.getInt("br"))
            assertEquals("${defect.name}: code reset to 200", 200, resItem.getInt("code"))
            assertEquals("${defect.name}: fee reset to 0", 0, resItem.getInt("fee"))
            assertEquals("${defect.name}: payed reset to 1", 1, resItem.getInt("payed"))
            assertFalse("${defect.name}: freeTrialInfo must be stripped", resItem.has("freeTrialInfo"))
            assertFalse("${defect.name}: freeTrialPrivilege must be stripped", resItem.has("freeTrialPrivilege"))
            assertFalse("${defect.name}: freeTimeTrialPrivilege must be stripped", resItem.has("freeTimeTrialPrivilege"))
            assertFalse("${defect.name}: urlTrial must be stripped", resItem.has("urlTrial"))
            assertEquals("${defect.name}: trialMode must be reset to 0", 0, resItem.optInt("trialMode", 0))
        }
    }

    @Test
    fun testPlayableVipSongWithFreeTrialPrivilegeIsPreserved() {
        val songId = 889900L
        songCache.put(songId, SongInfo(songId, "戒不掉 (原声版)", "欧豪", 210000L))

        var audioMatcherCalled = false
        val resolver = PlayerUrlResolver(
            songCache = songCache,
            audioMatcher = {
                audioMatcherCalled = true
                MatchedAudio("https://unm.replaced/$songId.mp3", br = 320000)
            },
            songDetailFetcher = null
        )

        val rootJson = JSONObject()
        rootJson.put("code", 200)
        val dataArray = JSONArray()
        val item = JSONObject()
        item.put("id", songId)
        item.put("url", "https://m701.music.126.net/official_vip_$songId.mp3")
        item.put("br", 320000)
        item.put("code", 200)
        item.put("fee", 1)
        item.put("payed", 0) // In NetEase API, payed is 0 for VIP subscription tracks
        item.put("trialMode", 0)
        item.put("freeTrialPrivilege", JSONObject("""{"resConsumable":false,"userConsumable":false,"cannotListenReason":null}"""))
        item.put("freeTimeTrialPrivilege", JSONObject("""{"type":0,"remainTime":0}"""))
        item.put("freeTrialInfo", JSONObject.NULL)
        dataArray.put(item)
        rootJson.put("data", dataArray)

        val result = resolver.processPlayerUrl(playerUrlEndpoint, rootJson.toString())
        assertNull("VIP track with valid URL and cannotListenReason=null MUST NOT be replaced, returning null (unmodified)", result)
        assertFalse("AudioMatcher must not be called for playable VIP track", audioMatcherCalled)
    }

    @Test
    fun testRestrictedTrackGeneratesOrPreservesValidMd5() {
        val songId = 998877L
        songCache.put(songId, SongInfo(songId, "测试无MD5歌曲", "歌手", 180000L))

        val resolver = PlayerUrlResolver(
            songCache = songCache,
            audioMatcher = {
                // Return matched audio with empty MD5
                MatchedAudio("https://replacement.cdn/song.mp3", br = 320000, md5 = "")
            },
            songDetailFetcher = null
        )

        val rootJson = JSONObject()
        rootJson.put("code", 200)
        val dataArray = JSONArray()
        val item = JSONObject()
        item.put("id", songId)
        item.put("url", "")
        item.put("br", 0)
        item.put("code", 404)
        dataArray.put(item)
        rootJson.put("data", dataArray)

        val result = resolver.processPlayerUrl(playerUrlEndpoint, rootJson.toString())
        assertNotNull(result)
        val resItem = JSONObject(result!!).getJSONArray("data").getJSONObject(0)
        val md5 = resItem.getString("md5")
        assertTrue("MD5 must be present and 32-character hex to prevent player crash", md5.length == 32)
    }

    // =========================================================================
    // SECTION 3: Cache Fallback, Cache Miss & Error Handling
    // =========================================================================

    @Test
    fun testCacheHitDoesNotTriggerDetailFetcher() {
        songCache.put(3001L, SongInfo(3001L, "CachedTrack", "CachedArtist", 180000L))
        var fetcherCalled = false

        val resolver = PlayerUrlResolver(
            songCache = songCache,
            audioMatcher = { song ->
                assertEquals(3001L, song.id)
                assertEquals("CachedTrack", song.name)
                MatchedAudio("https://matched.cdn/3001.mp3", br = 320000)
            },
            songDetailFetcher = {
                fetcherCalled = true
                null
            }
        )

        val json = """{"code":200,"data":[{"id":3001,"url":"","br":0,"code":404}]}"""
        val result = resolver.processPlayerUrl(playerUrlEndpoint, json)

        assertNotNull(result)
        assertFalse("Detail fetcher must NOT be called on cache hit", fetcherCalled)
    }

    @Test
    fun testCacheMissWithSuccessfulFetcherPopulatesCacheAndReplaces() {
        var fetcherCallCount = 0
        val resolver = PlayerUrlResolver(
            songCache = songCache,
            audioMatcher = { song ->
                MatchedAudio("https://matched.cdn/${song.id}.mp3", br = 320000)
            },
            songDetailFetcher = { id ->
                fetcherCallCount++
                SongInfo(id, "OnlineSong_$id", "OnlineArtist_$id", 210000L)
            }
        )

        val json = """{"code":200,"data":[{"id":4001,"url":"","br":0,"code":404}]}"""
        val result = resolver.processPlayerUrl(playerUrlEndpoint, json)

        assertNotNull(result)
        assertEquals(1, fetcherCallCount)

        // Cache must now contain the fetched song
        val cached = songCache.get(4001L)
        assertNotNull(cached)
        assertEquals("OnlineSong_4001", cached?.name)
        assertEquals("OnlineArtist_4001", cached?.artist)

        // Second call with same ID should hit cache
        val result2 = resolver.processPlayerUrl(playerUrlEndpoint, json)
        assertNotNull(result2)
        assertEquals("Fetcher must not be called a second time due to cache hit", 1, fetcherCallCount)
    }

    @Test
    fun testCacheMissWithFetcherReturningNullLeavesTrackUntouched() {
        var fetcherCalled = false
        val resolver = PlayerUrlResolver(
            songCache = songCache,
            audioMatcher = { song ->
                fail("Matcher must not be called if SongInfo could not be resolved!")
                null
            },
            songDetailFetcher = {
                fetcherCalled = true
                null // Remote fetch failed / returned null
            }
        )

        val json = """{"code":200,"data":[{"id":5001,"url":"","br":0,"code":404}]}"""
        val result = resolver.processPlayerUrl(playerUrlEndpoint, json)

        assertTrue(fetcherCalled)
        assertNull("If track cannot be resolved, result should be null (unmodified)", result)
        assertNull(songCache.get(5001L))
    }

    @Test
    fun testCacheMissWithFetcherThrowingExceptionGracefullyHandled() {
        val resolver = PlayerUrlResolver(
            songCache = songCache,
            audioMatcher = { null },
            songDetailFetcher = {
                throw RuntimeException("Simulated network timeout / socket exception")
            }
        )

        val json = """{"code":200,"data":[{"id":6001,"url":"","br":0,"code":404}]}"""
        val result = resolver.processPlayerUrl(playerUrlEndpoint, json)

        // Must not throw exception out of processPlayerUrl
        assertNull("Fetcher exception must be silently caught, leaving track untouched", result)
    }

    @Test
    fun testAudioMatcherReturnsNullLeavesTrackUntouchedInBatch() {
        songCache.put(7001L, SongInfo(7001L, "Matchable", "Artist1", 200000L))
        songCache.put(7002L, SongInfo(7002L, "Unmatchable", "Artist2", 200000L))

        val resolver = PlayerUrlResolver(
            songCache = songCache,
            audioMatcher = { song ->
                if (song.id == 7001L) {
                    MatchedAudio("https://matched.cdn/7001.mp3", br = 320000)
                } else {
                    null // 7002 has no match from sound sources
                }
            },
            songDetailFetcher = null
        )

        val json = """
            {
                "code": 200,
                "data": [
                    {"id": 7001, "url": "", "br": 0, "code": 404},
                    {"id": 7002, "url": "", "br": 0, "code": 404}
                ]
            }
        """.trimIndent()

        val result = resolver.processPlayerUrl(playerUrlEndpoint, json)
        assertNotNull(result)

        val resultJson = JSONObject(result!!)
        val data = resultJson.getJSONArray("data")

        // Track 7001 replaced
        val item1 = data.getJSONObject(0)
        assertEquals("https://matched.cdn/7001.mp3", item1.getString("url"))

        // Track 7002 unmatchable, left untouched
        val item2 = data.getJSONObject(1)
        assertEquals("", item2.getString("url"))
        assertEquals(0, item2.getInt("br"))
        assertEquals(404, item2.getInt("code"))
    }

    // =========================================================================
    // SECTION 4: Adversarial Fuzzing & Malformed Payloads
    // =========================================================================

    @Test
    fun testFuzzingMalformedJsonAndStructure() {
        val resolver = PlayerUrlResolver(
            songCache = songCache,
            audioMatcher = { MatchedAudio("https://cdn/audio.mp3") },
            songDetailFetcher = null
        )

        val badInputs = listOf(
            "",
            "   ",
            "\n\t\r",
            "not a json string at all",
            "{",
            "{ \"code\": 200",
            "[1, 2, 3]",
            "\"just a string\"",
            "42",
            "true",
            "null",
            "{\"code\": 200}", // No "data" field
            "{\"code\": 200, \"data\": null}",
            "{\"code\": 200, \"data\": \"not an array\"}",
            "{\"code\": 200, \"data\": 12345}",
            "{\"code\": 200, \"data\": [\"str1\", \"str2\"]}", // array of non-objects
            "{\"code\": 200, \"data\": [null, null]}",
            "{\"code\": 200, \"data\": [{}]}", // object without id
            "{\"code\": 200, \"data\": [{\"id\": 0}]}", // id = 0
            "{\"code\": 200, \"data\": [{\"id\": -999}]}" // negative id
        )

        for (bad in badInputs) {
            try {
                val res = resolver.processPlayerUrl(playerUrlEndpoint, bad)
                assertNull("Bad input should return null: '$bad'", res)
            } catch (t: Throwable) {
                fail("processPlayerUrl crashed on input '$bad': ${t.javaClass.name}: ${t.message}")
            }
        }
    }

    @Test
    fun testHugeLongSongIdHandling() {
        val hugeId = Long.MAX_VALUE // 9223372036854775807L
        songCache.put(hugeId, SongInfo(hugeId, "HugeIdSong", "Artist", 200000L))

        val resolver = PlayerUrlResolver(
            songCache = songCache,
            audioMatcher = { song ->
                assertEquals(hugeId, song.id)
                MatchedAudio("https://matched.cdn/$hugeId.mp3", br = 320000)
            },
            songDetailFetcher = null
        )

        val json = """{"code":200,"data":[{"id":$hugeId,"url":"","br":0,"code":404}]}"""
        val result = resolver.processPlayerUrl(playerUrlEndpoint, json)

        assertNotNull(result)
        val resItem = JSONObject(result!!).getJSONArray("data").getJSONObject(0)
        assertEquals(hugeId, resItem.getLong("id"))
        assertEquals("https://matched.cdn/$hugeId.mp3", resItem.getString("url"))
    }

    @Test
    fun testStaticAssetAndNonPlayerUrlIgnored() {
        val resolver = PlayerUrlResolver(
            songCache = songCache,
            audioMatcher = { MatchedAudio("https://matched.cdn/audio.mp3") },
            songDetailFetcher = null
        )

        val json = """{"code":200,"data":[{"id":100,"url":"","br":0,"code":404}]}"""

        // URLs with image extensions should be ignored
        assertNull(resolver.processPlayerUrl("https://music.163.com/player/url/cover.jpg", json))
        assertNull(resolver.processPlayerUrl("https://music.163.com/player/url/avatar.png", json))
        assertNull(resolver.processPlayerUrl("https://music.163.com/player/url/preview.webp", json))

        // Non-player URLs
        assertNull(resolver.processPlayerUrl("https://interface.music.163.com/eapi/v3/playlist/detail", json))
        assertNull(resolver.processPlayerUrl("https://music.163.com/api/v1/album", json))
    }

    // =========================================================================
    // SECTION 5: Concurrent Multi-Threaded Stress Test
    // =========================================================================

    @Test
    fun testConcurrentPlayerUrlResolvingStress() {
        val numThreads = 10
        val iterationsPerThread = 50
        val executor = Executors.newFixedThreadPool(numThreads)
        val latch = CountDownLatch(numThreads)
        val failureCount = AtomicInteger(0)

        // Seed cache with 100 songs
        for (i in 0 until 100) {
            val id = 8000L + i
            songCache.put(id, SongInfo(id, "StressSong_$id", "StressArtist_$id", 200000L))
        }

        val resolver = PlayerUrlResolver(
            songCache = songCache,
            audioMatcher = { song ->
                MatchedAudio("https://cdn.stress/${song.id}.mp3", br = 320000)
            },
            songDetailFetcher = null
        )

        for (t in 0 until numThreads) {
            executor.submit {
                try {
                    for (iter in 0 until iterationsPerThread) {
                        // Alternate between all-playable and mixed batch
                        if (iter % 2 == 0) {
                            // All playable
                            val playableJson = """
                                {
                                    "code": 200,
                                    "data": [
                                        {"id": 8001, "url": "https://m701.music.126.net/valid1.mp3", "br": 320000, "code": 200},
                                        {"id": 8002, "url": "https://m701.music.126.net/valid2.mp3", "br": 320000, "code": 200}
                                    ]
                                }
                            """.trimIndent()
                            val res = resolver.processPlayerUrl(playerUrlEndpoint, playableJson)
                            if (res != null) {
                                failureCount.incrementAndGet()
                            }
                        } else {
                            // Mixed: one playable, one restricted
                            val mixedJson = """
                                {
                                    "code": 200,
                                    "data": [
                                        {"id": 8010, "url": "https://m701.music.126.net/valid.mp3", "br": 320000, "code": 200},
                                        {"id": 8011, "url": "", "br": 0, "code": 404}
                                    ]
                                }
                            """.trimIndent()
                            val res = resolver.processPlayerUrl(playerUrlEndpoint, mixedJson)
                            if (res == null) {
                                failureCount.incrementAndGet()
                            } else {
                                val obj = JSONObject(res)
                                val data = obj.getJSONArray("data")
                                val t1 = data.getJSONObject(0)
                                val t2 = data.getJSONObject(1)
                                if (t1.getString("url") != "https://m701.music.126.net/valid.mp3") failureCount.incrementAndGet()
                                if (t2.getString("url") != "https://cdn.stress/8011.mp3") failureCount.incrementAndGet()
                            }
                        }
                    }
                } catch (t: Throwable) {
                    failureCount.incrementAndGet()
                } finally {
                    latch.countDown()
                }
            }
        }

        assertTrue("Stress test timed out", latch.await(10, TimeUnit.SECONDS))
        executor.shutdown()
        assertEquals("Concurrent stress test encountered errors", 0, failureCount.get())
    }
}
