package pochita.source

import java.util.regex.Pattern
import pochita.common.UnmLogger
import pochita.config.ConfigManager
import pochita.model.SongInfo

/**
 * Intelligent candidate scoring and anti-roll version filter for NetEase UNM.
 *
 * Responsibilities:
 * - Dynamic keyword penalties (Live, 现场, 电台, 翻唱, DJ, Remix, 伴奏, etc.):
 *   Penalties are applied ONLY when the original song title does NOT contain them.
 *   If the original song title explicitly contains them, candidates are rewarded with a version-match bonus.
 * - Latin word boundaries (\bLive\b, \bRadio\b) to avoid false positives (Alive, Radioactive).
 * - Multi-tier duration fitting (<=1s, <=3s, 3-8s, 8-15s, 15-45s, >45s disqualification, teaser <30s guard).
 * - Full-fidelity artist and title similarity alignment with CJK / delimiter tokenization.
 * - Respects [ConfigManager.getConfig().enableAntiRoll] switch with instant hot reload.
 */
object AntiRollFilter {
    const val TAG = "AntiRollFilter"

    const val BASE_SCORE = 100
    const val SCORE_BASE_CANDIDATE = 100
    const val SCORE_EXACT_STUDIO_MATCH = 150
    const val THRESHOLD_CLEAN = 0
    const val THRESHOLD_FALLBACK = -150
    const val SCORE_DISQUALIFIED = -1000
    const val DISQUALIFIED_THRESHOLD = 0

    // =========================================================================
    // Keyword Categories & Patterns
    // =========================================================================

    enum class VersionCategory(
        val penalty: Int,
        val matchBonus: Int,
        val chineseKeywords: List<String>,
        val latinPatterns: List<Pattern>
    ) {
        ACCOMPANIMENT(
            penalty = -250,
            matchBonus = 20,
            chineseKeywords = listOf("伴奏", "伴唱", "消音", "伴奏带", "纯伴奏", "伴奏版"),
            latinPatterns = listOf(
                Pattern.compile("\\b(instrumental|karaoke|backing track|inst)\\b", Pattern.CASE_INSENSITIVE)
            )
        ),
        COVER(
            penalty = -220,
            matchBonus = 20,
            chineseKeywords = listOf("翻唱", "翻唱版", "翻自", "致敬"),
            latinPatterns = listOf(
                Pattern.compile("\\b(cover|tribute)\\b", Pattern.CASE_INSENSITIVE)
            )
        ),
        RADIO(
            penalty = -220,
            matchBonus = 20,
            chineseKeywords = listOf("电台", "广播", "广播版", "有声书", "评书", "主播", "播客", "朗诵"),
            latinPatterns = listOf(
                Pattern.compile("\\b(radio|fm|podcast|broadcast)\\b", Pattern.CASE_INSENSITIVE)
            )
        ),
        DJ_REMIX(
            penalty = -220,
            matchBonus = 20,
            chineseKeywords = listOf("DJ", "DJ版", "慢摇", "电音", "串烧", "嗨歌", "重低音"),
            latinPatterns = listOf(
                Pattern.compile("\\b(remix|club mix|extended mix|mashup|bootleg|edm)\\b", Pattern.CASE_INSENSITIVE)
            )
        ),
        LIVE(
            penalty = -220,
            matchBonus = 20,
            chineseKeywords = listOf("现场", "演唱会", "音乐会", "现场版", "实况", "巡演", "音乐节"),
            latinPatterns = listOf(
                Pattern.compile("\\b(live|concert|tour|unplugged|acoustic)\\b", Pattern.CASE_INSENSITIVE)
            )
        ),
        CLIP_TEASER(
            penalty = -500,
            matchBonus = 0,
            chineseKeywords = listOf("片段", "铃声", "彩铃", "副歌", "截取", "加速", "减速", "变调", "试听", "预告", "下架", "剪辑", "卡点"),
            latinPatterns = listOf(
                Pattern.compile("\\b(ringtone|clip|teaser|preview|pitch)\\b", Pattern.CASE_INSENSITIVE)
            )
        );

        fun matches(title: String): Boolean {
            if (title.isBlank()) return false
            for (kw in chineseKeywords) {
                if (title.contains(kw, ignoreCase = true)) return true
            }
            for (p in latinPatterns) {
                if (p.matcher(title).find()) return true
            }
            return false
        }
    }

    /**
     * Diagnostic breakdown of a candidate evaluation.
     */
    data class CandidateEvaluation(
        val totalScore: Int,
        val titleScore: Int,
        val artistScore: Int,
        val durationScore: Int,
        val keywordScore: Int,
        val penalizedCategories: List<VersionCategory>,
        val matchedCategories: List<VersionCategory>
    ) {
        val isDisqualified: Boolean get() = totalScore <= DISQUALIFIED_THRESHOLD
    }

    // =========================================================================
    // Core Scoring API
    // =========================================================================

