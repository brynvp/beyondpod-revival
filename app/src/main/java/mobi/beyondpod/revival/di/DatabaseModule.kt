package mobi.beyondpod.revival.di

import android.app.Application
import androidx.room.Room
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import mobi.beyondpod.revival.data.local.BeyondPodDatabase
import mobi.beyondpod.revival.data.local.dao.CategoryDao
import mobi.beyondpod.revival.data.local.dao.EpisodeDao
import mobi.beyondpod.revival.data.local.dao.FeedDao
import mobi.beyondpod.revival.data.local.dao.ManualPlaylistDao
import mobi.beyondpod.revival.data.local.dao.QueueSnapshotDao
import mobi.beyondpod.revival.data.local.dao.SmartPlaylistDao
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object DatabaseModule {

    @Provides
    @Singleton
    fun provideDatabase(app: Application): BeyondPodDatabase =
        Room.databaseBuilder(app, BeyondPodDatabase::class.java, BeyondPodDatabase.DATABASE_NAME)
            .addMigrations(BeyondPodDatabase.MIGRATION_1_2)
            .build()

    @Provides
    fun provideFeedDao(db: BeyondPodDatabase): FeedDao = db.feedDao()

    @Provides
    fun provideEpisodeDao(db: BeyondPodDatabase): EpisodeDao = db.episodeDao()

    @Provides
    fun provideCategoryDao(db: BeyondPodDatabase): CategoryDao = db.categoryDao()

    @Provides
    fun provideSmartPlaylistDao(db: BeyondPodDatabase): SmartPlaylistDao = db.smartPlaylistDao()

    @Provides
    fun provideQueueSnapshotDao(db: BeyondPodDatabase): QueueSnapshotDao = db.queueSnapshotDao()

    @Provides
    fun provideManualPlaylistDao(db: BeyondPodDatabase): ManualPlaylistDao = db.manualPlaylistDao()
}
