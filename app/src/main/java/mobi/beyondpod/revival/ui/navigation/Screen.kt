package mobi.beyondpod.revival.ui.navigation

/** All top-level navigation destinations in the BeyondPod nav graph. */
sealed class Screen(val route: String) {
    data object MyEpisodes          : Screen("my_episodes")
    data object AllPublished        : Screen("all_published")
    data object Settings            : Screen("settings")
    data object AddFeed             : Screen("add_feed")
    data object DownloadQueue       : Screen("download_queue")
    data object CategoryManagement  : Screen("category_management")
    data object SmartPlaylists      : Screen("smart_playlists")
    data object Queue               : Screen("queue")

    data object FeedEpisodes : Screen("feed_episodes/{feedId}?showCategoryPicker={showCategoryPicker}") {
        /** Normal navigation — category picker stays closed. */
        fun createRoute(feedId: Long) = "feed_episodes/$feedId"
        /** Navigate and auto-open the Assign Category dialog on arrival. */
        fun createRoutePickCategory(feedId: Long) = "feed_episodes/$feedId?showCategoryPicker=true"
        const val ARG_FEED_ID = "feedId"
        const val ARG_SHOW_CATEGORY_PICKER = "showCategoryPicker"
    }

    data object Playlist : Screen("playlist/{playlistId}") {
        fun createRoute(playlistId: Long) = "playlist/$playlistId"
        const val ARG_PLAYLIST_ID = "playlistId"
    }

    data object FullPlayer        : Screen("full_player")
    data object PodcastSearch    : Screen("podcast_search")

    data object EpisodeNotes : Screen("episode_notes/{episodeId}") {
        fun createRoute(episodeId: Long) = "episode_notes/$episodeId"
        const val ARG_EPISODE_ID = "episodeId"
    }

    data object BackupRestore  : Screen("backup_restore")
    data object AddFolderFeed  : Screen("add_folder_feed")
}
