package pochita.domain.transformer

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import pochita.model.SongInfo

/**
 * Adversarial stress and fuzzing test harness for [SongRightsTransformer].
 *
 * Covers:
 * 1. Deeply nested trees (50+ levels, boundary depth 64 vs 65).
 * 2. Deeply nested stringified JSON (multi-level serialized string unpacking & repacking).
 * 3. Malformed JSON, non-JSON strings, primitive roots, and invalid syntax.
 * 4. Massive JSON arrays (thousands of songs, deduplication stress, timing bounds).
 * 5. Trial mode clearing permutations (freeTrialInfo as object, array, string, boolean true/false, int).
 * 6. Partial privilege & track objects (missing fields, null values, malformed types).
 * 7. Bitrate preservation (ensuring Hi-Res / 999999 is never downgraded).
 */
class SongRightsTransformerAdversarialTest {

    // =========================================================================
    // Category 1: Deep Nested JSON Trees & Boundary Depth
    // =========================================================================

    @Test
    fun testDeepNestingFiftyLevelsUnblocksCorrectly() {
        // Construct 50 nested JSON objects: root -> level_1 -> ... -> level_50
        var innermost = JSONObject().apply {
            put("id", 88801L)
            put("name", "DeepSong50")
            put("fee", 4)
            put("st", -200)
            put("cp", 0)
            put("pl", 0)
            put("dl", 0)
            put("ar", JSONArray("""[{"id": 1, "name": "DeepArtist"}]"""))
            put("dt", 210000L)
            put("privilege", JSONObject().apply {
                put("id", 88801L)
                put("st", -200)
                put("cp", 0)
                put("pl", 0)
                put("dl", 0)
                put("fee", 4)
            })
        }

        for (level in 49 downTo 1) {
            val parent = JSONObject()
            parent.put("level_$level", innermost)
            innermost = parent
        }

        val root = JSONObject().apply {
            put("code", 200)
            put("level_0", innermost)
        }

        val discoveredCallback = mutableListOf<SongInfo>()
        val result = SongRightsTransformer.transformResponseJson(root.toString()) { song ->
            discoveredCallback.add(song)
        }

        assertTrue("Should be modified at depth ~50", result.hasModified)
        assertEquals(1, result.discoveredSongs.size)
        assertEquals(88801L, result.discoveredSongs[0].id)
        assertEquals("DeepSong50", result.discoveredSongs[0].name)
        assertEquals(1, discoveredCallback.size)

        // Drill down to verify fields were unblocked
        var curr = JSONObject(result.modifiedJson)
        curr = curr.getJSONObject("level_0")
        for (level in 1..49) {
            curr = curr.getJSONObject("level_$level")
        }
        assertEquals(0, curr.getInt("fee"))
        assertEquals(0, curr.getInt("st"))
        assertEquals(1, curr.getInt("cp"))
        assertEquals(320000, curr.getInt("pl"))
        assertEquals(320000, curr.getInt("dl"))

        val priv = curr.getJSONObject("privilege")
        assertEquals(0, priv.getInt("st"))
        assertEquals(1, priv.getInt("cp"))
        assertEquals(0, priv.getInt("fee"))
        assertEquals(320000, priv.getInt("pl"))
        assertEquals(320000, priv.getInt("dl"))
    }

    @Test
    fun testDeepNestingBoundarySixtyFourVsSixtyFive() {
        // Target placed at exactly depth 64: should be modified
        var target64 = JSONObject().apply {
            put("id", 64001L)
            put("name", "Depth64Song")
            put("fee", 1)
            put("st", -1)
            put("ar", JSONArray("""[{"name": "Artist64"}]"""))
        }
        // Nest 64 times
        for (i in 63 downTo 0) {
            val parent = JSONObject()
            parent.put("node_$i", target64)
            target64 = parent
        }

        val result64 = SongRightsTransformer.transformResponseJson(target64.toString())
        assertTrue("Depth 64 should be modified (<= MAX_RECURSION_DEPTH)", result64.hasModified)
        assertEquals(1, result64.discoveredSongs.size)

        // Target placed at depth 66 (exceeds MAX_RECURSION_DEPTH=64)
        var target66 = JSONObject().apply {
            put("id", 66001L)
            put("name", "Depth66Song")
            put("fee", 1)
            put("st", -1)
            put("ar", JSONArray("""[{"name": "Artist66"}]"""))
        }
        for (i in 65 downTo 0) {
            val parent = JSONObject()
            parent.put("node_$i", target66)
            target66 = parent
        }

        // Must terminate safely without StackOverflowError and return without modifying beyond depth limit
        val result66 = SongRightsTransformer.transformResponseJson(target66.toString())
        assertNotNull(result66)
        assertFalse("Depth 66 exceeds MAX_RECURSION_DEPTH, should not be modified", result66.hasModified)
    }

