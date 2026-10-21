package pochita.resolver

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import pochita.common.UnmLruCache
import pochita.model.MatchedAudio
import pochita.model.SongInfo

class PlayerUrlResolverTest {

    private lateinit var songCache: UnmLruCache<Long, SongInfo>

    @Before
    fun setUp() {
        songCache = UnmLruCache(100)
    }

    @Test
    fun testPreserveValidPlayableSong() {
        var matcherCalled = false
        val resolver = PlayerUrlResolver(
            songCache = songCache,
            audioMatcher = {
                matcherCalled = true
                MatchedAudio("http://should.not.be.used/audio.mp3")
            },
            songDetailFetcher = null
        )

        val json = """
            {
                "code": 200,
                "data": [
                    {
                        "id": 10001,
                        "url": "http://m701.music.126.net/valid.mp3",
                        "br": 320000,
                        "code": 200,
                        "size": 8500000,
                        "trialMode": 0
                    }
                ]
            }
        """.trimIndent()

        val result = resolver.processPlayerUrl("https://interface.music.163.com/eapi/song/enhance/player/url", json)
        assertNull("Valid playable audio must be preserved and return null", result)
        assertFalse("Matcher must not be called for valid audio", matcherCalled)
    }

    @Test
    fun testReplaceUnplayableSongFromCache() {
        songCache.put(101L, SongInfo(101L, "TestSong", "TestArtist", 200000L))

        val resolver = PlayerUrlResolver(
            songCache = songCache,
            audioMatcher = { song ->
                if (song.id == 101L) {
                    MatchedAudio("http://backup.cdn/101.mp3", br = 320000, size = 6000000L, md5 = "hash101", source = "mock-kuwo")
                } else null
            },
            songDetailFetcher = null
        )

        val json = """
            {
                "code": 200,
                "data": [
                    {
                        "id": 101,
                        "url": "",
                        "br": 0,
                        "code": 404
                    }
                ]
            }
        """.trimIndent()

        val result = resolver.processPlayerUrl("https://interface.music.163.com/eapi/song/enhance/player/url", json)
        assertNotNull(result)

        val root = JSONObject(result!!)
        val item = root.getJSONArray("data").getJSONObject(0)
        assertEquals("http://backup.cdn/101.mp3", item.getString("url"))
        assertEquals(320000, item.getInt("br"))
        assertEquals(200, item.getInt("code"))
        assertEquals(0, item.getInt("fee"))
        assertEquals(1, item.getInt("payed"))
        assertEquals("exhigh", item.getString("level"))
        assertEquals("mp3", item.getString("type"))
        assertEquals(6000000L, item.getLong("size"))
        assertEquals("hash101", item.getString("md5"))
    }

    @Test
    fun testReplaceTrialSongClearsTrialInfo() {
        songCache.put(202L, SongInfo(202L, "TrialSong", "Artist2", 180000L))

        val resolver = PlayerUrlResolver(
            songCache = songCache,
            audioMatcher = { song ->
                MatchedAudio("http://full.cdn/202.mp3", br = 320000, source = "mock-bodian")
            },
            songDetailFetcher = null
        )

        val json = """
            {
                "code": 200,
                "data": [
                    {
                        "id": 202,
                        "url": "http://trial.net/clip.mp3",
                        "br": 128000,
                        "code": 200,
                        "freeTrialInfo": {"start": 0, "end": 30},
                        "freeTrialPrivilege": {"userConsumable": false},
                        "freeTimeTrialPrivilege": {"type": 1},
                        "urlTrial": "true",
                        "trialMode": 1
                    }
                ]
            }
        """.trimIndent()

        val result = resolver.processPlayerUrl("https://interface.music.163.com/eapi/song/enhance/player/url", json)
        assertNotNull(result)

        val root = JSONObject(result!!)
        val item = root.getJSONArray("data").getJSONObject(0)
        assertEquals("http://full.cdn/202.mp3", item.getString("url"))
        assertEquals(320000, item.getInt("br"))
        assertFalse(item.has("freeTrialInfo"))
        assertFalse(item.has("freeTrialPrivilege"))
        assertFalse(item.has("freeTimeTrialPrivilege"))
        assertFalse(item.has("urlTrial"))
        assertEquals(0, item.getInt("trialMode"))
    }

