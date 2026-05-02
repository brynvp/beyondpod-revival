package mobi.beyondpod.revival.data.repository

import android.content.Context
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.text.Html
import androidx.documentfile.provider.DocumentFile
import com.google.gson.Gson
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import mobi.beyondpod.revival.data.local.dao.CategoryDao
import mobi.beyondpod.revival.data.local.dao.EpisodeDao
import mobi.beyondpod.revival.data.local.dao.FeedDao
import mobi.beyondpod.revival.data.local.dao.SmartPlaylistDao
import mobi.beyondpod.revival.data.local.entity.BlockSource
import mobi.beyondpod.revival.data.local.entity.EpisodeEntity
import mobi.beyondpod.revival.data.local.entity.FeedCategoryCrossRef
import mobi.beyondpod.revival.data.local.entity.CategoryEntity
import mobi.beyondpod.revival.data.local.entity.DownloadStateEnum
import mobi.beyondpod.revival.data.local.entity.DownloadStrategy
import mobi.beyondpod.revival.data.local.entity.FeedEntity
import mobi.beyondpod.revival.data.local.entity.PlayState
import mobi.beyondpod.revival.data.local.entity.PlaylistRuleMode
import mobi.beyondpod.revival.data.local.entity.SmartPlaylistBlock
import mobi.beyondpod.revival.data.parser.FeedParser
import mobi.beyondpod.revival.data.parser.ParsedEpisode
import mobi.beyondpod.revival.data.parser.ParsedFeed
import okhttp3.OkHttpClient
import okhttp3.Request
import javax.inject.Inject

