package pochita.source

import java.net.URLEncoder
import org.json.JSONObject
import pochita.codec.EApiCrypto
import pochita.common.UnmHttpClient
import pochita.common.UnmLogger
import pochita.model.MatchedAudio
import pochita.model.SongInfo

internal data class RawCandidate<T>(
    val raw: T,
    val title: String,
    val artist: String,
    val durationMs: Long?,
    val score: Int
)

internal fun unescapeHtml(text: String): String {
    if (!text.contains('&')) return text
    return text.replace("&nbsp;", " ")
        .replace("&amp;", "&")
        .replace("&lt;", "<")
        .replace("&gt;", ">")
        .replace("&quot;", "\"")
        .replace("&#39;", "'")
        .replace("&apos;", "'")
}

interface AudioSource {
    val name: String
    fun match(song: SongInfo): MatchedAudio? = match(song, AntiRollFilter.THRESHOLD_FALLBACK)
    fun match(song: SongInfo, minScore: Int): MatchedAudio?
}

object BodianSource : AudioSource {
    override val name: String = "bodian"
    private const val TAG = "BodianSource"
    private val devId = (10000000000L + (Math.random() * 90000000000L).toLong()).toString()
    @Volatile
    private var lastAdUnlockTime = 0L

    private fun ensureAdUnlocked() {
        val now = System.currentTimeMillis()
        if (now - lastAdUnlockTime < 1500_000L) {
            return
        }
        try {
            val adUrl = "http://bd-api.kuwo.cn/api/service/advert/watch?uid=-1&token=&timestamp=1724306124436&sign=15a676d66285117ad714e8c8371691da"
            val headers = mapOf(
                "user-agent" to "Dart/2.19 (dart:io)",
                "plat" to "ar",
                "channel" to "aliopen",
                "devid" to devId,
                "ver" to "3.9.0",
                "host" to "bd-api.kuwo.cn",
                "qimei36" to "1e9970cbcdc20a031dee9f37100017e1840e",
                "content-type" to "application/json; charset=utf-8"
            )
            val data = "{\"type\":5,\"subType\":5,\"musicId\":0,\"adToken\":\"\"}"
            httpPost(adUrl, data, headers)
            lastAdUnlockTime = now
        } catch (t: Throwable) {
            UnmLogger.w(TAG, "Failed to send Bodian ad unlock: ${t.message}", t)
        }
    }

    private fun generateSign(baseUrl: String, path: String): String {
        val timestamp = System.currentTimeMillis()
        val strWithTime = "$baseUrl&timestamp=$timestamp"
        val query = strWithTime.substringAfter("?")
        val filtered = query.filter { it.isLetterOrDigit() }.toCharArray().sorted().joinToString("")
        val toEncrypt = "kuwotest$filtered$path"
        val sign = EApiCrypto.md5(toEncrypt)
        return "$strWithTime&sign=$sign"
    }

    override fun match(song: SongInfo): MatchedAudio? = match(song, AntiRollFilter.THRESHOLD_FALLBACK)

