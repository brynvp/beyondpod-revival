package mobi.beyondpod.revival.widget

import android.content.Context
import android.content.SharedPreferences

/**
 * Thin SharedPreferences wrapper that bridges [PlaybackService] ↔ [PlayerWidgetProvider].
 *
 * PlaybackService writes state here whenever playback starts/stops/changes episode.
 * PlayerWidgetProvider reads it to populate the RemoteViews on each update.
 *
 * Using SharedPreferences (not DataStore) because widget providers are BroadcastReceivers
 * and need synchronous reads without a coroutine context.
 */
object PlaybackWidgetState {

    private const val PREFS_NAME = "beyondpod_widget_state"
    private const val KEY_EPISODE_ID    = "episode_id"
    private const val KEY_EPISODE_TITLE = "episode_title"
    private const val KEY_FEED_TITLE    = "feed_title"
    private const val KEY_ART_URL       = "art_url"
    private const val KEY_IS_PLAYING    = "is_playing"

    /** Broadcast sent by PlaybackService when state changes. Widget provider listens for this. */
    const val ACTION_WIDGET_UPDATE = "mobi.beyondpod.revival.ACTION_WIDGET_UPDATE"

    fun write(
        context: Context,
        episodeId: Long,
        episodeTitle: String,
        feedTitle: String,
        artUrl: String?,
        isPlaying: Boolean
    ) {
        prefs(context).edit()
            .putLong(KEY_EPISODE_ID, episodeId)
            .putString(KEY_EPISODE_TITLE, episodeTitle)
            .putString(KEY_FEED_TITLE, feedTitle)
            .putString(KEY_ART_URL, artUrl)
            .putBoolean(KEY_IS_PLAYING, isPlaying)
            .apply()
    }

    fun read(context: Context): State {
        val p = prefs(context)
        return State(
            episodeId    = p.getLong(KEY_EPISODE_ID, -1L),
            episodeTitle = p.getString(KEY_EPISODE_TITLE, null),
            feedTitle    = p.getString(KEY_FEED_TITLE, null),
            artUrl       = p.getString(KEY_ART_URL, null),
            isPlaying    = p.getBoolean(KEY_IS_PLAYING, false)
        )
    }

    fun clear(context: Context) {
        prefs(context).edit().clear().apply()
    }

    private fun prefs(context: Context): SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    data class State(
        val episodeId: Long,
        val episodeTitle: String?,
        val feedTitle: String?,
        val artUrl: String?,
        val isPlaying: Boolean
    ) {
        val hasEpisode: Boolean get() = episodeId > 0L
    }
}
