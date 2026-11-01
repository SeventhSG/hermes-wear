package cloud.stormlabs.hermescompanion;

import android.app.Activity;
import android.content.Intent;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.os.Bundle;
import android.text.InputType;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;

import static cloud.stormlabs.hermescompanion.Arr.PRO;
import static cloud.stormlabs.hermescompanion.Arr.QB;
import static cloud.stormlabs.hermescompanion.Arr.RAD;
import static cloud.stormlabs.hermescompanion.Arr.SON;

/**
 * nzb360-style media centre over the hub's /arr proxy: a Home dashboard (live speed, up next, recently downloaded,
 * new on Jellyfin, recommendations, service health), TV and Movies libraries with calendars, activity and wanted,
 * the qBittorrent queue, Prowlarr indexers and Jellyfin.
 */
public final class MediaActivity extends Activity {
    public static final String EXTRA_PAGE = "page";

    enum Page {
        HOME("Home", R.drawable.ic_home), TV("TV", R.drawable.ic_tv), MOVIES("Movies", R.drawable.ic_movie),
        DOWNLOADS("Downloads", R.drawable.ic_download), INDEXERS("Indexers", R.drawable.ic_server), SERVER("Library", R.drawable.ic_play),
        SEARCH("Search", R.drawable.ic_search);
        final String label; final int icon;
        Page(String label, int icon) { this.label = label; this.icon = icon; }
    }

    private interface Job<T> { T run() throws Exception; }

    private final ExecutorService net = Executors.newFixedThreadPool(6);
    private HubApi hub;
    private LinearLayout page, pills, dest;
    private ScrollView scroll;
    private Page current = Page.HOME;
    private int gen, tick;
    private final Map<Page, String> sub = new HashMap<>();
    private String tvSort = "A–Z", tvFilter = "All", movieSort = "A–Z", movieFilter = "All", dlFilter = "All", dlSort = "Added";
    private String query = "";
    private JSONArray seriesCache, moviesCache, torrents;
    /** Configured media modules (MediaModules); pages, sections and tiles exist only for these. */
    private List<MediaModules.Module> mods;
    /** Media server shown by Page.SERVER (one tab per configured server). */
    private String server = "";

    // live speed hero
    private TextView heroDown, heroInfo, altChip;
    private Sparkline graphDown, graphUp;
    private LinearLayout dlList;
    private boolean alt;

    private final Runnable poller = new Runnable() {
        @Override public void run() {
            if ((current == Page.HOME || current == Page.DOWNLOADS) && has("qbit")) poll();
            page.postDelayed(this, 2000);
        }
    };

    private int dp(int n) { return Ui.dp(this, n); }
    private LinearLayout.LayoutParams wrap() { return new LinearLayout.LayoutParams(-1, -2); }

    static int accent(Page p) {
        switch (p) {
            case TV: return Arr.SONARR;
            case MOVIES: return Arr.RADARR;
            case DOWNLOADS: return Arr.QBIT;
            case INDEXERS: return Arr.PROWLARR;
            default: return Ui.BLUE;
        }
    }

    private int accentOf(Page p) { return p == Page.SERVER ? MediaModules.color(server) : accent(p); }

    private boolean has(String id) { return MediaModules.has(mods, id); }

    /** The tab a module owns, or null for media servers (Page.SERVER + server id). */
    private static Page pageOf(String id) {
        switch (id) { case "sonarr": return Page.TV; case "radarr": return Page.MOVIES; case "qbit": return Page.DOWNLOADS; case "prowlarr": return Page.INDEXERS; default: return null; }
    }

    private void showServer(String id) { server = id; show(Page.SERVER); }

    @Override public void onCreate(Bundle b) {
        super.onCreate(b);
        Ui.legacyWindowInsets(this);
        SettingsStore store = new SettingsStore(this);
        hub = new HubApi(store.hubUrl(), store.hubKey());
        getWindow().setStatusBarColor(Ui.BG);
        getWindow().setNavigationBarColor(Ui.BG);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Ui.BG);

        LinearLayout bar = new LinearLayout(this);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setPadding(Ui.layout == Layout.TABS ? dp(16) : dp(2), dp(4), dp(8), 0);
        if (Ui.layout != Layout.TABS) {
            ImageButton back = Ui.icon(this, R.drawable.ic_back, "Back");
            back.setOnClickListener(v -> onBackPressed());
            bar.addView(back, new LinearLayout.LayoutParams(dp(48), dp(56)));
        }
        TextView title = Ui.text(this, "Media", 26, Ui.TEXT, true);
        title.setTypeface(Ui.WORD_HERO);
        bar.addView(title, new LinearLayout.LayoutParams(0, -2, 1));
        ImageButton search = Ui.icon(this, R.drawable.ic_search, "Search");
        search.setOnClickListener(v -> show(Page.SEARCH));
        bar.addView(search, new LinearLayout.LayoutParams(dp(48), dp(56)));
        ImageButton refresh = Ui.icon(this, R.drawable.ic_refresh, "Refresh");
        refresh.setOnClickListener(v -> { seriesCache = null; moviesCache = null; show(current); });
        bar.addView(refresh, new LinearLayout.LayoutParams(dp(48), dp(56)));
        root.addView(bar, wrap());

        HorizontalScrollView strip = new HorizontalScrollView(this);
        strip.setHorizontalScrollBarEnabled(false);
        pills = new LinearLayout(this);
        pills.setPadding(dp(12), dp(4), dp(12), dp(10));
        strip.addView(pills);
        root.addView(strip, wrap());

