package pochita.source

import com.dokar.quickjs.QuickJs
import com.dokar.quickjs.QuickJsException
import com.dokar.quickjs.QuickJsInterruptedException
import com.dokar.quickjs.binding.define
import com.dokar.quickjs.binding.function
import java.io.Closeable
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.json.JSONObject
import pochita.codec.EApiCrypto
import pochita.common.UnmHttpClient
import pochita.common.UnmLogger
import pochita.model.MatchedAudio
import pochita.model.SongInfo

/**
 * High-performance, persistent embedded QuickJS execution engine for NetEase UNM rules.
 *
 * Key Architectural Highlights:
 * 1. Persistent Instance: Reuses a warm QuickJS runtime across matches, eliminating
 *    expensive per-match context creation and teardown.
 * 2. Dedicated Single-Thread Worker: Enforces strict single-threaded access to QuickJS C
 *    native structures via [workerDispatcher], ensuring 100% thread safety across concurrent calls.
 * 3. Unified Native Bridge: Registers native HTTP (`httpGet`, `httpPost`) and cryptographic (`md5`)
 *    bindings ONCE, routing all network I/O through [UnmHttpClient].
 * 4. Bytecode Compilation: Compiles scripts into QuickJS bytecode on load, bypassing source parsing
 *    during matching.
 * 5. Host Safety Ceilings: Imposes 16MB memory limit, 512KB stack limit, and 5000ms evaluation timeout.
 * 6. Graceful JVM Fallback: Safe detection and silent suppression of [LinkageError] on unsupported ABIs/JVMs.
 */
object QuickJsEngine : Closeable {
    private const val TAG = "QuickJsEngine"

    private const val DEFAULT_MEMORY_LIMIT_BYTES = 16 * 1024 * 1024L // 16 MB
    private const val DEFAULT_MAX_STACK_BYTES = 512 * 1024L          // 512 KB
    private const val DEFAULT_EVAL_TIMEOUT_MS = 5000L               // 5 seconds
    private const val DEFAULT_HTTP_TIMEOUT_MS = 8000L               // 8 seconds

