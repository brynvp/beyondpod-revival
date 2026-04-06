package mobi.beyondpod.revival.ui.screens.feedlist

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.navigation.NavController
import coil3.compose.AsyncImage
import mobi.beyondpod.revival.data.local.entity.CategoryEntity
import mobi.beyondpod.revival.data.local.entity.FeedEntity
import mobi.beyondpod.revival.ui.navigation.Screen

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FeedListScreen(
    navController: NavController,
    viewModel: FeedListViewModel = hiltViewModel()
) {
    val uiState      by viewModel.uiState.collectAsState()
    val isRefreshing by viewModel.isRefreshing.collectAsState()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Feeds", fontWeight = FontWeight.SemiBold) },
                actions = {
                    IconButton(onClick = { navController.navigate(Screen.PodcastSearch.route) }) {
                        Icon(Icons.Default.Search, contentDescription = "Search podcasts")
                    }
                }
            )
        },
        floatingActionButton = {
            FloatingActionButton(
                onClick = { navController.navigate(Screen.AddFeed.route) },
                modifier = Modifier.semantics { contentDescription = "Add podcast" }
            ) {
                Icon(Icons.Default.Add, contentDescription = null)
            }
        }
    ) { innerPadding ->
        PullToRefreshBox(
            isRefreshing = isRefreshing,
            onRefresh    = viewModel::refresh,
            modifier     = Modifier.padding(innerPadding)
        ) {
            when (val state = uiState) {
                is FeedListUiState.Loading -> {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator(modifier = Modifier.semantics {
                            contentDescription = "Loading feeds"
                        })
                    }
                }

                is FeedListUiState.Error -> {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(state.message, style = MaterialTheme.typography.bodyMedium)
                            TextButton(onClick = viewModel::refresh) { Text("Retry") }
                        }
                    }
                }

                is FeedListUiState.Success -> {
                    if (state.groups.isEmpty()) {
                        EmptyFeedList(onAddFeed = { navController.navigate(Screen.AddFeed.route) })
                    } else {
                        LazyColumn(modifier = Modifier.fillMaxSize()) {
                            for (group in state.groups) {
                                item(key = "header_${group.category?.id ?: "uncategorized"}") {
                                    CategoryHeader(
                                        group    = group,
                                        onToggle = { viewModel.toggleCategory(group.category?.id) }
                                    )
                                    HorizontalDivider(thickness = 0.5.dp)
                                }

                                if (group.isExpanded) {
                                    items(
                                        items = group.feeds,
                                        key   = { "feed_${it.id}" }
                                    ) { feed ->
                                        FeedRow(
                                            feed    = feed,
                                            onClick = {
                                                navController.navigate(
                                                    Screen.FeedEpisodes.createRoute(feed.id)
                                                )
                                            }
                                        )
                                        HorizontalDivider(
                                            modifier  = Modifier.padding(start = 72.dp),
                                            thickness = 0.5.dp
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun CategoryHeader(
    group: CategoryWithFeeds,
    onToggle: () -> Unit,
    modifier: Modifier = Modifier
) {
    val category = group.category
    val label = "${category?.name ?: "Uncategorized"}, ${group.feeds.size} feed${if (group.feeds.size == 1) "" else "s"}, ${if (group.isExpanded) "expanded" else "collapsed"}"
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clickable(onClickLabel = if (group.isExpanded) "Collapse category" else "Expand category", onClick = onToggle)
            .semantics { contentDescription = label }
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (category != null) {
            Box(
                modifier = Modifier
                    .size(10.dp)
                    .background(Color(category.color), CircleShape)
            )
            Spacer(Modifier.width(12.dp))
        } else {
            Icon(
                imageVector    = Icons.Default.Folder,
                contentDescription = null,
                modifier       = Modifier.size(18.dp),
                tint           = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
            )
            Spacer(Modifier.width(8.dp))
        }

        Text(
            text     = category?.name ?: "Uncategorized",
            style    = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold),
            modifier = Modifier.weight(1f)
        )
        Text(
            text  = "${group.feeds.size}",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
        )
        Spacer(Modifier.width(8.dp))
        Icon(
            imageVector    = if (group.isExpanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
            contentDescription = null,  // described by semantics above
            tint           = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
        )
    }
}

@Composable
private fun FeedRow(
    feed: FeedEntity,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clickable(onClickLabel = "Open ${feed.title}", onClick = onClick)
            .padding(start = 36.dp, end = 16.dp, top = 10.dp, bottom = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(50.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.surfaceVariant)
        ) {
            if (feed.imageUrl != null) {
                AsyncImage(
                    model              = feed.imageUrl,
                    contentDescription = "${feed.title} artwork",
                    contentScale       = ContentScale.Crop,
                    modifier           = Modifier.fillMaxSize()
                )
            }
        }
        Spacer(Modifier.width(14.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text     = feed.title,
                style    = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Medium),
                maxLines = 1
            )
            if (feed.author.isNotEmpty()) {
                Text(
                    text     = feed.author,
                    style    = MaterialTheme.typography.bodySmall,
                    color    = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                    maxLines = 1
                )
            }
        }
    }
}

@Composable
private fun EmptyFeedList(onAddFeed: () -> Unit) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text("No podcasts yet", style = MaterialTheme.typography.titleMedium)
            TextButton(onClick = onAddFeed) { Text("Add your first podcast") }
        }
    }
}