        scroll = new ScrollView(this);
        page = new LinearLayout(this);
        page.setOrientation(LinearLayout.VERTICAL);
        page.setPadding(dp(12), 0, dp(12), dp(28));
        scroll.addView(page);
        root.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));
        Nav.tabBar(this, root, Layout.Tab.MEDIA);
        setContentView(root);
        Ui.fitInsets(root, root);
        mods = MediaModules.cached(this);
        Page start = Page.HOME;
        try { String p = getIntent().getStringExtra(EXTRA_PAGE); if (p != null) start = Page.valueOf(p); } catch (Exception ignored) { }
        show(start);
        net.execute(() -> {  // the hub may have gained or lost services since last time: redraw only if the set changed
            try {
                List<MediaModules.Module> fresh = MediaModules.refresh(this, hub);
                if (!ids(fresh).equals(ids(mods))) runOnUiThread(() -> { if (!isDestroyed()) { mods = fresh; show(current); } });
            } catch (Exception ignored) {}
        });
    }

    private static String ids(List<MediaModules.Module> m) { StringBuilder b = new StringBuilder(); for (MediaModules.Module x : m) b.append(x.id).append(','); return b.toString(); }

    @Override protected void onResume() { super.onResume(); page.removeCallbacks(poller); page.post(poller); }
    @Override protected void onPause() { super.onPause(); page.removeCallbacks(poller); }
    @Override protected void onDestroy() { super.onDestroy(); net.shutdownNow(); }

    @Override public void onBackPressed() {
        if (current != Page.HOME) { show(Page.HOME); return; }
        if (Nav.leaveFromTab(this, Layout.Tab.MEDIA)) return;
        super.onBackPressed();
    }

    // ---------------------------------------------------------------- plumbing

    private void show(Page p) {
        current = p; gen++;
        heroDown = null; heroInfo = null; graphDown = null; graphUp = null; altChip = null; dlList = null;
        pills.removeAllViews();
        if ((p == Page.SERVER && MediaModules.find(mods, server) == null) || (p != Page.HOME && p != Page.SEARCH && p != Page.SERVER && !hasPage(p))) p = Page.HOME;
        current = p;
        for (Page x : Page.values()) {
            if (x == Page.SEARCH) continue;
            if (x == Page.SERVER) { for (MediaModules.Module m : MediaModules.servers(mods)) pill(p == Page.SERVER && m.id.equals(server), m.name, R.drawable.ic_play, MediaModules.color(m.id), () -> showServer(m.id)); continue; }
            if (x != Page.HOME && !hasPage(x)) continue;
            pill(x == p, x.label, x.icon, accent(x), () -> show(x));
        }
        page.removeAllViews();
        dest = page;
        scroll.scrollTo(0, 0);
        switch (p) {
            case TV: tv(); break;
            case MOVIES: movies(); break;
            case DOWNLOADS: downloads(); break;
            case INDEXERS: indexers(); break;
            case SERVER: server(); break;
            case SEARCH: search(); break;
            default: home();
        }
        Ui.stagger(page);
    }

    private boolean hasPage(Page p) { for (MediaModules.Module m : mods) if (pageOf(m.id) == p) return true; return false; }

    private void pill(boolean on, String label, int icon, int ac, Runnable tap) {
        LinearLayout pill = new LinearLayout(this);
        pill.setGravity(Gravity.CENTER_VERTICAL);
        pill.setPadding(dp(12), dp(8), dp(16), dp(8));
        pill.setBackground(on ? Ui.round(this, ac, 99) : Ui.round(this, Ui.SURFACE, 99));
        ImageView ic = new ImageView(this);
        ic.setImageResource(icon);
        ic.setImageTintList(ColorStateList.valueOf(on ? Arr.on(ac) : ac));
        pill.addView(ic, new LinearLayout.LayoutParams(dp(18), dp(18)));
        TextView t = Ui.text(this, label, 14, on ? Arr.on(ac) : Ui.TEXT, on);
        t.setPadding(dp(8), 0, 0, 0);
        pill.addView(t);
        pill.setOnClickListener(v -> tap.run());
        pill.setContentDescription(label + (on ? ", selected" : ""));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, -2); lp.setMarginEnd(dp(8));
        pills.addView(pill, lp);
    }

    /** Runs job off the UI thread; the result is dropped once the user has moved to another page. */
    private <T> void bg(Job<T> job, Consumer<T> ok, Consumer<String> fail) {
        int g = gen;
        net.execute(() -> {
            try {
                T r = job.run();
                runOnUiThread(() -> { if (g == gen && !isDestroyed()) ok.accept(r); });
            } catch (Exception e) {
                String m = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
                runOnUiThread(() -> { if (g == gen && !isDestroyed() && fail != null) fail.accept(m); });
            }
        });
    }

    /** A write: toast the outcome, then optionally reload. */
    private void act(Job<?> job, String done, Runnable then) {
        net.execute(() -> {
            String m;
            try { job.run(); m = done; } catch (Exception e) { m = "Failed: " + e.getMessage(); }
            String msg = m;
            runOnUiThread(() -> { if (isDestroyed()) return; Toast.makeText(this, msg, Toast.LENGTH_SHORT).show(); if (then != null) then.run(); });
        });
    }

    private JSONObject obj(String path) throws Exception { return (JSONObject) hub.arr(path); }
    private JSONArray arr(String path) throws Exception { return (JSONArray) hub.arr(path); }
    private Object post(String path, String body, String type) throws Exception { return hub.arr("POST", path, body, type); }
    private Object command(String base, JSONObject cmd) throws Exception { return post(base + "command", cmd.toString(), Arr.JSON); }

    private LinearLayout holder() {
        LinearLayout h = new LinearLayout(this);
        h.setOrientation(LinearLayout.VERTICAL);
        dest.addView(h, wrap());
        return h;
    }

    private void fill(LinearLayout h, Runnable body) {
        h.removeAllViews();
        LinearLayout old = dest; dest = h;
        try { body.run(); } finally { dest = old; }
    }

    private void loading(LinearLayout h, int rows) { fill(h, () -> dest.addView(Ui.skeletonRows(this, rows), wrap())); }
    private void fail(LinearLayout h, String m) { fill(h, () -> note(m, Ui.RED)); }

    private TextView note(String text, int color) {
        TextView t = Ui.text(this, text, 13, color, false);
        t.setPadding(dp(4), dp(4), dp(4), dp(8));
        dest.addView(t, wrap());
        return t;
    }

    private void header(String title, String action, int accent, View.OnClickListener tap) { dest.addView(Arr.header(this, title, action, accent, tap), wrap()); }

    private LinearLayout card() {
        LinearLayout c = new LinearLayout(this);
        c.setOrientation(LinearLayout.VERTICAL);
        c.setPadding(dp(16), dp(14), dp(16), dp(14));
        c.setBackground(Ui.card(this, 1));
        LinearLayout.LayoutParams lp = wrap(); lp.bottomMargin = dp(10);
        dest.addView(c, lp);
        return c;
    }

    /** Sub-navigation (Library / Upcoming / …) for one page, remembered while the screen lives. */
    private String subTabs(Page p, String[] names) {
        String on = sub.getOrDefault(p, names[0]);
        LinearLayout row = Arr.chipRow(this, dest);
        for (String n : names) Arr.addChip(this, row, Arr.chip(this, n, n.equals(on), accentOf(p), v -> { sub.put(p, n); show(p); }));
        return on;
    }

    /** A list row: small poster, title, subtitle lines, optional trailing tag, optional progress bar. */
    private LinearLayout row(String img, String title, String sub, String tag, int tagColor, float progress, int accent, View.OnClickListener tap) {
        LinearLayout r = new LinearLayout(this);
        r.setGravity(Gravity.CENTER_VERTICAL);
        r.setPadding(dp(10), dp(10), dp(12), dp(10));
        r.setBackground(Ui.card(this, 1));
        if (img != null) {
            ImageView p = new ImageView(this);
            p.setScaleType(ImageView.ScaleType.CENTER_CROP);
            p.setBackground(Ui.round(this, Ui.RAISED, 8));
            p.setClipToOutline(true);
            LinearLayout.LayoutParams pl = new LinearLayout.LayoutParams(dp(46), dp(69)); pl.setMarginEnd(dp(12));
            r.addView(p, pl);
            Arr.image(this, hub, p, img, dp(92));
        }
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        LinearLayout top = new LinearLayout(this);
        top.setGravity(Gravity.CENTER_VERTICAL);
        TextView t = Ui.text(this, title, 15, Ui.TEXT, true);
        t.setMaxLines(2); t.setEllipsize(TextUtils.TruncateAt.END);
        top.addView(t, new LinearLayout.LayoutParams(0, -2, 1));
        if (tag != null && !tag.isEmpty()) {
            TextView tg = Arr.tag(this, tag, tagColor);
            LinearLayout.LayoutParams tl = new LinearLayout.LayoutParams(-2, -2); tl.setMarginStart(dp(8));
            top.addView(tg, tl);
        }
        col.addView(top, wrap());
        if (sub != null && !sub.isEmpty()) {
            TextView s = Ui.text(this, sub, 12, Ui.SUB, false);
            s.setMaxLines(3); s.setEllipsize(TextUtils.TruncateAt.END);
            s.setPadding(0, dp(2), 0, 0);
            col.addView(s, wrap());
        }
        if (progress >= 0) {
            View b = Arr.bar(this, progress, accent, 5);
            LinearLayout.LayoutParams bl = new LinearLayout.LayoutParams(-1, dp(5)); bl.topMargin = dp(8);
            col.addView(b, bl);
        }
        r.addView(col, new LinearLayout.LayoutParams(0, -2, 1));
        if (tap != null) { r.setOnClickListener(tap); Ui.press(r); }
        LinearLayout.LayoutParams lp = wrap(); lp.bottomMargin = dp(8);
        dest.addView(r, lp);
        return r;
    }

    private void dayHead(String d, int accent) {
        TextView h = Ui.label(this, d, accent);
        h.setPadding(dp(4), dp(12), 0, dp(6));
        dest.addView(h, wrap());
    }

    private void openDetail(String svc, int id) {
        startActivity(new Intent(this, MediaDetailActivity.class).putExtra(MediaDetailActivity.EXTRA_SVC, svc).putExtra(MediaDetailActivity.EXTRA_ID, id));
    }

    // ---------------------------------------------------------------- speed hero (Home + Downloads)

    private void hero() {
        LinearLayout c = new LinearLayout(this);
        c.setOrientation(LinearLayout.VERTICAL);
        c.setPadding(dp(18), dp(16), dp(18), dp(14));
        android.graphics.drawable.GradientDrawable g = Arr.glow(Arr.QBIT);
        g.setCornerRadius(dp(18));
        c.setBackground(g);
        LinearLayout.LayoutParams lp = wrap(); lp.bottomMargin = dp(6);
        dest.addView(c, lp);

        LinearLayout top = new LinearLayout(this);
        top.setGravity(Gravity.CENTER_VERTICAL);
        top.addView(Ui.label(this, "qBittorrent", Arr.QBIT), new LinearLayout.LayoutParams(0, -2, 1));
        altChip = Arr.tag(this, "Alt speed off", Ui.SUB);
        altChip.setOnClickListener(v -> act(() -> post(QB + "transfer/toggleSpeedLimitsMode", "", Arr.FORM), alt ? "Full speed" : "Alternative speed limits on", this::poll));
        top.addView(altChip);
        c.addView(top, wrap());

        heroDown = Ui.metric(this, "↓ —", Ui.TEXT);
        heroDown.setPadding(0, dp(6), 0, 0);
        c.addView(heroDown, wrap());
        heroInfo = Ui.text(this, "Connecting…", 13, Ui.SUB, false);
        c.addView(heroInfo, wrap());

        FrameLayout graphs = new FrameLayout(this);
        graphUp = new Sparkline(this, Ui.GREEN, Double.NaN, false);
        graphDown = new Sparkline(this, Arr.QBIT, Double.NaN, false);
        graphs.addView(graphUp, new FrameLayout.LayoutParams(-1, -1));
        graphs.addView(graphDown, new FrameLayout.LayoutParams(-1, -1));
        LinearLayout.LayoutParams gl = new LinearLayout.LayoutParams(-1, dp(64)); gl.topMargin = dp(10);
        c.addView(graphs, gl);

        LinearLayout btns = new LinearLayout(this);
        btns.setPadding(0, dp(10), 0, 0);
        TextView pause = Ui.ghostButton(this, "Pause all", Ui.AMBER), resume = Ui.ghostButton(this, "Resume all", Ui.GREEN);
        pause.setOnClickListener(v -> act(() -> post(QB + "torrents/stop", "hashes=all", Arr.FORM), "Paused everything", this::poll));
        resume.setOnClickListener(v -> act(() -> post(QB + "torrents/start", "hashes=all", Arr.FORM), "Resumed everything", this::poll));
        btns.addView(pause); btns.addView(resume);
        if (current == Page.HOME) {
            TextView open = Ui.ghostButton(this, "Queue  ›", Arr.QBIT);
            open.setOnClickListener(v -> show(Page.DOWNLOADS));
            btns.addView(open);
        }
        c.addView(btns, wrap());
        renderHero(null);
    }

    private void poll() {
        tick++;
        boolean list = current == Page.DOWNLOADS && tick % 2 == 0;
        bg(() -> new Object[]{obj(QB + "transfer/info"), hub.arr(QB + "transfer/speedLimitsMode"), list || torrents == null ? arr(QB + "torrents/info?sort=added_on&reverse=true") : null}, r -> {
            JSONObject t = (JSONObject) r[0];
            Arr.sample(t.optLong("dl_info_speed"), t.optLong("up_info_speed"));
            alt = "1".equals(String.valueOf(r[1]).trim());
            if (r[2] != null) { torrents = (JSONArray) r[2]; if (dlList != null) renderTorrents(); }
            renderHero(t);
        }, m -> { if (heroInfo != null) { heroInfo.setText("qBittorrent unreachable: " + m); heroInfo.setTextColor(Ui.RED); } });
    }

    private void renderHero(JSONObject t) {
        if (heroDown == null) return;
        double[][] d = Arr.series(1), u = Arr.series(2);
        int vis = d.length >= 4 ? View.VISIBLE : View.INVISIBLE;
        graphDown.setVisibility(vis); graphUp.setVisibility(vis);
        if (d.length > 0) {
            double[] last = d[d.length - 1];
            heroDown.setText("↓ " + Arr.speed((long) last[1]));
            double top = 1;
            for (double[] x : d) top = Math.max(top, x[1]);
            for (double[] x : u) top = Math.max(top, x[1]);
            long span = Math.max(20, (long) (System.currentTimeMillis() / 1000.0 - d[0][0]));
            graphDown.setData(d, span, "Download speed, last 3 minutes");
            graphUp.setData(u, span, "Upload speed, last 3 minutes");
        }
        if (t != null || torrents != null) {
            int[] c = counts();
            String up = u.length > 0 ? "↑ " + Arr.speed((long) u[u.length - 1][1]) : "";
            heroInfo.setText(up + "  ·  " + c[1] + " downloading · " + c[5] + " stalled · " + c[2] + " seeding");
            heroInfo.setTextColor(Ui.SUB);
        }
        if (altChip != null) {
            altChip.setText(alt ? "🐢 Alt speed on" : "Alt speed off");
            altChip.setTextColor(alt ? Ui.AMBER : Ui.SUB);
            altChip.setBackground(Ui.round(this, Arr.alpha(alt ? Ui.AMBER : Ui.SUB, 0x2a), 99));
        }
    }

    // ---------------------------------------------------------------- HOME

    private void home() {
        boolean son = has("sonarr"), rad = has("radarr");
        if (mods.isEmpty()) {
            note("No media services are set up on your hub yet. Add Sonarr, Radarr, qBittorrent, Prowlarr or a media server "
                    + "(Jellyfin, Emby, Plex, Ampache) to the hub's arr.env and they appear here.", Ui.SUB);
            phoneApps();
            return;
        }
        if (son || rad || has("prowlarr")) { LinearLayout health = holder(); bg(this::issues, l -> fill(health, () -> healthCard(l)), null); }
        if (has("qbit")) hero();

        for (MediaModules.Module m : MediaModules.servers(mods)) {
            if (!m.can("sessions")) continue;
            LinearLayout playing = holder();
            bg(() -> hub.media("GET", m.id + "/sessions", null), r -> fill(playing, () -> nowPlaying(m, r.optJSONArray("sessions"), true)), null);
        }

        if (son || rad) homeArr(son, rad);

        for (MediaModules.Module m : MediaModules.servers(mods)) {
            if (!m.can("latest")) continue;
            header("New on " + m.name, m.name, MediaModules.color(m.id), v -> showServer(m.id));
            LinearLayout box = holder();
            loadingPosters(box);
            bg(() -> hub.media("GET", m.id + "/latest?limit=16", null), r -> fill(box, () -> {
                JSONArray items = r.optJSONArray("items");
                if (items == null || items.length() == 0) { note("Nothing new.", Ui.SUB); return; }
                LinearLayout row = Arr.carousel(this, dest);
                for (int i = 0; i < items.length(); i++) Arr.addToCarousel(this, row, mediaPoster(m, items.optJSONObject(i), 112));
            }), e -> fail(box, m.name + ": " + e));
        }

        if (rad) recommended();

        header("Services", null, 0, null);
        services();
        phoneApps();
    }

    /** Sonarr/Radarr sections of the home page: the week ahead and what was imported lately. */
    private void homeArr(boolean son, boolean rad) {
        Page arrPage = son ? Page.TV : Page.MOVIES;
        header("Up next", "Calendar", accent(arrPage), v -> { sub.put(arrPage, "Upcoming"); show(arrPage); });
        LinearLayout upHolder = holder();
        loadingPosters(upHolder);
        List<Object[]> up = new ArrayList<>();
        long now = System.currentTimeMillis();
        String range = "start=" + Arr.ymd(now) + "&end=" + Arr.ymd(now + 8 * 86400000L);
        if (son) bg(() -> arr(SON + "calendar?includeSeries=true&" + range), r -> {
            for (int i = 0; i < r.length(); i++) {
                JSONObject e = r.optJSONObject(i); JSONObject s = e.optJSONObject("series");
                if (s == null) continue;
                Date d = Arr.date(e.optString("airDateUtc"));
                up.add(new Object[]{d, Arr.poster(this, hub, 112, Arr.art("sonarr", s, "poster"), s.optString("title"),
                        Arr.ep(e) + (d == null ? "" : " · " + Arr.clock(d)), Arr.when(d), e.optBoolean("hasFile") ? Ui.GREEN : Arr.SONARR, -1, Arr.SONARR,
                        v -> openDetail("sonarr", s.optInt("id")))});
            }
            renderCarousel(upHolder, up, "Nothing airing this week.");
        }, m -> fail(upHolder, "Sonarr: " + m));
        if (rad) bg(() -> arr(RAD + "calendar?" + range), r -> {
            for (int i = 0; i < r.length(); i++) {
                JSONObject m = r.optJSONObject(i);
                Date d = upcomingDate(m);
                up.add(new Object[]{d, Arr.poster(this, hub, 112, Arr.art("radarr", m, "poster"), m.optString("title"), "Movie · " + m.optInt("year"),
                        Arr.when(d), m.optBoolean("hasFile") ? Ui.GREEN : Arr.RADARR, -1, Arr.RADARR, v -> openDetail("radarr", m.optInt("id")))});
            }
            renderCarousel(upHolder, up, "Nothing airing this week.");
        }, son ? null : m -> fail(upHolder, "Radarr: " + m));

        header("Recently downloaded", "Activity", accent(arrPage), v -> { sub.put(arrPage, "Activity"); show(arrPage); });
        LinearLayout recent = holder();
        loadingPosters(recent);
        List<Object[]> got = new ArrayList<>();
        if (son) bg(() -> obj(SON + "history?pageSize=40&sortKey=date&sortDirection=descending&eventType=3&includeSeries=true&includeEpisode=true"), r -> {
            Map<Integer, List<JSONObject>> bySeries = new LinkedHashMap<>();
            JSONArray rec = r.optJSONArray("records");
            for (int i = 0; rec != null && i < rec.length(); i++) {
                JSONObject h = rec.optJSONObject(i);
                bySeries.computeIfAbsent(h.optInt("seriesId"), k -> new ArrayList<>()).add(h);
            }
            for (List<JSONObject> hs : bySeries.values()) {
                JSONObject h = hs.get(0), s = h.optJSONObject("series"), e = h.optJSONObject("episode");
                if (s == null) continue;
                Date d = Arr.date(h.optString("date"));
                String what = (e == null ? "" : Arr.ep(e)) + (hs.size() > 1 ? " +" + (hs.size() - 1) : "");
                got.add(new Object[]{d, Arr.poster(this, hub, 112, Arr.art("sonarr", s, "poster"), s.optString("title"), what + " · " + Arr.ago(d),
                        "NEW", Ui.GREEN, -1, Arr.SONARR, v -> openDetail("sonarr", s.optInt("id")))});
            }
            renderCarousel(recent, got, "Nothing imported lately.", true);
        }, m -> fail(recent, "Sonarr: " + m));
        if (rad) bg(() -> obj(RAD + "history?pageSize=10&sortKey=date&sortDirection=descending&eventType=3&includeMovie=true"), r -> {
            JSONArray rec = r.optJSONArray("records");
            for (int i = 0; rec != null && i < rec.length(); i++) {
                JSONObject h = rec.optJSONObject(i), m = h.optJSONObject("movie");
                if (m == null) continue;
                Date d = Arr.date(h.optString("date"));
                got.add(new Object[]{d, Arr.poster(this, hub, 112, Arr.art("radarr", m, "poster"), m.optString("title"), "Movie · " + Arr.ago(d),
                        "NEW", Ui.GREEN, -1, Arr.RADARR, v -> openDetail("radarr", m.optInt("id")))});
            }
            renderCarousel(recent, got, "Nothing imported lately.", true);
        }, son ? null : m -> fail(recent, "Radarr: " + m));
    }

    private void recommended() {
        header("Recommended movies", "Discover", Arr.RADARR, v -> { sub.put(Page.MOVIES, "Discover"); show(Page.MOVIES); });
        LinearLayout recs = holder();
        loadingPosters(recs);
        bg(() -> arr(RAD + "importlist/movie?includeRecommendations=true"), r -> fill(recs, () -> {
            LinearLayout row = Arr.carousel(this, dest);
            int n = 0;
            for (int i = 0; i < r.length() && n < 20; i++) {
                JSONObject m = r.optJSONObject(i);
                if (m.optBoolean("isExisting") || m.optBoolean("isExcluded")) continue;
                n++;
                Arr.addToCarousel(this, row, Arr.poster(this, hub, 112, Arr.art("radarr", m, "poster"), m.optString("title"), String.valueOf(m.optInt("year")),
                        rating(m), Arr.RADARR, -1, Arr.RADARR, v -> ArrAdd.add(this, hub, m, true, null)));
            }
            if (n == 0) note("No recommendations yet.", Ui.SUB);
        }), m -> fail(recs, "Radarr: " + m));
    }

    /** {source, message, isError} for every health warning plus a nearly full media disk. */
    private List<String[]> issues() throws Exception {
        List<String[]> out = new ArrayList<>();
        String[][] src = {{"Sonarr", SON + "health", "sonarr"}, {"Radarr", RAD + "health", "radarr"}, {"Prowlarr", PRO + "health", "prowlarr"}};
        for (String[] s : src) {
            if (!has(s[2])) continue;
            try {
                JSONArray h = arr(s[1]);
                for (int i = 0; i < h.length(); i++) {
                    JSONObject x = h.getJSONObject(i);
                    out.add(new String[]{s[0], x.optString("message"), String.valueOf("error".equals(x.optString("type")))});
                }
            } catch (Exception e) { out.add(new String[]{s[0], "Not reachable: " + e.getMessage(), "true"}); }
        }
        if (has("sonarr") || has("radarr")) try {
            JSONArray d = arr((has("sonarr") ? SON : RAD) + "diskspace");
            for (int i = 0; i < d.length(); i++) {
                JSONObject x = d.getJSONObject(i);
                long free = x.optLong("freeSpace"), total = x.optLong("totalSpace");
                if (total > 100L << 30 && free < 100L << 30)
                    out.add(0, new String[]{"Storage", x.optString("path") + " is almost full: " + Arr.size(free) + " free of " + Arr.size(total)
                            + (free < 10L << 30 ? ". Downloads stall and Sonarr/Radarr can't write their databases." : ". Below 100 GB the vault pool can fill up and lock the disk."),
                            String.valueOf(free < 10L << 30)});
            }
        } catch (Exception ignored) { }
        return out;
    }

    private void healthCard(List<String[]> issues) {
        if (issues.isEmpty()) return;
        boolean err = false;
        for (String[] i : issues) err |= Boolean.parseBoolean(i[2]);
        int color = err ? Ui.RED : Ui.AMBER;
        LinearLayout c = new LinearLayout(this);
        c.setOrientation(LinearLayout.VERTICAL);
        c.setPadding(dp(16), dp(12), dp(16), dp(12));
        c.setBackground(Ui.stroke(this, Arr.alpha(color, 0x1f), Arr.alpha(color, 0x66), 16));
        TextView t = Ui.text(this, (issues.size() == 1 ? "1 issue needs" : issues.size() + " issues need") + " attention", 15, color, true);
        c.addView(t, wrap());
        for (int i = 0; i < Math.min(2, issues.size()); i++) {
            TextView m = Ui.text(this, issues.get(i)[0] + ": " + issues.get(i)[1], 13, Ui.TEXT, false);
            m.setMaxLines(3); m.setEllipsize(TextUtils.TruncateAt.END);
            m.setPadding(0, dp(6), 0, 0);
            c.addView(m, wrap());
        }
        if (issues.size() > 2) c.addView(Ui.text(this, "Show all " + issues.size() + "  ›", 13, color, true), wrap());
        c.setOnClickListener(v -> {
            String[][] rows = new String[issues.size()][];
            for (int i = 0; i < rows.length; i++) rows[i] = new String[]{issues.get(i)[0], issues.get(i)[1]};
            Sheets.info(this, "Server health", "From Sonarr, Radarr, Prowlarr and disk space", rows);
        });
        Ui.press(c);
        LinearLayout.LayoutParams lp = wrap(); lp.bottomMargin = dp(10);
        dest.addView(c, lp);
    }

    private static String rating(JSONObject m) {
        JSONObject r = m.optJSONObject("ratings");
        if (r == null) return "";
        JSONObject t = r.optJSONObject("tmdb"), i = r.optJSONObject("imdb");
        double v = i != null && i.optDouble("value", 0) > 0 ? i.optDouble("value") : t != null ? t.optDouble("value", 0) : 0;
        return v > 0 ? String.format(Locale.US, "★ %.1f", v) : "";
    }

    private void loadingPosters(LinearLayout h) {
        fill(h, () -> {
            LinearLayout row = Arr.carousel(this, dest);
            for (int i = 0; i < 4; i++) {
                View sk = new View(this);
                sk.setBackground(Ui.round(this, Ui.RAISED, 10));
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(112), dp(168)); lp.setMarginEnd(dp(12));
                row.addView(sk, lp);
                Ui.shimmer(sk);
            }
        });
    }

    private void renderCarousel(LinearLayout h, List<Object[]> items, String empty) { renderCarousel(h, items, empty, false); }

    /** Sorted by date (newest first when {@code desc}); each item is {Date, View}. Re-run as each service answers. */
    private void renderCarousel(LinearLayout h, List<Object[]> items, String empty, boolean desc) {
        items.sort((x, y) -> {
            long a = x[0] == null ? 0 : ((Date) x[0]).getTime(), b = y[0] == null ? 0 : ((Date) y[0]).getTime();
            return desc ? Long.compare(b, a) : Long.compare(a, b);
        });
        fill(h, () -> {
            if (items.isEmpty()) { note(empty, Ui.SUB); return; }
            LinearLayout row = Arr.carousel(this, dest);
            for (Object[] it : items) {
                View v = (View) it[1];
                if (v.getParent() != null) ((android.view.ViewGroup) v.getParent()).removeView(v);
                Arr.addToCarousel(this, row, v);
            }
        });
    }

    /** One tile per configured module (two per row), plus storage when Sonarr or Radarr can report it. */
    private void services() {
        LinearLayout grid = new LinearLayout(this);
        grid.setOrientation(LinearLayout.VERTICAL);
        dest.addView(grid, wrap());
        LinearLayout[] row = {null};
        java.util.function.BiFunction<String, Integer, LinearLayout> slot = (name, color) -> {
            if (row[0] == null || row[0].getChildCount() == 2) {
                row[0] = new LinearLayout(this); row[0].setBaselineAligned(false);
                LinearLayout.LayoutParams lp = wrap(); lp.bottomMargin = dp(8); grid.addView(row[0], lp);
            }
            return row[0];
        };
        for (MediaModules.Module m : mods) {
            int ac = MediaModules.color(m.id);
            Page target = pageOf(m.id);
            TextView body = svcTile(slot.apply(m.name, ac), m.name, ac, target, m.server() ? () -> showServer(m.id) : null);
            switch (m.id) {
                case "sonarr": bg(() -> new Object[]{arr(SON + "series").length(), obj(SON + "queue/status"), arr(SON + "health")}, r ->
                        tileText(body, r[0] + " series", ((JSONObject) r[1]).optInt("totalCount") + " queued", ((JSONArray) r[2]).length()), e -> tileErr(body, e)); break;
                case "radarr": bg(() -> new Object[]{arr(RAD + "movie").length(), obj(RAD + "queue/status"), arr(RAD + "health")}, r ->
                        tileText(body, r[0] + " movies", ((JSONObject) r[1]).optInt("totalCount") + " queued", ((JSONArray) r[2]).length()), e -> tileErr(body, e)); break;
                case "qbit": bg(() -> new Object[]{hub.arr(QB + "app/version"), arr(QB + "torrents/info")}, r ->
                        tileText(body, ((JSONArray) r[1]).length() + " torrents", String.valueOf(r[0]).trim(), 0), e -> tileErr(body, e)); break;
                case "prowlarr": bg(() -> new Object[]{arr(PRO + "indexer"), arr(PRO + "health")}, r ->
                        tileText(body, ((JSONArray) r[0]).length() + " indexers", "", ((JSONArray) r[1]).length()), e -> tileErr(body, e)); break;
                default:
                    if (!m.server()) { tileText(body, "Set up", "", 0); break; }
                    bg(() -> new Object[]{hub.media("GET", m.id + "/info", null), m.can("sessions") ? hub.media("GET", m.id + "/sessions", null) : new JSONObject()}, r -> {
                        JSONArray ss = ((JSONObject) r[1]).optJSONArray("sessions");
                        String v = ((JSONObject) r[0]).optString("version");
                        tileText(body, m.can("sessions") ? (ss == null ? 0 : ss.length()) + " playing" : "Online", v.isEmpty() ? "" : "v" + v, 0);
                    }, e -> tileErr(body, e));
            }
        }
        if (!has("sonarr") && !has("radarr")) return;
        TextView disk = svcTile(slot.apply("Storage", Ui.GREEN), "Storage", Ui.GREEN, null, null);
        bg(() -> arr((has("sonarr") ? SON : RAD) + "diskspace"), r -> {
            long free = 0, total = 0; String label = "";
            for (int i = 0; i < r.length(); i++) {
                JSONObject d = r.optJSONObject(i);
                if (d.optLong("totalSpace") > total) { total = d.optLong("totalSpace"); free = d.optLong("freeSpace"); label = d.optString("path"); }
            }
            float used = total == 0 ? 0 : 1 - free / (float) total;
            tileText(disk, Arr.size(free) + " free", label + " · " + Math.round(used * 100) + "% used", 0);
            LinearLayout t = (LinearLayout) disk.getParent();
            View b = Arr.bar(this, used, used > 0.9f ? Ui.RED : used > 0.8f ? Ui.AMBER : Ui.GREEN, 5);
            LinearLayout.LayoutParams bl = new LinearLayout.LayoutParams(-1, dp(5)); bl.topMargin = dp(8);
            t.addView(b, bl);
        }, m -> tileErr(disk, m));
    }

    private TextView svcTile(LinearLayout row, String name, int color, Page target, Runnable open) {
        LinearLayout t = new LinearLayout(this);
        t.setOrientation(LinearLayout.VERTICAL);
        t.setPadding(dp(14), dp(12), dp(14), dp(12));
        t.setBackground(Ui.card(this, 1));
        LinearLayout top = new LinearLayout(this);
        top.setGravity(Gravity.CENTER_VERTICAL);
        View dot = new View(this);
        dot.setBackground(Ui.round(this, color, 99));
        top.addView(dot, new LinearLayout.LayoutParams(dp(8), dp(8)));
        TextView n = Ui.label(this, name, Ui.SUB);
        n.setPadding(dp(8), 0, 0, 0);
        top.addView(n);
        t.addView(top, wrap());
        TextView body = Ui.text(this, "…", 16, Ui.TEXT, true);
        body.setPadding(0, dp(6), 0, 0);
        t.addView(body, wrap());
        if (target != null || open != null) { t.setOnClickListener(v -> { if (open != null) open.run(); else show(target); }); Ui.press(t); }
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, -1, 1);
        lp.setMarginEnd(row.getChildCount() == 0 ? dp(8) : 0);
        row.addView(t, lp);
        return body;
    }

    private void tileText(TextView body, String big, String small, int warnings) {
        body.setText(big);
        LinearLayout t = (LinearLayout) body.getParent();
        String s = small + (warnings > 0 ? (small.isEmpty() ? "" : " · ") + warnings + (warnings == 1 ? " warning" : " warnings") : "");
        if (!s.isEmpty()) {
            TextView sv = Ui.text(this, s, 12, warnings > 0 ? Ui.AMBER : Ui.SUB, false);
            sv.setSingleLine(true); sv.setEllipsize(TextUtils.TruncateAt.END);
            t.addView(sv, wrap());
        }
    }

    private void tileErr(TextView body, String m) { body.setText("Offline"); body.setTextColor(Ui.RED); }

    // ---------------------------------------------------------------- TV

    private void tv() {
        String s = subTabs(Page.TV, new String[]{"Library", "Upcoming", "Activity", "Wanted", "System"});
        switch (s) {
            case "System": system(SON, Arr.SONARR, true); break;
            case "Upcoming": upcoming(true); break;
            case "Activity": activity(true); break;
            case "Wanted": wanted(true); break;
            default: tvLibrary();
        }
    }

    private void tvLibrary() {
        LinearLayout sorts = Arr.chipRow(this, dest), filters = Arr.chipRow(this, dest);
        for (String n : new String[]{"A–Z", "Added", "Next airing", "Size"}) Arr.addChip(this, sorts, Arr.chip(this, n, n.equals(tvSort), Arr.SONARR, v -> { tvSort = n; show(Page.TV); }));
        for (String n : new String[]{"All", "Continuing", "Ended", "Missing", "Unmonitored"}) Arr.addChip(this, filters, Arr.chip(this, n, n.equals(tvFilter), Arr.SONARR, v -> { tvFilter = n; show(Page.TV); }));
        LinearLayout h = holder();
        loading(h, 4);
        bg(() -> seriesCache != null ? seriesCache : arr(SON + "series"), r -> { seriesCache = r; fill(h, () -> {
            List<JSONObject> l = new ArrayList<>();
            long eps = 0, files = 0, bytes = 0;
            for (int i = 0; i < r.length(); i++) {
                JSONObject x = r.optJSONObject(i), st = x.optJSONObject("statistics");
                if (st != null) { eps += st.optInt("episodeCount"); files += st.optInt("episodeFileCount"); bytes += st.optLong("sizeOnDisk"); }
                boolean missing = st != null && st.optInt("episodeFileCount") < st.optInt("episodeCount");
                if (tvFilter.equals("Continuing") && !"continuing".equals(x.optString("status"))) continue;
                if (tvFilter.equals("Ended") && !"ended".equals(x.optString("status"))) continue;
                if (tvFilter.equals("Missing") && !missing) continue;
                if (tvFilter.equals("Unmonitored") && x.optBoolean("monitored")) continue;
                l.add(x);
            }
            l.sort((a, b) -> {
                switch (tvSort) {
                    case "Added": return b.optString("added").compareTo(a.optString("added"));
                    case "Next airing": return next(a).compareTo(next(b));
                    case "Size": return Long.compare(stat(b, "sizeOnDisk"), stat(a, "sizeOnDisk"));
                    default: return a.optString("sortTitle").compareTo(b.optString("sortTitle"));
                }
            });
            note(r.length() + " series · " + files + "/" + eps + " episodes · " + Arr.size(bytes), Ui.SUB);
            int w = Arr.gridWidthDp(this, 3);
            List<View> cards = new ArrayList<>();
            for (JSONObject x : l) {
                JSONObject st = x.optJSONObject("statistics");
                int have = st == null ? 0 : st.optInt("episodeFileCount"), all = st == null ? 0 : st.optInt("episodeCount");
                Date nx = Arr.date(x.optString("nextAiring"));
                String badge = nx != null && nx.getTime() - System.currentTimeMillis() < 7 * 86400000L ? Arr.when(nx) : "";
                cards.add(Arr.poster(this, hub, w, Arr.art("sonarr", x, "poster"), x.optString("title"),
                        have + "/" + all + " · " + (x.optString("network").isEmpty() ? x.optString("status") : x.optString("network")),
                        badge, Arr.SONARR, all == 0 ? -1 : have / (float) all, have >= all ? Ui.GREEN : Arr.SONARR, v -> openDetail("sonarr", x.optInt("id"))));
            }
            if (cards.isEmpty()) note("No series match.", Ui.SUB);
            Arr.grid(this, dest, cards, 3);
        }); }, m -> fail(h, "Sonarr: " + m));
    }

    private static String next(JSONObject s) { String n = s.optString("nextAiring"); return n.isEmpty() ? "9999" : n; }
    private static long stat(JSONObject s, String k) { JSONObject st = s.optJSONObject("statistics"); return st == null ? 0 : st.optLong(k); }

    private void upcoming(boolean tv) {
        long now = System.currentTimeMillis();
        String range = "start=" + Arr.ymd(now - 86400000L) + "&end=" + Arr.ymd(now + 21 * 86400000L);
        int ac = tv ? Arr.SONARR : Arr.RADARR;
        LinearLayout h = holder();
        loading(h, 4);
        bg(() -> arr((tv ? SON + "calendar?includeSeries=true&" : RAD + "calendar?") + range), r -> fill(h, () -> {
            List<JSONObject> l = new ArrayList<>();
            for (int i = 0; i < r.length(); i++) l.add(r.optJSONObject(i));
            l.sort((a, b) -> releaseDate(a, tv).compareTo(releaseDate(b, tv)));
            if (l.isEmpty()) note("Nothing in the next three weeks.", Ui.SUB);
            String last = "";
            for (JSONObject e : l) {
                Date d = Arr.date(releaseDate(e, tv));
                String day = d == null ? "" : Arr.when(d) + (Arr.when(d).contains(" ") ? "" : " · " + Arr.day(d));
                if (!day.equals(last)) { last = day; dayHead(day, ac); }
                if (tv) episodeRow(e, d == null ? "" : Arr.clock(d));
                else movieRow(e, "");
            }
        }), m -> fail(h, (tv ? "Sonarr: " : "Radarr: ") + m));
    }

    /** The movie's first release date that hasn't passed (yesterday counts), else its latest one. */
    private static Date upcomingDate(JSONObject m) {
        Date best = null, last = null;
        long floor = System.currentTimeMillis() - 86400000L;
        for (String k : new String[]{"inCinemas", "digitalRelease", "physicalRelease"}) {
            Date d = Arr.date(m.optString(k));
            if (d == null) continue;
            if (last == null || d.after(last)) last = d;
            if (d.getTime() >= floor && (best == null || d.before(best))) best = d;
        }
        return best != null ? best : last;
    }

    private static String releaseDate(JSONObject e, boolean tv) {
        if (tv) return e.optString("airDateUtc");
        for (String k : new String[]{"digitalRelease", "physicalRelease", "inCinemas"}) if (!e.optString(k).isEmpty()) return e.optString(k);
        return "";
    }

    private void episodeRow(JSONObject e, String when) {
        JSONObject s = e.optJSONObject("series");
        boolean has = e.optBoolean("hasFile");
        String title = (s == null ? "" : s.optString("title"));
        row(s == null ? null : Arr.art("sonarr", s, "poster"), title, Arr.ep(e) + " · " + e.optString("title") + (when.isEmpty() ? "" : " · " + when),
                has ? "✓ Downloaded" : e.optBoolean("monitored", true) ? "" : "Unmonitored", has ? Ui.GREEN : Ui.SUB, -1, Arr.SONARR,
                v -> Sheets.actions(this, title + " " + Arr.ep(e), e.optString("title"),
                        Sheets.Item.of(R.drawable.ic_search, Arr.SONARR, "Automatic search", "Grab the best release", () -> act(() ->
                                command(SON, new JSONObject().put("name", "EpisodeSearch").put("episodeIds", new JSONArray().put(e.optInt("id")))), "Search started", null)),
                        Sheets.Item.of(R.drawable.ic_tools, Arr.SONARR, "Interactive search", "Pick a release yourself", () ->
                                ArrAdd.releases(this, hub, "sonarr", "episodeId=" + e.optInt("id"), title + " " + Arr.ep(e))),
                        Sheets.Item.of(R.drawable.ic_tv, Arr.SONARR, "Open series", null, () -> openDetail("sonarr", e.optInt("seriesId")))));
    }

    private void movieRow(JSONObject m, String extra) {
        boolean has = m.optBoolean("hasFile");
        String iso = releaseDate(m, false);
        Date d = Arr.date(iso);
        String rel = d == null ? "" : (iso.equals(m.optString("digitalRelease")) ? "Digital " : iso.equals(m.optString("physicalRelease")) ? "Physical " : "Cinemas ") + Arr.day(d);
        row(Arr.art("radarr", m, "poster"), m.optString("title") + " (" + m.optInt("year") + ")", rel + (extra.isEmpty() ? "" : " · " + extra),
                has ? "✓ Downloaded" : "", Ui.GREEN, -1, Arr.RADARR, v -> openDetail("radarr", m.optInt("id")));
    }

    private void activity(boolean tv) {
        String base = tv ? SON : RAD;
        int ac = tv ? Arr.SONARR : Arr.RADARR;
        header("Queue", null, 0, null);
        LinearLayout q = holder();
        loading(q, 3);
        bg(() -> obj(base + "queue?pageSize=50&" + (tv ? "includeSeries=true&includeEpisode=true" : "includeMovie=true")), r -> fill(q, () -> {
            JSONArray rec = r.optJSONArray("records");
            if (rec == null || rec.length() == 0) { note("Queue is empty.", Ui.SUB); return; }
            if (r.optInt("totalRecords") > rec.length()) note(r.optInt("totalRecords") + " items · showing " + rec.length(), Ui.SUB);
            for (int i = 0; i < rec.length(); i++) {
                JSONObject x = rec.optJSONObject(i);
                JSONObject s = x.optJSONObject(tv ? "series" : "movie"), e = x.optJSONObject("episode");
                long size = x.optLong("size"), left = x.optLong("sizeleft");
                float p = size > 0 ? 1 - left / (float) size : 0;
                String err = x.optString("errorMessage");
                String title = s == null ? x.optString("title") : s.optString("title") + (e != null ? " " + Arr.ep(e) : "");
                String status = x.optString("trackedDownloadState", x.optString("status"));
                String sub = Math.round(p * 100) + "% of " + Arr.size(size) + (x.has("timeleft") ? " · " + x.optString("timeleft") + " left" : "")
                        + " · " + x.optString("indexer") + (err.isEmpty() ? "" : "\n⚠ " + err);
                row(s == null ? null : Arr.art(tv ? "sonarr" : "radarr", s, "poster"), title, sub, status, err.isEmpty() ? ac : Ui.AMBER, p, err.isEmpty() ? ac : Ui.AMBER,
                        v -> Sheets.actions(this, title, x.optString("title"),
                                Sheets.Item.danger(R.drawable.ic_delete, "Remove", "Remove from the queue and the download client", () -> act(() ->
                                        hub.arr("DELETE", base + "queue/" + x.optInt("id") + "?removeFromClient=true&blocklist=false", null, null), "Removed", () -> show(current))),
                                Sheets.Item.danger(R.drawable.ic_delete, "Remove and blocklist", "Never grab this release again; search for another", () -> act(() ->
                                        hub.arr("DELETE", base + "queue/" + x.optInt("id") + "?removeFromClient=true&blocklist=true", null, null), "Blocklisted", () -> show(current)))));
            }
        }), m -> fail(q, m));

        header("History", null, 0, null);
        LinearLayout h = holder();
        loading(h, 3);
        bg(() -> obj(base + "history?pageSize=30&sortKey=date&sortDirection=descending&" + (tv ? "includeSeries=true&includeEpisode=true" : "includeMovie=true")), r -> fill(h, () -> {
            JSONArray rec = r.optJSONArray("records");
            if (rec == null || rec.length() == 0) { note("No history.", Ui.SUB); return; }
            for (int i = 0; i < rec.length(); i++) {
                JSONObject x = rec.optJSONObject(i), s = x.optJSONObject(tv ? "series" : "movie"), e = x.optJSONObject("episode");
                String ev = x.optString("eventType");
                String label; int color;
                switch (ev) {
                    case "grabbed": label = "Grabbed"; color = ac; break;
                    case "downloadFolderImported": label = "Imported"; color = Ui.GREEN; break;
                    case "downloadFailed": label = "Failed"; color = Ui.RED; break;
                    case "episodeFileDeleted": case "movieFileDeleted": label = "Deleted"; color = Ui.SUB; break;
                    case "episodeFileRenamed": case "movieFileRenamed": label = "Renamed"; color = Ui.SUB; break;
                    default: label = ev; color = Ui.SUB;
                }
                JSONObject qq = x.optJSONObject("quality");
                String qn = qq == null || qq.optJSONObject("quality") == null ? "" : qq.optJSONObject("quality").optString("name");
                String title = (s == null ? "" : s.optString("title")) + (e != null ? " " + Arr.ep(e) : "");
                boolean grabbed = ev.equals("grabbed");
                row(null, title, qn + " · " + Arr.ago(Arr.date(x.optString("date"))) + "\n" + x.optString("sourceTitle"), label, color, -1, ac,
                        v -> {
                            List<Sheets.Item> items = new ArrayList<>();
                            if (s != null) items.add(Sheets.Item.of(tv ? R.drawable.ic_tv : R.drawable.ic_movie, ac, "Open " + s.optString("title"), null,
                                    () -> openDetail(tv ? "sonarr" : "radarr", s.optInt("id"))));
                            if (grabbed) items.add(Sheets.Item.danger(R.drawable.ic_delete, "Mark as failed", "Blocklist this release and search for another",
                                    () -> act(() -> post(base + "history/failed/" + x.optInt("id"), "", Arr.JSON), "Marked as failed", () -> show(current))));
                            Sheets.actions(this, title, x.optString("sourceTitle"), items.toArray(new Sheets.Item[0]));
                        });
            }
        }), m -> fail(h, m));
    }

    private void wanted(boolean tv) {
        String base = tv ? SON : RAD;
        int ac = tv ? Arr.SONARR : Arr.RADARR;
        LinearLayout btns = new LinearLayout(this);
        TextView all = Ui.ghostButton(this, tv ? "Search all missing" : "Search all missing movies", ac);
        all.setOnClickListener(v -> Sheets.confirm(this, "Search everything missing?", "This asks every indexer for every monitored missing item.", "Search", false,
                () -> act(() -> command(base, new JSONObject().put("name", tv ? "MissingEpisodeSearch" : "MissingMoviesSearch")), "Search started", null)));
        btns.addView(all);
        TextView cut = Ui.ghostButton(this, "Search upgrades", ac);
        cut.setOnClickListener(v -> act(() -> command(base, new JSONObject().put("name", tv ? "CutoffUnmetEpisodeSearch" : "CutOffUnmetMoviesSearch")), "Upgrade search started", null));
        btns.addView(cut);
        dest.addView(btns, wrap());
        for (String kind : new String[]{"missing", "cutoff"}) {
            header(kind.equals("missing") ? "Missing" : "Cutoff unmet", null, 0, null);
            LinearLayout h = holder();
            loading(h, 3);
            String q = base + "wanted/" + kind + "?pageSize=30&monitored=true&sortDirection=descending&" + (tv ? "sortKey=episodes.airDateUtc&includeSeries=true" : "");
            bg(() -> obj(q), r -> fill(h, () -> {
                JSONArray rec = r.optJSONArray("records");
                if (rec == null || rec.length() == 0) { note("Nothing here.", Ui.SUB); return; }
                for (int i = 0; i < rec.length(); i++) {
                    JSONObject e = rec.optJSONObject(i);
                    if (tv) episodeRow(e, Arr.day(Arr.date(e.optString("airDateUtc")) == null ? new Date() : Arr.date(e.optString("airDateUtc"))));
                    else movieRow(e, "");
                }
                if (r.optInt("totalRecords") > rec.length()) note("+ " + (r.optInt("totalRecords") - rec.length()) + " more", Ui.SUB);
            }), m -> fail(h, m));
        }
    }

    /** Status, updates, health, disk, scheduled tasks (tap to run now) and the blocklist of one *arr. */
    private void system(String base, int ac, boolean arr) {
        LinearLayout status = holder();
        loading(status, 2);
        bg(() -> new Object[]{obj(base + "system/status"), hub.arr(base + "update"), arr(base + "health")}, r -> fill(status, () -> {
            JSONObject st = (JSONObject) r[0];
            Date start = Arr.date(st.optString("startTime"));
            LinearLayout c = card();
            c.addView(Ui.text(this, st.optString("appName") + " " + st.optString("version"), 17, Ui.TEXT, true));
            c.addView(Ui.caption(this, (start == null ? "" : "Running since " + Arr.ago(start).replace(" ago", "")) + " · " + st.optString("osName") + " · " + st.optString("runtimeVersion"), Ui.SUB));
            if (r[1] instanceof JSONArray) {
                JSONArray up = (JSONArray) r[1];
                for (int i = 0; i < up.length(); i++) {
                    JSONObject u = up.optJSONObject(i);
                    if (u.optBoolean("latest") && !u.optBoolean("installed")) {
                        TextView t = Ui.text(this, "Update available: " + u.optString("version") + " (installs from the web UI or container image)", 13, Ui.AMBER, false);
                        t.setPadding(0, dp(6), 0, 0);
                        c.addView(t);
                    }
                }
            }
            JSONArray h = (JSONArray) r[2];
            header("Health", null, 0, null);
            if (h.length() == 0) note("No problems reported.", Ui.GREEN);
            for (int i = 0; i < h.length(); i++) {
                JSONObject x = h.optJSONObject(i);
                row(null, x.optString("source"), x.optString("message"), x.optString("type"), "error".equals(x.optString("type")) ? Ui.RED : Ui.AMBER, -1, ac, null);
            }
        }), m -> fail(status, m));

        if (arr) {
            header("Disk space", null, 0, null);
            LinearLayout disk = holder();
            bg(() -> arr(base + "diskspace"), r -> fill(disk, () -> {
                java.util.Set<String> seen = new java.util.HashSet<>();
                for (int i = 0; i < r.length(); i++) {
                    JSONObject d = r.optJSONObject(i);
                    long free = d.optLong("freeSpace"), total = d.optLong("totalSpace");
                    if (!seen.add(total + ":" + free / (1 << 26))) continue; // bind mounts of one disk: show it once
                    float used = total == 0 ? 0 : 1 - free / (float) total;
                    row(null, d.optString("path"), Arr.size(free) + " free of " + Arr.size(total), Math.round(used * 100) + "%",
                            free < 100L << 30 ? Ui.AMBER : Ui.SUB, used, used > 0.95f ? Ui.RED : used > 0.85f ? Ui.AMBER : Ui.GREEN, null);
                }
            }), m -> fail(disk, m));
        }

        header("Tasks", null, 0, null);
        note("Tap a task to run it now.", Ui.SUB);
        LinearLayout tasks = holder();
        loading(tasks, 3);
        bg(() -> arr(base + "system/task"), r -> fill(tasks, () -> {
            for (int i = 0; i < r.length(); i++) {
                JSONObject t = r.optJSONObject(i);
                Date last = Arr.date(t.optString("lastExecution")), next = Arr.date(t.optString("nextExecution"));
                String sub = "Last " + (last == null ? "never" : Arr.ago(last)) + (next == null ? "" : " · next " + Arr.when(next) + " " + Arr.clock(next))
                        + " · every " + (t.optInt("interval") >= 60 ? t.optInt("interval") / 60 + " h" : t.optInt("interval") + " min");
                String name = t.optString("taskName"), label = t.optString("name");
                row(null, label, sub, "Run", ac, -1, ac, v -> act(() -> command(base, new JSONObject().put("name", name)), label + " started", () -> show(current)));
            }
        }), m -> fail(tasks, m));

        if (arr) {
            header("Blocklist", null, 0, null);
            LinearLayout bl = holder();
            bg(() -> obj(base + "blocklist?page=1&pageSize=50&sortKey=date&sortDirection=descending"), r -> fill(bl, () -> {
                JSONArray rec = r.optJSONArray("records");
                if (rec == null || rec.length() == 0) { note("Nothing blocklisted.", Ui.SUB); return; }
                TextView clear = Ui.ghostButton(this, "Clear all " + r.optInt("totalRecords"), Ui.RED);
                clear.setOnClickListener(v -> Sheets.confirm(this, "Clear the blocklist?", "Releases that failed before may be grabbed again.", "Clear", true,
                        () -> act(() -> { for (int i = 0; i < rec.length(); i++) hub.arr("DELETE", base + "blocklist/" + rec.getJSONObject(i).optInt("id"), null, null); return null; },
                                "Blocklist cleared", () -> show(current))));
                dest.addView(clear);
                for (int i = 0; i < rec.length(); i++) {
                    JSONObject x = rec.optJSONObject(i);
                    String sub = Arr.ago(Arr.date(x.optString("date"))) + " · " + x.optString("indexer") + (x.optString("message").isEmpty() ? "" : "\n" + x.optString("message"));
                    row(null, x.optString("sourceTitle"), sub, null, 0, -1, ac, v -> Sheets.actions(this, "Blocklisted release", x.optString("sourceTitle"),
                            Sheets.Item.of(R.drawable.ic_delete, ac, "Remove from blocklist", "It may be grabbed again", () ->
                                    act(() -> hub.arr("DELETE", base + "blocklist/" + x.optInt("id"), null, null), "Removed", () -> show(current)))));
                }
            }), m -> fail(bl, m));
        }
    }

    // ---------------------------------------------------------------- MOVIES

    private void movies() {
        String s = subTabs(Page.MOVIES, new String[]{"Library", "Discover", "Upcoming", "Activity", "Wanted", "System"});
        switch (s) {
            case "System": system(RAD, Arr.RADARR, true); break;
            case "Discover": discover(); break;
            case "Upcoming": upcoming(false); break;
            case "Activity": activity(false); break;
            case "Wanted": wanted(false); break;
            default: movieLibrary();
        }
    }

    private void movieLibrary() {
        LinearLayout sorts = Arr.chipRow(this, dest), filters = Arr.chipRow(this, dest);
        for (String n : new String[]{"A–Z", "Added", "Release", "Size"}) Arr.addChip(this, sorts, Arr.chip(this, n, n.equals(movieSort), Arr.RADARR, v -> { movieSort = n; show(Page.MOVIES); }));
        for (String n : new String[]{"All", "Downloaded", "Missing", "Unmonitored"}) Arr.addChip(this, filters, Arr.chip(this, n, n.equals(movieFilter), Arr.RADARR, v -> { movieFilter = n; show(Page.MOVIES); }));
        LinearLayout h = holder();
        loading(h, 4);
        bg(() -> moviesCache != null ? moviesCache : arr(RAD + "movie"), r -> { moviesCache = r; fill(h, () -> {
            List<JSONObject> l = new ArrayList<>();
            long bytes = 0; int have = 0;
            for (int i = 0; i < r.length(); i++) {
                JSONObject m = r.optJSONObject(i);
                bytes += m.optLong("sizeOnDisk"); if (m.optBoolean("hasFile")) have++;
                if (movieFilter.equals("Downloaded") && !m.optBoolean("hasFile")) continue;
                if (movieFilter.equals("Missing") && (m.optBoolean("hasFile") || !m.optBoolean("monitored"))) continue;
                if (movieFilter.equals("Unmonitored") && m.optBoolean("monitored")) continue;
                l.add(m);
            }
            l.sort((a, b) -> {
                switch (movieSort) {
                    case "Added": return b.optString("added").compareTo(a.optString("added"));
                    case "Release": return releaseDate(b, false).compareTo(releaseDate(a, false));
                    case "Size": return Long.compare(b.optLong("sizeOnDisk"), a.optLong("sizeOnDisk"));
                    default: return a.optString("sortTitle").compareTo(b.optString("sortTitle"));
                }
            });
            note(r.length() + " movies · " + have + " downloaded · " + Arr.size(bytes), Ui.SUB);
            int w = Arr.gridWidthDp(this, 3);
            List<View> cards = new ArrayList<>();
            for (JSONObject m : l) {
                boolean has = m.optBoolean("hasFile");
                cards.add(Arr.poster(this, hub, w, Arr.art("radarr", m, "poster"), m.optString("title"), m.optInt("year") + (has ? " · ✓" : m.optBoolean("monitored") ? " · missing" : ""),
                        rating(m), Arr.RADARR, -1, Arr.RADARR, v -> openDetail("radarr", m.optInt("id"))));
            }
            if (cards.isEmpty()) note(r.length() == 0 ? "Radarr has no movies yet. Try Discover." : "No movies match.", Ui.SUB);
            Arr.grid(this, dest, cards, 3);
        }); }, m -> fail(h, "Radarr: " + m));
    }

    private void discover() {
        note("From your Radarr lists and TMDB recommendations. Tap to add.", Ui.SUB);
        LinearLayout h = holder();
        loading(h, 4);
        bg(() -> arr(RAD + "importlist/movie?includeRecommendations=true"), r -> fill(h, () -> {
            int w = Arr.gridWidthDp(this, 3);
            List<View> cards = new ArrayList<>();
            for (int i = 0; i < r.length(); i++) {
                JSONObject m = r.optJSONObject(i);
                if (m.optBoolean("isExisting") || m.optBoolean("isExcluded")) continue;
                cards.add(Arr.poster(this, hub, w, Arr.art("radarr", m, "poster"), m.optString("title"), String.valueOf(m.optInt("year")),
                        rating(m), Arr.RADARR, -1, Arr.RADARR, v -> ArrAdd.add(this, hub, m, true, null)));
            }
            if (cards.isEmpty()) note("No recommendations yet.", Ui.SUB);
            Arr.grid(this, dest, cards, 3);
        }), m -> fail(h, "Radarr: " + m));
    }

    // ---------------------------------------------------------------- DOWNLOADS

    private void downloads() {
        hero();
        LinearLayout acts = Arr.chipRow(this, dest);
        Arr.addChip(this, acts, Arr.chip(this, "+ Add torrent", true, Arr.QBIT, v -> addTorrent()));
        Arr.addChip(this, acts, Arr.chip(this, "Speed limits", false, Arr.QBIT, v -> limits()));
        Arr.addChip(this, acts, Arr.chip(this, "Clean up finished", false, Arr.QBIT, v -> cleanup()));
        LinearLayout filters = Arr.chipRow(this, dest), sorts = Arr.chipRow(this, dest);
        filters.setTag("filters"); sorts.setTag("sorts");
        dlList = holder();
        loading(dlList, 5);
        if (torrents != null) renderTorrents();
        poll();
    }

    /** {all, downloading, seeding, paused, completed, stalled, errored} */
    private int[] counts() {
        int[] c = new int[7];
        if (torrents == null) return c;
        for (int i = 0; i < torrents.length(); i++) {
            String s = torrents.optJSONObject(i).optString("state");
            c[0]++;
            for (int k = 1; k < 7; k++) if (matches(torrents.optJSONObject(i), new String[]{"", "Downloading", "Seeding", "Paused", "Completed", "Stalled", "Errored"}[k])) c[k]++;
        }
        return c;
    }

    private static boolean matches(JSONObject t, String f) {
        String s = t.optString("state");
        switch (f) {
            case "Downloading": return s.equals("downloading") || s.equals("forcedDL") || s.equals("metaDL") || s.equals("queuedDL") || s.equals("checkingDL");
            case "Seeding": return s.equals("uploading") || s.equals("forcedUP") || s.equals("stalledUP") || s.equals("queuedUP");
            case "Paused": return s.startsWith("paused") || s.startsWith("stopped");
            case "Completed": return t.optDouble("progress") >= 1;
            case "Stalled": return s.equals("stalledDL");
            case "Errored": return s.equals("error") || s.equals("missingFiles");
            default: return true;
        }
    }

    private void renderTorrents() {
        if (dlList == null || torrents == null) return;
        LinearLayout filters = null, sorts = null;
        for (int i = 0; i < page.getChildCount(); i++) {
            View v = page.getChildAt(i);
            if (v instanceof HorizontalScrollView) {
                View inner = ((HorizontalScrollView) v).getChildAt(0);
                if ("filters".equals(inner.getTag())) filters = (LinearLayout) inner;
                if ("sorts".equals(inner.getTag())) sorts = (LinearLayout) inner;
            }
        }
        int[] c = counts();
        String[] names = {"All", "Downloading", "Seeding", "Paused", "Completed", "Stalled", "Errored"};
        if (filters != null) {
            filters.removeAllViews();
            for (int k = 0; k < names.length; k++) {
                String n = names[k];
                if (c[k] == 0 && !n.equals(dlFilter) && k > 0) continue;
                Arr.addChip(this, filters, Arr.chip(this, n + "  " + c[k], n.equals(dlFilter), Arr.QBIT, v -> { dlFilter = n; renderTorrents(); }));
            }
        }
        if (sorts != null) {
            sorts.removeAllViews();
            for (String n : new String[]{"Added", "Name", "Progress", "Speed", "Size"}) Arr.addChip(this, sorts, Arr.chip(this, n, n.equals(dlSort), Arr.QBIT, v -> { dlSort = n; renderTorrents(); }));
        }
        List<JSONObject> l = new ArrayList<>();
        for (int i = 0; i < torrents.length(); i++) if (matches(torrents.optJSONObject(i), dlFilter)) l.add(torrents.optJSONObject(i));
        l.sort((a, b) -> {
            switch (dlSort) {
                case "Name": return a.optString("name").compareToIgnoreCase(b.optString("name"));
                case "Progress": return Double.compare(b.optDouble("progress"), a.optDouble("progress"));
                case "Speed": return Long.compare(b.optLong("dlspeed") + b.optLong("upspeed"), a.optLong("dlspeed") + a.optLong("upspeed"));
                case "Size": return Long.compare(b.optLong("size"), a.optLong("size"));
                default: return Long.compare(b.optLong("added_on"), a.optLong("added_on"));
            }
        });
        fill(dlList, () -> {
            if (l.isEmpty()) note("Nothing here.", Ui.SUB);
            for (int i = 0; i < Math.min(l.size(), 150); i++) torrentRow(l.get(i));
        });
    }

    private void torrentRow(JSONObject t) {
        String state = t.optString("state"), hash = t.optString("hash"), name = t.optString("name");
        boolean paused = state.startsWith("paused") || state.startsWith("stopped");
        double p = t.optDouble("progress");
        String label; int color;
        if (matches(t, "Errored")) { label = "Error"; color = Ui.RED; }
        else if (paused) { label = p >= 1 ? "Done" : "Paused"; color = Ui.SUB; }
        else if (state.equals("stalledDL")) { label = "Stalled"; color = Ui.AMBER; }
        else if (matches(t, "Seeding")) { label = "Seeding"; color = Ui.GREEN; }
        else if (state.equals("metaDL")) { label = "Metadata"; color = Arr.QBIT; }
        else if (state.startsWith("queued")) { label = "Queued"; color = Ui.SUB; }
        else if (state.startsWith("checking")) { label = "Checking"; color = Arr.QBIT; }
        else { label = "Downloading"; color = Arr.QBIT; }
        StringBuilder sub = new StringBuilder();
        sub.append(String.format(Locale.US, "%.1f%%", p * 100)).append(" of ").append(Arr.size(t.optLong("size")));
        if (t.optLong("dlspeed") > 0) sub.append(" · ↓ ").append(Arr.speed(t.optLong("dlspeed"))).append(" · ").append(Arr.eta(t.optLong("eta")));
        if (t.optLong("upspeed") > 0) sub.append(" · ↑ ").append(Arr.speed(t.optLong("upspeed")));
        sub.append("\n").append(t.optInt("num_seeds")).append(" seeds · ").append(t.optInt("num_leechs")).append(" peers · ratio ").append(String.format(Locale.US, "%.2f", t.optDouble("ratio")));
        if (!t.optString("category").isEmpty()) sub.append(" · ").append(t.optString("category"));
        row(null, name, sub.toString(), label, color, (float) p, color == Ui.SUB ? Ui.FAINT : color, v -> torrentSheet(t, paused));
    }

    private void torrentSheet(JSONObject t, boolean paused) {
        String hash = t.optString("hash"), name = t.optString("name");
        ScrollView sv = new ScrollView(this);
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(16), 0, dp(16), dp(8));
        sv.addView(box);
        String[][] props = {
                {"Progress", String.format(Locale.US, "%.1f%% of %s", t.optDouble("progress") * 100, Arr.size(t.optLong("size")))},
                {"Speed", "↓ " + Arr.speed(t.optLong("dlspeed")) + "  ↑ " + Arr.speed(t.optLong("upspeed"))},
                {"ETA", Arr.eta(t.optLong("eta"))},
                {"Ratio", String.format(Locale.US, "%.2f · uploaded %s", t.optDouble("ratio"), Arr.size(t.optLong("uploaded")))},
                {"Peers", t.optInt("num_seeds") + " seeds · " + t.optInt("num_leechs") + " peers"},
                {"Category", t.optString("category", "—")},
                {"Added", Arr.ago(new Date(t.optLong("added_on") * 1000))},
                {"Save path", t.optString("save_path")}};
        for (String[] kv : props) {
            LinearLayout r = new LinearLayout(this);
            r.setPadding(0, dp(5), 0, dp(5));
            r.addView(Ui.text(this, kv[0], 13, Ui.SUB, false), new LinearLayout.LayoutParams(dp(96), -2));
            TextView v = Ui.text(this, kv[1], 13, Ui.TEXT, false);
            r.addView(v, new LinearLayout.LayoutParams(0, -2, 1));
            box.addView(r);
        }
        TextView fl = Ui.label(this, "Files", Ui.SUB);
        fl.setPadding(0, dp(12), 0, dp(4));
        box.addView(fl);
        TextView files = Ui.text(this, "Loading…", 12, Ui.SUB, false);
        box.addView(files);
        net.execute(() -> {
            try {
                JSONArray f = arr(QB + "torrents/files?hash=" + hash);
                StringBuilder b = new StringBuilder();
                for (int i = 0; i < Math.min(f.length(), 40); i++) {
                    JSONObject x = f.getJSONObject(i);
                    b.append(String.format(Locale.US, "%3.0f%%  ", x.optDouble("progress") * 100)).append(x.optString("name")).append("  ·  ").append(Arr.size(x.optLong("size"))).append('\n');
                }
                if (f.length() > 40) b.append("+ ").append(f.length() - 40).append(" more");
                runOnUiThread(() -> files.setText(b.toString().trim()));
            } catch (Exception e) { runOnUiThread(() -> files.setText("Couldn't load files")); }
        });
        Runnable reload = () -> { torrents = null; poll(); };
        Sheets.tall(this, name, null, sv,
                Sheets.Item.of(paused ? R.drawable.ic_play : R.drawable.ic_pause, Arr.QBIT, paused ? "Resume" : "Pause", null,
                        () -> act(() -> post(QB + "torrents/" + (paused ? "start" : "stop"), "hashes=" + hash, Arr.FORM), paused ? "Resumed" : "Paused", reload)),
                Sheets.Item.of(R.drawable.ic_bolt, Arr.QBIT, "Force start", "Ignore queue limits",
                        () -> act(() -> post(QB + "torrents/setForceStart", "hashes=" + hash + "&value=true", Arr.FORM), "Forced", reload)),
                Sheets.Item.of(R.drawable.ic_refresh, Arr.QBIT, "Reannounce", "Ask trackers for peers now",
                        () -> act(() -> post(QB + "torrents/reannounce", "hashes=" + hash, Arr.FORM), "Reannounced", null)),
                Sheets.Item.of(R.drawable.ic_archive, Arr.QBIT, "Category", t.optString("category").isEmpty() ? "None" : t.optString("category"), () -> pickCategory(hash)),
                Sheets.Item.of(R.drawable.ic_pin, Arr.QBIT, "Top priority", "Move to the front of the queue",
                        () -> act(() -> post(QB + "torrents/topPrio", "hashes=" + hash, Arr.FORM), "Moved to top", reload)),
                Sheets.Item.of(R.drawable.ic_shield, Arr.QBIT, "Recheck", "Verify downloaded data",
                        () -> act(() -> post(QB + "torrents/recheck", "hashes=" + hash, Arr.FORM), "Rechecking", reload)),
                Sheets.Item.danger(R.drawable.ic_delete, "Remove", "Keep files", () -> removeTorrent(hash, name, false)),
                Sheets.Item.danger(R.drawable.ic_delete, "Remove and delete files", null, () -> removeTorrent(hash, name, true)));
    }

    private EditText field(LinearLayout f, String label, String hint, int type) {
        TextView l = Ui.label(this, label, Ui.SUB);
        l.setPadding(dp(4), dp(10), 0, dp(4));
        f.addView(l);
        EditText e = new EditText(this);
        e.setHint(hint); e.setHintTextColor(Ui.FAINT); e.setTextColor(Ui.TEXT); e.setInputType(type);
        e.setBackground(Ui.round(this, Ui.RAISED, 10)); e.setPadding(dp(12), dp(10), dp(12), dp(10));
        f.addView(e, wrap());
        return e;
    }

    private LinearLayout formBox() {
        LinearLayout f = new LinearLayout(this);
        f.setOrientation(LinearLayout.VERTICAL);
        f.setPadding(dp(16), 0, dp(16), dp(8));
        return f;
    }

    /** Category names from qBittorrent ("" first = none). */
    private List<String> categories() throws Exception {
        JSONObject c = obj(QB + "torrents/categories");
        List<String> out = new ArrayList<>();
        out.add("");
        java.util.Iterator<String> it = c.keys();
        while (it.hasNext()) out.add(it.next());
        java.util.Collections.sort(out);
        return out;
    }

    private void addTorrent() {
        bg(this::categories, cats -> {
            LinearLayout f = formBox();
            String clip = "";
            try {
                android.content.ClipboardManager cm = (android.content.ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
                CharSequence t = cm.hasPrimaryClip() ? cm.getPrimaryClip().getItemAt(0).getText() : null;
                if (t != null && (t.toString().startsWith("magnet:") || t.toString().matches("https?://.*\\.torrent.*"))) clip = t.toString();
            } catch (Exception ignored) { }
            EditText url = field(f, "Magnet link or .torrent URL", "magnet:?xt=…", InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
            url.setText(clip);
            TextView l = Ui.label(this, "Category", Ui.SUB);
            l.setPadding(dp(4), dp(12), 0, dp(6));
            f.addView(l);
            LinearLayout row = Arr.chipRow(this, f);
            String[] pick = {""};
            Runnable[] draw = new Runnable[1];
            draw[0] = () -> {
                row.removeAllViews();
                for (String c : cats) Arr.addChip(this, row, Arr.chip(this, c.isEmpty() ? "None" : c, c.equals(pick[0]), Arr.QBIT, v -> { pick[0] = c; draw[0].run(); }));
            };
            draw[0].run();
            Sheets.form(this, "Add torrent", f, "Add", () -> {
                String u = url.getText().toString().trim();
                if (u.isEmpty()) { Toast.makeText(this, "Paste a magnet link or URL", Toast.LENGTH_SHORT).show(); return false; }
                act(() -> post(QB + "torrents/add", "urls=" + Arr.enc(u) + (pick[0].isEmpty() ? "" : "&category=" + Arr.enc(pick[0])), Arr.FORM), "Added to qBittorrent", () -> { torrents = null; poll(); });
                return true;
            });
        }, m -> Toast.makeText(this, "qBittorrent: " + m, Toast.LENGTH_SHORT).show());
    }

    private void limits() {
        bg(() -> new long[]{Long.parseLong(String.valueOf(hub.arr(QB + "transfer/downloadLimit")).trim()), Long.parseLong(String.valueOf(hub.arr(QB + "transfer/uploadLimit")).trim())}, cur -> {
            LinearLayout f = formBox();
            EditText down = field(f, "Download limit (KB/s, 0 = unlimited)", "0", InputType.TYPE_CLASS_NUMBER);
            EditText up = field(f, "Upload limit (KB/s, 0 = unlimited)", "0", InputType.TYPE_CLASS_NUMBER);
            down.setText(String.valueOf(cur[0] / 1024)); up.setText(String.valueOf(cur[1] / 1024));
            Sheets.form(this, "Speed limits", f, "Save", () -> {
                long d, u;
                try { d = Long.parseLong(down.getText().toString().trim()) * 1024; u = Long.parseLong(up.getText().toString().trim()) * 1024; }
                catch (NumberFormatException e) { Toast.makeText(this, "Whole numbers only", Toast.LENGTH_SHORT).show(); return false; }
                act(() -> { post(QB + "transfer/setDownloadLimit", "limit=" + d, Arr.FORM); return post(QB + "transfer/setUploadLimit", "limit=" + u, Arr.FORM); },
                        "Limits saved", this::poll);
                return true;
            });
        }, m -> Toast.makeText(this, "qBittorrent: " + m, Toast.LENGTH_SHORT).show());
    }

    private void cleanup() {
        if (torrents == null) return;
        List<String> hashes = new ArrayList<>();
        long bytes = 0;
        for (int i = 0; i < torrents.length(); i++) {
            JSONObject t = torrents.optJSONObject(i);
            if (t.optDouble("progress") >= 1) { hashes.add(t.optString("hash")); bytes += t.optLong("size"); }
        }
        if (hashes.isEmpty()) { Toast.makeText(this, "Nothing finished to clean up", Toast.LENGTH_SHORT).show(); return; }
        String all = TextUtils.join("|", hashes);
        String freed = Arr.size(bytes);
        Sheets.actions(this, "Clean up " + hashes.size() + " finished torrents", freed + " · Sonarr/Radarr have already imported finished downloads",
                Sheets.Item.of(R.drawable.ic_delete, Arr.QBIT, "Remove from qBittorrent", "Keep the files on disk",
                        () -> act(() -> post(QB + "torrents/delete", "hashes=" + all + "&deleteFiles=false", Arr.FORM), "Removed " + hashes.size(), () -> { torrents = null; poll(); })),
                Sheets.Item.danger(R.drawable.ic_delete, "Remove and delete the files", "Frees " + freed + " — imported copies stay in the library only if they were copied, not hardlinked",
                        () -> Sheets.confirm(this, "Delete " + freed + " of files?", "This can't be undone.", "Delete", true,
                                () -> act(() -> post(QB + "torrents/delete", "hashes=" + all + "&deleteFiles=true", Arr.FORM), "Deleted", () -> { torrents = null; poll(); }))));
    }

    private void pickCategory(String hash) {
        bg(this::categories, cats -> {
            Sheets.Item[] items = new Sheets.Item[cats.size()];
            for (int i = 0; i < items.length; i++) {
                String c = cats.get(i);
                items[i] = Sheets.Item.of(R.drawable.ic_archive, Arr.QBIT, c.isEmpty() ? "None" : c, null,
                        () -> act(() -> post(QB + "torrents/setCategory", "hashes=" + hash + "&category=" + Arr.enc(c), Arr.FORM), "Category set", () -> { torrents = null; poll(); }));
            }
            Sheets.actions(this, "Category", null, items);
        }, m -> Toast.makeText(this, m, Toast.LENGTH_SHORT).show());
    }

    private void removeTorrent(String hash, String name, boolean files) {
        Sheets.confirm(this, files ? "Delete files too?" : "Remove torrent?", name, "Remove", true,
                () -> act(() -> post(QB + "torrents/delete", "hashes=" + hash + "&deleteFiles=" + files, Arr.FORM), "Removed", () -> { torrents = null; poll(); }));
    }

    // ---------------------------------------------------------------- INDEXERS

    private void indexers() {
        LinearLayout box = searchBox("Search all indexers", q -> { query = q; show(Page.INDEXERS); });
        if (!query.isEmpty()) {
            ((EditText) box.getChildAt(0)).setText(query);
            header("Results for “" + query + "”", null, 0, null);
            LinearLayout h = holder();
            fill(h, () -> note("Searching every indexer…", Ui.SUB));
            TextView wait = (TextView) h.getChildAt(0);
            bg(() -> (JSONArray) ArrAdd.waitFor(hub, PRO + "search?type=search&limit=100&query=" + Arr.enc(query),
                    s -> runOnUiThread(() -> wait.setText("Searching every indexer… " + s + " s"))), r -> fill(h, () -> {
                List<JSONObject> l = new ArrayList<>();
                for (int i = 0; i < r.length(); i++) l.add(r.optJSONObject(i));
                l.sort((a, b) -> Integer.compare(b.optInt("seeders"), a.optInt("seeders")));
                if (l.isEmpty()) note("No results.", Ui.SUB);
                for (int i = 0; i < Math.min(l.size(), 60); i++) {
                    JSONObject x = l.get(i);
                    String sub = Arr.size(x.optLong("size")) + " · " + x.optString("indexer") + " · " + Arr.ago(Arr.date(x.optString("publishDate")));
                    String tag = "torrent".equals(x.optString("protocol")) ? "▲" + x.optInt("seeders") + " ▼" + x.optInt("leechers") : "usenet";
                    String link = !x.optString("magnetUrl").isEmpty() ? x.optString("magnetUrl") : x.optString("downloadUrl");
                    row(null, x.optString("title"), sub, tag, x.optInt("seeders") > 0 ? Ui.GREEN : Ui.SUB, -1, Arr.PROWLARR,
                            v -> Sheets.actions(this, x.optString("title"), sub,
                                    Sheets.Item.of(R.drawable.ic_download, Arr.QBIT, "Send to qBittorrent", "Start downloading now", () -> act(() ->
                                            post(QB + "torrents/add", "urls=" + Arr.enc(link), Arr.FORM), "Added to qBittorrent", null)),
                                    Sheets.Item.of(R.drawable.ic_download, Arr.PROWLARR, "Grab via Prowlarr", "Uses Prowlarr's download client", () -> act(() ->
                                            post(PRO + "search", new JSONObject().put("guid", x.optString("guid")).put("indexerId", x.optInt("indexerId")).toString(), Arr.JSON),
                                            "Sent to the download client", null))));
                }
            }), m -> fail(h, "Prowlarr: " + m));
        }
        header("Indexers", "System", Arr.PROWLARR, v -> { LinearLayout sys = holder(); fill(sys, () -> system(PRO, Arr.PROWLARR, false)); });
        LinearLayout h = holder();
        loading(h, 5);
        bg(() -> new Object[]{arr(PRO + "indexer"), obj(PRO + "indexerstats"), arr(PRO + "indexerstatus")}, r -> fill(h, () -> {
            JSONArray ix = (JSONArray) r[0];
            Map<Integer, JSONObject> stats = new HashMap<>(), status = new HashMap<>();
            JSONArray st = ((JSONObject) r[1]).optJSONArray("indexers");
            long q = 0, g = 0;
            for (int i = 0; st != null && i < st.length(); i++) {
                JSONObject s = st.optJSONObject(i); stats.put(s.optInt("indexerId"), s);
                q += s.optLong("numberOfQueries"); g += s.optLong("numberOfGrabs");
            }
            JSONArray ss = (JSONArray) r[2];
            for (int i = 0; i < ss.length(); i++) status.put(ss.optJSONObject(i).optInt("indexerId"), ss.optJSONObject(i));
            note(ix.length() + " indexers · " + q + " queries · " + g + " grabs", Ui.SUB);
            List<JSONObject> l = new ArrayList<>();
            for (int i = 0; i < ix.length(); i++) l.add(ix.optJSONObject(i));
            l.sort((a, b) -> a.optString("name").compareToIgnoreCase(b.optString("name")));
            for (JSONObject x : l) {
                JSONObject s = stats.get(x.optInt("id")), f = status.get(x.optInt("id"));
                boolean failing = f != null && !f.optString("disabledTill").isEmpty() && Arr.date(f.optString("disabledTill")) != null
                        && Arr.date(f.optString("disabledTill")).after(new Date());
                String sub = x.optString("protocol") + " · " + x.optString("privacy")
                        + (s == null ? "" : " · " + s.optLong("numberOfQueries") + " queries · " + s.optLong("numberOfGrabs") + " grabs · " + s.optLong("averageResponseTime") + " ms");
                boolean on = x.optBoolean("enable");
                row(null, x.optString("name"), sub, !on ? "Disabled" : failing ? "Failing" : "OK",
                        !on ? Ui.SUB : failing ? Ui.RED : Ui.GREEN, -1, Arr.PROWLARR, v -> Sheets.actions(this, x.optString("name"), x.optString("protocol") + " · " + x.optString("privacy"),
                                Sheets.Item.of(R.drawable.ic_shield, Arr.PROWLARR, "Test", "Check the indexer answers", () -> act(() -> {
                                    JSONObject full = obj(PRO + "indexer/" + x.optInt("id"));
                                    return post(PRO + "indexer/test", full.toString(), Arr.JSON);
                                }, x.optString("name") + " works", null)),
                                Sheets.Item.of(on ? R.drawable.ic_pause : R.drawable.ic_play, Arr.PROWLARR, on ? "Disable" : "Enable", null, () -> act(() -> {
                                    JSONObject full = obj(PRO + "indexer/" + x.optInt("id"));
                                    full.put("enable", !on);
                                    return hub.arr("PUT", PRO + "indexer/" + x.optInt("id"), full.toString(), Arr.JSON);
                                }, on ? "Disabled" : "Enabled", () -> show(current)))));
            }
        }), m -> fail(h, "Prowlarr: " + m));
    }

    private LinearLayout searchBox(String hint, Consumer<String> go) {
        LinearLayout box = new LinearLayout(this);
        box.setGravity(Gravity.CENTER_VERTICAL);
        box.setPadding(dp(14), 0, dp(4), 0);
        box.setBackground(Ui.card(this, 2));
        EditText term = new EditText(this);
        term.setHint(hint);
        term.setHintTextColor(Ui.FAINT); term.setTextColor(Ui.TEXT); term.setBackgroundColor(Color.TRANSPARENT);
        term.setSingleLine(true); term.setInputType(InputType.TYPE_CLASS_TEXT); term.setImeOptions(EditorInfo.IME_ACTION_SEARCH);
        Runnable submit = () -> {
            String q = term.getText().toString().trim();
            if (q.isEmpty()) return;
            ((InputMethodManager) getSystemService(INPUT_METHOD_SERVICE)).hideSoftInputFromWindow(term.getWindowToken(), 0);
            go.accept(q);
        };
        term.setOnEditorActionListener((v, a, e) -> { submit.run(); return true; });
        box.addView(term, new LinearLayout.LayoutParams(0, dp(52), 1));
        ImageButton s = Ui.icon(this, R.drawable.ic_search, "Search");
        s.setOnClickListener(v -> submit.run());
        box.addView(s, new LinearLayout.LayoutParams(dp(48), dp(48)));
        LinearLayout.LayoutParams lp = wrap(); lp.bottomMargin = dp(8);
        dest.addView(box, lp);
        return box;
    }

    // ---------------------------------------------------------------- MEDIA SERVERS (Jellyfin, Emby, Plex, Ampache: MediaModules)

    private String mediaImage(String hubPath, int w) { return hubPath == null || hubPath.isEmpty() ? "" : "media:" + hubPath + "&w=" + w; }

    private View mediaPoster(MediaModules.Module m, JSONObject i, int w) {
        int ac = MediaModules.color(m.id);
        String title = i.optString("title"), sub = i.optString("subtitle"), id = i.optString("id");
        boolean played = i.optBoolean("played");
        int unplayed = i.optInt("unplayed");
        View.OnClickListener tap = !m.can("played") ? null : v -> Sheets.actions(this, title, sub,
                Sheets.Item.of(R.drawable.ic_pin, ac, played ? "Mark unwatched" : "Mark watched", null, () -> act(() ->
                        hub.media("POST", m.id + "/played", new JSONObject().put("id", id).put("played", !played)),
                        played ? "Marked unwatched" : "Marked watched", () -> show(current))));
        return Arr.poster(this, hub, w, mediaImage(i.optString("image"), 360), title, sub, unplayed > 0 ? String.valueOf(unplayed) : played ? "✓" : "",
                ac, (float) i.optDouble("progress", -1), ac, tap);
    }

    private void nowPlaying(MediaModules.Module m, JSONArray sessions, boolean compactHeader) {
        int ac = MediaModules.color(m.id);
        boolean any = false;
        for (int i = 0; sessions != null && i < sessions.length(); i++) {
            JSONObject s = sessions.optJSONObject(i);
            if (!any) { header("Now playing" + (MediaModules.servers(mods).size() > 1 ? " · " + m.name : ""), null, 0, null); any = true; }
            boolean pausedNow = s.optBoolean("paused");
            double pos = s.optDouble("position", 0), len = s.optDouble("duration", 0);

            FrameLayout c = new FrameLayout(this);
            c.setBackground(Ui.round(this, Ui.RAISED, 16));
            c.setClipToOutline(true);
            ImageView art = new ImageView(this);
            art.setScaleType(ImageView.ScaleType.CENTER_CROP);
            c.addView(art, new FrameLayout.LayoutParams(-1, -1));
            String bd = s.optString("backdrop");
            Arr.image(this, hub, art, mediaImage(bd.isEmpty() ? s.optString("image") : bd, 540), dp(360));
            View scrim = new View(this);
            scrim.setBackground(new android.graphics.drawable.GradientDrawable(android.graphics.drawable.GradientDrawable.Orientation.TOP_BOTTOM, new int[]{0x22000000, 0xdd000000}));
            c.addView(scrim, new FrameLayout.LayoutParams(-1, -1));
            LinearLayout info = new LinearLayout(this);
            info.setOrientation(LinearLayout.VERTICAL);
            info.setPadding(dp(16), dp(14), dp(16), dp(14));
            info.setGravity(Gravity.BOTTOM);
            StringBuilder who = new StringBuilder(pausedNow ? "❚❚ " : "▶ ");
            for (String k : new String[]{"user", "client", "device"}) if (!s.optString(k).isEmpty()) who.append(who.length() > 2 ? " · " : "").append(s.optString(k));
            info.addView(Arr.solidTag(this, who.toString(), ac));
            TextView t = Ui.text(this, s.optString("title"), 18, Color.WHITE, true);
            t.setPadding(0, dp(8), 0, 0);
            info.addView(t);
            if (!s.optString("subtitle").isEmpty()) info.addView(Ui.text(this, s.optString("subtitle"), 13, 0xccffffff, false));
            if (len > 0) {
                View b = Arr.bar(this, (float) (pos / len), ac, 4);
                LinearLayout.LayoutParams bl = new LinearLayout.LayoutParams(-1, dp(4)); bl.topMargin = dp(10);
                info.addView(b, bl);
            }
            if (m.can("control")) {
                LinearLayout ctl = new LinearLayout(this);
                ctl.setPadding(0, dp(6), 0, 0);
                String sid = s.optString("id");
                if (!"plex".equals(m.id)) {  // Plex can stop a stream from the server, not pause a remote player
                    TextView pp = Ui.ghostButton(this, pausedNow ? "Resume" : "Pause", Color.WHITE);
                    pp.setOnClickListener(v -> act(() -> hub.media("POST", m.id + "/control", new JSONObject().put("session", sid).put("cmd", "playpause")), "Sent", () -> show(current)));
                    ctl.addView(pp);
                }
                TextView stop = Ui.ghostButton(this, "Stop", 0xffff8080);
                stop.setOnClickListener(v -> act(() -> hub.media("POST", m.id + "/control", new JSONObject().put("session", sid).put("cmd", "stop")), "Stopped", () -> show(current)));
                ctl.addView(stop);
                info.addView(ctl);
            }
            c.addView(info, new FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, dp(220)); lp.bottomMargin = dp(8);
            dest.addView(c, lp);
        }
        if (!any && !compactHeader) note("Nothing is playing right now.", Ui.SUB);
    }

    /** One media server: what is playing, library counts, continue watching and the latest additions, as far as it supports them. */
    private void server() {
        MediaModules.Module m = MediaModules.find(mods, server);
        if (m == null) { note("This media server is no longer set up on the hub.", Ui.SUB); return; }
        int ac = MediaModules.color(m.id);
        if (m.can("refresh")) {
            LinearLayout tools = Arr.chipRow(this, dest);
            Arr.addChip(this, tools, Arr.chip(this, "Scan libraries", false, ac, v ->
                    act(() -> hub.media("POST", m.id + "/refresh", new JSONObject()), "Library scan started", null)));
        }
        if (m.can("sessions")) {
            LinearLayout playing = holder();
            loading(playing, 1);
            bg(() -> hub.media("GET", m.id + "/sessions", null), r -> fill(playing, () -> nowPlaying(m, r.optJSONArray("sessions"), false)), e -> fail(playing, m.name + ": " + e));
        }
        if (m.can("counts")) {
            LinearLayout counts = holder();
            bg(() -> hub.media("GET", m.id + "/counts", null), r -> fill(counts, () -> {
                JSONArray cs = r.optJSONArray("counts");
                if (cs == null || cs.length() == 0) return;
                LinearLayout row = new LinearLayout(this);
                row.setBaselineAligned(false);
                for (int i = 0; i < Math.min(4, cs.length()); i++) {
                    JSONObject it = cs.optJSONObject(i);
                    LinearLayout t = new LinearLayout(this);
                    t.setOrientation(LinearLayout.VERTICAL);
                    t.setPadding(dp(12), dp(10), dp(12), dp(10));
                    t.setBackground(Ui.card(this, 1));
                    TextView n = Ui.num(this, String.valueOf(it.optLong("n")), 18, Ui.TEXT), l = Ui.caption(this, it.optString("label"), Ui.SUB);
                    n.setSingleLine(true); l.setSingleLine(true); l.setEllipsize(TextUtils.TruncateAt.END);
                    t.addView(n); t.addView(l);
                    LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, -2, 1); lp.setMarginEnd(dp(6));
                    row.addView(t, lp);
                }
                LinearLayout.LayoutParams lp = wrap(); lp.topMargin = dp(8);
                dest.addView(row, lp);
            }), null);
        }
        if (m.can("resume")) {
            header("Continue watching", null, 0, null);
            LinearLayout resume = holder();
            loadingPosters(resume);
            bg(() -> hub.media("GET", m.id + "/resume?limit=16", null), r -> fill(resume, () -> {
                JSONArray items = r.optJSONArray("items");
                if (items == null || items.length() == 0) { note("Nothing in progress.", Ui.SUB); return; }
                LinearLayout row = Arr.carousel(this, dest);
                for (int i = 0; i < items.length(); i++) Arr.addToCarousel(this, row, mediaPoster(m, items.optJSONObject(i), 112));
            }), e -> fail(resume, m.name + ": " + e));
        }
        if (m.can("latest")) {
            header("Latest", null, 0, null);
            LinearLayout latest = holder();
            loading(latest, 3);
            bg(() -> hub.media("GET", m.id + "/latest?limit=30", null), r -> fill(latest, () -> {
                JSONArray items = r.optJSONArray("items");
                if (items == null || items.length() == 0) { note("Nothing yet.", Ui.SUB); return; }
                int w = Arr.gridWidthDp(this, 3);
                List<View> cards = new ArrayList<>();
                for (int i = 0; i < items.length(); i++) cards.add(mediaPoster(m, items.optJSONObject(i), w));
                Arr.grid(this, dest, cards, 3);
            }), e -> fail(latest, m.name + ": " + e));
        }
        phoneApps();
    }

    /** Player apps installed on this phone (Ampchor has no server API, so it is a launcher module; the others open their own app). */
    private static final String[][] PLAYER_APPS = {
            {"Ampchor", ""}, {"Jellyfin", "org.jellyfin.mobile"}, {"Findroid", "dev.jdtech.jellyfin"}, {"Emby", "com.mb.android"},
            {"Plex", "com.plexapp.android"}, {"Plexamp", "tv.plex.labs.plexamp"}, {"Finamp", "com.unicornsonlsd.finamp"}};

    private void phoneApps() {
        android.content.pm.PackageManager pm = getPackageManager();
        List<Object[]> found = new ArrayList<>();
        Intent launcher = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER);
        List<android.content.pm.ResolveInfo> all = pm.queryIntentActivities(launcher, 0);
        for (String[] app : PLAYER_APPS) {
            for (android.content.pm.ResolveInfo ri : all) {
                String pkg = ri.activityInfo.packageName, label = String.valueOf(ri.loadLabel(pm));
                if (app[1].isEmpty() ? label.toLowerCase(Locale.ROOT).contains(app[0].toLowerCase(Locale.ROOT)) : pkg.equals(app[1])) {
                    found.add(new Object[]{label, pkg}); break;
                }
            }
        }
        if (found.isEmpty()) return;
        header("Open in", null, 0, null);
        LinearLayout tools = Arr.chipRow(this, dest);
        for (Object[] f : found) Arr.addChip(this, tools, Arr.chip(this, (String) f[0], false, Ui.BLUE, v -> {
            Intent i = pm.getLaunchIntentForPackage((String) f[1]);
            if (i != null) startActivity(i);
        }));
    }

    // ---------------------------------------------------------------- SEARCH

    private void search() {
        LinearLayout box = searchBox("Find a series or movie", q -> { query = q; show(Page.SEARCH); });
        EditText term = (EditText) box.getChildAt(0);
        if (query.isEmpty()) {
            term.requestFocus();
            term.postDelayed(() -> ((InputMethodManager) getSystemService(INPUT_METHOD_SERVICE)).showSoftInput(term, 0), 150);
            note(has("sonarr") || has("radarr") ? "Search Sonarr and Radarr to add something new, or your indexers for a specific release."
                    : has("prowlarr") ? "Search your indexers for a release." : "Search needs Sonarr, Radarr or Prowlarr on the hub.", Ui.SUB);
            return;
        }
        term.setText(query);
        for (boolean movie : new boolean[]{false, true}) {
            if (!has(movie ? "radarr" : "sonarr")) continue;
            int ac = movie ? Arr.RADARR : Arr.SONARR;
            header(movie ? "Movies" : "Series", null, 0, null);
            LinearLayout h = holder();
            loadingPosters(h);
            bg(() -> arr((movie ? RAD + "movie/lookup?term=" : SON + "series/lookup?term=") + Arr.enc(query)), r -> fill(h, () -> {
                if (r.length() == 0) { note("No results.", Ui.SUB); return; }
                LinearLayout row = Arr.carousel(this, dest);
                for (int i = 0; i < Math.min(r.length(), 20); i++) {
                    JSONObject o = r.optJSONObject(i);
                    boolean have = o.optInt("id") > 0;
                    String svc = movie ? "radarr" : "sonarr";
                    Arr.addToCarousel(this, row, Arr.poster(this, hub, 120, Arr.art(svc, o, "poster"), o.optString("title"),
                            (o.optInt("year") > 0 ? o.optInt("year") + " · " : "") + (movie ? o.optString("studio") : o.optString("network")),
                            have ? "✓ In library" : rating(o), have ? Ui.GREEN : ac, -1, ac,
                            v -> { if (have) openDetail(svc, o.optInt("id")); else ArrAdd.add(this, hub, o, movie, () -> { seriesCache = null; moviesCache = null; }); }));
                }
            }), m -> fail(h, (movie ? "Radarr: " : "Sonarr: ") + m));
        }
        if (!has("prowlarr")) return;
        TextView ix = Ui.ghostButton(this, "Search indexers for “" + query + "”  ›", Arr.PROWLARR);
        ix.setOnClickListener(v -> show(Page.INDEXERS));
        LinearLayout.LayoutParams lp = wrap(); lp.topMargin = dp(12);
        dest.addView(ix, lp);
    }
}