    override fun match(song: SongInfo, minScore: Int): MatchedAudio? {
        return try {
            val keyword = URLEncoder.encode("${song.name} ${song.artist}", "UTF-8")
            val searchUrl = "http://search.kuwo.cn/r.s?correct=1&vipver=1&stype=comprehensive&encoding=utf8&rformat=json&mobi=1&show_copyright_off=1&searchapi=6&all=$keyword"
            val searchRes = httpGet(searchUrl) ?: return null
            val searchJson = JSONObject(searchRes)
            val content = searchJson.optJSONArray("content") ?: return null
            if (content.length() < 2) return null

            val musicPage = content.getJSONObject(1).optJSONObject("musicpage") ?: return null
            val absList = musicPage.optJSONArray("abslist") ?: return null
            if (absList.length() == 0) return null

            val candidateCount = minOf(absList.length(), 10)
            val candidates = ArrayList<RawCandidate<JSONObject>>(candidateCount)

            for (i in 0 until candidateCount) {
                val item = absList.optJSONObject(i) ?: continue
                val rid = item.optString("MUSICRID").substringAfter("MUSIC_")
                if (rid.isEmpty() || AudioValidator.isBlacklistedAudioId(rid)) continue

                val title = unescapeHtml(item.optString("SONGNAME").ifEmpty { item.optString("name", "") })
                val artist = unescapeHtml(item.optString("ARTIST").ifEmpty { item.optString("artist", "") })
                val durationSec = item.optLong("DURATION", 0L)
                val durationMs = if (durationSec > 0) durationSec * 1000L else null

                val score = AntiRollFilter.scoreCandidate(
                    originalTitle = song.name,
                    originalArtist = song.artist,
                    originalDurationMs = song.durationMs.takeIf { it > 0 },
                    candidateTitle = title,
                    candidateArtist = artist,
                    candidateDurationMs = durationMs
                ) + (candidateCount - i)

                if (score >= minScore) {
                    candidates.add(RawCandidate(item, title, artist, durationMs, score))
                }
            }

            candidates.sortByDescending { it.score }
            if (candidates.isEmpty()) return null

            ensureAdUnlocked()

            for (cand in candidates) {
                val selectedRid = cand.raw.optString("MUSICRID").substringAfter("MUSIC_")
                val path = "/api/play/music/v2/audioUrl"
                val rawUrl = "http://bd-api.kuwo.cn$path?&br=320kmp3&musicId=$selectedRid"
                val signedUrl = generateSign(rawUrl, path)

                val headers = mapOf(
                    "user-agent" to "Dart/2.19 (dart:io)",
                    "plat" to "ar",
                    "channel" to "aliopen",
                    "devid" to devId,
                    "ver" to "3.9.0",
                    "host" to "bd-api.kuwo.cn"
                )

                var trackRes = httpGet(signedUrl, headers = headers)
                if (trackRes != null && trackRes.contains("20018")) {
                    lastAdUnlockTime = 0L
                    ensureAdUnlocked()
                    val retrySignedUrl = generateSign(rawUrl, path)
                    trackRes = httpGet(retrySignedUrl, headers = headers)
                }

                if (trackRes == null) continue
                val trackJson = JSONObject(trackRes)
                if (trackJson.optInt("code") != 200) continue

                val data = trackJson.optJSONObject("data") ?: continue
                val audioUrl = data.optString("audioHttpsUrl").ifEmpty { data.optString("audioUrl") }
                if (!AudioValidator.isValidAudioUrl(audioUrl)) {
                    UnmLogger.w(TAG, "Bodian returned invalid or teaser audio URL: $audioUrl")
                    continue
                }

                val durationSec = data.optInt("duration", 0)
                if (AudioValidator.isTeaserDuration(song.durationMs, durationSec)) {
                    UnmLogger.w(TAG, "Bodian audio rejected as teaser clip: durationSec=$durationSec, songDurationMs=${song.durationMs}")
                    continue
                }

                val resolvedDurationMs = cand.durationMs ?: (if (durationSec > 0) durationSec * 1000L else null)

                return MatchedAudio(
                    url = audioUrl,
                    br = 320000,
                    source = name,
                    matchedTitle = cand.title.ifBlank { song.name },
                    matchedArtist = cand.artist.ifBlank { song.artist },
                    durationMs = resolvedDurationMs
                )
            }
            null
        } catch (t: Throwable) {
            UnmLogger.w(TAG, "Bodian match failed: ${t.message}", t)
            null
        }
    }
}

object KuwoSource : AudioSource {
    override val name: String = "kuwo"
    private const val TAG = "KuwoSource"

    override fun match(song: SongInfo): MatchedAudio? = match(song, AntiRollFilter.THRESHOLD_FALLBACK)

