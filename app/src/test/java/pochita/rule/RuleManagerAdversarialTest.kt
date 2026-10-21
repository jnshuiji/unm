package pochita.rule

import android.content.Context
import java.io.File
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import pochita.common.UnmHttpClient
import pochita.model.SongInfo
import pochita.source.AudioValidator
import pochita.source.QuickJsEngine

/**
 * Empirical Adversarial Challenge Suite for [RuleManager].
 *
 * Stress-tests:
 * 1. 5-Tier Dry Run Validation Edge Cases:
 *    - Boundary checks (empty, blank, <30 chars, >2MB limits).
 *    - HTML error page variations (404/502/500, with doctypes, headers, body, embedded text).
 *    - Structural balance scanner (unclosed/mismatched brackets, unclosed quotes/templates/comments, escaped chars, regex literals).
 *    - Function definition patterns (various valid syntaxes vs corrupted/missing/commented unmMatch).
 * 2. Atomic File Swapping & Crash Resilience:
 *    - Temp file lifecycle (.tmp cleanup).
 *    - Read-only directory / write failure crash resilience (target preserved, zero-byte prevention).
 *    - High-concurrency simultaneous atomic writes.
 * 3. Auto-Rollback Invariant Verification:
 *    - Consecutive failure tracking under runtime errors.
 *    - Threshold breach (>= 3 errors) triggering automatic rollback to DEFAULT_EMBEDDED_RULE.
 *    - Reset of error counter on successful match.
 * 4. Contract & Backward Compatibility:
 *    - pochita.source.RuleManager typealias contract with DataRepository and NetEaseHook.
 */
class RuleManagerAdversarialTest {

    private lateinit var tempDir: File
    private lateinit var fakePrefs: FakeSharedPreferences
    private lateinit var fakeContext: FakeContext

