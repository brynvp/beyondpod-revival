package mobi.beyondpod.revival.ui.screens.settings

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.workDataOf
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import mobi.beyondpod.revival.data.settings.AppSettings
import mobi.beyondpod.revival.service.FeedUpdateWorker
import java.util.concurrent.TimeUnit
import javax.inject.Inject

data class SettingsUiState(
    // Playback
    val skipBackSeconds: Int      = 10,
    val skipForwardSeconds: Int   = 30,
    val globalPlaybackSpeed: Float = 1.0f,
    val skipSilence: Boolean      = false,
    val volumeBoostGlobal: Int    = 0,
    val pauseOnHeadphoneUnplug: Boolean = true,
    val pauseOnNotification: Boolean = false,
    val continuousPlayback: Boolean = true,

    // Updates
    val autoUpdateEnabled: Boolean = true,
    val updateIntervalHours: Int   = 4,
    val turnWifiDuringUpdate: Boolean = false,
    val updateOnWifiOnly: Boolean  = false,

    // Downloads
    val downloadOnWifiOnly: Boolean = true,
    val globalDownloadCount: Int    = 1,
    val globalMaxKeep: Int          = 5,
    val autoDeletePlayed: Boolean   = false,

    // Interface
    val theme: String              = "system",

    // Scrobbling
    val scrobbleEnabled: Boolean   = false,

    // Notifications
    val notifNewEpisodes: Boolean      = true,
    val notifDownloadComplete: Boolean = true,
    val notifDownloadFailed: Boolean   = true,
    val notifPlaybackControls: Boolean = true,
    val notifFeedUpdateFailed: Boolean = false,
    val notifEpisodeFinished: Boolean  = false,
    val notifQueueEmpty: Boolean       = false,
    val notifSleepTimer: Boolean       = false,
    val notifRewindReminder: Boolean   = false,
    val notifAutoAdd: Boolean          = false,
    val notifCleanup: Boolean          = false,
    val notifUpdateCheck: Boolean      = false,
    val notifSubscriptionNew: Boolean  = true,
    val notifErrorGeneric: Boolean     = true,
    val notifWifiRequired: Boolean     = false,
    val notifStorageLow: Boolean       = true,
    val notifScrobbleError: Boolean    = false,
    val notifGpodderSync: Boolean      = false,
    val notifChargeDownload: Boolean   = false,
    val notifPlaylistRebuild: Boolean  = false
)

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val dataStore: DataStore<Preferences>,
    private val workManager: WorkManager
) : ViewModel() {

    val uiState: StateFlow<SettingsUiState> = dataStore.data.map { prefs ->
        SettingsUiState(
            skipBackSeconds      = prefs[AppSettings.SKIP_BACK_SECONDS] ?: 10,
            skipForwardSeconds   = prefs[AppSettings.SKIP_FORWARD_SECONDS] ?: 30,
            globalPlaybackSpeed  = prefs[AppSettings.GLOBAL_PLAYBACK_SPEED] ?: 1.0f,
            skipSilence          = prefs[AppSettings.SKIP_SILENCE] ?: false,
            volumeBoostGlobal    = prefs[AppSettings.VOLUME_BOOST_GLOBAL] ?: 0,
            pauseOnHeadphoneUnplug = prefs[AppSettings.PAUSE_ON_HEADPHONE_UNPLUG] ?: true,
            pauseOnNotification  = prefs[AppSettings.PAUSE_ON_NOTIFICATION] ?: false,
            continuousPlayback   = prefs[AppSettings.CONTINUOUS_PLAYBACK] ?: true,
            autoUpdateEnabled    = prefs[AppSettings.AUTO_UPDATE_ENABLED] ?: true,
            updateIntervalHours  = prefs[AppSettings.UPDATE_INTERVAL_HOURS] ?: 4,
            turnWifiDuringUpdate = prefs[AppSettings.TURN_WIFI_DURING_UPDATE] ?: false,
            updateOnWifiOnly     = prefs[AppSettings.UPDATE_ON_WIFI_ONLY] ?: false,
            downloadOnWifiOnly   = prefs[AppSettings.DOWNLOAD_ON_WIFI_ONLY] ?: true,
            globalDownloadCount  = prefs[AppSettings.GLOBAL_DOWNLOAD_COUNT] ?: 1,
            globalMaxKeep        = prefs[AppSettings.GLOBAL_MAX_KEEP] ?: 5,
            autoDeletePlayed     = prefs[AppSettings.AUTO_DELETE_PLAYED] ?: false,
            theme                = prefs[AppSettings.THEME] ?: "system",
            scrobbleEnabled      = prefs[AppSettings.SCROBBLE_ENABLED] ?: false,
            notifNewEpisodes     = prefs[AppSettings.NOTIF_NEW_EPISODES] ?: true,
            notifDownloadComplete = prefs[AppSettings.NOTIF_DOWNLOAD_COMPLETE] ?: true,
            notifDownloadFailed  = prefs[AppSettings.NOTIF_DOWNLOAD_FAILED] ?: true,
            notifPlaybackControls = prefs[AppSettings.NOTIF_PLAYBACK_CONTROLS] ?: true,
            notifFeedUpdateFailed = prefs[AppSettings.NOTIF_FEED_UPDATE_FAILED] ?: false,
            notifEpisodeFinished = prefs[AppSettings.NOTIF_EPISODE_FINISHED] ?: false,
            notifQueueEmpty      = prefs[AppSettings.NOTIF_QUEUE_EMPTY] ?: false,
            notifSleepTimer      = prefs[AppSettings.NOTIF_SLEEP_TIMER] ?: false,
            notifRewindReminder  = prefs[AppSettings.NOTIF_REWIND_REMINDER] ?: false,
            notifAutoAdd         = prefs[AppSettings.NOTIF_AUTO_ADD] ?: false,
            notifCleanup         = prefs[AppSettings.NOTIF_CLEANUP] ?: false,
            notifUpdateCheck     = prefs[AppSettings.NOTIF_UPDATE_CHECK] ?: false,
            notifSubscriptionNew = prefs[AppSettings.NOTIF_SUBSCRIPTION_NEW] ?: true,
            notifErrorGeneric    = prefs[AppSettings.NOTIF_ERROR_GENERIC] ?: true,
            notifWifiRequired    = prefs[AppSettings.NOTIF_WIFI_REQUIRED] ?: false,
            notifStorageLow      = prefs[AppSettings.NOTIF_STORAGE_LOW] ?: true,
            notifScrobbleError   = prefs[AppSettings.NOTIF_SCROBBLE_ERROR] ?: false,
            notifGpodderSync     = prefs[AppSettings.NOTIF_GPODDER_SYNC] ?: false,
            notifChargeDownload  = prefs[AppSettings.NOTIF_CHARGE_DOWNLOAD] ?: false,
            notifPlaylistRebuild = prefs[AppSettings.NOTIF_PLAYLIST_REBUILD] ?: false
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = SettingsUiState()
    )

    // ── Write helpers — each maps to its DataStore key ────────────────────────

    fun setSkipBackSeconds(v: Int)       = set { it[AppSettings.SKIP_BACK_SECONDS] = v }
    fun setSkipForwardSeconds(v: Int)    = set { it[AppSettings.SKIP_FORWARD_SECONDS] = v }
    fun setGlobalPlaybackSpeed(v: Float) = set { it[AppSettings.GLOBAL_PLAYBACK_SPEED] = v }
    fun setSkipSilence(v: Boolean)       = set { it[AppSettings.SKIP_SILENCE] = v }
    fun setVolumeBoostGlobal(v: Int)     = set { it[AppSettings.VOLUME_BOOST_GLOBAL] = v }
    fun setPauseOnHeadphoneUnplug(v: Boolean) = set { it[AppSettings.PAUSE_ON_HEADPHONE_UNPLUG] = v }
    fun setPauseOnNotification(v: Boolean)    = set { it[AppSettings.PAUSE_ON_NOTIFICATION] = v }
    fun setContinuousPlayback(v: Boolean)     = set { it[AppSettings.CONTINUOUS_PLAYBACK] = v }

    fun setAutoUpdateEnabled(v: Boolean) {
        set { it[AppSettings.AUTO_UPDATE_ENABLED] = v }
        viewModelScope.launch {
            if (v) {
                val hours = dataStore.data.first()[AppSettings.UPDATE_INTERVAL_HOURS] ?: 4
                schedulePeriodicUpdate(hours.toLong())
            } else {
                workManager.cancelUniqueWork("feed_update_periodic")
            }
        }
    }

    fun setUpdateIntervalHours(v: Int) {
        set { it[AppSettings.UPDATE_INTERVAL_HOURS] = v }
        viewModelScope.launch {
            val enabled = dataStore.data.first()[AppSettings.AUTO_UPDATE_ENABLED] ?: true
            if (enabled) schedulePeriodicUpdate(v.toLong())
        }
    }
    fun setTurnWifiDuringUpdate(v: Boolean) = set { it[AppSettings.TURN_WIFI_DURING_UPDATE] = v }
    fun setUpdateOnWifiOnly(v: Boolean)   = set { it[AppSettings.UPDATE_ON_WIFI_ONLY] = v }

    fun setDownloadOnWifiOnly(v: Boolean) = set { it[AppSettings.DOWNLOAD_ON_WIFI_ONLY] = v }
    fun setGlobalDownloadCount(v: Int)    = set { it[AppSettings.GLOBAL_DOWNLOAD_COUNT] = v }
    fun setGlobalMaxKeep(v: Int)          = set { it[AppSettings.GLOBAL_MAX_KEEP] = v }
    fun setAutoDeletePlayed(v: Boolean)   = set { it[AppSettings.AUTO_DELETE_PLAYED] = v }

    fun setTheme(v: String)              = set { it[AppSettings.THEME] = v }
    fun setScrobbleEnabled(v: Boolean)   = set { it[AppSettings.SCROBBLE_ENABLED] = v }

    fun setNotifNewEpisodes(v: Boolean)      = set { it[AppSettings.NOTIF_NEW_EPISODES] = v }
    fun setNotifDownloadComplete(v: Boolean) = set { it[AppSettings.NOTIF_DOWNLOAD_COMPLETE] = v }
    fun setNotifDownloadFailed(v: Boolean)   = set { it[AppSettings.NOTIF_DOWNLOAD_FAILED] = v }
    fun setNotifPlaybackControls(v: Boolean) = set { it[AppSettings.NOTIF_PLAYBACK_CONTROLS] = v }
    fun setNotifFeedUpdateFailed(v: Boolean) = set { it[AppSettings.NOTIF_FEED_UPDATE_FAILED] = v }
    fun setNotifEpisodeFinished(v: Boolean)  = set { it[AppSettings.NOTIF_EPISODE_FINISHED] = v }
    fun setNotifQueueEmpty(v: Boolean)       = set { it[AppSettings.NOTIF_QUEUE_EMPTY] = v }
    fun setNotifSleepTimer(v: Boolean)       = set { it[AppSettings.NOTIF_SLEEP_TIMER] = v }
    fun setNotifRewindReminder(v: Boolean)   = set { it[AppSettings.NOTIF_REWIND_REMINDER] = v }
    fun setNotifAutoAdd(v: Boolean)          = set { it[AppSettings.NOTIF_AUTO_ADD] = v }
    fun setNotifCleanup(v: Boolean)          = set { it[AppSettings.NOTIF_CLEANUP] = v }
    fun setNotifUpdateCheck(v: Boolean)      = set { it[AppSettings.NOTIF_UPDATE_CHECK] = v }
    fun setNotifSubscriptionNew(v: Boolean)  = set { it[AppSettings.NOTIF_SUBSCRIPTION_NEW] = v }
    fun setNotifErrorGeneric(v: Boolean)     = set { it[AppSettings.NOTIF_ERROR_GENERIC] = v }
    fun setNotifWifiRequired(v: Boolean)     = set { it[AppSettings.NOTIF_WIFI_REQUIRED] = v }
    fun setNotifStorageLow(v: Boolean)       = set { it[AppSettings.NOTIF_STORAGE_LOW] = v }
    fun setNotifScrobbleError(v: Boolean)    = set { it[AppSettings.NOTIF_SCROBBLE_ERROR] = v }
    fun setNotifGpodderSync(v: Boolean)      = set { it[AppSettings.NOTIF_GPODDER_SYNC] = v }
    fun setNotifChargeDownload(v: Boolean)   = set { it[AppSettings.NOTIF_CHARGE_DOWNLOAD] = v }
    fun setNotifPlaylistRebuild(v: Boolean)  = set { it[AppSettings.NOTIF_PLAYLIST_REBUILD] = v }

    private fun schedulePeriodicUpdate(intervalHours: Long) {
        val request = PeriodicWorkRequestBuilder<FeedUpdateWorker>(
            repeatInterval = intervalHours,
            repeatIntervalTimeUnit = TimeUnit.HOURS
        )
            .setInputData(workDataOf(FeedUpdateWorker.KEY_FEED_ID to FeedUpdateWorker.ALL_FEEDS))
            .build()
        workManager.enqueueUniquePeriodicWork(
            "feed_update_periodic",
            ExistingPeriodicWorkPolicy.UPDATE,
            request
        )
    }

    private fun set(block: (MutablePreferences) -> Unit) {
        viewModelScope.launch { dataStore.edit(block) }
    }
}

// Type alias for the lambda parameter in DataStore.edit
private typealias MutablePreferences = androidx.datastore.preferences.core.MutablePreferences