    /**
     * Calculates candidate match score based on keyword penalty, duration fitting, and metadata alignment.
     * Higher score indicates a better candidate. Negative scores indicate penalized or fallback versions.
     * Scores <= -300 indicate hard disqualification (e.g. extreme duration drift or teaser clips).
     *
     * @param originalTitle Title of the song in NetEase Cloud Music.
     * @param originalArtist Artist name in NetEase Cloud Music.
     * @param originalDurationMs Target song duration in milliseconds (or null/0 if unknown).
     * @param candidateTitle Title of candidate track returned by search provider.
     * @param candidateArtist Artist name of candidate track returned by search provider.
     * @param candidateDurationMs Candidate track duration in milliseconds (or null/0 if unknown).
     * @param enableAntiRoll Whether anti-roll penalties are enabled. Defaults to active config switch.
     * @return Composite integer score.
     */
    @JvmStatic
    @JvmOverloads
    fun scoreCandidate(
        originalTitle: String,
        originalArtist: String,
        originalDurationMs: Long?,
        candidateTitle: String,
        candidateArtist: String,
        candidateDurationMs: Long?,
        enableAntiRoll: Boolean = ConfigManager.getConfig().enableAntiRoll
    ): Int {
        if (!enableAntiRoll) {
            return SCORE_BASE_CANDIDATE
        }
        return evaluateCandidate(
            originalTitle = originalTitle,
            originalArtist = originalArtist,
            originalDurationMs = originalDurationMs,
            candidateTitle = candidateTitle,
            candidateArtist = candidateArtist,
            candidateDurationMs = candidateDurationMs,
            enableAntiRoll = enableAntiRoll
        ).totalScore
    }

    /**
     * Detailed candidate evaluation producing score breakdown and category classifications.
     */
    @JvmStatic
    @JvmOverloads
    fun evaluateCandidate(
        originalTitle: String,
        originalArtist: String,
        originalDurationMs: Long?,
        candidateTitle: String,
        candidateArtist: String,
        candidateDurationMs: Long?,
        enableAntiRoll: Boolean = ConfigManager.getConfig().enableAntiRoll
    ): CandidateEvaluation {
        if (!enableAntiRoll) {
            return CandidateEvaluation(
                totalScore = SCORE_BASE_CANDIDATE,
                titleScore = 0,
                artistScore = 0,
                durationScore = 0,
                keywordScore = 0,
                penalizedCategories = emptyList(),
                matchedCategories = emptyList()
            )
        }

        var titleScore = 0
        var artistScore = 0
        var keywordScore = 0
        val penalizedCategories = mutableListOf<VersionCategory>()
        val matchedCategories = mutableListOf<VersionCategory>()

        // 1. Title Similarity
        val cleanOrig = cleanTitle(originalTitle)
        val cleanCand = cleanTitle(candidateTitle)
        val coreOrig = extractCoreTitle(originalTitle)
        val coreCand = extractCoreTitle(candidateTitle)

        if (cleanCand.isBlank()) {
            // Neutral when candidate title is missing
            titleScore = 0
        } else if (cleanOrig.isBlank()) {
            titleScore = 0
        } else {
            when {
                cleanOrig.equals(cleanCand, ignoreCase = true) -> {
                    titleScore += 40
                }
                coreOrig.isNotEmpty() && coreCand.isNotEmpty() && coreOrig.equals(coreCand, ignoreCase = true) -> {
                    titleScore += 35
                }
                cleanCand.contains(cleanOrig, ignoreCase = true) || cleanOrig.contains(cleanCand, ignoreCase = true) -> {
                    titleScore += 20
                }
                coreCand.contains(coreOrig, ignoreCase = true) || coreOrig.contains(coreCand, ignoreCase = true) -> {
                    titleScore += 15
                }
                else -> {
                    titleScore -= 80
                }
            }
        }

        // 2. Artist Similarity
        if (originalArtist.isNotBlank() && candidateArtist.isNotBlank()) {
            val origTokens = splitArtists(originalArtist)
            val candTokens = splitArtists(candidateArtist)
            val hasArtistOverlap = origTokens.any { o ->
                candTokens.any { c ->
                    c.contains(o, ignoreCase = true) || o.contains(c, ignoreCase = true)
                }
            }
            if (hasArtistOverlap) {
                artistScore += 30
            } else {
                artistScore -= 50
            }
        }

        // 3. Dynamic Keyword Penalties & Version Match Bonus
        for (category in VersionCategory.values()) {
            val candidateHas = category.matches(candidateTitle)
            val originalHas = category.matches(originalTitle)

            if (candidateHas) {
                if (originalHas) {
                    // Original explicitly requested this version (e.g. "晴天 (Live)"), reward match
                    keywordScore += category.matchBonus
                    matchedCategories.add(category)
                } else {
                    // Original is studio, candidate introduced unwanted non-studio variant: penalize
                    keywordScore += category.penalty
                    penalizedCategories.add(category)
                }
            }
        }

        // 4. Duration Fitting
        val durationScore = calculateDurationScore(originalDurationMs, candidateDurationMs, enableAntiRoll)

        val totalScore = BASE_SCORE + titleScore + artistScore + keywordScore + durationScore

        return CandidateEvaluation(
            totalScore = totalScore,
            titleScore = titleScore,
            artistScore = artistScore,
            durationScore = durationScore,
            keywordScore = keywordScore,
            penalizedCategories = penalizedCategories,
            matchedCategories = matchedCategories
        )
    }

