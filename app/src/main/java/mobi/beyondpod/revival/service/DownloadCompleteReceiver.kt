package mobi.beyondpod.revival.service

import android.app.DownloadManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.Uri
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import mobi.beyondpod.revival.data.local.dao.EpisodeDao
import mobi.beyondpod.revival.data.local.entity.DownloadStateEnum
import java.io.File
import javax.inject.Inject

/**
 * Receives [DownloadManager.ACTION_DOWNLOAD_COMPLETE] and updates the episode's
 * download state in the database.
 *
 * The Android [DownloadManager] runs in the system process — the actual download
 * bytes never touch this app's process. When the download finishes (success or
 * failure), the system broadcasts ACTION_DOWNLOAD_COMPLETE with the download ID.
 *
 * We store the system download ID in [EpisodeEntity.downloadId] at enqueue time,
 * so we can look the episode up here and update its state accordingly.
 */
@AndroidEntryPoint
class DownloadCompleteReceiver : BroadcastReceiver() {

    @Inject lateinit var episodeDao: EpisodeDao
    @Inject lateinit var downloadManager: DownloadManager

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != DownloadManager.ACTION_DOWNLOAD_COMPLETE) return
        val dmId = intent.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1L)
        if (dmId == -1L) return

        // goAsync() keeps the BroadcastReceiver alive long enough for the coroutine
        // to complete its DB work before Android reclaims the process.
        val pendingResult = goAsync()
        CoroutineScope(Dispatchers.IO + SupervisorJob()).launch {
            try {
                handleDownloadComplete(dmId)
            } finally {
                pendingResult.finish()
            }
        }
    }

    private suspend fun handleDownloadComplete(dmId: Long) {
        // Find the episode that was downloading with this system download ID
        val episode = episodeDao.getEpisodeByDownloadManagerId(dmId) ?: return

        val cursor = downloadManager.query(DownloadManager.Query().setFilterById(dmId))
        if (!cursor.moveToFirst()) {
            cursor.close()
            return
        }

        val status    = cursor.getInt(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS))
        val localUri  = cursor.getString(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_LOCAL_URI))
        val reason    = cursor.getInt(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_REASON))
        cursor.close()

        when (status) {
            DownloadManager.STATUS_SUCCESSFUL -> {
                // DownloadManager gives us a file:// URI — resolve to absolute path
                val file = localUri
                    ?.let { Uri.parse(it).path }
                    ?.let { File(it) }
                    ?.takeIf { it.exists() }
                if (file != null) {
                    episodeDao.updateDownloadComplete(
                        episode.id,
                        DownloadStateEnum.DOWNLOADED,
                        file.absolutePath,
                        file.length()
                    )
                } else {
                    // File URI resolved but file missing — treat as failure
                    episodeDao.updateDownloadState(episode.id, DownloadStateEnum.FAILED, null)
                }
            }
            DownloadManager.STATUS_FAILED -> {
                // reason codes documented in DownloadManager — logged here for future debugging
                android.util.Log.w(
                    "DownloadCompleteReceiver",
                    "Download failed for episode ${episode.id}, dmId=$dmId, reason=$reason"
                )
                episodeDao.updateDownloadState(episode.id, DownloadStateEnum.FAILED, null)
            }
            // STATUS_PAUSED / STATUS_PENDING / STATUS_RUNNING should not arrive here,
            // but if they do we leave the existing state untouched.
        }
    }
}
