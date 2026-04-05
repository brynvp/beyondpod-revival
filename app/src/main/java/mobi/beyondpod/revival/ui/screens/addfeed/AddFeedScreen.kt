package mobi.beyondpod.revival.ui.screens.addfeed

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Podcasts
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.navigation.NavController
import coil3.compose.AsyncImage
import mobi.beyondpod.revival.ui.navigation.Screen

/**
 * Add Feed screen — URL entry, feed preview, and subscription confirm.
 *
 * Flow:
 *   1. User types/pastes a feed URL.
 *   2. "Fetch Preview" creates a minimal FeedEntity (full RSS parse happens in
 *      Phase 3 FeedUpdateWorker). Shows preview card with title, URL, description.
 *   3. "Subscribe" confirms — navigates to FeedDetailScreen for the new feed.
 *
 * Spec ref: §7.1 Subscribing, §8.4 AddFeedView
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AddFeedScreen(
    navController: NavController,
    viewModel: AddFeedViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsState()

    // Navigate to FeedDetailScreen once subscription is confirmed
    LaunchedEffect(uiState) {
        if (uiState is AddFeedUiState.Subscribed) {
            val feedId = (uiState as AddFeedUiState.Subscribed).feedId
            navController.navigate(Screen.FeedEpisodes.createRoute(feedId)) {
                popUpTo(Screen.AddFeed.route) { inclusive = true }
            }
            viewModel.reset()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Add Podcast", fontWeight = FontWeight.SemiBold) },
                navigationIcon = {
                    IconButton(onClick = { navController.popBackStack() }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                }
            )
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .padding(innerPadding)
                .padding(16.dp)
                .fillMaxSize()
        ) {
            Text(
                text = "Enter a podcast RSS feed URL",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
            )

            Spacer(Modifier.height(12.dp))

            OutlinedTextField(
                value = viewModel.urlInput,
                onValueChange = viewModel::onUrlChange,
                label = { Text("Feed URL") },
                placeholder = { Text("https://example.com/feed.rss") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                keyboardOptions = KeyboardOptions(
                    keyboardType = KeyboardType.Uri,
                    imeAction = ImeAction.Go
                ),
                keyboardActions = KeyboardActions(
                    onGo = { viewModel.fetchPreview() }
                ),
                isError = uiState is AddFeedUiState.Error,
                supportingText = if (uiState is AddFeedUiState.Error) {
                    { Text((uiState as AddFeedUiState.Error).message) }
                } else null
            )

            Spacer(Modifier.height(12.dp))

            Button(
                onClick = viewModel::fetchPreview,
                modifier = Modifier.fillMaxWidth(),
                enabled = uiState !is AddFeedUiState.Loading
            ) {
                if (uiState is AddFeedUiState.Loading) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(18.dp),
                        strokeWidth = 2.dp
                    )
                } else {
                    Text("Fetch Preview")
                }
            }

            // Preview card shown once feed is fetched
            if (uiState is AddFeedUiState.Preview) {
                val feed = (uiState as AddFeedUiState.Preview).feed
                Spacer(Modifier.height(24.dp))

                Card(
                    modifier = Modifier.fillMaxWidth(),
                    elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            // Artwork
                            Box(
                                modifier = Modifier
                                    .size(72.dp)
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(MaterialTheme.colorScheme.surfaceVariant),
                                contentAlignment = Alignment.Center
                            ) {
                                if (feed.imageUrl != null) {
                                    AsyncImage(
                                        model = feed.imageUrl,
                                        contentDescription = null,
                                        contentScale = ContentScale.Crop,
                                        modifier = Modifier.fillMaxSize()
                                    )
                                } else {
                                    Icon(
                                        imageVector = Icons.Default.Podcasts,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.3f)
                                    )
                                }
                            }

                            Spacer(Modifier.width(12.dp))

                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = feed.title,
                                    style = MaterialTheme.typography.titleMedium.copy(
                                        fontWeight = FontWeight.SemiBold
                                    ),
                                    maxLines = 2
                                )
                                if (feed.author.isNotEmpty()) {
                                    Spacer(Modifier.height(2.dp))
                                    Text(
                                        text = feed.author,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                                    )
                                }
                            }
                        }

                        if (feed.description.isNotEmpty()) {
                            Spacer(Modifier.height(8.dp))
                            Text(
                                text = feed.description,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
                                maxLines = 3
                            )
                        }

                        Spacer(Modifier.height(16.dp))

                        Row {
                            OutlinedButton(
                                onClick = viewModel::reset,
                                modifier = Modifier.weight(1f)
                            ) {
                                Text("Cancel")
                            }
                            Spacer(Modifier.width(12.dp))
                            Button(
                                onClick = viewModel::confirmSubscribe,
                                modifier = Modifier.weight(1f)
                            ) {
                                Text("Subscribe")
                            }
                        }
                    }
                }
            }
        }
    }
}