    /**
     * Multi-tier duration scoring curve.
     */
    internal fun calculateDurationScore(
        originalDurationMs: Long?,
        candidateDurationMs: Long?,
        enableAntiRoll: Boolean
    ): Int {
        if (originalDurationMs == null || originalDurationMs <= 0 ||
            candidateDurationMs == null || candidateDurationMs <= 0
        ) {
            return 0 // Neutral when duration is unavailable
        }

        // Teaser clip protection (always enforced: 1-30s when expected >60s)
        if (originalDurationMs > 60_000L && candidateDurationMs in 1_000L..30_000L) {
            return SCORE_DISQUALIFIED
        }

        val diffMs = kotlin.math.abs(candidateDurationMs - originalDurationMs)
        val diffSec = diffMs / 1000.0

        if (!enableAntiRoll) {
            return 0
        }

        return when {
            diffSec <= 1.0 -> 20 // Spot-on master match bonus
            diffSec <= 3.0 -> 10 // Close fit bonus
            diffSec <= 8.0 -> 0  // Acceptable tolerance (neutral)
            diffSec <= 15.0 -> -15 // Moderate drift
            diffSec <= 45.0 -> {
                // Progressive linear drift penalty: -30 base, -2 pts per second over 15s
                val extraSec = diffSec - 15.0
                -30 - (extraSec * 2.0).toInt()
            }
            else -> {
                // Extreme drift disqualification (>45s)
                -500
            }
        }
    }

    // =========================================================================
    // Candidate Ranking & Selection Helpers
    // =========================================================================

    data class ScoredCandidate<T>(
        val candidate: T,
        val score: Int,
        val evaluation: CandidateEvaluation
    )

    /**
     * Ranks a collection of candidate items using [scoreCandidate] in descending order.
     * Ties preserve natural provider rank (stable sort).
     */
    fun <T> rankCandidates(
        candidates: List<T>,
        originalSong: SongInfo,
        titleExtractor: (T) -> String,
        artistExtractor: (T) -> String,
        durationMsExtractor: (T) -> Long?,
        enableAntiRoll: Boolean = ConfigManager.getConfig().enableAntiRoll
    ): List<ScoredCandidate<T>> {
        return candidates.map { c ->
            val evaluation = evaluateCandidate(
                originalTitle = originalSong.name,
                originalArtist = originalSong.artist,
                originalDurationMs = originalSong.durationMs.takeIf { it > 0 },
                candidateTitle = titleExtractor(c),
                candidateArtist = artistExtractor(c),
                candidateDurationMs = durationMsExtractor(c),
                enableAntiRoll = enableAntiRoll
            )
            ScoredCandidate(c, evaluation.totalScore, evaluation)
        }.sortedWith(
            compareByDescending { it.score }
        )
    }

    /**
     * Selects the highest-scoring candidate that exceeds [minScoreThreshold].
     */
    fun <T> selectBestCandidate(
        candidates: List<T>,
        originalSong: SongInfo,
        titleExtractor: (T) -> String,
        artistExtractor: (T) -> String,
        durationMsExtractor: (T) -> Long?,
        enableAntiRoll: Boolean = ConfigManager.getConfig().enableAntiRoll,
        minScoreThreshold: Int = THRESHOLD_CLEAN
    ): T? {
        val ranked = rankCandidates(
            candidates = candidates,
            originalSong = originalSong,
            titleExtractor = titleExtractor,
            artistExtractor = artistExtractor,
            durationMsExtractor = durationMsExtractor,
            enableAntiRoll = enableAntiRoll
        )
        val best = ranked.firstOrNull() ?: return null
        return if (best.score >= minScoreThreshold) {
            best.candidate
        } else {
            UnmLogger.w(TAG, "All candidates below threshold $minScoreThreshold for '${originalSong.name}' (best score=${best.score})")
            null
        }
    }

    // =========================================================================
    // Text Normalization Helpers
    // =========================================================================

    private val TITLE_CLEAN_REGEX = Pattern.compile("[\\s()（）\\[\\]【】{}《》·._\\-]+")
    private val BRACKET_CONTENT_REGEX = Regex("[\\(\\[（【{《].*?[\\)\\]）】}》]")
    private val ARTIST_DELIMITER_REGEX = Regex("[,/&、+;]|\\bfeat\\.?\\b|\\bft\\.?\\b", RegexOption.IGNORE_CASE)

    private fun cleanTitle(title: String): String {
        return TITLE_CLEAN_REGEX.matcher(title).replaceAll("").trim().lowercase()
    }

    private fun extractCoreTitle(title: String): String {
        val stripped = title.replace(BRACKET_CONTENT_REGEX, "").trim()
        return cleanTitle(if (stripped.isNotEmpty()) stripped else title)
    }

    private fun splitArtists(artists: String): List<String> {
        return artists.split(ARTIST_DELIMITER_REGEX)
            .map { it.trim().lowercase() }
            .filter { it.isNotBlank() }
    }
}
