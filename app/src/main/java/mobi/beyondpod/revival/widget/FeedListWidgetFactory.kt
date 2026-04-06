package mobi.beyondpod.revival.widget

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.widget.RemoteViews
import android.widget.RemoteViewsService
import kotlinx.coroutines.runBlocking
import mobi.beyondpod.revival.R
import mobi.beyondpod.revival.data.local.BeyondPodDatabase
import mobi.beyondpod.revival.data.local.entity.EpisodeEntity
import mobi.beyondpod.revival.data.local.entity.PlayState
import mobi.beyondpod.revival.ui.theme.EpisodeInProgress
import mobi.beyondpod.revival.ui.theme.EpisodeNew
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Supplies rows for the [FeedListWidgetService] ListView.
 *
 * `onDataSetChanged()` is called on a background thread by the system — `runBlocking` here
 * is intentional and correct (this is NOT the main thread).
 */
class FeedListWidgetFactory(private val context: Context) : RemoteViewsService.RemoteViewsFactory {

    private val db by lazy { BeyondPodDatabase.getInstance(context) }
    private var episodes: List<EpisodeEntity> = emptyList()
    private val artCache = mutableMapOf<String, Bitmap?>()

    // ── Factory lifecycle ─────────────────────────────────────────────────────

    override fun onCreate() { loadEpisodes() }

    override fun onDataSetChanged() { loadEpisodes() }

    override fun onDestroy() {
        episodes = emptyList()
        artCache.clear()
    }

    // ── List data ─────────────────────────────────────────────────────────────

    override fun getCount(): Int = episodes.size

    override fun getItemId(position: Int): Long = episodes.getOrNull(position)?.id ?: position.toLong()

    override fun hasStableIds(): Boolean = true

    override fun getViewAt(position: Int): RemoteViews {
        val ep = episodes.getOrNull(position)
            ?: return RemoteViews(context.packageName, R.layout.widget_feed_list_item)

        val views = RemoteViews(context.packageName, R.layout.widget_feed_list_item)

        views.setTextViewText(R.id.widget_item_title, ep.title)
        views.setTextViewText(
            R.id.widget_item_meta,
            formatDate(ep.pubDate) + if (ep.duration > 0) " · ${formatDuration(ep.duration)}" else ""
        )

        // State bar colour
        val barColor = when (ep.playState) {
            PlayState.NEW         -> EpisodeNew.value.toInt()
            PlayState.IN_PROGRESS -> EpisodeInProgress.value.toInt()
            else                  -> android.graphics.Color.TRANSPARENT
        }
        views.setInt(R.id.widget_item_state_bar, "setBackgroundColor", barColor)

        // Artwork (cached)
        val art = ep.imageUrl?.let { url ->
            artCache.getOrPut(url) { downloadBitmap(url) }
        }
        if (art != null) {
            views.setImageViewBitmap(R.id.widget_item_artwork, art)
        } else {
            views.setImageViewResource(R.id.widget_item_artwork, R.mipmap.ic_launcher)
        }

        // Fill-in intent carries episode ID so the provider can open the right screen
        // (actual click handling done in FeedListWidgetProvider via setOnClickFillInIntent)
        val fillIn = android.content.Intent().apply {
            putExtra("episode_id", ep.id)
        }
        views.setOnClickFillInIntent(R.id.widget_item_artwork, fillIn)

        return views
    }

    override fun getLoadingView(): RemoteViews =
        RemoteViews(context.packageName, R.layout.widget_feed_list_item)

    override fun getViewTypeCount(): Int = 1

    // ── Helpers ───────────────────────────────────────────────────────────────

    private fun loadEpisodes() {
        runBlocking {
            // Load up to 20 recent NEW or IN_PROGRESS episodes across all feeds
            episodes = db.episodeDao().getRecentUnplayedForWidget(limit = 20)
        }
    }

    private fun downloadBitmap(url: String): Bitmap? = try {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.connectTimeout = 3_000
        conn.readTimeout    = 3_000
        conn.connect()
        BitmapFactory.decodeStream(conn.inputStream)
    } catch (_: Exception) { null }

    private fun formatDate(epochMs: Long): String {
        if (epochMs == 0L) return ""
        return SimpleDateFormat("MMM d", Locale.getDefault()).format(Date(epochMs))
    }

    private fun formatDuration(ms: Long): String {
        val h = ms / 3_600_000L
        val m = (ms % 3_600_000L) / 60_000L
        return if (h > 0) "${h}h ${m}m" else "${m}m"
    }
}
