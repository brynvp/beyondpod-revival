package mobi.beyondpod.revival

import android.app.Application
import androidx.hilt.work.HiltWorkerFactory
import androidx.work.Configuration
import coil3.ImageLoader
import coil3.PlatformContext
import coil3.SingletonImageLoader
import coil3.disk.DiskCache
import coil3.memory.MemoryCache
import okio.Path.Companion.toOkioPath
import coil3.request.crossfade
import dagger.hilt.android.HiltAndroidApp
import mobi.beyondpod.revival.service.PlaybackNotificationManager
import javax.inject.Inject

/**
 * HiltWorkerFactory is injected here so that WorkManager uses it to build HiltWorker instances.
 * WorkManager's default auto-initialisation must be disabled in the manifest (see InitializationProvider).
 *
 * Implements [SingletonImageLoader.Factory] so Coil uses a shared image loader with a proper
 * disk cache — podcast artwork is fetched once and cached on-disk across sessions.
 */
@HiltAndroidApp
class BeyondPodApp : Application(), Configuration.Provider, SingletonImageLoader.Factory {

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

    /**
     * Coil singleton image loader — called once and reused for all [AsyncImage] calls.
     *
     * Memory cache: 20% of available RAM  (~40–80 MB on a modern phone)
     * Disk cache:   75 MB in the app's cache dir (cleared by system under storage pressure)
     * Crossfade:    150ms — smooth image appearance without feeling slow
     */
    override fun newImageLoader(context: PlatformContext): ImageLoader =
        ImageLoader.Builder(context)
            .memoryCache {
                MemoryCache.Builder()
                    .maxSizePercent(context, 0.20)
                    .build()
            }
            .diskCache {
                DiskCache.Builder()
                    .directory(cacheDir.resolve("coil_image_cache").toOkioPath())
                    .maxSizeBytes(75 * 1024 * 1024L)
                    .build()
            }
            .crossfade(150)
            .build()
}
