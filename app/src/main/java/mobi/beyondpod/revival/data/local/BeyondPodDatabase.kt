package mobi.beyondpod.revival.data.local

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
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
    version = 1,
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
    }
}
