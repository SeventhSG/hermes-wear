package cloud.stormlabs.hermescompanion;

import android.app.Activity;
import android.app.Dialog;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** nzb360-style add sheet (profile, folder, monitoring, search on add) and interactive release search. */
final class ArrAdd {
    private ArrAdd() {}

    private static final ExecutorService NET = Executors.newCachedThreadPool();

    private static void ui(Activity a, Runnable r) { a.runOnUiThread(() -> { if (!a.isDestroyed()) r.run(); }); }
    private static void toast(Activity a, String m) { ui(a, () -> Toast.makeText(a, m, Toast.LENGTH_SHORT).show()); }

    /** One-of-many chip group; returns the holder whose tag is the selected index. */
    private static LinearLayout choice(Activity a, LinearLayout fields, String label, String[] names, int selected, int accent) {
        TextView l = Ui.label(a, label, Ui.SUB);
        l.setPadding(Arr.dp(a, 4), Arr.dp(a, 12), 0, Arr.dp(a, 6));
        fields.addView(l);
        LinearLayout row = Arr.chipRow(a, fields);
        row.setTag(selected);
        Runnable[] draw = new Runnable[1];
        draw[0] = () -> {
            row.removeAllViews();
            for (int i = 0; i < names.length; i++) {
                int k = i;
                Arr.addChip(a, row, Arr.chip(a, names[i], k == (int) row.getTag(), accent, v -> { row.setTag(k); draw[0].run(); }));
            }
        };
        draw[0].run();
        return row;
    }

    /** Add a lookup result (series or movie) to Sonarr/Radarr. */
    static void add(Activity a, HubApi hub, JSONObject item, boolean movie, Runnable done) {
        String base = movie ? Arr.RAD : Arr.SON;
        int accent = movie ? Arr.RADARR : Arr.SONARR;
        Toast.makeText(a, "Loading options…", Toast.LENGTH_SHORT).show();
        NET.execute(() -> {
            try {
                JSONArray profiles = (JSONArray) hub.arr(base + "qualityprofile"), roots = (JSONArray) hub.arr(base + "rootfolder");
                if (profiles.length() == 0 || roots.length() == 0) throw new IllegalStateException("No quality profile or root folder");
                ui(a, () -> sheet(a, hub, item, movie, base, accent, profiles, roots, done));
            } catch (Exception e) { toast(a, "Failed: " + e.getMessage()); }
        });
    }

