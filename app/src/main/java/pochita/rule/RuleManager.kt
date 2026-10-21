package pochita.rule

import android.content.Context
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.concurrent.atomic.AtomicInteger
import pochita.common.UnmHttpClient
import pochita.common.UnmLogger
import pochita.model.MatchedAudio
import pochita.model.SongInfo
import pochita.source.AudioValidator
import pochita.source.BodianSource
import pochita.source.KugouSource
import pochita.source.KuwoSource
import pochita.source.MiguSource
import pochita.source.QuickJsEngine

/**
 * Robust rule manager providing remote updates, multi-tier dry-run syntax verification,
 * atomic file swap, resilient rollback, and UI settings compatibility.
 */
object RuleManager {
    const val TAG = "RuleManager"
    const val RULE_FILE_NAME = "unm_rule.js"
    const val RULE_TMP_FILE_NAME = "unm_rule.js.tmp"
    const val PREFS_NAME = "config"
    const val KEY_CACHED_RULE_SCRIPT = "cached_rule_script"
    const val KEY_RULE_URL = "rule_url"
    const val DEFAULT_RULE_URL = "https://raw.githubusercontent.com/jnshuiji/unm/refs/heads/main/rules/unm_rule.js"

    const val MAX_CONSECUTIVE_ERRORS = 3
    const val MAX_SCRIPT_SIZE_BYTES = 2 * 1024 * 1024 // 2MB