class FeedRepositoryImpl @Inject constructor(
    private val feedDao: FeedDao,
    private val episodeDao: EpisodeDao,
    private val episodeRepository: EpisodeRepository,
    private val categoryDao: CategoryDao,
    private val okHttpClient: OkHttpClient,
    private val feedParser: FeedParser,
    private val smartPlaylistDao: SmartPlaylistDao,
    private val gson: Gson,
    @ApplicationContext private val context: Context
) : FeedRepository {

    override fun getAllFeeds(): Flow<List<FeedEntity>> = feedDao.getAllFeeds()

    override fun getFeedsByCategory(categoryId: Long): Flow<List<FeedEntity>> =
        feedDao.getFeedsByCategory(categoryId)

    override suspend fun getFeedById(id: Long): FeedEntity? = feedDao.getFeedById(id)

    override suspend fun subscribeToFeed(url: String): Result<FeedEntity> = runCatching {
        val existing = feedDao.getFeedByUrl(url)
        if (existing != null) return@runCatching existing

        val parsed = fetchAndParse(url).getOrNull()?.first
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

    override suspend fun clearStaleUpdateFailedFlags() {
        feedDao.clearAllUpdateFailedFlags()
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
     *
     * [markFailure] = true (default): persists lastUpdateFailed=true on error so the Feeds
     * screen shows the red ⚠ indicator. Use true for manual pull-to-refresh only.
     * [markFailure] = false: background worker calls — transient failures are silent so the
     * warning icon isn't stamped on every feed if the device is temporarily offline.
     *
     * On redirect (301/302), persists the final URL so subsequent refreshes don't re-chase it.
     */
    override suspend fun refreshFeed(id: Long, markFailure: Boolean): Result<Unit> {
        val feed = feedDao.getFeedById(id) ?: return Result.success(Unit)
        val fetchResult = fetchAndParse(feed.url)

        if (fetchResult.isFailure) {
            val errorMsg = fetchResult.exceptionOrNull()?.message ?: "Update failed"
            if (markFailure) {
                runCatching {
                    feedDao.upsertFeed(feed.copy(
                        lastUpdateFailed = true,
                        lastUpdateError  = errorMsg
                    ))
                }
            }
            return Result.failure(fetchResult.exceptionOrNull() ?: Exception(errorMsg))
        }

        return runCatching {
            val (parsed, finalUrl) = fetchResult.getOrThrow()

            feedDao.upsertFeed(feed.copy(
                url              = finalUrl,  // persist redirected URL if it changed
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
    }

    override suspend fun refreshAllFeeds(): Result<Unit> = runCatching {
        // markFailure=false: bulk refresh — individual feed failures are transient and should
        // not stamp every feed with a warning. Per-feed errors only surface on single-feed
        // manual pull-to-refresh in FeedDetailScreen (which uses the default markFailure=true).
        feedDao.getAllFeedsList().forEach { feed -> refreshFeed(feed.id, markFailure = false) }
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

        // Cache category name → id so we only hit the DB once per unique category name.
        // Seed with any categories that already exist.
        val categoryCache = mutableMapOf<String, Long>()
        categoryDao.getAllCategoriesList().forEach { categoryCache[it.name] = it.id }

        outlines.forEach { (xmlUrl, title, categoryName) ->
            // Skip duplicates
            val existing = feedDao.getFeedByUrl(xmlUrl)
            val feedId: Long = if (existing != null) {
                existing.id
            } else {
                val id = feedDao.upsertFeed(FeedEntity(url = xmlUrl, title = title.ifEmpty { xmlUrl }))
                count++
                id
            }

            // Wire up category if the OPML outline was nested under a category folder
            if (categoryName != null) {
                val categoryId = categoryCache.getOrPut(categoryName) {
                    categoryDao.upsertCategory(CategoryEntity(name = categoryName))
                }
                // Only insert the cross-ref if this feed isn't already in this category
                val existingRefs = feedDao.getCategoriesForFeed(feedId)
                if (existingRefs.none { it.categoryId == categoryId }) {
                    feedDao.insertFeedCategoryRef(
                        FeedCategoryCrossRef(feedId = feedId, categoryId = categoryId, isPrimary = true)
                    )
                    feedDao.updatePrimaryCategory(feedId, categoryId)
                }
            }
        }
        count
    }

    override suspend fun exportToOpml(): Result<String> = runCatching {
        val feeds     = feedDao.getAllFeedsList()
        val categories = categoryDao.getAllCategoriesList()

        // Map categoryId → category name for quick lookup
        val categoryById = categories.associateBy { it.id }

        // Group feeds: category name → list of feeds; null key = Uncategorized
        val grouped = LinkedHashMap<String?, MutableList<FeedEntity>>()
        grouped[null] = mutableListOf()                     // Uncategorized goes last
        categories.forEach { grouped[it.name] = mutableListOf() }

        feeds.forEach { feed ->
            val catName = feed.primaryCategoryId?.let { categoryById[it]?.name }
            grouped.getOrPut(catName) { mutableListOf() }.add(feed)
        }

        buildString {
            appendLine("""<?xml version="1.0" encoding="UTF-8"?>""")
            appendLine("""<opml version="2.0">""")
            appendLine("""  <head><title>BeyondPod Revival — Podcast Subscriptions</title></head>""")
            appendLine("""  <body>""")
            grouped.forEach { (catName, catFeeds) ->
                if (catFeeds.isEmpty()) return@forEach
                if (catName != null) {
                    appendLine("""    <outline text="${catName.escapeXml()}" title="${catName.escapeXml()}">""")
                    catFeeds.forEach { feed ->
                        val t = feed.title.escapeXml()
                        val x = feed.url.escapeXml()
                        val h = feed.website.escapeXml()
                        appendLine("""      <outline text="$t" title="$t" type="rss" xmlUrl="$x" htmlUrl="$h"/>""")
                    }
                    appendLine("""    </outline>""")
                } else {
                    // Uncategorized feeds — flat outlines at body level
                    catFeeds.forEach { feed ->
                        val t = feed.title.escapeXml()
                        val x = feed.url.escapeXml()
                        val h = feed.website.escapeXml()
                        appendLine("""    <outline text="$t" title="$t" type="rss" xmlUrl="$x" htmlUrl="$h"/>""")
                    }
                }
            }
            appendLine("""  </body>""")
            appendLine("""</opml>""")
        }
    }

    // ── Virtual folder feeds ──────────────────────────────────────────────────

    override suspend fun addFolderFeed(folderUri: String, displayName: String): Result<FeedEntity> = runCatching {
        // Return existing feed if this folder is already subscribed
        feedDao.getFeedByUrl(folderUri)?.let { return@runCatching it }

        val id = feedDao.upsertFeed(FeedEntity(
            url              = folderUri,
            title            = displayName.ifEmpty { "Local Folder" },
            isVirtualFeed    = true,
            virtualFeedFolderPath = folderUri,
            fingerprintType  = -1,
            downloadStrategy = DownloadStrategy.MANUAL   // local files — nothing to auto-download
        ))
        feedDao.getFeedById(id)!!
    }

    override suspend fun scanFolderFeed(feedId: Long): Result<Int> = runCatching {
        val feed = feedDao.getFeedById(feedId) ?: return@runCatching 0
        val folderUriStr = feed.virtualFeedFolderPath ?: return@runCatching 0

        val folder = DocumentFile.fromTreeUri(context, Uri.parse(folderUriStr))
            ?: return@runCatching 0

        var count = 0
        for (file in folder.listFiles()) {
            if (!file.isFile) continue
            val mimeType = file.type ?: continue
            if (!isAudioMimeType(mimeType)) continue

            val fileUriStr = file.uri.toString()

            // Dedup by GUID (the content URI is stable for the same file)
            if (episodeDao.getEpisodeByGuid(fileUriStr, feedId) != null) continue

            val (title, durationMs, artist) = extractAudioMetadata(file.uri)

            episodeDao.upsertEpisode(EpisodeEntity(
                feedId        = feedId,
                guid          = fileUriStr,
                url           = fileUriStr,
                title         = title.ifEmpty { file.name ?: fileUriStr },
                mimeType      = mimeType,
                fileSizeBytes = file.length(),
                duration      = durationMs,
                pubDate       = file.lastModified(),
                author        = artist,
                downloadState = DownloadStateEnum.DOWNLOADED,
                localFilePath = fileUriStr,
                downloadedAt  = file.lastModified()
            ))
            count++
        }
        count
    }

    private fun isAudioMimeType(mimeType: String): Boolean =
        mimeType.startsWith("audio/") ||
        mimeType == "application/ogg" ||
        mimeType == "video/mp4"   // some m4a/AAC files reported as video/mp4

    private fun extractAudioMetadata(uri: Uri): Triple<String, Long, String> {
        return try {
            MediaMetadataRetriever().use { retriever ->
                retriever.setDataSource(context, uri)
                val title  = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_TITLE) ?: ""
                val durMs  = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                                ?.toLongOrNull() ?: 0L
                val artist = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ARTIST)
                                ?: retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ALBUMARTIST)
                                ?: ""
                Triple(title, durMs, artist)
            }
        } catch (e: Exception) {
            Triple("", 0L, "")
        }
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    /**
     * Fetch and parse the RSS at [url]. Returns (ParsedFeed, finalUrl) where finalUrl is the
     * URL OkHttp actually landed on after any 301/302 redirects — may differ from [url].
     */
    private fun fetchAndParse(url: String): Result<Pair<ParsedFeed, String>> = runCatching {
        val req = Request.Builder().url(url).header("User-Agent", "BeyondPodRevival/5.0").build()
        okHttpClient.newCall(req).execute().use { resp ->
            check(resp.isSuccessful) { "HTTP ${resp.code} for $url" }
            val parsed = feedParser.parse(resp.body?.byteStream() ?: error("Empty response body"))
            Pair(parsed, resp.request.url.toString())
        }
    }

    /**
     * Strip HTML tags and decode entities from RSS description fields.
     * content:encoded in particular is always full HTML. Applied once at storage time
     * so every consumer (UI, search, notifications) always sees clean plain text.
     */
    private fun String.stripHtml(): String {
        if (isBlank()) return this
        @Suppress("DEPRECATION")
        return Html.fromHtml(this, Html.FROM_HTML_MODE_COMPACT).toString().trim()
    }

    private fun ParsedEpisode.toEntity(feedId: Long) = EpisodeEntity(
        feedId        = feedId,
        guid          = guid.ifEmpty { url },
        title         = title,
        description   = description.stripHtml(),
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