    override fun match(song: SongInfo, minScore: Int): MatchedAudio? {
        return try {
            val keyword = URLEncoder.encode("${song.name} ${song.artist}", "UTF-8")
            val searchUrl = "http://search.kuwo.cn/r.s?correct=1&vipver=1&stype=comprehensive&encoding=utf8&rformat=json&mobi=1&show_copyright_off=1&searchapi=6&all=$keyword"
            val searchRes = httpGet(searchUrl) ?: return null
            val searchJson = JSONObject(searchRes)
            val content = searchJson.optJSONArray("content") ?: return null
            if (content.length() < 2) return null

            val musicPage = content.getJSONObject(1).optJSONObject("musicpage") ?: return null
            val absList = musicPage.optJSONArray("abslist") ?: return null
            if (absList.length() == 0) return null

            val candidateCount = minOf(absList.length(), 10)
            val candidates = ArrayList<RawCandidate<JSONObject>>(candidateCount)

            for (i in 0 until candidateCount) {
                val item = absList.optJSONObject(i) ?: continue
                val rid = item.optString("MUSICRID").substringAfter("MUSIC_")
                if (rid.isEmpty() || AudioValidator.isBlacklistedAudioId(rid)) continue

                val title = unescapeHtml(item.optString("SONGNAME").ifEmpty { item.optString("name", "") })
                val artist = unescapeHtml(item.optString("ARTIST").ifEmpty { item.optString("artist", "") })
                val durationSec = item.optLong("DURATION", 0L)
                val durationMs = if (durationSec > 0) durationSec * 1000L else null

                val score = AntiRollFilter.scoreCandidate(
                    originalTitle = song.name,
                    originalArtist = song.artist,
                    originalDurationMs = song.durationMs.takeIf { it > 0 },
                    candidateTitle = title,
                    candidateArtist = artist,
                    candidateDurationMs = durationMs
                ) + (candidateCount - i)

                if (score >= minScore) {
                    candidates.add(RawCandidate(item, title, artist, durationMs, score))
                }
            }

            candidates.sortByDescending { it.score }
            if (candidates.isEmpty()) return null

            for (cand in candidates) {
                val selectedRid = cand.raw.optString("MUSICRID").substringAfter("MUSIC_")
                val trackUrl = "http://antiserver.kuwo.cn/anti.s?type=convert_url&format=mp3&response=url&rid=MUSIC_$selectedRid"
                val trackRes = httpGet(trackUrl, headers = mapOf("User-Agent" to "okhttp/3.10.0")) ?: continue
                val audioUrl = Regex("http[^\r\n\"'\t ]+").find(trackRes)?.value ?: continue
                if (!AudioValidator.isValidAudioUrl(audioUrl)) {
                    UnmLogger.w(TAG, "Kuwo antiserver returned teaser/prompt audio, rejecting: $audioUrl")
                    continue
                }

                return MatchedAudio(
                    url = audioUrl,
                    br = 320000,
                    source = name,
                    matchedTitle = cand.title.ifBlank { song.name },
                    matchedArtist = cand.artist.ifBlank { song.artist },
                    durationMs = cand.durationMs
                )
            }
            null
        } catch (t: Throwable) {
            UnmLogger.w(TAG, "Kuwo match failed: ${t.message}", t)
            null
        }
    }
}

object MiguSource : AudioSource {
    override val name: String = "migu"
    private const val TAG = "MiguSource"

    override fun match(song: SongInfo): MatchedAudio? = match(song, AntiRollFilter.THRESHOLD_FALLBACK)

