package mobi.beyondpod.revival.ui.screens.queue

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ClearAll
import androidx.compose.material.icons.filled.DragHandle
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Surface
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.toMutableStateList
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.navigation.NavController
import kotlinx.coroutines.launch
import mobi.beyondpod.revival.service.PlaybackService
import sh.calvin.reorderable.ReorderableItem
import sh.calvin.reorderable.rememberReorderableLazyListState

/**
 * Queue screen — shows the active [QueueSnapshotEntity] as an ordered episode list.
 *
 * **Interactions:**
 * - Drag-to-reorder via long-press on the drag handle ([sh.calvin.reorderable]).
 *   On drop: [QueueViewModel.reorderItems] calls [QueueSnapshotDao.replaceActiveSnapshot].
 * - Swipe-to-dismiss removes the episode from the snapshot via
 *   [QueueViewModel.removeItem] → [QueueSnapshotDao.removeItemsFromActiveSnapshot].
 * - "Clear Queue" deactivates the snapshot; episode records are untouched.
 *
 * **Queue immutability (CLAUDE.md rule #1):**
 * No write path in this screen touches [EpisodeEntity]. All mutations go through
 * [QueueSnapshotDao] via [QueueViewModel].
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun QueueScreen(
    navController: NavController,
    viewModel: QueueViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsState()
    val context = androidx.compose.ui.platform.LocalContext.current
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    var showClearDialog by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("Queue", fontWeight = FontWeight.SemiBold)
                        if (uiState is QueueUiState.Active) {
                            val count = (uiState as QueueUiState.Active).items.size
                            Text(
                                text = "$count episode${if (count == 1) "" else "s"}",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                            )
                        }
                    }
                },
                navigationIcon = {
                    IconButton(onClick = { navController.popBackStack() }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    if (uiState is QueueUiState.Active) {
                        IconButton(onClick = { showClearDialog = true }) {
                            Icon(Icons.Default.ClearAll, contentDescription = "Clear queue")
                        }
                    }
                }
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) }
    ) { innerPadding ->
        Box(modifier = Modifier.padding(innerPadding)) {
            when (val state = uiState) {
                is QueueUiState.Empty -> {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text("Queue is empty", style = MaterialTheme.typography.titleMedium)
                            Text(
                                text = "Play a playlist or add episodes to start",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
                            )
                        }
                    }
                }

                is QueueUiState.Active -> {
                    // Local mutable list drives drag-to-reorder UI without waiting for DB
                    val localItems = remember(state.items) {
                        state.items.toMutableStateList()
                    }

                    val lazyListState = rememberLazyListState()
                    val reorderState = rememberReorderableLazyListState(lazyListState) { from, to ->
                        localItems.apply { add(to.index, removeAt(from.index)) }
                        viewModel.reorderItems(localItems.toList())
                    }

                    LazyColumn(
                        state = lazyListState,
                        modifier = Modifier.fillMaxSize()
                    ) {
                        items(localItems, key = { it.snapshotItem.id }) { item ->
                            ReorderableItem(reorderState, key = item.snapshotItem.id) { isDragging ->
                                val elevation by animateDpAsState(
                                    if (isDragging) 6.dp else 0.dp,
                                    label = "drag_elevation"
                                )
                                val dismissState = rememberSwipeToDismissBoxState(
                                    confirmValueChange = { value ->
                                        if (value == SwipeToDismissBoxValue.EndToStart ||
                                            value == SwipeToDismissBoxValue.StartToEnd
                                        ) {
                                            scope.launch {
                                                val result = snackbarHostState.showSnackbar(
                                                    message = "Removed from queue",
                                                    actionLabel = null
                                                )
                                                if (result == SnackbarResult.Dismissed ||
                                                    result == SnackbarResult.ActionPerformed
                                                ) {
                                                    viewModel.removeItem(item.snapshotItem.episodeId)
                                                    localItems.remove(item)
                                                }
                                            }
                                            true
                                        } else false
                                    }
                                )

                                SwipeToDismissBox(
                                    state = dismissState,
                                    backgroundContent = { /* tinted background */ },
                                    modifier = Modifier.animateItem()
                                ) {
                                    Surface(shadowElevation = elevation) {
                                        QueueItemRow(
                                            item = item,
                                            dragHandleModifier = Modifier.draggableHandle(),
                                            onPlay = {
                                                item.episode?.id?.let { id ->
                                                    context.startService(
                                                        PlaybackService.playEpisodeIntent(context, id)
                                                    )
                                                }
                                            }
                                        )
                                    }
                                }
                                HorizontalDivider(
                                    modifier = Modifier.padding(start = 76.dp),
                                    thickness = 0.5.dp
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    if (showClearDialog) {
        AlertDialog(
            onDismissRequest = { showClearDialog = false },
            title = { Text("Clear Queue") },
            text = { Text("Remove all episodes from the queue? Episodes and downloads are not affected.") },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.clearQueue()
                    showClearDialog = false
                }) {
                    Text("Clear", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { showClearDialog = false }) { Text("Cancel") }
            }
        )
    }
}

@Composable
private fun QueueItemRow(
    item: QueueItem,
    dragHandleModifier: Modifier,
    onPlay: () -> Unit,
    modifier: Modifier = Modifier
) {
    val episode = item.episode
    val title = episode?.title ?: item.snapshotItem.episodeTitleSnapshot
    val feedTitle = episode?.let { "" } ?: item.snapshotItem.feedTitleSnapshot

    Row(
        modifier = modifier
            .fillMaxWidth()
            .clickable(onClickLabel = "Play episode", onClick = onPlay)
            .padding(vertical = 8.dp, horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Position badge
        Text(
            text = "${item.snapshotItem.position + 1}",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f),
            modifier = Modifier.width(28.dp)
        )

        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Medium),
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
            if (feedTitle.isNotEmpty()) {
                Text(
                    text = feedTitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }

        Spacer(Modifier.width(8.dp))

        // Drag handle — triggers reorder on long press (sh.calvin.reorderable)
        Icon(
            imageVector = Icons.Default.DragHandle,
            contentDescription = "Drag to reorder",
            modifier = dragHandleModifier.size(24.dp),
            tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f)
        )
    }
}
