package mobi.beyondpod.revival

import android.app.Application
import androidx.hilt.work.HiltWorkerFactory
import androidx.work.Configuration
import dagger.hilt.android.HiltAndroidApp
import mobi.beyondpod.revival.service.PlaybackNotificationManager
import javax.inject.Inject

/**
 * HiltWorkerFactory is injected here so that WorkManager uses it to build HiltWorker instances.
 * WorkManager's default auto-initialisation must be disabled in the manifest (see InitializationProvider).
 */
@HiltAndroidApp
class BeyondPodApp : Application(), Configuration.Provider {

    @Inject lateinit var workerFactory: HiltWorkerFactory
    @Inject lateinit var notificationManager: PlaybackNotificationManager

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder()
            .setWorkerFactory(workerFactory)
            .build()

    override fun onCreate() {
        super.onCreate()
        notificationManager.createNotificationChannels()
    }
}
