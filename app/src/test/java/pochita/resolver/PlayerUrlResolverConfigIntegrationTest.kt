package pochita.resolver

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import pochita.common.UnmLruCache
import pochita.config.ConfigManager
import pochita.config.UnmConfig
import pochita.model.MatchedAudio
import pochita.model.ReplaceEvent
import pochita.model.SongInfo

class PlayerUrlResolverConfigIntegrationTest {

    private lateinit var songCache: UnmLruCache<Long, SongInfo>
    private val playerUrlEndpoint = "https://interface.music.163.com/eapi/song/enhance/player/url"

    @Before
    fun setUp() {
        ConfigManager.resetForTesting()
        songCache = UnmLruCache(100)
    }

    private fun buildRestrictedPayload(songId: Long): String {
        return """
            {
                "code": 200,
                "data": [
                    {
                        "id": $songId,
                        "url": null,
                        "br": 0,
                        "code": -100,
                        "trialMode": 1,
                        "freeTrialInfo": {"start": 0, "end": 30}
                    }
                ]
            }
        """.trimIndent()
    }

    @Test
    fun testResolverPassesEnabledSourcesToMatcher() {
        val songId = 88801L
        songCache.put(songId, SongInfo(songId, "晴天", "周杰伦", 269000L))

        var passedSources: List<String>? = null
        val customSources = listOf("kuwo", "migu")

        val resolver = PlayerUrlResolver(
            songCache = songCache,
            audioMatcher = { _, config ->
                passedSources = config.enabledSources
                MatchedAudio("http://audio.kuwo.cn/qts.mp3", source = "kuwo")
            },
            songDetailFetcher = null,
            configProvider = { UnmConfig(enabledSources = customSources) }
        )

        val result = resolver.processPlayerUrl(playerUrlEndpoint, buildRestrictedPayload(songId))
        assertNotNull(result)
        assertEquals(customSources, passedSources)
    }

    @Test
    fun testResolverAppliesFlacQualityBitrateAndFormat() {
        val songId = 88802L
        songCache.put(songId, SongInfo(songId, "七里香", "周杰伦", 299000L))

        val resolver = PlayerUrlResolver(
            songCache = songCache,
            audioMatcher = { _, _ ->
                MatchedAudio("http://audio.migu.cn/qlx.flac", br = 0, source = "migu")
            },
            songDetailFetcher = null,
            configProvider = { UnmConfig(preferredQuality = "flac") }
        )

        val resultJson = resolver.processPlayerUrl(playerUrlEndpoint, buildRestrictedPayload(songId))
        assertNotNull(resultJson)

        val root = JSONObject(resultJson!!)
        val item = root.getJSONArray("data").getJSONObject(0)

        assertEquals("http://audio.migu.cn/qlx.flac", item.getString("url"))
        assertEquals(999000, item.getInt("br"))
        assertEquals("lossless", item.getString("level"))
        assertEquals("flac", item.getString("type"))
        assertEquals(200, item.getInt("code"))
        assertEquals(0, item.getInt("fee"))
        assertEquals(0, item.getInt("trialMode"))
    }

    @Test
    fun testResolverApplies128kQualityBitrateAndFormat() {
        val songId = 88803L
        songCache.put(songId, SongInfo(songId, "夜曲", "周杰伦", 226000L))

        val resolver = PlayerUrlResolver(
            songCache = songCache,
            audioMatcher = { _, _ ->
                MatchedAudio("http://audio.kugou.com/yq.mp3", br = 0, source = "kugou")
            },
            songDetailFetcher = null,
            configProvider = { UnmConfig(preferredQuality = "128k") }
        )

        val resultJson = resolver.processPlayerUrl(playerUrlEndpoint, buildRestrictedPayload(songId))
        assertNotNull(resultJson)

        val root = JSONObject(resultJson!!)
        val item = root.getJSONArray("data").getJSONObject(0)

        assertEquals("http://audio.kugou.com/yq.mp3", item.getString("url"))
        assertEquals(128000, item.getInt("br"))
        assertEquals("standard", item.getString("level"))
        assertEquals("mp3", item.getString("type"))
    }

    @Test
    fun testResolverApplies192kQualityBitrateAndFormat() {
        val songId = 88804L
        songCache.put(songId, SongInfo(songId, "发如雪", "周杰伦", 300000L))

        val resolver = PlayerUrlResolver(
            songCache = songCache,
            audioMatcher = { _, _ ->
                MatchedAudio("http://audio.bodian.cn/frx.mp3", br = 0, source = "bodian")
            },
            songDetailFetcher = null,
            configProvider = { UnmConfig(preferredQuality = "192k") }
        )

        val resultJson = resolver.processPlayerUrl(playerUrlEndpoint, buildRestrictedPayload(songId))
        assertNotNull(resultJson)

        val root = JSONObject(resultJson!!)
        val item = root.getJSONArray("data").getJSONObject(0)

        assertEquals(192000, item.getInt("br"))
        assertEquals("higher", item.getString("level"))
        assertEquals("mp3", item.getString("type"))
    }

