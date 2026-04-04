package mobi.beyondpod.revival.data.repository

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import mobi.beyondpod.revival.data.local.dao.SmartPlaylistDao
import mobi.beyondpod.revival.data.local.entity.SyncProvider
import mobi.beyondpod.revival.data.local.entity.SyncStateEntity
import javax.inject.Inject

/**
 * Stub implementation — network calls (gPodder, Nextcloud) are implemented in Phase 7.
 * Local sync state is persisted in Room (SyncStateEntity, singleton row id=1).
 */
class SyncRepositoryImpl @Inject constructor(
    private val playlistDao: SmartPlaylistDao   // placeholder; real impl needs SyncStateDao
) : SyncRepository {

    // Phase 7: replace with SyncStateDao.getSyncState()
    override fun getSyncState(): Flow<SyncStateEntity> = flow {
        emit(SyncStateEntity())
    }

    override suspend fun configureSyncProvider(
        provider: SyncProvider,
        username: String,
        password: String,
        serverUrl: String?
    ): Result<Unit> = Result.failure(NotImplementedError("Sync implemented in Phase 7"))

    override suspend fun syncNow(): Result<Unit> =
        Result.failure(NotImplementedError("Sync implemented in Phase 7"))

    override suspend fun uploadSubscriptions(): Result<Unit> =
        Result.failure(NotImplementedError("Sync implemented in Phase 7"))

    override suspend fun downloadSubscriptions(): Result<List<String>> =
        Result.failure(NotImplementedError("Sync implemented in Phase 7"))

    override suspend fun uploadEpisodeActions(): Result<Unit> =
        Result.failure(NotImplementedError("Sync implemented in Phase 7"))

    override suspend fun downloadEpisodeActions(): Result<Unit> =
        Result.failure(NotImplementedError("Sync implemented in Phase 7"))
}
