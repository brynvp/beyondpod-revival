package mobi.beyondpod.revival.data.local

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.RoomDatabase.JournalMode
import androidx.room.TypeConverters
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import mobi.beyondpod.revival.data.local.converter.Converters
import mobi.beyondpod.revival.data.local.dao.CategoryDao
import mobi.beyondpod.revival.data.local.dao.EpisodeDao
import mobi.beyondpod.revival.data.local.dao.FeedDao
import mobi.beyondpod.revival.data.local.dao.ManualPlaylistDao
import mobi.beyondpod.revival.data.local.dao.QueueSnapshotDao
import mobi.beyondpod.revival.data.local.dao.SmartPlaylistDao
import mobi.beyondpod.revival.data.local.entity.CategoryEntity
import mobi.beyondpod.revival.data.local.entity.ChangeHistoryEntity
import mobi.beyondpod.revival.data.local.entity.DownloadQueueEntity
import mobi.beyondpod.revival.data.local.entity.EpisodeEntity
import mobi.beyondpod.revival.data.local.entity.EpisodePlayHistoryEntity
import mobi.beyondpod.revival.data.local.entity.FeedCategoryCrossRef
import mobi.beyondpod.revival.data.local.entity.FeedEntity
import mobi.beyondpod.revival.data.local.entity.ManualPlaylistEpisodeCrossRef
import mobi.beyondpod.revival.data.local.entity.QueueSnapshotEntity
import mobi.beyondpod.revival.data.local.entity.QueueSnapshotItemEntity
import mobi.beyondpod.revival.data.local.entity.ScheduledTaskEntity
import mobi.beyondpod.revival.data.local.entity.SmartPlaylistEntity
import mobi.beyondpod.revival.data.local.entity.SyncStateEntity

@Database(
    entities = [
        FeedEntity::class,
        FeedCategoryCrossRef::class,
        EpisodeEntity::class,
        CategoryEntity::class,
        SmartPlaylistEntity::class,
        ManualPlaylistEpisodeCrossRef::class,
        QueueSnapshotEntity::class,
        QueueSnapshotItemEntity::class,
        DownloadQueueEntity::class,
        SyncStateEntity::class,
        EpisodePlayHistoryEntity::class,      // Play event audit log (episode_history)
        ChangeHistoryEntity::class,           // Subscription change log (gPodder sync diffs)
        ScheduledTaskEntity::class            // User-defined scheduled update tasks
    ],
    version = 5,
    exportSchema = true
)
@TypeConverters(Converters::class)
abstract class BeyondPodDatabase : RoomDatabase() {
    abstract fun feedDao(): FeedDao
    abstract fun episodeDao(): EpisodeDao
    abstract fun categoryDao(): CategoryDao
    abstract fun smartPlaylistDao(): SmartPlaylistDao
    abstract fun queueSnapshotDao(): QueueSnapshotDao  // All queue mutations go here
    abstract fun manualPlaylistDao(): ManualPlaylistDao

    companion object {
        const val DATABASE_NAME = "beyondpod.db"

        // ── Migration 1 → 2: add mandatory compound indices (§13) ────────────────
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                // Compound indices for episodes (feed-scoped queries)
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_episodes_feedId_pubDate` ON `episodes` (`feedId`, `pubDate`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_episodes_feedId_playState` ON `episodes` (`feedId`, `playState`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_episodes_feedId_downloadState` ON `episodes` (`feedId`, `downloadState`)")
                // Compound index for ordered snapshot reads
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_queue_snapshot_items_snapshotId_position` ON `queue_snapshot_items` (`snapshotId`, `position`)")
                // Simple indices on feeds
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_feeds_primaryCategoryId` ON `feeds` (`primaryCategoryId`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_feeds_downloadStrategy` ON `feeds` (`downloadStrategy`)")
            }
        }

        // ── Migration 2 → 3: standalone indices for global episode queries ─────────
        val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                // Standalone indices — the compound feedId+X ones exist but global queries
                // (no feedId filter) need these to avoid full table scans.
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_episodes_downloadState` ON `episodes` (`downloadState`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_episodes_downloadedAt` ON `episodes` (`downloadedAt`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_episodes_isStarred` ON `episodes` (`isStarred`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_episodes_lastPlayed` ON `episodes` (`lastPlayed`)")
            }
        }

        // ── Migration 3 → 4: add categoryId FK (ON DELETE CASCADE) to junction table ──
        // SQLite cannot alter FK constraints — must drop-and-recreate the table.
        // CASCADE on categoryId only removes the cross-ref row; the feed itself is untouched.
        val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS `feed_category_cross_ref_new` (
                        `feedId` INTEGER NOT NULL,
                        `categoryId` INTEGER NOT NULL,
                        `isPrimary` INTEGER NOT NULL DEFAULT 1,
                        PRIMARY KEY(`feedId`, `categoryId`),
                        FOREIGN KEY(`feedId`) REFERENCES `feeds`(`id`) ON DELETE CASCADE,
                        FOREIGN KEY(`categoryId`) REFERENCES `categories`(`id`) ON DELETE CASCADE
                    )
                """.trimIndent())
                db.execSQL("INSERT OR IGNORE INTO `feed_category_cross_ref_new` SELECT * FROM `feed_category_cross_ref`")
                db.execSQL("DROP TABLE `feed_category_cross_ref`")
                db.execSQL("ALTER TABLE `feed_category_cross_ref_new` RENAME TO `feed_category_cross_ref`")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_feed_category_cross_ref_feedId` ON `feed_category_cross_ref` (`feedId`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_feed_category_cross_ref_categoryId` ON `feed_category_cross_ref` (`categoryId`)")
            }
        }

        // ── Migration 4 → 5: add virtual folder feed columns to feeds table ────────
        // isVirtualFeed and virtualFeedFolderPath exist in FeedEntity from day one, so
        // fresh installs already have both columns (Room created the full schema at v1).
        // Existing installs that went through v1→4 migrations also have them for the same
        // reason. Guard with PRAGMA table_info to avoid "duplicate column name" crashes.
        val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                val cursor = db.query("PRAGMA table_info(feeds)")
                val existingCols = mutableSetOf<String>()
                while (cursor.moveToNext()) {
                    existingCols.add(cursor.getString(cursor.getColumnIndexOrThrow("name")))
                }
                cursor.close()
                if (!existingCols.contains("isVirtualFeed")) {
                    db.execSQL("ALTER TABLE feeds ADD COLUMN isVirtualFeed INTEGER NOT NULL DEFAULT 0")
                }
                if (!existingCols.contains("virtualFeedFolderPath")) {
                    db.execSQL("ALTER TABLE feeds ADD COLUMN virtualFeedFolderPath TEXT")
                }
            }
        }

        /**
         * Singleton accessor for non-Hilt contexts (e.g., widget [RemoteViewsFactory]).
         * Hilt's [DatabaseModule] uses all migrations; this accessor does too.
         */
        @Volatile private var widgetInstance: BeyondPodDatabase? = null

        fun getInstance(context: Context): BeyondPodDatabase =
            widgetInstance ?: synchronized(this) {
                widgetInstance ?: Room.databaseBuilder(
                    context.applicationContext,
                    BeyondPodDatabase::class.java,
                    DATABASE_NAME
                )
                    .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5)
                    .setJournalMode(JournalMode.WRITE_AHEAD_LOGGING)
                    .build()
                    .also { widgetInstance = it }
            }
    }
}
