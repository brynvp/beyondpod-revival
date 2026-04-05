package mobi.beyondpod.revival.ui.screens.playlist

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Save
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuAnchorType
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.navigation.NavController
import mobi.beyondpod.revival.data.local.entity.BlockEpisodeOrder
import mobi.beyondpod.revival.data.local.entity.BlockSource
import mobi.beyondpod.revival.data.local.entity.EpisodeSortOrder
import mobi.beyondpod.revival.data.local.entity.LogicalConnector
import mobi.beyondpod.revival.data.local.entity.OnEmptyAction
import mobi.beyondpod.revival.data.local.entity.PlaylistRuleMode
import mobi.beyondpod.revival.data.local.entity.RuleField
import mobi.beyondpod.revival.data.local.entity.RuleOperator
import mobi.beyondpod.revival.data.local.entity.SmartPlaylistBlock
import mobi.beyondpod.revival.data.local.entity.SmartPlaylistRule
import mobi.beyondpod.revival.ui.components.EpisodeListItem
import mobi.beyondpod.revival.ui.navigation.Screen

/**
 * Smart playlist detail screen — Episodes tab + Editor tab.
 *
 * **Episodes tab**: live-evaluated episode list. Play button builds a frozen
 * [QueueSnapshotEntity] via [SmartPlaylistDetailViewModel.buildQueueSnapshot]
 * (→ [EpisodeRepository.buildQueueSnapshot] → [QueueSnapshotDao.replaceActiveSnapshot]).
 * No queue state ever touches EpisodeEntity (CLAUDE.md rule #1).
 *
 * **Editor tab**: mode toggle Standard/Advanced (CLAUDE.md rule #3), block/rule
 * editor, and playlist settings. Save persists the updated [SmartPlaylistEntity].
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SmartPlaylistDetailScreen(
    navController: NavController,
    viewModel: SmartPlaylistDetailViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsState()
    val episodes by viewModel.evaluatedEpisodes.collectAsState()
    var selectedTab by remember { mutableIntStateOf(0) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    val name = (uiState as? SmartPlaylistDetailUiState.Success)?.playlist?.name ?: "Playlist"
                    Text(name, fontWeight = FontWeight.SemiBold, maxLines = 1)
                },
                navigationIcon = {
                    IconButton(onClick = { navController.popBackStack() }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    // Save button (always visible in editor tab)
                    if (selectedTab == 1) {
                        IconButton(onClick = viewModel::savePlaylist) {
                            Icon(Icons.Default.Save, contentDescription = "Save")
                        }
                    }
                }
            )
        }
    ) { innerPadding ->
        Box(modifier = Modifier.padding(innerPadding)) {
            when (val state = uiState) {
                is SmartPlaylistDetailUiState.Loading -> {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator()
                    }
                }

                is SmartPlaylistDetailUiState.Error -> {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text(state.message)
                    }
                }

                is SmartPlaylistDetailUiState.Success -> {
                    Column(modifier = Modifier.fillMaxSize()) {
                        TabRow(selectedTabIndex = selectedTab) {
                            Tab(
                                selected = selectedTab == 0,
                                onClick = { selectedTab = 0 },
                                text = { Text("Episodes (${episodes.size})") }
                            )
                            Tab(
                                selected = selectedTab == 1,
                                onClick = { selectedTab = 1 },
                                text = { Text("Editor") }
                            )
                        }

                        when (selectedTab) {
                            0 -> EpisodesTab(
                                episodes = episodes,
                                isDefault = state.playlist.isDefault,
                                onBuildQueue = {
                                    viewModel.buildQueueSnapshot()
                                    navController.navigate(Screen.Queue.route)
                                }
                            )
                            1 -> EditorTab(viewModel = viewModel, isDefault = state.playlist.isDefault)
                        }
                    }
                }
            }
        }
    }
}

// ── Episodes tab ──────────────────────────────────────────────────────────────

@Composable
private fun EpisodesTab(
    episodes: List<mobi.beyondpod.revival.data.local.entity.EpisodeEntity>,
    isDefault: Boolean,
    onBuildQueue: () -> Unit
) {
    LazyColumn(modifier = Modifier.fillMaxSize()) {
        item {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "${episodes.size} episode${if (episodes.size == 1) "" else "s"}",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                    modifier = Modifier.weight(1f)
                )
                // Build queue / Play button
                Button(onClick = onBuildQueue) {
                    Icon(Icons.Default.PlayArrow, contentDescription = null)
                    Spacer(Modifier.width(4.dp))
                    Text("Play")
                }
                Spacer(Modifier.width(8.dp))
                // Regenerate = rebuild with same rules
                IconButton(onClick = onBuildQueue) {
                    Icon(Icons.Default.Refresh, contentDescription = "Regenerate queue")
                }
            }
            HorizontalDivider(thickness = 0.5.dp)
        }

        if (episodes.isEmpty()) {
            item {
                Box(
                    modifier = Modifier.fillMaxWidth().padding(32.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "No episodes match the current rules",
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
                    )
                }
            }
        } else {
            itemsIndexed(episodes, key = { _, ep -> ep.id }) { _, episode ->
                EpisodeListItem(
                    episode = episode,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp)
                )
                HorizontalDivider(modifier = Modifier.padding(start = 76.dp), thickness = 0.5.dp)
            }
        }
    }
}

// ── Editor tab ────────────────────────────────────────────────────────────────

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun EditorTab(
    viewModel: SmartPlaylistDetailViewModel,
    isDefault: Boolean
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp)
    ) {
        // Playlist name
        item {
            Spacer(Modifier.height(12.dp))
            OutlinedTextField(
                value = viewModel.editName,
                onValueChange = viewModel::onNameChange,
                label = { Text("Playlist name") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                enabled = !isDefault  // My Episodes name is fixed
            )
            Spacer(Modifier.height(16.dp))
        }

        // Mode toggle — Standard vs Advanced (CLAUDE.md rule #3)
        item {
            Text(
                text = "Mode",
                style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold),
                color = MaterialTheme.colorScheme.primary
            )
            Spacer(Modifier.height(6.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = if (viewModel.editMode == PlaylistRuleMode.SEQUENTIAL_BLOCKS)
                        "Standard (Sequential Blocks)" else "Advanced (Filter Rules)",
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.weight(1f)
                )
                TextButton(onClick = viewModel::toggleMode) {
                    Text(
                        if (viewModel.editMode == PlaylistRuleMode.SEQUENTIAL_BLOCKS)
                            "Switch to Advanced" else "Switch to Standard"
                    )
                }
            }
            Text(
                text = if (viewModel.editMode == PlaylistRuleMode.SEQUENTIAL_BLOCKS)
                    "Blocks run in sequence — each contributes N episodes from a source."
                else
                    "All rules are evaluated as filters against the full episode pool.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f)
            )
            Spacer(Modifier.height(16.dp))
            HorizontalDivider()
            Spacer(Modifier.height(12.dp))
        }

        // Rules / Blocks section
        if (viewModel.editMode == PlaylistRuleMode.SEQUENTIAL_BLOCKS) {
            item {
                Text(
                    text = "Blocks",
                    style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold),
                    color = MaterialTheme.colorScheme.primary
                )
                Spacer(Modifier.height(8.dp))
            }
            itemsIndexed(viewModel.editBlocks) { index, block ->
                BlockRow(
                    block = block,
                    onUpdate = { viewModel.updateBlock(index, it) },
                    onDelete = { viewModel.removeBlock(index) }
                )
                Spacer(Modifier.height(8.dp))
            }
            item {
                TextButton(
                    onClick = viewModel::addBlock,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(Icons.Default.Add, contentDescription = null)
                    Spacer(Modifier.width(4.dp))
                    Text("Add Block")
                }
                Spacer(Modifier.height(16.dp))
            }
        } else {
            item {
                Text(
                    text = "Rules",
                    style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold),
                    color = MaterialTheme.colorScheme.primary
                )
                Spacer(Modifier.height(8.dp))
            }
            itemsIndexed(viewModel.editRules) { index, rule ->
                RuleRow(
                    rule = rule,
                    index = index,
                    onUpdate = { viewModel.updateRule(index, it) },
                    onDelete = { viewModel.removeRule(index) }
                )
                Spacer(Modifier.height(8.dp))
            }
            item {
                TextButton(
                    onClick = viewModel::addRule,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(Icons.Default.Add, contentDescription = null)
                    Spacer(Modifier.width(4.dp))
                    Text("Add Rule")
                }
                Spacer(Modifier.height(16.dp))
            }
        }

        // Settings section
        item {
            HorizontalDivider()
            Spacer(Modifier.height(12.dp))
            Text(
                text = "Settings",
                style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold),
                color = MaterialTheme.colorScheme.primary
            )
            Spacer(Modifier.height(8.dp))

            // Max items
            OutlinedTextField(
                value = viewModel.editMaxItems,
                onValueChange = viewModel::onMaxItemsChange,
                label = { Text("Max items (0 = unlimited)") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                    keyboardType = androidx.compose.ui.text.input.KeyboardType.Number
                )
            )
            Spacer(Modifier.height(8.dp))

            // Sort order dropdown
            SortOrderDropdown(
                selected = viewModel.editSortOrder,
                onSelect = viewModel::onSortOrderChange
            )
            Spacer(Modifier.height(8.dp))

            // On empty action
            OnEmptyDropdown(
                selected = viewModel.editOnEmptyAction,
                onSelect = viewModel::onOnEmptyActionChange
            )
            Spacer(Modifier.height(8.dp))

            // Auto-play toggle
            SettingsToggleRow(
                label = "Auto-play",
                subtitle = "Start playing when playlist is opened",
                checked = viewModel.editAutoPlay,
                onCheckedChange = viewModel::onAutoPlayChange
            )

            // Continue on complete toggle
            SettingsToggleRow(
                label = "Continue on complete",
                subtitle = "Advance to next episode automatically",
                checked = viewModel.editContinueOnComplete,
                onCheckedChange = viewModel::onContinueOnCompleteChange
            )

            Spacer(Modifier.height(16.dp))

            Button(
                onClick = viewModel::savePlaylist,
                modifier = Modifier.fillMaxWidth()
            ) {
                Icon(Icons.Default.Save, contentDescription = null)
                Spacer(Modifier.width(6.dp))
                Text("Save")
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}

// ── Sequential block row ──────────────────────────────────────────────────────

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun BlockRow(
    block: SmartPlaylistBlock,
    onUpdate: (SmartPlaylistBlock) -> Unit,
    onDelete: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .then(
                Modifier.padding(8.dp)
            )
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                "Block",
                style = MaterialTheme.typography.labelMedium,
                modifier = Modifier.weight(1f)
            )
            IconButton(onClick = onDelete) {
                Icon(
                    Icons.Default.Delete,
                    contentDescription = "Remove block",
                    tint = MaterialTheme.colorScheme.error.copy(alpha = 0.6f)
                )
            }
        }

        // Source picker
        EnumDropdown(
            label = "Source",
            selected = block.source,
            options = BlockSource.entries,
            display = { it.toDisplay() },
            onSelect = { onUpdate(block.copy(source = it)) }
        )
        Spacer(Modifier.height(4.dp))

        // Order picker
        EnumDropdown(
            label = "Order",
            selected = block.order,
            options = BlockEpisodeOrder.entries,
            display = { it.toDisplay() },
            onSelect = { onUpdate(block.copy(order = it)) }
        )
        Spacer(Modifier.height(4.dp))

        // Count field
        OutlinedTextField(
            value = block.count.toString(),
            onValueChange = { v -> v.toIntOrNull()?.let { onUpdate(block.copy(count = it)) } },
            label = { Text("Count") },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                keyboardType = androidx.compose.ui.text.input.KeyboardType.Number
            )
        )
        Spacer(Modifier.height(4.dp))

        // Only downloaded toggle
        SettingsToggleRow(
            label = "Downloaded only",
            subtitle = null,
            checked = block.onlyDownloaded,
            onCheckedChange = { onUpdate(block.copy(onlyDownloaded = it)) }
        )
    }
    HorizontalDivider(thickness = 0.5.dp)
}

// ── Filter rule row ───────────────────────────────────────────────────────────

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun RuleRow(
    rule: SmartPlaylistRule,
    index: Int,
    onUpdate: (SmartPlaylistRule) -> Unit,
    onDelete: () -> Unit
) {
    Column(modifier = Modifier.fillMaxWidth().padding(8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            // AND / OR connector (shown from rule index 1 onward)
            if (index > 0) {
                EnumDropdown(
                    label = "Connector",
                    selected = rule.logicalConnector,
                    options = LogicalConnector.entries,
                    display = { it.name },
                    onSelect = { onUpdate(rule.copy(logicalConnector = it)) },
                    modifier = Modifier.weight(1f)
                )
            } else {
                Text(
                    "Where",
                    style = MaterialTheme.typography.labelMedium,
                    modifier = Modifier.weight(1f)
                )
            }
            IconButton(onClick = onDelete) {
                Icon(
                    Icons.Default.Delete,
                    contentDescription = "Remove rule",
                    tint = MaterialTheme.colorScheme.error.copy(alpha = 0.6f)
                )
            }
        }

        EnumDropdown(
            label = "Field",
            selected = rule.field,
            options = RuleField.entries,
            display = { it.toDisplay() },
            onSelect = { onUpdate(rule.copy(field = it)) }
        )
        Spacer(Modifier.height(4.dp))

        EnumDropdown(
            label = "Operator",
            selected = rule.operator,
            options = RuleOperator.entries,
            display = { it.toDisplay() },
            onSelect = { onUpdate(rule.copy(operator = it)) }
        )
        Spacer(Modifier.height(4.dp))

        OutlinedTextField(
            value = rule.value,
            onValueChange = { onUpdate(rule.copy(value = it)) },
            label = { Text("Value") },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            placeholder = { Text(rule.field.valuePlaceholder()) }
        )
    }
    HorizontalDivider(thickness = 0.5.dp)
}

// ── Shared helpers ────────────────────────────────────────────────────────────

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun <T> EnumDropdown(
    label: String,
    selected: T,
    options: List<T>,
    display: (T) -> String,
    onSelect: (T) -> Unit,
    modifier: Modifier = Modifier
) {
    var expanded by remember { mutableStateOf(false) }
    ExposedDropdownMenuBox(
        expanded = expanded,
        onExpandedChange = { expanded = it },
        modifier = modifier.fillMaxWidth()
    ) {
        OutlinedTextField(
            value = display(selected),
            onValueChange = {},
            readOnly = true,
            label = { Text(label) },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
            modifier = Modifier
                .menuAnchor(MenuAnchorType.PrimaryNotEditable)
                .fillMaxWidth()
        )
        ExposedDropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false }
        ) {
            options.forEach { option ->
                DropdownMenuItem(
                    text = { Text(display(option)) },
                    onClick = { onSelect(option); expanded = false }
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SortOrderDropdown(
    selected: EpisodeSortOrder,
    onSelect: (EpisodeSortOrder) -> Unit
) {
    EnumDropdown(
        label = "Sort order",
        selected = selected,
        options = EpisodeSortOrder.entries,
        display = { it.toDisplay() },
        onSelect = onSelect
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun OnEmptyDropdown(
    selected: OnEmptyAction,
    onSelect: (OnEmptyAction) -> Unit
) {
    EnumDropdown(
        label = "If no episodes match",
        selected = selected,
        options = OnEmptyAction.entries,
        display = { if (it == OnEmptyAction.DO_NOTHING) "Do nothing" else "Play all unplayed" },
        onSelect = onSelect
    )
}

@Composable
private fun SettingsToggleRow(
    label: String,
    subtitle: String?,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(label, style = MaterialTheme.typography.bodyMedium)
            if (subtitle != null) {
                Text(
                    subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
                )
            }
        }
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

// ── Display helpers for enums ─────────────────────────────────────────────────

private fun BlockSource.toDisplay() = when (this) {
    BlockSource.ALL_FEEDS -> "All feeds"
    BlockSource.FEED -> "Feed"
    BlockSource.CATEGORY -> "Category"
}

private fun BlockEpisodeOrder.toDisplay() = when (this) {
    BlockEpisodeOrder.NEWEST -> "Newest first"
    BlockEpisodeOrder.OLDEST -> "Oldest first"
    BlockEpisodeOrder.RANDOM -> "Random"
}

private fun RuleField.toDisplay() = when (this) {
    RuleField.PLAY_STATE -> "Play state"
    RuleField.IS_STARRED -> "Starred"
    RuleField.IS_DOWNLOADED -> "Downloaded"
    RuleField.IS_PROTECTED -> "Protected"
    RuleField.FEED_ID -> "Feed ID"
    RuleField.CATEGORY_ID -> "Category ID"
    RuleField.PUB_DATE -> "Publish date"
    RuleField.DURATION -> "Duration (s)"
    RuleField.IS_IN_MY_EPISODES -> "In My Episodes"
    RuleField.TITLE_CONTAINS -> "Title"
    RuleField.FILE_TYPE -> "File type"
    RuleField.PLAYED_FRACTION -> "Played fraction"
}

private fun RuleField.valuePlaceholder() = when (this) {
    RuleField.PLAY_STATE -> "NEW / IN_PROGRESS / PLAYED / SKIPPED"
    RuleField.IS_STARRED, RuleField.IS_DOWNLOADED,
    RuleField.IS_PROTECTED, RuleField.IS_IN_MY_EPISODES -> "true / false"
    RuleField.FEED_ID, RuleField.CATEGORY_ID -> "ID number"
    RuleField.PUB_DATE -> "YYYY-MM-DD"
    RuleField.DURATION -> "Seconds"
    RuleField.TITLE_CONTAINS -> "Search text"
    RuleField.FILE_TYPE -> "audio / video"
    RuleField.PLAYED_FRACTION -> "0.0 – 1.0"
}

private fun RuleOperator.toDisplay() = when (this) {
    RuleOperator.IS -> "is"
    RuleOperator.IS_NOT -> "is not"
    RuleOperator.IS_BEFORE -> "is before"
    RuleOperator.IS_AFTER -> "is after"
    RuleOperator.CONTAINS -> "contains"
    RuleOperator.DOES_NOT_CONTAIN -> "does not contain"
    RuleOperator.GREATER_THAN -> "greater than"
    RuleOperator.LESS_THAN -> "less than"
}

private fun EpisodeSortOrder.toDisplay() = when (this) {
    EpisodeSortOrder.PUB_DATE_DESC -> "Newest first"
    EpisodeSortOrder.PUB_DATE_ASC -> "Oldest first"
    EpisodeSortOrder.DURATION_DESC -> "Longest first"
    EpisodeSortOrder.DURATION_ASC -> "Shortest first"
    EpisodeSortOrder.FEED_TITLE_ASC -> "Feed A–Z"
    EpisodeSortOrder.FEED_TITLE_DESC -> "Feed Z–A"
    EpisodeSortOrder.TITLE_ASC -> "Title A–Z"
    EpisodeSortOrder.TITLE_DESC -> "Title Z–A"
    EpisodeSortOrder.DOWNLOAD_DATE_DESC -> "Download date"
    EpisodeSortOrder.PLAYED_PORTION_ASC -> "Played portion"
    EpisodeSortOrder.FILE_NAME_ASC -> "File name"
    EpisodeSortOrder.MANUAL -> "Manual"
}
