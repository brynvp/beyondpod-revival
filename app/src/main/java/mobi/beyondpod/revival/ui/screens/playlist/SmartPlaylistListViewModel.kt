package mobi.beyondpod.revival.ui.screens.playlist

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import mobi.beyondpod.revival.data.local.entity.SmartPlaylistEntity
import mobi.beyondpod.revival.domain.usecase.playlist.DeletePlaylistUseCase
import mobi.beyondpod.revival.data.repository.PlaylistRepository
import javax.inject.Inject

sealed interface SmartPlaylistListUiState {
    data object Loading : SmartPlaylistListUiState
    data class Success(val playlists: List<SmartPlaylistEntity>) : SmartPlaylistListUiState
}

/**
 * ViewModel for SmartPlaylistListScreen.
 *
 * Exposes the full playlist list. Deletion is guarded: My Episodes (isDefault = true) is
 * indestructible (§7.5 rule #3) — [deletePlaylist] silently no-ops for default playlists.
 * New playlists are created via [createPlaylist] which returns the new ID for navigation.
 */
@HiltViewModel
class SmartPlaylistListViewModel @Inject constructor(
    private val playlistRepository: PlaylistRepository,
    private val deletePlaylistUseCase: DeletePlaylistUseCase
) : ViewModel() {

    val uiState: StateFlow<SmartPlaylistListUiState> = playlistRepository.getAllPlaylists()
        .map<List<SmartPlaylistEntity>, SmartPlaylistListUiState> {
            SmartPlaylistListUiState.Success(it)
        }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = SmartPlaylistListUiState.Loading
        )

    /** Newly created playlist ID — consumed by the UI to navigate to the detail screen. */
    val newPlaylistId = MutableStateFlow<Long?>(null)

    /** Create a new SmartPlaylist with default SEQUENTIAL_BLOCKS mode. */
    fun createPlaylist(name: String) {
        viewModelScope.launch {
            val id = playlistRepository.createPlaylist(SmartPlaylistEntity(name = name))
            newPlaylistId.value = id
        }
    }

    fun consumeNewPlaylistId() { newPlaylistId.value = null }

    /**
     * Delete a playlist. Silently ignored for My Episodes (isDefault = true)
     * per §7.5 rule #3 — indestructible.
     */
    fun deletePlaylist(playlist: SmartPlaylistEntity) {
        if (playlist.isDefault) return
        viewModelScope.launch {
            runCatching { deletePlaylistUseCase(playlist) }
        }
    }
}
