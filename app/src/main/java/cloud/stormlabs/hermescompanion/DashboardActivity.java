package cloud.stormlabs.hermescompanion;

import android.app.Activity;
import android.content.Intent;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.view.inputmethod.EditorInfo;
import android.widget.EditText;
import android.widget.HorizontalScrollView;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Dashboard layout's home: hero status strip, bento (Homelab, Agents, Continue), fixes, activity, captures. */
public final class DashboardActivity extends Activity {
    private final ExecutorService network = Executors.newCachedThreadPool();
    private SettingsStore store;
    private AgentBackend api;
    private RunManager runs;
    private boolean hubOn;
    private EditText ask;
    private LinearLayout body, capturesBody, feedBody, fixBody, needsChips;
    private View needsRow, fixCard;
    private int loadGeneration, liveGeneration, failStreak;
    private final Handler tickHandler = new Handler(Looper.getMainLooper());
    private final Runnable tick = () -> { refreshLive(); tickHandler.postDelayed(this.tick, 30_000); };
    // Hero
    private Gauge heroGauge;
    private TextView heroWord, heroSentence, heroLive;
    private View heroDot;
    private Ui.Heartbeat heartbeat;
    private List<SystemHealth.Reason> reasons = new ArrayList<>();
    private boolean healthKnown;
    // Bento
    private View labTile;
    private DigitRoll guestRoll, failedRoll;
    private TextView labSub, labStats, agentsSub, continueTitle, continueSub;
    private Sparkline spark;
    private LabSummary lab;
    private final List<HubApi.Event> feed = new ArrayList<>(); // newest first, <= 200
    private long feedLast;

    private int dp(int n) { return Ui.dp(this, n); }
    private LinearLayout.LayoutParams match() { return new LinearLayout.LayoutParams(-1, -2); }