    @Test
    fun testCacheMissFallsBackToDetailFetcher() {
        var fetcherCalled = false
        val resolver = PlayerUrlResolver(
            songCache = songCache,
            audioMatcher = { song ->
                MatchedAudio("http://matched.cdn/${song.id}.mp3", br = 320000)
            },
            songDetailFetcher = { id ->
                fetcherCalled = true
                SongInfo(id, "FetchedOnline", "Artist3", 210000L)
            }
        )

        val json = """
            {
                "code": 200,
                "data": [
                    {
                        "id": 303,
                        "url": null,
                        "br": 0,
                        "code": 404
                    }
                ]
            }
        """.trimIndent()

        val result = resolver.processPlayerUrl("https://interface.music.163.com/eapi/song/enhance/player/url", json)
        assertNotNull(result)
        assertTrue(fetcherCalled)
        assertNotNull(songCache.get(303L))
        assertEquals("FetchedOnline", songCache.get(303L)?.name)

        val root = JSONObject(result!!)
        val item = root.getJSONArray("data").getJSONObject(0)
        assertEquals("http://matched.cdn/303.mp3", item.getString("url"))
    }

    @Test
    fun testMatcherReturnsNullLeavesTrackUntouched() {
        songCache.put(404L, SongInfo(404L, "NoMatchSong", "Artist4", 150000L))

        val resolver = PlayerUrlResolver(
            songCache = songCache,
            audioMatcher = { null },
            songDetailFetcher = null
        )

        val json = """
            {
                "code": 200,
                "data": [
                    {
                        "id": 404,
                        "url": "",
                        "br": 0,
                        "code": 404
                    }
                ]
            }
        """.trimIndent()

        val result = resolver.processPlayerUrl("https://interface.music.163.com/eapi/song/enhance/player/url", json)
        assertNull("If matcher returns null for all restricted songs, result should be null", result)
    }

    @Test
    fun testNonPlayerUrlReturnsNullImmediately() {
        val resolver = PlayerUrlResolver(
            songCache = songCache,
            audioMatcher = { MatchedAudio("http://never.call/audio.mp3") },
            songDetailFetcher = null
        )

        val json = """{"code":200,"data":[]}"""
        val result = resolver.processPlayerUrl("https://interface.music.163.com/eapi/v3/playlist/detail", json)
        assertNull(result)
    }

    @Test
    fun testMixedBatchTracksPreservesPlayableAndReplacesRestricted() {
        songCache.put(2L, SongInfo(2L, "TrackTwo", "Artist2", 180000L))
        songCache.put(3L, SongInfo(3L, "TrackThree", "Artist3", 190000L))

        val resolver = PlayerUrlResolver(
            songCache = songCache,
            audioMatcher = { song ->
                if (song.id == 2L) {
                    MatchedAudio("http://replaced.cdn/2.mp3", br = 320000)
                } else null
            },
            songDetailFetcher = null
        )

        val json = """
            {
                "code": 200,
                "data": [
                    {
                        "id": 1,
                        "url": "http://m701.music.126.net/original_valid.mp3",
                        "br": 320000,
                        "code": 200
                    },
                    {
                        "id": 2,
                        "url": "http://trial.net/2.mp3",
                        "br": 128000,
                        "trialMode": 1
                    },
                    {
                        "id": 3,
                        "url": "",
                        "br": 0,
                        "code": 404
                    }
                ]
            }
        """.trimIndent()

        val result = resolver.processPlayerUrl("https://interface.music.163.com/eapi/song/enhance/player/url", json)
        assertNotNull(result)

        val root = JSONObject(result!!)
        val data = root.getJSONArray("data")

        // Track 1: Original preserved
        val t1 = data.getJSONObject(0)
        assertEquals("http://m701.music.126.net/original_valid.mp3", t1.getString("url"))

        // Track 2: Replaced & trialMode cleared
        val t2 = data.getJSONObject(1)
        assertEquals("http://replaced.cdn/2.mp3", t2.getString("url"))
        assertEquals(0, t2.getInt("trialMode"))

        // Track 3: Unmatchable, left unmodified
        val t3 = data.getJSONObject(2)
        assertEquals("", t3.getString("url"))
    }

    @Test
    fun testZeroBitrateTreatedAsRestrictedAndReplaced() {
        songCache.put(505L, SongInfo(505L, "ZeroBrSong", "Artist5", 220000L))

        val resolver = PlayerUrlResolver(
            songCache = songCache,
            audioMatcher = {
                MatchedAudio("http://new.cdn/505.mp3", br = 320000)
            },
            songDetailFetcher = null
        )

        val json = """
            {
                "code": 200,
                "data": [
                    {
                        "id": 505,
                        "url": "http://stub.net/dummy.mp3",
                        "br": 0,
                        "code": 200
                    }
                ]
            }
        """.trimIndent()

        val result = resolver.processPlayerUrl("https://interface.music.163.com/eapi/song/enhance/player/url", json)
        assertNotNull(result)

        val root = JSONObject(result!!)
        val item = root.getJSONArray("data").getJSONObject(0)
        assertEquals("http://new.cdn/505.mp3", item.getString("url"))
        assertEquals(320000, item.getInt("br"))
    }
}
