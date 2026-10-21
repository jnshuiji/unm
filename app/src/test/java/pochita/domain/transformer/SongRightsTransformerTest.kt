package pochita.domain.transformer

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import pochita.model.SongInfo

class SongRightsTransformerTest {

    @Test
    fun testUnblockSingleTrackAndPrivilege() {
        val json = """
            {
                "code": 200,
                "song": {
                    "id": 12345,
                    "name": "SingleTrack",
                    "fee": 1,
                    "st": -200,
                    "cp": 0,
                    "status": -1,
                    "pl": 0,
                    "dl": 0,
                    "maxbr": 128000,
                    "subp": 0,
                    "ar": [{"id": 1, "name": "ArtistOne"}],
                    "dt": 180000,
                    "privilege": {
                        "id": 12345,
                        "st": -200,
                        "cp": 0,
                        "pl": 0,
                        "dl": 0,
                        "fee": 1,
                        "subp": 0,
                        "maxbr": 128000
                    }
                }
            }
        """.trimIndent()

        val result = SongRightsTransformer.transformResponseJson(json)
        assertTrue(result.hasModified)
        assertEquals(1, result.discoveredSongs.size)
        val discovered = result.discoveredSongs[0]
        assertEquals(12345L, discovered.id)
        assertEquals("SingleTrack", discovered.name)
        assertEquals("ArtistOne", discovered.artist)
        assertEquals(180000L, discovered.durationMs)

        val root = JSONObject(result.modifiedJson)
        val song = root.getJSONObject("song")
        assertEquals(0, song.getInt("fee"))
        assertEquals(0, song.getInt("st"))
        assertEquals(1, song.getInt("cp"))
        assertEquals(0, song.getInt("status"))
        assertEquals(320000, song.getInt("pl"))
        assertEquals(320000, song.getInt("dl"))
        assertEquals(320000, song.getInt("maxbr"))
        assertEquals(1, song.getInt("subp"))

        val priv = song.getJSONObject("privilege")
        assertEquals(0, priv.getInt("st"))
        assertEquals(1, priv.getInt("cp"))
        assertEquals(0, priv.getInt("fee"))
        assertEquals(320000, priv.getInt("pl"))
        assertEquals(320000, priv.getInt("dl"))
        assertEquals(1, priv.getInt("subp"))
        assertEquals(320000, priv.getInt("maxbr"))
        assertEquals("exhigh", priv.getString("maxBrLevel"))
    }

    @Test
    fun testPlaylistTracksAndPrivilegesArray() {
        val json = """
            {
                "code": 200,
                "playlist": {
                    "id": 999,
                    "tracks": [
                        {
                            "id": 101,
                            "name": "Track101",
                            "fee": 4,
                            "st": -100,
                            "cp": 0,
                            "ar": [{"name": "ArtistA"}],
                            "dt": 200000
                        },
                        {
                            "id": 102,
                            "name": "Track102",
                            "fee": 1,
                            "st": -1,
                            "cp": 0,
                            "artists": [{"name": "ArtistB"}],
                            "duration": 210000
                        }
                    ],
                    "privileges": [
                        {
                            "id": 101,
                            "fee": 4,
                            "st": -100,
                            "pl": 0,
                            "dl": 0,
                            "cp": 0
                        },
                        {
                            "id": 102,
                            "fee": 1,
                            "st": -1,
                            "pl": 0,
                            "dl": 0,
                            "cp": 0
                        }
                    ]
                }
            }
        """.trimIndent()

        val result = SongRightsTransformer.transformResponseJson(json)
        assertTrue(result.hasModified)
        assertEquals(2, result.discoveredSongs.size)

        val root = JSONObject(result.modifiedJson)
        val tracks = root.getJSONObject("playlist").getJSONArray("tracks")
        val privileges = root.getJSONObject("playlist").getJSONArray("privileges")

        for (i in 0 until 2) {
            val t = tracks.getJSONObject(i)
            assertEquals(0, t.getInt("fee"))
            assertEquals(0, t.getInt("st"))
            assertEquals(1, t.getInt("cp"))

            val p = privileges.getJSONObject(i)
            assertEquals(0, p.getInt("fee"))
            assertEquals(0, p.getInt("st"))
            assertEquals(1, p.getInt("cp"))
            assertEquals(320000, p.getInt("pl"))
            assertEquals(320000, p.getInt("dl"))
        }
    }

    @Test
    fun testAlreadyUnlockedSongRemainsUnmodified() {
        val json = """
            {
                "code": 200,
                "song": {
                    "id": 555,
                    "name": "FreeTrack",
                    "fee": 0,
                    "st": 0,
                    "cp": 1,
                    "pl": 320000,
                    "dl": 320000,
                    "maxbr": 320000,
                    "subp": 1,
                    "sp": 7,
                    "ar": [{"name": "FreeArtist"}],
                    "dt": 150000,
                    "privilege": {
                        "id": 555,
                        "st": 0,
                        "cp": 1,
                        "fee": 0,
                        "pl": 320000,
                        "dl": 320000,
                        "subp": 1,
                        "maxbr": 320000
                    }
                }
            }
        """.trimIndent()

        val result = SongRightsTransformer.transformResponseJson(json)
        assertFalse(result.hasModified)
        assertEquals(json, result.modifiedJson)
        assertEquals(1, result.discoveredSongs.size)
        assertEquals(555L, result.discoveredSongs[0].id)
    }

