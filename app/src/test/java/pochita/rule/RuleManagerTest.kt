package pochita.rule

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import java.io.File
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import pochita.common.UnmHttpClient
import pochita.model.SongInfo
import pochita.source.AudioValidator
import pochita.source.QuickJsEngine

class RuleManagerTest {

    private lateinit var tempDir: File
    private lateinit var fakePrefs: FakeSharedPreferences
    private lateinit var fakeContext: FakeContext

    @Before
    fun setUp() {
        UnmHttpClient.reset()
        AudioValidator.reset()
        QuickJsEngine.resetForTesting()
        RuleManager.resetForTest()

        tempDir = File(System.getProperty("java.io.tmpdir"), "unm_test_${System.nanoTime()}")
        tempDir.mkdirs()
        fakePrefs = FakeSharedPreferences()
        fakeContext = FakeContext(tempDir, fakePrefs)
    }

    @After
    fun tearDown() {
        RuleManager.resetForTest()
        QuickJsEngine.resetForTesting()
        UnmHttpClient.reset()
        AudioValidator.reset()
        tempDir.deleteRecursively()
    }

    // =========================================================================
    // Tier 1: Multi-Tier Dry-Run Validation Tests (>= 5 cases)
    // =========================================================================

    @Test
    fun testValidDefaultScriptPassesValidation() {
        val result = RuleManager.validateRuleScript(RuleManager.DEFAULT_EMBEDDED_RULE)
        assertTrue(result.isSuccess)
    }

    @Test
    fun testEmptyOrBlankScriptRejected() {
        val emptyRes = RuleManager.validateRuleScript("")
        assertTrue(emptyRes.isFailure)
        assertTrue(emptyRes.exceptionOrNull()?.message?.contains("不能为空") == true)

        val blankRes = RuleManager.validateRuleScript("   \n\t  ")
        assertTrue(blankRes.isFailure)
        assertTrue(blankRes.exceptionOrNull()?.message?.contains("不能为空") == true)
    }

    @Test
    fun testTooShortScriptRejected() {
        val shortRes = RuleManager.validateRuleScript("function unmMatch() {}")
        assertTrue(shortRes.isFailure)
        assertTrue(shortRes.exceptionOrNull()?.message?.contains("过短") == true)
    }

    @Test
    fun testHtmlResponseRejected() {
        val htmlDoc = "<!DOCTYPE html><html><head><title>Error</title></head><body>function unmMatch(){}</body></html>"
        val htmlRes = RuleManager.validateRuleScript(htmlDoc)
        assertTrue(htmlRes.isFailure)
        assertTrue(htmlRes.exceptionOrNull()?.message?.contains("HTML") == true)
    }

    @Test
    fun testMissingUnmMatchRejected() {
        val validJsNoMatch = """
            function parseSong(id, name) {
                return { id: id, name: name };
            }
        """.trimIndent()
        val res = RuleManager.validateRuleScript(validJsNoMatch)
        assertTrue(res.isFailure)
        assertTrue(res.exceptionOrNull()?.message?.contains("unmMatch") == true)
    }

    @Test
    fun testArrowFunctionUnmMatchAccepted() {
        val arrowScript = """
            // Custom modern arrow function rule definition
            const unmMatch = (id, name, artist, duration) => {
                return { url: "http://example.com/audio.mp3", br: 320000 };
            };
        """.trimIndent()
        val res = RuleManager.validateRuleScript(arrowScript)
        assertTrue(res.isSuccess)
    }

    // =========================================================================
    // Tier 2: Boundary, Lexical Scanner & Negative Tests (>= 5 cases)
    // =========================================================================

    @Test
    fun testTruncatedScriptUnclosedBraceRejected() {
        val truncated = """
            function unmMatch(id, name, artist, duration) {
                if (id > 0) {
                    return { url: "http://example.com/audio.mp3" };
        """.trimIndent()
        val res = RuleManager.validateRuleScript(truncated)
        assertTrue(res.isFailure)
        assertTrue(res.exceptionOrNull()?.message?.contains("未闭合") == true)
    }

    @Test
    fun testTruncatedScriptUnclosedStringRejected() {
        val unclosedStr = """
            function unmMatch(id, name, artist, duration) {
                var message = "this string is never closed;
                return null;
            }
        """.trimIndent()
        val res = RuleManager.validateRuleScript(unclosedStr)
        assertTrue(res.isFailure)
        assertTrue(res.exceptionOrNull()?.message?.contains("未闭合的字符串字面量") == true)
    }