    // =========================================================================
    // Category 2: Deeply Nested Stringified JSON & Escaped Permutations
    // =========================================================================

    @Test
    fun testDeeplyNestedStringifiedJsonFiveLevels() {
        // Build 5 levels of JSON strings inside JSON strings:
        // Level 5 (object): {"id": 555, "name": "StringifiedSong", "fee": 1, "st": -1, "ar": [{"name": "A"}]}
        // Level 4: {"payload4": "<level5_as_string>"}
        // Level 3: {"payload3": "<level4_as_string>"}
        // Level 2: {"payload2": "<level3_as_string>"}
        // Level 1: {"payload1": "<level2_as_string>"}
        val level5 = JSONObject().apply {
            put("id", 555L)
            put("name", "StringifiedSong")
            put("fee", 4)
            put("st", -100)
            put("ar", JSONArray("""[{"name": "StringArtist"}]"""))
            put("dt", 185000L)
        }

        var currentStr = level5.toString()
        for (level in 4 downTo 1) {
            val obj = JSONObject()
            obj.put("payload_$level", currentStr)
            currentStr = obj.toString()
        }

        val result = SongRightsTransformer.transformResponseJson(currentStr)
        assertTrue("Stringified hierarchy should be modified", result.hasModified)
        assertEquals(1, result.discoveredSongs.size)
        assertEquals(555L, result.discoveredSongs[0].id)
        assertEquals("StringifiedSong", result.discoveredSongs[0].name)

        // Deserialize back through all 5 levels to verify the innermost object is unblocked
        var currentParsed = JSONObject(result.modifiedJson)
        for (level in 1..4) {
            val innerStr = currentParsed.getString("payload_$level")
            currentParsed = JSONObject(innerStr)
        }
        assertEquals(0, currentParsed.getInt("fee"))
        assertEquals(0, currentParsed.getInt("st"))
    }

    @Test
    fun testMixedObjectArrayStringifiedHierarchy() {
        // Structure:
        // root (JSONObject) -> "items" (JSONArray) -> [
        //    "stringified_object" -> {"songs": "[{\"id\": 999, \"name\": \"DeepArraySong\", \"fee\": 1, \"ar\": [{\"name\": \"DA\"}]}]"}
        // ]
        val innerSongArray = JSONArray().apply {
            put(JSONObject().apply {
                put("id", 999L)
                put("name", "DeepArraySong")
                put("fee", 1)
                put("st", -200)
                put("ar", JSONArray("""[{"name": "DeepArrayArtist"}]"""))
            })
        }

        val middleObj = JSONObject().apply {
            put("songs", innerSongArray.toString())
        }

        val root = JSONObject().apply {
            put("items", JSONArray().apply {
                put(middleObj.toString())
            })
        }

        val result = SongRightsTransformer.transformResponseJson(root.toString())
        assertTrue("Mixed hierarchy should be modified", result.hasModified)
        assertEquals(1, result.discoveredSongs.size)
        assertEquals(999L, result.discoveredSongs[0].id)

        // Verify deserialized content inside modifiedJson
        val parsedRoot = JSONObject(result.modifiedJson)
        val middleStr = parsedRoot.getJSONArray("items").getString(0)
        val parsedMiddle = JSONObject(middleStr)
        val innerArrayStr = parsedMiddle.getString("songs")
        val parsedInnerArray = JSONArray(innerArrayStr)
        val target = parsedInnerArray.getJSONObject(0)

        assertEquals(0, target.getInt("fee"))
        assertEquals(0, target.getInt("st"))
    }

