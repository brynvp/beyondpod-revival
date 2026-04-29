package mobi.beyondpod.revival

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import kotlinx.coroutines.flow.map
import mobi.beyondpod.revival.data.settings.AppSettings
import javax.inject.Inject
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Podcasts
import androidx.compose.material.icons.automirrored.filled.QueueMusic
import androidx.compose.material.icons.filled.Queue
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.NavigationDrawerItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberDrawerState
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch
import mobi.beyondpod.revival.ui.components.MiniPlayer
import mobi.beyondpod.revival.ui.navigation.BeyondPodNavGraph
import mobi.beyondpod.revival.ui.navigation.Screen
import mobi.beyondpod.revival.ui.player.PlaybackViewModel
import mobi.beyondpod.revival.ui.theme.BeyondPodTheme
import androidx.hilt.navigation.compose.hiltViewModel

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    @Inject lateinit var dataStore: DataStore<Preferences>

    // Android 13+ (API 33): POST_NOTIFICATIONS is a runtime permission.
    // Without it, download progress and playback notifications are silently suppressed.
    private val requestNotificationPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) {
            // Permission granted/denied — no further action needed; we proceed either way.
            // Notifications are informational, not required for core functionality.
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(
                    this, Manifest.permission.POST_NOTIFICATIONS
                ) != PackageManager.PERMISSION_GRANTED
            ) {
                requestNotificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }

        setContent {
            // Read theme preference reactively — "system" | "light" | "dark" (default "system")
            val themePref by dataStore.data
                .map { prefs -> prefs[AppSettings.THEME] ?: "system" }
                .collectAsState(initial = "system")

            val darkTheme = when (themePref) {
                "light"  -> false
                "dark"   -> true
                else     -> isSystemInDarkTheme()   // "system" follows device setting
            }

            BeyondPodTheme(darkTheme = darkTheme) {
                AppShell()
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppShell() {
    val navController     = rememberNavController()
    val drawerState       = rememberDrawerState(DrawerValue.Closed)
    val coroutineScope    = rememberCoroutineScope()
    // Activity-scoped: shared between MiniPlayer and PlayerScreen so they see the same state.
    val playbackViewModel = hiltViewModel<PlaybackViewModel>()

    val currentBackStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = currentBackStackEntry?.destination?.route
    val isFullPlayer = currentRoute == Screen.FullPlayer.route

    val screenTitle = when {
        currentRoute == Screen.MyEpisodes.route         -> "My Episodes"
        currentRoute == Screen.AllPublished.route       -> "Feeds"
        currentRoute == Screen.PodcastSearch.route      -> "Search Podcasts"
        currentRoute == Screen.AddFeed.route            -> "Add Podcast"
        currentRoute == Screen.CategoryManagement.route -> "Manage Categories"
        currentRoute == Screen.SmartPlaylists.route     -> "Playlists"
        currentRoute == Screen.Queue.route              -> "Queue"
        currentRoute == Screen.Settings.route           -> "Settings"
        currentRoute == Screen.FullPlayer.route         -> "Now Playing"
        currentRoute?.startsWith("feed_episodes/") == true  -> "Episodes"
        currentRoute?.startsWith("playlist/") == true       -> "Playlist"
        currentRoute?.startsWith("episode_notes/") == true  -> "Episode Notes"
        else                                            -> "BeyondPod"
    }

    ModalNavigationDrawer(
        drawerState = drawerState,
        drawerContent = {
            ModalDrawerSheet {
                BeyondPodDrawerContent(
                    navController = navController,
                    onDestinationSelected = {
                        coroutineScope.launch { drawerState.close() }
                    }
                )
            }
        }
    ) {
        Scaffold(
            modifier = Modifier.fillMaxSize(),
            topBar = {
                // Hidden on FullPlayer so PlayerScreen can own its own full-screen header
                if (!isFullPlayer) {
                    TopAppBar(
                        title = { Text(screenTitle) },
                        navigationIcon = {
                            IconButton(onClick = { coroutineScope.launch { drawerState.open() } }) {
                                Icon(Icons.Default.Menu, contentDescription = "Open menu")
                            }
                        },
                        actions = {
                            // Search icon — always reachable without opening the drawer
                            IconButton(onClick = { navController.navigate(Screen.PodcastSearch.route) }) {
                                Icon(Icons.Default.Search, contentDescription = "Search podcasts")
                            }
                        }
                    )
                }
            },
            bottomBar = {
                // Hidden on FullPlayer (no mini-player overlapping the full-screen player)
                if (!isFullPlayer) {
                    MiniPlayer(
                        onTap = { navController.navigate(Screen.FullPlayer.route) },
                        viewModel = playbackViewModel
                    )
                }
            }
        ) { innerPadding ->
            BeyondPodNavGraph(
                navController = navController,
                playbackViewModel = playbackViewModel,
                modifier = Modifier.padding(innerPadding)
            )
        }
    }
}

@Composable
private fun BeyondPodDrawerContent(
    navController: NavController,
    onDestinationSelected: () -> Unit
) {
    val currentRoute = navController.currentBackStackEntryAsState().value?.destination?.route

    Column {
        // Header
        Text(
            text = "BeyondPod",
            style = androidx.compose.material3.MaterialTheme.typography.titleLarge.copy(
                fontWeight = FontWeight.Bold
            ),
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 20.dp)
        )

        HorizontalDivider()

        // My Episodes (default landing)
        NavigationDrawerItem(
            icon  = { Icon(Icons.Default.Queue, contentDescription = null) },
            label = { Text("My Episodes") },
            selected = currentRoute == Screen.MyEpisodes.route,
            onClick = {
                navController.navigate(Screen.MyEpisodes.route) {
                    popUpTo(Screen.MyEpisodes.route) { inclusive = true }
                }
                onDestinationSelected()
            }
        )

        // Feeds
        NavigationDrawerItem(
            icon  = { Icon(Icons.Default.Podcasts, contentDescription = null) },
            label = { Text("Feeds") },
            selected = currentRoute == Screen.AllPublished.route,
            onClick = {
                navController.navigate(Screen.AllPublished.route)
                onDestinationSelected()
            }
        )

        HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))

        // Queue
        NavigationDrawerItem(
            icon  = { Icon(Icons.AutoMirrored.Filled.QueueMusic, contentDescription = null) },
            label = { Text("Queue") },
            selected = currentRoute == Screen.Queue.route,
            onClick = {
                navController.navigate(Screen.Queue.route)
                onDestinationSelected()
            }
        )

        HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))

        // Settings
        NavigationDrawerItem(
            icon  = { Icon(Icons.Default.Settings, contentDescription = null) },
            label = { Text("Settings") },
            selected = currentRoute == Screen.Settings.route,
            onClick = {
                navController.navigate(Screen.Settings.route)
                onDestinationSelected()
            }
        )
    }
}