    @Test
    fun testResolverApplies320kDefaultQuality() {
        val songId = 88805L
        songCache.put(songId, SongInfo(songId, "枫", "周杰伦", 275000L))

        val resolver = PlayerUrlResolver(
            songCache = songCache,
            audioMatcher = { _, _ ->
                MatchedAudio("http://audio.kuwo.cn/feng.mp3", br = 320000, source = "kuwo")
            },
            songDetailFetcher = null,
            configProvider = { UnmConfig(preferredQuality = "320k") }
        )

        val resultJson = resolver.processPlayerUrl(playerUrlEndpoint, buildRestrictedPayload(songId))
        assertNotNull(resultJson)

        val root = JSONObject(resultJson!!)
        val item = root.getJSONArray("data").getJSONObject(0)

        assertEquals(320000, item.getInt("br"))
        assertEquals("exhigh", item.getString("level"))
        assertEquals("mp3", item.getString("type"))
    }

    @Test
    fun testResolverDispatchesReplaceEventOnMatch() {
        val songId = 88806L
        songCache.put(songId, SongInfo(songId, "稻香", "周杰伦", 223000L))

        var dispatchedEvent: ReplaceEvent? = null

        val resolver = PlayerUrlResolver(
            songCache = songCache,
            audioMatcher = { _, _ ->
                MatchedAudio(
                    url = "http://audio.kuwo.cn/dx.mp3",
                    br = 320000,
                    source = "kuwo",
                    matchedTitle = "稻香 (原版)",
                    matchedArtist = "周杰伦"
                )
            },
            songDetailFetcher = null,
            configProvider = {
                UnmConfig(enableToast = true, enablePlayerBadge = true)
            },
            onTrackReplaced = { event ->
                dispatchedEvent = event
            }
        )

        val result = resolver.processPlayerUrl(playerUrlEndpoint, buildRestrictedPayload(songId))
        assertNotNull(result)
        assertNotNull(dispatchedEvent)

        assertEquals(songId, dispatchedEvent?.songId)
        assertEquals("稻香", dispatchedEvent?.originalTitle)
        assertEquals("周杰伦", dispatchedEvent?.originalArtist)
        assertEquals("kuwo", dispatchedEvent?.matchedSource)
        assertEquals("稻香 (原版)", dispatchedEvent?.matchedTitle)
        assertEquals("周杰伦", dispatchedEvent?.matchedArtist)
        assertEquals(320000, dispatchedEvent?.br)
    }

    @Test
    fun testResolverSkipsMatchingWhenAllSourcesDisabled() {
        val songId = 88807L
        songCache.put(songId, SongInfo(songId, "青花瓷", "周杰伦", 239000L))

        var matcherInvoked = false

        val resolver = PlayerUrlResolver(
            songCache = songCache,
            audioMatcher = { _, _ ->
                matcherInvoked = true
                MatchedAudio("http://audio.kuwo.cn/qhc.mp3")
            },
            songDetailFetcher = null,
            configProvider = { UnmConfig(enabledSources = emptyList()) }
        )

        val result = resolver.processPlayerUrl(playerUrlEndpoint, buildRestrictedPayload(songId))
        assertNull("When all sources disabled, resolution must return null", result)
        assertEquals("Matcher must not be invoked when sources list is empty", false, matcherInvoked)
    }

    @Test
    fun testResolverSuppressesReplaceEventWhenBothFeedbackDisabled() {
        val songId = 88808L
        songCache.put(songId, SongInfo(songId, "安静", "周杰伦", 334000L))

        var eventDispatched = false

        val resolver = PlayerUrlResolver(
            songCache = songCache,
            audioMatcher = { _, _ ->
                MatchedAudio("http://audio.kuwo.cn/aj.mp3", br = 320000, source = "kuwo")
            },
            songDetailFetcher = null,
            configProvider = {
                UnmConfig(enableToast = false, enablePlayerBadge = false)
            },
            onTrackReplaced = {
                eventDispatched = true
            }
        )

        val result = resolver.processPlayerUrl(playerUrlEndpoint, buildRestrictedPayload(songId))
        assertNotNull("Audio must still be replaced even if feedback is disabled", result)
        assertEquals("Event must not be dispatched when both feedback flags are disabled", false, eventDispatched)
    }
}
