package mobi.beyondpod.revival.data.repository

import kotlinx.coroutines.flow.Flow
import mobi.beyondpod.revival.data.local.entity.SyncProvider
import mobi.beyondpod.revival.data.local.entity.SyncStateEntity

interface SyncRepository {
    fun getSyncState(): Flow<SyncStateEntity>

    /**
     * Persist sync configuration and validate credentials.
     * Network validation is stubbed until Phase 7.
     */
    suspend fun configureSyncProvider(
        provider: SyncProvider,
        username: String,
        password: String,
        serverUrl: String? = null
    ): Result<Unit>

    /** Perform a full bidirectional sync (subscriptions + episode actions). Stubbed. */
    suspend fun syncNow(): Result<Unit>

    /** Upload pending subscription changes (ADD/REMOVE). Stubbed. */
    suspend fun uploadSubscriptions(): Result<Unit>

    /** Download remote subscriptions and return their feed URLs. Stubbed. */
    suspend fun downloadSubscriptions(): Result<List<String>>

    /** Upload pending episode play actions for gPodder sync. Stubbed. */
    suspend fun uploadEpisodeActions(): Result<Unit>

    /** Download remote episode actions and apply them locally. Stubbed. */
    suspend fun downloadEpisodeActions(): Result<Unit>
}
