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

        // ── Stubs for future phases ───────────────────────────────────────────
        composable(Screen.AllPublished.route) {
            PlaceholderScreen("All Published — Phase 5")
        }

        composable(
            route = Screen.FeedEpisodes.route,
            arguments = listOf(navArgument(Screen.FeedEpisodes.ARG_FEED_ID) {
                type = NavType.LongType
            })
        ) {
            PlaceholderScreen("Feed Episodes — Phase 5")
        }

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

        composable(Screen.AddFeed.route) {
            PlaceholderScreen("Add Feed — Phase 5")
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
