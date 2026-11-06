package cloud.stormlabs.hermescompanion;

import android.app.Activity;
import android.content.Intent;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Series (Sonarr) or movie (Radarr) page in the nzb360 mould: backdrop, poster, actions, seasons/episodes or file, history. */
public final class MediaDetailActivity extends Activity {
    static final String EXTRA_SVC = "svc", EXTRA_ID = "id";

    private interface Job<T> { T run() throws Exception; }

    private final ExecutorService net = Executors.newFixedThreadPool(4);
    private HubApi hub;
    private String svc, base;
    private boolean movie;
    private int id, accent;
    private JSONObject item;
    private JSONArray profiles = new JSONArray();
    private LinearLayout content;
    private final Map<Integer, List<JSONObject>> episodes = new HashMap<>();
    private final Map<Integer, JSONObject> files = new HashMap<>();
    private final Map<Integer, Boolean> open = new HashMap<>();

    private int dp(int n) { return Ui.dp(this, n); }
    private LinearLayout.LayoutParams wrap() { return new LinearLayout.LayoutParams(-1, -2); }

    @Override public void onCreate(Bundle b) {
        super.onCreate(b);
        Ui.legacyWindowInsets(this);
        SettingsStore store = new SettingsStore(this);
        hub = new HubApi(store.hubUrl(), store.hubKey());
        svc = getIntent().getStringExtra(EXTRA_SVC);
        movie = "radarr".equals(svc);
        base = svc + "/api/v3/";
        id = getIntent().getIntExtra(EXTRA_ID, 0);
        accent = movie ? Arr.RADARR : Arr.SONARR;
        getWindow().setStatusBarColor(Color.TRANSPARENT);
        getWindow().setNavigationBarColor(Ui.BG);

        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(Ui.BG);
        ScrollView sv = new ScrollView(this);
        content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        sv.addView(content);
        root.addView(sv, new FrameLayout.LayoutParams(-1, -1));
        ImageButton back = Ui.icon(this, R.drawable.ic_back, "Back");
        back.setImageTintList(ColorStateList.valueOf(Color.WHITE));
        back.setBackground(Ui.round(this, 0x66000000, 99));
        back.setOnClickListener(v -> finish());
        FrameLayout.LayoutParams bl = new FrameLayout.LayoutParams(dp(44), dp(44), Gravity.TOP | Gravity.START);
        bl.setMargins(dp(12), dp(36), 0, 0);
        root.addView(back, bl);
        setContentView(root);
        content.addView(Ui.skeletonRows(this, 6), wrap());
        load();
    }

    @Override protected void onDestroy() { super.onDestroy(); net.shutdownNow(); }

    private <T> void bg(Job<T> job, java.util.function.Consumer<T> ok, java.util.function.Consumer<String> fail) {
        net.execute(() -> {
            try { T r = job.run(); runOnUiThread(() -> { if (!isDestroyed()) ok.accept(r); }); }
            catch (Exception e) { String m = String.valueOf(e.getMessage()); runOnUiThread(() -> { if (!isDestroyed() && fail != null) fail.accept(m); }); }
        });
    }

    private void act(Job<?> job, String done, Runnable then) {
        net.execute(() -> {
            String m;
            try { job.run(); m = done; } catch (Exception e) { m = "Failed: " + e.getMessage(); }
            String msg = m;
            runOnUiThread(() -> { if (isDestroyed()) return; Toast.makeText(this, msg, Toast.LENGTH_SHORT).show(); if (then != null) then.run(); });
        });
    }

    private Object command(JSONObject c) throws Exception { return hub.arr("POST", base + "command", c.toString(), Arr.JSON); }

