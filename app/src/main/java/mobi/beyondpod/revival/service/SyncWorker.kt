package mobi.beyondpod.revival.service

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import mobi.beyondpod.revival.data.repository.SyncRepository

/**
 * Performs a gpodder.net / Nextcloud background sync.
 * Full implementation in Phase 7. Stub delegates to [SyncRepository.syncNow].
 */
@HiltWorker
class SyncWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
    private val syncRepository: SyncRepository
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        return syncRepository.syncNow()
            .fold(
                onSuccess = { Result.success() },
                onFailure = { if (runAttemptCount < 3) Result.retry() else Result.failure() }
            )
    }
}
