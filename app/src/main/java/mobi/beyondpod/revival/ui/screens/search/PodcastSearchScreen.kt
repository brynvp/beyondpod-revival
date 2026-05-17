package mobi.beyondpod.revival.ui.screens.search

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SuggestionChip
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.navigation.NavController
import coil3.compose.AsyncImage
import mobi.beyondpod.revival.data.repository.PodcastSearchResult
import mobi.beyondpod.revival.ui.navigation.Screen

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PodcastSearchScreen(
    navController: NavController,
    viewModel: PodcastSearchViewModel = hiltViewModel()
) {
    val uiState         by viewModel.uiState.collectAsState()
    val subscribedUrls  by viewModel.subscribedUrls.collectAsState()
    val subscribingUrls by viewModel.subscribingUrls.collectAsState()
    val categories      by viewModel.categories.collectAsState()
    val pendingFeedId   by viewModel.pendingCategoryFeedId.collectAsState()
    val keyboard        = LocalSoftwareKeyboardController.current

    // Local state drives the dialog — same isolated-recomposition pattern as AddFeedScreen.
    var pendingCategoryFeedId by remember { mutableStateOf<Long?>(null) }
    var newCategoryName       by remember { mutableStateOf("") }

    val predefinedCategories = listOf(
        "Science", "History", "Comedy", "Crime", "True Crime",
        "Technology", "Business", "News", "Sports", "Entertainment",
        "Society & Culture", "Health", "Education", "Politics",
        "Arts", "Music", "Kids & Family", "Fiction", "Self-Improvement"
    )

    // Mirror the VM's pendingFeedId into local state so dialog recompositions are isolated.
    LaunchedEffect(pendingFeedId) {
        pendingCategoryFeedId = pendingFeedId
    }

    fun doSearch() {
        keyboard?.hide()
        viewModel.search()
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Search Podcasts", fontWeight = FontWeight.SemiBold) },
                navigationIcon = {
                    IconButton(onClick = { navController.popBackStack() }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                }
            )
        }
    ) { innerPadding ->
        Column(modifier = Modifier.padding(innerPadding).fillMaxSize()) {

            // ── Search bar ───────────────────────────────────────────────────
            OutlinedTextField(
                value         = viewModel.query,
                onValueChange = viewModel::onQueryChange,
                placeholder   = { Text("e.g. Serial, Lex Fridman…") },
                singleLine    = true,
                trailingIcon  = {
                    IconButton(onClick = ::doSearch) {
                        Icon(Icons.Default.Search, contentDescription = "Search")
                    }
                },
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { doSearch() }),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp)
            )

            // ── Body ─────────────────────────────────────────────────────────
            when (val state = uiState) {
                is PodcastSearchUiState.Idle -> {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text(
                            "Search the iTunes podcast directory",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
                        )
                    }
                }

                is PodcastSearchUiState.Loading -> {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator()
                    }
                }

                is PodcastSearchUiState.Error -> {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text(
                            state.message,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                            modifier = Modifier.padding(32.dp)
                        )
                    }
                }

                is PodcastSearchUiState.Results -> {
                    // Deduplicate by feedUrl — iTunes API can return the same feed twice.
                    val uniqueItems = remember(state.items) { state.items.distinctBy { it.feedUrl } }
                    LazyColumn(modifier = Modifier.fillMaxSize()) {
                        items(items = uniqueItems, key = { it.feedUrl }) { result ->
                            SearchResultRow(
                                result        = result,
                                isSubscribed  = result.feedUrl in subscribedUrls,
                                isSubscribing = result.feedUrl in subscribingUrls,
                                onSubscribe   = { viewModel.subscribe(result.feedUrl) }
                            )
                            HorizontalDivider(
                                modifier  = Modifier.padding(start = 76.dp),
                                thickness = 0.5.dp
                            )
                        }
                    }
                }
            }
        }
    }

    // Category picker dialog — shown after a subscribe completes.
    if (pendingCategoryFeedId != null) {
        val feedId = pendingCategoryFeedId!!
        val unusedSuggestions = predefinedCategories.filter { suggestion ->
            categories.none { it.name.equals(suggestion, ignoreCase = true) }
        }
        AlertDialog(
            onDismissRequest = {
                viewModel.skipCategory()
                newCategoryName = ""
            },
            title = { Text("Add to a category?") },
            text = {
                Column {
                    if (categories.isNotEmpty()) {
                        LazyColumn(modifier = Modifier.heightIn(max = 200.dp)) {
                            items(categories) { category ->
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clickable {
                                            viewModel.assignCategoryAndProceed(feedId, category.id)
                                            newCategoryName = ""
                                            navController.navigate(
                                                Screen.FeedEpisodes.createRoute(feedId)
                                            ) {
                                                popUpTo(Screen.PodcastSearch.route) {
                                                    inclusive = true
                                                }
                                            }
                                        }
                                        .padding(vertical = 8.dp, horizontal = 4.dp)
                                ) {
                                    Text(text = category.name, modifier = Modifier.weight(1f))
                                }
                            }
                        }
                        HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
                    }

                    Text(
                        "Create new",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                        modifier = Modifier.padding(bottom = 4.dp)
                    )

                    if (unusedSuggestions.isNotEmpty()) {
                        LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            items(unusedSuggestions.size) { index ->
                                SuggestionChip(
                                    onClick = {
                                        viewModel.createCategoryAndProceed(feedId, unusedSuggestions[index])
                                        newCategoryName = ""
                                        navController.navigate(
                                            Screen.FeedEpisodes.createRoute(feedId)
                                        ) {
                                            popUpTo(Screen.PodcastSearch.route) { inclusive = true }
                                        }
                                    },
                                    label = { Text(unusedSuggestions[index]) }
                                )
                            }
                        }
                    }

                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        OutlinedTextField(
                            value = newCategoryName,
                            onValueChange = { newCategoryName = it },
                            placeholder = { Text("Category name") },
                            singleLine = true,
                            modifier = Modifier.weight(1f)
                        )
                        TextButton(
                            onClick = {
                                if (newCategoryName.isNotBlank()) {
                                    viewModel.createCategoryAndProceed(feedId, newCategoryName)
                                    newCategoryName = ""
                                    navController.navigate(
                                        Screen.FeedEpisodes.createRoute(feedId)
                                    ) {
                                        popUpTo(Screen.PodcastSearch.route) { inclusive = true }
                                    }
                                }
                            },
                            enabled = newCategoryName.isNotBlank()
                        ) { Text("Add") }
                    }
                }
            },
            confirmButton = {},
            dismissButton = {
                TextButton(onClick = {
                    viewModel.skipCategory()
                    newCategoryName = ""
                    navController.navigate(Screen.FeedEpisodes.createRoute(feedId)) {
                        popUpTo(Screen.PodcastSearch.route) { inclusive = true }
                    }
                }) { Text("Skip") }
            }
        )
    }
}