    private void load() {
        bg(() -> {
            JSONObject it = (JSONObject) hub.arr(base + (movie ? "movie/" : "series/") + id);
            profiles = (JSONArray) hub.arr(base + "qualityprofile");
            if (!movie) {
                JSONArray eps = (JSONArray) hub.arr(base + "episode?seriesId=" + id);
                JSONArray fs = (JSONArray) hub.arr(base + "episodefile?seriesId=" + id);
                episodes.clear(); files.clear();
                for (int i = 0; i < eps.length(); i++) {
                    JSONObject e = eps.getJSONObject(i);
                    episodes.computeIfAbsent(e.optInt("seasonNumber"), k -> new ArrayList<>()).add(e);
                }
                for (List<JSONObject> l : episodes.values()) l.sort((x, y) -> Integer.compare(y.optInt("episodeNumber"), x.optInt("episodeNumber")));
                for (int i = 0; i < fs.length(); i++) files.put(fs.getJSONObject(i).optInt("id"), fs.getJSONObject(i));
            }
            String profile = "";
            for (int i = 0; i < profiles.length(); i++) if (profiles.getJSONObject(i).optInt("id") == it.optInt("qualityProfileId")) profile = profiles.getJSONObject(i).optString("name");
            it.put("_profile", profile);
            return it;
        }, it -> { item = it; render(); }, m -> { content.removeAllViews(); TextView t = Ui.text(this, "Couldn't load: " + m, 14, Ui.RED, false); t.setPadding(dp(20), dp(100), dp(20), 0); content.addView(t); });
    }

    // ---------------------------------------------------------------- page

    private void render() {
        content.removeAllViews();
        FrameLayout hero = new FrameLayout(this);
        ImageView backdrop = new ImageView(this);
        backdrop.setScaleType(ImageView.ScaleType.CENTER_CROP);
        backdrop.setBackgroundColor(Arr.alpha(accent, 0x33));
        hero.addView(backdrop, new FrameLayout.LayoutParams(-1, -1));
        Arr.image(this, hub, backdrop, Arr.art(svc, item, "fanart"), getResources().getDisplayMetrics().widthPixels);
        View scrim = new View(this);
        scrim.setBackground(new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, new int[]{0x55000000, Arr.alpha(Ui.BG, 0x00), Arr.alpha(Ui.BG, 0xcc), Ui.BG}));
        hero.addView(scrim, new FrameLayout.LayoutParams(-1, -1));
        content.addView(hero, new LinearLayout.LayoutParams(-1, dp(300)));

        LinearLayout head = new LinearLayout(this);
        head.setGravity(Gravity.BOTTOM);
        head.setPadding(dp(16), 0, dp(16), 0);
        ImageView poster = new ImageView(this);
        poster.setScaleType(ImageView.ScaleType.CENTER_CROP);
        poster.setBackground(Ui.round(this, Ui.RAISED, 12));
        poster.setClipToOutline(true);
        poster.setElevation(dp(8));
        head.addView(poster, new LinearLayout.LayoutParams(dp(112), dp(168)));
        Arr.image(this, hub, poster, Arr.art(svc, item, "poster"), dp(224));
        LinearLayout info = new LinearLayout(this);
        info.setOrientation(LinearLayout.VERTICAL);
        info.setPadding(dp(14), 0, 0, dp(4));
        TextView title = Ui.text(this, item.optString("title"), 24, Ui.TEXT, true);
        title.setTypeface(Ui.WORD_HERO);
        title.setMaxLines(3); title.setEllipsize(TextUtils.TruncateAt.END);
        info.addView(title);
        List<String> meta = new ArrayList<>();
        if (item.optInt("year") > 0) meta.add(String.valueOf(item.optInt("year")));
        String org = movie ? item.optString("studio") : item.optString("network");
        if (!org.isEmpty()) meta.add(org);
        if (item.optInt("runtime") > 0) meta.add(item.optInt("runtime") + " min");
        if (!item.optString("certification").isEmpty()) meta.add(item.optString("certification"));
        TextView m = Ui.text(this, TextUtils.join(" · ", meta), 13, Ui.SUB, false);
        m.setPadding(0, dp(4), 0, 0);
        info.addView(m);
        LinearLayout tags = new LinearLayout(this);
        tags.setPadding(0, dp(8), 0, 0);
        String status = item.optString("status");
        addTag(tags, Arr.tag(this, status.isEmpty() ? "" : Character.toUpperCase(status.charAt(0)) + status.substring(1), accent));
        addTag(tags, Arr.tag(this, item.optBoolean("monitored") ? "Monitored" : "Unmonitored", item.optBoolean("monitored") ? Ui.GREEN : Ui.SUB));
        for (String r : ratings()) addTag(tags, Arr.tag(this, r, Ui.AMBER));
        info.addView(tags);
        head.addView(info, new LinearLayout.LayoutParams(0, -2, 1));
        LinearLayout.LayoutParams hl = wrap(); hl.topMargin = -dp(120);
        content.addView(head, hl);

