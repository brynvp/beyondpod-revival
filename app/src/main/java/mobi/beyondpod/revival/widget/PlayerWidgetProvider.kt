package mobi.beyondpod.revival.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.widget.RemoteViews
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import mobi.beyondpod.revival.R
import mobi.beyondpod.revival.service.PlaybackService
import java.net.HttpURLConnection
import java.net.URL

/**
 * AppWidget provider for the 4×1 PlayerWidget.
 *
 * Reads playback state from [PlaybackWidgetState] (SharedPreferences written by
 * [PlaybackService]). Responds to:
 * - [PlaybackWidgetState.ACTION_WIDGET_UPDATE] — pushed by PlaybackService on state change
 * - [ACTION_PLAY_PAUSE] / [ACTION_SKIP_BACK] / [ACTION_SKIP_FORWARD] — button taps
 */
class PlayerWidgetProvider : AppWidgetProvider() {

    companion object {
        const val ACTION_PLAY_PAUSE   = "mobi.beyondpod.revival.WIDGET_PLAY_PAUSE"
        const val ACTION_SKIP_BACK    = "mobi.beyondpod.revival.WIDGET_SKIP_BACK"
        const val ACTION_SKIP_FORWARD = "mobi.beyondpod.revival.WIDGET_SKIP_FORWARD"

        /** Called by PlaybackService to push a state change to all widget instances. */
        fun notifyUpdate(context: Context) {
            val mgr  = AppWidgetManager.getInstance(context)
            val ids  = mgr.getAppWidgetIds(ComponentName(context, PlayerWidgetProvider::class.java))
            if (ids.isEmpty()) return
            context.sendBroadcast(
                Intent(context, PlayerWidgetProvider::class.java).apply {
                    action = AppWidgetManager.ACTION_APPWIDGET_UPDATE
                    putExtra(AppWidgetManager.EXTRA_APPWIDGET_IDS, ids)
                }
            )
        }
    }

    override fun onUpdate(context: Context, mgr: AppWidgetManager, ids: IntArray) {
        ids.forEach { refresh(context, mgr, it) }
    }

    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent)
        when (intent.action) {
            PlaybackWidgetState.ACTION_WIDGET_UPDATE -> {
                val mgr = AppWidgetManager.getInstance(context)
                mgr.getAppWidgetIds(ComponentName(context, PlayerWidgetProvider::class.java))
                    .forEach { refresh(context, mgr, it) }
            }
            ACTION_PLAY_PAUSE   -> context.startService(
                Intent(context, PlaybackService::class.java).also { it.action = PlaybackService.ACTION_TOGGLE_PLAYBACK }
            )
            ACTION_SKIP_BACK    -> context.startService(
                Intent(context, PlaybackService::class.java).also { it.action = PlaybackService.ACTION_SKIP_BACK }
            )
            ACTION_SKIP_FORWARD -> context.startService(
                Intent(context, PlaybackService::class.java).also { it.action = PlaybackService.ACTION_SKIP_FORWARD }
            )
        }
    }

    private fun refresh(context: Context, mgr: AppWidgetManager, widgetId: Int) {
        val state = PlaybackWidgetState.read(context)
        val views = buildViews(context, state)
        mgr.updateAppWidget(widgetId, views)

        // Async artwork load — update again once bitmap is ready
        if (state.hasEpisode && !state.artUrl.isNullOrEmpty()) {
            CoroutineScope(Dispatchers.IO).launch {
                val bmp = downloadBitmap(state.artUrl)
                if (bmp != null) {
                    views.setImageViewBitmap(R.id.widget_artwork, bmp)
                    mgr.updateAppWidget(widgetId, views)
                }
            }
        }
    }

    private fun buildViews(context: Context, state: PlaybackWidgetState.State): RemoteViews {
        val views = RemoteViews(context.packageName, R.layout.widget_player)

        views.setTextViewText(
            R.id.widget_episode_title,
            if (state.hasEpisode) state.episodeTitle.orEmpty()
            else context.getString(R.string.widget_no_episode)
        )
        views.setTextViewText(R.id.widget_feed_title, state.feedTitle.orEmpty())

        views.setImageViewResource(
            R.id.widget_play_pause,
            if (state.isPlaying) R.drawable.ic_widget_pause else R.drawable.ic_widget_play
        )
        if (!state.hasEpisode || state.artUrl.isNullOrEmpty()) {
            views.setImageViewResource(R.id.widget_artwork, R.mipmap.ic_launcher)
        }

        views.setOnClickPendingIntent(R.id.widget_play_pause,  broadcastPi(context, ACTION_PLAY_PAUSE))
        views.setOnClickPendingIntent(R.id.widget_skip_back,    broadcastPi(context, ACTION_SKIP_BACK))
        views.setOnClickPendingIntent(R.id.widget_skip_forward, broadcastPi(context, ACTION_SKIP_FORWARD))

        // Tap title/artwork → open app
        context.packageManager.getLaunchIntentForPackage(context.packageName)?.let { launch ->
            val pi = PendingIntent.getActivity(context, 0, launch,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
            views.setOnClickPendingIntent(R.id.widget_artwork, pi)
            views.setOnClickPendingIntent(R.id.widget_episode_title, pi)
        }

        return views
    }

    private fun broadcastPi(context: Context, action: String): PendingIntent =
        PendingIntent.getBroadcast(
            context, action.hashCode(),
            Intent(context, PlayerWidgetProvider::class.java).apply { this.action = action },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

    private fun downloadBitmap(url: String): Bitmap? = try {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.connectTimeout = 5_000
        conn.readTimeout    = 5_000
        conn.connect()
        BitmapFactory.decodeStream(conn.inputStream)
    } catch (_: Exception) { null }
}
