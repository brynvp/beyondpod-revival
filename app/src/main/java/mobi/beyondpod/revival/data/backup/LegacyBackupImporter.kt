package mobi.beyondpod.revival.data.backup

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import dagger.hilt.android.qualifiers.ApplicationContext
import mobi.beyondpod.revival.data.local.dao.CategoryDao
import mobi.beyondpod.revival.data.local.dao.EpisodeDao
import mobi.beyondpod.revival.data.local.dao.FeedDao
import mobi.beyondpod.revival.data.local.dao.QueueSnapshotDao
import mobi.beyondpod.revival.data.local.dao.SmartPlaylistDao
import mobi.beyondpod.revival.data.local.entity.CategoryEntity
import mobi.beyondpod.revival.data.local.entity.DownloadStateEnum
import mobi.beyondpod.revival.data.local.entity.EpisodeEntity
import mobi.beyondpod.revival.data.local.entity.FeedCategoryCrossRef
import mobi.beyondpod.revival.data.local.entity.FeedEntity
import mobi.beyondpod.revival.data.local.entity.PlayState
import mobi.beyondpod.revival.data.local.entity.QueueSnapshotEntity
import mobi.beyondpod.revival.data.local.entity.QueueSnapshotItemEntity
import mobi.beyondpod.revival.data.repository.EpisodeRepository
import java.io.File
import java.io.InputStream
import java.util.zip.ZipInputStream
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Imports legacy BeyondPod 4.x `.bpbak` files.
 *
 * Legacy archive structure (ZIP):
 * - `beyondpod.db.autobak` — SQLite database with feeds/tracks/categories/smartplaylists
 * - `*.xml` — SharedPreferences XML (settings, ignored on import)
 * - `PlayList.bin.autobak` — ASCII file paths, one per line, = active queue at backup time
 *
 * **Detection**: presence of `beyondpod.db.autobak` distinguishes legacy from Revival format.
 *
 * Legacy speed table (feeds.audioSettings = "speedIndex|"):
 * Index  0    1    2    3    4    5    6    7    8    9    10   11    12   13   14
 * Speed 0.5  0.6  0.7  0.8  0.9  1.0  1.1  1.2  1.3  1.4  1.5  1.75  2.0  2.5  3.0
 */
