package mobi.beyondpod.revival.service

/**
 * DownloadWorker has been removed.
 *
 * Downloads are now handled by [android.app.DownloadManager] — the Android system service
 * that runs entirely outside the app process. This eliminates the ANR, responsiveness,
 * and stream-hang issues caused by the previous custom OkHttp streaming implementation.
 *
 * Enqueue point : [mobi.beyondpod.revival.data.repository.DownloadRepositoryImpl.enqueueDownload]
 * Completion    : [DownloadCompleteReceiver] handles ACTION_DOWNLOAD_COMPLETE broadcasts
 *
 * This file is kept as a tombstone so `git log` explains the removal.
 * It can be deleted entirely once the change is confirmed stable.
 */
