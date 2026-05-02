package mobi.beyondpod.revival.ui.screens.backup

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import mobi.beyondpod.revival.data.settings.AppSettings
import mobi.beyondpod.revival.domain.usecase.feed.ExportOpmlUseCase
import mobi.beyondpod.revival.domain.usecase.feed.ImportOpmlUseCase
import org.json.JSONObject
import javax.inject.Inject

data class BackupUiState(
    val isLoading: Boolean = false,
    /** Non-null = ready for export; screen should launch CreateDocument picker. */
    val pendingOpmlExport: String? = null,
    /** Non-null = ready for export; screen should launch CreateDocument picker. */
    val pendingSettingsExport: String? = null,
    /** One-shot toast/snackbar message. */
    val message: String? = null
)

@HiltViewModel
class BackupViewModel @Inject constructor(
    private val importOpmlUseCase: ImportOpmlUseCase,
    private val exportOpmlUseCase: ExportOpmlUseCase,
    private val dataStore: DataStore<Preferences>
) : ViewModel() {

    private val _uiState = MutableStateFlow(BackupUiState())
    val uiState: StateFlow<BackupUiState> = _uiState.asStateFlow()

    // ── OPML ────────────────────────────────────────────────────────────────────

    /** Generate OPML XML and store it as pending; screen observes and opens the file picker. */
    fun prepareOpmlExport() {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true) }
            exportOpmlUseCase().fold(
                onSuccess = { xml ->
                    _uiState.update { it.copy(isLoading = false, pendingOpmlExport = xml) }
                },
                onFailure = { e ->
                    _uiState.update { it.copy(isLoading = false, message = "Export failed: ${e.message}") }
                }
            )
        }
    }

    fun clearPendingOpmlExport() = _uiState.update { it.copy(pendingOpmlExport = null) }

    /** Parse an OPML string and import feeds + categories. */
    fun importOpml(content: String) {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true) }
            importOpmlUseCase(content).fold(
                onSuccess = { count ->
                    _uiState.update { it.copy(isLoading = false, message = "Imported $count feeds") }
                },
                onFailure = { e ->
                    _uiState.update { it.copy(isLoading = false, message = "Import failed: ${e.message}") }
                }
            )
        }
    }

    // ── Settings backup ──────────────────────────────────────────────────────────

    /** Serialize all scalar DataStore prefs to JSON and stage for file export. */
    fun prepareSettingsExport() {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true) }
            try {
                val prefs = dataStore.data.first()
                val json = serializePrefs(prefs)
                _uiState.update { it.copy(isLoading = false, pendingSettingsExport = json) }
            } catch (e: Exception) {
                _uiState.update { it.copy(isLoading = false, message = "Export failed: ${e.message}") }
            }
        }
    }

    fun clearPendingSettingsExport() = _uiState.update { it.copy(pendingSettingsExport = null) }

    /** Parse a JSON settings backup and write all recognised keys back to DataStore. */
    fun importSettings(json: String) {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true) }
            try {
                restorePrefs(json)
                _uiState.update { it.copy(isLoading = false, message = "Settings restored") }
            } catch (e: Exception) {
                _uiState.update { it.copy(isLoading = false, message = "Restore failed: ${e.message}") }
            }
        }
    }

    fun clearMessage() = _uiState.update { it.copy(message = null) }

    // ── Serialization ──────────────────────────────────────────────────────────

    private fun serializePrefs(prefs: Preferences): String {
        val obj = JSONObject()
        // Int
        prefs[AppSettings.SKIP_BACK_SECONDS]?.let            { obj.put("skip_back_seconds", it) }
        prefs[AppSettings.SKIP_FORWARD_SECONDS]?.let         { obj.put("skip_forward_seconds", it) }
        prefs[AppSettings.UPDATE_INTERVAL_HOURS]?.let        { obj.put("update_interval_hours", it) }
        prefs[AppSettings.VOLUME_BOOST_GLOBAL]?.let          { obj.put("volume_boost_global", it) }
        prefs[AppSettings.GLOBAL_DOWNLOAD_COUNT]?.let        { obj.put("global_download_count", it) }
        prefs[AppSettings.GLOBAL_MAX_KEEP]?.let              { obj.put("global_max_keep", it) }
        prefs[AppSettings.GLOBAL_DELETE_OLDER_THAN_DAYS]?.let { obj.put("global_delete_older_than_days", it) }
        // Float
        prefs[AppSettings.GLOBAL_PLAYBACK_SPEED]?.let        { obj.put("global_playback_speed", it.toDouble()) }
        // String
        prefs[AppSettings.THEME]?.let                        { obj.put("theme", it) }
        prefs[AppSettings.EPISODE_SORT_ORDER]?.let           { obj.put("episode_sort_order", it) }
        prefs[AppSettings.DOWNLOAD_STORAGE_PATH]?.let        { obj.put("download_storage_path", it) }
        prefs[AppSettings.SCROBBLE_USERNAME]?.let            { obj.put("scrobble_username", it) }
        // Boolean — playback
        prefs[AppSettings.SKIP_SILENCE]?.let                 { obj.put("skip_silence", it) }
        prefs[AppSettings.PAUSE_ON_HEADPHONE_UNPLUG]?.let    { obj.put("pause_on_headphone_unplug", it) }
        prefs[AppSettings.PAUSE_ON_NOTIFICATION]?.let        { obj.put("pause_on_notification", it) }
        prefs[AppSettings.CONTINUOUS_PLAYBACK]?.let          { obj.put("continuous_playback", it) }
        // Boolean — updates
        prefs[AppSettings.AUTO_UPDATE_ENABLED]?.let          { obj.put("auto_update_enabled", it) }
        prefs[AppSettings.TURN_WIFI_DURING_UPDATE]?.let      { obj.put("turn_wifi_during_update", it) }
        prefs[AppSettings.UPDATE_ON_WIFI_ONLY]?.let          { obj.put("update_on_wifi_only", it) }
        // Boolean — downloads
        prefs[AppSettings.DOWNLOAD_ON_WIFI_ONLY]?.let        { obj.put("download_on_wifi_only", it) }
        prefs[AppSettings.AUTO_DELETE_PLAYED]?.let           { obj.put("auto_delete_played", it) }
        // Boolean — scrobbling
        prefs[AppSettings.SCROBBLE_ENABLED]?.let             { obj.put("scrobble_enabled", it) }
        // Boolean — notifications (20)
        prefs[AppSettings.NOTIF_NEW_EPISODES]?.let           { obj.put("notif_new_episodes", it) }
        prefs[AppSettings.NOTIF_DOWNLOAD_COMPLETE]?.let      { obj.put("notif_download_complete", it) }
        prefs[AppSettings.NOTIF_DOWNLOAD_FAILED]?.let        { obj.put("notif_download_failed", it) }
        prefs[AppSettings.NOTIF_FEED_UPDATE_FAILED]?.let     { obj.put("notif_feed_update_failed", it) }
        prefs[AppSettings.NOTIF_PLAYBACK_CONTROLS]?.let      { obj.put("notif_playback_controls", it) }
        prefs[AppSettings.NOTIF_EPISODE_FINISHED]?.let       { obj.put("notif_episode_finished", it) }
        prefs[AppSettings.NOTIF_QUEUE_EMPTY]?.let            { obj.put("notif_queue_empty", it) }
        prefs[AppSettings.NOTIF_SLEEP_TIMER]?.let            { obj.put("notif_sleep_timer", it) }
        prefs[AppSettings.NOTIF_REWIND_REMINDER]?.let        { obj.put("notif_rewind_reminder", it) }
        prefs[AppSettings.NOTIF_AUTO_ADD]?.let               { obj.put("notif_auto_add", it) }
        prefs[AppSettings.NOTIF_CLEANUP]?.let                { obj.put("notif_cleanup", it) }
        prefs[AppSettings.NOTIF_UPDATE_CHECK]?.let           { obj.put("notif_update_check", it) }
        prefs[AppSettings.NOTIF_SUBSCRIPTION_NEW]?.let       { obj.put("notif_subscription_new", it) }
        prefs[AppSettings.NOTIF_ERROR_GENERIC]?.let          { obj.put("notif_error_generic", it) }
        prefs[AppSettings.NOTIF_WIFI_REQUIRED]?.let          { obj.put("notif_wifi_required", it) }
        prefs[AppSettings.NOTIF_STORAGE_LOW]?.let            { obj.put("notif_storage_low", it) }
        prefs[AppSettings.NOTIF_SCROBBLE_ERROR]?.let         { obj.put("notif_scrobble_error", it) }
        prefs[AppSettings.NOTIF_GPODDER_SYNC]?.let           { obj.put("notif_gpodder_sync", it) }
        prefs[AppSettings.NOTIF_CHARGE_DOWNLOAD]?.let        { obj.put("notif_charge_download", it) }
        prefs[AppSettings.NOTIF_PLAYLIST_REBUILD]?.let       { obj.put("notif_playlist_rebuild", it) }
        return obj.toString(2)
    }

    private suspend fun restorePrefs(json: String) {
        val obj = JSONObject(json)
        dataStore.edit { p ->
            // Int
            if (obj.has("skip_back_seconds"))            p[AppSettings.SKIP_BACK_SECONDS]            = obj.getInt("skip_back_seconds")
            if (obj.has("skip_forward_seconds"))         p[AppSettings.SKIP_FORWARD_SECONDS]         = obj.getInt("skip_forward_seconds")
            if (obj.has("update_interval_hours"))        p[AppSettings.UPDATE_INTERVAL_HOURS]        = obj.getInt("update_interval_hours")
            if (obj.has("volume_boost_global"))          p[AppSettings.VOLUME_BOOST_GLOBAL]          = obj.getInt("volume_boost_global")
            if (obj.has("global_download_count"))        p[AppSettings.GLOBAL_DOWNLOAD_COUNT]        = obj.getInt("global_download_count")
            if (obj.has("global_max_keep"))              p[AppSettings.GLOBAL_MAX_KEEP]              = obj.getInt("global_max_keep")
            if (obj.has("global_delete_older_than_days")) p[AppSettings.GLOBAL_DELETE_OLDER_THAN_DAYS] = obj.getInt("global_delete_older_than_days")
            // Float
            if (obj.has("global_playback_speed"))        p[AppSettings.GLOBAL_PLAYBACK_SPEED]        = obj.getDouble("global_playback_speed").toFloat()
            // String
            if (obj.has("theme"))                        p[AppSettings.THEME]                        = obj.getString("theme")
            if (obj.has("episode_sort_order"))           p[AppSettings.EPISODE_SORT_ORDER]           = obj.getString("episode_sort_order")
            if (obj.has("download_storage_path"))        p[AppSettings.DOWNLOAD_STORAGE_PATH]        = obj.getString("download_storage_path")
            if (obj.has("scrobble_username"))            p[AppSettings.SCROBBLE_USERNAME]            = obj.getString("scrobble_username")
            // Boolean — playback
            if (obj.has("skip_silence"))                 p[AppSettings.SKIP_SILENCE]                 = obj.getBoolean("skip_silence")
            if (obj.has("pause_on_headphone_unplug"))    p[AppSettings.PAUSE_ON_HEADPHONE_UNPLUG]    = obj.getBoolean("pause_on_headphone_unplug")
            if (obj.has("pause_on_notification"))        p[AppSettings.PAUSE_ON_NOTIFICATION]        = obj.getBoolean("pause_on_notification")
            if (obj.has("continuous_playback"))          p[AppSettings.CONTINUOUS_PLAYBACK]          = obj.getBoolean("continuous_playback")
            // Boolean — updates
            if (obj.has("auto_update_enabled"))          p[AppSettings.AUTO_UPDATE_ENABLED]          = obj.getBoolean("auto_update_enabled")
            if (obj.has("turn_wifi_during_update"))      p[AppSettings.TURN_WIFI_DURING_UPDATE]      = obj.getBoolean("turn_wifi_during_update")
            if (obj.has("update_on_wifi_only"))          p[AppSettings.UPDATE_ON_WIFI_ONLY]          = obj.getBoolean("update_on_wifi_only")
            // Boolean — downloads
            if (obj.has("download_on_wifi_only"))        p[AppSettings.DOWNLOAD_ON_WIFI_ONLY]        = obj.getBoolean("download_on_wifi_only")
            if (obj.has("auto_delete_played"))           p[AppSettings.AUTO_DELETE_PLAYED]           = obj.getBoolean("auto_delete_played")
            // Boolean — scrobbling
            if (obj.has("scrobble_enabled"))             p[AppSettings.SCROBBLE_ENABLED]             = obj.getBoolean("scrobble_enabled")
            // Boolean — notifications
            if (obj.has("notif_new_episodes"))           p[AppSettings.NOTIF_NEW_EPISODES]           = obj.getBoolean("notif_new_episodes")
            if (obj.has("notif_download_complete"))      p[AppSettings.NOTIF_DOWNLOAD_COMPLETE]      = obj.getBoolean("notif_download_complete")
            if (obj.has("notif_download_failed"))        p[AppSettings.NOTIF_DOWNLOAD_FAILED]        = obj.getBoolean("notif_download_failed")
            if (obj.has("notif_feed_update_failed"))     p[AppSettings.NOTIF_FEED_UPDATE_FAILED]     = obj.getBoolean("notif_feed_update_failed")
            if (obj.has("notif_playback_controls"))      p[AppSettings.NOTIF_PLAYBACK_CONTROLS]      = obj.getBoolean("notif_playback_controls")
            if (obj.has("notif_episode_finished"))       p[AppSettings.NOTIF_EPISODE_FINISHED]       = obj.getBoolean("notif_episode_finished")
            if (obj.has("notif_queue_empty"))            p[AppSettings.NOTIF_QUEUE_EMPTY]            = obj.getBoolean("notif_queue_empty")
            if (obj.has("notif_sleep_timer"))            p[AppSettings.NOTIF_SLEEP_TIMER]            = obj.getBoolean("notif_sleep_timer")
            if (obj.has("notif_rewind_reminder"))        p[AppSettings.NOTIF_REWIND_REMINDER]        = obj.getBoolean("notif_rewind_reminder")
            if (obj.has("notif_auto_add"))               p[AppSettings.NOTIF_AUTO_ADD]               = obj.getBoolean("notif_auto_add")
            if (obj.has("notif_cleanup"))                p[AppSettings.NOTIF_CLEANUP]                = obj.getBoolean("notif_cleanup")
            if (obj.has("notif_update_check"))           p[AppSettings.NOTIF_UPDATE_CHECK]           = obj.getBoolean("notif_update_check")
            if (obj.has("notif_subscription_new"))       p[AppSettings.NOTIF_SUBSCRIPTION_NEW]       = obj.getBoolean("notif_subscription_new")
            if (obj.has("notif_error_generic"))          p[AppSettings.NOTIF_ERROR_GENERIC]          = obj.getBoolean("notif_error_generic")
            if (obj.has("notif_wifi_required"))          p[AppSettings.NOTIF_WIFI_REQUIRED]          = obj.getBoolean("notif_wifi_required")
            if (obj.has("notif_storage_low"))            p[AppSettings.NOTIF_STORAGE_LOW]            = obj.getBoolean("notif_storage_low")
            if (obj.has("notif_scrobble_error"))         p[AppSettings.NOTIF_SCROBBLE_ERROR]         = obj.getBoolean("notif_scrobble_error")
            if (obj.has("notif_gpodder_sync"))           p[AppSettings.NOTIF_GPODDER_SYNC]           = obj.getBoolean("notif_gpodder_sync")
            if (obj.has("notif_charge_download"))        p[AppSettings.NOTIF_CHARGE_DOWNLOAD]        = obj.getBoolean("notif_charge_download")
            if (obj.has("notif_playlist_rebuild"))       p[AppSettings.NOTIF_PLAYLIST_REBUILD]       = obj.getBoolean("notif_playlist_rebuild")
        }
    }
}
