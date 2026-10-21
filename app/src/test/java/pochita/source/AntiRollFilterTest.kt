package pochita.source

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import pochita.config.ConfigManager
import pochita.model.SongInfo

/**
 * Unit tests for [AntiRollFilter].
 * Verifies algorithmic correctness for:
 * 1. Keyword penalties on studio tracks (Live, Radio, Cover, DJ/Remix, Accompaniment, Teaser/Clip).
 * 2. Keyword preservation and bonus when original song title explicitly requests that version.
 * 3. Latin word boundary defense preventing false positives (e.g. "Alive", "Radioactive").
 * 4. Multi-tier duration fitting and teaser protection.
 * 5. Configuration switch [ConfigManager.getConfig().enableAntiRoll] behavior.
 * 6. Candidate ranking and best candidate selection helpers.
 * 7. Edge cases with CJK brackets, empty metadata, and multi-artist delimiters.
 */
class AntiRollFilterTest {

    @Before
    fun setUp() {
        ConfigManager.resetForTesting()
    }

    // =========================================================================
    // 1. Keyword Penalties: Original is Studio -> Penalize Non-Studio Candidates
    // =========================================================================

    @Test
    fun testLiveKeywordPenalizedWhenOriginalIsStudio() {
        val originalTitle = "晴天"
        val originalArtist = "周杰伦"
        val duration = 269_000L

        val studioScore = AntiRollFilter.scoreCandidate(
            originalTitle, originalArtist, duration,
            candidateTitle = "晴天", candidateArtist = "周杰伦", candidateDurationMs = 269_000L
        )
        val liveScore = AntiRollFilter.scoreCandidate(
            originalTitle, originalArtist, duration,
            candidateTitle = "晴天 (Live现场版)", candidateArtist = "周杰伦", candidateDurationMs = 285_000L
        )

        assertTrue("Studio version score must be >= 100", studioScore >= 100)
        assertTrue("Live version score must be penalized below clean threshold", liveScore < AntiRollFilter.THRESHOLD_CLEAN)
        assertTrue("Studio version must outscore Live version by >= 100 points", studioScore - liveScore >= 100)
    }

    @Test
    fun testRadioAndBroadcastKeywordSeverelyPenalized() {
        val score = AntiRollFilter.scoreCandidate(
            originalTitle = "七里香",
            originalArtist = "周杰伦",
            originalDurationMs = 300_000L,
            candidateTitle = "七里香 (电台FM深夜播放)",
            candidateArtist = "周杰伦",
            candidateDurationMs = 300_000L
        )
        assertTrue("Radio versions must be heavily penalized below clean threshold", score < AntiRollFilter.THRESHOLD_CLEAN)
    }

    @Test
    fun testCoverKeywordPenalized() {
        val score = AntiRollFilter.scoreCandidate(
            originalTitle = "起风了",
            originalArtist = "买辣椒也用券",
            originalDurationMs = 325_000L,
            candidateTitle = "起风了 (翻唱Cover)",
            candidateArtist = "网络歌手",
            candidateDurationMs = 325_000L
        )
        assertTrue("Cover songs must be heavily penalized below clean threshold", score < AntiRollFilter.THRESHOLD_CLEAN)
    }

    @Test
    fun testDjAndRemixKeywordPenalized() {
        val score = AntiRollFilter.scoreCandidate(
            originalTitle = "夜曲",
            originalArtist = "周杰伦",
            originalDurationMs = 220_000L,
            candidateTitle = "夜曲 (DJ慢摇加快重低音版)",
            candidateArtist = "周杰伦",
            candidateDurationMs = 220_000L
        )
        assertTrue("DJ Remix must be penalized below clean threshold", score < AntiRollFilter.THRESHOLD_CLEAN)
    }

    @Test
    fun testAccompanimentKeywordPenalized() {
        val score = AntiRollFilter.scoreCandidate(
            originalTitle = "青花瓷",
            originalArtist = "周杰伦",
            originalDurationMs = 240_000L,
            candidateTitle = "青花瓷 (纯伴奏/消音伴唱)",
            candidateArtist = "周杰伦",
            candidateDurationMs = 240_000L
        )
        assertTrue("Accompaniment tracks must be heavily penalized below clean threshold", score < AntiRollFilter.THRESHOLD_CLEAN)
    }

