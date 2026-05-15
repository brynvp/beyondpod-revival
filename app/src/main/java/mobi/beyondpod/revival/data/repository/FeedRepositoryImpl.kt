package mobi.beyondpod.revival.data.repository

import android.app.DownloadManager
import android.content.Context
import android.content.Intent
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
import mobi.beyondpod.revival.data.local.dao.ManualPlaylistDao
import mobi.beyondpod.revival.data.local.dao.SmartPlaylistDao
import mobi.beyondpod.revival.service.PlaybackService
import mobi.beyondpod.revival.service.PlaybackStateHolder
import mobi.beyondpod.revival.data.local.entity.BlockSource
import mobi.beyondpod.revival.data.local.entity.EpisodeEntity
import mobi.beyondpod.revival.data.local.entity.FeedCategoryCrossRef
import mobi.beyondpod.revival.data.local.entity.CategoryEntity
import mobi.beyondpod.revival.data.local.entity.DownloadStateEnum
import mobi.beyondpod.revival.data.local.entity.DownloadStrategy
import mobi.beyondpod.revival.data.local.entity.FeedEntity
import mobi.beyondpod.revival.data.local.entity.PlayState
import mobi.beyondpod.revival.data.local.entity.PlaylistRuleMode
import mobi.beyondpod.revival.data.local.entity.RuleField
import mobi.beyondpod.revival.data.local.entity.SmartPlaylistBlock
import mobi.beyondpod.revival.data.local.entity.SmartPlaylistRule
import mobi.beyondpod.revival.data.parser.FeedParser
import mobi.beyondpod.revival.data.parser.ParsedEpisode
import mobi.beyondpod.revival.data.parser.ParsedFeed
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import java.util.concurrent.ConcurrentHashMap
import okhttp3.Request
import javax.inject.Inject

/**
 * Per-feed mutex map. Keyed by feed ID — ensures only one refresh (WorkManager worker,
 * ViewModel, or any other caller) processes a given feed at a time. Using ConcurrentHashMap
 * so concurrent getOrPut calls from different coroutines are safe.
 *
 * Stored at class level (not companion) because FeedRepositoryImpl is a @Singleton — one
 * instance for the process lifetime, so the map is effectively process-scoped.
 */
private val feedRefreshLocks = ConcurrentHashMap<Long, Mutex>()

