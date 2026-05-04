package mobi.beyondpod.revival.service

/**
 * Lightweight singleton bridge so DownloadRepositoryImpl can check whether a given episode
 * is currently loaded in PlaybackService before soft-deleting its file.
 *
 * Written by PlaybackService.loadAndPlay() as soon as an episode is claimed for playback.
 * Cleared in PlaybackService.onDestroy() and whenever a claim is abandoned (episode row gone,
 * WiFi-only block, no fallback available).
 *
 * Read by DownloadRepositoryImpl.applyRetentionCleanup() to enforce the
 * "never delete the currently-playing episode" guard (FEED_DOWNLOAD_AUDIT Q9).
 *
 * @Volatile ensures visibility across threads (Dispatchers.IO vs Main).
 */
object PlaybackStateHolder {
    @Volatile var currentlyPlayingEpisodeId: Long = -1L
}