    private val workerExecutor: ExecutorService = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "QuickJsWorker").apply {
            isDaemon = true
            priority = Thread.NORM_PRIORITY
        }
    }

    private val workerDispatcher: CoroutineDispatcher = workerExecutor.asCoroutineDispatcher()

    @Volatile
    private var activeScript: String? = null

    @Volatile
    private var compiledBytecode: ByteArray? = null

    private var persistentQuickJs: QuickJs? = null

    @Volatile
    private var isInitialized: Boolean = false

    private val isNativeSupported: Boolean = try {
        val qjs = QuickJs.create(jobDispatcher = workerDispatcher)
        qjs.close()
        true
    } catch (e: LinkageError) {
        UnmLogger.w(TAG, "QuickJS native library unavailable: ${e.message}. Safe fallback active.")
        false
    } catch (t: Throwable) {
        UnmLogger.w(TAG, "QuickJS native library initialization failed: ${t.message}. Safe fallback active.")
        false
    }

    @Volatile
    private var isNativeAvailable: Boolean = isNativeSupported

    /**
     * Returns true if a valid script has been supplied.
     */
    fun hasScript(): Boolean = !activeScript.isNullOrBlank()

    /**
     * Returns true if the engine is initialized and ready to execute matches.
     */
    fun isReady(): Boolean = isInitialized && persistentQuickJs != null && isNativeAvailable

    /**
     * Returns true if native QuickJS library is unavailable in the current environment.
     */
    fun isNativeUnavailable(): Boolean = !isNativeAvailable

    /**
     * Loads, compiles, and activates a JavaScript rule script into the persistent QuickJS context.
     *
     * @param scriptContent Raw JavaScript source code containing the `unmMatch` function.
     * @return true if compilation and initialization succeeded, false otherwise.
     */
    fun loadScript(scriptContent: String): Boolean {
        if (scriptContent.isBlank()) {
            UnmLogger.w(TAG, "Cannot load empty or blank script")
            return false
        }

        activeScript = scriptContent

        if (!isNativeAvailable) {
            UnmLogger.w(TAG, "QuickJS native library unavailable; recorded script without native runtime")
            return false
        }

        return try {
            runBlocking(workerDispatcher) {
                loadScriptInternal(scriptContent)
            }
        } catch (t: Throwable) {
            UnmLogger.e(TAG, "Unexpected error in loadScript: ${t.message}", t)
            false
        }
    }

    /**
     * Validates a script's syntax by attempting a dry-run compile without replacing active state.
     */
    fun validateScript(scriptContent: String): Boolean {
        if (scriptContent.isBlank()) return false
        if (!isNativeAvailable) {
            return scriptContent.contains("unmMatch")
        }

        return try {
            runBlocking(workerDispatcher) {
                val testQjs = try {
                    QuickJs.create(jobDispatcher = workerDispatcher)
                } catch (e: LinkageError) {
                    isNativeAvailable = false
                    return@runBlocking scriptContent.contains("unmMatch")
                }
                try {
                    testQjs.compile(code = scriptContent, filename = "dry_run.js")
                    true
                } finally {
                    testQjs.close()
                }
            }
        } catch (t: Throwable) {
            UnmLogger.w(TAG, "Dry-run script validation failed: ${t.message}")
            false
        }
    }

    /**
     * Synchronously matches an audio source for [song] using the active JavaScript rule.
     *
     * @param song Song metadata to match
     * @return [MatchedAudio] if matched successfully, or null if unhit, error, or native unavailable.
     */
    fun match(song: SongInfo): MatchedAudio? {
        if (!isInitialized || persistentQuickJs == null || !isNativeAvailable) {
            return null
        }

        return try {
            runBlocking(workerDispatcher) {
                matchInternal(song)
            }
        } catch (t: Throwable) {
            UnmLogger.w(TAG, "QuickJS match execution error: ${t.message}", t)
            null
        }
    }

    /**
     * Asynchronous suspend variant of [match] for non-blocking coroutine callers.
     */
    suspend fun matchAsync(song: SongInfo): MatchedAudio? {
        if (!isInitialized || persistentQuickJs == null || !isNativeAvailable) {
            return null
        }

        return withContext(workerDispatcher) {
            matchInternal(song)
        }
    }

    /**
     * Closes the active QuickJS runtime and releases all native C structures.
     */
    override fun close() {
        runBlocking(workerDispatcher) {
            closeInternal()
        }
    }

    /**
     * Resets all internal states, scripts, and bytecode. Primarily used in unit tests.
     */
    fun resetForTesting() {
        runBlocking(workerDispatcher) {
            closeInternal()
            activeScript = null
            compiledBytecode = null
            isNativeAvailable = isNativeSupported
        }
    }

    fun setNativeAvailableForTesting(available: Boolean) {
        isNativeAvailable = available
    }

    /**
     * Shuts down the dedicated background worker thread executor.
     */
    fun shutdown() {
        close()
        workerExecutor.shutdown()
    }

    // =========================================================================
    // Internal Lifecycle Management (Runs exclusively on workerDispatcher)
    // =========================================================================

    private suspend fun loadScriptInternal(scriptContent: String): Boolean {
        closeInternal()

        val qjs = try {
            QuickJs.create(jobDispatcher = workerDispatcher)
        } catch (e: LinkageError) {
            isNativeAvailable = false
            UnmLogger.w(TAG, "QuickJS native library unavailable: ${e.message}. Safe fallback active.")
            return false
        } catch (t: Throwable) {
            UnmLogger.e(TAG, "Failed to instantiate QuickJS runtime: ${t.message}", t)
            return false
        }

        return try {
            qjs.memoryLimit = DEFAULT_MEMORY_LIMIT_BYTES
            qjs.maxStackSize = DEFAULT_MAX_STACK_BYTES
            qjs.evaluationTimeoutMillis = DEFAULT_EVAL_TIMEOUT_MS

            registerNativeBridge(qjs)

            // Attempt bytecode compilation for performance; fallback to source eval
            val bytecode = try {
                qjs.compile(code = scriptContent, filename = "unm_rule.js")
            } catch (t: Throwable) {
                UnmLogger.w(TAG, "Bytecode compilation failed, falling back to direct eval: ${t.message}")
                null
            }

            if (bytecode != null) {
                qjs.evaluate<Any?>(bytecode)
                compiledBytecode = bytecode
            } else {
                qjs.evaluate<Any?>(scriptContent)
            }

            persistentQuickJs = qjs
            isInitialized = true
            isNativeAvailable = true
            UnmLogger.i(TAG, "QuickJS engine initialized (script=${scriptContent.length} chars, bytecode=${bytecode != null})")
            true
        } catch (t: Throwable) {
            UnmLogger.e(TAG, "Failed to evaluate script in QuickJS: ${t.message}", t)
            try { qjs.close() } catch (_: Throwable) {}
            persistentQuickJs = null
            isInitialized = false
            false
        }
    }

    private suspend fun matchInternal(song: SongInfo): MatchedAudio? {
        val qjs = persistentQuickJs ?: return null
        if (qjs.isClosed) return null

        return try {
            val songJson = JSONObject().apply {
                put("id", song.id)
                put("name", song.name)
                put("artist", song.artist)
                put("duration", song.durationMs)
            }.toString()

            val execCode = """
                (function() {
                    if (typeof unmMatch !== 'function') return null;
                    var s = $songJson;
                    var res = unmMatch.length === 1 ? unmMatch(s) : unmMatch(s.id, s.name, s.artist, s.duration);
                    return res ? JSON.stringify(res) : null;
                })()
            """.trimIndent()

            val evalResult = qjs.evaluate<Any?>(execCode)
            val jsonResult = evalResult as? String
            if (jsonResult.isNullOrBlank() || jsonResult == "null") {
                return null
            }

            parseMatchedAudio(jsonResult)
        } catch (t: Throwable) {
            handleExecutionError(t)
            null
        }
    }

    private suspend fun handleExecutionError(t: Throwable) {
        UnmLogger.w(TAG, "QuickJS execution error: ${t.message}", t)
        val qjs = persistentQuickJs
        if (qjs != null && (qjs.isClosed || t is QuickJsInterruptedException)) {
            UnmLogger.w(TAG, "Context interrupted or closed; auto-recovering engine state...")
            val script = activeScript
            closeInternal()
            if (!script.isNullOrBlank()) {
                loadScriptInternal(script)
            }
        }
    }

    private fun closeInternal() {
        try {
            persistentQuickJs?.close()
        } catch (t: Throwable) {
            UnmLogger.w(TAG, "Error closing QuickJS instance: ${t.message}", t)
        } finally {
            persistentQuickJs = null
            compiledBytecode = null
            isInitialized = false
        }
    }

    private fun parseMatchedAudio(jsonResult: String): MatchedAudio? {
        return try {
            val obj = JSONObject(jsonResult)
            val url = obj.optString("url")
            if (!AudioValidator.isValidAudioUrl(url)) return null

            MatchedAudio(
                url = url,
                br = obj.optInt("br", 320000),
                size = obj.optLong("size", 0L),
                md5 = obj.optString("md5", ""),
                source = obj.optString("source", "unm-js")
            )
        } catch (t: Throwable) {
            UnmLogger.w(TAG, "Failed to parse matched audio JSON: $jsonResult", t)
            null
        }
    }

    // =========================================================================
    // Native Bridge Implementation (Accessible for direct unit testing)
    // =========================================================================

    internal fun registerNativeBridge(qjs: QuickJs) {
        qjs.define("nativeBridge") {
            function("httpGet") { args ->
                bridgeHttpGet(args)
            }
            function("httpPost") { args ->
                bridgeHttpPost(args)
            }
            function("md5") { args ->
                bridgeMd5(args)
            }
        }
    }

    internal fun bridgeHttpGet(args: Array<Any?>): String {
        val url = args.getOrNull(0) as? String
        if (url.isNullOrBlank()) return ""

        val ua = args.getOrNull(1) as? String
        val headersJson = args.getOrNull(2) as? String

        return try {
            val headers = mutableMapOf<String, String>()
            if (!ua.isNullOrBlank()) {
                headers["User-Agent"] = ua
            }
            if (!headersJson.isNullOrBlank()) {
                parseHeadersJson(headersJson, headers)
            }

            val resp = UnmHttpClient.get(url, headers = headers, timeoutMs = DEFAULT_HTTP_TIMEOUT_MS)
            if (resp.isSuccessful) resp.bodyString else ""
        } catch (t: Throwable) {
            UnmLogger.w(TAG, "nativeBridge.httpGet error for $url: ${t.message}", t)
            ""
        }
    }

    internal fun bridgeHttpPost(args: Array<Any?>): String {
        val url = args.getOrNull(0) as? String
        if (url.isNullOrBlank()) return ""

        val body = (args.getOrNull(1) as? String) ?: ""
        val headersJson = args.getOrNull(2) as? String
        val ua = args.getOrNull(3) as? String

        return try {
            val headers = mutableMapOf<String, String>()
            if (!ua.isNullOrBlank()) {
                headers["User-Agent"] = ua
            }
            if (!headersJson.isNullOrBlank()) {
                parseHeadersJson(headersJson, headers)
            }

            val contentType = headers.entries.firstOrNull {
                it.key.equals("Content-Type", ignoreCase = true)
            }?.value ?: "application/json; charset=utf-8"

            val resp = UnmHttpClient.post(
                url = url,
                body = body,
                contentType = contentType,
                headers = headers,
                timeoutMs = DEFAULT_HTTP_TIMEOUT_MS
            )
            if (resp.isSuccessful) resp.bodyString else ""
        } catch (t: Throwable) {
            UnmLogger.w(TAG, "nativeBridge.httpPost error for $url: ${t.message}", t)
            ""
        }
    }

    internal fun bridgeMd5(args: Array<Any?>): String {
        val text = args.getOrNull(0) as? String ?: return ""
        return try {
            EApiCrypto.md5(text)
        } catch (t: Throwable) {
            UnmLogger.w(TAG, "nativeBridge.md5 error: ${t.message}", t)
            ""
        }
    }

    private fun parseHeadersJson(jsonStr: String, destination: MutableMap<String, String>) {
        try {
            val json = JSONObject(jsonStr)
            val keys = json.keys()
            while (keys.hasNext()) {
                val key = keys.next()
                val value = json.optString(key)
                if (value.isNotEmpty()) {
                    destination[key] = value
                }
            }
        } catch (t: Throwable) {
            UnmLogger.w(TAG, "Failed to parse headers JSON in bridge: $jsonStr", t)
        }
    }
}
