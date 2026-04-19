package mobi.beyondpod.revival.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.workDataOf
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import mobi.beyondpod.revival.data.settings.AppSettings
import java.util.concurrent.TimeUnit
import javax.inject.Inject

/**
 * Re-enqueues the periodic [FeedUpdateWorker] after device reboot.
 *
 * Reads AUTO_UPDATE_ENABLED and UPDATE_INTERVAL_HOURS from DataStore before
 * scheduling — if auto-update is disabled in settings, no work is enqueued.
 * Uses goAsync() so the BroadcastReceiver stays alive during the DataStore read.
 */
@AndroidEntryPoint
class BootReceiver : BroadcastReceiver() {

    @Inject lateinit var dataStore: DataStore<Preferences>

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return

        val pendingResult = goAsync()
        CoroutineScope(Dispatchers.IO + SupervisorJob()).launch {
            try {
                val prefs = dataStore.data.first()
                val autoUpdateEnabled = prefs[AppSettings.AUTO_UPDATE_ENABLED] ?: true
                val intervalHours = (prefs[AppSettings.UPDATE_INTERVAL_HOURS] ?: 4).toLong()

                if (autoUpdateEnabled) {
                    val feedUpdateRequest = PeriodicWorkRequestBuilder<FeedUpdateWorker>(
                        repeatInterval = intervalHours,
                        repeatIntervalTimeUnit = TimeUnit.HOURS
                    )
                        .setInputData(workDataOf(FeedUpdateWorker.KEY_FEED_ID to FeedUpdateWorker.ALL_FEEDS))
                        .build()

                    WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                        "feed_update_periodic",
                        ExistingPeriodicWorkPolicy.UPDATE,
                        feedUpdateRequest
                    )
                }
            } finally {
                pendingResult.finish()
            }
        }
    }
}