    @Test
    fun testMismatchedBracketsRejected() {
        val mismatched = """
            function unmMatch(id, name, artist, duration) {
                var arr = [1, 2, (3 + 4];
                return null;
            }
        """.trimIndent()
        val res = RuleManager.validateRuleScript(mismatched)
        assertTrue(res.isFailure)
        assertTrue(res.exceptionOrNull()?.message?.contains("括号不匹配") == true)
    }

    @Test
    fun testOversizedScriptRejected() {
        val hugeBuilder = StringBuilder("function unmMatch() {\n")
        val chunk = "// " + "A".repeat(1000) + "\n"
        while (hugeBuilder.length <= RuleManager.MAX_SCRIPT_SIZE_BYTES) {
            hugeBuilder.append(chunk)
        }
        hugeBuilder.append("return null;\n}")
        val res = RuleManager.validateRuleScript(hugeBuilder.toString())
        assertTrue(res.isFailure)
        assertTrue(res.exceptionOrNull()?.message?.contains("超过限制") == true)
    }

    // =========================================================================
    // Tier 3: Atomic File Operations
    // =========================================================================

    @Test
    fun testAtomicWriteCreatesFileSuccessfully() {
        val target = File(tempDir, "unm_rule.js")
        val content = "// Rule content\nfunction unmMatch(){}"
        val res = RuleManager.writeAtomic(target, content)
        assertTrue(res.isSuccess)
        assertTrue(target.exists())
        assertEquals(content, target.readText(Charsets.UTF_8))
        val tmpFile = File(tempDir, "unm_rule.js.tmp")
        assertFalse(tmpFile.exists())
    }

    @Test
    fun testAtomicWriteOverwritesExistingSafely() {
        val target = File(tempDir, "unm_rule.js")
        target.writeText("initial old content")

        val newContent = "// updated new content\nfunction unmMatch(){}"
        val res = RuleManager.writeAtomic(target, newContent)
        assertTrue(res.isSuccess)
        assertEquals(newContent, target.readText(Charsets.UTF_8))
    }

    // =========================================================================
    // Tier 4: Remote Rule Updates via UnmHttpClient
    // =========================================================================

    @Test
    fun testUpdateRuleFromUrlSuccess() {
        val validRemoteScript = """
            function unmMatch(id, name, artist, duration) {
                return { url: "http://cdn.example.com/" + id + ".mp3", br: 320000, source: "remote-js" };
            }
        """.trimIndent()

        UnmHttpClient.setMockHandler { request ->
            UnmHttpClient.createMockResponse(request, 200, validRemoteScript)
        }

        val res = RuleManager.updateRuleFromUrl(fakeContext, "https://example.com/rules/unm_rule.js")
        assertTrue(res.isSuccess)
        assertEquals(validRemoteScript.length, res.getOrNull())

        // Verify disk file
        val diskFile = File(tempDir, RuleManager.RULE_FILE_NAME)
        assertTrue(diskFile.exists())
        assertEquals(validRemoteScript, diskFile.readText(Charsets.UTF_8))

        // Verify SharedPreferences
        assertEquals(validRemoteScript, fakePrefs.getString(RuleManager.KEY_CACHED_RULE_SCRIPT, null))
        assertEquals("https://example.com/rules/unm_rule.js", fakePrefs.getString(RuleManager.KEY_RULE_URL, null))

        // Verify active script
        assertEquals(validRemoteScript, RuleManager.getActiveScript())
        assertFalse(RuleManager.isUsingDefault())
    }

    @Test
    fun testUpdateRuleFromUrlHttp404Failure() {
        UnmHttpClient.setMockHandler { request ->
            UnmHttpClient.createMockResponse(request, 404, "Not Found")
        }

        val res = RuleManager.updateRuleFromUrl(fakeContext, "https://example.com/missing.js")
        assertTrue(res.isFailure)
        assertTrue(res.exceptionOrNull()?.message?.contains("404") == true)

        val diskFile = File(tempDir, RuleManager.RULE_FILE_NAME)
        assertFalse(diskFile.exists())
    }

    @Test
    fun testUpdateRuleFromUrlNetworkTimeoutFailure() {
        UnmHttpClient.setMockHandler {
            throw SocketTimeoutException("connect timed out")
        }

        val res = RuleManager.updateRuleFromUrl(fakeContext, "https://example.com/timeout.js")
        assertTrue(res.isFailure)
        assertTrue(res.exceptionOrNull()?.message?.contains("超时") == true)
    }

