package mobi.beyondpod.revival.ui.screens.playlist

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.google.gson.Gson
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import mobi.beyondpod.revival.data.local.entity.BlockEpisodeOrder
import mobi.beyondpod.revival.data.local.entity.BlockSource
import mobi.beyondpod.revival.data.local.entity.EpisodeEntity
import mobi.beyondpod.revival.data.local.entity.EpisodeSortOrder
import mobi.beyondpod.revival.data.local.entity.LogicalConnector
import mobi.beyondpod.revival.data.local.entity.OnEmptyAction
import mobi.beyondpod.revival.data.local.entity.PlaylistRuleMode
import mobi.beyondpod.revival.data.local.entity.RuleField
import mobi.beyondpod.revival.data.local.entity.RuleOperator
import mobi.beyondpod.revival.data.local.entity.SmartPlaylistBlock
import mobi.beyondpod.revival.data.local.entity.SmartPlaylistEntity
import mobi.beyondpod.revival.data.local.entity.SmartPlaylistRule
import mobi.beyondpod.revival.data.repository.EpisodeRepository
import mobi.beyondpod.revival.data.repository.PlaylistRepository
import mobi.beyondpod.revival.domain.usecase.playlist.EvaluateSmartPlaylistUseCase
import mobi.beyondpod.revival.ui.navigation.Screen
import javax.inject.Inject

sealed interface SmartPlaylistDetailUiState {
    data object Loading : SmartPlaylistDetailUiState
    data class Success(
        val playlist: SmartPlaylistEntity,
        val episodes: List<EpisodeEntity>,
        val editBlocks: List<SmartPlaylistBlock>,
        val editRules: List<SmartPlaylistRule>
    ) : SmartPlaylistDetailUiState
    data class Error(val message: String) : SmartPlaylistDetailUiState
}

/**
 * ViewModel for SmartPlaylistDetailScreen.
 *
 * Manages two tabs:
 *   1. Episodes tab — live evaluation via [EvaluateSmartPlaylistUseCase]
 *   2. Editor tab — mode toggle + rules/blocks editing + playlist settings
 *
 * Rule modes (CLAUDE.md rule #3):
 *   SEQUENTIAL_BLOCKS ("Standard") — original BeyondPod model, default for new playlists.
 *   FILTER_RULES ("Advanced") — predicate-based, Revival extension.
 *
 * Queue immutability (CLAUDE.md rule #1): [buildQueueSnapshot] calls
 * [EpisodeRepository.buildQueueSnapshot] which goes through [QueueSnapshotDao.replaceActiveSnapshot].
 * No queue state is ever written to EpisodeEntity.
 */