@Composable
private fun SearchResultRow(
    result: PodcastSearchResult,
    isSubscribed: Boolean,
    isSubscribing: Boolean,
    onSubscribe: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Artwork
        Box(
            modifier = Modifier
                .size(56.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant)
        ) {
            if (result.artworkUrl.isNotEmpty()) {
                AsyncImage(
                    model              = result.artworkUrl,
                    contentDescription = "${result.trackName} artwork",
                    contentScale       = ContentScale.Crop,
                    modifier           = Modifier.fillMaxSize()
                )
            }
        }

        Spacer(Modifier.width(12.dp))

        // Title + author
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text     = result.trackName,
                style    = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Medium),
                maxLines = 2
            )
            if (result.artistName.isNotEmpty()) {
                Text(
                    text     = result.artistName,
                    style    = MaterialTheme.typography.bodySmall,
                    color    = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                    maxLines = 1
                )
            }
        }

        Spacer(Modifier.width(8.dp))

        // Subscribe / Subscribed button
        if (isSubscribed) {
            OutlinedButton(onClick = {}, enabled = false) {
                Text("Subscribed")
            }
        } else if (isSubscribing) {
            OutlinedButton(onClick = {}, enabled = false) {
                CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
            }
        } else {
            Button(onClick = onSubscribe) {
                Text("Subscribe")
            }
        }
    }
}
