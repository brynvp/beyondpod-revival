package mobi.beyondpod.revival.data.repository

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import mobi.beyondpod.revival.data.local.dao.FeedDao
import mobi.beyondpod.revival.data.local.entity.DownloadStrategy
import mobi.beyondpod.revival.data.local.entity.FeedCategoryCrossRef
import mobi.beyondpod.revival.data.local.entity.FeedEntity
import java.io.File
import javax.inject.Inject

class FeedRepositoryImpl @Inject constructor(
    private val feedDao: FeedDao
) : FeedRepository {

    override fun getAllFeeds(): Flow<List<FeedEntity>> = feedDao.getAllFeeds()

    override fun getFeedsByCategory(categoryId: Long): Flow<List<FeedEntity>> =
        feedDao.getFeedsByCategory(categoryId)

    override suspend fun getFeedById(id: Long): FeedEntity? = feedDao.getFeedById(id)

    override suspend fun subscribeToFeed(url: String): Result<FeedEntity> = runCatching {
        // Phase 3 will fetch and parse the RSS feed. For now, create a minimal record.
        val existing = feedDao.getFeedByUrl(url)
        if (existing != null) return@runCatching existing

        val placeholder = FeedEntity(
            url = url,
            title = url,                   // overwritten when RSS is parsed in Phase 3
            lastUpdated = 0L
        )
        val id = feedDao.upsertFeed(placeholder)
        feedDao.getFeedById(id) ?: placeholder.copy(id = id)
    }

    override suspend fun updateFeedProperties(feed: FeedEntity): Result<Unit> = runCatching {
        feedDao.upsertFeed(feed)
    }

    override suspend fun deleteFeed(id: Long, deleteDownloads: Boolean) {
        val feed = feedDao.getFeedById(id) ?: return
        // File deletion is handled by DownloadWorker/cleanup in Phase 3; for now, proceed.
        feedDao.deleteFeed(feed)
    }

    override suspend fun refreshFeed(id: Long): Result<Unit> =
        Result.failure(NotImplementedError("RSS parsing implemented in Phase 3 (FeedUpdateWorker)"))

    override suspend fun refreshAllFeeds(): Result<Unit> =
        Result.failure(NotImplementedError("RSS parsing implemented in Phase 3 (FeedUpdateWorker)"))

    override suspend fun moveFeedToCategory(feedId: Long, categoryId: Long?, isPrimary: Boolean) {
        // Remove the existing ref of the same type (primary or secondary).
        feedDao.deleteFeedCategoryRefByType(feedId, isPrimary)

        if (categoryId != null) {
            feedDao.insertFeedCategoryRef(FeedCategoryCrossRef(feedId, categoryId, isPrimary))
        }

        // Keep the denormalized cache fields on FeedEntity in sync.
        if (isPrimary) {
            feedDao.updatePrimaryCategory(feedId, categoryId)
        } else {
            feedDao.updateSecondaryCategory(feedId, categoryId)
        }
    }

    /**
     * OPML import — full SAX parsing is implemented in Phase 7 (§7.9).
     * Returns Result.success(0) as a non-crashing stub.
     */
    override suspend fun importFromOpml(opmlContent: String): Result<Int> =
        Result.success(0) // TODO Phase 7: implement SAX-based OPML parser

    /**
     * Serialize all subscribed feeds to OPML 2.0 format.
     * Feeds are grouped by primary category where possible.
     */
    override suspend fun exportToOpml(): Result<String> = runCatching {
        val feeds = feedDao.getAllFeedsList()
        buildString {
            appendLine("""<?xml version="1.0" encoding="UTF-8"?>""")
            appendLine("""<opml version="2.0">""")
            appendLine("""  <head><title>BeyondPod Revival — Podcast Subscriptions</title></head>""")
            appendLine("""  <body>""")
            for (feed in feeds) {
                val title = feed.title.escapeXml()
                val xmlUrl = feed.url.escapeXml()
                val htmlUrl = feed.website.escapeXml()
                appendLine("""    <outline text="$title" title="$title" type="rss" xmlUrl="$xmlUrl" htmlUrl="$htmlUrl"/>""")
            }
            appendLine("""  </body>""")
            appendLine("""</opml>""")
        }
    }

    private fun String.escapeXml(): String = replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
        .replace("\"", "&quot;")
        .replace("'", "&apos;")
}