    @Test
    fun testClipsAndTeaserKeywordsDisqualified() {
        val clipTitles = listOf(
            "晴天 (副歌伴奏片段)",
            "海阔天空 (高潮铃声剪辑)",
            "稻香 (快手热播卡点试听)"
        )
        for (candTitle in clipTitles) {
            val score = AntiRollFilter.scoreCandidate(
                originalTitle = "晴天",
                originalArtist = "周杰伦",
                originalDurationMs = 269_000L,
                candidateTitle = candTitle,
                candidateArtist = "周杰伦",
                candidateDurationMs = 30_000L
            )
            assertTrue("Clips/Ringtones must receive severe disqualification score (<= -300)", score <= -300)
        }
    }

    // =========================================================================
    // 2. Keyword Preservation: Original is Explicitly Live/Remix -> Do NOT Penalize
    // =========================================================================

    @Test
    fun testOriginalLivePreservedAndRewarded() {
        val originalTitle = "晴天 (Live)"
        val originalArtist = "周杰伦"
        val duration = 285_000L

        val liveCandidateScore = AntiRollFilter.scoreCandidate(
            originalTitle = originalTitle,
            originalArtist = originalArtist,
            originalDurationMs = duration,
            candidateTitle = "晴天 (Live现场版)",
            candidateArtist = "周杰伦",
            candidateDurationMs = 285_000L
        )

        assertTrue(
            "When original is Live, Live candidate must not be penalized and must pass clean threshold",
            liveCandidateScore >= AntiRollFilter.THRESHOLD_CLEAN
        )
        assertTrue("Live candidate should receive studio-tier score when requested", liveCandidateScore >= 150)
    }

    @Test
    fun testOriginalRemixPreservedAndRewarded() {
        val originalTitle = "Bad Guy (Remix)"
        val originalArtist = "Billie Eilish"
        val duration = 194_000L

        val remixCandidateScore = AntiRollFilter.scoreCandidate(
            originalTitle = originalTitle,
            originalArtist = originalArtist,
            originalDurationMs = duration,
            candidateTitle = "Bad Guy (Justin Bieber Remix)",
            candidateArtist = "Billie Eilish",
            candidateDurationMs = 194_000L
        )

        assertTrue("When original is Remix, Remix candidate must pass clean threshold", remixCandidateScore >= AntiRollFilter.THRESHOLD_CLEAN)
    }

    // =========================================================================
    // 3. Latin Word Boundary Defense (False Positive Prevention)
    // =========================================================================

    @Test
    fun testLatinWordBoundariesPreventFalsePositives() {
        // "Alive" contains "live" as substring, but word boundary \bLive\b must NOT match
        val aliveScore = AntiRollFilter.scoreCandidate(
            originalTitle = "I'm Alive",
            originalArtist = "Celine Dion",
            originalDurationMs = 210_000L,
            candidateTitle = "I'm Alive",
            candidateArtist = "Celine Dion",
            candidateDurationMs = 210_000L
        )
        assertTrue("Song with 'Alive' in title must not be penalized as Live version", aliveScore >= 150)

        // "Radioactive" contains "radio", must NOT match \bRadio\b
        val radioScore = AntiRollFilter.scoreCandidate(
            originalTitle = "Radioactive",
            originalArtist = "Imagine Dragons",
            originalDurationMs = 186_000L,
            candidateTitle = "Radioactive",
            candidateArtist = "Imagine Dragons",
            candidateDurationMs = 186_000L
        )
        assertTrue("Song with 'Radioactive' in title must not be penalized as Radio version", radioScore >= 150)

        // "Deliverance" contains "live", must NOT match
        val deliverScore = AntiRollFilter.scoreCandidate(
            originalTitle = "Deliverance",
            originalArtist = "Opeth",
            originalDurationMs = 816_000L,
            candidateTitle = "Deliverance",
            candidateArtist = "Opeth",
            candidateDurationMs = 816_000L
        )
        assertTrue("Song with 'Deliverance' must not trigger Live penalty", deliverScore >= 150)
    }

    // =========================================================================
    // 4. Duration Fitting Mechanics
    // =========================================================================

