package mobi.beyondpod.revival.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.widget.RemoteViews
import mobi.beyondpod.revival.R

/**
 * AppWidget provider for the scrollable FeedListWidget (4×3 default size).
 *
 * The list is populated by [FeedListWidgetFactory] via [FeedListWidgetService].
 * Updates every hour or on explicit [ACTION_FEED_LIST_UPDATE] broadcast.
 */
class FeedListWidgetProvider : AppWidgetProvider() {

    companion object {
        const val ACTION_FEED_LIST_UPDATE = "mobi.beyondpod.revival.ACTION_FEED_LIST_UPDATE"

        /** Triggers an update of all FeedListWidget instances (e.g., after a feed refresh). */
        fun notifyUpdate(context: Context) {
            val mgr = AppWidgetManager.getInstance(context)
            val ids = mgr.getAppWidgetIds(ComponentName(context, FeedListWidgetProvider::class.java))
            if (ids.isEmpty()) return
            mgr.notifyAppWidgetViewDataChanged(ids, R.id.widget_episode_list)
        }
    }

    override fun onUpdate(context: Context, mgr: AppWidgetManager, ids: IntArray) {
        ids.forEach { update(context, mgr, it) }
    }

    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent)
        if (intent.action == ACTION_FEED_LIST_UPDATE) {
            val mgr = AppWidgetManager.getInstance(context)
            mgr.notifyAppWidgetViewDataChanged(
                mgr.getAppWidgetIds(ComponentName(context, FeedListWidgetProvider::class.java)),
                R.id.widget_episode_list
            )
        }
    }

    private fun update(context: Context, mgr: AppWidgetManager, widgetId: Int) {
        val views = RemoteViews(context.packageName, R.layout.widget_feed_list)

        // Wire ListView to RemoteViewsService
        val serviceIntent = Intent(context, FeedListWidgetService::class.java)
        views.setRemoteAdapter(R.id.widget_episode_list, serviceIntent)
        views.setEmptyView(R.id.widget_episode_list, R.id.widget_empty)

        // PendingIntent template: tapping an item opens the app (no deep link in Phase 8)
        val launchIntent = context.packageManager.getLaunchIntentForPackage(context.packageName)
        if (launchIntent != null) {
            val template = PendingIntent.getActivity(
                context, 0, launchIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            views.setPendingIntentTemplate(R.id.widget_episode_list, template)
        }

        mgr.updateAppWidget(widgetId, views)
    }
}