@Singleton
class LegacyBackupImporter @Inject constructor(
    @ApplicationContext private val context: Context,
    private val feedDao: FeedDao,
    private val episodeDao: EpisodeDao,
    private val categoryDao: CategoryDao,
    private val smartPlaylistDao: SmartPlaylistDao,
    private val queueSnapshotDao: QueueSnapshotDao,
    private val episodeRepository: EpisodeRepository
) {

    companion object {
        private val SPEED_TABLE = floatArrayOf(
            0.5f, 0.6f, 0.7f, 0.8f, 0.9f, 1.0f,
            1.1f, 1.2f, 1.3f, 1.4f, 1.5f, 1.75f, 2.0f, 2.5f, 3.0f
        )
    }

    suspend fun importFrom(inputStream: InputStream): Result<RevivalBackupManager.ImportSummary> =
        runCatching {
            val entries = readZipToTempFiles(inputStream)
            val dbFile = entries["beyondpod.db.autobak"]
                ?: error("Not a legacy BeyondPod backup (beyondpod.db.autobak not found)")
            val playlistBin = entries["PlayList.bin.autobak"]

            var feedCount = 0; var categoryCount = 0

            SQLiteDatabase.openDatabase(
                dbFile.absolutePath, null, SQLiteDatabase.OPEN_READONLY
            ).use { db ->

                // ── 1. Import categories ─────────────────────────────────────
                val categoryNameToId = mutableMapOf<String, Long>()
                runCatching {
                    db.rawQuery("SELECT * FROM categories", null).use { cursor ->
                        while (cursor.moveToNext()) {
                            // Legacy format: a row may contain a pipe-delimited string "name^sortOrder|"
                            // or the table may have name and sortOrder columns directly.
                            val rawName = runCatching {
                                cursor.getString(cursor.getColumnIndexOrThrow("name"))
                            }.getOrNull() ?: continue

                            // Handle "name^sortOrder|" format
                            val entries2 = rawName.split("|").filter { it.isNotBlank() }
                            val names = if (entries2.size > 1) {
                                entries2.map { it.split("^").first().trim() }
                            } else {
                                listOf(rawName.trim())
                            }
                            names.forEachIndexed { idx, name ->
                                if (name.isNotEmpty() && name !in categoryNameToId) {
                                    val cat = CategoryEntity(name = name, sortOrder = idx)
                                    val id = categoryDao.upsertCategory(cat)
                                    categoryNameToId[name] = id
                                    categoryCount++
                                }
                            }
                        }
                    }
                }

                // ── 2. Import feeds ──────────────────────────────────────────
                val legacyFeedIdToNew = mutableMapOf<Long, Long>()
                runCatching {
                    db.rawQuery("SELECT * FROM feeds", null).use { cursor ->
                        while (cursor.moveToNext()) {
                            val legacyId = cursor.getLong(cursor.getColumnIndexOrThrow("id"))
                            val url = cursor.getStringSafe("url") ?: continue
                            val title = cursor.getStringSafe("title") ?: url
                            val description = cursor.getStringSafe("description") ?: ""
                            val imageUrl = cursor.getStringSafe("imageUrl")
                            val author = cursor.getStringSafe("author") ?: ""
                            val website = cursor.getStringSafe("website") ?: ""
                            val language = cursor.getStringSafe("language") ?: ""

                            // audioSettings = "speedIndex|" → parse index
                            val audioSettings = cursor.getStringSafe("audioSettings") ?: ""
                            val speedIndex = audioSettings.split("|").firstOrNull()?.toIntOrNull() ?: -1

                            // category = "Primary|Secondary" pipe string
                            val categoryStr = cursor.getStringSafe("category") ?: ""
                            val categoryNames = categoryStr.split("|")
                                .map { it.trim() }.filter { it.isNotEmpty() }

                            val feed = FeedEntity(
                                url                    = url,
                                title                  = title,
                                description            = description,
                                imageUrl               = imageUrl,
                                author                 = author,
                                website                = website,
                                language               = language,
                                audioSettingsSpeedIndex = speedIndex,
                                lastUpdated            = System.currentTimeMillis()
                            )
                            val newFeedId = feedDao.upsertFeed(feed)
                            legacyFeedIdToNew[legacyId] = newFeedId
                            feedCount++

                            // Wire category cross-refs (max 2 per feed)
                            categoryNames.take(2).forEachIndexed { idx, catName ->
                                val catId = resolveCategoryName(catName, categoryNameToId)
                                if (catId != null) {
                                    val isPrimary = (idx == 0)
                                    runCatching {
                                        feedDao.insertFeedCategoryRef(
                                            FeedCategoryCrossRef(newFeedId, catId, isPrimary)
                                        )
                                        if (isPrimary) feedDao.updatePrimaryCategory(newFeedId, catId)
                                        else           feedDao.updateSecondaryCategory(newFeedId, catId)
                                    }
                                }
                            }
                        }
                    }
                }

                // ── 3. Import episodes (tracks) ──────────────────────────────
                // Dedup follows CLAUDE.md rule #5: GUID → URL → Title+Duration → file hash
                val urlToNewEpisodeId = mutableMapOf<String, Long>()
                runCatching {
                    db.rawQuery("SELECT * FROM tracks", null).use { cursor ->
                        while (cursor.moveToNext()) {
                            val legacyFeedId = cursor.getLong(cursor.getColumnIndexOrThrow("feedId"))
                            val feedId = legacyFeedIdToNew[legacyFeedId] ?: return@use

                            val guid = cursor.getStringSafe("guid") ?: ""
                            val url = cursor.getStringSafe("url") ?: continue
                            val title = cursor.getStringSafe("title") ?: url
                            val description = cursor.getStringSafe("description") ?: ""
                            val pubDateSec = cursor.getLongSafe("pubDate") ?: 0L
                            // duration and playedTime are in seconds in legacy DB
                            val durationSec = cursor.getLongSafe("totalTime") ?: 0L
                            val playedTimeSec = cursor.getLongSafe("playedTime") ?: 0L
                            val played = (cursor.getIntSafe("played") ?: 0) == 1
                            val isStarred = (cursor.getIntSafe("isStarred") ?: 0) == 1
                            val isProtected = (cursor.getIntSafe("isProtected") ?: 0) == 1
                            val localFilePath = cursor.getStringSafe("localFilePath")

                            // Map legacy play state
                            val playState = when {
                                played                   -> PlayState.PLAYED
                                playedTimeSec > 0L       -> PlayState.IN_PROGRESS
                                else                     -> PlayState.NEW
                            }

                            val downloadState = if (localFilePath != null && File(localFilePath).exists())
                                DownloadStateEnum.DOWNLOADED else DownloadStateEnum.NOT_DOWNLOADED

                            val episode = EpisodeEntity(
                                feedId       = feedId,
                                guid         = guid.ifEmpty { url },
                                title        = title,
                                description  = description,
                                pubDate      = pubDateSec * 1000L,
                                url          = url,
                                duration     = durationSec * 1000L,
                                playState    = playState,
                                playPosition = playedTimeSec * 1000L,
                                isStarred    = isStarred,
                                isProtected  = isProtected,
                                localFilePath = localFilePath,
                                downloadState = downloadState
                            )
                            val newId = episodeRepository.upsertEpisode(episode)
                            urlToNewEpisodeId[url] = newId
                        }
                    }
                }

                // ── 4. Import queue from PlayList.bin.autobak ────────────────
                if (playlistBin != null) {
                    val filePaths = playlistBin.readLines(Charsets.US_ASCII)
                        .filter { it.startsWith("/") && it.length > 10 }
                    buildQueueFromFilePaths(filePaths)
                }
            }

            // Clean up temp files
            entries.values.forEach { it.delete() }

            RevivalBackupManager.ImportSummary(
                feeds      = feedCount,
                categories = categoryCount,
                playlists  = 0
            )
        }

    // ── Helpers ───────────────────────────────────────────────────────────────

    /**
     * 4-step Category Name Resolution (spec §7.9):
     * 1. Exact match
     * 2. Case-insensitive match
     * 3. Normalized (trim + tab→space) case-insensitive
     * 4. Fuzzy: contains, only if exactly 1 match
     */
    private fun resolveCategoryName(name: String, map: Map<String, Long>): Long? {
        map[name]?.let { return it }                                      // 1. exact
        map.entries.find { it.key.equals(name, ignoreCase = true) }
            ?.let { return it.value }                                      // 2. case-insensitive
        val norm = name.trim().replace("\t", " ")
        map.entries.find { it.key.trim().replace("\t", " ").equals(norm, ignoreCase = true) }
            ?.let { return it.value }                                      // 3. normalized
        val matches = map.entries.filter { it.key.contains(name, ignoreCase = true) }
        return if (matches.size == 1) matches.first().value else null      // 4. fuzzy, unique only
    }

    private suspend fun buildQueueFromFilePaths(filePaths: List<String>) {
        val items = filePaths.mapIndexedNotNull { index, path ->
            val ep = episodeDao.getEpisodeByLocalPath(path) ?: return@mapIndexedNotNull null
            val feed = feedDao.getFeedById(ep.feedId)
            QueueSnapshotItemEntity(
                snapshotId           = 0L,
                episodeId            = ep.id,
                position             = index,
                episodeTitleSnapshot = ep.title,
                feedTitleSnapshot    = feed?.title ?: "",
                localFilePathSnapshot = ep.localFilePath,
                episodeUrlSnapshot   = ep.url
            )
        }
        if (items.isEmpty()) return
        val snapshot = QueueSnapshotEntity(isActive = true, currentItemIndex = 0, currentItemPositionMs = 0L)
        queueSnapshotDao.replaceActiveSnapshot(snapshot, items)
    }

    private fun readZipToTempFiles(inputStream: InputStream): Map<String, File> {
        val result = mutableMapOf<String, File>()
        ZipInputStream(inputStream.buffered()).use { zip ->
            var entry = zip.nextEntry
            while (entry != null) {
                if (!entry.isDirectory) {
                    val name = File(entry.name).name  // strip path components
                    val tmp = File.createTempFile("bpbak_", "_$name", context.cacheDir)
                    tmp.outputStream().use { zip.copyTo(it) }
                    result[entry.name] = tmp
                    result[name] = tmp  // also index by basename
                }
                zip.closeEntry()
                entry = zip.nextEntry
            }
        }
        return result
    }

    // Extension helpers for Cursor safe access
    private fun android.database.Cursor.getStringSafe(col: String): String? {
        val idx = getColumnIndex(col)
        return if (idx >= 0 && !isNull(idx)) getString(idx) else null
    }

    private fun android.database.Cursor.getLongSafe(col: String): Long? {
        val idx = getColumnIndex(col)
        return if (idx >= 0 && !isNull(idx)) getLong(idx) else null
    }

    private fun android.database.Cursor.getIntSafe(col: String): Int? {
        val idx = getColumnIndex(col)
        return if (idx >= 0 && !isNull(idx)) getInt(idx) else null
    }
}