    @Override public void onCreate(Bundle b) {
        super.onCreate(b);
        CrashReporter.install(this);
        Ui.legacyWindowInsets(this);
        store = new SettingsStore(this);
        if (store.apiKey().isEmpty()) { startActivity(new Intent(this, ConnectionActivity.class)); finish(); return; }
        api = Backends.create(store);
        runs = RunManager.get(this);
        hubOn = api.capabilitiesForUi().hub && !store.hubUrl().isEmpty();
        getWindow().setStatusBarColor(Ui.BG); getWindow().setNavigationBarColor(Ui.BG);
        LinearLayout root = new LinearLayout(this); root.setOrientation(LinearLayout.VERTICAL); Ui.ambient(root);
        ScrollView scroll = new ScrollView(this); scroll.setFillViewport(true);
        body = new LinearLayout(this); body.setOrientation(LinearLayout.VERTICAL);
        body.setPadding(Ui.space(this, 12), Ui.space(this, 12), Ui.space(this, 12), dp(28));
        scroll.addView(body);
        root.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));

        body.addView(hero(), gap(10));
        needsRow = needsRow();
        body.addView(needsRow, gap(10));
        bento();
        fixBody = card(body, "NEEDS A FIX", null, null);
        fixCard = (View) fixBody.getParent();
        fixCard.setVisibility(View.GONE);
        if (hubOn) feedBody = card(body, "ACTIVITY", "See all", this::showFeed);
        if (store.essentialsReady()) capturesBody = card(body, "RECENT CAPTURES", "Open", this::openCapture);
        Nav.tabBar(this, root, Layout.Tab.HOME);
        setContentView(root);
        Ui.fitInsets(root, root);
        Ui.stagger(body);
        LabSummary c = LabSummary.cached(this);
        if (hubOn && c != null) renderLab(c);
    }

    @Override protected void onResume() { super.onResume(); Updater.check(this, false); if (api != null) { load(); tickHandler.postDelayed(tick, 30_000); } }
    @Override protected void onPause() { super.onPause(); tickHandler.removeCallbacks(tick); }
    @Override protected void onDestroy() { super.onDestroy(); network.shutdownNow(); }
    @Override public void onBackPressed() { if (Nav.leaveFromTab(this, Layout.Tab.HOME)) return; if (isTaskRoot()) moveTaskToBack(true); else super.onBackPressed(); }

    private LinearLayout.LayoutParams gap(int bottomDp) { LinearLayout.LayoutParams p = match(); p.setMargins(0, 0, 0, dp(bottomDp)); return p; }

    // ---------------------------------------------------------------- hero

    private View hero() {
        LinearLayout box = new LinearLayout(this); box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(Ui.space(this, 16), Ui.space(this, 10), Ui.space(this, 12), Ui.space(this, 14));
        box.setBackground(Ui.card(this, 2)); Ui.elevate(box, 2);

        LinearLayout top = new LinearLayout(this); top.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout names = new LinearLayout(this); names.setOrientation(LinearLayout.VERTICAL); names.setMinimumHeight(dp(48)); names.setGravity(Gravity.CENTER_VERTICAL);
        names.addView(Ui.caption(this, DashboardText.greeting(Calendar.getInstance().get(Calendar.HOUR_OF_DAY)) + " · "
                + new java.text.SimpleDateFormat("EEEE, d MMMM", java.util.Locale.getDefault()).format(new java.util.Date()), Ui.SUB));
        TextView who = Ui.caption(this, store.profileName() + " · " + Profiles.where(store.endpoint()), Ui.FAINT);
        who.setSingleLine(true); who.setEllipsize(TextUtils.TruncateAt.END); names.addView(who);
        names.setContentDescription("Profile " + store.profileName() + ", tap to switch");
        names.setOnClickListener(v -> Profiles.show(this));
        top.addView(names, new LinearLayout.LayoutParams(0, -2, 1));
        ImageButton settings = Ui.icon(this, R.drawable.ic_settings, "Settings");
        settings.setOnClickListener(v -> startActivity(new Intent(this, SettingsActivity.class)));
        top.addView(settings, new LinearLayout.LayoutParams(dp(48), dp(48)));
        box.addView(top, match());

        // Status strip: worst metric as a gauge, state word, one sentence, heartbeat.
        LinearLayout strip = new LinearLayout(this); strip.setGravity(Gravity.CENTER_VERTICAL); strip.setPadding(0, dp(4), Ui.space(this, 4), dp(4));
        strip.setMinimumHeight(dp(64));
        heroGauge = new Gauge(this); heroGauge.setVisibility(View.GONE);
        LinearLayout.LayoutParams gp = new LinearLayout.LayoutParams(dp(108), dp(108)); gp.setMargins(0, 0, dp(12), 0);
        strip.addView(heroGauge, gp);
        LinearLayout col = new LinearLayout(this); col.setOrientation(LinearLayout.VERTICAL);
        heroWord = Ui.display(this, "Checking", Ui.SUB); heroWord.setTextSize(28); heroWord.setSingleLine(true);
        col.addView(heroWord, match());
        heroSentence = Ui.body(this, "Checking systems…", Ui.TEXT); heroSentence.setMaxLines(3); heroSentence.setEllipsize(TextUtils.TruncateAt.END);
        heroSentence.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
        col.addView(heroSentence, match());
        LinearLayout live = new LinearLayout(this); live.setGravity(Gravity.CENTER_VERTICAL); live.setPadding(0, dp(6), 0, 0);
        heroDot = new View(this); heroDot.setBackground(Ui.circle(Ui.GREEN)); heroDot.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        live.addView(heroDot, new LinearLayout.LayoutParams(dp(8), dp(8)));
        heartbeat = Ui.heartbeat(heroDot);
        heroLive = Ui.caption(this, "Connecting", Ui.SUB); heroLive.setPadding(dp(8), 0, 0, 0);
        live.addView(heroLive);
        col.addView(live, match());
        strip.addView(col, new LinearLayout.LayoutParams(0, -2, 1));
        strip.setBackground(Ui.ripple(this, Color.TRANSPARENT, 12));
        strip.setOnClickListener(v -> startActivity(new Intent(this, StatusActivity.class)));
        Ui.press(strip);
        box.addView(strip, match());

        LinearLayout row = new LinearLayout(this); row.setGravity(Gravity.CENTER_VERTICAL); row.setPadding(0, dp(8), 0, 0);
        ask = new EditText(this); ask.setHint("Ask Hermes…"); ask.setTextColor(Ui.TEXT); ask.setHintTextColor(Ui.FAINT);
        ask.setTextSize(16); ask.setMinHeight(dp(48)); ask.setMaxLines(4); ask.setPadding(dp(14), dp(8), dp(14), dp(8));
        ask.setBackground(Ui.stroke(this, Ui.tier(1), Ui.LINE, 24));
        ask.setInputType(android.text.InputType.TYPE_CLASS_TEXT | android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE | android.text.InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
        ask.setImeOptions(EditorInfo.IME_ACTION_SEND);
        ask.setOnEditorActionListener((v, a, e) -> { if (a == EditorInfo.IME_ACTION_SEND) { sendAsk(); return true; } return false; });
        row.addView(ask, new LinearLayout.LayoutParams(0, -2, 1));
        ImageButton send = Ui.icon(this, R.drawable.ic_send, "Send in a new conversation");
        send.setImageTintList(ColorStateList.valueOf(Color.WHITE)); send.setBackground(Ui.circle(Ui.BLUE));
        send.setOnClickListener(v -> sendAsk());
        LinearLayout.LayoutParams sp = new LinearLayout.LayoutParams(dp(48), dp(48)); sp.setMargins(dp(8), 0, 0, 0);
        row.addView(send, sp);
        box.addView(row, match());
        return box;
    }

    /** Recomputes word, sentence and colour from the one severity model (health + host + Proxmox reasons, plus the homelab summary). */
    private void updateHero() {
        int overall = SystemHealth.overall(reasons);
        int sev = Math.max(OpsText.severity(overall), lab == null ? OpsText.CALM : lab.level()); // LabSummary levels are 0/1/2 like OpsText
        boolean known = healthKnown || lab != null;
        int color = !known ? Ui.SUB : sev == OpsText.CALM ? Ui.GREEN : sev == OpsText.WATCH ? Ui.AMBER : Ui.RED;
        String word = OpsText.word(sev, !known);
        String sentence;
        if (!reasons.isEmpty()) sentence = OpsText.sentence(reasons, false, "");
        else if (lab != null && lab.problems > 0) sentence = lab.problem + (lab.problems > 1 ? " · " + (lab.problems - 1) + " more" : "");
        else sentence = DashboardText.status(known, 0, lab == null ? -1 : lab.guestsUp, lab == null ? "" : lab.node, lab == null ? Double.NaN : lab.cpu);
        heroWord.setText(word); heroWord.setTextColor(color);
        heroWord.setContentDescription("System " + word.toLowerCase(java.util.Locale.ROOT));
        if (!sentence.contentEquals(heroSentence.getText())) heroSentence.setText(sentence);
        boolean stalled = failStreak >= 2;
        heroLive.setText(stalled ? "Reconnecting" : known ? "Live" : "Connecting");
        heroLive.setTextColor(stalled ? Ui.AMBER : Ui.SUB);
    }

    private LinearLayout needsRow() {
        LinearLayout box = new LinearLayout(this); box.setOrientation(LinearLayout.VERTICAL); box.setVisibility(View.GONE);
        TextView l = Ui.label(this, "Needs you", Ui.SUB); l.setPadding(dp(6), 0, 0, dp(4)); box.addView(l);
        HorizontalScrollView hs = new HorizontalScrollView(this); hs.setHorizontalScrollBarEnabled(false);
        needsChips = new LinearLayout(this);
        hs.addView(needsChips);
        box.addView(hs, match());
        return box;
    }

    // ---------------------------------------------------------------- bento

    private LinearLayout tile(String label) {
        LinearLayout t = new LinearLayout(this); t.setOrientation(LinearLayout.VERTICAL);
        t.setPadding(Ui.space(this, 16), Ui.space(this, 14), Ui.space(this, 16), Ui.space(this, 14));
        t.setMinimumHeight(dp(112));
        t.setBackground(Ui.card(this, 1)); Ui.elevate(t, 1);
        t.setForeground(Ui.ripple(this, Color.TRANSPARENT, 14));
        t.addView(Ui.label(this, label, Ui.SUB), match());
        Ui.press(t);
        return t;
    }

    /** Live notes: one tap to start transcribing a lecture or meeting with live insights. */
    private void liveNotesTile() {
        LinearLayout t = tile("Live notes");
        TextView line = Ui.caption(this, "Transcript, insight cards and a running summary of a lecture or meeting", Ui.SUB);
        line.setPadding(0, dp(6), 0, 0);
        t.addView(line, match());
        t.setOnClickListener(v -> startActivity(new Intent(this, LiveNotesActivity.class)));
        t.setContentDescription("Live notes. Open lecture and meeting transcription");
        body.addView(t, gap(10));
    }

    /** Media: live download speed and the next few episodes/movies as posters; tap for the Media screen. */
    private void mediaTile() {
        LinearLayout t = tile("Media");
        TextView speed = Ui.num(this, "↓ …", 22, Ui.TEXT);
        speed.setPadding(0, dp(6), 0, 0);
        t.addView(speed, match());
        TextView line = Ui.caption(this, "TV, movies, downloads and media servers", Ui.SUB);
        t.addView(line, match());
        LinearLayout posters = new LinearLayout(this);
        posters.setPadding(0, dp(12), 0, 0);
        t.addView(posters, match());
        t.setOnClickListener(v -> startActivity(new Intent(this, MediaActivity.class)));
        t.setContentDescription("Media. Open TV, movies and downloads");
        body.addView(t, gap(10));
        HubApi h = new HubApi(store.hubUrl(), store.hubKey());
        long now = System.currentTimeMillis();
        network.execute(() -> {
            try {
                boolean qb = MediaModules.has(this, "qbit"), son = MediaModules.has(this, "sonarr");
                org.json.JSONObject ti = qb ? (org.json.JSONObject) h.arr(Arr.QB + "transfer/info") : null;
                org.json.JSONArray dl = qb ? (org.json.JSONArray) h.arr(Arr.QB + "torrents/info?filter=downloading") : new org.json.JSONArray();
                org.json.JSONArray cal = son ? (org.json.JSONArray) h.arr(Arr.SON + "calendar?includeSeries=true&start=" + Arr.ymd(now) + "&end=" + Arr.ymd(now + 7 * 86400000L)) : new org.json.JSONArray();
                runOnUiThread(() -> {
                    if (isDestroyed()) return;
                    speed.setText(ti == null ? "Media" : "↓ " + Arr.speed(ti.optLong("dl_info_speed")) + "   ↑ " + Arr.speed(ti.optLong("up_info_speed")));
                    String what = (qb ? dl.length() + " downloading" : "") + (cal.length() > 0 ? (qb ? " · " : "") + cal.length() + " episodes this week" : "");
                    if (!what.isEmpty()) line.setText(what);
                    int w = (int) ((getResources().getDisplayMetrics().widthPixels / getResources().getDisplayMetrics().density - 32 - 32 - 30) / 4);
                    for (int i = 0; i < Math.min(4, cal.length()); i++) {
                        org.json.JSONObject e = cal.optJSONObject(i), s = e.optJSONObject("series");
                        if (s == null) continue;
                        java.util.Date d = Arr.date(e.optString("airDateUtc"));
                        View p = Arr.poster(this, h, w, Arr.art("sonarr", s, "poster"), null, null, Arr.when(d), e.optBoolean("hasFile") ? Ui.GREEN : Arr.SONARR, -1, Arr.SONARR, null);
                        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, -2); lp.setMarginEnd(dp(10));
                        posters.addView(p, lp);
                    }
                });
            } catch (Exception e) {
                runOnUiThread(() -> { if (!isDestroyed()) { speed.setText("Media"); line.setText("Not reachable: " + e.getMessage()); } });
            }
        });
    }

    private void bento() {
        if (hubOn) {
            LinearLayout t = tile("Homelab");
            labTile = t;
            LinearLayout line = new LinearLayout(this); line.setGravity(Gravity.BOTTOM); line.setPadding(0, dp(6), 0, 0);
            guestRoll = new DigitRoll(this); guestRoll.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
            line.addView(guestRoll, new LinearLayout.LayoutParams(-2, -2));
            labSub = Ui.caption(this, "guests up", Ui.SUB); labSub.setPadding(dp(8), 0, 0, dp(4));
            line.addView(labSub, new LinearLayout.LayoutParams(0, -2, 1));
            t.addView(line, match());
            labStats = Ui.num(this, "", 12, Ui.SUB); labStats.setSingleLine(true); labStats.setEllipsize(TextUtils.TruncateAt.END);
            t.addView(labStats, match());
            spark = new Sparkline(this, Ui.BLUE, 100, false);
            LinearLayout.LayoutParams sp = new LinearLayout.LayoutParams(-1, dp(48)); sp.topMargin = dp(10);
            t.addView(spark, sp);
            t.setOnClickListener(v -> startActivity(new Intent(this, StatusActivity.class)));
            t.setContentDescription("Homelab, loading. Open System");
            body.addView(t, gap(10));
        }
        if (hubOn && MediaModules.any(this)) mediaTile();
        liveNotesTile();
        LinearLayout pair = new LinearLayout(this); pair.setBaselineAligned(false);
        if (api.capabilitiesForUi().jobs) {
            LinearLayout t = tile("Agents");
            failedRoll = new DigitRoll(this); failedRoll.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
            LinearLayout.LayoutParams rp = new LinearLayout.LayoutParams(-2, -2); rp.topMargin = dp(6);
            t.addView(failedRoll, rp);
            agentsSub = Ui.caption(this, "Loading", Ui.SUB); t.addView(agentsSub, match());
            t.setOnClickListener(v -> startActivity(new Intent(this, JobsActivity.class)));
            t.setContentDescription("Agents, loading. Open Agents");
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, -1, 1); lp.setMargins(0, 0, dp(5), 0);
            pair.addView(t, lp);
        }
        LinearLayout c = tile("Continue");
        continueTitle = Ui.body(this, "Loading", Ui.TEXT); continueTitle.setMaxLines(2); continueTitle.setEllipsize(TextUtils.TruncateAt.END);
        continueTitle.setPadding(0, dp(6), 0, 0);
        c.addView(continueTitle, match());
        continueSub = Ui.caption(this, "", Ui.SUB); continueSub.setSingleLine(true); continueSub.setEllipsize(TextUtils.TruncateAt.END);
        c.addView(continueSub, match());
        LinearLayout.LayoutParams cp = new LinearLayout.LayoutParams(0, -1, 1);
        if (pair.getChildCount() > 0) cp.setMargins(dp(5), 0, 0, 0);
        pair.addView(c, cp);
        c.setOnClickListener(v -> openChatLane(MainActivity.EXTRA_OPEN_CHAT));
        body.addView(pair, gap(10));
    }

    /** A titled card; returns its body. With an action, the title row becomes a 48dp target labelled with the action. */
    private LinearLayout card(LinearLayout parent, String title, String actionLabel, Runnable action) {
        LinearLayout box = new LinearLayout(this); box.setOrientation(LinearLayout.VERTICAL);
        box.setBackground(Ui.card(this, 1)); Ui.elevate(box, 1);
        box.setPadding(0, 0, 0, Ui.space(this, 8));
        LinearLayout head = new LinearLayout(this); head.setGravity(Gravity.CENTER_VERTICAL);
        head.setPadding(Ui.space(this, 16), 0, Ui.space(this, 16), 0); head.setMinimumHeight(dp(44));
        head.addView(Ui.label(this, title, Ui.SUB), new LinearLayout.LayoutParams(0, -2, 1));
        if (action != null) {
            head.addView(Ui.caption(this, actionLabel, Ui.BLUE));
            head.setMinimumHeight(dp(48)); head.setBackground(Ui.ripple(this, Color.TRANSPARENT, 12));
            head.setContentDescription(actionLabel + " " + title.toLowerCase(java.util.Locale.ROOT)); head.setOnClickListener(v -> action.run());
        }
        box.addView(head, match());
        LinearLayout inner = new LinearLayout(this); inner.setOrientation(LinearLayout.VERTICAL);
        box.addView(inner, match());
        inner.addView(Ui.skeletonRows(this, 1), match());
        parent.addView(box, gap(10));
        return inner;
    }

    private void note(LinearLayout parent, String text) {
        TextView t = Ui.caption(this, text, Ui.SUB); t.setPadding(Ui.space(this, 16), dp(4), Ui.space(this, 16), dp(8));
        parent.addView(t, match());
    }

    /** A tappable two-line row: avatar colour, title, detail, optional right text. */
    private void row(LinearLayout parent, int color, String title, String detail, String right, Runnable action) {
        LinearLayout row = new LinearLayout(this); row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(Ui.space(this, 16), dp(6), Ui.space(this, 14), dp(6)); row.setMinimumHeight(dp(56));
        if (action != null) { row.setBackground(Ui.ripple(this, Color.TRANSPARENT, 0)); row.setOnClickListener(v -> action.run()); Ui.press(row); }
        TextView av = Ui.avatar(this, title, color, 34); av.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        row.addView(av, new LinearLayout.LayoutParams(dp(34), dp(34)));
        LinearLayout copy = new LinearLayout(this); copy.setOrientation(LinearLayout.VERTICAL); copy.setPadding(dp(12), 0, dp(6), 0);
        TextView t = Ui.body(this, title, Ui.TEXT); t.setSingleLine(true); t.setEllipsize(TextUtils.TruncateAt.END); copy.addView(t);
        if (detail != null && !detail.isEmpty()) { TextView d = Ui.caption(this, detail, Ui.SUB); d.setMaxLines(2); d.setEllipsize(TextUtils.TruncateAt.END); copy.addView(d); }
        row.addView(copy, new LinearLayout.LayoutParams(0, -2, 1));
        if (right != null && !right.isEmpty()) row.addView(Ui.caption(this, right, Ui.FAINT));
        row.setContentDescription(title + (detail == null || detail.isEmpty() ? "" : ", " + detail));
        parent.addView(row, match());
    }

    // ---------------------------------------------------------------- actions

    private void openChatLane(String flag) {
        startActivity(new Intent(this, MainActivity.class).putExtra(MainActivity.EXTRA_OPEN_CHAT, true).putExtra(flag, true));
    }

    private void openCapture() {
        if (!store.essentialsReady()) { Toast.makeText(this, "Set the capture URL and token in Settings first", Toast.LENGTH_SHORT).show(); return; }
        startActivity(new Intent(this, EssentialsActivity.class).putExtra(EssentialsActivity.EXTRA_BASE, store.essentialsBase()).putExtra(EssentialsActivity.EXTRA_TOKEN, store.essentialsToken()));
    }

    private void openChat(String id, String title, String model) {
        store.sessionId(id);
        startActivity(Nav.chat(this, id, title, model));
    }

    /** "Ask Hermes…" starts a new conversation and sends the text there. */
    private void sendAsk() {
        String text = ask.getText().toString().trim();
        if (text.isEmpty()) return;
        ask.setText("");
        network.submit(() -> {
            try {
                HermesApi.SessionInfo s = api.createSession("New conversation");
                runOnUiThread(() -> {
                    store.sessionId(s.id);
                    startActivity(Nav.chat(this, s.id, s.displayTitle(), s.model).putExtra(ChatActivity.EXTRA_PROMPT, text)
                            .putExtra(ChatActivity.EXTRA_AUTOSEND, true).putExtra(MainActivity.EXTRA_PROFILE_SECRET, store.intentSecret()));
                });
            } catch (Exception e) {
                runOnUiThread(() -> { if (ask.length() == 0) ask.setText(text); Toast.makeText(this, HermesApi.friendly(e), Toast.LENGTH_SHORT).show(); });
            }
        });
    }

    /** Opens a new conversation that asks Hermes about something on this screen (same pattern as the System screen). */
    private void askHermes(String title, String prompt) {
        Toast.makeText(this, "Asking Hermes…", Toast.LENGTH_SHORT).show();
        network.submit(() -> {
            try {
                HermesApi.SessionInfo s = Backends.create(store).createSession(title);
                runOnUiThread(() -> startActivity(new Intent(this, MainActivity.class)
                        .putExtra(MainActivity.EXTRA_SESSION, s.id).putExtra(ChatActivity.EXTRA_TITLE, title)
                        .putExtra(ChatActivity.EXTRA_PROMPT, prompt).putExtra(ChatActivity.EXTRA_AUTOSEND, true)
                        .putExtra(MainActivity.EXTRA_PROFILE_SECRET, store.intentSecret())));
            } catch (Exception e) { runOnUiThread(() -> Toast.makeText(this, "Couldn't start a chat: " + HermesApi.friendly(e), Toast.LENGTH_SHORT).show()); }
        });
    }

    // ---------------------------------------------------------------- data

    private void load() {
        int gen = ++loadGeneration;
        String profile = store.profileId();
        refreshLive();
        network.submit(() -> {
            try {
                List<HermesApi.SessionInfo> all = api.listSessions(150, 0);
                runOnUiThread(() -> { if (gen == loadGeneration) renderSessions(all, profile); });
            } catch (Exception e) {
                runOnUiThread(() -> { if (gen != loadGeneration) return; continueTitle.setText("Couldn't load"); continueSub.setText(HermesApi.friendly(e)); needsRow.setVisibility(View.GONE); });
            }
        });
        if (capturesBody != null) network.submit(() -> {
            try {
                List<EssentialsApi.Item> items = new EssentialsApi(store.essentialsBase(), store.essentialsToken()).items(3);
                runOnUiThread(() -> { if (gen == loadGeneration) renderCaptures(items); });
            } catch (Exception e) {
                runOnUiThread(() -> { if (gen != loadGeneration) return; capturesBody.removeAllViews(); note(capturesBody, "Could not load captures · " + HermesApi.friendly(e)); });
            }
        });
        if (failedRoll != null) network.submit(() -> {
            try {
                List<HermesApi.Job> jobs = api.jobs();
                runOnUiThread(() -> { if (gen == loadGeneration) renderAgents(jobs); });
            } catch (Exception e) {
                runOnUiThread(() -> { if (gen != loadGeneration) return; agentsSub.setText("Couldn't load"); });
            }
        });
    }

    /** The live parts (hero, homelab tile, activity); also run every 30 s while visible. */
    private void refreshLive() {
        int gen = ++liveGeneration;
        network.submit(() -> {
            JSONObject health = null; String healthError = null; HubApi.Services ops = null; String opsError = null; HubApi.Infra infra = null;
            try { health = api.healthDetailed(); } catch (Exception e) { healthError = HermesApi.friendly(e); }
            if (hubOn) {
                HubApi hub = new HubApi(store.hubUrl(), store.hubKey());
                try { ops = hub.opsServices(); } catch (Exception e) { opsError = HermesApi.friendly(e); }
                try { infra = hub.opsInfra(); } catch (Exception ignored) { }
            }
            List<SystemHealth.Reason> rs = SystemHealth.reasons(health, healthError, ops, opsError, store.dailyTokenBudget());
            rs.addAll(SystemHealth.pveReasons(infra == null ? null : infra.pveHost));
            rs.sort((x, y) -> y.level - x.level);
            boolean ok = healthError == null;
            runOnUiThread(() -> {
                if (gen != liveGeneration) return;
                reasons = rs; healthKnown = true;
                if (ok) { failStreak = 0; heartbeat.stalled(false); } else if (++failStreak >= 2) heartbeat.stalled(true);
                updateHero();
                if (ok) heartbeat.beat();
            });
        });
        if (feedBody != null) network.submit(() -> {
            try {
                HubApi hub = new HubApi(store.hubUrl(), store.hubKey());
                List<HubApi.Event> got = feedLast == 0 ? hub.latestEvents(60) : hub.events(feedLast, 100);
                runOnUiThread(() -> { if (gen == liveGeneration) mergeFeed(got); });
            } catch (Exception e) {
                runOnUiThread(() -> {
                    if (gen != liveGeneration) return;
                    if (String.valueOf(e.getMessage()).contains("404")) ((View) feedBody.getParent()).setVisibility(View.GONE);
                    else if (feed.isEmpty()) { feedBody.removeAllViews(); note(feedBody, "Could not load activity · " + HermesApi.friendly(e)); }
                });
            }
        });
        if (labTile == null) return;
        network.submit(() -> {
            if (!LabSummary.refresh(this)) { runOnUiThread(() -> { if (gen == liveGeneration && lab == null) labSub.setText("summary unavailable"); }); return; }
            LabSummary s = LabSummary.cached(this);
            double[][] pts = null;
            try { pts = new HubApi(store.hubUrl(), store.hubKey()).history("pve.cpu", "24h"); } catch (Exception ignored) { }
            double[][] h = pts;
            runOnUiThread(() -> { if (gen == liveGeneration && s != null) { renderLab(s); if (h != null && h.length > 1) spark.setData(h, 86400, "CPU last 24 hours, " + History.summary(h, "%")); } });
        });
    }

    /** Updates the homelab tile and the hero gauge in place; values roll, the gauge sweeps. */
    private void renderLab(LabSummary s) {
        lab = s;
        guestRoll.setValue(s.guestsUp + "/" + s.guests);
        labSub.setText(s.guestsUp == 1 && s.guests == 1 ? "guest up" : "guests up");
        labStats.setText((s.node.isEmpty() ? "host" : s.node) + " · CPU " + LabSummary.pct(s.cpu) + " · RAM " + LabSummary.pct(s.mem)
                + (Double.isNaN(s.temp) ? "" : " · " + Math.round(s.temp) + "°C"));
        String state = s.level() == LabSummary.OK ? "normal" : s.level() == LabSummary.BAD ? "critical" : "needs attention";
        labTile.setContentDescription("Homelab " + state + ". " + s.guestsUp + " of " + s.guests + " guests up. " + labStats.getText() + ". Open System");
        OpsText.Worst w = OpsText.worst(s.cpu, s.mem, s.worstPct, s.temp);
        if (w != null) {
            heroGauge.setVisibility(View.VISIBLE);
            heroGauge.setLabel(w.label);
            heroGauge.post(() -> heroGauge.setValue((float) (w.value / 100), w.display));
        }
        updateHero();
    }

    private void renderSessions(List<HermesApi.SessionInfo> all, String profile) {
        HermesApi.SessionInfo last = null;
        String wanted = store.sessionId();
        for (HermesApi.SessionInfo s : all) if (s.id.equals(wanted)) { last = s; break; }
        if (last == null) for (HermesApi.SessionInfo s : all) if ("chats".equals(ConversationList.kindOf(s))) { last = s; break; }
        if (last == null) { continueTitle.setText("Start a conversation"); continueSub.setText("Nothing to continue yet"); }
        else {
            HermesApi.SessionInfo s = last;
            continueTitle.setText(s.displayTitle()); continueSub.setText(ChatActivity.relTime(s.lastActive));
            ((View) continueTitle.getParent()).setContentDescription("Continue " + s.displayTitle() + ", " + continueSub.getText());
            ((View) continueTitle.getParent()).setOnClickListener(v -> openChat(s.id, s.displayTitle(), s.model));
        }

        // "Needs you" shows only when something does.
        needsChips.removeAllViews();
        List<HermesApi.SessionInfo> needs = ConversationList.needsYou(all, id -> ConversationList.approvalPending(runs, profile, id), store.pendingProposals());
        needsRow.setVisibility(needs.isEmpty() ? View.GONE : View.VISIBLE);
        for (int i = 0; i < needs.size() && i < 6; i++) {
            HermesApi.SessionInfo s = needs.get(i);
            String why = ConversationList.approvalPending(runs, profile, s.id) ? "Waiting for your approval"
                    : store.pendingProposals().contains(s.id) ? "Proposal waiting for an answer" : ConversationList.previewOf(s);
            String title = s.displayTitle();
            TextView chip = Ui.pillButton(this, title.length() > 26 ? title.substring(0, 25).trim() + "…" : title, 0, Ui.tier(2), Ui.LINE, Ui.TEXT);
            chip.setMinHeight(dp(48)); chip.setSingleLine(true);
            chip.setContentDescription(title + ", " + why);
            chip.setOnClickListener(v -> openChat(s.id, title, s.model));
            Ui.press(chip);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, -2); lp.setMargins(0, 0, dp(8), 0);
            needsChips.addView(chip, lp);
        }
    }

    private void renderCaptures(List<EssentialsApi.Item> items) {
        capturesBody.removeAllViews();
        if (items.isEmpty()) { note(capturesBody, "No captures yet"); return; }
        for (EssentialsApi.Item it : items) {
            String title = !it.title.isEmpty() ? it.title : !it.text.isEmpty() ? it.text : !it.transcript.isEmpty() ? it.transcript : "Capture";
            row(capturesBody, 0xff4d675f, title.replace('\n', ' '), it.kind + (it.status.isEmpty() ? "" : " · " + it.status), ChatActivity.relTime(it.ts), this::openCapture);
        }
    }

    /** The Agents tile ("6 failed", tap for the list) and the quiet fix cards for failed runs. */
    private void renderAgents(List<HermesApi.Job> jobs) {
        int failed = 0;
        for (HermesApi.Job j : jobs) if (failedRun(j)) failed++;
        failedRoll.setTextSize(28); failedRoll.setTypeface(Ui.MONO_SEMI);
        failedRoll.setTextColor(failed > 0 ? Ui.RED : Ui.TEXT);
        failedRoll.setValue(String.valueOf(failed > 0 ? failed : jobs.size()));
        agentsSub.setText(jobs.isEmpty() ? "no agents yet" : failed > 0 ? "failed last run" : jobs.size() == 1 ? "agent, no failures" : "agents, no failures");
        ((View) agentsSub.getParent()).setContentDescription("Agents. " + (failed > 0 ? failed + " failed last run" : jobs.size() + " agents, no failures") + ". Open Agents");

        fixBody.removeAllViews();
        List<HermesApi.Job> bad = new ArrayList<>();
        for (HermesApi.Job j : jobs) if (failedRun(j)) bad.add(j);
        bad.sort((x, y) -> y.lastRunAt.compareTo(x.lastRunAt));
        fixCard.setVisibility(bad.isEmpty() ? View.GONE : View.VISIBLE);
        for (int i = 0; i < bad.size() && i < 3; i++) {
            HermesApi.Job j = bad.get(i);
            String name = j.name.isEmpty() ? "Agent" : j.name, why = JobsActivity.shortError(j.lastError);
            LinearLayout r = new LinearLayout(this); r.setOrientation(LinearLayout.VERTICAL);
            r.setPadding(Ui.space(this, 16), dp(6), Ui.space(this, 16), dp(2));
            r.addView(Ui.body(this, OpsText.agentFailure(name), Ui.TEXT), match());
            if (!why.isEmpty()) { TextView c = Ui.caption(this, why, Ui.SUB); c.setMaxLines(2); c.setEllipsize(TextUtils.TruncateAt.END); r.addView(c, match()); }
            TextView fix = Ui.caption(this, "Fix with Hermes", Ui.BLUE);
            fix.setMinHeight(dp(48)); fix.setGravity(Gravity.CENTER_VERTICAL); fix.setMinWidth(dp(48));
            fix.setContentDescription("Fix " + name + " with Hermes");
            fix.setOnClickListener(v -> askHermes("Fix " + name, "The scheduled agent \"" + name + "\" failed on its last run with:\n" + j.lastError
                    + "\n\nFind out why (read-only first: logs, config, installed modules), explain the cause, and propose a fix as a proposal card. "
                    + "Don't change anything until I approve."));
            r.addView(fix, new LinearLayout.LayoutParams(-2, -2));
            fixBody.addView(r, match());
        }
    }

    private void mergeFeed(List<HubApi.Event> got) {
        ((View) feedBody.getParent()).setVisibility(View.VISIBLE);
        for (HubApi.Event e : got) if (e.id > feedLast) { feed.add(0, e); feedLast = e.id; }
        while (feed.size() > 200) feed.remove(feed.size() - 1);
        feedBody.removeAllViews();
        List<Feed.Row> rows = Feed.collapse(feed);
        if (rows.isEmpty()) note(feedBody, "No activity yet");
        for (int i = 0; i < rows.size() && i < 4; i++) feedBody.addView(feedRow(rows.get(i)), match());
        if (!got.isEmpty() && feed.size() > got.size()) Ui.fadeIn(feedBody);
    }

    private View feedRow(Feed.Row r) {
        HubApi.Event e = r.e;
        String kind = Feed.kindOf(e);
        int icon = kind.equals("alert") ? R.drawable.ic_pulse : kind.equals("job") ? R.drawable.ic_clock : kind.equals("proposal") ? R.drawable.ic_bolt : R.drawable.ic_info;
        int color = kind.equals("alert") ? Ui.RED : kind.equals("info") ? Ui.GREEN : kind.equals("proposal") ? Ui.AMBER : Ui.SUB;
        LinearLayout row = new LinearLayout(this); row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(Ui.space(this, 16), dp(6), Ui.space(this, 14), dp(6)); row.setMinimumHeight(dp(52));
        row.setBackground(Ui.ripple(this, Color.TRANSPARENT, 0)); row.setOnClickListener(v -> openEvent(e)); Ui.press(row);
        ImageView ic = new ImageView(this); ic.setImageResource(icon); ic.setImageTintList(ColorStateList.valueOf(color));
        ic.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        row.addView(ic, new LinearLayout.LayoutParams(dp(22), dp(22)));
        LinearLayout copy = new LinearLayout(this); copy.setOrientation(LinearLayout.VERTICAL); copy.setPadding(dp(12), 0, dp(6), 0);
        String title = (e.title.isEmpty() ? "Event" : e.title) + (r.count > 1 ? " ×" + r.count : "");
        TextView t = Ui.body(this, title, Ui.TEXT); t.setSingleLine(true); t.setEllipsize(TextUtils.TruncateAt.END); copy.addView(t);
        if (!e.body.isEmpty()) { TextView d = Ui.caption(this, e.body.replace('\n', ' '), Ui.SUB); d.setSingleLine(true); d.setEllipsize(TextUtils.TruncateAt.END); copy.addView(d); }
        row.addView(copy, new LinearLayout.LayoutParams(0, -2, 1));
        String ago = Feed.ago(e.ts, System.currentTimeMillis());
        row.addView(Ui.num(this, ago, 12, Ui.FAINT));
        row.setContentDescription(title + ", " + ago);
        return row;
    }

    private void openEvent(HubApi.Event e) {
        if (!e.sessionId.isEmpty()) startActivity(new Intent(this, MainActivity.class).putExtra(MainActivity.EXTRA_SESSION, e.sessionId));
        else if (!e.jobId.isEmpty()) startActivity(new Intent(this, JobsActivity.class).putExtra(JobsActivity.EXTRA_JOB_ID, e.jobId));
        else if (Feed.kindOf(e).equals("alert")) startActivity(new Intent(this, StatusActivity.class));
        else Sheets.info(this, e.title.isEmpty() ? "Event" : e.title, Feed.ago(e.ts, System.currentTimeMillis()), new String[][]{{"Details", e.body.isEmpty() ? "(no details)" : e.body}});
    }

    private void showFeed() {
        LinearLayout col = new LinearLayout(this); col.setOrientation(LinearLayout.VERTICAL);
        LinearLayout chips = new LinearLayout(this); chips.setPadding(dp(12), dp(4), dp(12), dp(8));
        LinearLayout list = new LinearLayout(this); list.setOrientation(LinearLayout.VERTICAL);
        ScrollView sv = new ScrollView(this); sv.addView(list);
        col.addView(chips, match()); col.addView(sv, new LinearLayout.LayoutParams(-1, 0, 1));
        String[][] kinds = {{"All", "all"}, {"Alerts", "alert"}, {"Jobs", "job"}, {"Info", "info"}};
        TextView[] pills = new TextView[kinds.length];
        Runnable[] pick = new Runnable[1];
        String[] cur = {"all"};
        pick[0] = () -> {
            list.removeAllViews();
            List<Feed.Row> rows = Feed.filter(Feed.collapse(feed), cur[0]);
            if (rows.isEmpty()) note(list, "Nothing here");
            for (Feed.Row r : rows) list.addView(feedRow(r), match());
            for (int i = 0; i < pills.length; i++) {
                boolean on = kinds[i][1].equals(cur[0]);
                pills[i].setBackground(Ui.stroke(this, on ? Ui.BLUE : Ui.tier(1), on ? Ui.BLUE : Ui.LINE, 20));
                pills[i].setTextColor(on ? Color.WHITE : Ui.SUB);
            }
        };
        for (int i = 0; i < kinds.length; i++) {
            String k = kinds[i][1];
            TextView c = Ui.pillButton(this, kinds[i][0], 0, Ui.tier(1), Ui.LINE, Ui.SUB);
            c.setOnClickListener(v -> { cur[0] = k; pick[0].run(); });
            pills[i] = c;
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, -2); lp.setMargins(0, 0, dp(8), 0);
            chips.addView(c, lp);
        }
        pick[0].run();
        Sheets.tall(this, "Activity", feed.size() + " recent events", col);
    }

    private static boolean failedRun(HermesApi.Job j) {
        String s = j.lastStatus.toLowerCase(java.util.Locale.ROOT);
        return !j.lastError.isEmpty() || s.startsWith("fail") || s.startsWith("error");
    }
}
