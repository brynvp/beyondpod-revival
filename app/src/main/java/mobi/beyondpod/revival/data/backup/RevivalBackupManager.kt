package mobi.beyondpod.revival.data.backup

import android.content.Context
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import dagger.hilt.android.qualifiers.ApplicationContext
import mobi.beyondpod.revival.data.local.dao.CategoryDao
import mobi.beyondpod.revival.data.local.dao.EpisodeDao
import mobi.beyondpod.revival.data.local.dao.FeedDao
import mobi.beyondpod.revival.data.local.dao.QueueSnapshotDao
import mobi.beyondpod.revival.data.local.dao.SmartPlaylistDao
import mobi.beyondpod.revival.data.local.entity.CategoryEntity
import mobi.beyondpod.revival.data.local.entity.FeedCategoryCrossRef
import mobi.beyondpod.revival.data.local.entity.FeedEntity
import mobi.beyondpod.revival.data.local.entity.SmartPlaylistEntity
import java.io.InputStream
import java.io.OutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Handles export and import of Revival-native `.bpbak` backup files.
 *
 * Format: ZIP archive containing:
 * - `BackupManifest.txt` — key=value metadata
 * - `feeds.json` — all FeedEntity records
 * - `feed_categories.json` — FeedCategoryCrossRef join table
 * - `categories.json` — all CategoryEntity records
 * - `smart_playlists.json` — all SmartPlaylistEntity records
 * - `episode_states.json` — per-episode play/star/protect state keyed by "feedUrl|guid"
 * - `queue_snapshot.json` — active queue episode URL list
 * - `settings.json` — settings key-value pairs (handled separately by SettingsViewModel)
 */
