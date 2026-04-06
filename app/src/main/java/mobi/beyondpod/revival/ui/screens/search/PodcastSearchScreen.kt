package mobi.beyondpod.revival.ui.screens.search

import androidx.compose.foundation.background
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Search
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
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PodcastSearchScreen(
    navController: NavController,
    viewModel: PodcastSearchViewModel = hiltViewModel()
) {
    val uiState       by viewModel.uiState.collectAsState()
    val subscribedUrls by viewModel.subscribedUrls.collectAsState()
    val keyboard       = LocalSoftwareKeyboardController.current

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
                    LazyColumn(modifier = Modifier.fillMaxSize()) {
                        items(items = state.items, key = { it.feedUrl }) { result ->
                            SearchResultRow(
                                result       = result,
                                isSubscribed = result.feedUrl in subscribedUrls,
                                onSubscribe  = { viewModel.subscribe(result.feedUrl) }
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
}

@Composable
private fun SearchResultRow(
    result: PodcastSearchResult,
    isSubscribed: Boolean,
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
        } else {
            Button(onClick = onSubscribe) {
                Text("Subscribe")
            }
        }
    }
}
