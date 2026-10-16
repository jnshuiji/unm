package pochita.source

import android.util.Log
import com.dokar.quickjs.QuickJs
import com.dokar.quickjs.binding.define
import com.dokar.quickjs.binding.function
import java.net.HttpURLConnection
import java.net.URL
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import pochita.crypto.EApiCrypto
import pochita.model.MatchedAudio
import pochita.model.SongInfo

/**
 * 嵌入式 QuickJS 执行引擎，负责执行 JavaScript 格式的音源解析规则。
 */
object QuickJsEngine {
    private const val TAG = "QuickJsEngine"

    private var activeScript: String? = null
    private var isInitialized = false

    fun loadScript(scriptContent: String) {
        activeScript = scriptContent
        isInitialized = true
    }

    fun hasScript(): Boolean = !activeScript.isNullOrBlank()

    /**
     * 调用 JS 规则进行音源匹配
     */
    fun match(song: SongInfo): MatchedAudio? {
        val script = activeScript ?: return null
        return try {
            runBlocking(Dispatchers.IO) {
                val qjs = QuickJs.create(jobDispatcher = Dispatchers.IO)
                try {
                    // 注入原生网络与加密桥接对象 nativeBridge
                    qjs.define("nativeBridge") {
                        function("httpGet") { args ->
                            val url = args.getOrNull(0) as? String ?: return@function ""
                            val ua = (args.getOrNull(1) as? String) ?: "okhttp/3.10.0"
                            val headersJson = args.getOrNull(2) as? String
                            try {
                                val conn = (URL(url).openConnection() as HttpURLConnection).apply {
                                    requestMethod = "GET"
                                    connectTimeout = 5000
                                    readTimeout = 5000
                                    instanceFollowRedirects = true
                                    setRequestProperty("User-Agent", ua)
                                    if (!headersJson.isNullOrEmpty()) {
                                        val json = JSONObject(headersJson)
                                        for (key in json.keys()) {
                                            setRequestProperty(key, json.optString(key))
                                        }
                                    }
                                }
                                if (conn.responseCode in 200..299) {
                                    conn.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
                                } else ""
                            } catch (_: Throwable) {
                                ""
                            }
                        }

                        function("httpPost") { args ->
                            val url = args.getOrNull(0) as? String ?: return@function ""
                            val body = args.getOrNull(1) as? String ?: ""
                            val headersJson = args.getOrNull(2) as? String
                            val ua = (args.getOrNull(3) as? String) ?: "okhttp/3.10.0"
                            try {
                                val conn = (URL(url).openConnection() as HttpURLConnection).apply {
                                    requestMethod = "POST"
                                    connectTimeout = 5000
                                    readTimeout = 5000
                                    doOutput = true
                                    instanceFollowRedirects = true
                                    setRequestProperty("User-Agent", ua)
                                    if (!headersJson.isNullOrEmpty()) {
                                        val json = JSONObject(headersJson)
                                        for (key in json.keys()) {
                                            setRequestProperty(key, json.optString(key))
                                        }
                                    }
                                }
                                conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
                                if (conn.responseCode in 200..299) {
                                    conn.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
                                } else ""
                            } catch (_: Throwable) {
                                ""
                            }
                        }

                        function("md5") { args ->
                            val text = args.getOrNull(0) as? String ?: return@function ""
                            EApiCrypto.md5(text)
                        }
                    }

                    // 执行规则脚本
                    qjs.evaluate<Any?>(script)

                    // 执行匹配函数 match(id, name, artist, duration)
                    val execCode = """
                        (function() {
                            if (typeof unmMatch === 'function') {
                                var res = unmMatch(${song.id}, ${JSONObject.quote(song.name)}, ${JSONObject.quote(song.artist)}, ${song.durationMs});
                                return JSON.stringify(res || null);
                            }
                            return null;
                        })()
                    """.trimIndent()

                    val jsonResult = qjs.evaluate<String>(execCode)

                    if (!jsonResult.isNullOrBlank() && jsonResult != "null") {
                        val obj = JSONObject(jsonResult)
                        val url = obj.optString("url")
                        if (url.isNotEmpty()) {
                            MatchedAudio(
                                url = url,
                                br = obj.optInt("br", 320000),
                                size = obj.optLong("size", 0L),
                                md5 = obj.optString("md5", ""),
                                source = obj.optString("source", "unm-js"),
                            )
                        } else null
                    } else null
                } finally {
                    qjs.close()
                }
            }
        } catch (t: Throwable) {
            try {
                Log.w(TAG, "QuickJS execution error: ${t.message}")
            } catch (_: Throwable) {
                System.err.println("[$TAG] QuickJS execution error: ${t.message}")
            }
            null
        }
    }
}