    @Test
    fun testDurationFittingTiers() {
        val baseDuration = 200_000L // 200s
        val origTitle = "测试曲目"
        val origArtist = "测试歌手"

        val exactScore = AntiRollFilter.scoreCandidate(origTitle, origArtist, baseDuration, origTitle, origArtist, 200_000L) // +0s
        val closeScore = AntiRollFilter.scoreCandidate(origTitle, origArtist, baseDuration, origTitle, origArtist, 202_000L) // +2s
        val moderateScore = AntiRollFilter.scoreCandidate(origTitle, origArtist, baseDuration, origTitle, origArtist, 207_000L) // +7s
        val driftScore = AntiRollFilter.scoreCandidate(origTitle, origArtist, baseDuration, origTitle, origArtist, 212_000L) // +12s
        val heavyDriftScore = AntiRollFilter.scoreCandidate(origTitle, origArtist, baseDuration, origTitle, origArtist, 235_000L) // +35s
        val extremeDriftScore = AntiRollFilter.scoreCandidate(origTitle, origArtist, baseDuration, origTitle, origArtist, 310_000L) // +110s

        assertTrue("Exact fit must have highest duration reward", exactScore >= closeScore)
        assertTrue("Close fit (<=3s) must outscore moderate fit (8s)", closeScore > moderateScore)
        assertTrue("Moderate fit must outscore drift (12s)", moderateScore > driftScore)
        assertTrue("Heavy drift (>15s) must be penalized", driftScore > heavyDriftScore)
        assertTrue("Extreme drift (>45s) must be disqualified (<= -300)", extremeDriftScore <= -300)
    }

    @Test
    fun testTeaserDurationDisqualifiedEvenWithoutKeywords() {
        val score = AntiRollFilter.scoreCandidate(
            originalTitle = "长篇史诗",
            originalArtist = "交响乐团",
            originalDurationMs = 360_000L, // 6 mins
            candidateTitle = "长篇史诗",
            candidateArtist = "交响乐团",
            candidateDurationMs = 20_000L  // 20s teaser
        )
        assertTrue("Teaser duration (1-30s when expected >60s) must be disqualified (<= -300)", score <= -300)
    }

    @Test
    fun testMissingOrZeroDurationIsNeutral() {
        val scoreNullCand = AntiRollFilter.scoreCandidate("测试", "歌手", 180_000L, "测试", "歌手", null)
        val scoreZeroCand = AntiRollFilter.scoreCandidate("测试", "歌手", 180_000L, "测试", "歌手", 0L)
        val scoreNullOrig = AntiRollFilter.scoreCandidate("测试", "歌手", null, "测试", "歌手", 180_000L)

        assertTrue(scoreNullCand >= 150)
        assertEquals(scoreNullCand, scoreZeroCand)
        assertEquals(scoreNullCand, scoreNullOrig)
    }

    // =========================================================================
    // 5. Config Toggle Respect (enableAntiRoll)
    // =========================================================================

    @Test
    fun testAntiRollDisabledReturnsFlatScore() {
        val scoreWithAntiRollDisabled = AntiRollFilter.scoreCandidate(
            originalTitle = "晴天",
            originalArtist = "周杰伦",
            originalDurationMs = 269_000L,
            candidateTitle = "晴天 (Live现场版)",
            candidateArtist = "周杰伦",
            candidateDurationMs = 350_000L,
            enableAntiRoll = false
        )
        assertEquals(
            "Disabled anti-roll should return SCORE_BASE_CANDIDATE without keyword/duration penalties",
            AntiRollFilter.SCORE_BASE_CANDIDATE,
            scoreWithAntiRollDisabled
        )
    }

    // =========================================================================
    // 6. Detailed Evaluation & Ranking Helpers
    // =========================================================================

