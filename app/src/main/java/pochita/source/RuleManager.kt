package pochita.source

import android.content.Context
import android.util.Log
import java.io.File
import pochita.model.MatchedAudio
import pochita.model.SongInfo

import java.net.HttpURLConnection
import java.net.URL

object RuleManager {
    private const val TAG = "RuleManager"
    private const val RULE_FILE_NAME = "unm_rule.js"

    // 默认内置 JS 规则脚本
    private const val DEFAULT_EMBEDDED_RULE = """
    function unmMatch(id, name, artist, duration) {
        var keyword = encodeURIComponent(name + ' ' + artist);

        // 1. 波点音乐源
        try {
            var bodianRes = matchBodian(keyword, duration);
            if (bodianRes && isValidAudio(bodianRes.url)) return bodianRes;
        } catch (e) {}

        // 2. 酷我音乐源
        try {
            var kuwoRes = matchKuwo(keyword, duration);
            if (kuwoRes && isValidAudio(kuwoRes.url)) return kuwoRes;
        } catch (e) {}

        // 3. 酷狗音乐源
        try {
            var kugouRes = matchKugou(keyword, duration);
            if (kugouRes && isValidAudio(kugouRes.url)) return kugouRes;
        } catch (e) {}

        return null;
    }

    function isValidAudio(url) {
        if (!url || typeof url !== 'string' || url.indexOf('http') !== 0) return false;
        if (url.indexOf('588957081') >= 0 || url.indexOf('563182854') >= 0) return false;
        return true;
    }

    function matchBodian(keyword, duration) {
        var searchUrl = 'http://search.kuwo.cn/r.s?correct=1&vipver=1&stype=comprehensive&encoding=utf8&rformat=json&mobi=1&show_copyright_off=1&searchapi=6&all=' + keyword;
        var searchRes = nativeBridge.httpGet(searchUrl, 'okhttp/3.10.0');
        if (!searchRes) return null;
        var json = JSON.parse(searchRes);
        var abslist = json.content && json.content[1] && json.content[1].musicpage && json.content[1].musicpage.abslist;
        if (!abslist || abslist.length === 0) return null;
        var rid = abslist[0].MUSICRID.replace('MUSIC_', '');

        var devId = '10086' + Math.floor(Math.random() * 1000000);
        var adHeaders = JSON.stringify({
            'user-agent': 'Dart/2.19 (dart:io)',
            'plat': 'ar',
            'channel': 'aliopen',
            'devid': devId,
            'ver': '3.9.0',
            'host': 'bd-api.kuwo.cn',
            'qimei36': '1e9970cbcdc20a031dee9f37100017e1840e',
            'content-type': 'application/json; charset=utf-8'
        });
        var adData = JSON.stringify({ type: 5, subType: 5, musicId: 0, adToken: '' });
        nativeBridge.httpPost('http://bd-api.kuwo.cn/api/service/advert/watch?uid=-1&token=&timestamp=1724306124436&sign=15a676d66285117ad714e8c8371691da', adData, adHeaders);

        var baseAudioUrl = 'http://bd-api.kuwo.cn/api/play/music/v2/audioUrl?&br=320kmp3&musicId=' + rid;
        var now = Date.now();
        var strWithTime = baseAudioUrl + '&timestamp=' + now;
        var query = strWithTime.substring(strWithTime.indexOf('?') + 1);
        var filteredChars = query.replace(/[^a-zA-Z0-9]/g, '').split('').sort().join('');
        var path = '/api/play/music/v2/audioUrl';
        var sign = nativeBridge.md5('kuwotest' + filteredChars + path);
        var signedUrl = strWithTime + '&sign=' + sign;

        var playHeaders = JSON.stringify({
            'user-agent': 'Dart/2.19 (dart:io)',
            'plat': 'ar',
            'channel': 'aliopen',
            'devid': devId,
            'ver': '3.9.0',
            'host': 'bd-api.kuwo.cn',
            'X-Forwarded-For': '1.0.1.114'
        });

        var playRes = nativeBridge.httpGet(signedUrl, 'Dart/2.19 (dart:io)', playHeaders);
        if (playRes) {
            var playJson = JSON.parse(playRes);
            if (playJson.code === 200 && playJson.data && playJson.data.audioUrl) {
                return { url: playJson.data.audioUrl, br: 320000, source: 'bodian-js' };
            }
        }
        return null;
    }

    function matchKuwo(keyword, duration) {
        var searchUrl = 'http://search.kuwo.cn/r.s?correct=1&vipver=1&stype=comprehensive&encoding=utf8&rformat=json&mobi=1&show_copyright_off=1&searchapi=6&all=' + keyword;
        var searchRes = nativeBridge.httpGet(searchUrl, 'okhttp/3.10.0');
        if (!searchRes) return null;
        var json = JSON.parse(searchRes);
        var abslist = json.content && json.content[1] && json.content[1].musicpage && json.content[1].musicpage.abslist;
        if (!abslist || abslist.length === 0) return null;
        var rid = abslist[0].MUSICRID.replace('MUSIC_', '');
        var trackUrl = 'http://antiserver.kuwo.cn/anti.s?type=convert_url&format=mp3&response=url&rid=MUSIC_' + rid;
        var trackRes = nativeBridge.httpGet(trackUrl, 'okhttp/3.10.0');
        if (trackRes && isValidAudio(trackRes)) {
            return { url: trackRes, br: 320000, source: 'kuwo-js' };
        }
        return null;
    }

    function matchKugou(keyword, duration) {
        var searchUrl = 'http://mobilecdn.kugou.com/api/v3/search/song?keyword=' + keyword + '&page=1&pagesize=5';
        var searchRes = nativeBridge.httpGet(searchUrl, 'okhttp/3.10.0');
        if (!searchRes) return null;
        var json = JSON.parse(searchRes);
        var info = json.data && json.data.info;
        if (!info || info.length === 0) return null;
        var song = info[0];
        var hash = song['320hash'] || song['hash'] || '';
        if (!hash) return null;
        var albumId = song.album_id || '';
        var key = nativeBridge.md5(hash + 'kgcloudv2');
        var trackUrl = 'http://trackercdn.kugou.com/i/v2/?key=' + key + '&hash=' + hash + '&appid=1005&pid=2&cmd=25&behavior=play&album_id=' + albumId;
        var trackRes = nativeBridge.httpGet(trackUrl, 'okhttp/3.10.0');
        if (trackRes) {
            var trackJson = JSON.parse(trackRes);
            if (trackJson.url && trackJson.url.length > 0) {
                return { url: trackJson.url[0], br: 320000, source: 'kugou-js' };
            }
        }
        return null;
    }
    """

