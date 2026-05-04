package mobi.beyondpod.revival.data.settings

import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey

/**
 * All DataStore preference keys for BeyondPod Revival (spec §7.14).
 *
 * Grouping mirrors the Settings screen hierarchy:
 *  - Playback
 *  - Updates
 *  - Downloads
 *  - Notifications (20 individual flags)
 *  - Interface
 *  - Scrobbling
 */
object AppSettings {

    // ── Playback ──────────────────────────────────────────────────────────────
    val SKIP_BACK_SECONDS    = intPreferencesKey("skip_back_seconds")       // default 10
    val SKIP_FORWARD_SECONDS = intPreferencesKey("skip_forward_seconds")    // default 30
    val GLOBAL_PLAYBACK_SPEED = floatPreferencesKey("global_playback_speed") // default 1.0f
    val SKIP_SILENCE         = booleanPreferencesKey("skip_silence")
    val VOLUME_BOOST_GLOBAL  = intPreferencesKey("volume_boost_global")     // 0 = off, 1–10 = gain
    val PAUSE_ON_HEADPHONE_UNPLUG = booleanPreferencesKey("pause_on_headphone_unplug")
    val PAUSE_ON_NOTIFICATION = booleanPreferencesKey("pause_on_notification")
    val CONTINUOUS_PLAYBACK  = booleanPreferencesKey("continuous_playback")
    // true = advance to the next NEWER episode (higher pubDate); false = next OLDER episode. Default true.
    val AUTOPLAY_NEWER_NEXT  = booleanPreferencesKey("autoplay_newer_next")

    // ── Updates ───────────────────────────────────────────────────────────────
    val AUTO_UPDATE_ENABLED  = booleanPreferencesKey("auto_update_enabled")
    val UPDATE_INTERVAL_HOURS = intPreferencesKey("update_interval_hours")  // default 4
    val TURN_WIFI_DURING_UPDATE = booleanPreferencesKey("turn_wifi_during_update")
    val UPDATE_ON_WIFI_ONLY  = booleanPreferencesKey("update_on_wifi_only")

    // ── Downloads ─────────────────────────────────────────────────────────────
    val DOWNLOAD_ON_WIFI_ONLY   = booleanPreferencesKey("download_on_wifi_only")
    val GLOBAL_DOWNLOAD_COUNT   = intPreferencesKey("global_download_count") // default 1
    val GLOBAL_MAX_KEEP         = intPreferencesKey("global_max_keep")       // default 5; 0 = unlimited
    val GLOBAL_DELETE_OLDER_THAN_DAYS = intPreferencesKey("global_delete_older_than_days") // default 99999 = never
    val AUTO_DELETE_PLAYED      = booleanPreferencesKey("auto_delete_played")
    val DOWNLOAD_STORAGE_PATH   = stringPreferencesKey("download_storage_path")

    // ── Interface ─────────────────────────────────────────────────────────────
    val THEME                   = stringPreferencesKey("theme")              // "system" | "light" | "dark"
    val PRIMARY_SMARTPLAYLIST_ID = longPreferencesKey("primary_smartplaylist_id")
    val EPISODE_SORT_ORDER      = stringPreferencesKey("episode_sort_order") // EpisodeSortOrder.name

    // ── Scrobbling ────────────────────────────────────────────────────────────
    val SCROBBLE_ENABLED        = booleanPreferencesKey("scrobble_enabled")
    val SCROBBLE_USERNAME       = stringPreferencesKey("scrobble_username")

    // ── Notifications — 20 individual flags (spec §7.14) ─────────────────────
    val NOTIF_NEW_EPISODES      = booleanPreferencesKey("notif_new_episodes")
    val NOTIF_DOWNLOAD_COMPLETE = booleanPreferencesKey("notif_download_complete")
    val NOTIF_DOWNLOAD_FAILED   = booleanPreferencesKey("notif_download_failed")
    val NOTIF_FEED_UPDATE_FAILED = booleanPreferencesKey("notif_feed_update_failed")
    val NOTIF_PLAYBACK_CONTROLS = booleanPreferencesKey("notif_playback_controls")
    val NOTIF_EPISODE_FINISHED  = booleanPreferencesKey("notif_episode_finished")
    val NOTIF_QUEUE_EMPTY       = booleanPreferencesKey("notif_queue_empty")
    val NOTIF_SLEEP_TIMER       = booleanPreferencesKey("notif_sleep_timer")
    val NOTIF_REWIND_REMINDER   = booleanPreferencesKey("notif_rewind_reminder")
    val NOTIF_AUTO_ADD          = booleanPreferencesKey("notif_auto_add")
    val NOTIF_CLEANUP           = booleanPreferencesKey("notif_cleanup")
    val NOTIF_UPDATE_CHECK      = booleanPreferencesKey("notif_update_check")
    val NOTIF_SUBSCRIPTION_NEW  = booleanPreferencesKey("notif_subscription_new")
    val NOTIF_ERROR_GENERIC     = booleanPreferencesKey("notif_error_generic")
    val NOTIF_WIFI_REQUIRED     = booleanPreferencesKey("notif_wifi_required")
    val NOTIF_STORAGE_LOW       = booleanPreferencesKey("notif_storage_low")
    val NOTIF_SCROBBLE_ERROR    = booleanPreferencesKey("notif_scrobble_error")
    val NOTIF_GPODDER_SYNC      = booleanPreferencesKey("notif_gpodder_sync")
    val NOTIF_CHARGE_DOWNLOAD   = booleanPreferencesKey("notif_charge_download")
    val NOTIF_PLAYLIST_REBUILD  = booleanPreferencesKey("notif_playlist_rebuild")
}