    @Test
    fun testEvaluateCandidateDetailedBreakdown() {
        val eval = AntiRollFilter.evaluateCandidate(
            originalTitle = "晴天",
            originalArtist = "周杰伦",
            originalDurationMs = 269_000L,
            candidateTitle = "晴天 (Live)",
            candidateArtist = "周杰伦",
            candidateDurationMs = 285_000L,
            enableAntiRoll = true
        )

        assertEquals(1, eval.penalizedCategories.size)
        assertEquals(AntiRollFilter.VersionCategory.LIVE, eval.penalizedCategories[0])
        assertTrue(eval.matchedCategories.isEmpty())
        assertTrue("Total score should be below clean threshold", eval.totalScore < AntiRollFilter.THRESHOLD_CLEAN)
        assertTrue("isDisqualified flag should match threshold", eval.isDisqualified)
    }

    @Test
    fun testRankCandidatesSorting() {
        data class Item(val title: String, val artist: String, val dur: Long)

        val candidates = listOf(
            Item("晴天 (Live版)", "周杰伦", 290_000L),       // Candidate 0: Live version
            Item("晴天", "周杰伦", 269_000L),               // Candidate 1: Official studio version
            Item("晴天 (翻唱)", "网络歌手", 269_000L)          // Candidate 2: Cover version
        )

        val song = SongInfo(id = 1L, name = "晴天", artist = "周杰伦", durationMs = 269_000L)
        val ranked = AntiRollFilter.rankCandidates(
            candidates = candidates,
            originalSong = song,
            titleExtractor = { it.title },
            artistExtractor = { it.artist },
            durationMsExtractor = { it.dur }
        )

        assertEquals("First ranked candidate must be the studio version", "晴天", ranked[0].candidate.title)
        assertTrue("Studio version must have highest score", ranked[0].score > ranked[1].score)
        assertTrue(ranked[0].score >= 150)
    }

    @Test
    fun testSelectBestCandidateThresholdFilter() {
        data class Item(val title: String, val artist: String, val dur: Long)

        val candidates = listOf(
            Item("晴天 (Live版)", "周杰伦", 290_000L)
        )
        val song = SongInfo(id = 1L, name = "晴天", artist = "周杰伦", durationMs = 269_000L)

        // Threshold clean (0) -> Live version should be rejected
        val bestClean = AntiRollFilter.selectBestCandidate(
            candidates = candidates,
            originalSong = song,
            titleExtractor = { it.title },
            artistExtractor = { it.artist },
            durationMsExtractor = { it.dur },
            minScoreThreshold = AntiRollFilter.THRESHOLD_CLEAN
        )
        assertNull("Live version must not qualify under THRESHOLD_CLEAN", bestClean)

        // Threshold fallback (-150) -> Live version should qualify as fallback
        val bestFallback = AntiRollFilter.selectBestCandidate(
            candidates = candidates,
            originalSong = song,
            titleExtractor = { it.title },
            artistExtractor = { it.artist },
            durationMsExtractor = { it.dur },
            minScoreThreshold = AntiRollFilter.THRESHOLD_FALLBACK
        )
        assertNotNull("Live version must qualify under THRESHOLD_FALLBACK", bestFallback)
        assertEquals("晴天 (Live版)", bestFallback?.title)
    }

    // =========================================================================
    // 7. Robustness & Boundary Edge Cases
    // =========================================================================

    @Test
    fun testEdgeCasesNullEmptyOrStrangeCharacters() {
        val scoreEmpty = AntiRollFilter.scoreCandidate("", "", null, "", "", null)
        assertTrue("Empty strings should not throw and return neutral score", scoreEmpty >= 0)

        val scoreCJK = AntiRollFilter.scoreCandidate(
            originalTitle = "晴天（现场版）",
            originalArtist = "周杰倫",
            originalDurationMs = 285_000L,
            candidateTitle = "晴天 (Live版)",
            candidateArtist = "周杰伦",
            candidateDurationMs = 285_000L
        )
        assertTrue("Full-width CJK brackets and simplified/traditional variants handled gracefully", scoreCJK >= 0)
    }

    @Test
    fun testArtistTokenOverlap() {
        val scoreDuet = AntiRollFilter.scoreCandidate(
            originalTitle = "千里之外",
            originalArtist = "周杰伦 / 费玉清",
            originalDurationMs = 250_000L,
            candidateTitle = "千里之外",
            candidateArtist = "周杰伦",
            candidateDurationMs = 250_000L
        )
        assertTrue("Duet with artist overlap should receive artist match bonus", scoreDuet >= 170)
    }
}