    override fun match(song: SongInfo, minScore: Int): MatchedAudio? {
        return try {
            val keyword = URLEncoder.encode("${song.name} ${song.artist}", "UTF-8")
            val searchUrl = "https://m.music.migu.cn/migu/remoting/scr_search_tag?keyword=$keyword&type=2&rows=20&pgc=1"
            val headers = mapOf(
                "origin" to "http://music.migu.cn/",
                "referer" to "http://m.music.migu.cn/v3/",
                "channel" to "0146921"
            )
            val searchRes = httpGet(searchUrl, headers = headers) ?: return null
            val searchJson = JSONObject(searchRes)
            val musics = searchJson.optJSONArray("musics") ?: return null
            if (musics.length() == 0) return null

            val candidateCount = minOf(musics.length(), 10)
            val candidates = ArrayList<RawCandidate<JSONObject>>(candidateCount)

            for (i in 0 until candidateCount) {
                val item = musics.optJSONObject(i) ?: continue
                val id = item.optString("id")
                if (id.isEmpty()) continue

                val title = unescapeHtml(item.optString("songName").ifEmpty {
                    item.optString("title").ifEmpty { item.optString("name", "") }
                })
                val artist = unescapeHtml(item.optString("singerName").ifEmpty {
                    item.optString("singer").ifEmpty { item.optString("artist", "") }
                })
                val durationMs = parseMiguDuration(item)

                val score = AntiRollFilter.scoreCandidate(
                    originalTitle = song.name,
                    originalArtist = song.artist,
                    originalDurationMs = song.durationMs.takeIf { it > 0 },
                    candidateTitle = title,
                    candidateArtist = artist,
                    candidateDurationMs = durationMs
                ) + (candidateCount - i)

                if (score >= minScore) {
                    candidates.add(RawCandidate(item, title, artist, durationMs, score))
                }
            }

            candidates.sortByDescending { it.score }
            if (candidates.isEmpty()) return null

            for (cand in candidates) {
                val selectedId = cand.raw.optString("id")
                val trackUrl = "https://app.c.nf.migu.cn/MIGUM2.0/strategy/listen-url/v2.4?netType=01&resourceType=2&songId=$selectedId&toneFlag=HQ"
                val trackRes = httpGet(trackUrl, headers = headers) ?: continue
                val trackJson = JSONObject(trackRes)
                val data = trackJson.optJSONObject("data") ?: continue
                val playUrl = data.optString("url").ifEmpty { data.optString("playUrl") }
                if (playUrl.isEmpty()) continue

                val fullUrl = if (playUrl.startsWith("http")) playUrl else "http:$playUrl"
                if (!AudioValidator.isValidAudioUrl(fullUrl)) {
                    UnmLogger.w(TAG, "Migu returned invalid audio URL: $fullUrl")
                    continue
                }

                return MatchedAudio(
                    url = fullUrl,
                    br = 320000,
                    source = name,
                    matchedTitle = cand.title.ifBlank { song.name },
                    matchedArtist = cand.artist.ifBlank { song.artist },
                    durationMs = cand.durationMs
                )
            }
            null
        } catch (t: Throwable) {
            UnmLogger.w(TAG, "Migu match failed: ${t.message}", t)
            null
        }
    }

    private fun parseMiguDuration(item: JSONObject): Long? {
        val durationSec = item.optLong("duration", 0L)
        if (durationSec > 0) return if (durationSec in 1..3600) durationSec * 1000L else durationSec
        val lengthStr = item.optString("length", "")
        if (lengthStr.contains(":")) {
            val parts = lengthStr.split(":")
            val min = parts[0].toLongOrNull() ?: 0L
            val sec = parts[1].toLongOrNull() ?: 0L
            return (min * 60 + sec) * 1000L
        }
        return null
    }
}

object KugouSource : AudioSource {
    override val name: String = "kugou"
    private const val TAG = "KugouSource"

    override fun match(song: SongInfo): MatchedAudio? = match(song, AntiRollFilter.THRESHOLD_FALLBACK)

