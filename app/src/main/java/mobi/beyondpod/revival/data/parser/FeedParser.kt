package mobi.beyondpod.revival.data.parser

import org.xml.sax.Attributes
import org.xml.sax.InputSource
import org.xml.sax.helpers.DefaultHandler
import java.io.InputStream
import java.io.StringReader
import java.text.SimpleDateFormat
import java.time.Instant
import java.time.format.DateTimeParseException
import java.util.Locale
import javax.inject.Inject
import javax.xml.parsers.SAXParserFactory

/**
 * Custom SAX-based RSS 2.0 / Atom 1.0 parser. No third-party RSS library (CLAUDE.md rule).
 *
 * Handles:
 * - RSS 2.0 `<channel>` / `<item>` structure
 * - Atom 1.0 `<feed>` / `<entry>` structure
 * - iTunes namespace (`itunes:duration`, `itunes:image`, `itunes:author`, `itunes:summary`)
 * - Media namespace (`media:content`)
 * - Podcast namespace chapters/transcript attributes
 */
class FeedParser @Inject constructor() {

    companion object {
        // Namespace URIs
        const val ITUNES_NS  = "http://www.itunes.com/dtds/podcast-1.0.dtd"
        const val MEDIA_NS   = "http://search.yahoo.com/mrss/"
        const val PODCAST_NS = "https://podcastindex.org/namespace/1.0"
        const val CONTENT_NS = "http://purl.org/rss/1.0/modules/content/"

        // Date formatters (RFC 2822 variants)
        private val RFC2822_FULL   = SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss Z", Locale.US)
        private val RFC2822_NO_DAY = SimpleDateFormat("dd MMM yyyy HH:mm:ss Z", Locale.US)
        private val RFC2822_NOTZ   = SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss", Locale.US)
        private val ISO_DATE       = SimpleDateFormat("yyyy-MM-dd", Locale.US)
        private val DATE_FORMATS: List<SimpleDateFormat> =
            listOf(RFC2822_FULL, RFC2822_NO_DAY, RFC2822_NOTZ, ISO_DATE)

        fun parseDate(raw: String): Long {
            if (raw.isBlank()) return 0L
            if (raw.contains('T')) {
                return try {
                    Instant.parse(raw.trim()).toEpochMilli()
                } catch (_: DateTimeParseException) {
                    try { Instant.parse(raw.trim().replace(" ", "T")).toEpochMilli() }
                    catch (_: Exception) { 0L }
                }
            }
            for (fmt in DATE_FORMATS) {
                try { return fmt.parse(raw.trim())?.time ?: continue } catch (_: Exception) {}
            }
            return 0L
        }

        fun parseDuration(raw: String): Long {
            if (raw.isBlank()) return 0L
            val parts = raw.trim().split(":")
            return when (parts.size) {
                3 -> {
                    val h = parts[0].toLongOrNull() ?: 0L
                    val m = parts[1].toLongOrNull() ?: 0L
                    val s = parts[2].toLongOrNull() ?: 0L
                    (h * 3600 + m * 60 + s) * 1000L
                }
                2 -> {
                    val m = parts[0].toLongOrNull() ?: 0L
                    val s = parts[1].toLongOrNull() ?: 0L
                    (m * 60 + s) * 1000L
                }
                else -> (raw.trim().toLongOrNull() ?: 0L) * 1000L
            }
        }
    }

    // ── Public API ────────────────────────────────────────────────────────────

    fun parse(inputStream: InputStream): ParsedFeed {
        val factory = SAXParserFactory.newInstance().apply { isNamespaceAware = true }
        val handler = FeedHandler()
        factory.newSAXParser().parse(inputStream, handler)
        return handler.build()
    }

    /** Parse OPML 2.0 and return list of (xmlUrl, title, categoryTitle?) triples. */
    fun parseOpml(xml: String): List<Triple<String, String, String?>> {
        val factory = SAXParserFactory.newInstance().apply { isNamespaceAware = true }
        val handler = OpmlHandler()
        factory.newSAXParser().parse(InputSource(StringReader(xml)), handler)
        return handler.results
    }