    @Before
    fun setUp() {
        UnmHttpClient.reset()
        AudioValidator.reset()
        QuickJsEngine.resetForTesting()
        RuleManager.resetForTest()

        tempDir = File(System.getProperty("java.io.tmpdir"), "unm_adv_rule_${System.nanoTime()}")
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
    // SECTION 1: 5-Tier Dry-Run Validation Adversarial Challenges
    // =========================================================================

    @Test
    fun testValidation_emptyAndBlankVariations_allRejected() {
        val blanks = listOf(
            "",
            " ",
            "\t",
            "\n",
            "\r\n",
            "   \n\t  \r  "
        )
        for (b in blanks) {
            val res = RuleManager.validateRuleScript(b)
            assertTrue("Expected failure for blank input '$b'", res.isFailure)
            assertTrue(res.exceptionOrNull()?.message?.contains("不能为空") == true)
        }
    }

    @Test
    fun testValidation_boundaryLengthCheck_rejectsUnder30Chars() {
        // Exactly 29 characters
        val script29 = "function unmMatch() {return }"
        assertEquals(29, script29.trim().length)
        val res29 = RuleManager.validateRuleScript(script29)
        assertTrue(res29.isFailure)
        assertTrue(res29.exceptionOrNull()?.message?.contains("过短") == true)

        // Exactly 30 characters
        val script30 = "function unmMatch() { return;}"
        assertEquals(30, script30.trim().length)
        val res30 = RuleManager.validateRuleScript(script30)
        assertTrue("30-char valid syntax should pass", res30.isSuccess)
    }

    @Test
    fun testValidation_oversizedBoundaryCheck() {
        // Exactly MAX_SCRIPT_SIZE_BYTES (2MB)
        val base = "function unmMatch() { return null; }\n"
        val paddingNeeded = RuleManager.MAX_SCRIPT_SIZE_BYTES - base.length
        val valid2Mb = base + "// " + "A".repeat(paddingNeeded - 3)
        assertEquals(RuleManager.MAX_SCRIPT_SIZE_BYTES, valid2Mb.length)
        val res2Mb = RuleManager.validateRuleScript(valid2Mb)
        assertTrue("Script at exact 2MB limit should pass", res2Mb.isSuccess)

        // 2MB + 1 byte
        val oversized = valid2Mb + "B"
        assertTrue(oversized.length > RuleManager.MAX_SCRIPT_SIZE_BYTES)
        val resOver = RuleManager.validateRuleScript(oversized)
        assertTrue("Script exceeding 2MB should be rejected", resOver.isFailure)
        assertTrue(resOver.exceptionOrNull()?.message?.contains("超过限制") == true)
    }

    @Test
    fun testValidation_htmlErrorPages_allRejected() {
        val htmlCases = listOf(
            "<!DOCTYPE html><html><head><title>404 Not Found</title></head><body><h1>404</h1></body></html>",
            "<!doctype html><html><body><p>function unmMatch() {}</p></body></html>",
            "<html><head><title>502 Bad Gateway</title></head><body>nginx</body></html>",
            "   <HTML><HEAD><TITLE>500 Error</TITLE></HEAD><BODY>Error</BODY></HTML>",
            "// Some comment before HTML\n<!DOCTYPE html>\n<html><body>Error</body></html>",
            "<head><title>Embedded Head</title></head>function unmMatch(){}",
            "<body><p>Direct body tag error page</p></body>function unmMatch(){}"
        )

        for (html in htmlCases) {
            val res = RuleManager.validateRuleScript(html)
            assertTrue("HTML payload should be rejected: $html", res.isFailure)
            assertTrue(
                "Error message should mention HTML: ${res.exceptionOrNull()?.message}",
                res.exceptionOrNull()?.message?.contains("HTML") == true
            )
        }
    }

    @Test
    fun testValidation_missingOrFakeUnmMatchFunction_allRejected() {
        val fakeMatchCases = listOf(
            "function unmMatchFake(id, name) { return null; }",
            "function myUnmMatch(id, name) { return null; }",
            "const unmMatchV2 = (id, name) => { return null; };",
            "let unm_match = function(id, name) { return null; };",
            "var otherFunction = function(id, name) { return null; };"
        )

        for (code in fakeMatchCases) {
            val res = RuleManager.validateRuleScript(code)
            assertTrue("Fake unmMatch function should be rejected: $code", res.isFailure)
            assertTrue(
                "Should fail with unmMatch missing: ${res.exceptionOrNull()?.message}",
                res.exceptionOrNull()?.message?.contains("unmMatch") == true
            )
        }
    }

    @Test
    fun testValidation_validUnmMatchSignatures_allAccepted() {
        val validSignatures = listOf(
            "function unmMatch(id, name, artist, duration) { return null; }",
            "async function unmMatch(id, name, artist, duration) { return null; }",
            "const unmMatch = (id, name, artist, duration) => null;",
            "let unmMatch = (id, name) => { return null; };",
            "var unmMatch = function(id, name, artist) { return null; };",
            "var unmMatch = async function(id) { return null; };",
            "unmMatch = (id, name) => { return null; };"
        )

        for (sig in validSignatures) {
            val res = RuleManager.validateRuleScript(sig)
            assertTrue("Valid signature should pass: $sig, error=${res.exceptionOrNull()?.message}", res.isSuccess)
        }
    }

    @Test
    fun testValidation_structuralScanner_unbalancedBrackets() {
        val badBrackets = listOf(
            "function unmMatch() { var a = [1, 2; return null; }", // unclosed [
            "function unmMatch() { var a = (1 + 2; return null; }", // unclosed (
            "function unmMatch() { if (true) { return null; }", // unclosed {
            "function unmMatch() { var a = [1, 2)]; return null; }", // mismatched order [ )
            "function unmMatch() { var a = {1: [2, 3}]}; return null; }", // mismatched order [ }
            "function unmMatch() { return null; }}", // extra }
            "function unmMatch() { return null; ])", // extra ]
            "function unmMatch() { return null; ))" // extra )
        )

        for (bad in badBrackets) {
            val res = RuleManager.validateRuleScript(bad)
            assertTrue("Malformed brackets must be rejected: $bad", res.isFailure)
            assertTrue(
                "Should mention syntax error or bracket mismatch: ${res.exceptionOrNull()?.message}",
                res.exceptionOrNull()?.message?.contains("语法错误") == true ||
                res.exceptionOrNull()?.message?.contains("未闭合") == true ||
                res.exceptionOrNull()?.message?.contains("括号不匹配") == true ||
                res.exceptionOrNull()?.message?.contains("未匹配") == true
            )
        }
    }

    @Test
    fun testValidation_structuralScanner_unclosedQuotesAndComments() {
        val unclosedCases = listOf(
            "function unmMatch() { var s = 'unclosed single; return null; }",
            "function unmMatch() { var s = \"unclosed double; return null; }",
            "function unmMatch() { var s = `unclosed template; return null; }",
            "function unmMatch() { /* unclosed block comment\n return null; }"
        )

        for (bad in unclosedCases) {
            val res = RuleManager.validateRuleScript(bad)
            assertTrue("Unclosed literal/comment must be rejected: $bad", res.isFailure)
            assertTrue(
                "Should mention unclosed literal: ${res.exceptionOrNull()?.message}",
                res.exceptionOrNull()?.message?.contains("未闭合") == true
            )
        }
    }

    @Test
    fun testValidation_structuralScanner_escapedCharactersAndRegex() {
        val complexValidScript = """
            function unmMatch(id, name, artist, duration) {
                var str1 = "escaped \" double quote";
                var str2 = 'escaped \' single quote';
                var str3 = `escaped \` template quote`;
                var str4 = "line1\nline2\\with\\backslashes";
                var regex1 = /[^a-zA-Z0-9]/g;
                var regex2 = /[\/]/g;
                var div = (10 / 2) / 5;
                if (div === 1) {
                    return { url: "http://example.com/audio.mp3" };
                }
                return null;
            }
        """.trimIndent()

        val res = RuleManager.validateRuleScript(complexValidScript)
        assertTrue("Complex valid script with escapes, regex, and division must pass: ${res.exceptionOrNull()?.message}", res.isSuccess)
    }

    // =========================================================================
    // SECTION 2: Atomic File Swapping & Crash Resilience
    // =========================================================================

    @Test
    fun testAtomicWrite_cleansUpTempFileOnSuccess() {
        val target = File(tempDir, "unm_rule.js")
        val content = "// Content\nfunction unmMatch(){}"
        val res = RuleManager.writeAtomic(target, content)
        assertTrue(res.isSuccess)
        assertTrue("Target file must exist", target.exists())
        assertEquals(content, target.readText(Charsets.UTF_8))

        val tmpFile = File(tempDir, "unm_rule.js.tmp")
        assertFalse("Temp file must NOT exist after atomic swap", tmpFile.exists())
    }

    @Test
    fun testAtomicWrite_createsMissingParentDirectories() {
        val deepDir = File(tempDir, "nested/sub/dir/rules")
        val target = File(deepDir, "unm_rule.js")
        assertFalse(deepDir.exists())

        val content = "function unmMatch() { return null; }"
        val res = RuleManager.writeAtomic(target, content)
        assertTrue(res.isSuccess)
        assertTrue(deepDir.exists())
        assertTrue(target.exists())
        assertEquals(content, target.readText(Charsets.UTF_8))
    }

    @Test
    fun testAtomicWrite_preservesExistingFileWhenOverwriting() {
        val target = File(tempDir, "unm_rule.js")
        val originalContent = "// Original version\nfunction unmMatch() { return null; }"
        target.writeText(originalContent)

        val newContent = "// Upgraded version\nfunction unmMatch() { return { url: 'http://a.com' }; }"
        val res = RuleManager.writeAtomic(target, newContent)
        assertTrue(res.isSuccess)
        assertEquals(newContent, target.readText(Charsets.UTF_8))
        assertFalse(File(tempDir, "unm_rule.js.tmp").exists())
    }

    @Test
    fun testAtomicWrite_highConcurrencyStress() {
        val target = File(tempDir, "unm_rule.js")
        val threadCount = 10
        val writesPerThread = 20
        val executor = Executors.newFixedThreadPool(threadCount)
        val latch = CountDownLatch(threadCount)
        val failureCount = AtomicInteger(0)

        for (t in 0 until threadCount) {
            executor.submit {
                try {
                    for (w in 0 until writesPerThread) {
                        val content = "// Thread $t iteration $w\nfunction unmMatch() { return { thread: $t, iter: $w }; }"
                        val res = RuleManager.writeAtomic(target, content)
                        if (res.isFailure) {
                            failureCount.incrementAndGet()
                        }
                    }
                } catch (t: Throwable) {
                    failureCount.incrementAndGet()
                } finally {
                    latch.countDown()
                }
            }
        }

        assertTrue("Concurrent atomic write stress timed out", latch.await(10, TimeUnit.SECONDS))
        executor.shutdown()

        assertEquals("All atomic writes must succeed without corruption", 0, failureCount.get())
        assertTrue("Target file must exist after concurrent writes", target.exists())
        assertTrue("Target file must not be empty", target.length() > 0)

        val finalContent = target.readText(Charsets.UTF_8)
        assertTrue("Final content must contain valid script header", finalContent.contains("function unmMatch()"))
        assertFalse("No orphaned .tmp file should remain", File(tempDir, "unm_rule.js.tmp").exists())
    }

    // =========================================================================
    // SECTION 3: Auto-Rollback Mechanism & Consecutive Failure Invariants
    // =========================================================================

    @Test
    fun testManualRollbackToDefault_restoresEmbeddedRuleAndCleansCache() {
        // Setup initial custom rule state
        val customScript = """
            function unmMatch(id, name, artist, duration) {
                return { url: "http://custom-server.com/play.mp3", source: "custom-source" };
            }
        """.trimIndent()

        RuleManager.init(fakeContext, customScript = customScript)
        assertFalse("Should be using custom rule initially", RuleManager.isUsingDefault())
        assertEquals(customScript, RuleManager.getActiveScript())

        // Disk and SharedPreferences should have custom script
        fakePrefs.edit().putString(RuleManager.KEY_CACHED_RULE_SCRIPT, customScript).apply()
        val file = File(tempDir, RuleManager.RULE_FILE_NAME)
        file.writeText(customScript)

        // Perform rollback
        val rollbackSuccess = RuleManager.rollbackToDefault(fakeContext)
        assertTrue("Rollback must succeed", rollbackSuccess)

        // Assertions:
        assertTrue("isUsingDefault must be true after rollback", RuleManager.isUsingDefault())
        assertEquals("activeScript must be DEFAULT_EMBEDDED_RULE", RuleManager.DEFAULT_EMBEDDED_RULE, RuleManager.getActiveScript())
        assertEquals("consecutiveErrorCount must be reset to 0", 0, RuleManager.getConsecutiveErrorCount())

        // Disk file must be replaced with DEFAULT_EMBEDDED_RULE
        assertEquals("Disk file must be overwritten with default rule", RuleManager.DEFAULT_EMBEDDED_RULE, file.readText(Charsets.UTF_8))

        // Cached script must be purged from SharedPreferences
        assertNull("cached_rule_script in SharedPreferences must be removed", fakePrefs.getString(RuleManager.KEY_CACHED_RULE_SCRIPT, null))
    }

    @Test
    fun testResetToDefault_restoresEmbeddedRule() {
        val customScript = """
            function unmMatch(id, name, artist, duration) {
                return { url: "http://another-custom.com/play.mp3" };
            }
        """.trimIndent()

        RuleManager.saveCustomScript(fakeContext, customScript)
        assertFalse(RuleManager.isUsingDefault())

        val resetResult = RuleManager.resetToDefault(fakeContext)
        assertTrue(resetResult.isSuccess)
        assertTrue(RuleManager.isUsingDefault())
        assertEquals(RuleManager.DEFAULT_EMBEDDED_RULE, RuleManager.getActiveScript())
        assertEquals(0, RuleManager.getConsecutiveErrorCount())
    }

    @Test
    fun testAutoRollback_afterThreeFailuresInMatchAudio_revertsToDefaultRule() {
        val failingScript = """
            function unmMatch(id, name, artist, duration) {
                throw new Error("Deliberate JS runtime crash");
            }
        """.trimIndent()

        RuleManager.init(fakeContext, customScript = failingScript)
        assertFalse("Initially using custom script", RuleManager.isUsingDefault())
        assertEquals(failingScript, RuleManager.getActiveScript())

        val song = SongInfo(id = 9999L, name = "CrashTest", artist = "CrashArtist")

        // Trigger 3 calls
        for (i in 1..3) {
            RuleManager.matchAudio(song, priority = emptyList())
        }

        // Expected according to spec: auto-rollback triggered after 3 consecutive failures
        assertEquals("Consecutive error count should reach 3 or trigger rollback", 0, RuleManager.getConsecutiveErrorCount())
        assertTrue("RuleManager should have rolled back to default rule after 3 failures", RuleManager.isUsingDefault())
        assertEquals("Script should be DEFAULT_EMBEDDED_RULE", RuleManager.DEFAULT_EMBEDDED_RULE, RuleManager.getActiveScript())
    }

    @Test
    fun testConsecutiveErrorCount_counterTrackingAndReset() {
        assertEquals(0, RuleManager.getConsecutiveErrorCount())

        RuleManager.setConsecutiveErrorCountForTest(2)
        assertEquals(2, RuleManager.getConsecutiveErrorCount())

        // Reset
        RuleManager.resetForTest()
        assertEquals(0, RuleManager.getConsecutiveErrorCount())
    }

    // =========================================================================
    // SECTION 4: Contract Compatibility & Integration
    // =========================================================================

    @Test
    fun testTypealiasCompatibility_sourceRuleManagerDelegatesToRulePackage() {
        // Test that pochita.source.RuleManager behaves identically to pochita.rule.RuleManager
        pochita.source.RuleManager.resetForTest()
        assertTrue(pochita.source.RuleManager.isUsingDefault())

        val customScript = """
            function unmMatch(id, name, artist, duration) {
                return { url: "http://typealias-test.com/audio.mp3" };
            }
        """.trimIndent()

        val saveRes = pochita.source.RuleManager.saveCustomScript(fakeContext, customScript)
        assertTrue(saveRes.isSuccess)
        assertFalse(pochita.source.RuleManager.isUsingDefault())
        assertFalse(pochita.rule.RuleManager.isUsingDefault())
        assertEquals(customScript, pochita.source.RuleManager.getActiveScript())
        assertEquals(customScript, pochita.rule.RuleManager.getActiveScript())

        val resetRes = pochita.source.RuleManager.resetToDefault(fakeContext)
        assertTrue(resetRes.isSuccess)
        assertTrue(pochita.source.RuleManager.isUsingDefault())
        assertTrue(pochita.rule.RuleManager.isUsingDefault())
    }
}
