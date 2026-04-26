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
    version = 3,
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
                    .addMigrations(MIGRATION_1_2, MIGRATION_2_3)
                    .setJournalMode(JournalMode.WRITE_AHEAD_LOGGING)
                    .build()
                    .also { widgetInstance = it }
            }
    }
}
