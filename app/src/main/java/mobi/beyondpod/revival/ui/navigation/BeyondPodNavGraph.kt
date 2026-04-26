package mobi.beyondpod.revival.ui.navigation

import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.navArgument
import mobi.beyondpod.revival.ui.player.PlaybackViewModel
import mobi.beyondpod.revival.ui.screens.addfeed.AddFeedScreen
import mobi.beyondpod.revival.ui.screens.search.PodcastSearchScreen
import mobi.beyondpod.revival.ui.screens.category.CategoryManagementScreen
import mobi.beyondpod.revival.ui.screens.feeddetail.FeedDetailScreen
import mobi.beyondpod.revival.ui.screens.feedlist.FeedListScreen
import mobi.beyondpod.revival.ui.screens.myepisodes.MyEpisodesScreen
import mobi.beyondpod.revival.ui.screens.player.EpisodeNotesScreen
import mobi.beyondpod.revival.ui.screens.player.PlayerScreen
import mobi.beyondpod.revival.ui.screens.playlist.SmartPlaylistDetailScreen
import mobi.beyondpod.revival.ui.screens.playlist.SmartPlaylistListScreen
import mobi.beyondpod.revival.ui.screens.queue.QueueScreen
import mobi.beyondpod.revival.ui.screens.settings.SettingsScreen

@Composable
fun BeyondPodNavGraph(
    navController: NavHostController,
    playbackViewModel: PlaybackViewModel,
    modifier: Modifier = Modifier
) {
    NavHost(
        navController = navController,
        startDestination = Screen.MyEpisodes.route,
        modifier = modifier,
        enterTransition    = { fadeIn(tween(180)) },
        exitTransition     = { fadeOut(tween(120)) },
        popEnterTransition = { fadeIn(tween(180)) },
        popExitTransition  = { fadeOut(tween(120)) }
    ) {
        // ── My Episodes (default landing screen) ─────────────────────────────
        composable(Screen.MyEpisodes.route) {
            MyEpisodesScreen(navController = navController)
        }

        // ── Feed list — all feeds grouped by category [phase-5] ───────────────
        composable(Screen.AllPublished.route) {
            FeedListScreen(navController = navController)
        }

        // ── Feed detail — episodes + settings for one feed [phase-5] ─────────
        composable(
            route = Screen.FeedEpisodes.route,
            arguments = listOf(navArgument(Screen.FeedEpisodes.ARG_FEED_ID) {
                type = NavType.LongType
            })
        ) {
            FeedDetailScreen(navController = navController)
        }

        // ── Add feed [phase-5] ────────────────────────────────────────────────
        composable(Screen.AddFeed.route) {
            AddFeedScreen(navController = navController)
        }

        // ── Category management [phase-5] ─────────────────────────────────────
        composable(Screen.CategoryManagement.route) {
            CategoryManagementScreen(navController = navController)
        }

        // ── Smart playlist list [phase-6] ─────────────────────────────────────
        composable(Screen.SmartPlaylists.route) {
            SmartPlaylistListScreen(navController = navController)
        }

        // ── Smart playlist detail + editor [phase-6] ──────────────────────────
        composable(
            route = Screen.Playlist.route,
            arguments = listOf(navArgument(Screen.Playlist.ARG_PLAYLIST_ID) {
                type = NavType.LongType
            })
        ) {
            SmartPlaylistDetailScreen(navController = navController)
        }

        // ── Active playback queue [phase-6] ───────────────────────────────────
        composable(Screen.Queue.route) {
            QueueScreen(navController = navController)
        }

        // ── Settings [phase-7] ────────────────────────────────────────────────
        composable(Screen.Settings.route) {
            SettingsScreen(navController = navController)
        }

        composable(Screen.DownloadQueue.route) {
            PlaceholderScreen("Download Queue — Phase 7")
        }

        // ── Full player screen ────────────────────────────────────────────────
        composable(Screen.FullPlayer.route) {
            PlayerScreen(
                navController = navController,
                viewModel = playbackViewModel
            )
        }

        // ── Episode show notes ────────────────────────────────────────────────
        composable(
            route = Screen.EpisodeNotes.route,
            arguments = listOf(navArgument(Screen.EpisodeNotes.ARG_EPISODE_ID) {
                type = NavType.LongType
            })
        ) {
            EpisodeNotesScreen(navController = navController)
        }

        // ── Podcast search / discovery ────────────────────────────────────────
        composable(Screen.PodcastSearch.route) {
            PodcastSearchScreen(navController = navController)
        }
    }
}

@Composable
private fun PlaceholderScreen(label: String) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text(label)
    }
}
