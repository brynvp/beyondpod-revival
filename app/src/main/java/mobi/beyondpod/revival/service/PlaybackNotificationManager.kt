package mobi.beyondpod.revival.service

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import androidx.core.content.getSystemService
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Creates and owns the notification channels required for foreground services.
 * Injected into [BeyondPodApp] and called once on app start — channels are idempotent.
 */
@Singleton
class PlaybackNotificationManager @Inject constructor(
    @ApplicationContext private val context: Context
) {
    companion object {
        const val PLAYBACK_CHANNEL_ID  = "beyondpod_playback"
        const val DOWNLOAD_CHANNEL_ID  = "beyondpod_downloads"
        const val UPDATE_CHANNEL_ID    = "beyondpod_updates"
    }

    fun createNotificationChannels() {
        val nm = context.getSystemService<NotificationManager>() ?: return

        nm.createNotificationChannel(
            NotificationChannel(
                PLAYBACK_CHANNEL_ID,
                "Playback",
                NotificationManager.IMPORTANCE_LOW
            ).apply { description = "Media playback controls" }
        )

        nm.createNotificationChannel(
            NotificationChannel(
                DOWNLOAD_CHANNEL_ID,
                "Downloads",
                NotificationManager.IMPORTANCE_LOW
            ).apply { description = "Episode download progress" }
        )

        nm.createNotificationChannel(
            NotificationChannel(
                UPDATE_CHANNEL_ID,
                "Feed Updates",
                NotificationManager.IMPORTANCE_MIN
            ).apply { description = "New episode notifications" }
        )
    }
}