    @Test
    fun testStringsStartingWithBracesOrBracketsThatAreNotJson() {
        val json = JSONObject().apply {
            put("code", 200)
            put("lyric", "[00:12.34] This is a lyric line with {curlys}")
            put("template", "{user_name} has won a prize!")
            put("broken_json_1", "{not valid json at all")
            put("broken_json_2", "[unclosed array")
            put("broken_json_3", "{ \"key\": ")
            put("empty_brace", "{}")
            put("empty_bracket", "[]")
            put("whitespace_brace", "   {   ")
            put("target_song", JSONObject().apply {
                put("id", 111L)
                put("name", "RealSong")
                put("fee", 1)
                put("ar", JSONArray("""[{"name": "RealArtist"}]"""))
            })
        }.toString()

        val result = SongRightsTransformer.transformResponseJson(json)
        assertTrue(result.hasModified)
        assertEquals(1, result.discoveredSongs.size)
        assertEquals(111L, result.discoveredSongs[0].id)

        // Verify non-JSON strings are preserved intact
        val parsed = JSONObject(result.modifiedJson)
        assertEquals("[00:12.34] This is a lyric line with {curlys}", parsed.getString("lyric"))
        assertEquals("{user_name} has won a prize!", parsed.getString("template"))
        assertEquals("{not valid json at all", parsed.getString("broken_json_1"))
        assertEquals("[unclosed array", parsed.getString("broken_json_2"))
    }

    // =========================================================================
    // Category 3: Malformed JSON, Non-JSON Strings, Primitive Roots
    // =========================================================================

    @Test
    fun testMalformedAndPathologicalInputs() {
        val pathologicalInputs = listOf(
            "",
            "   \n\t  ",
            "null",
            "true",
            "false",
            "12345",
            "3.14159",
            "\"just a plain quoted string\"",
            "<html><body>502 Bad Gateway</body></html>",
            "{\"code\": 200, \"incomplete\": ",
            "{\"trailing_comma\": [1, 2, ],}",
            "\u0000\u0001\u0002\u0003garbage",
            "undefined",
            "NaN",
            "{:}",
            "[,]"
        )

        for (input in pathologicalInputs) {
            val result = SongRightsTransformer.transformResponseJson(input)
            assertNotNull("Must never return null for input: '$input'", result)
            assertFalse("Pathological input must not be marked modified: '$input'", result.hasModified)
            assertTrue("Discovered songs must be empty: '$input'", result.discoveredSongs.isEmpty())
            assertEquals("Unmodified json must match input: '$input'", input, result.modifiedJson)
        }
    }

    // =========================================================================
    // Category 4: Massive Arrays & Performance Stress
    // =========================================================================

    @Test
    fun testMassiveArrayFiveThousandSongsStress() {
        val songCount = 5000
        val tracksArray = JSONArray()
        val privilegesArray = JSONArray()

        for (i in 1..songCount) {
            val songId = i.toLong()
            tracksArray.put(JSONObject().apply {
                put("id", songId)
                put("name", "Song_$i")
                put("fee", 4)
                put("st", -200)
                put("cp", 0)
                put("pl", 0)
                put("dl", 0)
                put("ar", JSONArray().put(JSONObject().put("name", "Artist_${i % 100}")))
                put("dt", 180000L + i)
            })
            privilegesArray.put(JSONObject().apply {
                put("id", songId)
                put("fee", 4)
                put("st", -200)
                put("cp", 0)
                put("pl", 0)
                put("dl", 0)
            })
        }

        val root = JSONObject().apply {
            put("code", 200)
            put("playlist", JSONObject().apply {
                put("id", 1001L)
                put("tracks", tracksArray)
                put("privileges", privilegesArray)
            })
        }

        val jsonPayload = root.toString()

        val startTime = System.currentTimeMillis()
        val result = SongRightsTransformer.transformResponseJson(jsonPayload)
        val elapsed = System.currentTimeMillis() - startTime

        assertTrue("Should have modified 5000 songs", result.hasModified)
        assertEquals("Must discover all 5000 songs", songCount, result.discoveredSongs.size)
        assertTrue("Processing 5000 songs must take under 3500ms (took ${elapsed}ms)", elapsed < 3500)

        // Verify sample elements in result
        val parsed = JSONObject(result.modifiedJson)
        val parsedTracks = parsed.getJSONObject("playlist").getJSONArray("tracks")
        val parsedPrivs = parsed.getJSONObject("playlist").getJSONArray("privileges")

        for (checkIdx in listOf(0, 2499, 4999)) {
            val t = parsedTracks.getJSONObject(checkIdx)
            assertEquals(0, t.getInt("fee"))
            assertEquals(0, t.getInt("st"))
            assertEquals(1, t.getInt("cp"))
            assertEquals(320000, t.getInt("pl"))

            val p = parsedPrivs.getJSONObject(checkIdx)
            assertEquals(0, p.getInt("fee"))
            assertEquals(0, p.getInt("st"))
            assertEquals(1, p.getInt("cp"))
            assertEquals(320000, p.getInt("pl"))
        }
    }