    private static void sheet(Activity a, HubApi hub, JSONObject item, boolean movie, String base, int accent,
                              JSONArray profiles, JSONArray roots, Runnable done) {
        LinearLayout f = new LinearLayout(a);
        f.setOrientation(LinearLayout.VERTICAL);
        f.setPadding(Arr.dp(a, 16), 0, Arr.dp(a, 16), Arr.dp(a, 8));

        LinearLayout head = new LinearLayout(a);
        ImageView p = new ImageView(a);
        p.setScaleType(ImageView.ScaleType.CENTER_CROP);
        p.setBackground(Ui.round(a, Ui.RAISED, 8)); p.setClipToOutline(true);
        head.addView(p, new LinearLayout.LayoutParams(Arr.dp(a, 72), Arr.dp(a, 108)));
        Arr.image(a, hub, p, Arr.art(movie ? "radarr" : "sonarr", item, "poster"), Arr.dp(a, 120));
        LinearLayout info = new LinearLayout(a); info.setOrientation(LinearLayout.VERTICAL); info.setPadding(Arr.dp(a, 12), 0, 0, 0);
        JSONArray seasons = item.optJSONArray("seasons");
        String meta = item.optInt("year") + (movie ? (item.optInt("runtime") > 0 ? " · " + item.optInt("runtime") + " min" : "")
                : (item.optString("network").isEmpty() ? "" : " · " + item.optString("network")) + (seasons == null ? "" : " · " + seasons.length() + " seasons"));
        info.addView(Ui.text(a, meta.trim(), 12, Ui.SUB, false));
        TextView ov = Ui.text(a, item.optString("overview"), 13, Ui.TEXT, false);
        ov.setMaxLines(5); ov.setEllipsize(TextUtils.TruncateAt.END);
        info.addView(ov);
        head.addView(info, new LinearLayout.LayoutParams(0, -2, 1));
        f.addView(head);

        String[] pn = new String[profiles.length()];
        for (int i = 0; i < pn.length; i++) pn[i] = profiles.optJSONObject(i).optString("name");
        String[] rn = new String[roots.length()];
        for (int i = 0; i < rn.length; i++) {
            JSONObject r = roots.optJSONObject(i);
            rn[i] = r.optString("path") + (r.has("freeSpace") ? " · " + Arr.size(r.optLong("freeSpace")) + " free" : "");
        }
        int hd = 0;
        for (int i = 0; i < pn.length; i++) if (pn[i].toLowerCase(Locale.ROOT).contains("1080")) { hd = i; break; }
        LinearLayout prof = choice(a, f, "Quality profile", pn, hd, accent);
        LinearLayout root = choice(a, f, "Root folder", rn, 0, accent);
        String[] monitorIds, monitorNames;
        LinearLayout monitor, kind = null;
        if (movie) {
            monitorNames = new String[]{"Announced", "In cinemas", "Released"};
            monitorIds = new String[]{"announced", "inCinemas", "released"};
            monitor = choice(a, f, "Minimum availability", monitorNames, 2, accent);
        } else {
            monitorNames = new String[]{"All episodes", "Future", "Missing", "First season", "Latest season", "None"};
            monitorIds = new String[]{"all", "future", "missing", "firstSeason", "lastSeason", "none"};
            monitor = choice(a, f, "Monitor", monitorNames, 0, accent);
            kind = choice(a, f, "Series type", new String[]{"Standard", "Anime", "Daily"}, 0, accent);
        }
        LinearLayout search = choice(a, f, "When added", new String[]{"Start searching", "Just add"}, 0, accent);
        LinearLayout kindRow = kind;

        Sheets.form(a, "Add " + item.optString("title"), f, "Add", () -> {
            int pi = (int) prof.getTag(), ri = (int) root.getTag(), mi = (int) monitor.getTag();
            boolean go = (int) search.getTag() == 0;
            NET.execute(() -> {
                try {
                    JSONObject body = new JSONObject(item.toString());
                    body.put("qualityProfileId", profiles.getJSONObject(pi).getInt("id"))
                            .put("rootFolderPath", roots.getJSONObject(ri).getString("path")).put("monitored", true);
                    if (movie) body.put("minimumAvailability", monitorIds[mi]).put("addOptions", new JSONObject().put("searchForMovie", go));
                    else body.put("seasonFolder", true).put("seriesType", new String[]{"standard", "anime", "daily"}[(int) kindRow.getTag()])
                            .put("addOptions", new JSONObject().put("monitor", monitorIds[mi]).put("searchForMissingEpisodes", go));
                    hub.arr("POST", base + (movie ? "movie" : "series"), body.toString(), Arr.JSON);
                    toast(a, "Added " + item.optString("title") + (go ? " · searching" : ""));
                    if (done != null) ui(a, done);
                } catch (Exception e) { toast(a, "Failed: " + e.getMessage()); }
            });
            return true;
        });
    }

    // ---------------------------------------------------------------- interactive search

    /** GET that the hub answers with 202 {"pending": true} while a long indexer search runs; polls until it's done. */
    static Object waitFor(HubApi hub, String path, java.util.function.IntConsumer elapsed) throws Exception {
        long start = System.currentTimeMillis();
        while (true) {
            Object r = hub.arr(path);
            if (!(r instanceof JSONObject) || !((JSONObject) r).optBoolean("pending")) return r;
            long s = (System.currentTimeMillis() - start) / 1000;
            if (s > 420) throw new IllegalStateException("the indexers took more than 7 minutes");
            elapsed.accept((int) s);
            Thread.sleep(3000);
        }
    }

    /** Lists indexer releases for {@code query} (e.g. "episodeId=12", "movieId=3") and grabs the one tapped. */
    static void releases(Activity a, HubApi hub, String svc, String query, String title) {
        String base = svc + "/api/v3/";
        int accent = svc.equals("radarr") ? Arr.RADARR : Arr.SONARR;
        ScrollView sv = new ScrollView(a);
        LinearLayout list = new LinearLayout(a);
        list.setOrientation(LinearLayout.VERTICAL);
        list.setPadding(Arr.dp(a, 12), 0, Arr.dp(a, 12), Arr.dp(a, 12));
        sv.addView(list);
        TextView wait = Ui.text(a, "Asking every indexer… this can take a minute.", 13, Ui.SUB, false);
        wait.setPadding(Arr.dp(a, 4), Arr.dp(a, 8), 0, Arr.dp(a, 8));
        list.addView(wait);
        list.addView(Ui.skeletonRows(a, 5));
        Dialog d = Sheets.tall(a, "Interactive search", title, sv);
        NET.execute(() -> {
            try {
                JSONArray r = (JSONArray) waitFor(hub, base + "release?" + query,
                        s -> ui(a, () -> wait.setText("Asking every indexer… " + s + " s. Slow indexers can take a few minutes.")));
                List<JSONObject> rel = new ArrayList<>();
                for (int i = 0; i < r.length(); i++) rel.add(r.getJSONObject(i));
                rel.sort((x, y) -> {
                    int c = Boolean.compare(y.optBoolean("approved"), x.optBoolean("approved"));
                    if (c != 0) return c;
                    c = Integer.compare(weight(y), weight(x));
                    return c != 0 ? c : Integer.compare(y.optInt("seeders"), x.optInt("seeders"));
                });
                ui(a, () -> {
                    list.removeAllViews();
                    if (rel.isEmpty()) { list.addView(Ui.text(a, "No releases found.", 14, Ui.SUB, false)); return; }
                    TextView n = Ui.text(a, rel.size() + " releases · best first", 12, Ui.SUB, false);
                    n.setPadding(Arr.dp(a, 4), Arr.dp(a, 4), 0, Arr.dp(a, 8));
                    list.addView(n);
                    for (JSONObject x : rel) list.addView(releaseRow(a, hub, base, x, accent, d));
                });
            } catch (Exception e) {
                ui(a, () -> { list.removeAllViews(); list.addView(Ui.text(a, "Search failed: " + e.getMessage(), 14, Ui.RED, false)); });
            }
        });
    }

