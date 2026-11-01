package cloud.stormlabs.hermescompanion;

import android.app.PendingIntent;
import android.appwidget.AppWidgetManager;
import android.appwidget.AppWidgetProvider;
import android.content.Context;
import android.content.Intent;
import android.widget.RemoteViews;

/**
 * Home-screen widget: one tap to record, one tap to type.
 *
 * Deliberately dumb — it launches the capture lane rather than talking to the API itself. A widget
 * cannot hold a session, and a background update every 30 minutes to show a count would cost more
 * than it tells you. `updatePeriodMillis="0"` keeps it from waking the device at all.
 */
public class CaptureWidgetProvider extends AppWidgetProvider {

    @Override public void onUpdate(Context context, AppWidgetManager manager, int[] ids) {
        for (int id : ids) {
            try {
                RemoteViews views = new RemoteViews(context.getPackageName(), R.layout.widget_capture);
                views.setOnClickPendingIntent(R.id.widgetRoot, open(context, 100, true));
                views.setOnClickPendingIntent(R.id.widgetGlyph, open(context, 101, true));
                views.setOnClickPendingIntent(R.id.widgetType, open(context, 102, false));
                manager.updateAppWidget(id, views);
            } catch (Throwable t) {
                // A widget that cannot inflate must not take the launcher down with it.
                CrashReporter.show(context, t);
            }
        }
    }

    private PendingIntent open(Context context, int requestCode, boolean record) {
        Intent intent = new Intent(context, EssentialsActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP)
                .putExtra(record ? EssentialsActivity.EXTRA_RECORD : EssentialsActivity.EXTRA_COMPOSE, true);
        return PendingIntent.getActivity(context, requestCode, intent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }
}
