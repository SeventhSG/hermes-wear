package cloud.stormlabs.hermescompanion;

import android.app.PendingIntent;
import android.appwidget.AppWidgetManager;
import android.appwidget.AppWidgetProvider;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.view.View;
import android.widget.RemoteViews;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.Date;

/** Home-screen Media: download speed, queue counts and the next episodes as posters (nzb360-style). */
public class MediaWidgetProvider extends AppWidgetProvider {
    static final String ACTION_REFRESH = "cloud.stormlabs.hermescompanion.MEDIA_REFRESH";
    private static final int[] POSTERS = {R.id.mediaP1, R.id.mediaP2, R.id.mediaP3, R.id.mediaP4};

    @Override public void onUpdate(Context context, AppWidgetManager manager, int[] ids) { refresh(context, goAsync()); }

    @Override public void onReceive(Context context, Intent intent) {
        if (ACTION_REFRESH.equals(intent.getAction())) refresh(context, goAsync());
        else super.onReceive(context, intent);
    }

    /** Called by the app after it changes something, so the widget doesn't lag. */
    static void update(Context c) {
        c.sendBroadcast(new Intent(c, MediaWidgetProvider.class).setAction(ACTION_REFRESH));
    }

    private static void refresh(Context context, PendingResult result) {
        new Thread(() -> {
            try {
                AppWidgetManager m = AppWidgetManager.getInstance(context);
                int[] ids = m.getAppWidgetIds(new ComponentName(context, MediaWidgetProvider.class));
                if (ids.length > 0) m.updateAppWidget(ids, build(context));
            } catch (Throwable t) {
                CrashReporter.show(context, t);
            } finally { result.finish(); }
        }).start();
    }

    private static RemoteViews build(Context c) {
        RemoteViews v = new RemoteViews(c.getPackageName(), R.layout.widget_media);
        v.setOnClickPendingIntent(R.id.mediaRoot, PendingIntent.getActivity(c, 120,
                new Intent(c, MediaActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP),
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE));
        v.setOnClickPendingIntent(R.id.mediaRefresh, PendingIntent.getBroadcast(c, 121,
                new Intent(c, MediaWidgetProvider.class).setAction(ACTION_REFRESH), PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE));
        SettingsStore store = new SettingsStore(c);
        if (store.hubUrl().isEmpty()) { v.setTextViewText(R.id.mediaInfo, "Connect the hub in Hermes Companion"); return v; }
        HubApi hub = new HubApi(store.hubUrl(), store.hubKey());
        if (!MediaModules.has(c, "qbit")) v.setTextViewText(R.id.mediaInfo, MediaModules.any(c) ? "No downloader set up on the hub" : "No media services set up on the hub");
        else try {
            JSONObject t = (JSONObject) hub.arr(Arr.QB + "transfer/info");
            JSONArray dl = (JSONArray) hub.arr(Arr.QB + "torrents/info?filter=downloading");
            v.setTextViewText(R.id.mediaSpeed, "↓ " + Arr.speed(t.optLong("dl_info_speed")));
            v.setTextViewText(R.id.mediaInfo, "↑ " + Arr.speed(t.optLong("up_info_speed")) + " · " + dl.length() + " downloading");
        } catch (Exception e) {
            v.setTextViewText(R.id.mediaSpeed, "↓ --");
            v.setTextViewText(R.id.mediaInfo, "qBittorrent unreachable");
        }
        if (MediaModules.has(c, "sonarr")) try {
            long now = System.currentTimeMillis();
            JSONArray cal = (JSONArray) hub.arr(Arr.SON + "calendar?includeSeries=true&start=" + Arr.ymd(now) + "&end=" + Arr.ymd(now + 7 * 86400000L));
            int shown = 0;
            for (int i = 0; i < cal.length() && shown < POSTERS.length; i++) {
                JSONObject s = cal.getJSONObject(i).optJSONObject("series");
                if (s == null) continue;
                try {
                    byte[] b = hub.arrBytes(Arr.art("sonarr", s, "poster").substring(4));
                    BitmapFactory.Options o = new BitmapFactory.Options(); o.inSampleSize = 2;
                    Bitmap bm = BitmapFactory.decodeByteArray(b, 0, b.length, o);
                    if (bm != null) { v.setImageViewBitmap(POSTERS[shown], bm); v.setViewVisibility(POSTERS[shown], View.VISIBLE); shown++; }
                } catch (Exception ignored) { }
            }
            for (int i = shown; i < POSTERS.length; i++) v.setViewVisibility(POSTERS[i], View.INVISIBLE);
            v.setViewVisibility(R.id.mediaPosters, shown == 0 ? View.GONE : View.VISIBLE);
            if (cal.length() > 0) {
                JSONObject e = cal.getJSONObject(0), s = e.optJSONObject("series");
                Date d = Arr.date(e.optString("airDateUtc"));
                v.setTextViewText(R.id.mediaNext, "Next: " + (s == null ? "" : s.optString("title") + " ") + Arr.ep(e) + (d == null ? "" : " · " + Arr.when(d) + " " + Arr.clock(d)));
            } else v.setTextViewText(R.id.mediaNext, "Nothing airing this week");
        } catch (Exception e) {
            v.setTextViewText(R.id.mediaNext, "Sonarr unreachable");
        }
        return v;
    }
}
