package pochita.source

import android.util.Log
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import kotlin.math.abs
import org.json.JSONObject
import pochita.crypto.EApiCrypto
import pochita.model.MatchedAudio
import pochita.model.SongInfo

private fun logW(tag: String, msg: String) {
    try {
        android.util.Log.w(tag, msg)
    } catch (_: Throwable) {
        System.err.println("[$tag] $msg")
    }
}

interface AudioSource {
    val name: String
    fun match(song: SongInfo): MatchedAudio?
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
            logW(TAG, "Failed to send Bodian ad unlock: ${t.message}")
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

    override fun match(song: SongInfo): MatchedAudio? {
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

            var selectedRid: String? = null
            for (i in 0 until minOf(absList.length(), 5)) {
                val item = absList.getJSONObject(i)
                val duration = item.optLong("DURATION", 0L) * 1000L
                val rid = item.optString("MUSICRID").substringAfter("MUSIC_")
                if (song.durationMs > 0 && abs(duration - song.durationMs) < 6000) {
                    selectedRid = rid
                    break
                }
            }
            if (selectedRid == null && absList.length() > 0) {
                selectedRid = absList.getJSONObject(0).optString("MUSICRID").substringAfter("MUSIC_")
            }
            if (selectedRid.isNullOrEmpty()) return null

            ensureAdUnlocked()

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

            if (trackRes == null) return null
            val trackJson = JSONObject(trackRes)
            if (trackJson.optInt("code") != 200) return null

            val data = trackJson.optJSONObject("data") ?: return null
            val audioUrl = data.optString("audioHttpsUrl").ifEmpty { data.optString("audioUrl") }
            if (audioUrl.isEmpty() || audioUrl.contains("588957081") || audioUrl.contains("563182854")) return null

            val durationSec = data.optInt("duration", 0)
            if (song.durationMs > 60000 && durationSec in 1..30) {
                return null
            }

            MatchedAudio(
                url = audioUrl,
                br = 320000,
                source = name,
            )
        } catch (t: Throwable) {
            logW(TAG, "Bodian match failed: ${t.message}")
            null
        }
    }
}

object KuwoSource : AudioSource {
    override val name: String = "kuwo"
    private const val TAG = "KuwoSource"

    override fun match(song: SongInfo): MatchedAudio? {
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

            var selectedRid: String? = null
            for (i in 0 until minOf(absList.length(), 5)) {
                val item = absList.getJSONObject(i)
                val duration = item.optLong("DURATION", 0L) * 1000L
                val rid = item.optString("MUSICRID").substringAfter("MUSIC_")
                if (song.durationMs > 0 && abs(duration - song.durationMs) < 6000) {
                    selectedRid = rid
                    break
                }
            }
            if (selectedRid == null && absList.length() > 0) {
                selectedRid = absList.getJSONObject(0).optString("MUSICRID").substringAfter("MUSIC_")
            }

            if (selectedRid.isNullOrEmpty()) return null

            val trackUrl = "http://antiserver.kuwo.cn/anti.s?type=convert_url&format=mp3&response=url&rid=MUSIC_$selectedRid"
            val trackRes = httpGet(trackUrl, headers = mapOf("User-Agent" to "okhttp/3.10.0")) ?: return null
            val audioUrl = Regex("http[^\r\n\"'\t ]+").find(trackRes)?.value ?: return null
            if (audioUrl.contains("588957081") || audioUrl.contains("563182854")) {
                logW(TAG, "Kuwo antiserver returned teaser/prompt audio, rejecting.")
                return null
            }

            MatchedAudio(
                url = audioUrl,
                br = 320000,
                source = name,
            )
        } catch (t: Throwable) {
            logW(TAG, "Kuwo match failed: ${t.message}")
            null
        }
    }
}

object MiguSource : AudioSource {
    override val name: String = "migu"
    private const val TAG = "MiguSource"

    override fun match(song: SongInfo): MatchedAudio? {
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

            var selectedId: String? = null
            for (i in 0 until minOf(musics.length(), 5)) {
                val item = musics.getJSONObject(i)
                val id = item.optString("id")
                if (id.isNotEmpty()) {
                    selectedId = id
                    break
                }
            }
            if (selectedId == null) return null

            val trackUrl = "https://app.c.nf.migu.cn/MIGUM2.0/strategy/listen-url/v2.4?netType=01&resourceType=2&songId=$selectedId&toneFlag=HQ"
            val trackRes = httpGet(trackUrl, headers = headers) ?: return null
            val trackJson = JSONObject(trackRes)
            val data = trackJson.optJSONObject("data") ?: return null
            val playUrl = data.optString("url").ifEmpty { data.optString("playUrl") }
            if (playUrl.isEmpty()) return null

            val fullUrl = if (playUrl.startsWith("http")) playUrl else "http:$playUrl"
            MatchedAudio(
                url = fullUrl,
                br = 320000,
                source = name,
            )
        } catch (t: Throwable) {
            logW(TAG, "Migu match failed: ${t.message}")
            null
        }
    }
}

object KugouSource : AudioSource {
    override val name: String = "kugou"
    private const val TAG = "KugouSource"

    override fun match(song: SongInfo): MatchedAudio? {
        return try {
            val keyword = URLEncoder.encode("${song.name} ${song.artist}", "UTF-8")
            val searchUrl = "http://mobilecdn.kugou.com/api/v3/search/song?keyword=$keyword&page=1&pagesize=10"
            val searchRes = httpGet(searchUrl) ?: return null
            val searchJson = JSONObject(searchRes)
            val data = searchJson.optJSONObject("data") ?: return null
            val infoList = data.optJSONArray("info") ?: return null
            if (infoList.length() == 0) return null

            val firstSong = infoList.getJSONObject(0)
            val hash = firstSong.optString("320hash").ifEmpty {
                firstSong.optString("hash")
            }
            val albumId = firstSong.optString("album_id", "0")
            if (hash.isEmpty()) return null

            val key = EApiCrypto.md5("${hash}kgcloudv2")
            val trackUrl = "http://trackercdn.kugou.com/i/v2/?key=$key&hash=$hash&appid=1005&pid=2&cmd=25&behavior=play&album_id=$albumId"
            val trackRes = httpGet(trackUrl) ?: return null
            val trackJson = JSONObject(trackRes)
            val urlArr = trackJson.optJSONArray("url")
            val audioUrl = urlArr?.optString(0) ?: ""
            if (audioUrl.isEmpty()) return null

            MatchedAudio(
                url = audioUrl,
                br = 320000,
                source = name,
            )
        } catch (t: Throwable) {
            logW(TAG, "Kugou match failed: ${t.message}")
            null
        }
    }
}

fun httpGet(urlStr: String, headers: Map<String, String> = emptyMap(), timeoutMs: Int = 5000): String? {
    return try {
        val conn = (URL(urlStr).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = timeoutMs
            readTimeout = timeoutMs
            instanceFollowRedirects = true
            headers.forEach { (k, v) -> setRequestProperty(k, v) }
        }
        if (conn.responseCode in 200..299) {
            conn.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
        } else null
    } catch (_: Throwable) {
        null
    }
}

fun httpPost(urlStr: String, body: String, headers: Map<String, String> = emptyMap(), timeoutMs: Int = 5000): String? {
    return try {
        val conn = (URL(urlStr).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = timeoutMs
            readTimeout = timeoutMs
            doOutput = true
            headers.forEach { (k, v) -> setRequestProperty(k, v) }
        }
        conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
        if (conn.responseCode in 200..299) {
            conn.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
        } else null
    } catch (_: Throwable) {
        null
    }
}