    @Test
    fun testUpdateRuleFromUrlDnsFailure() {
        UnmHttpClient.setMockHandler {
            throw UnknownHostException("no such domain")
        }

        val res = RuleManager.updateRuleFromUrl(fakeContext, "https://invalid-non-existent-domain.com/rule.js")
        assertTrue(res.isFailure)
        assertTrue(res.exceptionOrNull()?.message?.contains("域名") == true)
    }

    @Test
    fun testUpdateRuleFromUrlMalformedJsRejected() {
        val brokenScript = "function unmMatch(id, name) { var unclosed = (;;;"
        UnmHttpClient.setMockHandler { request ->
            UnmHttpClient.createMockResponse(request, 200, brokenScript)
        }

        val res = RuleManager.updateRuleFromUrl(fakeContext, "https://example.com/broken.js")
        assertTrue(res.isFailure)

        val diskFile = File(tempDir, RuleManager.RULE_FILE_NAME)
        assertFalse(diskFile.exists())
    }

    // =========================================================================
    // Tier 5: Rollback & Reset Mechanisms
    // =========================================================================

    @Test
    fun testExplicitResetToDefault() {
        // First install a custom script
        val customScript = """
            function unmMatch(id, name, artist, duration) {
                return { url: "http://custom.com/audio.mp3", source: "custom" };
            }
        """.trimIndent()
        RuleManager.saveCustomScript(fakeContext, customScript)
        assertEquals(customScript, RuleManager.getActiveScript())
        assertFalse(RuleManager.isUsingDefault())

        // Reset to default
        val resetRes = RuleManager.resetToDefault(fakeContext)
        assertTrue(resetRes.isSuccess)
        assertTrue(RuleManager.isUsingDefault())
        assertEquals(RuleManager.DEFAULT_EMBEDDED_RULE, RuleManager.getActiveScript())

        // Cached script removed from SharedPreferences
        assertNull(fakePrefs.getString(RuleManager.KEY_CACHED_RULE_SCRIPT, null))

        // Disk file contains default rule
        val diskFile = File(tempDir, RuleManager.RULE_FILE_NAME)
        assertEquals(RuleManager.DEFAULT_EMBEDDED_RULE, diskFile.readText(Charsets.UTF_8))
    }

    @Test
    fun testInitWithCorruptedFileRollsBackToDefault() {
        val file = File(tempDir, RuleManager.RULE_FILE_NAME)
        file.writeText("Corrupted broken JS text {{{{")

        RuleManager.init(fakeContext)
        assertTrue(RuleManager.isUsingDefault())
        assertEquals(RuleManager.DEFAULT_EMBEDDED_RULE, RuleManager.getActiveScript())
        assertEquals(RuleManager.DEFAULT_EMBEDDED_RULE, file.readText(Charsets.UTF_8))
    }

    @Test
    fun testInitWithValidCustomScriptFromPreferences() {
        val customScript = """
            function unmMatch(id, name, artist, duration) {
                return { url: "http://remote-pref.com/a.mp3" };
            }
        """.trimIndent()

        RuleManager.init(fakeContext, customScript = customScript)
        assertFalse(RuleManager.isUsingDefault())
        assertEquals(customScript, RuleManager.getActiveScript())
    }

    // =========================================================================
    // Tier 6: Audio Matching & Blacklist Integration
    // =========================================================================

    @Test
    fun testBlacklistedSongIdRejectedBeforeMatching() {
        val badSong = SongInfo(id = 588957081L, name = "下架提示", artist = "未知")
        val match = RuleManager.matchAudio(badSong)
        assertNull(match)
    }

    @Test
    fun testTeaserAudioUrlRejected() {
        UnmHttpClient.setMockHandler { request ->
            val url = request.url.toString()
            if (url.contains("search.kuwo.cn")) {
                UnmHttpClient.createMockResponse(
                    request,
                    200,
                    """{"content":[{},{"musicpage":{"abslist":[{"MUSICRID":"MUSIC_999","DURATION":180}]}}]}"""
                )
            } else if (url.contains("antiserver.kuwo.cn")) {
                // Returns teaser prompt URL containing 563182854
                UnmHttpClient.createMockResponse(
                    request,
                    200,
                    "http://antiserver.kuwo.cn/play/563182854.mp3"
                )
            } else {
                UnmHttpClient.createMockResponse(request, 404, "")
            }
        }

        val song = SongInfo(id = 888L, name = "歌曲", artist = "歌手", durationMs = 180000L)
        val match = RuleManager.matchAudio(song, priority = listOf("kuwo"))
        assertNull(match)
    }

