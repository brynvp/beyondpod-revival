package mobi.beyondpod.revival.data.parser

/** Output of [FeedParser]. All fields are safe defaults; never null. */
data class ParsedFeed(
    val title: String = "",
    val description: String = "",
    val imageUrl: String? = null,
    val author: String = "",
    val website: String = "",
    val language: String = "",
    val episodes: List<ParsedEpisode> = emptyList()
)

data class ParsedEpisode(
    val title: String = "",
    val description: String = "",
    val guid: String = "",           // may equal url if feed omits <guid>
    val url: String = "",            // enclosure/audio URL
    val mimeType: String = "audio/mpeg",
    val fileSizeBytes: Long = 0L,
    val pubDate: Long = 0L,          // epoch millis
    val duration: Long = 0L,         // millis; 0 = unknown
    val imageUrl: String? = null,    // overrides feed image
    val author: String = "",
    val chapterUrl: String? = null,
    val transcriptUrl: String? = null
)