    fun init(context: Context, customScript: String? = null) {
        try {
            if (!customScript.isNullOrBlank()) {
                QuickJsEngine.loadScript(customScript)
                Log.i(TAG, "Loaded UNM rule from remote preferences (${customScript.length} chars)")
                return
            }
            val file = File(context.filesDir, RULE_FILE_NAME)
            if (file.exists() && file.length() > 0) {
                val cachedScript = file.readText(Charsets.UTF_8)
                QuickJsEngine.loadScript(cachedScript)
                Log.i(TAG, "Loaded cached UNM rule (${cachedScript.length} chars)")
            } else {
                file.writeText(DEFAULT_EMBEDDED_RULE, Charsets.UTF_8)
                QuickJsEngine.loadScript(DEFAULT_EMBEDDED_RULE)
                Log.i(TAG, "Initialized default embedded UNM rule")
            }
        } catch (t: Throwable) {
            Log.w(TAG, "Failed to load cached rule: ${t.message}")
            QuickJsEngine.loadScript(DEFAULT_EMBEDDED_RULE)
        }
    }

    /**
     * 从远程 URL 更新规则脚本，支持自动跟随重定向
     */
    fun updateRuleFromUrl(context: Context, url: String): Result<Int> {
        val trimmed = url.trim()
        if (trimmed.isEmpty()) {
            return Result.failure(IllegalArgumentException("规则订阅地址不能为空"))
        }
        return try {
            var currentUrl = trimmed
            var redirectCount = 0
            var conn: HttpURLConnection
            var code: Int
            while (true) {
                conn = (URL(currentUrl).openConnection() as HttpURLConnection).apply {
                    requestMethod = "GET"
                    connectTimeout = 8000
                    readTimeout = 8000
                    instanceFollowRedirects = false
                    setRequestProperty("User-Agent", "Mozilla/5.0 (Android) NetEase-UNM-LSPosed")
                }
                code = conn.responseCode
                if (code in 301..308 && redirectCount < 5) {
                    val location = conn.getHeaderField("Location")
                    if (!location.isNullOrBlank()) {
                        currentUrl = if (location.startsWith("http")) location else URL(URL(currentUrl), location).toString()
                        redirectCount++
                        conn.disconnect()
                        continue
                    }
                }
                break
            }

            if (code !in 200..299) {
                return Result.failure(Exception("HTTP $code (${conn.responseMessage ?: "请求失败"})，请检查链接有效性"))
            }
            val script = conn.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
            if (!script.contains("unmMatch")) {
                return Result.failure(Exception("规则文件校验失败：未包含有效的 unmMatch 函数定义"))
            }

            val file = File(context.filesDir, RULE_FILE_NAME)
            file.writeText(script, Charsets.UTF_8)
            QuickJsEngine.loadScript(script)
            Log.i(TAG, "Successfully updated rule from: $trimmed (${script.length} bytes)")
            Result.success(script.length)
        } catch (t: Throwable) {
            val errMsg = when (t) {
                is java.net.UnknownHostException -> "无法解析域名 (DNS 解析失败)"
                is java.net.SocketTimeoutException -> "连接超时"
                else -> t.message?.ifBlank { null } ?: "${t.javaClass.simpleName}: 下载规则失败"
            }
            Log.w(TAG, "Failed to update rule from $trimmed: $errMsg", t)
            Result.failure(Exception(errMsg))
        }
    }

