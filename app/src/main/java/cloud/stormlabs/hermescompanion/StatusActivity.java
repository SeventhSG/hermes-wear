package cloud.stormlabs.hermescompanion;

import android.app.Activity;
import android.app.Dialog;
import android.content.Intent;
import android.content.res.ColorStateList;
import android.graphics.Typeface;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.Editable;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.TextUtils;
import android.text.TextWatcher;
import android.text.style.ForegroundColorSpan;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.GridLayout;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * The System screen: one health summary over Hermes (gateway, usage, platforms) and, when the hub
 * is set up, the host and its services. Each source loads and fails on its own. OpsActivity
 * redirects here.
 */
public final class StatusActivity extends Activity {
    public static final String EXTRA_SKILLS = "skills", EXTRA_PROMPT = "prompt";

    private final ExecutorService network = Executors.newFixedThreadPool(3);
    private final Handler handler = new Handler(Looper.getMainLooper());
    private SettingsStore store;
    private AgentBackend api;
    private HubApi hub;
    private boolean hubOn, visible;
    private Runnable stopLogs; // turns the open log sheet's Follow off
    private ScrollView scroll;
    private LinearLayout body;
    private final Map<String, View> anchors = new java.util.LinkedHashMap<>(); // section order, for the jump chips

    // Hero is built once and reused by every render so its heartbeat ring survives rebuilds.
    private LinearLayout hero;
    private TextView heroWord, heroSentence, heroLive;
    private View heroDot;
    private Ui.Heartbeat heartbeat;
    private TextView heroAsk;
    private int failStreak;
    private List<SystemHealth.Reason> heroReasons = new ArrayList<>();
    // Gauges and rolling numbers outlive renders so values sweep/roll instead of snapping.
    private final Map<String, Gauge> gauges = new HashMap<>();
    private final Map<String, DigitRoll> rolls = new HashMap<>();
    private final List<Runnable> later = new ArrayList<>();
    private boolean storageOpen, detailsOpen;

    // Written and read on the UI thread only; null data and null error means "still loading".
    private JSONObject health, caps;
    private HubApi.Services ops;
    private HubApi.Infra infra; // null with infraOff = hub too old (404), hide Proxmox
    private boolean infraOff, infraBusy;
    private HubApi.Security sec; private boolean secOff;
    private HubApi.Sync sync; private boolean syncOff;
    private HubApi.Network net; private HubApi.Backups bak; // null = not loaded / old hub: section hidden
    private boolean netOff, bakOff, extraBusy;
    private HubApi.Updates upd; private boolean updOff, updBusy;
    private String healthError, capsError, opsError;
    private boolean healthBusy, capsBusy, opsBusy;

    private TextView testRow;
    private boolean testing;
    private final Runnable poll = new Runnable() { @Override public void run() { load(false); handler.postDelayed(this, 15_000); } };

    private int dp(int n) { return Ui.dp(this, n); }
    private LinearLayout.LayoutParams match(int h) { return new LinearLayout.LayoutParams(-1, h < 0 ? h : dp(h)); }

    @Override public void onCreate(Bundle b) {
        super.onCreate(b);
        Ui.legacyWindowInsets(this);
        store = new SettingsStore(this);
        api = Backends.create(store);
        hubOn = api.capabilitiesForUi().hub && !store.hubUrl().isEmpty();
        if (hubOn) hub = new HubApi(store.hubUrl(), store.hubKey());
        if (hubOn) restoreLastKnown();
        getWindow().setStatusBarColor(Ui.BAR);
        getWindow().setNavigationBarColor(Ui.BG);
        build();
        render();
    }

    @Override protected void onResume() { super.onResume(); visible = true; handler.removeCallbacks(poll); load(true); handler.postDelayed(poll, 15_000); }
    @Override protected void onPause() { visible = false; handler.removeCallbacks(poll); if (stopLogs != null) stopLogs.run(); super.onPause(); }
    @Override protected void onDestroy() { super.onDestroy(); handler.removeCallbacks(poll); network.shutdownNow(); }

