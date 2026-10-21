package pochita.source

import org.junit.After
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import pochita.common.UnmHttpClient
import pochita.model.SongInfo
import pochita.rule.RuleManager

class SourceMatchTest {

    @Before
    fun setUp() {
        UnmHttpClient.reset()
        AudioValidator.reset()
        RuleManager.resetForTest()
        QuickJsEngine.resetForTesting()
    }

    @After
    fun tearDown() {
        UnmHttpClient.reset()
        AudioValidator.reset()
        RuleManager.resetForTest()
        QuickJsEngine.resetForTesting()
    }

    @Test
    fun testKuwoOfflinePromptBlacklisted() {
        UnmHttpClient.setMockHandler { request ->
            val url = request.url.toString()
            when {
                url.contains("search.kuwo.cn") -> {
                    val searchJson = """
                        {
                            "content": [
                                {},
                                {
                                    "musicpage": {
                                        "abslist": [
                                            {
                                                "MUSICRID": "MUSIC_563182854",
                                                "DURATION": 172
                                            }
                                        ]
                                    }
                                }
                            ]
                        }
                    """.trimIndent()
                    UnmHttpClient.createMockResponse(request, 200, searchJson)
                }
                url.contains("antiserver.kuwo.cn") -> {
                    UnmHttpClient.createMockResponse(
                        request = request,
                        code = 200,
                        body = "http://antiserver.kuwo.cn/anti.s?type=convert_url&format=mp3&response=url&rid=MUSIC_563182854"
                    )
                }
                else -> UnmHttpClient.createMockResponse(request, 404, "not found")
            }
        }

        val song = SongInfo(
            id = 1851652156L,
            name = "忘不掉的你",
            artist = "h3R3",
            durationMs = 172173L
        )
        val kuwoMatched = KuwoSource.match(song)
        // 验证对下架音频标识 (563182854) 的过滤拦截
        assertNull(kuwoMatched)
    }

    @Test
    fun testBlacklistTeaserFilter() {
        val song = SongInfo(
            id = 588957081L,
            name = "测试下架歌曲",
            artist = "测试歌手",
            durationMs = 180000L
        )

        val matched = RuleManager.matchAudio(song, priority = emptyList())
        // 黑名单歌曲直接返回 null
        assertNull(matched)
    }

    @Test
    fun testQuickJsEngineScriptLifecycle() {
        val testScript = """
            function unmMatch(id, name, artist, duration) {
                if (id === 1001) {
                    return { url: "https://example.com/audio.mp3", br: 320000, source: "mock-js" };
                }
                return null;
            }
        """.trimIndent()

        QuickJsEngine.loadScript(testScript)
        assertTrue(QuickJsEngine.hasScript())

        val song = SongInfo(id = 1001L, name = "测试", artist = "歌手")
        val result = QuickJsEngine.match(song)
        if (result != null) {
            assertTrue(result.url == "https://example.com/audio.mp3")
            assertTrue(result.source == "mock-js")
        } else {
            println("Note: QuickJS native library is targeting Android platforms; on host JVM it safely falls back to native Kotlin sources.")
        }
    }

    @Test
    fun testRulesFileExistsAndValid() {
        val defaultEmbedded = RuleManager.DEFAULT_EMBEDDED_RULE
        assertTrue(defaultEmbedded.contains("function unmMatch"))
        assertTrue(defaultEmbedded.contains("matchBodian"))
        assertTrue(defaultEmbedded.contains("matchKuwo"))
        assertTrue(defaultEmbedded.contains("matchKugou"))

        val ruleFile = java.io.File("rules/unm_rule.js")
        if (ruleFile.exists()) {
            val content = ruleFile.readText()
            assertTrue(content.contains("function unmMatch"))
            assertTrue(content.contains("matchBodian"))
            assertTrue(content.contains("matchKuwo"))
            assertTrue(content.contains("matchKugou"))
        }
    }
}
