package mobi.beyondpod.revival.ui.navigation

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
import mobi.beyondpod.revival.ui.screens.addfeed.AddFeedScreen
import mobi.beyondpod.revival.ui.screens.category.CategoryManagementScreen
import mobi.beyondpod.revival.ui.screens.feeddetail.FeedDetailScreen
import mobi.beyondpod.revival.ui.screens.feedlist.FeedListScreen
import mobi.beyondpod.revival.ui.screens.myepisodes.MyEpisodesScreen

@Composable
fun BeyondPodNavGraph(
    navController: NavHostController,
    modifier: Modifier = Modifier
) {
    NavHost(
        navController = navController,
        startDestination = Screen.MyEpisodes.route,
        modifier = modifier
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

        // ── Stubs for future phases ───────────────────────────────────────────
        composable(
            route = Screen.Playlist.route,
            arguments = listOf(navArgument(Screen.Playlist.ARG_PLAYLIST_ID) {
                type = NavType.LongType
            })
        ) {
            PlaceholderScreen("Playlist — Phase 6")
        }

        composable(Screen.Settings.route) {
            PlaceholderScreen("Settings — Phase 7")
        }

        composable(Screen.DownloadQueue.route) {
            PlaceholderScreen("Download Queue — Phase 5")
        }

        composable(Screen.FullPlayer.route) {
            PlaceholderScreen("Full Player — Phase 6")
        }
    }
}

@Composable
private fun PlaceholderScreen(label: String) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text(label)
    }
}