    @Test
    fun testMassiveArrayDeduplicationStress() {
        val totalItems = 10000
        val uniqueSongs = 100
        val tracksArray = JSONArray()

        for (i in 0 until totalItems) {
            val songId = (i % uniqueSongs + 1).toLong()
            tracksArray.put(JSONObject().apply {
                put("id", songId)
                put("name", "UniqueSong_$songId")
                put("fee", 1)
                put("ar", JSONArray().put(JSONObject().put("name", "Artist")))
            })
        }

        val root = JSONObject().apply {
            put("code", 200)
            put("tracks", tracksArray)
        }.toString()

        var callbackCount = 0
        val result = SongRightsTransformer.transformResponseJson(root) {
            callbackCount++
        }

        assertTrue(result.hasModified)
        assertEquals("Discovered songs must be deduplicated to exactly 100", uniqueSongs, result.discoveredSongs.size)
        assertEquals("Callback must be invoked exactly 100 times", uniqueSongs, callbackCount)
    }

    // =========================================================================
    // Category 5: Trial Mode Clearing Permutations
    // =========================================================================

    @Test
    fun testTrialModeClearingPermutations() {
        // Test freeTrialInfo as:
        // 1. JSONObject: {"remainTime": 30}
        // 2. JSONArray: [1, 2, 3]
        // 3. String: "trial_active"
        // 4. Boolean true: true
        // 5. Boolean false: false
        // 6. Integer: 123
        val testCases = listOf(
            "object" to JSONObject("""{"remainTime": 30}"""),
            "array" to JSONArray("""[1, 2, 3]"""),
            "string" to "trial_active",
            "boolean_true" to true,
            "boolean_false" to false,
            "integer" to 123
        )

        for ((type, value) in testCases) {
            val root = JSONObject().apply {
                put("code", 200)
                put("song", JSONObject().apply {
                    put("id", 7001L)
                    put("name", "TrialTest_$type")
                    put("ar", JSONArray("""[{"name": "TrialArtist"}]"""))
                    put("freeTrialInfo", value)
                    put("freeTrialPrivilege", JSONObject("""{"userConsumable": false}"""))
                    put("freeTimeTrialPrivilege", JSONArray("""[1]"""))
                    put("trialMode", 1)
                })
            }

            val result = SongRightsTransformer.transformResponseJson(root.toString())
            assertTrue("freeTrialInfo as $type must trigger modification", result.hasModified)

            val parsed = JSONObject(result.modifiedJson).getJSONObject("song")
            assertFalse("freeTrialInfo as $type must be removed", parsed.has("freeTrialInfo"))
            assertFalse("freeTrialPrivilege must be removed", parsed.has("freeTrialPrivilege"))
            assertFalse("freeTimeTrialPrivilege must be removed", parsed.has("freeTimeTrialPrivilege"))
            assertEquals("trialMode must be set to 0", 0, parsed.getInt("trialMode"))
        }
    }

    @Test
    fun testTrialModeValueVariations() {
        // Test trialMode = 2, -1, "1"
        for (tm in listOf(2, -1)) {
            val root = JSONObject().apply {
                put("code", 200)
                put("data", JSONObject().apply {
                    put("id", 8001L)
                    put("name", "TrialModeVar")
                    put("ar", JSONArray("""[{"name": "Artist"}]"""))
                    put("trialMode", tm)
                })
            }

            val result = SongRightsTransformer.transformResponseJson(root.toString())
            assertTrue("trialMode=$tm must trigger modification", result.hasModified)
            val parsed = JSONObject(result.modifiedJson).getJSONObject("data")
            assertEquals("trialMode must be normalized to 0", 0, parsed.getInt("trialMode"))
        }
    }

