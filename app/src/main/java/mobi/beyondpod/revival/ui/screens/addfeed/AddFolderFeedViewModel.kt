package mobi.beyondpod.revival.ui.screens.addfeed

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import mobi.beyondpod.revival.domain.usecase.feed.AddFolderFeedUseCase
import mobi.beyondpod.revival.domain.usecase.feed.ScanFolderFeedUseCase
import javax.inject.Inject

sealed class AddFolderUiState {
    object Idle    : AddFolderUiState()
    object Loading : AddFolderUiState()
    data class Success(val feedId: Long, val episodeCount: Int) : AddFolderUiState()
    data class Error(val message: String)   : AddFolderUiState()
}

@HiltViewModel
class AddFolderFeedViewModel @Inject constructor(
    private val addFolderFeedUseCase: AddFolderFeedUseCase,
    private val scanFolderFeedUseCase: ScanFolderFeedUseCase,
    @ApplicationContext private val context: Context
) : ViewModel() {

    private val _uiState = MutableStateFlow<AddFolderUiState>(AddFolderUiState.Idle)
    val uiState: StateFlow<AddFolderUiState> = _uiState.asStateFlow()

    fun addFolder(treeUri: Uri) {
        viewModelScope.launch {
            _uiState.value = AddFolderUiState.Loading

            // Persist SAF read permission across reboots
            try {
                context.contentResolver.takePersistableUriPermission(
                    treeUri, Intent.FLAG_GRANT_READ_URI_PERMISSION
                )
            } catch (_: SecurityException) { /* already held */ }

            val displayName = DocumentFile.fromTreeUri(context, treeUri)?.name
                ?: treeUri.lastPathSegment?.substringAfterLast("%2F")
                ?: "Local Folder"

            addFolderFeedUseCase(treeUri.toString(), displayName).fold(
                onSuccess = { feed ->
                    scanFolderFeedUseCase(feed.id).fold(
                        onSuccess = { count ->
                            _uiState.value = AddFolderUiState.Success(feed.id, count)
                        },
                        onFailure = { e ->
                            _uiState.value = AddFolderUiState.Error("Scan failed: ${e.message}")
                        }
                    )
                },
                onFailure = { e ->
                    _uiState.value = AddFolderUiState.Error("Could not add folder: ${e.message}")
                }
            )
        }
    }

    fun reset() { _uiState.value = AddFolderUiState.Idle }
}
