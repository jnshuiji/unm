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
    val matchedTitle: String = "",
    val matchedArtist: String = "",
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
)