    @Test
    fun testFreeTrialInfoNullPreservesUnmodifiedWhenAlreadyUnlocked() {
        // When freeTrialInfo is JSON null and song is already fully unlocked, should remain unmodified
        val root = JSONObject().apply {
            put("code", 200)
            put("song", JSONObject().apply {
                put("id", 9001L)
                put("name", "AlreadyUnlocked")
                put("fee", 0)
                put("st", 0)
                put("cp", 1)
                put("pl", 320000)
                put("dl", 320000)
                put("maxbr", 320000)
                put("subp", 1)
                put("freeTrialInfo", JSONObject.NULL)
                put("trialMode", 0)
                put("ar", JSONArray("""[{"name": "Artist"}]"""))
                put("privilege", JSONObject().apply {
                    put("id", 9001L)
                    put("st", 0)
                    put("cp", 1)
                    put("pl", 320000)
                    put("dl", 320000)
                    put("fee", 0)
                    put("subp", 1)
                    put("maxbr", 320000)
                    put("freeTrialInfo", JSONObject.NULL)
                    put("trialMode", 0)
                })
            })
        }

        val result = SongRightsTransformer.transformResponseJson(root.toString())
        assertFalse("Already unlocked song with null trial info must not be modified", result.hasModified)
        assertEquals(1, result.discoveredSongs.size)
    }

    // =========================================================================
    // Category 6: Partial Privilege & Track Objects Missing Fields
    // =========================================================================

    @Test
    fun testPartialPrivilegeObjects() {
        // 1. Privilege with only id and pl=0 (missing fee, st, cp)
        val json1 = JSONObject().apply {
            put("privilege", JSONObject().apply {
                put("id", 101L)
                put("pl", 0)
            })
        }.toString()
        val res1 = SongRightsTransformer.transformResponseJson(json1)
        assertTrue("Partial priv with pl=0 must be unblocked", res1.hasModified)
        val p1 = JSONObject(res1.modifiedJson).getJSONObject("privilege")
        assertEquals(320000, p1.getInt("pl"))
        assertEquals(0, p1.getInt("st"))
        assertEquals(1, p1.getInt("cp"))

        // 2. Privilege with only id and st=-100 (missing fee, pl, cp)
        val json2 = JSONObject().apply {
            put("privilege", JSONObject().apply {
                put("id", 102L)
                put("st", -100)
            })
        }.toString()
        val res2 = SongRightsTransformer.transformResponseJson(json2)
        assertTrue("Partial priv with st=-100 must be unblocked", res2.hasModified)
        val p2 = JSONObject(res2.modifiedJson).getJSONObject("privilege")
        assertEquals(0, p2.getInt("st"))
        assertEquals(320000, p2.getInt("pl"))

        // 3. Privilege with only id and cp=0 (missing fee, st, pl)
        val json3 = JSONObject().apply {
            put("privilege", JSONObject().apply {
                put("id", 103L)
                put("cp", 0)
            })
        }.toString()
        val res3 = SongRightsTransformer.transformResponseJson(json3)
        assertTrue("Partial priv with cp=0 must be unblocked", res3.hasModified)
        val p3 = JSONObject(res3.modifiedJson).getJSONObject("privilege")
        assertEquals(1, p3.getInt("cp"))
    }