@Singleton
class RevivalBackupManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val feedDao: FeedDao,
    private val episodeDao: EpisodeDao,
    private val categoryDao: CategoryDao,
    private val smartPlaylistDao: SmartPlaylistDao,
    private val queueSnapshotDao: QueueSnapshotDao,
    private val gson: Gson
) {

    // ── Export ────────────────────────────────────────────────────────────────

    suspend fun exportTo(outputStream: OutputStream): Result<Unit> = runCatching {
        ZipOutputStream(outputStream.buffered()).use { zip ->
            val feeds      = feedDao.getAllFeedsList()
            val categories = categoryDao.getAllCategoriesList()
            val playlists  = smartPlaylistDao.getAllPlaylistsList()

            // Manifest
            zip.addEntry("BackupManifest.txt") {
                appendLine("BeyondPodRevivalVersion=5.0.0")
                appendLine("BackupFormatVersion=1")
                appendLine("BackupDate=${System.currentTimeMillis()}")
                appendLine("FeedCount=${feeds.size}")
            }

            zip.addJsonEntry("feeds.json", feeds)
            zip.addJsonEntry("categories.json", categories)
            zip.addJsonEntry("smart_playlists.json", playlists)

            // FeedCategoryCrossRef — query per feed
            val crossRefs = feeds.flatMap { feedDao.getCategoriesForFeed(it.id) }
            zip.addJsonEntry("feed_categories.json", crossRefs)

            // Episode states — keyed by "feedUrl|guid"
            val episodeStates = buildMap {
                feeds.forEach { feed ->
                    episodeDao.getEpisodesForFeedList(feed.id).forEach { ep ->
                        val key = "${feed.url}|${ep.guid}"
                        put(key, mapOf(
                            "playState"     to ep.playState.name,
                            "playPositionMs" to ep.playPosition,
                            "isStarred"     to ep.isStarred,
                            "isProtected"   to ep.isProtected,
                            "playCount"     to ep.playCount,
                            "playedFraction" to ep.playedFraction
                        ))
                    }
                }
            }
            zip.addJsonEntry("episode_states.json", episodeStates)

            // Active queue
            val activeSnapshot = queueSnapshotDao.getActiveSnapshotOnce()
            val queueUrls = if (activeSnapshot != null) {
                queueSnapshotDao.getSnapshotItemsList(activeSnapshot.id).map { it.episodeUrlSnapshot }
            } else emptyList<String>()
            zip.addJsonEntry("queue_snapshot.json", queueUrls)

            // Settings exported as empty by default — SettingsViewModel handles DataStore
            zip.addJsonEntry("settings.json", emptyMap<String, String>())
        }
    }

    // ── Import ────────────────────────────────────────────────────────────────

    suspend fun importFrom(inputStream: InputStream): Result<ImportSummary> = runCatching {
        var feedCount = 0; var categoryCount = 0; var playlistCount = 0
        val entries = readZipEntries(inputStream)

        // Detect format
        val manifest = entries["BackupManifest.txt"]
        check(manifest != null && manifest.contains("BeyondPodRevivalVersion")) {
            "Not a Revival backup — use LegacyBackupImporter for .bpbak from original BeyondPod"
        }

        // Restore categories first (feeds reference them)
        entries["categories.json"]?.let { json ->
            val type = object : TypeToken<List<CategoryEntity>>() {}.type
            val cats: List<CategoryEntity> = gson.fromJson(json, type)
            cats.forEach { categoryDao.upsertCategory(it.copy(id = 0)) }
            categoryCount = cats.size
        }

        // Restore feeds
        val feedUrlToId = mutableMapOf<String, Long>()
        entries["feeds.json"]?.let { json ->
            val type = object : TypeToken<List<FeedEntity>>() {}.type
            val feeds: List<FeedEntity> = gson.fromJson(json, type)
            feeds.forEach { feed ->
                val id = feedDao.upsertFeed(feed.copy(id = 0, primaryCategoryId = null, secondaryCategoryId = null))
                feedUrlToId[feed.url] = id
                feedCount++
            }
        }

        // Restore feed-category cross refs (re-map IDs)
        entries["feed_categories.json"]?.let { json ->
            val type = object : TypeToken<List<FeedCategoryCrossRef>>() {}.type
            val refs: List<FeedCategoryCrossRef> = gson.fromJson(json, type)
            refs.forEach { ref ->
                runCatching { feedDao.insertFeedCategoryRef(ref) }
            }
        }

        // Restore playlists
        entries["smart_playlists.json"]?.let { json ->
            val type = object : TypeToken<List<SmartPlaylistEntity>>() {}.type
            val pls: List<SmartPlaylistEntity> = gson.fromJson(json, type)
            pls.forEach { pl ->
                smartPlaylistDao.upsertPlaylist(pl.copy(id = 0))
                playlistCount++
            }
        }

        // Restore episode states
        entries["episode_states.json"]?.let { json ->
            val type = object : TypeToken<Map<String, Map<String, Any>>>() {}.type
            val states: Map<String, Map<String, Any>> = gson.fromJson(json, type)
            states.forEach { (key, state) ->
                val (feedUrl, guid) = key.split("|", limit = 2).let {
                    if (it.size == 2) it[0] to it[1] else return@forEach
                }
                val feedId = feedUrlToId[feedUrl] ?: return@forEach
                val ep = episodeDao.getEpisodeByGuid(guid, feedId) ?: return@forEach
                val playState = runCatching {
                    mobi.beyondpod.revival.data.local.entity.PlayState.valueOf(
                        state["playState"] as? String ?: "NEW"
                    )
                }.getOrDefault(mobi.beyondpod.revival.data.local.entity.PlayState.NEW)
                episodeDao.updatePlayState(ep.id, playState)
                (state["playPositionMs"] as? Number)?.toLong()?.let { pos ->
                    episodeDao.updatePlayPosition(ep.id, pos)
                }
                (state["isStarred"] as? Boolean)?.let { episodeDao.updateIsStarred(ep.id, it) }
                (state["isProtected"] as? Boolean)?.let { episodeDao.updateIsProtected(ep.id, it) }
            }
        }

        ImportSummary(feeds = feedCount, categories = categoryCount, playlists = playlistCount)
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private fun ZipOutputStream.addEntry(name: String, content: StringBuilder.() -> Unit) {
        putNextEntry(ZipEntry(name))
        write(buildString(content).toByteArray(Charsets.UTF_8))
        closeEntry()
    }

    private fun ZipOutputStream.addJsonEntry(name: String, obj: Any) {
        putNextEntry(ZipEntry(name))
        write(gson.toJson(obj).toByteArray(Charsets.UTF_8))
        closeEntry()
    }

    private fun readZipEntries(inputStream: InputStream): Map<String, String> {
        val map = mutableMapOf<String, String>()
        ZipInputStream(inputStream.buffered()).use { zip ->
            var entry = zip.nextEntry
            while (entry != null) {
                if (!entry.isDirectory) {
                    map[entry.name] = zip.readBytes().toString(Charsets.UTF_8)
                }
                zip.closeEntry()
                entry = zip.nextEntry
            }
        }
        return map
    }

    data class ImportSummary(val feeds: Int, val categories: Int, val playlists: Int)
}
