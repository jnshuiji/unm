package pochita.domain.transformer

import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener
import pochita.common.UnmLogger
import pochita.model.SongInfo

/**
 * Pure recursive JSON tree unblocker for NetEase Cloud Music responses (/eapi/ and /xeapi/).
 *
 * Responsibilities:
 * - Unblocks song rights and playback privileges (st=0, fee=0, cp=1, pl=320000, dl=320000, subp=1, maxbr=320000).
 * - Strips trial limitations (removes freeTrialInfo, freeTrialPrivilege, sets trialMode=0).
 * - Traverses nested JSON trees and deserializes/re-serializes embedded JSON strings.
 * - Discovers song metadata (SongInfo) during traversal and emits via callback or result list.
 * - Stateless and thread-safe: zero global state or static cache mutations.
 */
object SongRightsTransformer {
    private const val TAG = "SongRightsTransformer"
    private const val MAX_RECURSION_DEPTH = 64

    /**
     * Result of JSON transformation.
     *
     * @property modifiedJson The sanitized/unblocked JSON string (or original string if unmodified).
     * @property hasModified True if any field was mutated or trial info removed; false otherwise.
     * @property discoveredSongs Deduplicated list of song metadata discovered during traversal.
     */
    data class TransformResult(
        val modifiedJson: String,
        val hasModified: Boolean,
        val discoveredSongs: List<SongInfo>
    )

    /**
     * Recursively traverses and unblocks NetEase API JSON responses.
     *
     * @param jsonStr Raw JSON string from decrypted/decompressed response.
     * @param onSongDiscovered Optional callback invoked whenever a new unique song is discovered.
     * @return [TransformResult] detailing modifications and discovered songs.
     */
    fun transformResponseJson(
        jsonStr: String,
        onSongDiscovered: ((SongInfo) -> Unit)? = null
    ): TransformResult {
        if (jsonStr.isBlank()) {
            return TransformResult(jsonStr, hasModified = false, discoveredSongs = emptyList())
        }

        val discoveredSongs = mutableListOf<SongInfo>()
        val seenSongIds = HashSet<Long>()

        return try {
            val tokener = JSONTokener(jsonStr)
            val root = tokener.nextValue()
            val modified = when (root) {
                is JSONObject -> processObject(root, 0, discoveredSongs, seenSongIds, onSongDiscovered)
                is JSONArray -> processArray(root, 0, discoveredSongs, seenSongIds, onSongDiscovered)
                else -> false
            }

            TransformResult(
                modifiedJson = if (modified) root.toString() else jsonStr,
                hasModified = modified,
                discoveredSongs = discoveredSongs
            )
        } catch (t: Throwable) {
            UnmLogger.w(TAG, "Failed to parse/transform JSON response: ${t.message}", t)
            TransformResult(jsonStr, hasModified = false, discoveredSongs = emptyList())
        }
    }

    private fun processObject(
        obj: JSONObject,
        depth: Int,
        discoveredSongs: MutableList<SongInfo>,
        seenSongIds: HashSet<Long>,
        onSongDiscovered: ((SongInfo) -> Unit)?
    ): Boolean {
        if (depth > MAX_RECURSION_DEPTH) return false
        var modified = false

        // 1. Privilege Object detection and unblocking
        if (obj.has("id") && (obj.has("pl") || obj.has("st") || obj.has("cp"))) {
            if (unblockPrivilegeObject(obj)) {
                modified = true
            }
        }

        // 2. Track Object detection, metadata extraction & rights unblocking
        if (obj.has("id") && obj.has("name") && (obj.has("ar") || obj.has("artists"))) {
            val songId = obj.optLong("id", 0L)
            val songName = obj.optString("name", "")
            if (songId > 0 && songName.isNotEmpty()) {
                val arArray = obj.optJSONArray("ar") ?: obj.optJSONArray("artists")
                val artistName = if (arArray != null && arArray.length() > 0) {
                    val first = arArray.optJSONObject(0)
                    first?.optString("name", "")?.takeIf { it.isNotEmpty() }
                        ?: arArray.optString(0, "")
                } else {
                    obj.optString("artist", "")
                }
                val duration = obj.optLong("dt", obj.optLong("duration", 0L))

                if (seenSongIds.add(songId)) {
                    val songInfo = SongInfo(id = songId, name = songName, artist = artistName, durationMs = duration)
                    discoveredSongs.add(songInfo)
                    onSongDiscovered?.invoke(songInfo)
                }
            }

            if (obj.has("fee") && obj.optInt("fee", 0) > 0) {
                obj.put("fee", 0)
                modified = true
            }
            if (obj.has("st") && obj.optInt("st", 0) < 0) {
                obj.put("st", 0)
                modified = true
            }
            if (obj.has("cp") && obj.optInt("cp", 1) == 0) {
                obj.put("cp", 1)
                modified = true
            }
            if (obj.has("status") && obj.optInt("status", 0) < 0) {
                obj.put("status", 0)
                modified = true
            }
            if (obj.has("pl") && obj.optInt("pl", 0) <= 0) {
                obj.put("pl", 320000)
                modified = true
            }
            if (obj.has("dl") && obj.optInt("dl", 0) <= 0) {
                obj.put("dl", 320000)
                modified = true
            }
            if (obj.has("maxbr") && obj.optInt("maxbr", 0) < 320000) {
                obj.put("maxbr", 320000)
                modified = true
            }
            if (obj.has("subp") && obj.optInt("subp", 1) <= 0) {
                obj.put("subp", 1)
                modified = true
            }
        }

        // 3. Clear trial restrictions anywhere in JSONObject
        if (obj.has("freeTrialInfo") && !obj.isNull("freeTrialInfo")) {
            obj.remove("freeTrialInfo")
            modified = true
        }
        if (obj.has("freeTrialPrivilege")) {
            obj.remove("freeTrialPrivilege")
            modified = true
        }
        if (obj.has("freeTimeTrialPrivilege")) {
            obj.remove("freeTimeTrialPrivilege")
            modified = true
        }
        if (obj.has("trialMode") && obj.optInt("trialMode", 0) != 0) {
            obj.put("trialMode", 0)
            modified = true
        }

        // 4. Recursive traversal over all child properties
        val keys = obj.keys()
        while (keys.hasNext()) {
            val key = keys.next()
            when (val child = obj.opt(key)) {
                is JSONObject -> {
                    if (processObject(child, depth + 1, discoveredSongs, seenSongIds, onSongDiscovered)) {
                        modified = true
                    }
                }
                is JSONArray -> {
                    if (processArray(child, depth + 1, discoveredSongs, seenSongIds, onSongDiscovered)) {
                        modified = true
                    }
                }
                is String -> {
                    val trimmed = child.trim()
                    if (trimmed.startsWith("{") || trimmed.startsWith("[")) {
                        try {
                            if (trimmed.startsWith("{")) {
                                val nestedObj = JSONObject(trimmed)
                                if (processObject(nestedObj, depth + 1, discoveredSongs, seenSongIds, onSongDiscovered)) {
                                    obj.put(key, nestedObj.toString())
                                    modified = true
                                }
                            } else {
                                val nestedArr = JSONArray(trimmed)
                                if (processArray(nestedArr, depth + 1, discoveredSongs, seenSongIds, onSongDiscovered)) {
                                    obj.put(key, nestedArr.toString())
                                    modified = true
                                }
                            }
                        } catch (_: Throwable) {
                            // Non-JSON string, ignore
                        }
                    }
                }
            }
        }

        return modified
    }