    // ── RSS/Atom SAX handler ──────────────────────────────────────────────────

    private class FeedHandler : DefaultHandler() {

        private var feedTitle = ""; private var feedDesc = ""; private var feedImageUrl: String? = null
        private var feedAuthor = ""; private var feedWebsite = ""; private var feedLanguage = ""

        private val episodes = mutableListOf<ParsedEpisode>()
        private var ep: EpisodeBuilder? = null

        private var inChannel = false; private var inItem = false; private var inAtomEntry = false
        private var inChannelImage = false
        private val chars = StringBuilder()

        override fun startElement(uri: String, localName: String, qName: String, attrs: Attributes) {
            chars.clear()
            when {
                qName == "channel" -> inChannel = true
                qName == "item" -> { ep = EpisodeBuilder(); inItem = true }
                qName == "entry" -> { ep = EpisodeBuilder(); inAtomEntry = true }

                qName == "image" && inChannel && !inItem && !inAtomEntry -> inChannelImage = true

                qName == "enclosure" -> ep?.let { b ->
                    b.url = attrs.getValue("url") ?: b.url
                    b.mimeType = attrs.getValue("type") ?: "audio/mpeg"
                    b.fileSizeBytes = attrs.getValue("length")?.toLongOrNull() ?: 0L
                }

                qName == "link" && attrs.getValue("href") != null -> {
                    val href = attrs.getValue("href")!!
                    val rel  = attrs.getValue("rel") ?: "alternate"
                    val type = attrs.getValue("type") ?: ""
                    when {
                        rel == "enclosure" -> ep?.let { b ->
                            b.url = href; b.mimeType = type.ifEmpty { "audio/mpeg" }
                            b.fileSizeBytes = attrs.getValue("length")?.toLongOrNull() ?: 0L
                        }
                        rel == "alternate" || rel == "self" -> {
                            if (inItem || inAtomEntry) { if (ep?.link.isNullOrEmpty()) ep?.link = href }
                            else if (feedWebsite.isEmpty()) feedWebsite = href
                        }
                    }
                }

                qName == "itunes:image" || (uri == ITUNES_NS && localName == "image") -> {
                    val href = attrs.getValue("href") ?: return
                    if (inItem || inAtomEntry) ep?.imageUrl = href
                    else if (feedImageUrl == null) feedImageUrl = href
                }

                qName == "media:content" || (uri == MEDIA_NS && localName == "content") -> {
                    val url    = attrs.getValue("url") ?: return
                    val medium = attrs.getValue("medium") ?: ""
                    val type   = attrs.getValue("type") ?: ""
                    if ((medium == "audio" || medium == "video" || type.startsWith("audio") || type.startsWith("video"))
                        && ep?.url.isNullOrEmpty()) {
                        ep?.url = url; ep?.mimeType = type.ifEmpty { "audio/mpeg" }
                        ep?.fileSizeBytes = attrs.getValue("fileSize")?.toLongOrNull() ?: 0L
                    }
                }

                qName == "podcast:chapters" || (uri == PODCAST_NS && localName == "chapters") ->
                    ep?.chapterUrl = attrs.getValue("url")

                qName == "podcast:transcript" || (uri == PODCAST_NS && localName == "transcript") ->
                    if (ep?.transcriptUrl == null) ep?.transcriptUrl = attrs.getValue("url")
            }
        }

