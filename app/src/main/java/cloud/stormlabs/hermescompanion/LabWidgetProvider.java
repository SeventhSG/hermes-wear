package cloud.stormlabs.hermescompanion;

import android.app.PendingIntent;
import android.appwidget.AppWidgetManager;
import android.appwidget.AppWidgetProvider;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.text.format.DateUtils;
import android.view.View;
import android.widget.RemoteViews;

import java.util.Locale;

/** Home-screen "Homelab" status: renders the cached LabSummary instantly, refreshes it off-thread (periodic + refresh button). */
public class LabWidgetProvider extends AppWidgetProvider {
    static final String ACTION_REFRESH = "cloud.stormlabs.hermescompanion.LAB_REFRESH";
    /** Signal palette (teal ok, amber watch, coral critical) plus the widget's text tones; the widget stays dark in every style. */
    static final int[] SIGNAL = {0xFF3DDBB0, 0xFFF2B544, 0xFFFF6B6B};
    static final int TEXT = 0xFFE8ECF2, SUB = 0xFFA3ACBA;

    @Override public void onUpdate(Context context, AppWidgetManager manager, int[] ids) {
        render(context, manager, ids);
        refresh(context, goAsync());
    }

    @Override public void onReceive(Context context, Intent intent) {
        if (ACTION_REFRESH.equals(intent.getAction())) refresh(context, goAsync());
        else super.onReceive(context, intent);
    }

    private static void refresh(Context context, PendingResult result) {
        new Thread(() -> {
            try {
                if (LabSummary.refresh(context)) {
                    AppWidgetManager m = AppWidgetManager.getInstance(context);
                    render(context, m, m.getAppWidgetIds(new ComponentName(context, LabWidgetProvider.class)));
                }
            } finally { result.finish(); }
        }).start();
    }

    private static void render(Context context, AppWidgetManager manager, int[] ids) {
        try {
            LabSummary s = LabSummary.cached(context);
            RemoteViews v = new RemoteViews(context.getPackageName(), R.layout.widget_lab);
            if (s == null) {
                v.setTextViewText(R.id.labNode, "Homelab");
                v.setTextViewText(R.id.labProblem, "No data yet - tap refresh");
                v.setViewVisibility(R.id.labStats, View.GONE);
                v.setViewVisibility(R.id.labMetric, View.GONE);
                v.setViewVisibility(R.id.labGuests, View.GONE);
                v.setViewVisibility(R.id.labUpdated, View.GONE);
            } else {
                int[] colors = SIGNAL;
                OpsText.Worst w = OpsText.worst(s.cpu, s.mem, s.worstPct, s.temp);
                v.setTextViewText(R.id.labMetric, w == null ? "--" : w.display);
                v.setTextColor(R.id.labMetric, s.level() == LabSummary.OK ? TEXT : colors[s.level()]);
                v.setViewVisibility(R.id.labMetric, View.VISIBLE);
                v.setTextColor(R.id.labDot, colors[s.level()]);
                v.setContentDescription(R.id.labDot, "Homelab " + new String[]{"healthy", "needs attention", "critical"}[s.level()]);
                v.setTextViewText(R.id.labNode, (s.node.isEmpty() ? "Homelab" : s.node) + (w == null ? "" : " · " + w.label.toLowerCase(Locale.ROOT)));
                v.setTextViewText(R.id.labStats, "CPU " + LabSummary.pct(s.cpu) + " · RAM " + LabSummary.pct(s.mem)
                        + " · Disk " + LabSummary.pct(s.disk) + (Double.isNaN(s.temp) ? "" : String.format(Locale.US, " · %.0f°C", s.temp)));
                v.setTextViewText(R.id.labGuests, s.guestsUp + "/" + s.guests + " guests running");
                v.setTextViewText(R.id.labProblem, s.problem.isEmpty() ? "All systems normal" : s.problem);
                v.setTextColor(R.id.labProblem, s.problem.isEmpty() ? SUB : colors[s.level()]);
                v.setTextViewText(R.id.labUpdated, "updated " + DateUtils.getRelativeTimeSpanString(
                        s.updatedMs, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS));
            }
            Intent open = new Intent(context, StatusActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
            v.setOnClickPendingIntent(R.id.labRoot, PendingIntent.getActivity(context, 110, open,
                    PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE));
            v.setOnClickPendingIntent(R.id.labRefresh, PendingIntent.getBroadcast(context, 111,
                    new Intent(context, LabWidgetProvider.class).setAction(ACTION_REFRESH),
                    PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE));
            manager.updateAppWidget(ids, v);
        } catch (Throwable t) {
            // A widget that cannot inflate must not take the launcher down with it.
            CrashReporter.show(context, t);
        }
    }
}
