package mobi.beyondpod.revival.ui.screens.backup

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.CloudUpload
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.IosShare
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.navigation.NavController
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BackupRestoreScreen(
    navController: NavController,
    viewModel: BackupViewModel = hiltViewModel()
) {
    val state by viewModel.uiState.collectAsState()
    val context = LocalContext.current
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()

    // ── Pending content refs: hold the staged string so it's available when
    //    the ActivityResult callback fires (which is after re-composition). ──────
    var pendingOpmlContent by remember { mutableStateOf<String?>(null) }
    var pendingSettingsContent by remember { mutableStateOf<String?>(null) }

    // ── File picker launchers ────────────────────────────────────────────────────

    // OPML export — save file
    val opmlExportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("text/xml")
    ) { uri: Uri? ->
        uri ?: return@rememberLauncherForActivityResult
        val content = pendingOpmlContent ?: return@rememberLauncherForActivityResult
        try {
            context.contentResolver.openOutputStream(uri)?.use { it.write(content.toByteArray()) }
        } catch (e: Exception) {
            scope.launch { snackbarHostState.showSnackbar("Write failed: ${e.message}") }
        }
        pendingOpmlContent = null
        viewModel.clearPendingOpmlExport()
    }

    // OPML import — pick file
    val opmlImportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        uri ?: return@rememberLauncherForActivityResult
        try {
            val content = context.contentResolver.openInputStream(uri)?.use {
                it.readBytes().toString(Charsets.UTF_8)
            } ?: return@rememberLauncherForActivityResult
            viewModel.importOpml(content)
        } catch (e: Exception) {
            scope.launch { snackbarHostState.showSnackbar("Read failed: ${e.message}") }
        }
    }

    // Settings export — save file
    val settingsExportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/json")
    ) { uri: Uri? ->
        uri ?: return@rememberLauncherForActivityResult
        val content = pendingSettingsContent ?: return@rememberLauncherForActivityResult
        try {
            context.contentResolver.openOutputStream(uri)?.use { it.write(content.toByteArray()) }
        } catch (e: Exception) {
            scope.launch { snackbarHostState.showSnackbar("Write failed: ${e.message}") }
        }
        pendingSettingsContent = null
        viewModel.clearPendingSettingsExport()
    }

    // Settings import — pick file
    val settingsImportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        uri ?: return@rememberLauncherForActivityResult
        try {
            val content = context.contentResolver.openInputStream(uri)?.use {
                it.readBytes().toString(Charsets.UTF_8)
            } ?: return@rememberLauncherForActivityResult
            viewModel.importSettings(content)
        } catch (e: Exception) {
            scope.launch { snackbarHostState.showSnackbar("Read failed: ${e.message}") }
        }
    }

    // ── Launch pickers when ViewModel stages content ─────────────────────────────

    LaunchedEffect(state.pendingOpmlExport) {
        val xml = state.pendingOpmlExport
        if (xml != null) {
            pendingOpmlContent = xml
            opmlExportLauncher.launch("subscriptions.opml")
        }
    }

    LaunchedEffect(state.pendingSettingsExport) {
        val json = state.pendingSettingsExport
        if (json != null) {
            pendingSettingsContent = json
            settingsExportLauncher.launch("beyondpod_settings.json")
        }
    }

    // ── Show one-shot messages ────────────────────────────────────────────────────
    LaunchedEffect(state.message) {
        val msg = state.message
        if (msg != null) {
            snackbarHostState.showSnackbar(msg)
            viewModel.clearMessage()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Backup & Restore", fontWeight = FontWeight.SemiBold) },
                navigationIcon = {
                    IconButton(onClick = { navController.popBackStack() }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                }
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) }
    ) { innerPadding ->

        if (state.isLoading) {
            Box(
                Modifier
                    .fillMaxSize()
                    .padding(innerPadding),
                contentAlignment = Alignment.Center
            ) {
                CircularProgressIndicator()
            }
            return@Scaffold
        }

        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {

            // ── Podcast subscriptions ─────────────────────────────────────────
            item {
                SectionHeader("Podcast Subscriptions", Icons.Default.FolderOpen)
            }
            item {
                InfoText(
                    "Export your feed list and categories as an OPML file. " +
                    "This format is compatible with BeyondPod and most other podcast apps."
                )
            }
            item {
                ActionRow(
                    icon = Icons.Default.IosShare,
                    title = "Export subscriptions (OPML)",
                    subtitle = "Save feeds and categories to a file",
                    buttonLabel = "Export",
                    onClick = { viewModel.prepareOpmlExport() }
                )
            }
            item {
                ActionRow(
                    icon = Icons.Default.CloudDownload,
                    title = "Import subscriptions (OPML)",
                    subtitle = "From BeyondPod, Pocket Casts, or any podcast app",
                    buttonLabel = "Import",
                    onClick = { opmlImportLauncher.launch("*/*") }
                )
            }

            item { HorizontalDivider(modifier = Modifier.padding(top = 8.dp)) }

            // ── App settings ──────────────────────────────────────────────────
            item {
                SectionHeader("App Settings", Icons.Default.Settings)
            }
            item {
                InfoText(
                    "Back up your playback, download, and notification preferences. " +
                    "Settings backup only works between installs of this app — " +
                    "it does not include your podcast library or episode data."
                )
            }
            item {
                ActionRow(
                    icon = Icons.Default.CloudUpload,
                    title = "Export settings",
                    subtitle = "Save all app preferences to a file",
                    buttonLabel = "Export",
                    onClick = { viewModel.prepareSettingsExport() }
                )
            }
            item {
                ActionRow(
                    icon = Icons.Default.CloudDownload,
                    title = "Restore settings",
                    subtitle = "Load settings from a previous backup",
                    buttonLabel = "Restore",
                    secondary = true,
                    onClick = { settingsImportLauncher.launch("application/json") }
                )
            }

            item { Spacer(Modifier.height(32.dp)) }
        }
    }
}

// ── Private composables ───────────────────────────────────────────────────────

@Composable
private fun SectionHeader(title: String, icon: ImageVector) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 16.dp, end = 16.dp, top = 20.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(end = 8.dp)
        )
        Text(
            text = title,
            style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold),
            color = MaterialTheme.colorScheme.primary
        )
    }
}

@Composable
private fun InfoText(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)
    )
}

@Composable
private fun ActionRow(
    icon: ImageVector,
    title: String,
    subtitle: String,
    buttonLabel: String,
    secondary: Boolean = false,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            modifier = Modifier
                .size(24.dp)
                .padding(end = 0.dp),
            tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
        )
        Column(
            modifier = Modifier
                .weight(1f)
                .padding(start = 12.dp)
        ) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(
                subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
            )
        }
        if (secondary) {
            OutlinedButton(onClick = onClick) { Text(buttonLabel) }
        } else {
            Button(onClick = onClick) { Text(buttonLabel) }
        }
    }
}
