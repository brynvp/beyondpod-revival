package mobi.beyondpod.revival.di

import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import mobi.beyondpod.revival.data.repository.CategoryRepository
import mobi.beyondpod.revival.data.repository.CategoryRepositoryImpl
import mobi.beyondpod.revival.data.repository.DownloadRepository
import mobi.beyondpod.revival.data.repository.DownloadRepositoryImpl
import mobi.beyondpod.revival.data.repository.EpisodeRepository
import mobi.beyondpod.revival.data.repository.EpisodeRepositoryImpl
import mobi.beyondpod.revival.data.repository.FeedRepository
import mobi.beyondpod.revival.data.repository.FeedRepositoryImpl
import mobi.beyondpod.revival.data.repository.PlaylistRepository
import mobi.beyondpod.revival.data.repository.PlaylistRepositoryImpl
import mobi.beyondpod.revival.data.repository.SyncRepository
import mobi.beyondpod.revival.data.repository.SyncRepositoryImpl
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
abstract class RepositoryModule {

    @Binds
    @Singleton
    abstract fun bindFeedRepository(impl: FeedRepositoryImpl): FeedRepository

    @Binds
    @Singleton
    abstract fun bindEpisodeRepository(impl: EpisodeRepositoryImpl): EpisodeRepository

    @Binds
    @Singleton
    abstract fun bindDownloadRepository(impl: DownloadRepositoryImpl): DownloadRepository

    @Binds
    @Singleton
    abstract fun bindCategoryRepository(impl: CategoryRepositoryImpl): CategoryRepository

    @Binds
    @Singleton
    abstract fun bindPlaylistRepository(impl: PlaylistRepositoryImpl): PlaylistRepository

    @Binds
    @Singleton
    abstract fun bindSyncRepository(impl: SyncRepositoryImpl): SyncRepository
}
