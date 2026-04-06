package mobi.beyondpod.revival.data.repository

import com.google.gson.Gson
import kotlinx.coroutines.flow.Flow
import mobi.beyondpod.revival.data.local.dao.EpisodeDao
import mobi.beyondpod.revival.data.local.dao.FeedDao
import mobi.beyondpod.revival.data.local.dao.SmartPlaylistDao
import mobi.beyondpod.revival.data.local.entity.BlockSource
import mobi.beyondpod.revival.data.local.entity.EpisodeEntity
import mobi.beyondpod.revival.data.local.entity.FeedCategoryCrossRef
import mobi.beyondpod.revival.data.local.entity.FeedEntity
import mobi.beyondpod.revival.data.local.entity.PlayState
import mobi.beyondpod.revival.data.local.entity.PlaylistRuleMode
import mobi.beyondpod.revival.data.local.entity.SmartPlaylistBlock
import mobi.beyondpod.revival.data.parser.FeedParser
import mobi.beyondpod.revival.data.parser.ParsedEpisode
import okhttp3.OkHttpClient
import okhttp3.Request
import javax.inject.Inject

class FeedRepositoryImpl @Inject constructor(
    private val feedDao: FeedDao,
    private val episodeDao: EpisodeDao,
    private val episodeRepository: EpisodeRepository,
    private val okHttpClient: OkHttpClient,
    private val feedParser: FeedParser,
    private val smartPlaylistDao: SmartPlaylistDao,
    private val gson: Gson
) : FeedRepository {

    override fun getAllFeeds(): Flow<List<FeedEntity>> = feedDao.getAllFeeds()

    override fun getFeedsByCategory(categoryId: Long): Flow<List<FeedEntity>> =
        feedDao.getFeedsByCategory(categoryId)

    override suspend fun getFeedById(id: Long): FeedEntity? = feedDao.getFeedById(id)

    override suspend fun subscribeToFeed(url: String): Result<FeedEntity> = runCatching {
        val existing = feedDao.getFeedByUrl(url)
        if (existing != null) return@runCatching existing

        val parsed = fetchAndParse(url).getOrNull()
        val placeholder = FeedEntity(
            url          = url,
            title        = parsed?.title?.ifEmpty { url } ?: url,
            description  = parsed?.description ?: "",
            imageUrl     = parsed?.imageUrl,
            author       = parsed?.author ?: "",
            website      = parsed?.website ?: "",
            language     = parsed?.language ?: "",
            lastUpdated  = System.currentTimeMillis()
        )
        val id = feedDao.upsertFeed(placeholder)
        val feed = feedDao.getFeedById(id) ?: placeholder.copy(id = id)
        parsed?.episodes?.forEach { episodeRepository.upsertEpisode(it.toEntity(feed.id)) }
        feed
    }

    override suspend fun updateFeedProperties(feed: FeedEntity): Result<Unit> = runCatching {
        feedDao.upsertFeed(feed)
    }

    override suspend fun deleteFeed(id: Long, deleteDownloads: Boolean) {
        val feed = feedDao.getFeedById(id) ?: return
        if (deleteDownloads) {
            episodeDao.getEpisodesForFeedList(id).forEach { ep ->
                ep.localFilePath?.let { java.io.File(it).delete() }
            }
        }
        feedDao.deleteFeed(feed)
        prunePlaylistBlocksForFeed(id)
    }

    /**
     * Remove any SmartPlaylistBlock entries whose sourceId matches [feedId].
     * Called after deleting a feed so SEQUENTIAL_BLOCKS playlists don't reference a ghost feed.
     */
    private suspend fun prunePlaylistBlocksForFeed(feedId: Long) {
        smartPlaylistDao.getAllPlaylistsList().forEach { playlist ->
            if (playlist.ruleMode != PlaylistRuleMode.SEQUENTIAL_BLOCKS) return@forEach
            val blocks = runCatching {
                gson.fromJson(playlist.rulesJson, Array<SmartPlaylistBlock>::class.java).toList()
            }.getOrElse { return@forEach }
            val pruned = blocks.filter { it.source != BlockSource.FEED || it.sourceId != feedId }
            if (pruned.size != blocks.size) {
                smartPlaylistDao.upsertPlaylist(playlist.copy(rulesJson = gson.toJson(pruned)))
            }
        }
    }

    /**
     * Re-fetch the RSS, update feed metadata, upsert episodes (multi-key dedup), archive removed.
     * Steps 1–4 of FeedUpdateWorker pipeline (CLAUDE.md rule #8).
     */
    override suspend fun refreshFeed(id: Long): Result<Unit> = runCatching {
        val feed   = feedDao.getFeedById(id) ?: return@runCatching
        val parsed = fetchAndParse(feed.url).getOrThrow()

        feedDao.upsertFeed(feed.copy(
            title            = parsed.title.ifEmpty { feed.title },
            description      = parsed.description.ifEmpty { feed.description },
            imageUrl         = parsed.imageUrl ?: feed.imageUrl,
            author           = parsed.author.ifEmpty { feed.author },
            website          = parsed.website.ifEmpty { feed.website },
            lastUpdated      = System.currentTimeMillis(),
            lastUpdateFailed = false,
            lastUpdateError  = null
        ))

        val upsertedIds = mutableListOf<Long>()
        parsed.episodes.forEach { ep ->
            upsertedIds.add(episodeRepository.upsertEpisode(ep.toEntity(id)))
        }
        if (upsertedIds.isNotEmpty()) {
            episodeDao.archiveRemovedEpisodes(id, upsertedIds)
        }
    }

    override suspend fun refreshAllFeeds(): Result<Unit> = runCatching {
        feedDao.getAllFeedsList().forEach { feed -> refreshFeed(feed.id) }
    }

    override suspend fun moveFeedToCategory(feedId: Long, categoryId: Long?, isPrimary: Boolean) {
        feedDao.deleteFeedCategoryRefByType(feedId, isPrimary)
        if (categoryId != null) {
            feedDao.insertFeedCategoryRef(FeedCategoryCrossRef(feedId, categoryId, isPrimary))
        }
        if (isPrimary) feedDao.updatePrimaryCategory(feedId, categoryId)
        else           feedDao.updateSecondaryCategory(feedId, categoryId)
    }

    /**
     * Parse OPML and upsert feed stubs for every `<outline type="rss">` found.
     * SAX-based via [FeedParser.parseOpml] — no third-party library (CLAUDE.md rule).
     */
    override suspend fun importFromOpml(opmlContent: String): Result<Int> = runCatching {
        val outlines = feedParser.parseOpml(opmlContent)
        var count = 0
        outlines.forEach { (xmlUrl, title, _) ->
            if (feedDao.getFeedByUrl(xmlUrl) == null) {
                feedDao.upsertFeed(FeedEntity(url = xmlUrl, title = title.ifEmpty { xmlUrl }))
                count++
            }
        }
        count
    }

    override suspend fun exportToOpml(): Result<String> = runCatching {
        val feeds = feedDao.getAllFeedsList()
        buildString {
            appendLine("""<?xml version="1.0" encoding="UTF-8"?>""")
            appendLine("""<opml version="2.0">""")
            appendLine("""  <head><title>BeyondPod Revival — Podcast Subscriptions</title></head>""")
            appendLine("""  <body>""")
            for (feed in feeds) {
                val t = feed.title.escapeXml()
                val x = feed.url.escapeXml()
                val h = feed.website.escapeXml()
                appendLine("""    <outline text="$t" title="$t" type="rss" xmlUrl="$x" htmlUrl="$h"/>""")
            }
            appendLine("""  </body>""")
            appendLine("""</opml>""")
        }
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private fun fetchAndParse(url: String) = runCatching {
        val req = Request.Builder().url(url).header("User-Agent", "BeyondPodRevival/5.0").build()
        okHttpClient.newCall(req).execute().use { resp ->
            check(resp.isSuccessful) { "HTTP ${resp.code} for $url" }
            feedParser.parse(resp.body?.byteStream() ?: error("Empty response body"))
        }
    }

    private fun ParsedEpisode.toEntity(feedId: Long) = EpisodeEntity(
        feedId        = feedId,
        guid          = guid.ifEmpty { url },
        title         = title,
        description   = description,
        pubDate       = pubDate,
        url           = url,
        mimeType      = mimeType,
        fileSizeBytes = fileSizeBytes,
        duration      = duration,
        imageUrl      = imageUrl,
        author        = author,
        chapterUrl    = chapterUrl,
        transcriptUrl = transcriptUrl,
        playState     = PlayState.NEW
    )

    private fun String.escapeXml() = replace("&", "&amp;").replace("<", "&lt;")
        .replace(">", "&gt;").replace("\"", "&quot;").replace("'", "&apos;")
}
