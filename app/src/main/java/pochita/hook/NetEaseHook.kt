package pochita.hook

import android.content.Context
import android.util.Log
import io.github.libxposed.api.XposedInterface
import io.github.libxposed.api.XposedModule
import java.io.ByteArrayInputStream
import java.io.InputStream
import java.lang.reflect.InvocationHandler
import java.lang.reflect.Method
import java.lang.reflect.Proxy
import java.net.HttpURLConnection
import java.util.Collections
import java.util.LinkedHashMap
import java.util.WeakHashMap
import java.util.concurrent.atomic.AtomicBoolean
import org.json.JSONArray
import org.json.JSONObject
import pochita.crypto.EApiCrypto
import pochita.model.SongInfo
import pochita.source.RuleManager
import pochita.source.httpGet

object NetEaseHook {
    private const val TAG = "NetEaseHook"
    private val initialized = AtomicBoolean(false)
    private val songCache = Collections.synchronizedMap(
        object : LinkedHashMap<Long, SongInfo>(500, 0.75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Long, SongInfo>?): Boolean {
                return size > 500
            }
        }
    )
    private val modifiedLengths = Collections.synchronizedMap(WeakHashMap<HttpURLConnection, Int>())

    fun init(module: XposedModule, classLoader: ClassLoader, context: Context) {
        if (!initialized.compareAndSet(false, true)) {
            Log.i(TAG, "NetEaseHook already initialized in this process")
            return
        }

        try {
            var customScript: String? = null
            try {
                val prefs = module.getRemotePreferences("config")
                customScript = prefs.getString("cached_rule_script", null)
            } catch (t: Throwable) {
                Log.w(TAG, "RemotePreferences not available: ${t.message}")
            }
            RuleManager.init(context, customScript)
            Log.i(TAG, "RuleManager initialized")
        } catch (t: Throwable) {
            Log.e(TAG, "Failed to init RuleManager", t)
        }

        // 1. Hook Cronet 网络栈
        hookCronet(module, classLoader)

        // 2. Hook OkHttp 网络栈
        hookOkHttp(module, classLoader)
    }

    private fun hookCronet(module: XposedModule, classLoader: ClassLoader) {
        val cronetClass = try {
            classLoader.loadClass("org.chromium.net.urlconnection.CronetHttpURLConnection")
        } catch (t: Throwable) {
            Log.w(TAG, "CronetHttpURLConnection not found: ${t.message}")
            return
        }

        try {
            val getInputStreamMethod = cronetClass.getDeclaredMethod("getInputStream")
            module.hook(getInputStreamMethod)
                .setPriority(XposedInterface.PRIORITY_DEFAULT)
                .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                .intercept { chain ->
                    val conn = chain.thisObject as? HttpURLConnection
                    val url = conn?.url?.toString() ?: ""
                    Log.d(TAG, "Cronet getInputStream called for: $url")
                    val originalStream = chain.proceed() as? InputStream

                    val isTargetApi = (url.contains("/eapi/") || url.contains("/xeapi/") || url.contains("/api/") || url.contains("player/url")) &&
                            !url.contains(".jpg") && !url.contains(".webp") && !url.contains(".png") && !url.contains(".gif")
                    if (conn != null && originalStream != null && isTargetApi) {
                        Log.i(TAG, "Cronet target API detected: $url")
                        var rawBytes: ByteArray? = null
                        try {
                            rawBytes = originalStream.readBytes()
                            val modifiedBytes = processEApiResponse(url, rawBytes)
                            if (modifiedBytes != null) {
                                Log.i(TAG, "Cronet API successfully modified: $url (${rawBytes.size} -> ${modifiedBytes.size} bytes)")
                                modifiedLengths[conn] = modifiedBytes.size
                                ByteArrayInputStream(modifiedBytes)
                            } else {
                                Log.d(TAG, "Cronet API unchanged: $url (${rawBytes.size} bytes)")
                                ByteArrayInputStream(rawBytes)
                            }
                        } catch (t: Throwable) {
                            Log.e(TAG, "Error processing Cronet stream for $url: ${t.message}", t)
                            if (rawBytes != null && rawBytes.isNotEmpty()) {
                                ByteArrayInputStream(rawBytes)
                            } else {
                                originalStream
                            }
                        }
                    } else {
                        originalStream
                    }
                }
            Log.i(TAG, "Hooked CronetHttpURLConnection.getInputStream() successfully")
        } catch (t: Throwable) {
            Log.e(TAG, "Failed to hook Cronet getInputStream: ${t.message}", t)
        }

        try {
            val getContentLengthMethod = cronetClass.getDeclaredMethod("getContentLength")
            module.hook(getContentLengthMethod)
                .setPriority(XposedInterface.PRIORITY_DEFAULT)
                .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                .intercept { chain ->
                    val conn = chain.thisObject as? HttpURLConnection
                    if (conn != null && modifiedLengths.containsKey(conn)) {
                        modifiedLengths[conn]!!
                    } else {
                        chain.proceed()
                    }
                }
        } catch (_: Throwable) {}

        try {
            val getContentLengthLongMethod = cronetClass.getDeclaredMethod("getContentLengthLong")
            module.hook(getContentLengthLongMethod)
                .setPriority(XposedInterface.PRIORITY_DEFAULT)
                .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                .intercept { chain ->
                    val conn = chain.thisObject as? HttpURLConnection
                    if (conn != null && modifiedLengths.containsKey(conn)) {
                        modifiedLengths[conn]!!.toLong()
                    } else {
                        chain.proceed()
                    }
                }
        } catch (_: Throwable) {}

        try {
            val getHeaderFieldMethod = cronetClass.getDeclaredMethod("getHeaderField", String::class.java)
            module.hook(getHeaderFieldMethod)
                .setPriority(XposedInterface.PRIORITY_DEFAULT)
                .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                .intercept { chain ->
                    val name = chain.args.getOrNull(0) as? String
                    val conn = chain.thisObject as? HttpURLConnection
                    if (conn != null && "Content-Length".equals(name, ignoreCase = true) && modifiedLengths.containsKey(conn)) {
                        modifiedLengths[conn]!!.toString()
                    } else {
                        chain.proceed()
                    }
                }
        } catch (_: Throwable) {}
    }

    private fun hookOkHttp(module: XposedModule, classLoader: ClassLoader) {
        val realCallClass = try {
            classLoader.loadClass("okhttp3.RealCall")
        } catch (t: Throwable) {
            Log.w(TAG, "okhttp3.RealCall not found: ${t.message}")
            return
        }
        val callbackClass = try {
            classLoader.loadClass("okhttp3.Callback")
        } catch (_: Throwable) {
            null
        }

        try {
            val executeMethod = realCallClass.getDeclaredMethod("execute")
            module.hook(executeMethod)
                .setPriority(XposedInterface.PRIORITY_DEFAULT)
                .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                .intercept { chain ->
                    val response = chain.proceed()
                    processOkHttpResponse(response, classLoader) ?: response
                }
            Log.i(TAG, "Hooked RealCall.execute() successfully")
        } catch (t: Throwable) {
            Log.w(TAG, "Failed to hook RealCall.execute: ${t.message}")
        }

        if (callbackClass != null) {
            try {
                val enqueueMethod = realCallClass.getDeclaredMethod("enqueue", callbackClass)
                module.hook(enqueueMethod)
                    .setPriority(XposedInterface.PRIORITY_DEFAULT)
                    .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                    .intercept { chain ->
                        val originalCallback = chain.args.getOrNull(0)
                        if (originalCallback != null) {
                            val proxyCallback = Proxy.newProxyInstance(
                                classLoader,
                                arrayOf(callbackClass),
                                CallbackProxy(originalCallback, classLoader)
                            )
                            chain.args[0] = proxyCallback
                        }
                        chain.proceed()
                    }
                Log.i(TAG, "Hooked RealCall.enqueue() successfully")
            } catch (t: Throwable) {
                Log.w(TAG, "Failed to hook RealCall.enqueue: ${t.message}")
            }
        }
    }

    private class CallbackProxy(
        private val originalCallback: Any,
        private val classLoader: ClassLoader,
    ) : InvocationHandler {
        override fun invoke(proxy: Any?, method: Method, args: Array<out Any>?): Any? {
            if (method.name == "onResponse" && args != null && args.size >= 2) {
                val call = args[0]
                val originalResponse = args[1]
                val modifiedResponse = processOkHttpResponse(originalResponse, classLoader) ?: originalResponse
                return method.invoke(originalCallback, call, modifiedResponse)
            }
            return if (args == null) {
                method.invoke(originalCallback)
            } else {
                method.invoke(originalCallback, *args)
            }
        }
    }

    private fun processOkHttpResponse(responseObj: Any?, classLoader: ClassLoader): Any? {
        if (responseObj == null) return null
        return try {
            val requestMethod = responseObj.javaClass.getMethod("request")
            val request = requestMethod.invoke(responseObj) ?: return responseObj

            val urlMethod = request.javaClass.getMethod("url")
            val url = urlMethod.invoke(request)?.toString() ?: return responseObj

            val isTargetApi = (url.contains("/eapi/") || url.contains("/xeapi/") || url.contains("/api/") || url.contains("player/url")) &&
                    !url.contains(".jpg") && !url.contains(".webp") && !url.contains(".png") && !url.contains(".gif")
            if (!isTargetApi) {
                return responseObj
            }

            val bodyMethod = responseObj.javaClass.getMethod("body")
            val responseBody = bodyMethod.invoke(responseObj) ?: return responseObj

            val bytesMethod = responseBody.javaClass.getMethod("bytes")
            val rawBytes = bytesMethod.invoke(responseBody) as? ByteArray ?: return responseObj

            val modifiedBytes = processEApiResponse(url, rawBytes) ?: return responseObj

            val responseBodyClass = classLoader.loadClass("okhttp3.ResponseBody")
            val contentTypeMethod = responseBody.javaClass.getMethod("contentType")
            val mediaType = contentTypeMethod.invoke(responseBody)

            val newBody = createResponseBody(responseBodyClass, mediaType, modifiedBytes)
                ?: return responseObj

            val newBuilderMethod = responseObj.javaClass.getMethod("newBuilder")
            val builder = newBuilderMethod.invoke(responseObj) ?: return responseObj

            val builderBodyMethod = builder.javaClass.getMethod("body", responseBodyClass)
            builderBodyMethod.invoke(builder, newBody)

            val buildMethod = builder.javaClass.getMethod("build")
            buildMethod.invoke(builder)
        } catch (t: Throwable) {
            Log.e(TAG, "Error in processOkHttpResponse: ${t.message}", t)
            responseObj
        }
    }

    private fun isGzip(bytes: ByteArray): Boolean {
        return bytes.size >= 2 && bytes[0] == 0x1f.toByte() && bytes[1] == 0x8b.toByte()
    }

    private fun decompressGzip(bytes: ByteArray): ByteArray {
        return try {
            java.util.zip.GZIPInputStream(bytes.inputStream()).use { it.readBytes() }
        } catch (t: Throwable) {
            Log.w(TAG, "GZIP decompression failed: ${t.message}")
            bytes
        }
    }

    private fun compressGzip(bytes: ByteArray): ByteArray {
        val baos = java.io.ByteArrayOutputStream()
        java.util.zip.GZIPOutputStream(baos).use { it.write(bytes) }
        return baos.toByteArray()
    }

    /**
     * 处理 EAPI / XEAPI 响应报文：
     * 1. 针对音频播放地址接口 (/player/url) 替换失效音源
     * 2. 针对元数据接口递归修改歌曲权益及可用状态
     * 3. 支持透明 GZIP 解压缩与 AES-128 加解密
     */
    fun processEApiResponse(url: String, rawBytes: ByteArray): ByteArray? {
        if (rawBytes.isEmpty()) return null
        val isPlayerUrl = url.contains("player/url")
        val isApi = url.contains("/eapi/") || url.contains("/xeapi/") || url.contains("/api/")
        if (!isPlayerUrl && !isApi) return null

        val wasGzip = isGzip(rawBytes)
        val payload = if (wasGzip) decompressGzip(rawBytes) else rawBytes
        if (payload.isEmpty()) return null

        val firstChar = payload.firstOrNull { it.toInt() in 33..126 }
        val isEncrypted = firstChar != '{'.code.toByte() && firstChar != '['.code.toByte()

        Log.d(TAG, "processEApiResponse: url=$url, wasGzip=$wasGzip, payloadLen=${payload.size}, isEncrypted=$isEncrypted, firstChar='${firstChar?.toInt()?.toChar()}'")

        val jsonString = if (isEncrypted) {
            try {
                val decrypted = EApiCrypto.decrypt(payload)
                // 检查解密出来的数据是否还是 GZIP 压缩
                val decPayload = if (isGzip(decrypted)) decompressGzip(decrypted) else decrypted
                String(decPayload, Charsets.UTF_8)
            } catch (t: Throwable) {
                val hexSample = payload.take(16).joinToString("") { "%02x".format(it) }
                Log.w(TAG, "Decrypt error for $url: ${t.message}, sample=$hexSample")
                return null
            }
        } else {
            try {
                String(payload, Charsets.UTF_8)
            } catch (t: Throwable) {
                Log.w(TAG, "String error for $url: ${t.message}")
                return null
            }
        }

        val parsedJson = try {
            org.json.JSONTokener(jsonString).nextValue()
        } catch (t: Throwable) {
            Log.w(TAG, "JSONTokener error for $url: ${t.message}, sample=${jsonString.take(64)}")
            return null
        }

        var modified = false

        // 1. 播放地址接口匹配与音源替换
        if (isPlayerUrl && parsedJson is JSONObject) {
            Log.i(TAG, "Processing player url request: $url")
            val dataArray = parsedJson.optJSONArray("data")
            if (dataArray != null) {
                for (i in 0 until dataArray.length()) {
                    val item = dataArray.getJSONObject(i)
                    val songId = item.optLong("id", 0L)
                    val currentUrl = item.optString("url")
                    val hasValidUrl = currentUrl.isNotEmpty() && currentUrl != "null" && currentUrl.startsWith("http")
                    val hasTrial = (item.has("freeTrialInfo") && !item.isNull("freeTrialInfo")) ||
                            (item.optInt("trialMode", 0) != 0)
                    val code = item.optInt("code", 200)

                    // 仅在无有效播放直链、非200状态码或试听限制时替换音源
                    val needUnblock = !hasValidUrl || code != 200 || hasTrial
                    if (needUnblock && songId > 0) {
                        Log.i(TAG, "Found restricted song id=$songId in player url response (hasValidUrl=$hasValidUrl, hasTrial=$hasTrial, code=$code)")
                        val songInfo = getSongInfo(songId)
                        if (songInfo != null) {
                            val matched = RuleManager.matchAudio(songInfo)
                            if (matched != null && matched.url.isNotEmpty()) {
                                item.put("url", matched.url)
                                item.put("br", if (matched.br > 0) matched.br else 320000)
                                item.put("code", 200)
                                item.put("fee", 0)
                                item.put("payed", 1)
                                item.remove("freeTrialInfo")
                                item.put("level", "exhigh")
                                item.put("type", "mp3")
                                if (matched.size > 0) item.put("size", matched.size)
                                if (matched.md5.isNotEmpty()) item.put("md5", matched.md5)
                                modified = true
                                Log.i(TAG, "Unlocked audio for song $songId (${songInfo.name} - ${songInfo.artist}) via ${matched.source}")
                            } else {
                                Log.w(TAG, "No match found for song $songId (${songInfo.name})")
                            }
                        } else {
                            Log.w(TAG, "Could not obtain SongInfo for songId=$songId")
                        }
                    }
                }
            }
        }

        // 2. 递归更新歌曲权益对象并缓存元数据
        if (unblockJsonRecursively(parsedJson)) {
            modified = true
        }

        Log.d(TAG, "unblockJsonRecursively for $url: modified=$modified")
        if (!modified) return null

        val modifiedJsonBytes = parsedJson.toString().toByteArray(Charsets.UTF_8)
        val finalPayload = if (isEncrypted) {
            try {
                EApiCrypto.encrypt(modifiedJsonBytes)
            } catch (t: Throwable) {
                Log.e(TAG, "Failed to re-encrypt EAPI response: ${t.message}", t)
                return null
            }
        } else {
            modifiedJsonBytes
        }

        return if (wasGzip) compressGzip(finalPayload) else finalPayload
    }

    private fun unblockJsonRecursively(any: Any?): Boolean {
        var modified = false
        when (any) {
            is JSONObject -> {
                // A. 权限对象 (privilege)
                if (any.has("id") && (any.has("pl") || any.has("st") || any.has("cp"))) {
                    if (unblockPrivilegeObject(any)) {
                        modified = true
                    }
                }

                // B. 音轨对象 (song)
                if (any.has("id") && any.has("name") && (any.has("ar") || any.has("artists"))) {
                    val songId = any.optLong("id", 0L)
                    val songName = any.optString("name", "")
                    if (songId > 0 && songName.isNotEmpty()) {
                        val arArray = any.optJSONArray("ar") ?: any.optJSONArray("artists")
                        val artistName = arArray?.optJSONObject(0)?.optString("name", "") ?: ""
                        val duration = any.optLong("dt", any.optLong("duration", 0L))
                        songCache.put(songId, SongInfo(songId, songName, artistName, duration))
                    }

                    if (any.has("fee") && any.optInt("fee", 0) > 0) {
                        any.put("fee", 0)
                        modified = true
                    }
                    if (any.has("st") && any.optInt("st", 0) < 0) {
                        any.put("st", 0)
                        modified = true
                    }
                    if (any.has("cp") && any.optInt("cp", 1) == 0) {
                        any.put("cp", 1)
                        modified = true
                    }
                    if (any.has("status") && any.optInt("status", 0) < 0) {
                        any.put("status", 0)
                        modified = true
                    }
                }

                // C. 递归所有子键
                val keys = any.keys()
                while (keys.hasNext()) {
                    val key = keys.next()
                    val child = any.opt(key)
                    if (child is JSONObject || child is JSONArray) {
                        if (unblockJsonRecursively(child)) {
                            modified = true
                        }
                    } else if (child is String && (child.startsWith("{") || child.startsWith("["))) {
                        try {
                            if (child.startsWith("{")) {
                                val nestedObj = JSONObject(child)
                                if (unblockJsonRecursively(nestedObj)) {
                                    any.put(key, nestedObj.toString())
                                    modified = true
                                }
                            } else {
                                val nestedArr = JSONArray(child)
                                if (unblockJsonRecursively(nestedArr)) {
                                    any.put(key, nestedArr.toString())
                                    modified = true
                                }
                            }
                        } catch (_: Throwable) {}
                    }
                }
            }
            is JSONArray -> {
                for (i in 0 until any.length()) {
                    val child = any.opt(i)
                    if (unblockJsonRecursively(child)) {
                        modified = true
                    }
                }
            }
        }
        return modified
    }

    private fun unblockPrivilegeObject(obj: JSONObject): Boolean {
        var changed = false
        val st = obj.optInt("st", 0)
        val cp = obj.optInt("cp", 1)
        val pl = obj.optInt("pl", 320000)
        val fee = obj.optInt("fee", 0)

        if (st < 0 || cp == 0 || pl <= 0 || fee > 0) {
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
            changed = true
        }
        return changed
    }

    private fun createResponseBody(
        responseBodyClass: Class<*>,
        mediaType: Any?,
        contentBytes: ByteArray
    ): Any? {
        return try {
            val methodOkHttp3 = responseBodyClass.methods.firstOrNull {
                it.name == "create" && it.parameterTypes.size == 2 &&
                        it.parameterTypes[1] == ByteArray::class.java
            }
            if (methodOkHttp3 != null) {
                return methodOkHttp3.invoke(null, mediaType, contentBytes)
            }

            val companionField = responseBodyClass.getField("Companion")
            val companionObj = companionField.get(null)
            val companionClass = companionObj.javaClass
            val methodOkHttp4 = companionClass.methods.firstOrNull {
                it.name == "create" && it.parameterTypes.size == 2 &&
                        it.parameterTypes[0] == ByteArray::class.java
            }
            if (methodOkHttp4 != null) {
                return methodOkHttp4.invoke(companionObj, contentBytes, mediaType)
            }
            null
        } catch (_: Throwable) {
            null
        }
    }

    private fun getSongInfo(songId: Long): SongInfo? {
        songCache.get(songId)?.let { return it }

        return try {
            val url = "https://music.163.com/api/song/detail?ids=[$songId]"
            val res = httpGet(url) ?: return null
            val json = JSONObject(res)
            val songs = json.optJSONArray("songs") ?: return null
            if (songs.length() == 0) return null

            val songObj = songs.getJSONObject(0)
            val name = songObj.optString("name", "")
            val duration = songObj.optLong("duration", 0L)
            val artists = songObj.optJSONArray("artists")
            val artistName = if (artists != null && artists.length() > 0) {
                artists.getJSONObject(0).optString("name", "")
            } else ""

            val info = SongInfo(id = songId, name = name, artist = artistName, durationMs = duration)
            songCache.put(songId, info)
            info
        } catch (t: Throwable) {
            Log.w(TAG, "Failed to getSongInfo for $songId: ${t.message}")
            null
        }
    }
}