    private void build() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Ui.BG);

        LinearLayout bar = new LinearLayout(this);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setPadding(dp(2), 0, dp(8), 0);
        bar.setBackgroundColor(Ui.BAR);
        ImageButton back = Ui.icon(this, R.drawable.ic_back, "Back");
        back.setOnClickListener(v -> onBackPressed());
        bar.addView(back, new LinearLayout.LayoutParams(dp(52), dp(56)));
        bar.addView(Ui.title(this, "System", Ui.TEXT), new LinearLayout.LayoutParams(0, -2, 1));
        ImageButton refresh = Ui.icon(this, R.drawable.ic_refresh, "Refresh");
        refresh.setOnClickListener(v -> load(true));
        bar.addView(refresh, new LinearLayout.LayoutParams(dp(48), dp(56)));
        root.addView(bar, match(56));
        buildHero();

        scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        body = new LinearLayout(this);
        body.setOrientation(LinearLayout.VERTICAL);
        body.setPadding(dp(12), dp(12), dp(12), dp(28));
        scroll.addView(body);
        root.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));
        Nav.tabBar(this, root, Layout.Tab.SYSTEM);
        setContentView(root);
        Ui.fitInsets(root, root);
    }

    // ---------------------------------------------------------------- loading

    /** Polls keep the last good data on screen; capabilities only reload on an explicit refresh. */
    private void load(boolean withCaps) {
        if (isDestroyed() || network.isShutdown()) return;
        if (!healthBusy) {
            healthBusy = true;
            network.submit(() -> {
                JSONObject h = null; String err = null;
                try { h = api.healthDetailed(); } catch (Exception e) { err = HermesApi.friendly(e); }
                JSONObject result = h; String error = err;
                runOnUiThread(() -> {
                    healthBusy = false;
                    if (result != null) { health = result; failStreak = 0; heartbeat.stalled(false); } else if (++failStreak >= 2) heartbeat.stalled(true);
                    healthError = error;
                    render();
                    if (result != null) heartbeat.beat();
                });
            });
        }
        if (withCaps && !capsBusy) {
            capsBusy = true;
            network.submit(() -> {
                JSONObject c = null; String err = null;
                try { c = api.capabilities(); } catch (Exception e) { err = HermesApi.friendly(e); }
                JSONObject result = c; String error = err;
                runOnUiThread(() -> { capsBusy = false; if (result != null) caps = result; capsError = error; render(); });
            });
        }
        if (hubOn && !opsBusy) {
            opsBusy = true;
            network.submit(() -> {
                HubApi.Services s = null; String err = null; JSONObject raw = null;
                try { raw = hub.opsServicesRaw(); s = HubApi.parseServices(raw); } catch (Exception e) { err = HermesApi.friendly(e); }
                if (raw != null) remember("services", raw);
                HubApi.Services result = s; String error = err;
                runOnUiThread(() -> { opsBusy = false; if (result != null) { ops = result; servicesStaleAt = 0; } opsError = error; render(); });
            });
        }
        if (hubOn && !infraOff && !infraBusy && new SettingsStore(this).moduleOn(Modules.HOMELAB)) {
            infraBusy = true;
            network.submit(() -> {
                HubApi.Infra i = null; boolean off = false; JSONObject raw = null;
                try { raw = hub.opsInfraRaw(); i = new HubApi.Infra(raw); } catch (Exception e) { off = String.valueOf(e.getMessage()).startsWith("HTTP 404"); }
                if (raw != null) remember("infra", raw);
                HubApi.Infra result = i; boolean gone = off;
                runOnUiThread(() -> { infraBusy = false; if (result != null) { infra = result; infraStaleAt = 0; } if (gone) { infraOff = true; infra = null; } render(); });
            });
        }
        loadExtras();
    }

    /** Network and backups load after the main render; a failure keeps the last data, a 404 hides the section for good. */
    private void loadExtras() {
        loadUpdates();
        if (!hubOn || extraBusy || netOff && bakOff) return;
        extraBusy = true;
        network.submit(() -> {
            HubApi.Network n = null; HubApi.Backups b = null; boolean nOff = netOff, bOff = bakOff;
            if (!nOff) try { n = hub.opsNetwork(); } catch (Exception e) { nOff = String.valueOf(e.getMessage()).startsWith("HTTP 404"); }
            if (!bOff) try { b = hub.opsBackups(); } catch (Exception e) { bOff = String.valueOf(e.getMessage()).startsWith("HTTP 404"); }
            HubApi.Security sc = null; boolean sOff = secOff;
            if (!sOff) try { sc = hub.security(); } catch (Exception e) { sOff = String.valueOf(e.getMessage()).startsWith("HTTP 404"); }
            HubApi.Security rs = sc; boolean fs = sOff;
            runOnUiThread(() -> { secOff = fs; if (rs != null) sec = rs; });
            HubApi.Sync sy = null; boolean yOff = syncOff;
            if (!yOff) try { sy = hub.sync(); } catch (Exception e) { yOff = String.valueOf(e.getMessage()).startsWith("HTTP 404"); }
            HubApi.Sync ry = sy; boolean fy = yOff;
            runOnUiThread(() -> { syncOff = fy; if (ry != null) sync = ry; });
            HubApi.Network rn = n; HubApi.Backups rb = b; boolean fn = nOff, fb = bOff;
            runOnUiThread(() -> { extraBusy = false; netOff = fn; bakOff = fb; if (rn != null) net = rn; if (rb != null) bak = rb; render(); });
        });
    }

    /** Updates radar loads separately (the hub's first probe can take ~20 s); a 404 hides it. */
    private void loadUpdates() {
        if (!hubOn || updOff || updBusy) return;
        updBusy = true;
        network.submit(() -> {
            HubApi.Updates u = null; boolean off = false;
            try { u = hub.opsUpdates(); } catch (Exception e) { off = String.valueOf(e.getMessage()).startsWith("HTTP 404"); }
            HubApi.Updates ru = u; boolean fo = off;
            runOnUiThread(() -> { updBusy = false; updOff = fo; if (ru != null) upd = ru; render(); });
        });
    }

    private void updatesCard() {
        if (upd == null || upd.hosts.isEmpty()) return;
        LinearLayout card = section("UPDATES", "updates");
        for (HubApi.UpdHost h : upd.hosts)
            card.addView(tapRow(h.reboot || h.security > 0 ? SystemHealth.WARN : SystemHealth.OK, h.line(), "›", v -> Sheets.info(this, h.name, h.line(),
                    new String[][]{{"Upgradable (first " + h.sample.size() + ")", h.sample.isEmpty() ? "none" : TextUtils.join("\n", h.sample)}})), match(-2));
        if (!upd.hermes.isEmpty()) kvMono(card, "Hermes", upd.hermes);
        card.addView(actionRow("Ask Hermes to update", "Snapshot first, list changes, wait for approval", v -> askHermes("Updates", "Pending updates:\n" + updSummary()
                + "\nPlease take a snapshot/backup first where possible, list exactly what will change, and WAIT for my approval before upgrading anything.")), match(-2));
        body.addView(card, match(-2));
    }

    private String updSummary() {
        StringBuilder sb = new StringBuilder();
        for (HubApi.UpdHost h : upd.hosts) sb.append("- ").append(h.line()).append('\n');
        return sb.toString();
    }

    private void render() {
        if (isDestroyed()) return;
        body.removeAllViews();
        anchors.clear();
        later.clear();
        staleBanner();
        summaryCard();
        hermesCard();
        usageCard();
        if (hubOn) { pveHostCard(); hostCard(); proxmoxCard(); servicesCard(); networkCard(); securityCard(); syncCard(); backupsCard(); updatesCard(); } else hubHint();
        platformsCard();
        capabilitiesCard();
        connectionCard();
        jumpChips();
        Ui.stagger(body);
        for (Runnable r : later) r.run(); // gauges and rolling numbers need to be attached first
        later.clear();
    }

    // ---------------------------------------------------------------- last known (offline)

    /** Last good /ops payloads, per hub, so System still shows something useful when the hub can't be reached. */
    private long servicesStaleAt, infraStaleAt;
    private java.io.File lastKnownFile(String what) { return new java.io.File(getCacheDir(), "ops-" + what + "-" + Integer.toHexString(store.hubUrl().hashCode()) + ".json"); }

    private void remember(String what, JSONObject raw) {
        try (java.io.FileOutputStream out = new java.io.FileOutputStream(lastKnownFile(what))) {
            out.write(new JSONObject().put("at", System.currentTimeMillis()).put("data", raw).toString().getBytes(java.nio.charset.StandardCharsets.UTF_8));
        } catch (Exception ignored) { }
    }

    private JSONObject recall(String what) {
        java.io.File f = lastKnownFile(what);
        if (!f.isFile()) return null;
        try { return new JSONObject(new String(java.nio.file.Files.readAllBytes(f.toPath()), java.nio.charset.StandardCharsets.UTF_8)); }
        catch (Exception e) { return null; }
    }

    private void restoreLastKnown() {
        JSONObject s = recall("services"), i = recall("infra");
        try { if (s != null) { ops = HubApi.parseServices(s.getJSONObject("data")); servicesStaleAt = s.optLong("at"); } } catch (Exception ignored) { }
        try { if (i != null && new SettingsStore(this).moduleOn(Modules.HOMELAB)) { infra = new HubApi.Infra(i.getJSONObject("data")); infraStaleAt = i.optLong("at"); } } catch (Exception ignored) { }
    }

    /** Shown while what's on screen is the remembered copy rather than a live answer. */
    private void staleBanner() {
        long at = Math.max(servicesStaleAt, infraStaleAt);
        if (at == 0) return;
        boolean failing = opsError != null;
        if (!failing && System.currentTimeMillis() - at < 120_000) return; // a fresh copy is about to land anyway
        String age = TimeFmt.relativePast(String.valueOf(at / 1000));
        TextView t = Ui.body(this, (failing ? "Can't reach the hub \u00b7 last known " : "Last known ") + age + (failing ? "" : " \u00b7 refreshing\u2026"), failing ? Ui.AMBER : Ui.SUB);
        t.setPadding(dp(16), dp(10), dp(16), dp(10));
        t.setBackground(Ui.stroke(this, Ui.tier(1), failing ? (Ui.AMBER & 0xffffff) | 0x55000000 : Ui.LINE, 12));
        LinearLayout.LayoutParams lp = match(-2); lp.bottomMargin = dp(10);
        body.addView(t, lp);
    }

    // ---------------------------------------------------------------- summary

    private void buildHero() {
        hero = new LinearLayout(this);
        hero.setOrientation(LinearLayout.VERTICAL);
        hero.setPadding(dp(20), dp(16), dp(20), dp(16));
        hero.setBackground(Ui.card(this, 2));
        Ui.elevate(hero, 2);
        LinearLayout top = new LinearLayout(this);
        top.setGravity(Gravity.CENTER_VERTICAL);
        top.addView(Ui.label(this, "System", Ui.SUB), new LinearLayout.LayoutParams(0, -2, 1));
        heroLive = Ui.caption(this, "Connecting", Ui.SUB);
        top.addView(heroLive);
        heroDot = new View(this);
        heroDot.setBackground(Ui.circle(Ui.GREEN));
        heroDot.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO); // the "Live" text beside it says the same
        LinearLayout.LayoutParams dp = new LinearLayout.LayoutParams(dp(8), dp(8));
        dp.setMargins(dp(8), 0, dp(4), 0);
        top.addView(heroDot, dp);
        hero.addView(top, match(-2));
        heartbeat = Ui.heartbeat(heroDot);
        heroWord = Ui.display(this, "Checking", Ui.SUB);
        heroWord.setPadding(0, dp(6), 0, 0);
        hero.addView(heroWord, match(-2));
        heroSentence = Ui.body(this, "", Ui.SUB);
        heroSentence.setGravity(Gravity.CENTER_VERTICAL);
        heroSentence.setMaxLines(3);
        heroSentence.setEllipsize(TextUtils.TruncateAt.END);
        heroSentence.setOnClickListener(v -> { if (!heroReasons.isEmpty()) open(heroReasons.get(0)); });
        hero.addView(heroSentence, match(-2));
        heroAsk = Ui.pillButton(this, "Ask Hermes to investigate", R.drawable.ic_assistant, Ui.BLUE, Ui.BLUE, android.graphics.Color.WHITE);
        heroAsk.setMinHeight(dp(48));
        Ui.press(heroAsk);
        heroAsk.setOnClickListener(v -> {
            StringBuilder list = new StringBuilder();
            for (SystemHealth.Reason r : heroReasons) list.append("- ").append(r.text).append('\n');
            askHermes("Homelab check", "The System screen in the companion app shows these problems:\n" + list
                    + "\nInvestigate each one read-only first (status, logs, disk usage), explain the likely cause, and propose fixes as "
                    + "proposal cards. Don't change anything until I approve.");
        });
        LinearLayout.LayoutParams ap = match(-2);
        ap.topMargin = dp(12);
        hero.addView(heroAsk, ap);
    }

    /** Every source feeds one reason list, so the hero and the rows below can never disagree. */
    private List<SystemHealth.Reason> allReasons() {
        List<SystemHealth.Reason> reasons = SystemHealth.reasons(health, healthError, ops, opsError, store.dailyTokenBudget());
        reasons.addAll(SystemHealth.pveReasons(infra == null ? null : infra.pveHost));
        reasons.addAll(SystemHealth.backupReasons(bak));
        reasons.addAll(SystemHealth.updateReasons(upd));
        reasons.sort((a, b) -> b.level - a.level);
        return reasons;
    }

    private void summaryCard() {
        boolean waiting = health == null && healthError == null || hubOn && ops == null && opsError == null;
        List<SystemHealth.Reason> reasons = allReasons();
        heroReasons = reasons;
        int overall = SystemHealth.overall(reasons);
        boolean checking = waiting && overall == SystemHealth.OK;
        int sev = OpsText.severity(overall);
        int color = checking ? Ui.SUB : sevColor(sev);
        String word = OpsText.word(sev, checking);
        heroWord.setText(word);
        heroWord.setTextColor(color);
        heroWord.setContentDescription("System " + word.toLowerCase(Locale.ROOT));
        String healthy = hubOn ? "Hermes, host and " + (ops == null ? 0 : ops.services.size()) + " services are fine" : "Hermes is fine";
        heroSentence.setText(OpsText.sentence(reasons, checking, healthy));
        heroSentence.setTextColor(reasons.isEmpty() ? Ui.SUB : Ui.TEXT);
        heroSentence.setMinHeight(reasons.isEmpty() ? 0 : dp(48));
        heroSentence.setClickable(!reasons.isEmpty());
        heroSentence.setPadding(0, dp(2), 0, dp(2));
        heroSentence.setContentDescription(reasons.isEmpty() ? null : heroSentence.getText() + ", tap for details");
        heroAsk.setVisibility(reasons.isEmpty() ? View.GONE : View.VISIBLE);
        boolean stalled = failStreak >= 2;
        heroLive.setText(stalled ? "Reconnecting" : health == null && healthError == null ? "Connecting" : "Live");
        heroLive.setTextColor(stalled ? Ui.AMBER : Ui.SUB);
        if (hero.getParent() instanceof ViewGroup) ((ViewGroup) hero.getParent()).removeView(hero);
        LinearLayout.LayoutParams hp = match(-2);
        hp.bottomMargin = dp(4);
        body.addView(hero, hp);
        if (reasons.size() > 1) {
            LinearLayout card = section("Also needs a look", "summary");
            for (int i = 1; i < Math.min(5, reasons.size()); i++) card.addView(reasonRow(reasons.get(i)), match(-2));
            if (reasons.size() > 5) card.addView(note("+" + (reasons.size() - 5) + " more below"), match(-2));
            body.addView(card, match(-2));
        }
    }

    /** A row of chips above the first section that scroll to each one (the page is long), like Settings. */
    private int chipScrollX;

    private void jumpChips() {
        View first = null;
        LinearLayout chips = new LinearLayout(this);
        for (View anchor : anchors.values()) {
            if (!(anchor instanceof TextView) || anchor.getParent() != body) continue;
            String title = ((TextView) anchor).getText().toString().split(" \u00b7 ")[0].trim();
            if (first == null) first = anchor;
            if (title.startsWith("ALSO NEEDS")) continue; // directly below the chips anyway
            String label = title.isEmpty() ? "" : title.substring(0, 1) + title.substring(1).toLowerCase(Locale.ROOT);
            TextView chip = Ui.text(this, label, 12.5f, Ui.SUB, false);
            chip.setGravity(Gravity.CENTER); chip.setPadding(dp(12), 0, dp(12), 0); chip.setMinHeight(dp(40));
            chip.setBackground(Ui.stroke(this, android.graphics.Color.TRANSPARENT, Ui.LINE, 99));
            chip.setOnClickListener(v -> scroll.smoothScrollTo(0, anchor.getTop()));
            LinearLayout target = new LinearLayout(this); target.setGravity(Gravity.CENTER_VERTICAL);
            target.addView(chip, new LinearLayout.LayoutParams(-2, -2));
            Ui.touchTarget(chip, target);
            LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(-2, dp(48)); p.setMargins(0, 0, dp(6), 0);
            chips.addView(target, p);
        }
        if (first == null || chips.getChildCount() < 4) return;
        android.widget.HorizontalScrollView row = new android.widget.HorizontalScrollView(this);
        row.setHorizontalScrollBarEnabled(false);
        row.addView(chips);
        // The screen re-renders on every refresh: keep the chips where the user left them.
        row.setOnScrollChangeListener((v, x, y, ox, oy) -> chipScrollX = x);
        int keep = chipScrollX;
        row.post(() -> row.scrollTo(keep, 0));
        LinearLayout.LayoutParams lp = match(48); lp.setMargins(0, dp(8), 0, 0);
        body.addView(row, body.indexOfChild(first), lp);
    }

    private void open(SystemHealth.Reason r) {
        if (r.service != null) { showLogs(r.service); return; }
        View anchor = anchors.get(r.section);
        if (anchor != null) scroll.smoothScrollTo(0, anchor.getTop());
    }

    private View reasonRow(SystemHealth.Reason r) {
        View row = row(r.level, r.text, null, false, "›", v -> open(r));
        row.setContentDescription(r.text + ", tap for details");
        return row;
    }

    // ---------------------------------------------------------------- hermes

    private void hermesCard() {
        LinearLayout card = section("Hermes", "hermes");
        if (health == null) { card.addView(healthError != null ? errorRow(healthError) : skeleton(), match(-2)); body.addView(card, match(-2)); return; }
        JSONObject h = health;
        String gateway = SystemHealth.text(h, "gateway_state", "gateway");
        String status = gateway.isEmpty() ? SystemHealth.text(h, "status", "state") : gateway;
        String active = SystemHealth.text(h, "active_agents", "agents_active"), busy = SystemHealth.text(h, "gateway_busy", "busy");
        String heartbeatRaw = SystemHealth.text(h, "last_heartbeat", "updated_at", "heartbeat_at");
        String headline = OpsText.hermesHeadline(active, busy);
        StringBuilder sub = new StringBuilder(OpsText.gatewayLine(status));
        if (!heartbeatRaw.isEmpty()) sub.append(sub.length() > 0 ? " · " : "").append("Last heartbeat ").append(TimeFmt.lastSeen(heartbeatRaw));
        LinearLayout head = new LinearLayout(this);
        head.setOrientation(LinearLayout.VERTICAL);
        head.setPadding(dp(16), dp(14), dp(16), dp(8));
        LinearLayout titleRow = new LinearLayout(this);
        titleRow.setGravity(Gravity.CENTER_VERTICAL);
        titleRow.addView(dot(levelColor(SystemHealth.stateLevel(gateway))), new LinearLayout.LayoutParams(dp(8), dp(8)));
        TextView t = Ui.title(this, headline, Ui.TEXT);
        t.setPadding(dp(10), 0, 0, 0);
        titleRow.addView(t);
        head.addView(titleRow, match(-2));
        if (sub.length() > 0) {
            TextView c = Ui.caption(this, sub.toString(), Ui.SUB);
            c.setPadding(dp(18), dp(2), 0, 0);
            head.addView(c, match(-2));
        }
        head.setContentDescription(headline + (sub.length() > 0 ? ". " + sub : ""));
        card.addView(head, match(-2));
        kvMono(card, "Version", SystemHealth.text(h, "version", "hermes_version", "app_version"));
        String model = caps == null ? "" : SystemHealth.text(caps, "model", "default_model");
        if (!model.isEmpty()) kvMono(card, "Model", model);
        List<String> readiness = SystemHealth.readinessLines(h.optJSONObject("readiness"), ops != null);
        if (!readiness.isEmpty()) {
            subLabel(card, "Readiness");
            for (String line : readiness) {
                TextView reason = Ui.body(this, line, Ui.AMBER);
                reason.setMaxLines(3);
                reason.setEllipsize(TextUtils.TruncateAt.END);
                reason.setPadding(dp(16), dp(2), dp(16), dp(4));
                card.addView(reason, match(-2));
            }
        }
        // Raw fields stay available, but out of the way.
        card.addView(toggleRow("Details", detailsOpen, () -> { detailsOpen = !detailsOpen; render(); }), match(-2));
        if (detailsOpen) {
            String[][] raw = {{"Gateway state", gateway.isEmpty() ? status : gateway}, {"Active agents", active}, {"Busy", busy}, {"PID", SystemHealth.text(h, "pid")}};
            for (String[] kv : raw) if (!kv[1].isEmpty()) kvMono(card, kv[0], kv[1]);
        }
        if (healthError != null) card.addView(errorRow("Last refresh failed: " + healthError), match(-2));
        card.setPadding(0, 0, 0, dp(6));
        body.addView(card, match(-2));
    }

    /** A 48dp row with a rotating chevron that opens or closes a group. */
    private View toggleRow(String label, boolean open, Runnable flip) {
        LinearLayout row = new LinearLayout(this);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setMinimumHeight(dp(48));
        row.setPadding(dp(16), 0, dp(16), 0);
        row.setBackground(Ui.ripple(this, android.graphics.Color.TRANSPARENT, 12));
        row.addView(Ui.caption(this, label, Ui.SUB), new LinearLayout.LayoutParams(0, -2, 1));
        TextView chev = Ui.body(this, "›", Ui.FAINT);
        chev.setRotation(open ? 90 : 0);
        row.addView(chev);
        row.setContentDescription(label + (open ? ", expanded" : ", collapsed"));
        row.setOnClickListener(v -> flip.run());
        Ui.press(row);
        return row;
    }

    private void usageCard() {
        JSONObject metrics = health == null ? null : health.optJSONObject("metrics_today");
        if (health == null && healthError != null) return; // the Hermes card already explains
        LinearLayout card = section("USAGE TODAY", "usage");
        if (health == null) { card.addView(skeleton(), match(-2)); body.addView(card, match(-2)); return; }
        if (metrics == null) { card.addView(note("The server doesn't report usage"), match(-2)); body.addView(card, match(-2)); return; }
        double tokens = SystemHealth.number(metrics, "tokens", "total_tokens", "tokens_total");
        double requests = SystemHealth.number(metrics, "requests", "total_requests", "request_count");
        double latency = SystemHealth.number(metrics, "latency_p95_ms", "latency_p95", "p95_latency_ms");
        int budget = store.dailyTokenBudget();
        meter(card, "use.tokens", "Tokens", String.format(Locale.US, "%,.0f", tokens), budget > 0 ? String.format(Locale.US, "of %,d", budget) : "",
                tokens / (budget > 0 ? budget : 100_000) * 100, budget > 0 && tokens > budget ? Ui.RED : Ui.BLUE, 6);
        meter(card, "use.requests", "Requests", String.format(Locale.US, "%,.0f", requests), "", requests, Ui.BLUE, 6);
        meter(card, "use.latency", "Latency p95", String.format(Locale.US, "%,.0f ms", latency), "", latency / 50, latency >= 5_000 ? Ui.AMBER : Ui.BLUE, 6);
        double cost = SystemHealth.cost(metrics);
        if (!Double.isNaN(cost)) kvMono(card, "Cost", String.format(Locale.US, "$%.2f", cost));
        body.addView(card, match(-2));
    }

    // ---------------------------------------------------------------- host & services

    private void hubHint() {
        LinearLayout card = section("HOST & SERVICES", "host");
        TextView t = note("Set up the hub in Settings to see host CPU, RAM, disk and services. Tap to open Settings.");
        t.setTextColor(Ui.BLUE);
        t.setMinHeight(dp(48));
        t.setBackground(Ui.ripple(this, android.graphics.Color.TRANSPARENT, 16));
        t.setOnClickListener(v -> startActivity(new Intent(this, SettingsActivity.class)));
        card.addView(t, match(-2));
        body.addView(card, match(-2));
    }

    private void hostCard() {
        HubApi.Host h = ops == null ? null : ops.host;
        String hn = infra != null && infra.host != null && !infra.host.hostname.isEmpty() && !"host".equals(infra.host.hostname) ? infra.host.hostname : h == null ? "" : h.hostname;
        LinearLayout card = section(hn.isEmpty() || "host".equals(hn) ? "Host" : "Host · " + hn, "host");
        if (h == null) { card.addView(opsError != null ? errorRow(opsError) : skeleton(), match(-2)); body.addView(card, match(-2)); return; }
        double ram = HostThresholds.ratio(h.memUsedMb, h.memTotalMb), disk = HostThresholds.ratio(h.diskUsedGb, h.diskTotalGb);
        meter(card, "host.cpu", "CPU", pctText(h.cpuPct), h.load1 > 0 || h.cores > 0 ? String.format(Locale.US, "load %.2f", h.load1) + (h.cores > 0 ? " on " + h.cores + " cores" : "") : "",
                h.cpuPct, thresholdColor(HostThresholds.percent(h.cpuPct)), barFor(h.cpuPct));
        meter(card, "host.ram", "RAM", pctText(ram), String.format(Locale.US, "%.1f / %.1f GB", h.memUsedMb / 1024.0, h.memTotalMb / 1024.0), ram, thresholdColor(HostThresholds.percent(ram)), barFor(ram));
        meter(card, "host.disk", "Disk (host, via hub)", pctText(disk), String.format(Locale.US, "%.0f / %.0f GB", h.diskUsedGb, h.diskTotalGb), disk, thresholdColor(HostThresholds.percent(disk)), barFor(disk));
        kvMono(card, "Uptime", SystemHealth.uptime(h.uptimeS));
        String os = osLabel();
        if (!os.isEmpty()) kv(card, "System", os);
        if (infra != null) card.addView(actionRow("All VMs, containers and nodes", "Pick one for details and actions", v -> allDetails()), match(-2));
        if (opsError != null) card.addView(errorRow("Last refresh failed: " + opsError), match(-2));
        body.addView(card, match(-2));
    }

    private static String pctText(double v) { return Double.isNaN(v) ? "-" : Math.round(v) + "%"; }
    /** Critical bars are 8dp, the rest 6dp. */
    private static int barFor(double pct) { return HostThresholds.percent(pct) == 2 ? 8 : 6; }

    /** The Proxmox host itself: three gauges, history, storage worst first, then the quiet facts. */
    private void pveHostCard() {
        HubApi.PveHost h = infra == null ? null : infra.pveHost;
        if (h == null) return;
        LinearLayout card = section("Proxmox host · " + h.node, "pvehost");
        double gb = 1073741824.0;
        double ramPct = h.memTotal > 0 ? 100 * (h.memTotal - h.memAvail) / h.memTotal : Double.NaN;
        double cpuT = h.cpuTemp(), nvme = h.tempOf("Composite");
        List<HubApi.PveHost.Storage> storage = OpsText.sortStorage(h.storage);
        HubApi.PveHost.Storage worstStorage = storage.isEmpty() || !storage.get(0).reachable() && storage.size() == 1 ? null : storage.get(0);

        LinearLayout gaugeRow = new LinearLayout(this);
        gaugeRow.setPadding(dp(8), dp(14), dp(8), dp(2));
        if (!Double.isNaN(h.cpuPct)) addGauge(gaugeRow, "pve.cpu", "CPU %", h.cpuPct, pctDigits(h.cpuPct), "CPU " + pctText(h.cpuPct));
        if (!Double.isNaN(ramPct)) addGauge(gaugeRow, "pve.ram", "RAM %", ramPct, pctDigits(ramPct), "RAM " + pctText(ramPct));
        if (!Double.isNaN(cpuT) && (cpuT >= 80 || worstStorage == null))
            addGauge(gaugeRow, "pve.third", "Temp °C", cpuT, String.valueOf(Math.round(cpuT)), "CPU temperature " + Math.round(cpuT) + " degrees");
        else if (worstStorage != null) {
            double sp = OpsText.storagePct(worstStorage);
            addGauge(gaugeRow, "pve.third", "Disk %", sp, pctDigits(sp), "Worst storage " + worstStorage.name + " " + pctText(sp));
        }
        if (gaugeRow.getChildCount() > 0) card.addView(gaugeRow, match(-2));

        StringBuilder facts = new StringBuilder();
        if (!Double.isNaN(h.load1)) facts.append(String.format(Locale.US, "load %.2f", h.load1)).append(h.cores > 0 ? " on " + h.cores + " threads" : "");
        if (!Double.isNaN(cpuT)) facts.append(facts.length() > 0 ? " · " : "").append(String.format(Locale.US, "CPU %.0f °C", cpuT));
        if (!Double.isNaN(nvme)) facts.append(facts.length() > 0 ? " · " : "").append(String.format(Locale.US, "NVMe %.0f °C", nvme));
        if (facts.length() > 0) {
            TextView f = Ui.num(this, facts.toString(), 12, !Double.isNaN(cpuT) && cpuT >= 90 ? Ui.RED : !Double.isNaN(cpuT) && cpuT >= 80 || HostThresholds.load(h.load1, h.cores) > 0 ? Ui.AMBER : Ui.SUB);
            f.setGravity(Gravity.CENTER);
            f.setPadding(dp(14), dp(2), dp(14), dp(6));
            card.addView(f, match(-2));
        }
        historyBlock(card, !Double.isNaN(cpuT));

        int healthy = 0;
        for (HubApi.PveHost.Storage st : storage) if (OpsText.storageHealthy(st)) healthy++;
        for (HubApi.PveHost.Storage st : storage) if (!OpsText.storageHealthy(st)) storageRow(card, st);
        if (healthy > 0) {
            card.addView(toggleRow(healthy + " healthy", storageOpen, () -> { storageOpen = !storageOpen; render(); }), match(-2));
            if (storageOpen) for (HubApi.PveHost.Storage st : storage) if (OpsText.storageHealthy(st)) storageRow(card, st);
        }
        if (h.swapTotal > 0) kvMono(card, "Swap", String.format(Locale.US, "%.0f%% · %.1f / %.1f GB", 100 * (h.swapTotal - h.swapFree) / h.swapTotal, (h.swapTotal - h.swapFree) / gb, h.swapTotal / gb));
        java.util.regex.Matcher v = java.util.regex.Pattern.compile("pve-manager/([\\d.]+)").matcher(h.pveVersion);
        kvMono(card, "Proxmox VE", (v.find() ? v.group(1) : "?") + (h.kernel.isEmpty() ? "" : " · kernel " + h.kernel));
        if (!h.model.isEmpty()) kv(card, "CPU", h.model.replaceAll("\\(R\\)|\\(TM\\)| CPU", "").trim() + (h.cores > 0 ? " · " + h.cores + " threads" : ""));
        kvMono(card, "Uptime", SystemHealth.uptime((long) h.uptime));
        kvMono(card, infra.nodes.size() > 1 ? "Guests on " + h.node : "Guests", h.running + " running · " + h.stopped + " stopped");
        for (HubApi.Pve n : infra.nodes) if (n.name.equals(h.node)) { card.addView(actionRow("Node details", "Proxmox status for " + h.node, v2 -> pveDetail(n)), match(-2)); break; }
        body.addView(card, match(-2));
    }

    private static String pctDigits(double v) { return String.valueOf(Math.round(v)); }

    private void addGauge(LinearLayout row, String key, String label, double pct, String digits, String description) {
        Gauge g = gauge(key, label);
        row.addView(g, new LinearLayout.LayoutParams(0, dp(112), 1));
        later.add(() -> { g.setValue((float) (pct / 100), digits); g.setContentDescription(description); });
    }

    private Gauge gauge(String key, String label) {
        Gauge g = gauges.get(key);
        if (g == null) { g = new Gauge(this); gauges.put(key, g); }
        unparent(g);
        g.setLabel(label);
        return g;
    }

    private DigitRoll roll(String key, float sp, int color) {
        DigitRoll r = rolls.get(key);
        if (r == null) { r = new DigitRoll(this); rolls.put(key, r); r.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO); }
        unparent(r);
        r.setTextSize(sp);
        r.setTextColor(color);
        return r;
    }

    private static void unparent(View v) { if (v.getParent() instanceof ViewGroup) ((ViewGroup) v.getParent()).removeView(v); }

    private void storageRow(LinearLayout card, HubApi.PveHost.Storage st) {
        if (!st.reachable()) { card.addView(errorRow(st.name + " (" + st.type + ") unreachable"), match(-2)); return; }
        double pct = OpsText.storagePct(st);
        meter(card, "pve.st." + st.name, st.name + " · " + st.type, pctText(pct), size(st.used) + " / " + size(st.total), pct, thresholdColor(HostThresholds.percent(pct)), barFor(pct));
    }

    private String histRange = "24h";
    private final java.util.Map<String, double[][]> histCache = new java.util.concurrent.ConcurrentHashMap<>();
    private final java.util.Map<String, Long> histFetchedAt = new java.util.concurrent.ConcurrentHashMap<>();
    private volatile boolean histBusy;

    /** CPU / RAM / temp sparklines (card width x 64dp) with a 24h/7d chip; loaded off the UI thread, hidden on a hub without /ops/history. */
    private void historyBlock(LinearLayout card, boolean temp) {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(14), dp(4), dp(14), dp(6));
        TextView chip = Ui.caption(this, "History · " + histRange + " ⇄", Ui.BLUE);
        chip.setPadding(dp(12), 0, dp(12), 0); chip.setMinHeight(dp(48)); chip.setGravity(Gravity.CENTER_VERTICAL);
        chip.setBackground(Ui.stroke(this, Ui.tier(2), Ui.LINE, 20));
        chip.setContentDescription("History range " + histRange + ". Tap to switch between 24 hours and 7 days");
        Ui.press(chip);
        box.addView(chip, new LinearLayout.LayoutParams(-2, -2));
        String[][] rows = temp ? new String[][]{{"CPU", "pve.cpu", "%"}, {"RAM", "pve.mem", "%"}, {"Temp", "pve.temp", "°"}} : new String[][]{{"CPU", "pve.cpu", "%"}, {"RAM", "pve.mem", "%"}};
        TextView[] labels = new TextView[rows.length];
        Sparkline[] lines = new Sparkline[rows.length];
        for (int i = 0; i < rows.length; i++) {
            labels[i] = Ui.num(this, rows[i][0] + " · " + histRange, 12, Ui.SUB);
            labels[i].setSingleLine(true);
            labels[i].setPadding(0, dp(10), 0, dp(4)); labels[i].setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO); // the sparkline reads the same text
            box.addView(labels[i]);
            lines[i] = new Sparkline(this, Ui.BLUE, i == 2 ? Double.NaN : 100, false);
            box.addView(lines[i], match(64));
        }
        long span = "7d".equals(histRange) ? 7 * 86400 : 86400;
        Runnable apply = () -> { for (int i = 0; i < rows.length; i++) {
            double[][] d = histCache.get(rows[i][1] + histRange);
            if (d == null) continue;
            String sum = rows[i][0] + " · " + histRange + " · " + History.summary(d, rows[i][2]);
            labels[i].setText(sum);
            lines[i].setData(d, span, sum);
        } };
        apply.run();
        chip.setOnClickListener(v -> { histRange = "24h".equals(histRange) ? "7d" : "24h"; load(false); });
        card.addView(box, match(-2));
        // render() runs after every partial load: fetch at most once a minute per range, one request batch at a time.
        String range = histRange;
        Long at = histFetchedAt.get(range);
        if (histBusy || at != null && System.currentTimeMillis() - at < 60_000 || network.isShutdown()) return;
        histBusy = true;
        network.submit(() -> {
            try {
                for (String[] r : rows) histCache.put(r[1] + range, hub.history(r[1], range));
                histFetchedAt.put(range, System.currentTimeMillis());
                runOnUiThread(() -> { if (!isDestroyed()) apply.run(); });
            } catch (Exception e) { runOnUiThread(() -> box.setVisibility(View.GONE)); }
            finally { histBusy = false; }
        });
    }

    private static String size(double bytes) {
        return bytes >= 1e12 ? String.format(Locale.US, "%.2f TB", bytes / 1e12) : String.format(Locale.US, "%.0f GB", bytes / 1e9);
    }

    /** "Ubuntu 25.04 · LXC on Proxmox", "Proxmox VE 9.2 host"; empty when the hub can't say. */
    private String osLabel() {
        HubApi.Host h = infra == null ? null : infra.host;
        if (h == null) return "";
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("/(\\d+\\.\\d+)").matcher(h.pveVersion);
        String kind = "pve".equals(h.kind) ? "" : "lxc".equals(h.kind) ? "LXC" : "vm".equals(h.kind) ? "VM" : "bare".equals(h.kind) ? "bare metal" : "";
        if ("pve".equals(h.kind)) return "Proxmox VE" + (m.find() ? " " + m.group(1) : "") + " host";
        return h.osPretty + (kind.isEmpty() ? "" : (h.osPretty.isEmpty() ? "" : " · ") + kind + (infra.reachable && !"bare".equals(h.kind) ? " on Proxmox" : ""));
    }

    private void proxmoxCard() {
        if (infra == null || !infra.reachable && infra.nodes.isEmpty() && infra.guests.isEmpty() && infra.error.isEmpty()) return;
        LinearLayout card = section("PROXMOX · " + infra.nodes.size() + " nodes · " + infra.guests.size() + " guests", "proxmox");
        if (!infra.reachable) card.addView(errorRow("Proxmox unreachable" + (infra.error.isEmpty() ? "" : ": " + infra.error)), match(-2));
        // Each node followed by its own guests (running first, then stopped), so counts per node add up.
        List<HubApi.Pve> guests = new ArrayList<>(infra.guests);
        java.util.Collections.sort(guests, (x, y) -> rank(x) != rank(y) ? rank(x) - rank(y) : Long.compare(x.vmid, y.vmid));
        java.util.Set<String> listed = new java.util.HashSet<>();
        for (HubApi.Pve n : infra.nodes) {
            card.addView(pveRow(n), match(-2));
            for (HubApi.Pve g : guests) if (n.name.equals(g.node)) { card.addView(indent(pveRow(g)), match(-2)); listed.add(g.type + g.vmid); }
        }
        for (HubApi.Pve g : guests) if (!listed.contains(g.type + g.vmid)) card.addView(pveRow(g), match(-2));
        card.addView(actionRow("Recent tasks", "Backups, starts, stops and updates, with their logs", v -> tasksSheet()), match(-2));
        body.addView(card, match(-2));
    }

    private View indent(View v) { v.setPadding(v.getPaddingLeft() + dp(14), v.getPaddingTop(), v.getPaddingRight(), v.getPaddingBottom()); return v; }

    private void networkCard() {
        if (net == null || !net.tailnet && net.checks.isEmpty() && net.certs.isEmpty()) return;
        LinearLayout card = section("NETWORK", "network");
        if (net.tailnet) {
            kvMono(card, "Tailnet", net.self + (net.selfIps.isEmpty() ? "" : " · " + net.selfIps));
            kv(card, "Exit node", net.exitNode.isEmpty() ? "none" : net.exitNode);
            card.addView(tapRow(SystemHealth.OK, net.online() + " of " + net.peers.size() + " peers online", "›", v -> peersSheet()), match(-2));
        }
        for (HubApi.Check c : net.checks)
            kvColored(card, c.name + " · " + c.target, c.ms < 0 ? "unreachable" : c.ms + " ms", levelColor(SystemHealth.latencyLevel(c.ms)));
        // The hub sorts soonest-expiring first: show those three, the rest behind a summary row.
        for (int i = 0; i < Math.min(3, net.certs.size()); i++) {
            HubApi.Cert c = net.certs.get(i);
            kvColored(card, "TLS \u00b7 " + c.host, SystemHealth.certText(c.days, c.error), levelColor(SystemHealth.certLevel(c.days, c.error)));
        }
        if (net.certs.size() > 3) {
            int worst = SystemHealth.OK;
            for (HubApi.Cert c : net.certs) if (c.error.isEmpty() || c.error.startsWith("invalid")) worst = Math.max(worst, SystemHealth.certLevel(c.days, c.error));
            card.addView(tapRow(worst, "All " + net.certs.size() + " certificates", "\u203a", v -> certsSheet()), match(-2));
        }
        body.addView(card, match(-2));
    }

    private void syncCard() {
        if (sync == null || sync.devices.isEmpty() && sync.folders.isEmpty()) return;
        LinearLayout card = section("SYNC \u00b7 SYNCTHING", "sync");
        long now = System.currentTimeMillis();
        for (String[] d : sync.devices) {
            boolean connected = Boolean.parseBoolean(d[1]);
            long seen = 0; try { seen = java.time.Instant.parse(d[2]).toEpochMilli(); } catch (Exception ignored) { }
            String when = connected ? "connected" : seen <= 0 ? "never connected" : "last seen " + TimeFmt.relativePast(String.valueOf(seen / 1000));
            if (Boolean.parseBoolean(d[3])) when = "paused";
            card.addView(tapRow(SystemHealth.syncDeviceLevel(connected, seen, now), d[0], when, v -> syncSheet()), match(-2));
        }
        for (JSONObject f : sync.folders) {
            if (f.optLong("bytes") == 0 && f.optJSONArray("shared_with") != null && f.optJSONArray("shared_with").length() == 0) continue; // the empty default folder
            int errors = f.optInt("errors"), need = f.optInt("need");
            String state = errors > 0 ? errors + " errors" : need > 0 ? need + " files to fetch" : f.optString("state", "idle");
            kvColored(card, "Folder \u00b7 " + f.optString("label"), state + " \u00b7 " + android.text.format.Formatter.formatShortFileSize(this, f.optLong("bytes")),
                    levelColor(errors > 0 ? SystemHealth.BAD : need > 0 ? SystemHealth.WARN : SystemHealth.OK));
        }
        body.addView(card, match(-2));
    }

    private void syncSheet() {
        List<String[]> rows = new java.util.ArrayList<>();
        for (JSONObject f : sync.folders) {
            JSONArray w = f.optJSONArray("shared_with");
            StringBuilder with = new StringBuilder();
            if (w != null) for (int i = 0; i < w.length(); i++) with.append(i == 0 ? "" : ", ").append(w.optString(i));
            rows.add(new String[]{f.optString("label"), f.optString("path") + (with.length() == 0 ? " \u00b7 not shared" : " \u00b7 shared with " + with)});
        }
        rows.add(new String[]{"If a device stopped syncing", "Open Syncthing on that device and check it\u2019s running and not paused. On Android, battery optimisation often stops it in the background."});
        Sheets.info(this, "Syncthing", sync.devices.size() + " devices \u00b7 " + sync.folders.size() + " folders", rows.toArray(new String[0][]));
    }

    private void securityCard() {
        if (sec == null) return;
        LinearLayout card = section("SECURITY \u00b7 CROWDSEC", "security");
        // Paths that never existed are usually another distro's defaults in a stock setup file; only stale ones mean something moved.
        int stale = 0; for (String[] st : sec.staleLogs) if (!st[1].isEmpty()) stale++;
        if (sec.blind || stale > 0) card.addView(tapRow(SystemHealth.WARN, (stale == 0 ? "Reading no useful logs" : stale + (stale == 1 ? " watched log has" : " watched logs have") + " stopped updating")
                + ": attacks on your sites aren\u2019t detected, only blocklists apply", "\u203a", v -> securitySheet()), match(-2));
        kvMono(card, "Blocked IPs", String.format(java.util.Locale.US, "%,d \u00b7 %d lists", sec.blockedIps, sec.lists.size()));
        kvMono(card, "Packets dropped", String.format(java.util.Locale.US, "%,d", sec.droppedPackets));
        kvColored(card, "Detections \u00b7 24h", sec.alerts24h == 0 ? "none" : String.valueOf(sec.alerts24h), levelColor(sec.alerts24h > 0 ? SystemHealth.WARN : SystemHealth.OK));
        card.addView(tapRow(SystemHealth.OK, "Lists and log sources", "\u203a", v -> securitySheet()), match(-2));
        if (stale > 0) {
            StringBuilder paths = new StringBuilder();
            for (String[] st : sec.staleLogs) if (!st[1].isEmpty()) paths.append("\n- ").append(st[0]).append(" (no new lines for ").append(st[1]).append(" days)");
            card.addView(actionRow("Ask Hermes to fix CrowdSec", "It proposes the new log paths and waits for your OK", v -> askHermes("CrowdSec",
                    "CrowdSec in Proxmox container 108 (servarr) watches log paths that stopped updating, so it detects nothing locally:" + paths
                    + "\nFind where Nginx Proxy Manager and SSH log now (NPM likely writes to the npm-data Docker volume's logs/ directory; SSH may log to journald), "
                    + "propose the exact changes to /etc/crowdsec/acquis.yaml and acquis.d/, and wait for my approval before changing or restarting anything.")), match(-2));
        }
        body.addView(card, match(-2));
    }

    private void securitySheet() {
        List<String[]> rows = new java.util.ArrayList<>();
        for (String[] l : sec.lists) rows.add(new String[]{"Blocklist \u00b7 " + l[0].replace("lists:", "").replace("CAPI", "CrowdSec community"),
                String.format(java.util.Locale.US, "%,d IPs \u00b7 %,d packets dropped", Long.parseLong(l[1]), Long.parseLong(l[2]))});
        for (String[] src : sec.sources) rows.add(new String[]{"Log \u00b7 " + src[0],
                String.format(java.util.Locale.US, "%,d lines read \u00b7 %,d understood", Long.parseLong(src[1]), Long.parseLong(src[2]))});
        List<String> absent = new java.util.ArrayList<>();
        for (String[] st : sec.staleLogs) {
            if (st[1].isEmpty()) absent.add(st[0]);
            else rows.add(new String[]{"Stale \u00b7 " + st[0], "Nothing new for " + st[1] + " days: whatever writes here has moved or stopped"});
        }
        if (!absent.isEmpty()) rows.add(new String[]{"Not on this system (harmless)", String.join("\n", absent)});
        if (sec.blind || !sec.staleLogs.isEmpty()) rows.add(new String[]{"How to fix", "Point CrowdSec\u2019s acquisition files (/etc/crowdsec/acquis.yaml and acquis.d/) at the logs that are actually written now, "
                + "for Nginx Proxy Manager usually the npm-data Docker volume\u2019s logs/proxy-host-*_access.log, then restart crowdsec. Or tap Ask Hermes on System to have it done."});
        for (String s : sec.scenarios) rows.add(new String[]{"Detected", s});
        Sheets.info(this, "CrowdSec", sec.blind ? "Blocklists only \u00b7 local detection is off" : "Detecting and blocking", rows.toArray(new String[0][]));
    }

    private void certsSheet() {
        String[][] rows = new String[net.certs.size()][];
        int unreachable = 0;
        for (int i = 0; i < rows.length; i++) {
            HubApi.Cert c = net.certs.get(i);
            if (c.error.startsWith("unreachable")) unreachable++;
            rows[i] = new String[]{c.host, SystemHealth.certText(c.days, c.error) + (c.issuer.isEmpty() ? "" : " \u00b7 " + c.issuer)};
        }
        Sheets.info(this, "TLS certificates", rows.length + " watched, soonest first" + (unreachable > 0 ? " \u00b7 " + unreachable + " not answering" : ""), rows);
    }

    private void peersSheet() {
        String[][] rows = new String[net.peers.size()][];
        for (int i = 0; i < rows.length; i++) {
            HubApi.Peer p = net.peers.get(i);
            rows[i] = new String[]{(p.online ? "● " : "○ ") + p.name, (p.os.isEmpty() ? "" : p.os + " · ") + (p.online ? "online" : "seen " + TimeFmt.lastSeen(p.lastSeen))};
        }
        Sheets.info(this, "Tailnet peers", net.online() + " of " + rows.length + " online", rows);
    }

    private void backupsCard() {
        if (bak == null || bak.guests.isEmpty()) return;
        LinearLayout card = section("BACKUPS", "backups");
        for (String broken : bak.brokenJobs) card.addView(tapRow(SystemHealth.BAD, broken, "\u203a", v -> backupsSheet()), match(-2));
        if (!bak.brokenJobs.isEmpty()) card.addView(actionRow("Ask Hermes to fix the backup job", "It lists usable storage and waits for your choice", v -> askHermes("Backups",
                "These Proxmox backup jobs target storage that is disabled or missing, so their next run will fail:\n- " + String.join("\n- ", bak.brokenJobs)
                + "\nList the storages that can hold vzdump backups with their free space (note /vault is nearly full), recommend a target, and wait for my choice before changing the job. Do not re-enable backup104.")), match(-2));
        card.addView(tapRow(SystemHealth.OK, bak.jobs.isEmpty() ? "No backup jobs" : bak.jobs.size() + (bak.jobs.size() == 1 ? " job · " : " jobs · ") + bak.jobs.get(0), "›", v -> backupsSheet()), match(-2));
        for (HubApi.BackupGuest g : bak.guests) card.addView(tapRow(SystemHealth.backupLevel(g.ageDays, g.status), g.name, backupAge(g), v -> backupsSheet()), match(-2));
        body.addView(card, match(-2));
    }

    private static String backupAge(HubApi.BackupGuest g) {
        return SystemHealth.failed(g.status) ? "failed · " + g.status : g.ageDays < 0 ? "never" : g.ageDays < 1 ? "today" : Math.round(g.ageDays) + " d ago";
    }

    private void backupsSheet() {
        List<String[]> rows = new ArrayList<>();
        for (String j : bak.jobs) rows.add(new String[]{"Job", j});
        for (HubApi.BackupGuest g : bak.guests) rows.add(new String[]{g.name + " (" + g.vmid + ")", backupAge(g)});
        Sheets.info(this, "Backups", "From Proxmox task history", rows.toArray(new String[0][]));
    }

    private View tapRow(int level, String left, String right, View.OnClickListener click) {
        View row = row(level, left, null, false, right, click);
        row.setContentDescription(left + ", " + right);
        return row;
    }

    /**
     * The one list row: plain status dot, title, one line of metadata (mono when it carries numbers),
     * a quiet right-hand tag. Heartbeat lives on the hero only.
     */
    private View row(int level, String title, String meta, boolean monoMeta, String tag, View.OnClickListener click) {
        LinearLayout row = new LinearLayout(this);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setMinimumHeight(dp(meta == null || meta.isEmpty() ? 48 : 56));
        row.setPadding(dp(16), dp(6), dp(14), dp(6));
        int dotColor = levelColor(level);
        row.addView(dot(dotColor), new LinearLayout.LayoutParams(dp(8), dp(8)));
        LinearLayout text = new LinearLayout(this);
        text.setOrientation(LinearLayout.VERTICAL);
        text.setPadding(dp(14), 0, dp(8), 0);
        TextView name = Ui.body(this, title, Ui.TEXT);
        name.setMaxLines(2);
        name.setEllipsize(TextUtils.TruncateAt.END);
        text.addView(name);
        if (meta != null && !meta.isEmpty()) {
            int c = level == SystemHealth.OK || level == SystemHealth.NONE ? Ui.SUB : dotColor;
            TextView m = monoMeta ? Ui.num(this, meta, 12, c) : Ui.caption(this, meta, c);
            m.setMaxLines(2);
            m.setEllipsize(TextUtils.TruncateAt.END);
            text.addView(m);
        }
        row.addView(text, new LinearLayout.LayoutParams(0, -2, 1));
        if (tag != null && !tag.isEmpty()) row.addView("›".equals(tag) ? Ui.body(this, tag, Ui.FAINT) : Ui.num(this, tag, 12, Ui.SUB));
        row.setContentDescription(title + (meta == null || meta.isEmpty() ? "" : ", " + meta) + (click == null ? "" : ". Tap for details"));
        if (click != null) {
            row.setBackground(Ui.ripple(this, android.graphics.Color.TRANSPARENT, 12));
            row.setOnClickListener(click);
            Ui.press(row);
        }
        return row;
    }

    private boolean nodeDown(HubApi.Pve g) {
        for (HubApi.Pve n : infra.nodes) if (n.name.equals(g.node)) return !n.up();
        return false;
    }

    private int rank(HubApi.Pve g) { return g.up() ? 0 : nodeDown(g) ? 2 : 1;
    }

    private View pveRow(HubApi.Pve p) {
        boolean node = p.type.equals("node");
        boolean away = !node && nodeDown(p);
        int level = p.up() ? SystemHealth.OK : "stopped".equals(p.status) || away ? SystemHealth.NONE : SystemHealth.BAD;
        // Proxmox reports cpu 0 when its stats daemon is stale, so only show a real reading.
        String cpu = !Double.isNaN(p.cpu) && p.cpu > 0 ? String.format(Locale.US, "CPU %.0f%% · ", p.cpu * 100) : "";
        String stats = away ? "on " + p.node + " · node offline" : p.up() ? cpu
                + (Double.isNaN(p.mem) || Double.isNaN(p.maxmem) ? "running" : String.format(Locale.US, "RAM %.1f / %.1f GB", p.mem / 1073741824.0, p.maxmem / 1073741824.0)) : p.status;
        String tag = node ? p.status.toUpperCase(Locale.ROOT) : p.type.equals("lxc") ? "CT" : "VM";
        View row = row(level, p.name + (node || p.name.endsWith(" " + p.vmid) ? "" : " · " + p.vmid), stats, true, tag, v -> pveTap(p));
        row.setContentDescription(p.name + ", " + p.type + ", " + p.status + ". Tap for details");
        return row;
    }

    /** Opens a new conversation that asks Hermes about something on this screen. */
    private void askHermes(String title, String prompt) {
        toast("Asking Hermes…");
        network.submit(() -> {
            try {
                HermesApi.SessionInfo s = Backends.create(store).createSession(title);
                runOnUiThread(() -> startActivity(new Intent(this, MainActivity.class)
                        .putExtra(MainActivity.EXTRA_SESSION, s.id).putExtra(ChatActivity.EXTRA_TITLE, title)
                        .putExtra(ChatActivity.EXTRA_PROMPT, prompt).putExtra(ChatActivity.EXTRA_AUTOSEND, true)
                        .putExtra(MainActivity.EXTRA_PROFILE_SECRET, store.intentSecret())));
            } catch (Exception e) { runOnUiThread(() -> toast("Couldn't start a chat: " + HermesApi.friendly(e))); }
        });
    }

    /** Guests open an actions sheet (details, power, snapshots); nodes go straight to details. */
    private void pveTap(HubApi.Pve p) {
        if (p.type.equals("node")) { pveDetail(p); return; }
        String kind = p.type.equals("lxc") ? "Container " : "VM ";
        List<Sheets.Item> items = new java.util.ArrayList<>();
        items.add(Sheets.Item.of(R.drawable.ic_info, Ui.BLUE_DARK, "Details", "OS, uptime, CPU, memory, disk, IPs", () -> pveDetail(p)));
        boolean self = infra != null && infra.host != null && p.name.equals(infra.host.hostname);
        if (self) items.add(Sheets.Item.of(R.drawable.ic_server, Ui.BLUE_DARK, "This is the server the app talks to", "Control it from Proxmox itself", null));
        else if (p.up()) {
            items.add(Sheets.Item.of(R.drawable.ic_refresh, Ui.BLUE_DARK, "Reboot", "Restart the guest cleanly", () -> power(p, "reboot", "Reboot " + p.name + "?", "It goes offline for a moment and comes back.")));
            items.add(Sheets.Item.of(R.drawable.ic_stop, Ui.BLUE_DARK, "Shut down", "Ask the guest to power off", () -> power(p, "shutdown", "Shut down " + p.name + "?", "Services on it stop until you start it again.")));
            items.add(Sheets.Item.danger(R.drawable.ic_stop, "Force stop", "Pull the plug; unsaved data is lost", () -> power(p, "stop", "Force stop " + p.name + "?", "Like pulling the power cable. Use Shut down unless it's stuck.")));
        } else if ("stopped".equals(p.status)) items.add(Sheets.Item.of(R.drawable.ic_play, Ui.BLUE_DARK, "Start", "Power it on", () -> power(p, "start", null, null)));
        if (!self) items.add(Sheets.Item.of(R.drawable.ic_archive, Ui.BLUE_DARK, "Snapshots", "List, take, roll back", () -> snapshots(p)));
        items.add(Sheets.Item.of(R.drawable.ic_assistant, Ui.BLUE_DARK, "Ask Hermes about it", "Check its health and explain", () -> askHermes(p.name,
                "Check the Proxmox " + kind.trim().toLowerCase(Locale.ROOT) + " " + p.name + " (vmid " + p.vmid + " on node " + p.node + ", currently " + p.status
                        + "): is it healthy, what's using its resources, anything in its logs? Read-only unless I approve changes.")));
        Sheets.actions(this, p.name, kind + p.vmid + " on " + p.node + " · " + p.status, items.toArray(new Sheets.Item[0]));
    }

    /** Start needs one confirm; anything that stops a guest also needs the device credential. */
    private void power(HubApi.Pve p, String action, String title, String warning) {
        String verb = action.equals("start") ? "Starting " : action.equals("shutdown") ? "Shutting down " : action.equals("stop") ? "Stopping " : "Rebooting ";
        Runnable run = () -> { toast(verb + p.name + "…");
            network.submit(() -> { String err = null;
                try { hub.guestAction(p, action); } catch (Exception e) { err = HermesApi.friendly(e); }
                String e2 = err; runOnUiThread(() -> { toast(e2 == null ? "Sent to Proxmox" : "Failed: " + e2); body.postDelayed(() -> load(false), 3000); }); }); };
        if (title == null) Sheets.confirm(this, "Start " + p.name + "?", "Powers on the guest.", "Start", false, run);
        else Sheets.confirm(this, title, warning, action.equals("stop") ? "Force stop" : "Continue", true, () -> Confirm.strong(this, title, run));
    }

    private void snapshots(HubApi.Pve p) {
        toast("Loading snapshots…");
        network.submit(() -> {
            try {
                List<String[]> list = hub.snapshots(p);
                runOnUiThread(() -> {
                    List<Sheets.Item> items = new java.util.ArrayList<>();
                    items.add(Sheets.Item.of(R.drawable.ic_new, Ui.BLUE_DARK, "Take snapshot", "Saves the guest's current state", () -> Sheets.input(this, "New snapshot",
                            "A letter, then letters, digits, - or _.", "phone-" + new java.text.SimpleDateFormat("MMdd-HHmm", Locale.ROOT).format(new java.util.Date()), "Name", name -> {
                                if (!HubApi.snapshotName(name)) { toast("Use a letter, then letters, digits, - or _"); return; }
                                snapshotOp(p, "create", name); }, "Take")));
                    for (String[] s : list) {
                        long t = Long.parseLong(s[1]);
                        items.add(Sheets.Item.of(R.drawable.ic_archive, Ui.BLUE_DARK, s[0], (t > 0 ? new java.text.SimpleDateFormat("d MMM HH:mm", Locale.getDefault()).format(new java.util.Date(t * 1000)) : "") + (s[2].isEmpty() ? "" : " · " + s[2]), () ->
                                Sheets.actions(this, s[0], "Snapshot of " + p.name,
                                        Sheets.Item.of(R.drawable.ic_refresh, Ui.BLUE_DARK, "Roll back", "Return " + p.name + " to this snapshot", () -> Sheets.confirm(this, "Roll back " + p.name + "?",
                                                "Everything changed since " + s[0] + " is lost.", "Roll back", true, () -> Confirm.strong(this, "Roll back " + p.name, () -> snapshotOp(p, "rollback", s[0])))),
                                        Sheets.Item.danger(R.drawable.ic_delete, "Delete", "Remove this snapshot", () -> Sheets.confirm(this, "Delete " + s[0] + "?",
                                                "The snapshot is removed; the guest itself is unchanged.", "Delete", true, () -> Confirm.strong(this, "Delete snapshot", () -> snapshotOp(p, "delete", s[0])))))));
                    }
                    Sheets.actions(this, "Snapshots · " + p.name, list.isEmpty() ? "None yet" : list.size() + " snapshots", items.toArray(new Sheets.Item[0]));
                });
            } catch (Exception e) { runOnUiThread(() -> toast("Could not load snapshots: " + HermesApi.friendly(e))); }
        });
    }

    private void snapshotOp(HubApi.Pve p, String op, String name) {
        network.submit(() -> { String err = null;
            try { hub.snapshot(p, op, name); } catch (Exception e) { err = HermesApi.friendly(e); }
            String e2 = err; runOnUiThread(() -> toast(e2 == null ? "Snapshot " + op + " sent to Proxmox" : "Failed: " + e2)); });
    }

    private void tasksSheet() {
        toast("Loading tasks\u2026");
        network.submit(() -> {
            try {
                List<HubApi.Task> tasks = hub.tasks();
                java.util.Map<String, String> names = new java.util.HashMap<>();
                if (infra != null) for (HubApi.Pve g : infra.guests) names.put(String.valueOf(g.vmid), g.name);
                runOnUiThread(() -> {
                    if (tasks.isEmpty()) { toast("No recent Proxmox tasks"); return; }
                    int failed = 0;
                    List<Sheets.Item> items = new java.util.ArrayList<>();
                    for (HubApi.Task t : tasks) {
                        if (!t.ok()) failed++;
                        String label = OpsText.taskLabel(t.type, t.id, names);
                        String when = TimeFmt.relativePast(String.valueOf(t.start)) + " \u00b7 " + t.node + ("OK".equals(t.status) ? "" : " \u00b7 " + t.status);
                        items.add(Sheets.Item.of(t.ok() ? R.drawable.ic_clock : R.drawable.ic_info, t.ok() ? 0 : Ui.RED, label, when, () -> taskLog(t, label)));
                    }
                    Sheets.actions(this, "Recent Proxmox tasks", tasks.size() + " tasks" + (failed > 0 ? " \u00b7 " + failed + " didn\u2019t finish cleanly" : " \u00b7 all OK"), items.toArray(new Sheets.Item[0]));
                });
            } catch (Exception e) { runOnUiThread(() -> toast("Could not load tasks: " + HermesApi.friendly(e))); }
        });
    }

    private void taskLog(HubApi.Task t, String label) {
        network.submit(() -> {
            try {
                List<String> lines = hub.taskLog(t);
                String text = lines.isEmpty() || lines.size() == 1 && "no content".equals(lines.get(0)) ? "Proxmox kept no log for this task." : android.text.TextUtils.join("\n", lines);
                runOnUiThread(() -> Sheets.info(this, label, t.node + " \u00b7 " + t.status + " \u00b7 by " + t.user, new String[][]{{"Log", text}}));
            } catch (Exception e) { runOnUiThread(() -> toast("Could not load the log: " + HermesApi.friendly(e))); }
        });
    }

    private void wake(HubApi.Pve p) {
        toast("Waking " + p.name + "\u2026");
        network.submit(() -> {
            String err = null;
            try { hub.wakeNode(p.node); } catch (Exception e) { err = HermesApi.friendly(e); }
            String error = err;
            runOnUiThread(() -> {
                if (error != null) toast("Couldn't wake " + p.name + ": " + error);
                else { toast("Wake packet sent. " + p.name + " usually takes a minute or two to come online."); load(false); }
            });
        });
    }

    /** CPU, memory and network over the last 24 h from Proxmox, above the detail rows. */
    private View rrdCharts(double[][][] s) {
        LinearLayout box = new LinearLayout(this); box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(18), 0, dp(18), dp(6));
        String[][] spec = {{"CPU", "%"}, {"Memory", "%"}, {"Network", " KB/s"}};
        TextView head = Ui.label(this, "Last 24 hours", Ui.FAINT); head.setPadding(0, dp(4), 0, 0); box.addView(head);
        for (int i = 0; i < 3; i++) {
            if (s[i].length == 0) continue;
            double[] st = History.stats(s[i]);
            String sum = String.format(java.util.Locale.US, "%s \u00b7 avg %.0f%s \u00b7 peak %.0f%s", spec[i][0], st[1], spec[i][1], st[2], spec[i][1]);
            TextView label = Ui.num(this, sum, 12, Ui.SUB); label.setSingleLine(true);
            label.setPadding(0, dp(8), 0, dp(4)); label.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
            box.addView(label);
            // Percent charts scale to the data (at least 0-10%) so a quiet guest isn't a flat line at the floor.
            double top = i == 2 ? Double.NaN : Math.min(100, Math.max(10, Math.ceil(st[2] * 1.25 / 5) * 5));
            Sparkline line = new Sparkline(this, i == 2 ? Ui.GREEN : Ui.BLUE, top, false);
            box.addView(line, match(56));
            line.setData(s[i], 86400, sum);
        }
        return box.getChildCount() == 0 ? null : box;
    }

    private void pveDetail(HubApi.Pve p) {
        boolean node = p.type.equals("node");
        String title = p.name, sub = node ? "Proxmox node · " + p.status : (p.type.equals("lxc") ? "Container " : "VM ") + p.vmid + " on " + p.node + " · " + p.status;
        if (node && !p.up()) {
            if (p.wol) Sheets.actions(this, title, "Offline \u2014 the node isn't reachable",
                    Sheets.Item.of(R.drawable.ic_bolt, Ui.GREEN, "Wake " + p.name, "Send a Wake-on-LAN packet from Proxmox", () -> wake(p)));
            else Sheets.info(this, title, sub, new String[][]{{"Status", "offline \u2014 the node isn't reachable"},
                    {"Wake-on-LAN", "Not set up. In Proxmox: " + p.name + " \u203a System \u203a Options \u203a MAC address for Wake-on-LAN, and enable WoL in its BIOS. Then you can wake it from here."}});
            return;
        }
        toast("Loading " + title + "…");
        network.submit(() -> {
            try {
                HubApi.Detail d = node ? hub.opsNode(p.node) : hub.opsGuest(p.node, p.type, p.vmid);
                double[][][] rrd = null;
                try { rrd = hub.rrd(p.node, p.type, p.vmid); } catch (Exception ignored) { /* charts are a bonus; details still show */ }
                double[][][] series = rrd;
                runOnUiThread(() -> Sheets.info(this, title, sub, d.rows.toArray(new String[0][]), series == null ? null : rrdCharts(series)));
            } catch (Exception e) { runOnUiThread(() -> toast("Could not load details: " + HermesApi.friendly(e))); }
        });
    }

    private void allDetails() {
        List<Sheets.Item> items = new java.util.ArrayList<>();
        for (HubApi.Pve n : infra.nodes) items.add(Sheets.Item.of(R.drawable.ic_server, n.up() ? Ui.GREEN : Ui.RED, n.name, "Node · " + n.status, () -> pveDetail(n)));
        for (HubApi.Pve g : infra.guests) items.add(Sheets.Item.of(R.drawable.ic_server, g.up() ? Ui.BLUE : Ui.FAINT, g.name, (g.type.equals("lxc") ? "CT " : "VM ") + g.vmid + " · " + g.status, () -> pveTap(g)));
        Sheets.actions(this, "Proxmox", "Pick a node, VM or container", items.toArray(new Sheets.Item[0]));
    }

    private View actionRow(String label, String sub, View.OnClickListener l) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.VERTICAL);
        row.setMinimumHeight(dp(48));
        row.setPadding(dp(14), dp(8), dp(14), dp(8));
        row.setBackground(Ui.ripple(this, android.graphics.Color.TRANSPARENT, 12));
        row.addView(Ui.body(this, label, Ui.BLUE));
        row.addView(Ui.caption(this, sub, Ui.SUB));
        row.setContentDescription(label + ", " + sub);
        row.setOnClickListener(l);
        return row;
    }

    private void servicesCard() {
        if (ops == null) return; // the host card shows loading or the error for the hub
        int bad = 0;
        for (HubApi.Service s : ops.services) if (SystemHealth.serviceLevel(s.state) != SystemHealth.OK) bad++;
        LinearLayout card = section("SERVICES · " + ops.services.size() + (bad > 0 ? " · " + bad + " need attention" : ""), "services");
        if (ops.services.isEmpty()) card.addView(note("No services reported"), match(-2));
        for (HubApi.Service s : SystemHealth.sortServices(ops.services)) card.addView(serviceRow(s), match(-2));
        body.addView(card, match(-2));
    }

    private View serviceRow(HubApi.Service service) {
        int level = SystemHealth.serviceLevel(service.state);
        boolean docker = "docker".equalsIgnoreCase(service.kind);
        String detail = docker ? OpsText.dockerStatus(service.state) : OpsText.systemdStatus(service.state, service.sub, service.since, System.currentTimeMillis());
        View row = row(level, service.name, detail, false, service.kind.toUpperCase(Locale.ROOT), v -> showLogs(service));
        row.setContentDescription(service.name + ", " + detail + (service.kind.isEmpty() ? "" : ", " + service.kind) + ". Tap for logs, restart and diagnose");
        return row;
    }

    private void showLogs(HubApi.Service service) {
        ScrollView logScroll = new ScrollView(this);
        TextView logs = Ui.text(this, "Loading last 200 lines…", 12, Ui.SUB, false);
        logs.setTypeface(Typeface.MONOSPACE);
        logs.setTextIsSelectable(true);
        logs.setPadding(dp(16), dp(12), dp(16), dp(18));
        logScroll.addView(logs);
        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        TextView upLabel = Ui.caption(this, "Last 24 h", Ui.FAINT);
        upLabel.setPadding(dp(16), dp(4), dp(16), dp(4));
        Sparkline up = new Sparkline(this, Ui.GREEN, 1, true);
        LinearLayout.LayoutParams ulp = match(14);
        ulp.setMargins(dp(16), 0, dp(16), dp(6));
        content.addView(upLabel);
        content.addView(up, ulp);
        // Follow pill + LIVE dot + filter
        List<String> all = new ArrayList<>();
        String[] cursor = {""}, filter = {""};
        boolean[] follow = {false}, loaded = {false};
        LinearLayout bar = new LinearLayout(this);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setPadding(dp(16), 0, dp(16), dp(6));
        TextView followPill = Ui.pillButton(this, "Follow", 0, Ui.SURFACE, Ui.LINE, Ui.SUB);
        View live = new View(this);
        live.setBackground(Ui.circle(Ui.GREEN));
        live.setVisibility(View.GONE);
        live.setContentDescription("Live");
        followPill.setMinHeight(dp(48));
        EditText find = new EditText(this);
        find.setHint("Filter");
        find.setTextColor(Ui.TEXT);
        find.setHintTextColor(Ui.FAINT);
        find.setTextSize(13);
        find.setSingleLine(true);
        find.setPadding(dp(12), 0, dp(12), 0);
        find.setBackground(Ui.stroke(this, Ui.SURFACE, Ui.LINE, 20));
        bar.addView(followPill);
        bar.addView(live, new LinearLayout.LayoutParams(dp(8), dp(8)));
        ((LinearLayout.LayoutParams) live.getLayoutParams()).setMargins(dp(8), 0, dp(8), 0);
        bar.addView(find, new LinearLayout.LayoutParams(0, -2, 1));
        find.setMinHeight(dp(48));
        content.addView(bar);
        content.addView(logScroll, new LinearLayout.LayoutParams(-1, 0, 1));
        Runnable[] tick = new Runnable[1];
        Dialog sheet = Sheets.tall(this, service.name, service.kind + " · " + service.state, content,
                stoppedContainer(service)
                        ? Sheets.Item.of(R.drawable.ic_play, Ui.GREEN, "Start", "Confirm and authenticate", () ->
                                Confirm.strong(this, "Start " + service.name, () -> restart(service.name, "start")))
                        : Sheets.Item.of(R.drawable.ic_refresh, Ui.AMBER, "Restart", "Confirm and authenticate", () ->
                                Confirm.strong(this, "Restart " + service.name, () -> restart(service.name, "restart"))),
                Sheets.Item.of(R.drawable.ic_server, Ui.BLUE, "Diagnose with Hermes", "Send recent logs to chat", () -> diagnose(service)));
        // Colours the line by severity (error red, warn amber)
        java.util.function.Function<String, CharSequence> span = line -> {
            int lv = LogLines.level(line);
            SpannableStringBuilder b = new SpannableStringBuilder(line).append('\n');
            if (lv != LogLines.NORMAL) b.setSpan(new ForegroundColorSpan(lv == LogLines.ERROR ? Ui.RED : Ui.AMBER), 0, b.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            return b;
        };
        Runnable render = () -> {
            SpannableStringBuilder b = new SpannableStringBuilder();
            for (String l : all) if (LogLines.matches(l, filter[0])) b.append(span.apply(l));
            logs.setText(all.isEmpty() ? "No recent logs" : b.length() == 0 ? "No matching lines" : b);
        };
        // Appends new lines; keeps the scroll pinned only if the user was at the bottom
        java.util.function.Consumer<List<String>> append = add -> {
            if (add.isEmpty()) return;
            boolean atBottom = logScroll.getScrollY() + logScroll.getHeight() >= logs.getBottom() - dp(24);
            boolean was = all.isEmpty();
            all.addAll(add);
            if (was || LogLines.cap(all, LogLines.MAX)) render.run();
            else {
                SpannableStringBuilder b = new SpannableStringBuilder();
                for (String l : add) if (LogLines.matches(l, filter[0])) b.append(span.apply(l));
                if (b.length() > 0) { logs.append(b); logs.setAlpha(.6f); logs.animate().alpha(1f).setDuration(Ui.motionEnabled() ? 250 : 0).start(); }
            }
            if (atBottom) logScroll.post(() -> logScroll.fullScroll(View.FOCUS_DOWN));
        };
        final int[] gen = {0}; // bumped on every Follow toggle so an in-flight poll can't start a second loop
        tick[0] = () -> {
            if (!follow[0] || !sheet.isShowing() || !visible || network.isShutdown()) return;
            String at = cursor[0]; int g = gen[0];
            network.submit(() -> {
                HubApi.LogPage pg = null;
                try { pg = hub.opsLogs(service.name, 200, at); } catch (Exception ignored) { }
                HubApi.LogPage r = pg;
                runOnUiThread(() -> {
                    if (g != gen[0]) return;
                    if (r != null && sheet.isShowing() && follow[0]) {
                        cursor[0] = LogLines.cursor(at, r.cursor);
                        if (at.isEmpty()) { all.clear(); }
                        append.accept(r.lines);
                    }
                    if (follow[0]) handler.postDelayed(tick[0], 2000);
                });
            });
        };
        java.util.function.Consumer<Boolean> setFollow = on -> {
            follow[0] = on;
            gen[0]++;
            followPill.setTextColor(on ? Ui.GREEN : Ui.SUB);
            followPill.setText(on ? "Following" : "Follow");
            followPill.setContentDescription(on ? "Following logs, tap to stop" : "Follow logs live");
            live.setVisibility(on ? View.VISIBLE : View.GONE);
            handler.removeCallbacks(tick[0]);
            if (on) { if (loaded[0]) handler.postDelayed(tick[0], 2000); }
        };
        followPill.setOnClickListener(v -> setFollow.accept(!follow[0]));
        stopLogs = () -> setFollow.accept(false);
        find.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) { }
            @Override public void onTextChanged(CharSequence s, int a, int b, int c) { }
            @Override public void afterTextChanged(Editable e) { filter[0] = e.toString().trim(); if (loaded[0]) render.run(); }
        });
        sheet.setOnDismissListener(d -> { setFollow.accept(false); stopLogs = null; });
        network.submit(() -> {
            try {
                double[][] h = hub.history("up:" + service.name, "24h");
                String d = "Uptime last 24 h: " + (h.length == 0 ? "no data yet" : String.format(Locale.US, "%.0f%% up", History.uptimePct(h)));
                runOnUiThread(() -> { if (h.length == 0) { upLabel.setVisibility(View.GONE); up.setVisibility(View.GONE); } else { upLabel.setText(d); up.setData(h, 86400, d); } });
            } catch (Exception e) { runOnUiThread(() -> { upLabel.setVisibility(View.GONE); up.setVisibility(View.GONE); }); }
            try {
                HubApi.LogPage pg = hub.opsLogs(service.name, 200, "");
                runOnUiThread(() -> {
                    if (!sheet.isShowing()) return;
                    cursor[0] = pg.cursor;
                    loaded[0] = true;
                    all.addAll(pg.lines);
                    LogLines.cap(all, LogLines.MAX);
                    render.run();
                    logScroll.post(() -> logScroll.fullScroll(View.FOCUS_DOWN));
                    if (follow[0]) handler.postDelayed(tick[0], 2000);
                });
            } catch (Exception e) {
                runOnUiThread(() -> { if (sheet.isShowing()) logs.setText(HermesApi.friendly(e)); });
            }
        });
    }

    static boolean stoppedContainer(HubApi.Service s) {
        String st = s.state.toLowerCase(Locale.ROOT);
        return "docker".equalsIgnoreCase(s.kind) && (st.startsWith("exited") || st.startsWith("created"));
    }

    private void restart(String name, String action) {
        network.submit(() -> {
            try {
                String state = hub.opsRestart(name, action);
                runOnUiThread(() -> { toast((action.equals("start") ? "Started " : "Restarted ") + name + (state.isEmpty() ? "" : " · " + state)); load(false); });
            } catch (Exception e) {
                String msg = HermesApi.friendly(e);
                runOnUiThread(() -> toast(msg.contains("allowlist") ? "Add " + name + " to /opt/hermes-hub/ops-allowlist.txt to control it from the phone" : "Failed: " + msg));
            }
        });
    }

    private void diagnose(HubApi.Service service) {
        network.submit(() -> {
            try {
                List<String> lines = hub.opsLogs(service.name, 60);
                String prompt = "Diagnose why " + service.name + " is " + service.state + ". Recent logs:\n```\n"
                        + String.join("\n", lines.subList(Math.max(0, lines.size() - 60), lines.size())) + "\n```";
                runOnUiThread(() -> startActivity(new Intent(this, MainActivity.class)
                        .putExtra(MainActivity.EXTRA_OPEN_CHAT, true)
                        .putExtra(ChatActivity.EXTRA_PROMPT, prompt)
                        .putExtra(ChatActivity.EXTRA_AUTOSEND, true)
                        .putExtra(MainActivity.EXTRA_PROFILE_SECRET, store.intentSecret())));
            } catch (Exception e) { runOnUiThread(() -> toast("Could not load logs: " + HermesApi.friendly(e))); }
        });
    }

    // ---------------------------------------------------------------- platforms

    private void platformsCard() {
        JSONObject platforms = health == null ? null : health.optJSONObject("platforms");
        if (platforms == null || HermesApi.keys(platforms).isEmpty()) return;
        LinearLayout card = section("PLATFORMS", "platforms");
        for (String name : HermesApi.keys(platforms)) {
            String state = SystemHealth.platformState(platforms, name);
            String shown = state.isEmpty() ? "unknown" : state;
            card.addView(row(SystemHealth.platformLevel(state), TimeFmt.humanizeKey(name), shown, false, null, null), match(-2));
        }
        body.addView(card, match(-2));
    }

    // ---------------------------------------------------------------- capabilities

    private void capabilitiesCard() {
        LinearLayout card = section("CAPABILITIES", "capabilities");
        if (caps == null) { card.addView(capsError != null ? errorRow(capsError) : skeleton(), match(-2)); body.addView(card, match(-2)); return; }
        JSONObject c = caps;
        String platform = SystemHealth.text(c, "platform");
        if (!platform.isEmpty()) kv(card, "Platform", platform);

        JSONObject features = c.optJSONObject("features");
        if (features != null && HermesApi.keys(features).size() > 0) {
            subLabel(card, "FEATURES");
            card.addView(featureGrid(features), match(-2));
        }

        Object endpoints = c.opt("endpoints");
        int endpointCount = -1;
        if (endpoints instanceof org.json.JSONArray) endpointCount = ((org.json.JSONArray) endpoints).length();
        else if (endpoints instanceof JSONObject) endpointCount = HermesApi.keys((JSONObject) endpoints).size();
        if (endpointCount >= 0) kvMono(card, "Endpoints", String.valueOf(endpointCount));
        body.addView(card, match(-2));
    }

    private View featureGrid(JSONObject features) {
        GridLayout grid = new GridLayout(this);
        grid.setColumnCount(2);
        grid.setPadding(dp(6), 0, dp(6), dp(8));
        for (String key : HermesApi.keys(features)) {
            Object v = features.opt(key);
            if (!(v instanceof Boolean)) continue;
            boolean on = (Boolean) v;
            LinearLayout cell = new LinearLayout(this);
            cell.setGravity(Gravity.CENTER_VERTICAL);
            cell.setPadding(dp(8), dp(6), dp(8), dp(6));
            TextView mark = Ui.text(this, on ? "✓" : "✕", 13, on ? Ui.GREEN : Ui.FAINT, true);
            mark.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
            cell.addView(mark, new LinearLayout.LayoutParams(dp(18), -2));
            TextView label = Ui.text(this, TimeFmt.humanizeKey(key), 13, on ? Ui.TEXT : Ui.FAINT, false);
            cell.addView(label, new LinearLayout.LayoutParams(0, -2, 1));
            cell.setContentDescription(label.getText() + ", " + (on ? "Available" : "Unavailable"));
            GridLayout.LayoutParams p = new GridLayout.LayoutParams();
            p.width = 0;
            p.columnSpec = GridLayout.spec(GridLayout.UNDEFINED, 1f);
            grid.addView(cell, p);
        }
        return grid;
    }

    // ---------------------------------------------------------------- connection / test

    private void connectionCard() {
        LinearLayout card = section("CONNECTION", "connection");
        kvMono(card, "Endpoint", store.endpoint().isEmpty() ? "Not configured" : store.endpoint());
        if (hubOn) kvMono(card, "Hub", store.hubUrl());
        LinearLayout row = new LinearLayout(this);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setMinimumHeight(dp(48));
        row.setPadding(dp(14), dp(10), dp(14), dp(12));
        row.setBackground(Ui.ripple(this, android.graphics.Color.TRANSPARENT, 12));
        TextView label = Ui.body(this, "Test connection", Ui.TEXT);
        row.addView(label, new LinearLayout.LayoutParams(0, -2, 1));
        testRow = Ui.text(this, testing ? "TESTING…" : "TAP TO TEST", 12, Ui.BLUE, true);
        testRow.setLetterSpacing(.05f);
        row.addView(testRow);
        row.setOnClickListener(v -> testConnection());
        card.addView(row, match(-2));
        body.addView(card, match(-2));
    }

    private void testConnection() {
        if (testing) return;
        testing = true;
        testRow.setText("TESTING…");
        testRow.setTextColor(Ui.SUB);
        network.submit(() -> {
            long start = System.nanoTime();
            HermesApi.Result r = api.health();
            long ms = (System.nanoTime() - start) / 1_000_000;
            runOnUiThread(() -> {
                testing = false;
                if (r.ok) { testRow.setText(ms + " ms"); testRow.setTextColor(Ui.GREEN); }
                else { testRow.setText("Failed"); testRow.setTextColor(Ui.RED); toast(r.text); }
            });
        });
    }

    // ---------------------------------------------------------------- shared row builders

    private LinearLayout section(String title, String key) {
        TextView label = Ui.label(this, title, Ui.SUB);
        LinearLayout.LayoutParams lp = match(-2);
        lp.setMargins(dp(10), dp(20), 0, dp(8));
        body.addView(label, lp);
        anchors.put(key, label);
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setBackground(Ui.card(this, 1));
        Ui.elevate(card, 1);
        return card;
    }

    private void subLabel(LinearLayout card, String title) {
        TextView label = Ui.label(this, title, Ui.FAINT);
        LinearLayout.LayoutParams lp = match(-2);
        lp.setMargins(dp(16), dp(10), dp(16), dp(4));
        card.addView(label, lp);
    }

    /** Words value. */
    private void kv(LinearLayout card, String key, String value) { kvRow(card, key, value, Ui.TEXT, false); }

    /** Number, id, path or version value in mono. */
    private void kvMono(LinearLayout card, String key, String value) { kvRow(card, key, value, Ui.TEXT, true); }

    private void kvColored(LinearLayout card, String key, String value, int color) { kvRow(card, key, value, color, true); }

    private void kvRow(LinearLayout card, String key, String value, int color, boolean mono) {
        LinearLayout row = new LinearLayout(this);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(16), dp(8), dp(16), dp(8));
        TextView k = Ui.caption(this, key, Ui.SUB);
        LinearLayout.LayoutParams kp = new LinearLayout.LayoutParams(0, -2, 1);
        row.addView(k, kp);
        String shown = value == null || value.isEmpty() ? "—" : value;
        TextView v = mono ? Ui.num(this, shown, 13, color) : Ui.body(this, shown, color);
        v.setMaxLines(3);
        v.setGravity(Gravity.END);
        // Long values (timestamps, paths) take the row's width below the label instead of crushing it.
        if (shown.length() > 26) {
            row.setOrientation(LinearLayout.VERTICAL); row.setGravity(Gravity.START);
            kp.width = -1; kp.weight = 0; k.setLayoutParams(kp);
            v.setGravity(Gravity.START); v.setPadding(0, dp(2), 0, 0);
            row.addView(v, new LinearLayout.LayoutParams(-1, -2));
        } else {
            row.addView(v, new LinearLayout.LayoutParams(-2, -2));
        }
        row.setContentDescription(key + ", " + shown);
        card.addView(row, match(-2));
    }

    /** Label and a rolling value over a bar (6dp, 8dp when critical); percent 0-100 fills it. detail is an optional mono caption. */
    private void meter(LinearLayout card, String key, String label, String value, String detail, double percent, int color, int barDp) {
        LinearLayout row = new LinearLayout(this); row.setOrientation(LinearLayout.VERTICAL);
        row.setPadding(dp(16), dp(10), dp(16), dp(8));
        LinearLayout line = new LinearLayout(this);
        line.setGravity(Gravity.CENTER_VERTICAL);
        TextView name = Ui.caption(this, label, Ui.SUB);
        name.setSingleLine(true);
        name.setEllipsize(TextUtils.TruncateAt.END);
        line.addView(name, new LinearLayout.LayoutParams(0, -2, 1));
        DigitRoll val = roll(key, 15, color == Ui.BLUE ? Ui.TEXT : color);
        line.addView(val, new LinearLayout.LayoutParams(-2, -2));
        later.add(() -> val.setValue(value));
        row.addView(line);
        if (detail != null && !detail.isEmpty()) row.addView(Ui.num(this, detail, 12, Ui.FAINT));
        ProgressBar bar = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        bar.setMax(1000); bar.setProgress((int) Math.min(1000, Math.max(0, percent * 10)));
        bar.setProgressTintList(ColorStateList.valueOf(color));
        bar.setProgressBackgroundTintList(ColorStateList.valueOf(Ui.tier(3)));
        bar.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        LinearLayout.LayoutParams p = match(barDp); p.topMargin = dp(8); row.addView(bar, p);
        row.setContentDescription(label + ", " + value + (detail == null || detail.isEmpty() ? "" : ", " + detail));
        card.addView(row, match(-2));
    }

    /** Placeholder bars while a source loads, so the layout doesn't jump when data arrives. */
    private View skeleton() {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(14), dp(14), dp(14), dp(10));
        box.setContentDescription("Loading");
        for (int w : new int[]{70, 45, 58}) {
            LinearLayout line = new LinearLayout(this);
            View bar = new View(this);
            bar.setBackground(Ui.round(this, Ui.tier(2), 6));
            line.addView(bar, new LinearLayout.LayoutParams(0, dp(12), w));
            line.addView(new View(this), new LinearLayout.LayoutParams(0, dp(12), 100 - w));
            LinearLayout.LayoutParams lp = match(-2); lp.bottomMargin = dp(10);
            box.addView(line, lp);
        }
        return box;
    }

    private TextView note(String text) {
        TextView t = Ui.caption(this, text, Ui.SUB);
        t.setGravity(Gravity.CENTER_VERTICAL);
        t.setPadding(dp(14), dp(12), dp(14), dp(12));
        return t;
    }

    private View errorRow(String error) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.VERTICAL);
        row.setMinimumHeight(dp(48));
        row.setPadding(dp(14), dp(10), dp(14), dp(14));
        row.setBackground(Ui.ripple(this, android.graphics.Color.TRANSPARENT, 12));
        row.setOnClickListener(v -> load(true));
        TextView t = Ui.body(this, error == null ? "Couldn't load" : error, Ui.RED);
        t.setLineSpacing(0, 1.15f);
        row.addView(t);
        TextView r = Ui.label(this, "Tap to retry", Ui.BLUE);
        r.setPadding(0, dp(8), 0, 0);
        row.addView(r);
        row.setContentDescription((error == null ? "Couldn't load" : error) + ". Tap to retry");
        return row;
    }

    /** Plain status dot; only the hero's dot beats. */
    private View dot(int color) {
        View v = new View(this);
        v.setBackground(Ui.circle(color));
        v.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        return v;
    }

    private static int sevColor(int severity) { return severity == OpsText.CALM ? Ui.GREEN : severity == OpsText.WATCH ? Ui.AMBER : Ui.RED; }
    private static int levelColor(int level) { return level == SystemHealth.NONE ? Ui.FAINT : sevColor(OpsText.severity(level)); }
    private static int thresholdColor(int level) { return level == 2 ? Ui.RED : level == 1 ? Ui.AMBER : Ui.BLUE; }

    private void toast(String s) { Toast.makeText(this, s == null || s.isEmpty() ? "Done" : s, Toast.LENGTH_SHORT).show(); }
    @Override public void onBackPressed() { if (!Nav.leaveFromTab(this, Layout.Tab.SYSTEM)) Ui.back(this); }
}