        override fun endElement(uri: String, localName: String, qName: String) {
            val text = chars.toString().trim()

            when {
                qName == "item" || (inAtomEntry && (qName == "entry" || localName == "entry")) -> {
                    ep?.let { b ->
                        if (b.guid.isEmpty()) b.guid = b.url
                        if (b.url.isNotEmpty()) episodes.add(b.build())
                    }
                    ep = null; inItem = false; inAtomEntry = false
                }
                qName == "image" && inChannelImage -> inChannelImage = false
                qName == "channel" -> inChannel = false

                inItem || inAtomEntry -> {
                    val b = ep ?: return
                    when {
                        localName == "title" || qName == "title"                  -> b.title = text
                        localName == "guid" || qName == "guid"                    -> b.guid = text
                        localName == "link" || qName == "link"                    -> { if (b.link.isEmpty()) b.link = text }
                        localName == "pubDate" || qName == "pubDate"              -> b.pubDate = parseDate(text)
                        localName == "published" || qName == "published"          -> { if (b.pubDate == 0L) b.pubDate = parseDate(text) }
                        localName == "updated" || qName == "updated"              -> { if (b.pubDate == 0L) b.pubDate = parseDate(text) }
                        localName == "duration" && uri == ITUNES_NS               -> b.duration = parseDuration(text)
                        qName == "itunes:duration"                                -> b.duration = parseDuration(text)
                        (localName == "author" && uri == ITUNES_NS) || qName == "itunes:author" -> b.author = text
                        localName == "author" && b.author.isEmpty()               -> b.author = text
                        (localName == "summary" && uri == ITUNES_NS) || qName == "itunes:summary" ->
                            { if (b.description.isEmpty()) b.description = text }
                        (localName == "encoded" && uri == CONTENT_NS) || qName == "content:encoded" ->
                            b.description = text
                        (localName == "description" || qName == "description") && b.description.isEmpty() ->
                            b.description = text
                        localName == "content" && uri.contains("atom") && b.description.isEmpty() ->
                            b.description = text
                    }
                }

                else -> when {
                    (localName == "title" || qName == "title") && feedTitle.isEmpty()       -> feedTitle = text
                    (localName == "description" || qName == "description") && feedDesc.isEmpty() -> feedDesc = text
                    qName == "itunes:summary" && feedDesc.isEmpty()                          -> feedDesc = text
                    (localName == "link" || qName == "link") && feedWebsite.isEmpty() && text.startsWith("http") ->
                        feedWebsite = text
                    localName == "language" || qName == "language"                           -> feedLanguage = text
                    qName == "itunes:author" || (localName == "author" && uri == ITUNES_NS)  -> feedAuthor = text
                    (localName == "author" || qName == "managingEditor") && feedAuthor.isEmpty() -> feedAuthor = text
                    (localName == "url" || qName == "url") && inChannelImage && feedImageUrl == null ->
                        feedImageUrl = text
                }
            }
            chars.clear()
        }

        override fun characters(ch: CharArray, start: Int, length: Int) {
            chars.append(ch, start, length)
        }

        fun build() = ParsedFeed(
            title = feedTitle, description = feedDesc, imageUrl = feedImageUrl,
            author = feedAuthor, website = feedWebsite, language = feedLanguage,
            episodes = episodes
        )
    }

    // ── OPML SAX handler ─────────────────────────────────────────────────────

    private class OpmlHandler : DefaultHandler() {
        val results = mutableListOf<Triple<String, String, String?>>()
        private var currentCategory: String? = null

        override fun startElement(uri: String, localName: String, qName: String, attrs: Attributes) {
            if (qName != "outline" && localName != "outline") return
            val xmlUrl = attrs.getValue("xmlUrl")
            val text   = attrs.getValue("text") ?: attrs.getValue("title") ?: ""
            when {
                xmlUrl != null -> results.add(Triple(xmlUrl, text, currentCategory))
                else           -> currentCategory = text.ifEmpty { null }
            }
        }

        override fun endElement(uri: String, localName: String, qName: String) {
            if (qName == "outline" || localName == "outline") currentCategory = null
        }
    }

    // ── Episode builder ───────────────────────────────────────────────────────

    private class EpisodeBuilder {
        var title = ""; var description = ""; var guid = ""; var url = ""
        var mimeType = "audio/mpeg"; var fileSizeBytes = 0L; var pubDate = 0L
        var duration = 0L; var imageUrl: String? = null; var author = ""
        var chapterUrl: String? = null; var transcriptUrl: String? = null
        var link = ""

        fun build() = ParsedEpisode(
            title = title, description = description, guid = guid, url = url,
            mimeType = mimeType, fileSizeBytes = fileSizeBytes, pubDate = pubDate,
            duration = duration, imageUrl = imageUrl, author = author,
            chapterUrl = chapterUrl, transcriptUrl = transcriptUrl
        )
    }
}
