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

    data object FeedEpisodes : Screen("feed_episodes/{feedId}") {
        fun createRoute(feedId: Long) = "feed_episodes/$feedId"
        const val ARG_FEED_ID = "feedId"
    }

    data object Playlist : Screen("playlist/{playlistId}") {
        fun createRoute(playlistId: Long) = "playlist/$playlistId"
        const val ARG_PLAYLIST_ID = "playlistId"
    }

    data object FullPlayer : Screen("full_player")
}