        LinearLayout body = new LinearLayout(this);
        body.setOrientation(LinearLayout.VERTICAL);
        body.setPadding(dp(16), dp(16), dp(16), dp(32));
        content.addView(body, wrap());

        actions(body);
        stats(body);

        TextView ov = Ui.text(this, item.optString("overview"), 14, Ui.TEXT, false);
        ov.setLineSpacing(0, 1.15f);
        ov.setMaxLines(4); ov.setEllipsize(TextUtils.TruncateAt.END);
        ov.setPadding(dp(2), dp(14), dp(2), 0);
        ov.setOnClickListener(v -> { boolean all = ov.getMaxLines() > 4; ov.setMaxLines(all ? 4 : 100); ov.setEllipsize(all ? TextUtils.TruncateAt.END : null); });
        body.addView(ov);
        JSONArray genres = item.optJSONArray("genres");
        if (genres != null && genres.length() > 0) {
            LinearLayout g = Arr.chipRow(this, body);
            ((LinearLayout.LayoutParams) ((View) g.getParent()).getLayoutParams()).topMargin = dp(10);
            for (int i = 0; i < genres.length(); i++) Arr.addChip(this, g, Arr.tag(this, genres.optString(i), Ui.SUB));
        }

        if (movie) movieFile(body); else seasons(body);
        history(body);