    private fun processArray(
        arr: JSONArray,
        depth: Int,
        discoveredSongs: MutableList<SongInfo>,
        seenSongIds: HashSet<Long>,
        onSongDiscovered: ((SongInfo) -> Unit)?
    ): Boolean {
        if (depth > MAX_RECURSION_DEPTH) return false
        var modified = false

        for (i in 0 until arr.length()) {
            when (val elem = arr.opt(i)) {
                is JSONObject -> {
                    if (processObject(elem, depth + 1, discoveredSongs, seenSongIds, onSongDiscovered)) {
                        modified = true
                    }
                }
                is JSONArray -> {
                    if (processArray(elem, depth + 1, discoveredSongs, seenSongIds, onSongDiscovered)) {
                        modified = true
                    }
                }
                is String -> {
                    val trimmed = elem.trim()
                    if (trimmed.startsWith("{") || trimmed.startsWith("[")) {
                        try {
                            if (trimmed.startsWith("{")) {
                                val nestedObj = JSONObject(trimmed)
                                if (processObject(nestedObj, depth + 1, discoveredSongs, seenSongIds, onSongDiscovered)) {
                                    arr.put(i, nestedObj.toString())
                                    modified = true
                                }
                            } else {
                                val nestedArr = JSONArray(trimmed)
                                if (processArray(nestedArr, depth + 1, discoveredSongs, seenSongIds, onSongDiscovered)) {
                                    arr.put(i, nestedArr.toString())
                                    modified = true
                                }
                            }
                        } catch (_: Throwable) {
                            // Non-JSON string, ignore
                        }
                    }
                }
            }
        }

        return modified
    }

    private fun unblockPrivilegeObject(obj: JSONObject): Boolean {
        val st = obj.optInt("st", 0)
        val cp = obj.optInt("cp", 1)
        val pl = obj.optInt("pl", 320000)
        val fee = obj.optInt("fee", 0)
        val dl = obj.optInt("dl", 320000)
        val subp = obj.optInt("subp", 1)
        val maxbr = obj.optInt("maxbr", 320000)
        val hasTrial = (obj.has("freeTrialInfo") && !obj.isNull("freeTrialInfo")) ||
                (obj.has("trialMode") && obj.optInt("trialMode", 0) != 0) ||
                obj.has("freeTrialPrivilege") ||
                obj.has("freeTimeTrialPrivilege")

        val needsUnblock = st < 0 || cp == 0 || pl <= 0 || fee > 0 ||
                dl <= 0 || subp <= 0 || maxbr < 320000 || hasTrial

        if (needsUnblock) {
            obj.put("st", 0)
            obj.put("cp", 1)
            obj.put("fee", 0)
            obj.put("pl", 320000)
            obj.put("dl", 320000)
            obj.put("subp", 1)
            obj.put("sp", 7)
            obj.put("maxbr", 320000)
            obj.put("fl", 320000)
            obj.put("toast", false)
            obj.put("cs", false)
            obj.put("playMaxbr", 320000)
            obj.put("downloadMaxbr", 320000)
            obj.put("maxBrLevel", "exhigh")
            obj.put("playMaxBrLevel", "exhigh")
            obj.put("downloadMaxBrLevel", "exhigh")
            obj.put("plLevel", "exhigh")
            obj.put("dlLevel", "exhigh")
            obj.put("flLevel", "exhigh")
            obj.remove("freeTrialInfo")
            obj.remove("freeTrialPrivilege")
            obj.remove("freeTimeTrialPrivilege")
            if (obj.has("trialMode")) {
                obj.put("trialMode", 0)
            }
            return true
        }
        return false
    }
}