    private static int weight(JSONObject r) { return r.optInt("qualityWeight", 0); }

    private static View releaseRow(Activity a, HubApi hub, String base, JSONObject r, int accent, Dialog d) {
        boolean ok = r.optBoolean("approved");
        LinearLayout row = new LinearLayout(a);
        row.setOrientation(LinearLayout.VERTICAL);
        row.setPadding(Arr.dp(a, 12), Arr.dp(a, 10), Arr.dp(a, 12), Arr.dp(a, 10));
        row.setBackground(Ui.card(a, 1));
        row.setAlpha(ok ? 1f : 0.6f);
        TextView t = Ui.text(a, r.optString("title"), 13, Ui.TEXT, true);
        t.setMaxLines(2); t.setEllipsize(TextUtils.TruncateAt.MIDDLE);
        row.addView(t);
        LinearLayout tags = new LinearLayout(a);
        tags.setGravity(Gravity.CENTER_VERTICAL);
        tags.setPadding(0, Arr.dp(a, 6), 0, 0);
        JSONObject q = r.optJSONObject("quality");
        String qn = q == null ? "" : q.optJSONObject("quality") == null ? "" : q.optJSONObject("quality").optString("name");
        addTag(a, tags, Arr.tag(a, qn, accent));
        addTag(a, tags, Arr.tag(a, Arr.size(r.optLong("size")), Ui.SUB));
        int age = r.optInt("age");
        addTag(a, tags, Arr.tag(a, age > 0 ? age + "d" : Math.max(1, (int) r.optDouble("ageHours")) + "h", Ui.SUB));
        if ("torrent".equals(r.optString("protocol"))) addTag(a, tags, Arr.tag(a, "▲" + r.optInt("seeders") + " ▼" + r.optInt("leechers"), r.optInt("seeders") > 0 ? Ui.GREEN : Ui.RED));
        row.addView(tags);
        TextView ix = Ui.text(a, r.optString("indexer"), 11, Ui.SUB, false);
        ix.setPadding(0, Arr.dp(a, 4), 0, 0);
        row.addView(ix);
        JSONArray rej = r.optJSONArray("rejections");
        if (!ok && rej != null && rej.length() > 0) {
            Object first = rej.opt(0);
            String why = first instanceof JSONObject ? ((JSONObject) first).optString("reason") : String.valueOf(first);
            TextView w = Ui.text(a, why, 11, Ui.AMBER, false);
            w.setMaxLines(2);
            row.addView(w);
        }
        row.setOnClickListener(v -> Sheets.confirm(a, ok ? "Grab this release?" : "Grab anyway?", r.optString("title"), "Grab", !ok, () -> NET.execute(() -> {
            try {
                hub.arr("POST", base + "release", new JSONObject().put("guid", r.optString("guid")).put("indexerId", r.optInt("indexerId")).toString(), Arr.JSON);
                toast(a, "Sent to the download client");
                ui(a, d::dismiss);
            } catch (Exception e) { toast(a, "Grab failed: " + e.getMessage()); }
        })));
        Ui.press(row);
        LinearLayout wrap = new LinearLayout(a);
        wrap.setPadding(0, 0, 0, Arr.dp(a, 6));
        wrap.addView(row, new LinearLayout.LayoutParams(-1, -2));
        return wrap;
    }

    private static void addTag(Activity a, LinearLayout tags, TextView t) {
        if (t.getText().length() == 0) return;
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, -2); lp.setMarginEnd(Arr.dp(a, 6));
        tags.addView(t, lp);
    }
}