    @Test
    fun testTrialRestrictionSanitization() {
        val json = """
            {
                "code": 200,
                "data": {
                    "id": 888,
                    "name": "TrialTrack",
                    "ar": [{"name": "TrialArtist"}],
                    "dt": 120000,
                    "freeTrialInfo": {"remainTime": 30},
                    "freeTrialPrivilege": {"userConsumable": false},
                    "freeTimeTrialPrivilege": {"type": 1},
                    "trialMode": 1,
                    "privilege": {
                        "id": 888,
                        "st": 0,
                        "cp": 1,
                        "pl": 320000,
                        "dl": 320000,
                        "fee": 0,
                        "subp": 1,
                        "maxbr": 320000,
                        "freeTrialInfo": {"remainTime": 30},
                        "freeTrialPrivilege": {"userConsumable": false},
                        "freeTimeTrialPrivilege": {"type": 1},
                        "trialMode": 1
                    }
                }
            }
        """.trimIndent()

        val result = SongRightsTransformer.transformResponseJson(json)
        assertTrue(result.hasModified)

        val root = JSONObject(result.modifiedJson)
        val data = root.getJSONObject("data")
        assertFalse(data.has("freeTrialInfo"))
        assertFalse(data.has("freeTrialPrivilege"))
        assertFalse(data.has("freeTimeTrialPrivilege"))
        assertEquals(0, data.getInt("trialMode"))

        val priv = data.getJSONObject("privilege")
        assertFalse(priv.has("freeTrialInfo"))
        assertFalse(priv.has("freeTrialPrivilege"))
        assertFalse(priv.has("freeTimeTrialPrivilege"))
        assertEquals(0, priv.getInt("trialMode"))
    }

    @Test
    fun testNestedStringifiedJsonUnblocking() {
        val nestedJson = """{"id":777,"name":"NestedSong","fee":4,"st":-1,"ar":[{"name":"NestedArtist"}],"dt":190000}"""
        val rootJson = JSONObject().apply {
            put("code", 200)
            put("serializedTrack", nestedJson)
        }.toString()

        val discoveredCallback = mutableListOf<SongInfo>()
        val result = SongRightsTransformer.transformResponseJson(rootJson) { song ->
            discoveredCallback.add(song)
        }

        assertTrue(result.hasModified)
        assertEquals(1, result.discoveredSongs.size)
        assertEquals(777L, result.discoveredSongs[0].id)
        assertEquals(1, discoveredCallback.size)
        assertEquals(777L, discoveredCallback[0].id)

        val parsedRoot = JSONObject(result.modifiedJson)
        val deserializedNested = JSONObject(parsedRoot.getString("serializedTrack"))
        assertEquals(0, deserializedNested.getInt("fee"))
        assertEquals(0, deserializedNested.getInt("st"))
    }

    @Test
    fun testSongInfoExtractionAndCallbackStream() {
        val json = """
            {
                "code": 200,
                "tracks": [
                    {
                        "id": 1,
                        "name": "TrackOne",
                        "ar": [{"name": "Artist1"}],
                        "dt": 100000,
                        "fee": 1
                    },
                    {
                        "id": 2,
                        "name": "TrackTwo",
                        "artists": [{"name": "Artist2"}],
                        "duration": 200000,
                        "fee": 0
                    },
                    {
                        "id": 1,
                        "name": "DuplicateTrackOne",
                        "ar": [{"name": "Artist1"}],
                        "dt": 100000,
                        "fee": 0
                    }
                ]
            }
        """.trimIndent()

        val callbackSongs = mutableListOf<SongInfo>()
        val result = SongRightsTransformer.transformResponseJson(json) { song ->
            callbackSongs.add(song)
        }

        // Deduplication ensures song 1 is emitted only once
        assertEquals(2, result.discoveredSongs.size)
        assertEquals(2, callbackSongs.size)
        assertEquals(1L, callbackSongs[0].id)
        assertEquals(2L, callbackSongs[1].id)
    }

    @Test
    fun testMalformedAndEmptyJsonGracefulHandling() {
        val inputs = listOf("", "   ", "{not a valid json", "[1, 2, ", "plain string")
        for (input in inputs) {
            val result = SongRightsTransformer.transformResponseJson(input)
            assertFalse(result.hasModified)
            assertTrue(result.discoveredSongs.isEmpty())
        }
    }

    @Test
    fun testDeeplyNestedRecursionLimitSafety() {
        // Build 80 levels of nested JSON objects
        var current = JSONObject()
        current.put("id", 123)
        current.put("fee", 1)
        for (i in 0 until 80) {
            val parent = JSONObject()
            parent.put("nested_$i", current)
            current = parent
        }

        // Must terminate safely without StackOverflowError
        val result = SongRightsTransformer.transformResponseJson(current.toString())
        assertNotNull(result)
    }
}
