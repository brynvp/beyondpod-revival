package mobi.beyondpod.revival.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.workDataOf
import java.util.concurrent.TimeUnit

/**
 * Re-enqueues the periodic [FeedUpdateWorker] after device reboot.
 * WorkManager is already boot-resilient for API 23+, but belt-and-suspenders
 * ensures the schedule is alive if WorkManager's internal state was cleared.
 */
class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return

        val feedUpdateRequest = PeriodicWorkRequestBuilder<FeedUpdateWorker>(
            repeatInterval = 60,
            repeatIntervalTimeUnit = TimeUnit.MINUTES
        )
            .setInputData(workDataOf(FeedUpdateWorker.KEY_FEED_ID to FeedUpdateWorker.ALL_FEEDS))
            .build()

        WorkManager.getInstance(context).enqueueUniquePeriodicWork(
            "feed_update_periodic",
            ExistingPeriodicWorkPolicy.KEEP,
            feedUpdateRequest
        )
    }
}
