package mobi.beyondpod.revival

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.LibraryMusic
import androidx.compose.material.icons.filled.Podcasts
import androidx.compose.material.icons.filled.Queue
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.NavigationDrawerItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.rememberDrawerState
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
import mobi.beyondpod.revival.ui.theme.BeyondPodTheme

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            BeyondPodTheme {
                AppShell()
            }
        }
    }
}

@Composable
fun AppShell() {
    val navController  = rememberNavController()
    val drawerState    = rememberDrawerState(DrawerValue.Closed)
    val coroutineScope = rememberCoroutineScope()

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
            bottomBar = {
                // Mini player is always present (visible only when episode loaded — §7.6)
                MiniPlayer(onTap = { navController.navigate(Screen.FullPlayer.route) })
            }
        ) { innerPadding ->
            BeyondPodNavGraph(
                navController = navController,
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

        // All Published (Phase 5)
        NavigationDrawerItem(
            icon  = { Icon(Icons.Default.Podcasts, contentDescription = null) },
            label = { Text("All Published") },
            selected = currentRoute == Screen.AllPublished.route,
            onClick = {
                navController.navigate(Screen.AllPublished.route)
                onDestinationSelected()
            }
        )

        // Add Feed (Phase 5)
        NavigationDrawerItem(
            icon  = { Icon(Icons.Default.LibraryMusic, contentDescription = null) },
            label = { Text("Add Podcast") },
            selected = false,
            onClick = {
                navController.navigate(Screen.AddFeed.route)
                onDestinationSelected()
            }
        )

        HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))

        // Settings (Phase 7)
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