        TextView remove = Ui.ghostButton(this, movie ? "Remove from Radarr" : "Remove from Sonarr", Ui.RED);
        remove.setOnClickListener(v -> Sheets.actions(this, "Remove " + item.optString("title") + "?", "Deleting files can't be undone",
                Sheets.Item.danger(R.drawable.ic_delete, "Remove, keep files", null, () -> delete(false)),
                Sheets.Item.danger(R.drawable.ic_delete, "Remove and delete files", null, () -> delete(true))));
        LinearLayout.LayoutParams rl = wrap(); rl.topMargin = dp(24);
        body.addView(remove, rl);
    }

    private List<String> ratings() {
        List<String> out = new ArrayList<>();
        JSONObject r = item.optJSONObject("ratings");
        if (r == null) return out;
        if (r.has("value") && r.optDouble("value") > 0) out.add(String.format(Locale.US, "★ %.1f", r.optDouble("value")));
        JSONObject imdb = r.optJSONObject("imdb"), tmdb = r.optJSONObject("tmdb"), rt = r.optJSONObject("rottenTomatoes");
        if (imdb != null && imdb.optDouble("value") > 0) out.add(String.format(Locale.US, "IMDb %.1f", imdb.optDouble("value")));
        else if (tmdb != null && tmdb.optDouble("value") > 0) out.add(String.format(Locale.US, "TMDB %.1f", tmdb.optDouble("value")));
        if (rt != null && rt.optDouble("value") > 0) out.add(Math.round(rt.optDouble("value")) + "% RT");
        return out;
    }

    private void addTag(LinearLayout row, TextView t) {
        if (t.getText().length() == 0) return;
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, -2); lp.setMarginEnd(dp(6));
        row.addView(t, lp);
    }

    private void actions(LinearLayout body) {
        LinearLayout row = new LinearLayout(this);
        row.setBaselineAligned(false);
        boolean mon = item.optBoolean("monitored");
        tile(row, R.drawable.ic_search, "Search", () -> act(() -> command(movie
                ? new JSONObject().put("name", "MoviesSearch").put("movieIds", new JSONArray().put(id))
                : new JSONObject().put("name", "SeriesSearch").put("seriesId", id)), "Search started", null));
        if (movie) tile(row, R.drawable.ic_tools, "Interactive", () -> ArrAdd.releases(this, hub, svc, "movieId=" + id, item.optString("title")));
        tile(row, R.drawable.ic_pin, mon ? "Monitored" : "Monitor", () -> act(() -> {
            item.put("monitored", !mon);
            return hub.arr("PUT", base + (movie ? "movie/" : "series/") + id, strip(item).toString(), Arr.JSON);
        }, mon ? "Unmonitored" : "Monitoring", this::load));
        tile(row, R.drawable.ic_edit, "Edit", this::editSheet);
        tile(row, R.drawable.ic_more, "More", () -> Sheets.actions(this, item.optString("title"), null,
                Sheets.Item.of(R.drawable.ic_refresh, accent, "Refresh metadata", "Pull new info and artwork", () -> act(() -> command(movie
                        ? new JSONObject().put("name", "RefreshMovie").put("movieIds", new JSONArray().put(id))
                        : new JSONObject().put("name", "RefreshSeries").put("seriesId", id)), "Refreshing", this::load)),
                Sheets.Item.of(R.drawable.ic_file, accent, "Rescan files", "Pick up files added or removed on disk", () -> act(() -> command(movie
                        ? new JSONObject().put("name", "RescanMovie").put("movieId", id)
                        : new JSONObject().put("name", "RescanSeries").put("seriesId", id)), "Rescanning", this::load)),
                Sheets.Item.of(R.drawable.ic_edit, accent, "Rename files", "Apply your naming format to existing files", () -> act(() -> command(movie
                        ? new JSONObject().put("name", "RenameMovie").put("movieIds", new JSONArray().put(id))
                        : new JSONObject().put("name", "RenameSeries").put("seriesIds", new JSONArray().put(id))), "Renaming", this::load))));
        String trailer = item.optString("youTubeTrailerId");
        if (movie && !trailer.isEmpty()) tile(row, R.drawable.ic_play, "Trailer", () ->
                startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse("https://www.youtube.com/watch?v=" + trailer))));
        body.addView(row, wrap());
    }

    private LinearLayout choice(LinearLayout f, String label, String[] names, int selected) {
        TextView l = Ui.label(this, label, Ui.SUB);
        l.setPadding(dp(4), dp(12), 0, dp(6));
        f.addView(l);
        LinearLayout row = Arr.chipRow(this, f);
        row.setTag(Math.max(0, selected));
        Runnable[] draw = new Runnable[1];
        draw[0] = () -> {
            row.removeAllViews();
            for (int i = 0; i < names.length; i++) {
                int k = i;
                Arr.addChip(this, row, Arr.chip(this, names[i], k == (int) row.getTag(), accent, v -> { row.setTag(k); draw[0].run(); }));
            }
        };
        draw[0].run();
        return row;
    }

    private static int indexOf(String[] a, String v) { for (int i = 0; i < a.length; i++) if (a[i].equals(v)) return i; return 0; }

    /** Quality profile, monitoring and type/availability, saved with one PUT. */
    private void editSheet() {
        LinearLayout f = new LinearLayout(this);
        f.setOrientation(LinearLayout.VERTICAL);
        f.setPadding(dp(16), 0, dp(16), dp(8));
        String[] pn = new String[profiles.length()];
        int cur = 0;
        for (int i = 0; i < pn.length; i++) { pn[i] = profiles.optJSONObject(i).optString("name"); if (profiles.optJSONObject(i).optInt("id") == item.optInt("qualityProfileId")) cur = i; }
        LinearLayout prof = choice(f, "Quality profile", pn, cur);
        LinearLayout mon = choice(f, "Monitored", new String[]{"Yes", "No"}, item.optBoolean("monitored") ? 0 : 1);
        String[] kindIds = movie ? new String[]{"announced", "inCinemas", "released"} : new String[]{"standard", "anime", "daily"};
        LinearLayout kind = choice(f, movie ? "Minimum availability" : "Series type", movie ? new String[]{"Announced", "In cinemas", "Released"} : new String[]{"Standard", "Anime", "Daily"},
                indexOf(kindIds, item.optString(movie ? "minimumAvailability" : "seriesType")));
        LinearLayout folder = movie ? null : choice(f, "Season folders", new String[]{"Yes", "No"}, item.optBoolean("seasonFolder", true) ? 0 : 1);
        LinearLayout newItems = movie ? null : choice(f, "Monitor new seasons", new String[]{"All", "None"}, "none".equals(item.optString("monitorNewItems")) ? 1 : 0);
        Sheets.form(this, "Edit " + item.optString("title"), f, "Save", () -> {
            act(() -> {
                JSONObject b = strip(item);
                if (profiles.length() > 0) b.put("qualityProfileId", profiles.getJSONObject((int) prof.getTag()).getInt("id"));
                b.put("monitored", (int) mon.getTag() == 0);
                b.put(movie ? "minimumAvailability" : "seriesType", kindIds[(int) kind.getTag()]);
                if (!movie) { b.put("seasonFolder", (int) folder.getTag() == 0); b.put("monitorNewItems", (int) newItems.getTag() == 0 ? "all" : "none"); }
                return hub.arr("PUT", base + (movie ? "movie/" : "series/") + id, b.toString(), Arr.JSON);
            }, "Saved", this::load);
            return true;
        });
    }

    /** The detail call carries extra fields (our _profile); PUT the object without them. */
    private static JSONObject strip(JSONObject o) throws Exception {
        JSONObject c = new JSONObject(o.toString());
        c.remove("_profile");
        return c;
    }

    private void tile(LinearLayout row, int icon, String label, Runnable r) {
        LinearLayout t = new LinearLayout(this);
        t.setOrientation(LinearLayout.VERTICAL);
        t.setGravity(Gravity.CENTER);
        t.setPadding(0, dp(12), 0, dp(10));
        t.setBackground(Ui.card(this, 1));
        ImageView ic = new ImageView(this);
        ic.setImageResource(icon);
        ic.setImageTintList(ColorStateList.valueOf(accent));
        t.addView(ic, new LinearLayout.LayoutParams(dp(22), dp(22)));
        TextView l = Ui.text(this, label, 12, Ui.TEXT, false);
        l.setGravity(Gravity.CENTER); l.setSingleLine(true);
        l.setPadding(0, dp(6), 0, 0);
        t.addView(l);
        t.setOnClickListener(v -> r.run());
        t.setContentDescription(label);
        Ui.press(t);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, -2, 1);
        if (row.getChildCount() > 0) lp.setMarginStart(dp(8));
        row.addView(t, lp);
    }

    private void stats(LinearLayout body) {
        LinearLayout row = new LinearLayout(this);
        row.setBaselineAligned(false);
        LinearLayout.LayoutParams rl = wrap(); rl.topMargin = dp(10);
        body.addView(row, rl);
        if (movie) {
            stat(row, item.optBoolean("hasFile") ? "On disk" : "Missing", item.optBoolean("hasFile") ? Ui.GREEN : Ui.AMBER, "Status");
            stat(row, Arr.size(item.optLong("sizeOnDisk")), Ui.TEXT, "Size");
        } else {
            JSONObject st = item.optJSONObject("statistics");
            int have = st == null ? 0 : st.optInt("episodeFileCount"), all = st == null ? 0 : st.optInt("episodeCount");
            stat(row, have + "/" + all, have >= all ? Ui.GREEN : Ui.TEXT, "Episodes");
            stat(row, Arr.size(st == null ? 0 : st.optLong("sizeOnDisk")), Ui.TEXT, "Size");
        }
        stat(row, item.optString("_profile"), Ui.TEXT, "Profile");
    }

    private void stat(LinearLayout row, String big, int color, String label) {
        LinearLayout t = new LinearLayout(this);
        t.setOrientation(LinearLayout.VERTICAL);
        t.setPadding(dp(12), dp(10), dp(12), dp(10));
        t.setBackground(Ui.card(this, 1));
        TextView v = Ui.num(this, big, 16, color);
        v.setSingleLine(true); v.setEllipsize(TextUtils.TruncateAt.END);
        t.addView(v);
        t.addView(Ui.caption(this, label, Ui.SUB));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, -2, 1);
        if (row.getChildCount() > 0) lp.setMarginStart(dp(8));
        row.addView(t, lp);
    }

    private TextView section(LinearLayout body, String name) {
        TextView t = Ui.title(this, name, Ui.TEXT);
        t.setPadding(dp(2), dp(22), 0, dp(8));
        body.addView(t);
        return t;
    }

    // ---------------------------------------------------------------- movie

    private void movieFile(LinearLayout body) {
        section(body, "File");
        JSONObject f = item.optJSONObject("movieFile");
        LinearLayout c = new LinearLayout(this);
        c.setOrientation(LinearLayout.VERTICAL);
        c.setPadding(dp(14), dp(12), dp(14), dp(12));
        c.setBackground(Ui.card(this, 1));
        body.addView(c, wrap());
        if (f == null) {
            c.addView(Ui.text(this, item.optBoolean("monitored") ? "Not downloaded yet. Search, or wait for Radarr to find it." : "Not downloaded and not monitored.", 13, Ui.SUB, false));
            List<String> rel = new ArrayList<>();
            for (String[] k : new String[][]{{"inCinemas", "In cinemas"}, {"digitalRelease", "Digital"}, {"physicalRelease", "Physical"}}) {
                Date d = Arr.date(item.optString(k[0]));
                if (d != null) rel.add(k[1] + " " + Arr.day(d));
            }
            if (!rel.isEmpty()) c.addView(Ui.text(this, TextUtils.join(" · ", rel), 13, Ui.TEXT, false));
            return;
        }
        JSONObject q = f.optJSONObject("quality"), mi = f.optJSONObject("mediaInfo");
        String qn = q == null || q.optJSONObject("quality") == null ? "" : q.optJSONObject("quality").optString("name");
        LinearLayout tags = new LinearLayout(this);
        addTag(tags, Arr.tag(this, qn, accent));
        addTag(tags, Arr.tag(this, Arr.size(f.optLong("size")), Ui.SUB));
        if (mi != null) {
            addTag(tags, Arr.tag(this, mi.optString("resolution"), Ui.SUB));
            addTag(tags, Arr.tag(this, mi.optString("videoCodec"), Ui.SUB));
            addTag(tags, Arr.tag(this, mi.optString("audioCodec") + (mi.optDouble("audioChannels") > 0 ? " " + mi.optDouble("audioChannels") : ""), Ui.SUB));
        }
        c.addView(tags);
        TextView p = Ui.num(this, f.optString("relativePath"), 11, Ui.SUB);
        p.setPadding(0, dp(8), 0, 0);
        c.addView(p);
        if (!f.optString("releaseGroup").isEmpty()) c.addView(Ui.caption(this, "Release group " + f.optString("releaseGroup"), Ui.SUB));
        TextView del = Ui.ghostButton(this, "Delete file", Ui.RED);
        del.setOnClickListener(v -> Sheets.confirm(this, "Delete this file from disk?", f.optString("relativePath"), "Delete", true,
                () -> act(() -> hub.arr("DELETE", base + "moviefile/" + f.optInt("id"), null, null), "File deleted", this::load)));
        c.addView(del);
    }

    // ---------------------------------------------------------------- series

    private void seasons(LinearLayout body) {
        section(body, "Seasons");
        JSONArray ss = item.optJSONArray("seasons");
        if (ss == null) return;
        List<JSONObject> l = new ArrayList<>();
        for (int i = 0; i < ss.length(); i++) l.add(ss.optJSONObject(i));
        l.sort((a, b) -> {
            int x = a.optInt("seasonNumber"), y = b.optInt("seasonNumber");
            if (x == 0) return 1;
            if (y == 0) return -1;
            return Integer.compare(y, x);
        });
        for (JSONObject s : l) seasonCard(body, s);
    }

    private void seasonCard(LinearLayout body, JSONObject s) {
        int n = s.optInt("seasonNumber");
        JSONObject st = s.optJSONObject("statistics");
        int have = st == null ? 0 : st.optInt("episodeFileCount"), all = st == null ? 0 : st.optInt("episodeCount"), total = st == null ? 0 : st.optInt("totalEpisodeCount");
        boolean mon = s.optBoolean("monitored");
        LinearLayout c = new LinearLayout(this);
        c.setOrientation(LinearLayout.VERTICAL);
        c.setBackground(Ui.card(this, 1));
        LinearLayout.LayoutParams cl = wrap(); cl.bottomMargin = dp(8);
        body.addView(c, cl);

        LinearLayout top = new LinearLayout(this);
        top.setGravity(Gravity.CENTER_VERTICAL);
        top.setPadding(dp(14), dp(12), dp(6), dp(12));
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.addView(Ui.text(this, n == 0 ? "Specials" : "Season " + n, 16, mon ? Ui.TEXT : Ui.SUB, true));
        col.addView(Ui.text(this, have + " of " + all + (total > all ? " (" + total + " total)" : "") + " · " + Arr.size(st == null ? 0 : st.optLong("sizeOnDisk")), 12, Ui.SUB, false));
        View bar = Arr.bar(this, all == 0 ? 0 : have / (float) all, have >= all && all > 0 ? Ui.GREEN : accent, 4);
        LinearLayout.LayoutParams bl = new LinearLayout.LayoutParams(-1, dp(4)); bl.topMargin = dp(8);
        col.addView(bar, bl);
        top.addView(col, new LinearLayout.LayoutParams(0, -2, 1));
        TextView monitor = Arr.tag(this, mon ? "Monitored" : "Off", mon ? Ui.GREEN : Ui.SUB);
        monitor.setOnClickListener(v -> act(() -> {
            JSONArray ss = item.getJSONArray("seasons");
            for (int i = 0; i < ss.length(); i++) if (ss.getJSONObject(i).optInt("seasonNumber") == n) ss.getJSONObject(i).put("monitored", !mon);
            return hub.arr("PUT", base + "series/" + id, strip(item).toString(), Arr.JSON);
        }, mon ? "Season unmonitored" : "Season monitored", this::load));
        LinearLayout.LayoutParams ml = new LinearLayout.LayoutParams(-2, -2); ml.setMarginStart(dp(10));
        top.addView(monitor, ml);
        ImageButton more = Ui.icon(this, R.drawable.ic_more, "Season actions");
        more.setOnClickListener(v -> Sheets.actions(this, item.optString("title") + " · " + (n == 0 ? "Specials" : "Season " + n), null,
                Sheets.Item.of(R.drawable.ic_search, accent, "Search season", "Grab the best release for every missing episode",
                        () -> act(() -> command(new JSONObject().put("name", "SeasonSearch").put("seriesId", id).put("seasonNumber", n)), "Search started", null)),
                Sheets.Item.of(R.drawable.ic_tools, accent, "Interactive search", "Season packs and single episodes",
                        () -> ArrAdd.releases(this, hub, svc, "seriesId=" + id + "&seasonNumber=" + n, item.optString("title") + " season " + n))));
        top.addView(more, new LinearLayout.LayoutParams(dp(44), dp(44)));
        c.addView(top, wrap());

        LinearLayout eps = new LinearLayout(this);
        eps.setOrientation(LinearLayout.VERTICAL);
        eps.setPadding(dp(6), 0, dp(6), dp(6));
        c.addView(eps, wrap());
        Runnable toggle = () -> {
            boolean show = !Boolean.TRUE.equals(open.get(n));
            open.put(n, show);
            eps.removeAllViews();
            if (show) for (JSONObject e : episodes.getOrDefault(n, new ArrayList<>())) episodeRow(eps, e);
        };
        top.setOnClickListener(v -> toggle.run());
        Ui.press(top);
        if (Boolean.TRUE.equals(open.get(n))) { open.put(n, false); toggle.run(); }
    }

    private void episodeRow(LinearLayout eps, JSONObject e) {
        boolean has = e.optBoolean("hasFile"), mon = e.optBoolean("monitored");
        Date air = Arr.date(e.optString("airDateUtc"));
        boolean aired = air != null && air.before(new Date());
        JSONObject f = files.get(e.optInt("episodeFileId"));
        String q = f != null && f.optJSONObject("quality") != null && f.optJSONObject("quality").optJSONObject("quality") != null
                ? f.optJSONObject("quality").optJSONObject("quality").optString("name") : "";
        LinearLayout r = new LinearLayout(this);
        r.setGravity(Gravity.CENTER_VERTICAL);
        r.setPadding(dp(10), dp(9), dp(10), dp(9));
        TextView num = Ui.num(this, String.valueOf(e.optInt("episodeNumber")), 14, mon ? accent : Ui.FAINT);
        num.setGravity(Gravity.CENTER);
        r.addView(num, new LinearLayout.LayoutParams(dp(30), -2));
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setPadding(dp(8), 0, dp(8), 0);
        TextView t = Ui.text(this, e.optString("title"), 14, mon ? Ui.TEXT : Ui.SUB, false);
        t.setSingleLine(true); t.setEllipsize(TextUtils.TruncateAt.END);
        col.addView(t);
        col.addView(Ui.caption(this, air == null ? "TBA" : Arr.day(air) + (aired ? "" : " · " + Arr.when(air)), Ui.SUB));
        r.addView(col, new LinearLayout.LayoutParams(0, -2, 1));
        r.addView(has ? Arr.tag(this, q.isEmpty() ? "✓" : "✓ " + q, Ui.GREEN) : !aired ? Arr.tag(this, "Unaired", Ui.SUB)
                : Arr.tag(this, mon ? "Missing" : "Skipped", mon ? Ui.AMBER : Ui.SUB));
        String label = item.optString("title") + " " + Arr.ep(e);
        r.setOnClickListener(v -> {
            List<Sheets.Item> items = new ArrayList<>();
            items.add(Sheets.Item.of(R.drawable.ic_search, accent, "Automatic search", "Grab the best release",
                    () -> act(() -> command(new JSONObject().put("name", "EpisodeSearch").put("episodeIds", new JSONArray().put(e.optInt("id")))), "Search started", null)));
            items.add(Sheets.Item.of(R.drawable.ic_tools, accent, "Interactive search", "Pick a release yourself",
                    () -> ArrAdd.releases(this, hub, svc, "episodeId=" + e.optInt("id"), label)));
            items.add(Sheets.Item.of(R.drawable.ic_pin, accent, mon ? "Unmonitor" : "Monitor", null, () -> act(() -> hub.arr("PUT", base + "episode/monitor",
                    new JSONObject().put("episodeIds", new JSONArray().put(e.optInt("id"))).put("monitored", !mon).toString(), Arr.JSON), mon ? "Unmonitored" : "Monitored", this::load)));
            if (f != null) items.add(Sheets.Item.danger(R.drawable.ic_delete, "Delete file", f.optString("relativePath"), () ->
                    Sheets.confirm(this, "Delete " + label + " from disk?", f.optString("relativePath"), "Delete", true,
                            () -> act(() -> hub.arr("DELETE", base + "episodefile/" + f.optInt("id"), null, null), "File deleted", this::load))));
            String sub = e.optString("title") + (f == null ? "" : " · " + q + " · " + Arr.size(f.optLong("size")));
            Sheets.actions(this, label, sub, items.toArray(new Sheets.Item[0]));
        });
        Ui.press(r);
        eps.addView(r, wrap());
        String ov = e.optString("overview");
        if (!ov.isEmpty()) r.setContentDescription(label + ", " + e.optString("title") + ". " + ov);
    }

    // ---------------------------------------------------------------- history / delete

    private void history(LinearLayout body) {
        TextView head = section(body, "History");
        LinearLayout h = new LinearLayout(this);
        h.setOrientation(LinearLayout.VERTICAL);
        body.addView(h, wrap());
        bg(() -> hub.arr(base + (movie ? "history/movie?movieId=" + id : "history/series?seriesId=" + id + "&includeEpisode=true")), r -> {
            JSONArray rec = r instanceof JSONArray ? (JSONArray) r : ((JSONObject) r).optJSONArray("records");
            if (rec == null || rec.length() == 0) { head.setVisibility(View.GONE); return; }
            for (int i = 0; i < Math.min(rec.length(), 12); i++) {
                JSONObject x = rec.optJSONObject(i), e = x.optJSONObject("episode");
                String ev = x.optString("eventType");
                int color = ev.equals("downloadFolderImported") ? Ui.GREEN : ev.equals("downloadFailed") ? Ui.RED : ev.equals("grabbed") ? accent : Ui.SUB;
                String label = ev.equals("downloadFolderImported") ? "Imported" : ev.equals("grabbed") ? "Grabbed" : ev.equals("downloadFailed") ? "Failed"
                        : ev.replaceAll("([A-Z])", " $1").toLowerCase(Locale.ROOT).trim();
                LinearLayout row = new LinearLayout(this);
                row.setGravity(Gravity.CENTER_VERTICAL);
                row.setPadding(dp(4), dp(7), dp(4), dp(7));
                row.addView(Arr.tag(this, label, color), new LinearLayout.LayoutParams(dp(84), -2));
                TextView t = Ui.text(this, (e == null ? "" : Arr.ep(e) + "  ") + x.optString("sourceTitle"), 12, Ui.TEXT, false);
                t.setSingleLine(true); t.setEllipsize(TextUtils.TruncateAt.MIDDLE);
                t.setPadding(dp(8), 0, dp(8), 0);
                row.addView(t, new LinearLayout.LayoutParams(0, -2, 1));
                row.addView(Ui.caption(this, Arr.ago(Arr.date(x.optString("date"))), Ui.SUB));
                h.addView(row, wrap());
            }
        }, m -> head.setVisibility(View.GONE));
    }

    private void delete(boolean deleteFiles) {
        Sheets.confirm(this, deleteFiles ? "Delete " + item.optString("title") + " and its files?" : "Remove " + item.optString("title") + "?",
                deleteFiles ? "Files on disk are deleted too." : "Files stay on disk.", "Remove", true,
                () -> act(() -> hub.arr("DELETE", base + (movie ? "movie/" : "series/") + id + "?deleteFiles=" + deleteFiles, null, null), "Removed", this::finish));
    }
}
