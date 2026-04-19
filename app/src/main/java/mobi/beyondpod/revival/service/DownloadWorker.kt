package mobi.beyondpod.revival.service

import android.content.Context
import android.content.pm.ServiceInfo
import androidx.core.app.NotificationCompat
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import mobi.beyondpod.revival.data.local.dao.EpisodeDao
import mobi.beyondpod.revival.data.local.entity.DownloadStateEnum
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream

/**
 * Downloads a single episode audio file.
 *
 * Input:  [KEY_EPISODE_ID] (Long) — the episode DB id.
 * Output: updates episodeDao.downloadState, localFilePath, downloadBytesDownloaded.
 *
 * Partial resume: on retry sends `Range: bytes=N-`; if server returns 206 the download
 * resumes from byte N; if server returns 200 the download restarts from the beginning.
 *
 * Retries up to 3 times with WorkManager exponential backoff.
 */
@HiltWorker
class DownloadWorker @AssistedInject constructor(
    @Assisted private val context: Context,
    @Assisted params: WorkerParameters,
    private val episodeDao: EpisodeDao,
    private val okHttpClient: OkHttpClient
) : CoroutineWorker(context, params) {

    companion object {
        const val KEY_EPISODE_ID   = "episode_id"
        private const val NOTIFICATION_ID = 2001
    }

    override suspend fun doWork(): Result {
        val episodeId = inputData.getLong(KEY_EPISODE_ID, -1L)
        if (episodeId < 0) return Result.failure()

        val episode = episodeDao.getEpisodeById(episodeId) ?: return Result.failure()

        // Build output path: .../podcasts/<feedId>/<safeTitle>.<ext>
        // Preserve the original file extension from the URL rather than hardcoding .mp3,
        // so AAC / OGG / M4A episodes are stored and identified correctly.
        val safeTitle = episode.title.take(64).replace(Regex("[^A-Za-z0-9._\\- ]"), "_")
        val ext = episode.url.substringAfterLast('.').substringBefore('?').lowercase()
            .let { if (it.length in 2..4 && it.all { c -> c.isLetter() }) it else "mp3" }
        val outputDir = File(context.getExternalFilesDir(null), "podcasts/${episode.feedId}")
            .also { it.mkdirs() }
        val outputFile = File(outputDir, "$safeTitle.$ext")

        val resumeFrom = episode.downloadBytesDownloaded.coerceAtLeast(0L)

        val request = Request.Builder()
            .url(episode.url)
            .apply { if (resumeFrom > 0 && outputFile.exists()) addHeader("Range", "bytes=$resumeFrom-") }
            .build()

        return try {
            // setForeground() is inside the try/catch so a ForegroundServiceStartNotAllowedException
            // or other system exception is caught and handled rather than silently aborting the worker.
            setForeground(buildForegroundInfo(episode.title, 0))
            episodeDao.updateDownloadState(episodeId, DownloadStateEnum.DOWNLOADING, null)

            // Run blocking OkHttp I/O on the IO dispatcher — keeps Default thread pool free.
            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                okHttpClient.newCall(request).execute().use { response ->
                    if (!response.isSuccessful && response.code != 206) {
                        episodeDao.updateDownloadState(episodeId, DownloadStateEnum.FAILED, null)
                        return@withContext if (runAttemptCount < 3) Result.retry() else Result.failure()
                    }

                    val body = response.body ?: run {
                        episodeDao.updateDownloadState(episodeId, DownloadStateEnum.FAILED, null)
                        return@withContext Result.failure()
                    }

                    // 206 = server honoured Range header → append; 200 = restart from zero
                    val isPartialResume = response.code == 206
                    val bytesAlreadyWritten = if (isPartialResume) resumeFrom else 0L
                    val contentLength = body.contentLength()
                    val totalBytes = if (contentLength > 0) bytesAlreadyWritten + contentLength else 0L

                    FileOutputStream(outputFile, /* append= */ isPartialResume).use { out ->
                        body.byteStream().use { input ->
                            val buffer = ByteArray(8 * 1024)
                            var bytesWritten = bytesAlreadyWritten
                            var read: Int
                            while (input.read(buffer).also { read = it } != -1) {
                                out.write(buffer, 0, read)
                                bytesWritten += read
                                // Persist resume point every ~512 KB to limit DB writes
                                if (bytesWritten % (512 * 1024) < buffer.size) {
                                    episodeDao.updateDownloadProgress(episodeId, bytesWritten)
                                }
                                val progress = if (totalBytes > 0) {
                                    (bytesWritten * 100 / totalBytes).toInt().coerceIn(0, 100)
                                } else 0
                                setForeground(buildForegroundInfo(episode.title, progress))
                            }
                            // Final bytes-downloaded persist
                            episodeDao.updateDownloadProgress(episodeId, bytesWritten)
                        }
                    }

                    // Use actual on-disk file size, not Content-Length (which may be absent or wrong)
                    episodeDao.updateDownloadComplete(
                        episodeId,
                        DownloadStateEnum.DOWNLOADED,
                        outputFile.absolutePath,
                        outputFile.length()
                    )
                    Result.success()
                }
            }
        } catch (e: Exception) {
            episodeDao.updateDownloadState(episodeId, DownloadStateEnum.FAILED, null)
            if (runAttemptCount < 3) Result.retry() else Result.failure()
        }
    }

    private fun buildForegroundInfo(episodeTitle: String, progress: Int): ForegroundInfo {
        val notification = NotificationCompat
            .Builder(context, PlaybackNotificationManager.DOWNLOAD_CHANNEL_ID)
            .setContentTitle("Downloading")
            .setContentText(episodeTitle)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setProgress(100, progress, progress == 0)
            .setOngoing(true)
            .setSilent(true)
            .build()
        return ForegroundInfo(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
    }
}