    @Test
    fun testTrackMetadataFallbackVariations() {
        // Case A: artist extracted from "ar" array of string names
        val jsonA = JSONObject().apply {
            put("track", JSONObject().apply {
                put("id", 201L)
                put("name", "SongA")
                put("ar", JSONArray("""["DirectStringArtist"]"""))
                put("fee", 1)
            })
        }.toString()
        val resA = SongRightsTransformer.transformResponseJson(jsonA)
        assertEquals(1, resA.discoveredSongs.size)
        assertEquals("DirectStringArtist", resA.discoveredSongs[0].artist)

        // Case B: artist extracted from "artists" array of objects
        val jsonB = JSONObject().apply {
            put("track", JSONObject().apply {
                put("id", 202L)
                put("name", "SongB")
                put("artists", JSONArray("""[{"name": "ArtistFromArtistsArray"}]"""))
                put("fee", 1)
            })
        }.toString()
        val resB = SongRightsTransformer.transformResponseJson(jsonB)
        assertEquals(1, resB.discoveredSongs.size)
        assertEquals("ArtistFromArtistsArray", resB.discoveredSongs[0].artist)

        // Case C: artist extracted from direct "artist" field when ar is empty
        val jsonC = JSONObject().apply {
            put("track", JSONObject().apply {
                put("id", 203L)
                put("name", "SongC")
                put("ar", JSONArray())
                put("artist", "DirectArtistField")
                put("fee", 1)
            })
        }.toString()
        val resC = SongRightsTransformer.transformResponseJson(jsonC)
        assertEquals(1, resC.discoveredSongs.size)
        assertEquals("DirectArtistField", resC.discoveredSongs[0].artist)

        // Case D: duration from "duration" field when "dt" is missing
        val jsonD = JSONObject().apply {
            put("track", JSONObject().apply {
                put("id", 204L)
                put("name", "SongD")
                put("ar", JSONArray("""[{"name": "ArtistD"}]"""))
                put("duration", 250000L)
                put("fee", 1)
            })
        }.toString()
        val resD = SongRightsTransformer.transformResponseJson(jsonD)
        assertEquals(1, resD.discoveredSongs.size)
        assertEquals(250000L, resD.discoveredSongs[0].durationMs)
    }

    @Test
    fun testPathologicalTrackFields() {
        // 1. Zero or negative song ID: should not crash, must not emit invalid SongInfo
        val jsonZero = JSONObject().apply {
            put("track", JSONObject().apply {
                put("id", 0)
                put("name", "ZeroIdSong")
                put("ar", JSONArray("""[{"name": "Artist"}]"""))
                put("fee", 1)
            })
        }.toString()
        val resZero = SongRightsTransformer.transformResponseJson(jsonZero)
        assertTrue(resZero.discoveredSongs.isEmpty())

        // 2. Empty song name: must not emit SongInfo
        val jsonEmptyName = JSONObject().apply {
            put("track", JSONObject().apply {
                put("id", 301L)
                put("name", "")
                put("ar", JSONArray("""[{"name": "Artist"}]"""))
                put("fee", 1)
            })
        }.toString()
        val resEmptyName = SongRightsTransformer.transformResponseJson(jsonEmptyName)
        assertTrue(resEmptyName.discoveredSongs.isEmpty())

        // 3. String numeric ID: optLong parses it gracefully
        val jsonStringId = JSONObject().apply {
            put("track", JSONObject().apply {
                put("id", "302")
                put("name", "StringIdSong")
                put("ar", JSONArray("""[{"name": "Artist"}]"""))
                put("fee", 1)
            })
        }.toString()
        val resStringId = SongRightsTransformer.transformResponseJson(jsonStringId)
        assertEquals(1, resStringId.discoveredSongs.size)
        assertEquals(302L, resStringId.discoveredSongs[0].id)

        // 4. Large 64-bit Long ID
        val largeId = 777666555444333L
        val jsonLargeId = JSONObject().apply {
            put("track", JSONObject().apply {
                put("id", largeId)
                put("name", "LargeIdSong")
                put("ar", JSONArray("""[{"name": "Artist"}]"""))
                put("fee", 1)
            })
        }.toString()
        val resLargeId = SongRightsTransformer.transformResponseJson(jsonLargeId)
        assertEquals(1, resLargeId.discoveredSongs.size)
        assertEquals(largeId, resLargeId.discoveredSongs[0].id)
    }

    // =========================================================================
    // Category 7: High Bitrate & Unlocked Preservation Invariants
    // =========================================================================

