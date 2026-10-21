package pochita.model

data class SongInfo(
    val id: Long,
    val name: String,
    val artist: String,
    val durationMs: Long = 0L,
)

data class MatchedAudio(
    val url: String,
    val br: Int = 320000,
    val size: Long = 0L,
    val md5: String = "",
    val source: String = "unknown",
    val matchedTitle: String? = null,
    val matchedArtist: String? = null,
    val durationMs: Long? = null,
)

data class ReplaceEvent(
    val songId: Long,
    val originalTitle: String,
    val originalArtist: String,
    val matchedSource: String,
    val matchedTitle: String,
    val matchedArtist: String,
    val br: Int,
    val timestamp: Long = System.currentTimeMillis(),
    val matchedAudio: MatchedAudio? = null,
) {
    constructor(
        songId: Long,
        originalTitle: String,
        originalArtist: String,
        matchedAudio: MatchedAudio,
        timestamp: Long = System.currentTimeMillis()
    ) : this(
        songId = songId,
        originalTitle = originalTitle,
        originalArtist = originalArtist,
        matchedSource = matchedAudio.source,
        matchedTitle = matchedAudio.matchedTitle?.ifBlank { originalTitle } ?: originalTitle,
        matchedArtist = matchedAudio.matchedArtist?.ifBlank { originalArtist } ?: originalArtist,
        br = matchedAudio.br,
        timestamp = timestamp,
        matchedAudio = matchedAudio
    )
}

