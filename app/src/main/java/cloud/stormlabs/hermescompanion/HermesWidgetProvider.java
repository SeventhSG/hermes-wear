package cloud.stormlabs.hermescompanion;

import android.app.PendingIntent;
import android.appwidget.AppWidgetManager;
import android.appwidget.AppWidgetProvider;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.view.View;
import android.widget.RemoteViews;

/** Home-screen "Hermes" widget: last reply preview, health dot, "Needs you" chip, Ask / voice / Chat. Push-updated, no polling. */
public class HermesWidgetProvider extends AppWidgetProvider {
    private static final String PREFS = "hermes_widget";

    @Override public void onUpdate(Context context, AppWidgetManager manager, int[] ids) { render(context, manager, ids); }

    /** Collapse markdown-ish whitespace into a short single-paragraph preview. */
    static String preview(String s, int max) {
        if (s == null) return "";
        String t = s.replaceAll("[*_`#>]+", "").replaceAll("\\s+", " ").trim();
        return t.length() <= max ? t : t.substring(0, max - 1).trim() + "…";
    }

    /** Called when a reply completes: cache title + preview + session and redraw. */
    static void record(Context c, String sessionId, String title, String reply) {
        String p = preview(reply, 200);
        if (sessionId == null || p.isEmpty()) return;
        c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString("sid", sessionId)
                .putString("title", title == null ? "" : title).putString("preview", p).apply();
        update(c);
    }

    /** Redraw every placed widget from the caches (reply, health, needs-you count). */
    static void update(Context c) {
        AppWidgetManager m = AppWidgetManager.getInstance(c);
        int[] ids = m.getAppWidgetIds(new ComponentName(c, HermesWidgetProvider.class));
        if (ids.length > 0) render(c, m, ids);
    }

    private static PendingIntent open(Context c, int code, Intent i) {
        return PendingIntent.getActivity(c, code, i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP),
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }

    private static void render(Context c, AppWidgetManager manager, int[] ids) {
        try {
            SharedPreferences p = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
            String sid = p.getString("sid", "");
            RemoteViews v = new RemoteViews(c.getPackageName(), R.layout.widget_hermes);
            v.setTextViewText(R.id.hwTitle, sid.isEmpty() ? "No conversation yet" : p.getString("title", ""));
            v.setTextViewText(R.id.hwPreview, p.getString("preview", ""));
            LabSummary s = LabSummary.cached(c);
            if (s != null) {
                v.setTextColor(R.id.hwDot, LabWidgetProvider.SIGNAL[s.level()]);
                v.setContentDescription(R.id.hwDot, "Homelab " + new String[]{"healthy", "needs attention", "critical"}[s.level()]);
            }
            int needs = new SettingsStore(c).pendingProposals().size();
            for (RunManager.LiveRun r : RunManager.get(c).activeRuns()) if (r.approval != null) needs++;
            v.setViewVisibility(R.id.hwNeeds, needs > 0 ? View.VISIBLE : View.GONE);
            v.setTextViewText(R.id.hwNeeds, "Needs you: " + needs);
            Intent main = new Intent(c, MainActivity.class);
            v.setOnClickPendingIntent(R.id.hwRoot, open(c, 120, new Intent(main)));
            v.setOnClickPendingIntent(R.id.hwAsk, open(c, 121, new Intent(main).putExtra(ChatActivity.EXTRA_PROMPT, "").putExtra(ChatActivity.EXTRA_AUTOSEND, false)));
            v.setOnClickPendingIntent(R.id.hwMic, open(c, 122, new Intent(main).putExtra(MainActivity.EXTRA_START_VOICE, true)));
            v.setOnClickPendingIntent(R.id.hwChat, open(c, 123, sid.isEmpty() ? new Intent(main)
                    : new Intent(main).putExtra(MainActivity.EXTRA_SESSION, sid).putExtra(ChatActivity.EXTRA_TITLE, p.getString("title", ""))));
            manager.updateAppWidget(ids, v);
        } catch (Throwable t) {
            CrashReporter.show(c, t);
        }
    }
}