    /**
     * 恢复至内置默认规则
     */
    fun resetToDefault(context: Context): Result<Int> {
        return try {
            val file = File(context.filesDir, RULE_FILE_NAME)
            file.writeText(DEFAULT_EMBEDDED_RULE, Charsets.UTF_8)
            QuickJsEngine.loadScript(DEFAULT_EMBEDDED_RULE)
            Log.i(TAG, "Reset to default embedded UNM rule")
            Result.success(DEFAULT_EMBEDDED_RULE.length)
        } catch (t: Throwable) {
            Result.failure(t)
        }
    }

    /**
     * 音源匹配调度逻辑：
     * 1. 优先调用本地原生音源实现 (Bodian -> Kuwo -> Migu -> Kugou)
     * 2. 本地音源未命中时，调用 QuickJS 执行动态规则
     */
    fun matchAudio(song: SongInfo, priority: List<String> = listOf("bodian", "kuwo", "migu", "kugou")): MatchedAudio? {
        Log.i(TAG, "Matching audio for: ${song.name} - ${song.artist} (id=${song.id})")

        // 1. 本地原生音源解析
        for (sourceName in priority) {
            val source = when (sourceName) {
                "bodian" -> BodianSource
                "kuwo" -> KuwoSource
                "migu" -> MiguSource
                "kugou" -> KugouSource
                else -> null
            } ?: continue

            val result = source.match(song)
            if (result != null && result.url.isNotEmpty() && !isPromptAudio(result.url)) {
                Log.i(TAG, "Matched via Native engine [${source.name}]: ${result.url}")
                return result
            }
        }

        // 2. QuickJS 动态规则解析
        if (QuickJsEngine.hasScript()) {
            val jsResult = QuickJsEngine.match(song)
            if (jsResult != null && jsResult.url.isNotEmpty() && !isPromptAudio(jsResult.url)) {
                Log.i(TAG, "Matched via QuickJS rule [${jsResult.source}]: ${jsResult.url}")
                return jsResult
            }
        }

        Log.w(TAG, "No audio matched for: ${song.name} - ${song.artist}")
        return null
    }

    private fun isPromptAudio(url: String): Boolean {
        return url.contains("588957081") || url.contains("563182854")
    }
}

