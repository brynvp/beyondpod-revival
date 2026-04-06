package mobi.beyondpod.revival.widget

import android.content.Intent
import android.widget.RemoteViewsService

/**
 * RemoteViewsService that supplies the [FeedListWidgetFactory] to the system.
 * Required for scrollable collection widgets (ListView).
 */
class FeedListWidgetService : RemoteViewsService() {
    override fun onGetViewFactory(intent: Intent): RemoteViewsFactory =
        FeedListWidgetFactory(applicationContext)
}