    // =========================================================================
    // Tier 7: DataRepository Contract Preservation
    // =========================================================================

    @Test
    fun testDataRepositoryCompatibility() {
        val customScript = """
            function unmMatch(id, name, artist, duration) {
                return { url: "http://repo-test.com/audio.mp3" };
            }
        """.trimIndent()

        // 1. DataRepository updates rule via RuleManager.updateRuleFromUrl
        UnmHttpClient.setMockHandler { req ->
            UnmHttpClient.createMockResponse(req, 200, customScript)
        }

        val updateRes = RuleManager.updateRuleFromUrl(fakeContext, "https://repo-test.com/rules.js")
        assertTrue(updateRes.isSuccess)
        assertEquals(customScript, fakePrefs.getString("cached_rule_script", null))

        // 2. DataRepository resets rule via RuleManager.resetToDefault
        val resetRes = RuleManager.resetToDefault(fakeContext)
        assertTrue(resetRes.isSuccess)
        assertNull(fakePrefs.getString("cached_rule_script", null))
        assertTrue(RuleManager.isUsingDefault())
    }
}

// =============================================================================
// Test Harness Fakes
// =============================================================================

class FakeContext(
    private val filesDirFile: File,
    private val prefs: SharedPreferences = FakeSharedPreferences()
) : ContextWrapper(null) {
    override fun getFilesDir(): File = filesDirFile
    override fun getApplicationContext(): Context = this
    override fun getSharedPreferences(name: String, mode: Int): SharedPreferences = prefs
}

class FakeSharedPreferences : SharedPreferences {
    private val data = mutableMapOf<String, Any?>()

    override fun getAll(): MutableMap<String, *> = HashMap(data)
    override fun getString(key: String?, defValue: String?): String? = data[key] as? String ?: defValue
    @Suppress("UNCHECKED_CAST")
    override fun getStringSet(key: String?, defValues: MutableSet<String>?): MutableSet<String>? =
        data[key] as? MutableSet<String> ?: defValues
    override fun getInt(key: String?, defValue: Int): Int = data[key] as? Int ?: defValue
    override fun getLong(key: String?, defValue: Long): Long = data[key] as? Long ?: defValue
    override fun getFloat(key: String?, defValue: Float): Float = data[key] as? Float ?: defValue
    override fun getBoolean(key: String?, defValue: Boolean): Boolean = data[key] as? Boolean ?: defValue
    override fun contains(key: String?): Boolean = data.containsKey(key)
    override fun edit(): SharedPreferences.Editor = Editor()
    override fun registerOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener?) {}
    override fun unregisterOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener?) {}

    inner class Editor : SharedPreferences.Editor {
        private val temp = mutableMapOf<String, Any?>()
        private val removeKeys = mutableSetOf<String>()
        private var clearAll = false

        override fun putString(key: String?, value: String?): SharedPreferences.Editor {
            key?.let { temp[it] = value }
            return this
        }
        override fun putStringSet(key: String?, values: MutableSet<String>?): SharedPreferences.Editor {
            key?.let { temp[it] = values }
            return this
        }
        override fun putInt(key: String?, value: Int): SharedPreferences.Editor {
            key?.let { temp[it] = value }
            return this
        }
        override fun putLong(key: String?, value: Long): SharedPreferences.Editor {
            key?.let { temp[it] = value }
            return this
        }
        override fun putFloat(key: String?, value: Float): SharedPreferences.Editor {
            key?.let { temp[it] = value }
            return this
        }
        override fun putBoolean(key: String?, value: Boolean): SharedPreferences.Editor {
            key?.let { temp[it] = value }
            return this
        }
        override fun remove(key: String?): SharedPreferences.Editor {
            key?.let { removeKeys.add(it) }
            return this
        }
        override fun clear(): SharedPreferences.Editor {
            clearAll = true
            return this
        }
        override fun commit(): Boolean {
            apply()
            return true
        }
        override fun apply() {
            if (clearAll) data.clear()
            removeKeys.forEach { data.remove(it) }
            data.putAll(temp)
        }
    }
}