    const val DEFAULT_EMBEDDED_RULE = """
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

    private val consecutiveErrorCount = AtomicInteger(0)
    @Volatile
    private var activeScript: String? = null
    @Volatile
    private var isUsingDefaultRule: Boolean = true
    @Volatile
    private var appContext: Context? = null

    fun isUsingDefault(): Boolean = isUsingDefaultRule
    fun getActiveScript(): String? = activeScript
    fun getConsecutiveErrorCount(): Int = consecutiveErrorCount.get()
    fun setConsecutiveErrorCountForTest(count: Int) { consecutiveErrorCount.set(count) }
    fun resetForTest() {
        consecutiveErrorCount.set(0)
        activeScript = null
        isUsingDefaultRule = true
        appContext = null
    }

    @Synchronized
    fun init(context: Context, customScript: String? = null) {
        appContext = context.applicationContext ?: context
        try {
            if (!customScript.isNullOrBlank()) {
                val validation = validateRuleScript(customScript)
                if (validation.isSuccess) {
                    QuickJsEngine.loadScript(customScript)
                    activeScript = customScript
                    isUsingDefaultRule = false
                    consecutiveErrorCount.set(0)
                    UnmLogger.i(TAG, "Loaded UNM rule from remote preferences (${customScript.length} chars)")
                    return
                } else {
                    UnmLogger.w(TAG, "Custom script from preferences failed validation: ${validation.exceptionOrNull()?.message}, falling back")
                }
            }

            val file = File(context.filesDir, RULE_FILE_NAME)
            if (file.exists() && file.length() > 0) {
                val cachedScript = file.readText(Charsets.UTF_8)
                val validation = validateRuleScript(cachedScript)
                if (validation.isSuccess) {
                    QuickJsEngine.loadScript(cachedScript)
                    activeScript = cachedScript
                    isUsingDefaultRule = false
                    consecutiveErrorCount.set(0)
                    UnmLogger.i(TAG, "Loaded cached UNM rule (${cachedScript.length} chars)")
                    return
                } else {
                    UnmLogger.w(TAG, "Cached file rule failed validation, rewriting with embedded default: ${validation.exceptionOrNull()?.message}")
                }
            }

            writeAtomic(file, DEFAULT_EMBEDDED_RULE)
            QuickJsEngine.loadScript(DEFAULT_EMBEDDED_RULE)
            activeScript = DEFAULT_EMBEDDED_RULE
            isUsingDefaultRule = true
            consecutiveErrorCount.set(0)
            UnmLogger.i(TAG, "Initialized default embedded UNM rule")
        } catch (t: Throwable) {
            UnmLogger.w(TAG, "Failed to load cached rule: ${t.message}", t)
            QuickJsEngine.loadScript(DEFAULT_EMBEDDED_RULE)
            activeScript = DEFAULT_EMBEDDED_RULE
            isUsingDefaultRule = true
        }
    }

    fun updateRuleFromUrl(context: Context, url: String): Result<Int> {
        val trimmed = url.trim()
        if (trimmed.isEmpty()) {
            return Result.failure(IllegalArgumentException("规则订阅地址不能为空"))
        }
        if (!trimmed.startsWith("http://", ignoreCase = true) && !trimmed.startsWith("https://", ignoreCase = true)) {
            return Result.failure(IllegalArgumentException("规则订阅地址必须以 http:// 或 https:// 开头"))
        }

        val response = try {
            UnmHttpClient.get(
                url = trimmed,
                headers = mapOf("User-Agent" to "Mozilla/5.0 (Android) NetEase-UNM-LSPosed"),
                timeoutMs = 10000L
            )
        } catch (t: Throwable) {
            val errMsg = mapNetworkException(t)
            UnmLogger.w(TAG, "Failed to download rule from $trimmed: $errMsg", t)
            return Result.failure(Exception(errMsg, t))
        }

        if (!response.isSuccessful) {
            val error = response.error
            val errMsg = if (error != null) {
                mapNetworkException(error)
            } else {
                "HTTP ${response.code} (请求失败)，请检查链接有效性"
            }
            UnmLogger.w(TAG, "HTTP error updating rule from $trimmed: code=${response.code}, msg=$errMsg")
            return Result.failure(Exception(errMsg))
        }

        val script = response.bodyString
        if (script.isBlank()) {
            return Result.failure(Exception("下载的规则文件为空"))
        }

        return saveScriptInternal(context, script, sourceUrl = trimmed)
    }

    fun saveCustomScript(context: Context, script: String): Result<Int> {
        return saveScriptInternal(context, script, sourceUrl = null)
    }

    @Synchronized
    private fun saveScriptInternal(context: Context, script: String, sourceUrl: String?): Result<Int> {
        val validation = validateRuleScript(script)
        if (validation.isFailure) {
            val err = validation.exceptionOrNull() ?: Exception("规则文件校验失败")
            UnmLogger.w(TAG, "Script validation rejected: ${err.message}")
            return Result.failure(err)
        }

        val file = File(context.filesDir, RULE_FILE_NAME)
        val writeResult = writeAtomic(file, script)
        if (writeResult.isFailure) {
            val err = writeResult.exceptionOrNull() ?: IOException("写入规则文件失败")
            UnmLogger.e(TAG, "Failed to write rule atomically", err)
            return Result.failure(err)
        }

        QuickJsEngine.loadScript(script)
        activeScript = script
        isUsingDefaultRule = false
        consecutiveErrorCount.set(0)

        try {
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            val editor = prefs.edit().putString(KEY_CACHED_RULE_SCRIPT, script)
            if (sourceUrl != null) {
                editor.putString(KEY_RULE_URL, sourceUrl)
            }
            editor.apply()
        } catch (t: Throwable) {
            UnmLogger.w(TAG, "Failed to save cached_rule_script to SharedPreferences", t)
        }

        val desc = if (sourceUrl != null) "from: $sourceUrl" else "custom script"
        UnmLogger.i(TAG, "Successfully updated rule $desc (${script.length} bytes)")
        return Result.success(script.length)
    }

    @Synchronized
    fun resetToDefault(context: Context): Result<Int> {
        return try {
            val file = File(context.filesDir, RULE_FILE_NAME)
            val writeResult = writeAtomic(file, DEFAULT_EMBEDDED_RULE)
            if (writeResult.isFailure) {
                return Result.failure(writeResult.exceptionOrNull() ?: IOException("写入默认规则失败"))
            }

            QuickJsEngine.loadScript(DEFAULT_EMBEDDED_RULE)
            activeScript = DEFAULT_EMBEDDED_RULE
            isUsingDefaultRule = true
            consecutiveErrorCount.set(0)

            try {
                val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                prefs.edit().remove(KEY_CACHED_RULE_SCRIPT).apply()
            } catch (t: Throwable) {
                UnmLogger.w(TAG, "Failed to remove cached_rule_script on reset", t)
            }

            UnmLogger.i(TAG, "Reset to default embedded UNM rule")
            Result.success(DEFAULT_EMBEDDED_RULE.length)
        } catch (t: Throwable) {
            UnmLogger.e(TAG, "Failed to reset rule to default", t)
            Result.failure(t)
        }
    }

    @Synchronized
    fun rollbackToDefault(context: Context?): Boolean {
        UnmLogger.w(TAG, "Rolling back active rule to embedded default rule")
        return try {
            QuickJsEngine.loadScript(DEFAULT_EMBEDDED_RULE)
            activeScript = DEFAULT_EMBEDDED_RULE
            isUsingDefaultRule = true
            consecutiveErrorCount.set(0)

            val ctx = context ?: appContext
            ctx?.let { c ->
                val file = File(c.filesDir, RULE_FILE_NAME)
                writeAtomic(file, DEFAULT_EMBEDDED_RULE)
                try {
                    val prefs = c.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                    prefs.edit().remove(KEY_CACHED_RULE_SCRIPT).apply()
                } catch (t: Throwable) {
                    UnmLogger.w(TAG, "Failed to remove cached_rule_script during rollback", t)
                }
            }

            UnmLogger.i(TAG, "Rolled back to embedded default rule successfully")
            true
        } catch (t: Throwable) {
            UnmLogger.e(TAG, "Critical failure during rollbackToDefault", t)
            false
        }
    }

    fun matchAudio(
        song: SongInfo,
        priority: List<String> = listOf("bodian", "kuwo", "migu", "kugou")
    ): MatchedAudio? {
        if (AudioValidator.isBlacklistedAudioId(song.id)) {
            UnmLogger.w(TAG, "Song ID ${song.id} is blacklisted by AudioValidator, skipping matching.")
            return null
        }

        UnmLogger.i(TAG, "Matching audio for: ${song.name} - ${song.artist} (id=${song.id})")

        // 1. Native sound source resolution
        for (sourceName in priority) {
            val source = when (sourceName) {
                "bodian" -> BodianSource
                "kuwo" -> KuwoSource
                "migu" -> MiguSource
                "kugou" -> KugouSource
                else -> null
            } ?: continue

            try {
                val result = source.match(song)
                if (result != null && result.url.isNotEmpty() && AudioValidator.isValidAudioUrl(result.url)) {
                    UnmLogger.i(TAG, "Matched via Native engine [${source.name}]: ${result.url}")
                    return result
                }
            } catch (t: Throwable) {
                UnmLogger.w(TAG, "Native source ${source.name} failed: ${t.message}", t)
            }
        }

        // 2. QuickJS dynamic rule resolution with crash tracking & automatic rollback
        if (QuickJsEngine.hasScript()) {
            try {
                val jsResult = QuickJsEngine.match(song)
                if (jsResult != null && jsResult.url.isNotEmpty() && AudioValidator.isValidAudioUrl(jsResult.url)) {
                    consecutiveErrorCount.set(0)
                    UnmLogger.i(TAG, "Matched via QuickJS rule [${jsResult.source}]: ${jsResult.url}")
                    return jsResult
                }
                consecutiveErrorCount.set(0)
            } catch (t: Throwable) {
                val count = consecutiveErrorCount.incrementAndGet()
                UnmLogger.w(TAG, "QuickJS execution error ($count/$MAX_CONSECUTIVE_ERRORS): ${t.message}", t)
                if (count >= MAX_CONSECUTIVE_ERRORS) {
                    UnmLogger.e(TAG, "QuickJS rule exceeded maximum error threshold ($count). Triggering automatic rollback.")
                    rollbackToDefault(appContext)
                }
            }
        }

        UnmLogger.w(TAG, "No audio matched for: ${song.name} - ${song.artist}")
        return null
    }

    fun validateRuleScript(script: String): Result<Unit> {
        if (script.isBlank()) {
            return Result.failure(IllegalArgumentException("规则脚本内容不能为空"))
        }
        if (script.length > MAX_SCRIPT_SIZE_BYTES) {
            return Result.failure(IllegalArgumentException("规则脚本大小超过限制 (最大 ${MAX_SCRIPT_SIZE_BYTES / 1024 / 1024}MB)"))
        }
        if (script.trim().length < 30) {
            return Result.failure(IllegalArgumentException("规则脚本内容过短，非有效脚本"))
        }

        val lower = script.trim().lowercase()
        if (lower.startsWith("<!doctype html") ||
            lower.startsWith("<html") ||
            lower.contains("<head>") ||
            lower.contains("<body>")
        ) {
            return Result.failure(IllegalArgumentException("下载内容为 HTML 网页而非 JavaScript 规则脚本"))
        }

        val unmMatchRegex = Regex(
            """(?m)(?:(?:async\s+)?function\s+unmMatch\s*\(|(?:var|let|const)?\s*unmMatch\s*=\s*(?:async\s+)?(?:function|\([^)]*\)\s*=>))"""
        )
        if (!unmMatchRegex.containsMatchIn(script)) {
            return Result.failure(IllegalArgumentException("规则文件校验失败：未包含有效的 unmMatch 函数定义"))
        }

        val structuralCheck = checkStructuralBalance(script)
        if (structuralCheck.isFailure) {
            return structuralCheck
        }

        val qjsValid = QuickJsEngine.validateScript(script)
        if (!qjsValid) {
            return Result.failure(IllegalArgumentException("规则文件校验失败：unmMatch 语法或执行错误"))
        }

        return Result.success(Unit)
    }

    internal fun checkStructuralBalance(code: String): Result<Unit> {
        val stack = ArrayDeque<Char>()
        var i = 0
        val len = code.length

        var inSingleQuote = false
        var inDoubleQuote = false
        var inTemplate = false
        var inLineComment = false
        var inBlockComment = false
        var lastPunctuation = '\u0000'

        while (i < len) {
            val c = code[i]
            val next = if (i + 1 < len) code[i + 1] else '\u0000'

            if (inLineComment) {
                if (c == '\n' || c == '\r') inLineComment = false
                i++
                continue
            }

            if (inBlockComment) {
                if (c == '*' && next == '/') {
                    inBlockComment = false
                    i += 2
                    continue
                }
                i++
                continue
            }

            if (inSingleQuote) {
                if (c == '\\') { i += 2; continue }
                if (c == '\'') inSingleQuote = false
                i++
                continue
            }

            if (inDoubleQuote) {
                if (c == '\\') { i += 2; continue }
                if (c == '"') inDoubleQuote = false
                i++
                continue
            }

            if (inTemplate) {
                if (c == '\\') { i += 2; continue }
                if (c == '`') inTemplate = false
                i++
                continue
            }

            // Whitespace handling
            if (c.isWhitespace()) {
                i++
                continue
            }

            // Code mode comments
            if (c == '/' && next == '/') {
                inLineComment = true
                i += 2
                continue
            }
            if (c == '/' && next == '*') {
                inBlockComment = true
                i += 2
                continue
            }

            // Regex literal check: if '/' follows typical expression start punctuations
            if (c == '/' && (lastPunctuation in setOf('(', ',', '=', ':', '[', '!', '&', '|', ';', '{', '?', '~', '^', '+', '-', '*', '%', '\u0000'))) {
                // Regex literal: skip until terminating '/'
                var j = i + 1
                var inCharClass = false
                var closed = false
                while (j < len) {
                    val r = code[j]
                    if (r == '\\') {
                        j += 2
                        continue
                    }
                    if (r == '[') inCharClass = true
                    if (r == ']') inCharClass = false
                    if (r == '/' && !inCharClass) {
                        closed = true
                        i = j + 1
                        break
                    }
                    if (r == '\n' || r == '\r') break
                    j++
                }
                if (closed) {
                    lastPunctuation = '/'
                    continue
                }
            }

            if (c == '\'') { inSingleQuote = true; i++; continue }
            if (c == '"') { inDoubleQuote = true; i++; continue }
            if (c == '`') { inTemplate = true; i++; continue }

            lastPunctuation = c

            if (c == '{' || c == '(' || c == '[') {
                stack.addLast(c)
            } else if (c == '}' || c == ')' || c == ']') {
                if (stack.isEmpty()) {
                    return Result.failure(IllegalArgumentException("规则脚本语法错误：未匹配的右括号 '$c'"))
                }
                val open = stack.removeLast()
                val expected = when (c) {
                    '}' -> '{'
                    ')' -> '('
                    ']' -> '['
                    else -> '\u0000'
                }
                if (open != expected) {
                    return Result.failure(IllegalArgumentException("规则脚本语法错误：括号不匹配，期望 '$expected' 闭合，实际遇到 '$c'"))
                }
            }
            i++
        }

        if (inSingleQuote || inDoubleQuote) {
            return Result.failure(IllegalArgumentException("规则脚本语法错误：未闭合的字符串字面量 (可能脚本被截断)"))
        }
        if (inTemplate) {
            return Result.failure(IllegalArgumentException("规则脚本语法错误：未闭合的模板字符串 (可能脚本被截断)"))
        }
        if (inBlockComment) {
            return Result.failure(IllegalArgumentException("规则脚本语法错误：未闭合的多行注释 (可能脚本被截断)"))
        }
        if (stack.isNotEmpty()) {
            val unclosed = stack.joinToString(" ")
            return Result.failure(IllegalArgumentException("规则脚本语法错误：存在未闭合的括号或代码块: $unclosed (可能脚本被截断)"))
        }

        return Result.success(Unit)
    }

    internal fun writeAtomic(targetFile: File, content: String): Result<Unit> {
        val parentDir = targetFile.parentFile ?: return Result.failure(
            IOException("Target file has no parent directory: ${targetFile.path}")
        )
        if (!parentDir.exists() && !parentDir.mkdirs()) {
            return Result.failure(IOException("Failed to create directory: ${parentDir.path}"))
        }

        val tempFile = File(parentDir, "${targetFile.name}.tmp")
        return try {
            tempFile.outputStream().use { fos ->
                fos.write(content.toByteArray(Charsets.UTF_8))
                fos.flush()
                try {
                    fos.fd.sync()
                } catch (_: Throwable) {}
            }

            try {
                Files.move(
                    tempFile.toPath(),
                    targetFile.toPath(),
                    StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING
                )
            } catch (_: java.nio.file.AtomicMoveNotSupportedException) {
                Files.move(
                    tempFile.toPath(),
                    targetFile.toPath(),
                    StandardCopyOption.REPLACE_EXISTING
                )
            }
            Result.success(Unit)
        } catch (t: Throwable) {
            if (tempFile.exists()) {
                tempFile.delete()
            }
            Result.failure(t)
        }
    }

    private fun mapNetworkException(t: Throwable): String {
        return when (t) {
            is java.net.UnknownHostException -> "无法解析域名 (DNS 解析失败)"
            is java.net.SocketTimeoutException -> "连接超时"
            is java.net.ConnectException -> "连接被拒绝"
            else -> t.message?.ifBlank { null } ?: "${t.javaClass.simpleName}: 下载规则失败"
        }
    }
}