    override fun match(song: SongInfo, minScore: Int): MatchedAudio? {
        return try {
            val keyword = URLEncoder.encode("${song.name} ${song.artist}", "UTF-8")
            val searchUrl = "http://mobilecdn.kugou.com/api/v3/search/song?keyword=$keyword&page=1&pagesize=10"
            val searchRes = httpGet(searchUrl) ?: return null
            val searchJson = JSONObject(searchRes)
            val data = searchJson.optJSONObject("data") ?: return null
            val infoList = data.optJSONArray("info") ?: return null
            if (infoList.length() == 0) return null

            val candidateCount = minOf(infoList.length(), 10)
            val candidates = ArrayList<RawCandidate<JSONObject>>(candidateCount)

            for (i in 0 until candidateCount) {
                val item = infoList.optJSONObject(i) ?: continue
                val hash = item.optString("320hash").ifEmpty {
                    item.optString("hash")
                }
                if (hash.isEmpty()) continue

                val fileName = unescapeHtml(item.optString("filename").ifEmpty { item.optString("FileName", "") })
                var songName = unescapeHtml(item.optString("songname").ifEmpty { item.optString("SongName", "") })
                var singerName = unescapeHtml(item.optString("singername").ifEmpty { item.optString("SingerName", "") })

                if (songName.isEmpty() && fileName.contains(" - ")) {
                    val parts = fileName.split(" - ", limit = 2)
                    if (singerName.isEmpty()) singerName = parts[0].trim()
                    songName = parts[1].trim()
                } else if (songName.isEmpty()) {
                    songName = fileName
                }

                val durationSec = item.optLong("duration", 0L)
                val durationMs = if (durationSec > 0) durationSec * 1000L else null

                val score = AntiRollFilter.scoreCandidate(
                    originalTitle = song.name,
                    originalArtist = song.artist,
                    originalDurationMs = song.durationMs.takeIf { it > 0 },
                    candidateTitle = songName,
                    candidateArtist = singerName,
                    candidateDurationMs = durationMs
                ) + (candidateCount - i)

                if (score >= minScore) {
                    candidates.add(RawCandidate(item, songName, singerName, durationMs, score))
                }
            }

            candidates.sortByDescending { it.score }
            if (candidates.isEmpty()) return null

            for (cand in candidates) {
                val hash = cand.raw.optString("320hash").ifEmpty {
                    cand.raw.optString("hash")
                }
                val albumId = cand.raw.optString("album_id", "0")
                val key = EApiCrypto.md5("${hash}kgcloudv2")
                val trackUrl = "http://trackercdn.kugou.com/i/v2/?key=$key&hash=$hash&appid=1005&pid=2&cmd=25&behavior=play&album_id=$albumId"
                val trackRes = httpGet(trackUrl) ?: continue
                val trackJson = JSONObject(trackRes)
                val urlArr = trackJson.optJSONArray("url")
                val audioUrl = urlArr?.optString(0) ?: ""
                if (!AudioValidator.isValidAudioUrl(audioUrl)) {
                    UnmLogger.w(TAG, "Kugou returned invalid audio URL: $audioUrl")
                    continue
                }

                return MatchedAudio(
                    url = audioUrl,
                    br = 320000,
                    source = name,
                    matchedTitle = cand.title.ifBlank { song.name },
                    matchedArtist = cand.artist.ifBlank { song.artist },
                    durationMs = cand.durationMs
                )
            }
            null
        } catch (t: Throwable) {
            UnmLogger.w(TAG, "Kugou match failed: ${t.message}", t)
            null
        }
    }
}

/**
 * Synchronous HTTP GET helper backed by unified [UnmHttpClient].
 */
fun httpGet(
    urlStr: String,
    headers: Map<String, String> = emptyMap(),
    timeoutMs: Int = 5000
): String? {
    return try {
        val response = UnmHttpClient.get(urlStr, headers = headers, timeoutMs = timeoutMs.toLong())
        response.bodyStringOrNull()
    } catch (t: Throwable) {
        UnmLogger.w("NativeSources", "httpGet error for $urlStr: ${t.message}", t)
        null
    }
}

/**
 * Synchronous HTTP POST helper backed by unified [UnmHttpClient].
 */
fun httpPost(
    urlStr: String,
    body: String,
    headers: Map<String, String> = emptyMap(),
    timeoutMs: Int = 5000
): String? {
    return try {
        val contentType = headers.entries.firstOrNull { it.key.equals("content-type", ignoreCase = true) }?.value
            ?: "application/json; charset=utf-8"
        val response = UnmHttpClient.post(
            url = urlStr,
            body = body,
            contentType = contentType,
            headers = headers,
            timeoutMs = timeoutMs.toLong()
        )
        response.bodyStringOrNull()
    } catch (t: Throwable) {
        UnmLogger.w("NativeSources", "httpPost error for $urlStr: ${t.message}", t)
        null
    }
}