    @Test
    fun testHiResAudioBitrateNotDowngraded() {
        // Song already has maxbr=999000 (Hi-Res/FLAC) and pl=999000, fee=0, st=0
        val json = JSONObject().apply {
            put("code", 200)
            put("song", JSONObject().apply {
                put("id", 401L)
                put("name", "HiResSong")
                put("fee", 0)
                put("st", 0)
                put("cp", 1)
                put("pl", 999000)
                put("dl", 999000)
                put("maxbr", 999000)
                put("subp", 1)
                put("ar", JSONArray("""[{"name": "HiResArtist"}]"""))
                put("privilege", JSONObject().apply {
                    put("id", 401L)
                    put("st", 0)
                    put("cp", 1)
                    put("fee", 0)
                    put("pl", 999000)
                    put("dl", 999000)
                    put("subp", 1)
                    put("maxbr", 999000)
                })
            })
        }.toString()

        val result = SongRightsTransformer.transformResponseJson(json)
        assertFalse("Hi-Res track that is unlocked must not be modified", result.hasModified)
        assertEquals(json, result.modifiedJson)
    }

    // =========================================================================
    // Category 8: Concurrency, Callback Isolation & Extreme Deep Arrays
    // =========================================================================

    @Test
    fun testCallbackExceptionIsolation() {
        val json = JSONObject().apply {
            put("song", JSONObject().apply {
                put("id", 1001L)
                put("name", "ThrowingSong")
                put("ar", JSONArray("""[{"name": "Artist"}]"""))
                put("fee", 1)
            })
        }.toString()

        // If the discovery callback throws, transformer must isolate the exception and not crash host
        val result = SongRightsTransformer.transformResponseJson(json) { _ ->
            throw RuntimeException("Simulated host callback failure")
        }

        assertNotNull(result)
        assertFalse(result.hasModified)
        assertTrue(result.discoveredSongs.isEmpty())
        assertEquals(json, result.modifiedJson)
    }

    @Test
    fun testConcurrentMultiThreadedTransforms() {
        val threadCount = 30
        val iterationsPerThread = 20
        val threads = mutableListOf<Thread>()
        val errors = java.util.concurrent.ConcurrentLinkedQueue<Throwable>()

        for (t in 0 until threadCount) {
            val thread = Thread {
                try {
                    for (i in 0 until iterationsPerThread) {
                        val songId = (t * 1000 + i + 1).toLong()
                        val json = JSONObject().apply {
                            put("code", 200)
                            put("data", JSONObject().apply {
                                put("id", songId)
                                put("name", "ConcurrentSong_$songId")
                                put("fee", 1)
                                put("st", -1)
                                put("ar", JSONArray("""[{"name": "Artist_$t"}]"""))
                                put("freeTrialInfo", JSONObject("""{"time": 30}"""))
                            })
                        }.toString()

                        val result = SongRightsTransformer.transformResponseJson(json)
                        assertTrue(result.hasModified)
                        assertEquals(1, result.discoveredSongs.size)
                        assertEquals(songId, result.discoveredSongs[0].id)

                        val parsed = JSONObject(result.modifiedJson).getJSONObject("data")
                        assertEquals(0, parsed.getInt("fee"))
                        assertEquals(0, parsed.getInt("st"))
                        assertFalse(parsed.has("freeTrialInfo"))
                    }
                } catch (e: Throwable) {
                    errors.add(e)
                }
            }
            threads.add(thread)
        }

        threads.forEach { it.start() }
        threads.forEach { it.join(5000) }

        assertTrue("Multi-threaded transforms must have zero concurrency errors: $errors", errors.isEmpty())
    }

    @Test
    fun testDeeplyNestedArraysFiftyLevels() {
        // Construct 50 nested arrays: [[[[ ... [targetSong] ... ]]]]
        val target = JSONObject().apply {
            put("id", 50001L)
            put("name", "DeepArraySong")
            put("fee", 4)
            put("st", -100)
            put("ar", JSONArray("""[{"name": "Artist"}]"""))
        }

        var innermost = JSONArray().put(target)
        for (i in 49 downTo 1) {
            val parent = JSONArray().put(innermost)
            innermost = parent
        }

        val result = SongRightsTransformer.transformResponseJson(innermost.toString())
        assertTrue("Deeply nested array at depth 50 should be modified", result.hasModified)
        assertEquals(1, result.discoveredSongs.size)
        assertEquals(50001L, result.discoveredSongs[0].id)
    }
}