@HiltViewModel
class SmartPlaylistDetailViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val playlistRepository: PlaylistRepository,
    private val episodeRepository: EpisodeRepository,
    private val evaluateSmartPlaylistUseCase: EvaluateSmartPlaylistUseCase
) : ViewModel() {

    val playlistId: Long = checkNotNull(savedStateHandle[Screen.Playlist.ARG_PLAYLIST_ID])

    private val gson = Gson()

    // ── Live state (loaded from DB + evaluation) ──────────────────────────────

    val uiState: StateFlow<SmartPlaylistDetailUiState> = combine(
        flow { emit(playlistRepository.getPlaylistById(playlistId)) },
        // Evaluate once; the combine will refresh on playlist changes below
        flow { emit(emptyList<EpisodeEntity>()) }
    ) { playlist, _ ->
        if (playlist == null) {
            SmartPlaylistDetailUiState.Error("Playlist not found")
        } else {
            SmartPlaylistDetailUiState.Success(
                playlist = playlist,
                episodes = emptyList(),  // populated separately via evaluatedEpisodes
                editBlocks = parseBlocks(playlist),
                editRules = parseRules(playlist)
            )
        }
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = SmartPlaylistDetailUiState.Loading
    )

    // Live-evaluated episodes for the current playlist
    val evaluatedEpisodes: StateFlow<List<EpisodeEntity>> = kotlinx.coroutines.flow.flow<List<EpisodeEntity>> {
        val playlist = playlistRepository.getPlaylistById(playlistId)
        if (playlist != null) {
            evaluateSmartPlaylistUseCase(playlist).collect { emit(it) }
        }
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = emptyList()
    )

    // ── Editor mutable state ──────────────────────────────────────────────────

    var editName by mutableStateOf("")
        private set
    var editMode by mutableStateOf(PlaylistRuleMode.SEQUENTIAL_BLOCKS)
        private set
    var editBlocks by mutableStateOf<List<SmartPlaylistBlock>>(emptyList())
        private set
    var editRules by mutableStateOf<List<SmartPlaylistRule>>(emptyList())
        private set
    var editMaxItems by mutableStateOf("0")
        private set
    var editSortOrder by mutableStateOf(EpisodeSortOrder.PUB_DATE_DESC)
        private set
    var editAutoPlay by mutableStateOf(false)
        private set
    var editContinueOnComplete by mutableStateOf(true)
        private set
    var editOnEmptyAction by mutableStateOf(OnEmptyAction.DO_NOTHING)
        private set

    private var initialized = false

    init {
        viewModelScope.launch {
            val playlist = playlistRepository.getPlaylistById(playlistId) ?: return@launch
            if (!initialized) {
                initialized = true
                editName = playlist.name
                editMode = playlist.ruleMode
                editBlocks = parseBlocks(playlist)
                editRules = parseRules(playlist)
                editMaxItems = playlist.maxItems.toString()
                editSortOrder = playlist.episodeSortOrder
                editAutoPlay = playlist.autoPlay
                editContinueOnComplete = playlist.continueOnComplete
                editOnEmptyAction = playlist.onEmptyAction
            }
        }
    }

    // ── Editor actions ────────────────────────────────────────────────────────

    fun onNameChange(value: String) { editName = value }
    fun onMaxItemsChange(value: String) { editMaxItems = value }
    fun onSortOrderChange(value: EpisodeSortOrder) { editSortOrder = value }
    fun onAutoPlayChange(value: Boolean) { editAutoPlay = value }
    fun onContinueOnCompleteChange(value: Boolean) { editContinueOnComplete = value }
    fun onOnEmptyActionChange(value: OnEmptyAction) { editOnEmptyAction = value }

    /** Toggle between Standard (SEQUENTIAL_BLOCKS) and Advanced (FILTER_RULES). */
    fun toggleMode() {
        editMode = if (editMode == PlaylistRuleMode.SEQUENTIAL_BLOCKS)
            PlaylistRuleMode.FILTER_RULES
        else
            PlaylistRuleMode.SEQUENTIAL_BLOCKS
    }

    // ── Sequential Blocks editing ─────────────────────────────────────────────

    fun addBlock() {
        editBlocks = editBlocks + SmartPlaylistBlock(
            count = 5,
            source = BlockSource.ALL_FEEDS,
            order = BlockEpisodeOrder.NEWEST
        )
    }

    fun removeBlock(index: Int) {
        editBlocks = editBlocks.toMutableList().apply { removeAt(index) }
    }

    fun updateBlock(index: Int, block: SmartPlaylistBlock) {
        editBlocks = editBlocks.toMutableList().apply { set(index, block) }
    }

    // ── Filter Rules editing ──────────────────────────────────────────────────

    fun addRule() {
        editRules = editRules + SmartPlaylistRule(
            field = RuleField.PLAY_STATE,
            operator = RuleOperator.IS,
            value = "NEW",
            logicalConnector = LogicalConnector.AND
        )
    }

    fun removeRule(index: Int) {
        editRules = editRules.toMutableList().apply { removeAt(index) }
    }

    fun updateRule(index: Int, rule: SmartPlaylistRule) {
        editRules = editRules.toMutableList().apply { set(index, rule) }
    }

    // ── Save ──────────────────────────────────────────────────────────────────

    fun savePlaylist() {
        viewModelScope.launch {
            val playlist = playlistRepository.getPlaylistById(playlistId) ?: return@launch
            val rulesJson = when (editMode) {
                PlaylistRuleMode.SEQUENTIAL_BLOCKS -> gson.toJson(editBlocks)
                PlaylistRuleMode.FILTER_RULES -> gson.toJson(editRules)
            }
            playlistRepository.updatePlaylist(
                playlist.copy(
                    name = editName.ifBlank { playlist.name },
                    ruleMode = editMode,
                    rulesJson = rulesJson,
                    maxItems = editMaxItems.toIntOrNull() ?: 0,
                    episodeSortOrder = editSortOrder,
                    autoPlay = editAutoPlay,
                    continueOnComplete = editContinueOnComplete,
                    onEmptyAction = editOnEmptyAction
                )
            )
        }
    }

    /**
     * Build a frozen queue snapshot from the currently evaluated episode list.
     * Goes through [EpisodeRepository.buildQueueSnapshot] → [QueueSnapshotDao.replaceActiveSnapshot].
     * NEVER writes queue state to EpisodeEntity (CLAUDE.md rule #1).
     */
    fun buildQueueSnapshot() {
        viewModelScope.launch {
            val episodeIds = evaluatedEpisodes.value.map { it.id }
            episodeRepository.buildQueueSnapshot(playlistId, episodeIds)
        }
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private fun parseBlocks(playlist: SmartPlaylistEntity): List<SmartPlaylistBlock> =
        if (playlist.ruleMode == PlaylistRuleMode.SEQUENTIAL_BLOCKS) {
            runCatching {
                gson.fromJson(playlist.rulesJson, Array<SmartPlaylistBlock>::class.java).toList()
            }.getOrElse { emptyList() }
        } else emptyList()

    private fun parseRules(playlist: SmartPlaylistEntity): List<SmartPlaylistRule> =
        if (playlist.ruleMode == PlaylistRuleMode.FILTER_RULES) {
            runCatching {
                gson.fromJson(playlist.rulesJson, Array<SmartPlaylistRule>::class.java).toList()
            }.getOrElse { emptyList() }
        } else emptyList()
}