class FeedRepositoryImpl @Inject constructor(
    private val feedDao: FeedDao,
    private val episodeDao: EpisodeDao,
    private val episodeRepository: EpisodeRepository,
    private val categoryDao: CategoryDao,
    private val okHttpClient: OkHttpClient,
    private val feedParser: FeedParser,
    private val smartPlaylistDao: SmartPlaylistDao,
    private val manualPlaylistDao: ManualPlaylistDao,
    private val gson: Gson,
    private val downloadManager: DownloadManager,
    @ApplicationContext private val context: Context
) : FeedRepository {

    override fun getAllFeeds(): Flow<List<FeedEntity>> = feedDao.getAllFeeds()

    override fun getFeedsByCategory(categoryId: Long): Flow<List<FeedEntity>> =
        feedDao.getFeedsByCategory(categoryId)

    override suspend fun getFeedById(id: Long): FeedEntity? = feedDao.getFeedById(id)
    override fun getFeedByIdFlow(id: Long): Flow<FeedEntity?> = feedDao.getFeedByIdFlow(id)

    override suspend fun subscribeToFeed(url: String): Result<FeedEntity> = runCatching {
        // Check the original URL first (fast path — no network required).
        feedDao.getFeedByUrl(url)?.let { return@runCatching it }

        // G4: Reject HTML pages before doing a full RSS parse.
        // HEAD is cheap — no body download. If the server doesn't support HEAD (returns 4xx/5xx),
        // we skip the content-type check and let fetchAndParse fail naturally with a parse error.
        validateFeedContentType(url).getOrElse { e -> throw e }

        val (parsed, finalUrl) = fetchAndParse(url).getOrThrow()

        // Check the redirect-resolved URL. Podcast feeds commonly redirect from a tracking
        // domain (podtrac, feedburner, etc.) to the canonical host. After the first refresh,
        // the stored URL is updated to finalUrl. Re-subscribing with the original URL would
        // not match the stored finalUrl → duplicate feed row. Checking here prevents that.
        if (finalUrl != url) {
            feedDao.getFeedByUrl(finalUrl)?.let { return@runCatching it }
        }

        // Store finalUrl (not the original) so future re-subscribes via the original URL
        // are caught by the finalUrl check above — no duplicate feed created.
        val placeholder = FeedEntity(
            url          = finalUrl,
            title        = parsed.title.ifEmpty { url },
            description  = parsed.description,
            imageUrl     = parsed.imageUrl,
            author       = parsed.author,
            website      = parsed.website,
            language     = parsed.language,
            lastUpdated  = System.currentTimeMillis()
        )
        val id = feedDao.upsertFeed(placeholder)
        val feed = feedDao.getFeedById(id) ?: placeholder.copy(id = id)
        parsed.episodes.forEach { episodeRepository.upsertEpisode(it.toEntity(feed.id)) }
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

        // G12: stop playback if the currently-playing episode belongs to this feed.
        // Must run BEFORE feedDao.deleteFeed() so the episode row still exists for the feedId check.
        val playingId = PlaybackStateHolder.currentlyPlayingEpisodeId
        if (playingId > 0) {
            val playingEp = episodeDao.getEpisodeById(playingId)
            if (playingEp?.feedId == id) {
                context.startService(
                    Intent(context, PlaybackService::class.java).apply {
                        action = PlaybackService.ACTION_STOP_PLAYBACK
                    }
                )
            }
        }

        // G11: cancel any in-flight DownloadManager jobs for this feed's episodes.
        // Must run BEFORE feedDao.deleteFeed() — the CASCADE delete will wipe episode rows,
        // losing their downloadId values and making cancellation impossible afterward.
        val inFlight = episodeDao.getDownloadingEpisodesForFeed(id)
        if (inFlight.isNotEmpty()) {
            val dmIds = inFlight.mapNotNull { it.downloadId }.toLongArray()
            if (dmIds.isNotEmpty()) downloadManager.remove(*dmIds)
        }

        // Pre-fetch all episodes for this feed before the CASCADE wipes the rows.
        // Used for both My Episodes cleanup and local file deletion below.
        val feedEpisodes = episodeDao.getEpisodesForFeedList(id)

        // Clean up manual playlist (My Episodes) cross-refs for this feed's episodes.
        // ManualPlaylistEpisodeCrossRef has no FK on episodeId (intentional — other tables
        // like QueueSnapshotItemEntity also omit the FK for resilience). Without this call,
        // unsubscribing leaves orphaned rows in manual_playlist_episodes pointing at
        // episode IDs that no longer exist, corrupting My Episodes order and item count.
        val episodeIds = feedEpisodes.map { it.id }
        if (episodeIds.isNotEmpty()) {
            manualPlaylistDao.removeEpisodes(episodeIds)
        }

        // Delete local files on-disk
        if (deleteDownloads) {
            feedEpisodes.forEach { ep ->
                ep.localFilePath?.let { java.io.File(it).delete() }
            }
        }

        feedDao.deleteFeed(feed)
        prunePlaylistBlocksForFeed(id)
    }

    /**
     * Remove any playlist rules or blocks that reference [feedId] after feed deletion.
     *
     * - SEQUENTIAL_BLOCKS: removes [SmartPlaylistBlock] entries where source=FEED and sourceId=feedId.
     * - FILTER_RULES (G14): removes [SmartPlaylistRule] conditions where field=FEED_ID and
     *   value=feedId.toString(). If removing the condition leaves a playlist with zero rules,
     *   the playlist is cleared to an empty rule set (playlist itself is preserved so the user
     *   can see it and reconfigure — deleting silently would be surprising).
     *
     * Called after deleting a feed so playlists don't reference a ghost feed.
     */
    private suspend fun prunePlaylistBlocksForFeed(feedId: Long) {
        smartPlaylistDao.getAllPlaylistsList().forEach { playlist ->
            when (playlist.ruleMode) {
                PlaylistRuleMode.SEQUENTIAL_BLOCKS -> {
                    val blocks = runCatching {
                        gson.fromJson(playlist.rulesJson, Array<SmartPlaylistBlock>::class.java).toList()
                    }.getOrElse { return@forEach }
                    val pruned = blocks.filter { it.source != BlockSource.FEED || it.sourceId != feedId }
                    if (pruned.size != blocks.size) {
                        smartPlaylistDao.upsertPlaylist(playlist.copy(rulesJson = gson.toJson(pruned)))
                    }
                }
                PlaylistRuleMode.FILTER_RULES -> {
                    // G14: Remove FEED_ID filter conditions referencing the deleted feed.
                    val rules = runCatching {
                        gson.fromJson(playlist.rulesJson, Array<SmartPlaylistRule>::class.java).toList()
                    }.getOrElse { return@forEach }
                    val pruned = rules.filter { rule ->
                        !(rule.field == RuleField.FEED_ID && rule.value == feedId.toString())
                    }
                    if (pruned.size != rules.size) {
                        // Write pruned rules back. An empty rule set means "match everything" —
                        // better than silently deleting the playlist the user may have customised.
                        smartPlaylistDao.upsertPlaylist(playlist.copy(rulesJson = gson.toJson(pruned)))
                    }
                }
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
        // Per-feed mutex: only one refresh runs at a time for a given feed.
        // Guards against concurrent execution from WorkManager periodic job + manual pull-to-refresh.
        // Without this, two concurrent refreshes of the same feed cause archiveRemovedEpisodes
        // to mark valid episodes as archived and upsertEpisode races to create duplicate rows.
        val lock = feedRefreshLocks.getOrPut(id) { Mutex() }
        return lock.withLock { refreshFeedLocked(id, markFailure) }
    }

    private suspend fun refreshFeedLocked(id: Long, markFailure: Boolean): Result<Unit> {
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

            // G3: Prune old played+undownloaded rows to prevent DB bloat.
            // 180-day cutoff preserves recent history for feed display continuity.
            // Starred, protected, and downloaded episodes are always kept (enforced in DAO).
            val cutoff = System.currentTimeMillis() - (180L * 86_400_000L)
            episodeDao.pruneOldEpisodeRows(id, cutoff)
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
     * G4: Validate that the URL is likely a podcast feed, not a regular webpage.
     *
     * Uses a HEAD request (no body download) to check the Content-Type header.
     * Rejects `text/html` outright — that's a browser page, not a feed.
     * Ambiguous types (text/plain, application/octet-stream, missing header) are allowed
     * through so that [fetchAndParse] can attempt to parse and fail with a clearer SAX error.
     *
     * If the server doesn't support HEAD (returns 4xx/5xx), the check is skipped gracefully
     * — [fetchAndParse] will handle the error on the subsequent GET.
     */
    /**
     * Must be called from a coroutine — dispatches to Dispatchers.IO so the blocking
     * OkHttp execute() call never runs on the main thread.
     */
    private suspend fun validateFeedContentType(url: String): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            val headReq = Request.Builder().url(url)
                .head()
                .header("User-Agent", "BeyondPodRevival/5.0")
                .build()
            okHttpClient.newCall(headReq).execute().use { resp ->
                // Only act on definitive 2xx responses — let non-2xx fall through to fetchAndParse
                if (!resp.isSuccessful) return@runCatching
                val contentType = resp.header("Content-Type") ?: return@runCatching
                check(!contentType.contains("text/html", ignoreCase = true)) {
                    "URL appears to be a website, not a podcast feed. Please check the RSS/Atom feed URL."
                }
            }
        }
    }

    /**
     * Fetch and parse the RSS at [url]. Returns (ParsedFeed, finalUrl) where finalUrl is the
     * URL OkHttp actually landed on after any 301/302 redirects — may differ from [url].
     * Dispatches to Dispatchers.IO — safe to call from any coroutine context.
     */
    private suspend fun fetchAndParse(url: String): Result<Pair<ParsedFeed, String>> = withContext(Dispatchers.IO) {
        runCatching {
            val req = Request.Builder().url(url).header("User-Agent", "BeyondPodRevival/5.0").build()
            okHttpClient.newCall(req).execute().use { resp ->
                check(resp.isSuccessful) { "HTTP ${resp.code} for $url" }
                val parsed = feedParser.parse(resp.body?.byteStream() ?: error("Empty response body"))
                Pair(parsed, resp.request.url.toString())
            }
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
