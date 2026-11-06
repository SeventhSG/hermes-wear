package cloud.stormlabs.hermescompanion;

import android.Manifest;
import android.animation.ValueAnimator;
import android.app.Activity;
import android.app.Dialog;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.media.MediaRecorder;
import android.os.Build;
import android.os.Bundle;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.os.VibratorManager;
import android.text.InputType;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;
import android.view.animation.DecelerateInterpolator;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.net.URI;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * Essentials → Capture lane. One screen, three moves: hold to record, tap to type, tap a row to
 * expand it and send Hermes a command about that capture.
 *
 * The list is patched in place: sync() only ever updates existing rows for ids we already hold and
 * prepends genuinely new ids with a fade. Nothing is ever cleared, so the scroll position and the
 * expanded row survive every refresh — a 304 must be invisible.
 */
public final class EssentialsActivity extends Activity {
    public static final String EXTRA_BASE = "essentials_base", EXTRA_TOKEN = "essentials_token";
    /** Launched from the widget/tile: open already recording, or with the composer up. */
    public static final String EXTRA_RECORD = "essentials_record", EXTRA_COMPOSE = "essentials_compose";

    private static final int MIC_REQ = 71;
    private static final long HOLD_MS = 400L, POLL_MS = 4000L, TICK_MS = 100L;
    private static final int MAX_ROWS = 240;
    private static final String[] QUICK_CHIPS = {"task", "note", "file", "remind"};
    private static final String[] KINDS = {"voice", "photo", "note", "file", "mix"};
    private static final String DATE_HEADER_TAG = "date-header";

    private final java.util.concurrent.ExecutorService network = java.util.concurrent.Executors.newSingleThreadExecutor();
    private java.util.concurrent.ExecutorService getPool() { return network; }

    private static final String DOCK_HINT = "Hold to record · tap to type";
    private EssentialsApi api;
    private String base = "", token = "";
    private double cursor = 0;
    private boolean resumed = false, syncing = false, uploading = false, loadingFirst = false;
    private int commandSeq = 0;

    private ScrollView scroller;
    private LinearLayout list, stateBox, banner;
    private View screenControlReminder;
    private TextView stateText, stateAction, countView, offlineChip, dockHint, timerView;
    private TextView bannerText, bannerAction;
    private Runnable bannerRun;
    private ProgressBar dockSpinner;
    private ImageButton captureButton;

    private MediaRecorder recorder;
    private File recordingFile;
    private boolean recording = false, holdArmed = false;
    private View pulseRing;
    private ValueAnimator pulseAnimator;
    /** Set when a widget/tile asked to record and the microphone still needs granting. */
    private boolean autoRecordOnGrant = false;
    private long recordStart = 0L;
    private Runnable holdTask, pollTask, tickTask;

    private String pendingText = null, pendingTextId = null;
    private final java.util.Map<String, String> audioIds = new java.util.HashMap<>();
    private String uploadLabel = "";

    private final java.util.LinkedHashMap<String, Row> rows = new java.util.LinkedHashMap<>();
    private final ArrayList<String> order = new ArrayList<>();

    private int dp(int n) { return Ui.dp(this, n); }
    private LinearLayout.LayoutParams lp(int w, int h) { return new LinearLayout.LayoutParams(w < 0 ? w : dp(w), h < 0 ? h : dp(h)); }
    private static String n(String s, String fallback) { return s == null || s.isEmpty() ? fallback : s; }
    private static String msg(Exception e) { String m = e == null ? null : e.getMessage(); return m == null || m.isEmpty() ? (e == null ? "unknown error" : e.getClass().getSimpleName()) : m; }

    @Override public void onCreate(Bundle b) {
        super.onCreate(b);
        CrashReporter.install(this);
        try {
            Ui.legacyWindowInsets(this);
            getWindow().setStatusBarColor(Ui.BAR);
            getWindow().setNavigationBarColor(Ui.BG);
            readConnection();
            pollTask = () -> pollNow();
            buildUi();
            loadFirst();
        } catch (Throwable t) {
            // A window that never got its content shows the bare theme background (grey), which
            // looks exactly like a hang. Always leave a readable message instead.
            CrashReporter.show(this, t);
        }
    }

    @Override protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        readConnection();
        loadFirst();
        applyLaunchAction();
    }

    /** Fire the widget/tile request once the lane is on screen. */
    private void applyLaunchAction() {
        Intent i = getIntent();
        if (i == null) return;
        final boolean record = i.getBooleanExtra(EXTRA_RECORD, false);
        final boolean compose = i.getBooleanExtra(EXTRA_COMPOSE, false);
        if (!record && !compose) return;
        // Consume them: a configuration change must not start a second recording.
        i.removeExtra(EXTRA_RECORD);
        i.removeExtra(EXTRA_COMPOSE);
        new android.os.Handler(getMainLooper()).postDelayed(() -> {
            try {
                if (record) {
                    if (hasMic()) { setArmed(true); beginRecording(); }
                    else { autoRecordOnGrant = true; requestMic(); }
                } else openComposer(null);
            } catch (Throwable t) {
                CrashReporter.show(this, t);
            }
        }, 350);
    }

    /** Base/token come in as extras; the capture lane's own saved settings are the fallback — never
     *  the chat lane's endpoint/key, which would silently point the capture list at the wrong API. */
    private void readConnection() {
        Intent i = getIntent();
        String b = i == null ? null : i.getStringExtra(EXTRA_BASE);
        String t = i == null ? null : i.getStringExtra(EXTRA_TOKEN);
        if (b == null || b.isEmpty() || t == null || t.isEmpty()) {
            try {
                SettingsStore store = new SettingsStore(this);
                if (b == null || b.isEmpty()) b = store.essentialsBase();
                if (t == null || t.isEmpty()) t = store.essentialsToken();
            } catch (Exception ignored) { /* settings are optional: the unconnected state below covers this path */ }
        }
        base = n(b, "").trim();
        token = n(t, "").trim();
        api = base.isEmpty() || token.isEmpty() ? null : new EssentialsApi(base, token);
    }

    private LinearLayout.LayoutParams rowParams() { LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(-1, -2); p.setMargins(0, 0, 0, dp(8)); return p; }

    // ── layout ────────────────────────────────────────────────────────────────────────────────────

    private void buildUi() {
        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(Ui.BG);
        LinearLayout column = new LinearLayout(this);
        column.setOrientation(LinearLayout.VERTICAL);
        root.addView(column, new FrameLayout.LayoutParams(-1, -1));
        column.addView(buildHeader(), lp(-1, 60));
        screenControlReminder = Ui.screenControlReminder(this, new SettingsStore(this));
        column.addView(screenControlReminder);
        column.addView(buildBanner(), lp(-1, -2));

        FrameLayout layer = new FrameLayout(this);
        scroller = new ScrollView(this);
        scroller.setFillViewport(true);
        scroller.setClipToPadding(false);
        // Breathing room when scrolled, no gap at rest: content still scrolls under this padding.
        scroller.setPadding(0, dp(12), 0, 0);
        list = new LinearLayout(this);
        list.setOrientation(LinearLayout.VERTICAL);
        list.setPadding(dp(9), dp(10), dp(9), dp(10));
        scroller.addView(list);
        layer.addView(scroller, new FrameLayout.LayoutParams(-1, -1));
        stateBox = new LinearLayout(this);
        stateBox.setOrientation(LinearLayout.VERTICAL);
        stateBox.setGravity(Gravity.CENTER);
        stateBox.setPadding(dp(28), dp(24), dp(28), dp(24));
        stateBox.setVisibility(View.GONE);
        stateText = Ui.text(this, "", 13, Ui.SUB, false);
        stateText.setGravity(Gravity.CENTER);
        stateBox.addView(stateText, new LinearLayout.LayoutParams(-1, -2));
        stateAction = Ui.text(this, "", 13, Ui.BLUE, true);
        stateAction.setGravity(Gravity.CENTER);
        stateAction.setLetterSpacing(.06f);
        stateAction.setPadding(0, dp(14), 0, 0);
        stateAction.setVisibility(View.GONE);
        stateBox.addView(stateAction, new LinearLayout.LayoutParams(-1, -2));
        layer.addView(stateBox, new FrameLayout.LayoutParams(-1, -1));
        column.addView(layer, new LinearLayout.LayoutParams(-1, 0, 1));

        column.addView(buildDock(), lp(-1, -2));
        Nav.tabBar(this, column, Layout.Tab.CAPTURE);
        Ui.keepAboveKeyboard(root, column);
        setContentView(root);
    }

    private View buildHeader() {
        LinearLayout bar = new LinearLayout(this);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setPadding(dp(2), 0, dp(8), 0);
        bar.setBackgroundColor(Ui.BAR);
        ImageButton back = Ui.icon(this, R.drawable.ic_back, "Back");
        back.setOnClickListener(v -> onBackPressed());
        bar.addView(back, lp(48, 52));
        LinearLayout names = new LinearLayout(this);
        names.setOrientation(LinearLayout.VERTICAL);
        names.setPadding(dp(6), 0, 0, 0);
        names.addView(Ui.text(this, "Inbox", 18, Ui.TEXT, true));
        countView = Ui.text(this, "Loading…", 12, Ui.SUB, false);
        names.addView(countView);
        bar.addView(names, new LinearLayout.LayoutParams(0, -2, 1));
        offlineChip = Ui.text(this, "OFFLINE", 12, Ui.AMBER, true);
        offlineChip.setLetterSpacing(.08f);
        offlineChip.setPadding(dp(8), dp(3), dp(8), dp(3));
        offlineChip.setBackground(Ui.round(this, 0x33e7b24c, 99));
        offlineChip.setVisibility(View.GONE);
        bar.addView(offlineChip, new LinearLayout.LayoutParams(-2, -2));
        TextView live = Ui.text(this, "Live notes", 12, Ui.BLUE, true);
        live.setGravity(Gravity.CENTER);
        live.setContentDescription("Open Live notes: transcribe a lecture or meeting with live insights");
        live.setPadding(dp(12), dp(6), dp(12), dp(6));
        live.setBackground(Ui.stroke(this, Ui.RAISED, Ui.LINE, 99));
        live.setOnClickListener(v -> startActivity(new Intent(this, LiveNotesActivity.class)));
        LinearLayout.LayoutParams lp2 = new LinearLayout.LayoutParams(-2, -2);
        lp2.setMargins(0, 0, dp(6), 0);
        bar.addView(live, lp2);
        TextView hermes = Ui.text(this, "Hermes", 12, Ui.BLUE, true);
        hermes.setGravity(Gravity.CENTER);
        hermes.setContentDescription("Open the Hermes chat lane");
        hermes.setPadding(dp(12), dp(6), dp(12), dp(6));
        hermes.setBackground(Ui.stroke(this, Ui.RAISED, Ui.LINE, 99));
        hermes.setOnClickListener(v -> startActivity(new Intent(this, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP).putExtra(MainActivity.EXTRA_OPEN_CHAT, true)));
        LinearLayout.LayoutParams hp = new LinearLayout.LayoutParams(-2, -2);
        hp.setMargins(0, 0, dp(6), 0);
        bar.addView(hermes, hp);
        TextView refresh = Ui.text(this, "⟳", 22, Ui.SUB, false);
        refresh.setGravity(Gravity.CENTER);
        refresh.setContentDescription("Refresh captures");
        refresh.setBackground(Ui.ripple(this, Color.TRANSPARENT, 99));
        refresh.setOnClickListener(v -> { hideState(); reload(); });
        bar.addView(refresh, lp(46, 46));
        return bar;
    }

    private View buildBanner() {
        banner = new LinearLayout(this);
        banner.setGravity(Gravity.CENTER_VERTICAL);
        banner.setPadding(dp(12), dp(8), dp(8), dp(8));
        banner.setBackground(Ui.stroke(this, Ui.SURFACE, 0x66ed6b72, 12));
        LinearLayout.LayoutParams bp = new LinearLayout.LayoutParams(-1, -2);
        bp.setMargins(dp(9), dp(9), dp(9), 0);
        banner.setLayoutParams(bp);
        bannerText = Ui.text(this, "", 12, Ui.TEXT, false);
        bannerText.setLineSpacing(0, 1.12f);
        banner.addView(bannerText, new LinearLayout.LayoutParams(0, -2, 1));
        bannerAction = Ui.text(this, "RETRY", 12, Ui.BLUE, true);
        bannerAction.setLetterSpacing(.06f);
        bannerAction.setGravity(Gravity.CENTER);
        bannerAction.setPadding(dp(10), dp(6), dp(10), dp(6));
        bannerAction.setBackground(Ui.ripple(this, Color.TRANSPARENT, 10));
        bannerAction.setOnClickListener(v -> { Runnable r = bannerRun; if (r != null) r.run(); });
        banner.addView(bannerAction, new LinearLayout.LayoutParams(-2, -2));
        TextView close = Ui.text(this, "✕", 16, Ui.SUB, false);
        close.setGravity(Gravity.CENTER);
        close.setPadding(dp(10), dp(4), dp(10), dp(4));
        close.setOnClickListener(v -> hideBanner());
        banner.addView(close, new LinearLayout.LayoutParams(-2, -2));
        banner.setVisibility(View.GONE);
        return banner;
    }

    private View buildDock() {
        LinearLayout dock = new LinearLayout(this);
        dock.setOrientation(LinearLayout.VERTICAL);
        dock.setGravity(Gravity.CENTER_HORIZONTAL);
        dock.setBackgroundColor(Ui.BG);
        dock.setPadding(dp(10), dp(8), dp(10), dp(12));
        View divider = new View(this);
        divider.setBackgroundColor(Ui.LINE);
        dock.addView(divider, lp(-1, 1));
        LinearLayout.LayoutParams hintP = new LinearLayout.LayoutParams(-1, -2);
        hintP.setMargins(0, dp(8), 0, 0);
        dockHint = Ui.text(this, DOCK_HINT, 12, Ui.FAINT, true);
        dockHint.setGravity(Gravity.CENTER);
        dockHint.setLetterSpacing(.02f);
        dock.addView(dockHint, hintP);
        FrameLayout line = new FrameLayout(this);
        pulseRing = new View(this);
        pulseRing.setBackground(Ui.circle(Ui.RED));
        pulseRing.setVisibility(View.INVISIBLE);
        pulseRing.setAlpha(0f);
        FrameLayout.LayoutParams prp = new FrameLayout.LayoutParams(dp(72), dp(72), Gravity.CENTER);
        line.addView(pulseRing, prp);
        captureButton = new ImageButton(this);
        captureButton.setImageResource(R.drawable.ic_mic);
        captureButton.setImageTintList(android.content.res.ColorStateList.valueOf(Color.WHITE));
        captureButton.setContentDescription("Hold to record a voice capture");
        captureButton.setBackground(Ui.circle(Ui.BLUE));
        captureButton.setPadding(dp(20), dp(20), dp(20), dp(20));
        captureButton.setElevation(dp(2));
        captureButton.setFocusable(false);
        captureButton.setOnTouchListener((v, e) -> captureTouch(e));
        FrameLayout.LayoutParams cbp = new FrameLayout.LayoutParams(dp(72), dp(72), Gravity.CENTER);
        line.addView(captureButton, cbp);
        timerView = Ui.text(this, "", 13, Ui.RED, true);
        timerView.setGravity(Gravity.END | Gravity.CENTER_VERTICAL);
        FrameLayout.LayoutParams tp = new FrameLayout.LayoutParams(-2, -2, Gravity.END | Gravity.CENTER_VERTICAL);
        tp.setMargins(0, 0, dp(6), 0);
        line.addView(timerView, tp);
        dockSpinner = new ProgressBar(this);
        dockSpinner.setIndeterminateTintList(android.content.res.ColorStateList.valueOf(Ui.BLUE));
        dockSpinner.setVisibility(View.INVISIBLE);
        FrameLayout.LayoutParams sp = new FrameLayout.LayoutParams(dp(22), dp(22), Gravity.START | Gravity.CENTER_VERTICAL);
        sp.setMargins(dp(6), 0, 0, 0);
        line.addView(dockSpinner, sp);
        dock.addView(line, new LinearLayout.LayoutParams(-1, dp(80)));
        return dock;
    }

    // ── list state helpers ────────────────────────────────────────────────────────────────────────

    private void showState(String message, String actionLabel, Runnable action) {
        stateText.setText(message);
        if (actionLabel == null || action == null) {
            stateAction.setVisibility(View.GONE);
            stateAction.setOnClickListener(null);
        } else {
            stateAction.setText(actionLabel);
            stateAction.setVisibility(View.VISIBLE);
            stateAction.setOnClickListener(v -> action.run());
        }
        stateBox.setVisibility(View.VISIBLE);
        scroller.setVisibility(View.GONE);
    }

    private void hideState() {
        stateBox.setVisibility(View.GONE);
        scroller.setVisibility(View.VISIBLE);
    }

    private void updateEmpty() {
        if (order.isEmpty()) {
            if (stateBox.getVisibility() != View.VISIBLE)
                showState("Your inbox is empty\nHold the mic to record a voice note, or tap it to type one", null, null);
        } else {
            hideState();
        }
    }

    private void showBanner(String message, String actionLabel, Runnable action) {
        bannerText.setText(message);
        bannerRun = action;
        if (actionLabel == null || action == null) bannerAction.setVisibility(View.GONE);
        else { bannerAction.setText(actionLabel); bannerAction.setVisibility(View.VISIBLE); }
        Ui.showFade(banner);
    }

    private void hideBanner() { banner.setVisibility(View.GONE); bannerRun = null; }

    private void setOffline(boolean offline) {
        if (offline) Ui.showFade(offlineChip);
        else offlineChip.setVisibility(View.GONE);
    }

    private void toast(String s) { Toast.makeText(this, s == null ? "Done" : s, Toast.LENGTH_SHORT).show(); }

    // ── loading and in-place refresh ──────────────────────────────────────────────────────────────

    private void loadFirst() {
        if (api == null) {
            showState("Not connected to Essentials\nAdd the capture URL and token", "SET UP", this::openConnectionSettings);
            countView.setText("Not connected");
            return;
        }
        if (loadingFirst) return;
        loadingFirst = true;
        if (order.isEmpty()) showState("Loading captures…", null, null);
        getPool().submit(() -> {
            try {
                List<EssentialsApi.Item> items = api.items(60);
                runOnUiThread(() -> {
                    loadingFirst = false;
                    if (items != null && !items.isEmpty()) { applyItems(items, true); hideBanner(); }
                    else updateEmpty();
                    setOffline(false);
                    updateCount();
                    pollNow();
                });
            } catch (Exception e) {
                runOnUiThread(() -> {
                    loadingFirst = false;
                    if (order.isEmpty()) showState("Couldn't load captures · " + msg(e), "RETRY", this::loadFirst);
                    else showBanner("Sync failed · " + msg(e), "RETRY", this::loadFirst);
                    updateCount();
                });
            }
        });
    }

    private void reload() {
        if (api == null) { retryConnection(); return; }
        loadFirst();
    }

    /** Opens the shared connection screen so Essentials can be added or changed after onboarding. */
    private void openConnectionSettings() {
        startActivity(new Intent(this, ConnectionActivity.class));
    }

    /** Re-reads the extras: the parent may have been handed a connection after we first drew. */
    private void retryConnection() {
        readConnection();
        if (api == null) {
            showState("Still not connected\nAdd the capture URL and token", "SET UP", this::openConnectionSettings);
            return;
        }
        hideState();
        loadFirst();
    }

    private void startPolling() { resumed = true; pollNow(); }

    private void stopPolling() {
        resumed = false;
        if (list != null) list.removeCallbacks(pollTask);
    }

    private void schedulePoll() {
        if (!resumed || list == null) return;
        list.removeCallbacks(pollTask);
        list.postDelayed(pollTask, POLL_MS);
    }

    private void pollNow() {
        if (!resumed || api == null || syncing) { schedulePoll(); return; }
        syncing = true;
        final double since = cursor;
        getPool().submit(() -> {
            try {
                EssentialsApi.Sync sync = api.sync(since);
                runOnUiThread(() -> { syncing = false; applySync(sync); setOffline(false); updateCount(); schedulePoll(); });
            } catch (Exception e) {
                runOnUiThread(() -> { syncing = false; setOffline(true); schedulePoll(); });
            }
        });
    }

    private void applySync(EssentialsApi.Sync sync) {
        if (sync == null) return;
        if (sync.version > 0 && sync.version < cursor) return; // stale payload, keeps rows untouched
        if (sync.version > cursor) cursor = sync.version;
        List<EssentialsApi.Item> items = sync.items;
        if (items == null || items.isEmpty()) return; // 304-style answer: touch nothing at all
        applyItems(items, true);
    }

    /**
     * The one mutation path for the list: patch rows we already own, prepend unknown ids, and keep
     * the anchor row pinned to its pixel position. Never clears, never re-creates a row.
     */
    private void applyItems(List<EssentialsApi.Item> items, boolean allowInsert) {
        if (items == null || items.isEmpty()) { updateCount(); return; }
        final View anchor = firstRowView();
        final int anchorTop = anchor == null ? 0 : anchor.getTop();
        final int scrollBefore = scroller.getScrollY();
        ArrayList<Row> fresh = new ArrayList<>();
        for (EssentialsApi.Item item : items) {
            if (item == null || item.id == null || item.id.isEmpty()) continue;
            Row existing = rows.get(item.id);
            if (existing != null) { updateRow(existing, item); continue; }
            if (!allowInsert) continue;
            Row row = new Row(item);
            rows.put(item.id, row);
            fresh.add(row);
        }
        for (int i = fresh.size() - 1; i >= 0; i--) {
            Row row = fresh.get(i);
            list.addView(row.root, 0, rowParams());
            order.add(0, row.id);
        }
        trim();
        regroupHeaders();
        updateEmpty();
        updateCount();
        if (!fresh.isEmpty()) {
            if (anchor != null) {
                list.post(() -> {
                    int delta = anchor.getTop() - anchorTop;
                    if (delta != 0) scroller.scrollTo(0, Math.max(0, scrollBefore + delta));
                });
            }
            for (Row row : fresh) {
                row.root.setAlpha(0f);
                row.root.animate().alpha(1f).setDuration(200).start();
            }
        }
    }

    private void trim() {
        while (order.size() > MAX_ROWS) {
            int last = order.size() - 1;
            Row row = rows.remove(order.remove(last));
            if (row != null) list.removeView(row.root);
        }
    }

    /** The first non-header child, i.e. the newest capture row — used to pin the scroll position
     *  across a refresh even when a date header sits above it. */
    private View firstRowView() {
        if (list == null) return null;
        for (int i = 0; i < list.getChildCount(); i++) {
            View child = list.getChildAt(i);
            if (!DATE_HEADER_TAG.equals(child.getTag())) return child;
        }
        return null;
    }

    /**
     * Groups the list under "Today / Yesterday / weekday / date" headers, like a messaging app.
     * Rebuilds only the header views: every Row keeps its identity, its listeners and, if expanded,
     * its open panel — regrouping never recreates or reorders a row.
     */
    private void regroupHeaders() {
        if (list == null) return;
        for (int i = list.getChildCount() - 1; i >= 0; i--) {
            if (DATE_HEADER_TAG.equals(list.getChildAt(i).getTag())) list.removeViewAt(i);
        }
        String prevLabel = null;
        int at = 0;
        for (String id : order) {
            Row row = rows.get(id);
            if (row == null) continue;
            EssentialsApi.Item item = row.item;
            double ts = item == null ? 0 : (item.ts > 0 ? item.ts : item.updatedAt);
            String label = dayLabel(ts);
            if (!label.equals(prevLabel)) {
                list.addView(dateHeaderView(label), at, new LinearLayout.LayoutParams(-1, -2));
                prevLabel = label;
                at++;
            }
            at++;
        }
    }

    private View dateHeaderView(String label) {
        TextView v = Ui.text(this, label, 12, Ui.SUB, true);
        v.setLetterSpacing(.03f);
        v.setPadding(dp(3), dp(14), dp(3), dp(6));
        v.setTag(DATE_HEADER_TAG);
        return v;
    }

    /** "Today" / "Yesterday" / a weekday name for the last week / a date beyond that. */
    private static String dayLabel(double ts) {
        if (ts <= 0) return "Earlier";
        double ms = ts < 100000000000d ? ts * 1000d : ts;
        Calendar when = Calendar.getInstance();
        when.setTimeInMillis((long) ms);
        Calendar now = Calendar.getInstance();
        int diff = daysBetween(when, now);
        if (diff == 0) return "Today";
        if (diff == 1) return "Yesterday";
        if (diff > 1 && diff < 7) return new SimpleDateFormat("EEEE", Locale.getDefault()).format(new Date((long) ms));
        String pattern = when.get(Calendar.YEAR) == now.get(Calendar.YEAR) ? "d MMMM" : "d MMMM yyyy";
        return new SimpleDateFormat(pattern, Locale.getDefault()).format(new Date((long) ms));
    }

    private static int daysBetween(Calendar when, Calendar now) {
        Calendar a = (Calendar) when.clone();
        Calendar b = (Calendar) now.clone();
        a.set(Calendar.HOUR_OF_DAY, 0); a.set(Calendar.MINUTE, 0); a.set(Calendar.SECOND, 0); a.set(Calendar.MILLISECOND, 0);
        b.set(Calendar.HOUR_OF_DAY, 0); b.set(Calendar.MINUTE, 0); b.set(Calendar.SECOND, 0); b.set(Calendar.MILLISECOND, 0);
        return (int) Math.round((b.getTimeInMillis() - a.getTimeInMillis()) / 86400000.0);
    }

    private void updateCount() {
        if (countView == null) return;
        if (api == null) { countView.setText("Not connected"); return; }
        Calendar now = Calendar.getInstance();
        int today = 0;
        for (Row row : rows.values()) {
            EssentialsApi.Item item = row.item;
            if (item == null) continue;
            double ts = item.ts > 0 ? item.ts : item.updatedAt;
            if (ts <= 0) continue;
            Calendar c = Calendar.getInstance();
            c.setTimeInMillis((long) (ts * 1000));
            if (c.get(Calendar.YEAR) == now.get(Calendar.YEAR) && c.get(Calendar.DAY_OF_YEAR) == now.get(Calendar.DAY_OF_YEAR)) today++;
        }
        int total = order.size();
        countView.setText(today + " today · " + total + " total");
    }

    // ── rows ──────────────────────────────────────────────────────────────────────────────────────

    private final class Row {
        final String id;
        final LinearLayout root;
        final TextView glyph, title, body, meta, statusChip, commandBadge;
        LinearLayout panel, commandsBox, actionsBox;
        HorizontalScrollView actionsScroll;
        TextView triageLabel, triageText, transcriptLabel, transcriptText, noteLabel, noteText, actionsLabel, commandsLabel, panelError, commandSend;
        EditText commandInput;
        String pendingCommandText, pendingCommandId;
        EssentialsApi.Item item;
        boolean expanded = false;

        Row(EssentialsApi.Item item) {
            this.id = item.id;
            this.item = item;
            root = new LinearLayout(EssentialsActivity.this);
            root.setOrientation(LinearLayout.VERTICAL);
            root.setBackground(Ui.stroke(EssentialsActivity.this, Ui.SURFACE, Ui.LINE, 14));
            root.setPadding(dp(11), dp(11), dp(11), dp(11));

            LinearLayout top = new LinearLayout(EssentialsActivity.this);
            top.setGravity(Gravity.TOP);

            glyph = Ui.text(EssentialsActivity.this, "◇", 15, Color.WHITE, true);
            glyph.setGravity(Gravity.CENTER);
            glyph.setBackground(Ui.circle(0xff6f64c2));
            top.addView(glyph, new LinearLayout.LayoutParams(dp(36), dp(36)));

            LinearLayout mid = new LinearLayout(EssentialsActivity.this);
            mid.setOrientation(LinearLayout.VERTICAL);
            mid.setPadding(dp(10), 0, dp(8), 0);
            title = Ui.text(EssentialsActivity.this, "", 14, Ui.TEXT, true);
            title.setMaxLines(2);
            title.setEllipsize(TextUtils.TruncateAt.END);
            title.setLineSpacing(0, 1.12f);
            mid.addView(title, new LinearLayout.LayoutParams(-1, -2));
            body = Ui.text(EssentialsActivity.this, "", 13, Ui.SUB, false);
            body.setMaxLines(2);
            body.setEllipsize(TextUtils.TruncateAt.END);
            body.setLineSpacing(0, 1.1f);
            body.setVisibility(View.GONE);
            LinearLayout.LayoutParams bodyP = new LinearLayout.LayoutParams(-1, -2);
            bodyP.setMargins(0, dp(2), 0, 0);
            mid.addView(body, bodyP);
            meta = Ui.text(EssentialsActivity.this, "", 12, Ui.FAINT, false);
            LinearLayout.LayoutParams metaP = new LinearLayout.LayoutParams(-1, -2);
            metaP.setMargins(0, dp(4), 0, 0);
            mid.addView(meta, metaP);
            top.addView(mid, new LinearLayout.LayoutParams(0, -2, 1));

            LinearLayout right = new LinearLayout(EssentialsActivity.this);
            right.setOrientation(LinearLayout.VERTICAL);
            right.setGravity(Gravity.CENTER_HORIZONTAL);
            LinearLayout.LayoutParams rightP = new LinearLayout.LayoutParams(-2, -2);
            rightP.setMargins(dp(6), 0, 0, 0);
            statusChip = Ui.text(EssentialsActivity.this, "", 12, Ui.SUB, true);
            statusChip.setGravity(Gravity.CENTER);
            statusChip.setLetterSpacing(.03f);
            statusChip.setPadding(dp(9), dp(4), dp(9), dp(4));
            right.addView(statusChip, new LinearLayout.LayoutParams(-2, -2));
            commandBadge = Ui.text(EssentialsActivity.this, "", 12, Ui.SUB, true);
            commandBadge.setGravity(Gravity.CENTER);
            commandBadge.setVisibility(View.GONE);
            LinearLayout.LayoutParams badgeP = new LinearLayout.LayoutParams(dp(20), dp(20));
            badgeP.setMargins(0, dp(6), 0, 0);
            right.addView(commandBadge, badgeP);
            top.addView(right, rightP);

            root.addView(top, new LinearLayout.LayoutParams(-1, -2));
            root.setClickable(true);
            root.setOnClickListener(v -> toggle(this));
            fill(this, item);
        }

        LinearLayout panel() {
            if (panel == null) {
                panel = buildPanel(this);
                root.addView(panel, new LinearLayout.LayoutParams(-1, -2));
            }
            return panel;
        }
    }

    private LinearLayout buildPanel(Row row) {
        LinearLayout panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setVisibility(View.GONE);
        LinearLayout.LayoutParams pp = new LinearLayout.LayoutParams(-1, -2);
        pp.setMargins(0, dp(9), 0, 0);

        row.triageLabel = label("HERMES");
        row.triageText = value(14, Ui.TEXT);
        panel.addView(row.triageLabel, new LinearLayout.LayoutParams(-1, -2));
        panel.addView(row.triageText, new LinearLayout.LayoutParams(-1, -2));
        row.transcriptLabel = label("TRANSCRIPT");
        row.transcriptText = value(13, Ui.SUB);
        row.transcriptText.setTextIsSelectable(true);
        panel.addView(row.transcriptLabel, new LinearLayout.LayoutParams(-1, -2));
        panel.addView(row.transcriptText, new LinearLayout.LayoutParams(-1, -2));
        row.noteLabel = label("NOTE");
        row.noteText = value(13, Ui.SUB);
        row.noteText.setTextIsSelectable(true);
        panel.addView(row.noteLabel, new LinearLayout.LayoutParams(-1, -2));
        panel.addView(row.noteText, new LinearLayout.LayoutParams(-1, -2));

        row.actionsLabel = label("ACTIONS");
        panel.addView(row.actionsLabel, new LinearLayout.LayoutParams(-1, -2));
        row.actionsScroll = new HorizontalScrollView(this);
        row.actionsScroll.setHorizontalScrollBarEnabled(false);
        row.actionsBox = new LinearLayout(this);
        row.actionsBox.setOrientation(LinearLayout.HORIZONTAL);
        row.actionsBox.setGravity(Gravity.CENTER_VERTICAL);
        row.actionsScroll.addView(row.actionsBox);
        panel.addView(row.actionsScroll, new LinearLayout.LayoutParams(-1, -2));

        row.commandsLabel = label("COMMANDS");
        panel.addView(row.commandsLabel, new LinearLayout.LayoutParams(-1, -2));
        row.commandsBox = new LinearLayout(this);
        row.commandsBox.setOrientation(LinearLayout.VERTICAL);
        panel.addView(row.commandsBox, new LinearLayout.LayoutParams(-1, -2));

        LinearLayout quick = new LinearLayout(this);
        quick.setOrientation(LinearLayout.HORIZONTAL);
        quick.setPadding(0, dp(6), 0, 0);
        for (String chip : QUICK_CHIPS) {
            TextView c = Ui.text(this, chip, 12, Ui.BLUE, true);
            c.setGravity(Gravity.CENTER);
            c.setPadding(dp(11), dp(6), dp(11), dp(6));
            c.setOnClickListener(v -> {
                row.panel();
                row.commandInput.setText(chip + " ");
                row.commandInput.setSelection(row.commandInput.length());
                focusField(row.commandInput);
            });
            LinearLayout.LayoutParams cp = new LinearLayout.LayoutParams(-2, -2);
            cp.setMargins(0, 0, dp(6), 0);
            c.setBackground(Ui.stroke(this, Ui.RAISED, Ui.LINE, 99));
            c.setTextColor(Ui.BLUE);
            quick.addView(c, cp);
        }
        panel.addView(quick, new LinearLayout.LayoutParams(-1, -2));

        LinearLayout bar = new LinearLayout(this);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setPadding(0, dp(6), 0, 0);
        row.commandInput = new EditText(this);
        row.commandInput.setTextColor(Ui.TEXT);
        row.commandInput.setHintTextColor(Ui.FAINT);
        row.commandInput.setHint("Command for Hermes…");
        row.commandInput.setTextSize(14);
        row.commandInput.setSingleLine(true);
        row.commandInput.setImeOptions(EditorInfo.IME_ACTION_SEND);
        row.commandInput.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
        row.commandInput.setPadding(dp(12), 0, dp(12), 0);
        row.commandInput.setBackground(Ui.stroke(this, Ui.RAISED, Ui.LINE, 12));
        row.commandInput.setOnEditorActionListener((v, action, event) -> {
            boolean enter = event != null && event.getKeyCode() == KeyEvent.KEYCODE_ENTER && event.getAction() == KeyEvent.ACTION_DOWN;
            if (action == EditorInfo.IME_ACTION_SEND || action == EditorInfo.IME_ACTION_DONE || enter) {
                sendCommand(row);
                return true;
            }
            return false;
        });
        bar.addView(row.commandInput, new LinearLayout.LayoutParams(0, dp(44), 1));
        row.commandSend = Ui.text(this, "SEND", 12, Ui.BLUE, true);
        row.commandSend.setGravity(Gravity.CENTER);
        row.commandSend.setLetterSpacing(.06f);
        row.commandSend.setBackground(Ui.ripple(this, Color.TRANSPARENT, 10));
        row.commandSend.setOnClickListener(v -> sendCommand(row));
        LinearLayout.LayoutParams sendP = new LinearLayout.LayoutParams(dp(66), dp(44));
        sendP.setMargins(dp(6), 0, 0, 0);
        bar.addView(row.commandSend, sendP);
        panel.addView(bar, new LinearLayout.LayoutParams(-1, -2));

        row.panelError = Ui.text(this, "", 12, Ui.RED, false);
        row.panelError.setVisibility(View.GONE);
        row.panelError.setPadding(0, dp(6), 0, 0);
        panel.addView(row.panelError, new LinearLayout.LayoutParams(-1, -2));

        panel.setLayoutParams(pp);
        return panel;
    }

    private TextView label(String text) {
        TextView v = Ui.text(this, text, 12, Ui.FAINT, true);
        v.setLetterSpacing(.08f);
        v.setPadding(0, dp(9), 0, dp(3));
        return v;
    }

    private TextView value(float size, int color) {
        TextView v = Ui.text(this, "", size, color, false);
        v.setLineSpacing(0, 1.14f);
        return v;
    }

    private void toggle(Row row) {
        row.expanded = !row.expanded;
        if (row.expanded) {
            row.panel().setVisibility(View.VISIBLE);
            fill(row, row.item);
            row.root.setBackground(Ui.stroke(this, Ui.RAISED, 0x555a9cf4, 14));
        } else {
            if (row.panel != null) row.panel.setVisibility(View.GONE);
            row.root.setBackground(Ui.stroke(this, Ui.SURFACE, Ui.LINE, 14));
        }
    }

    /** Updates one existing row in place. Never touches the command field, so drafts survive a sync. */
    private void updateRow(Row row, EssentialsApi.Item item) {
        if (item == null) return;
        row.item = item;
        fill(row, item);
    }

    private void fill(Row row, EssentialsApi.Item item) {
        if (item == null) return;
        String kind = kindOf(item.kind);
        row.glyph.setText(kindGlyph(kind));
        row.glyph.setBackground(Ui.circle(kindColor(kind)));

        row.title.setText(rowTitle(item, kind));
        String secondary = !blank(item.title) && !blank(item.text) ? item.text.trim() : "";
        // A voice capture has no title and no text: its content IS the transcript. Showing it only
        // while the capture is still untriaged made every finished voice row read "(empty capture)".
        if (blank(secondary) && !blank(item.transcript) && !blank(item.title)) secondary = firstLine(item.transcript);
        row.body.setText(secondary);
        // A voice row's transcript is often identical to its title: saying it twice is noise.
        boolean repeated = !secondary.isEmpty() && secondary.equalsIgnoreCase(row.title.getText().toString().trim());
        row.body.setVisibility(secondary.isEmpty() || repeated ? View.GONE : View.VISIBLE);

        String word = statusWord(item.status);
        String metaLine = clock(item.ts > 0 ? item.ts : item.updatedAt) + " · " + kindLabel(kind);
        if ("failed".equals(word) && !blank(item.lastError)) metaLine += " · " + clip(item.lastError, 70);
        row.meta.setText(metaLine);

        int tint = statusTint(word);
        row.statusChip.setText(statusLabel(word));
        row.statusChip.setTextColor(tint);
        row.statusChip.setBackground(Ui.round(this, (tint & 0x00ffffff) | 0x2a000000, 99));

        int commands = item.commands == null ? 0 : item.commands.size();
        if (commands > 0) {
            row.commandBadge.setText(String.valueOf(commands));
            row.commandBadge.setBackground(Ui.circle(Ui.pick(0xff333b46, Ui.RAISED)));
            row.commandBadge.setVisibility(View.VISIBLE);
        } else {
            row.commandBadge.setVisibility(View.GONE);
        }

        if (row.panel != null) fillPanel(row, item);
    }

    /** The row's headline: a title, a URL rendered as domain › path, a voice transcript, or the
     *  capture's own text — in that order, never a raw truncated URL. */
    private String rowTitle(EssentialsApi.Item item, String kind) {
        if (!blank(item.title)) return looksLikeUrl(item.title) ? formatUrl(item.title.trim()) : item.title.trim();
        if (KINDS[0].equals(kind)) return !blank(item.transcript) ? firstLine(item.transcript) : "Voice note";
        if (!blank(item.text)) return looksLikeUrl(item.text) ? formatUrl(item.text.trim()) : firstLine(item.text);
        if (!blank(item.transcript)) return firstLine(item.transcript);
        return "(empty capture)";
    }

    private static boolean looksLikeUrl(String s) {
        String v = s == null ? "" : s.trim();
        return v.startsWith("http://") || v.startsWith("https://");
    }

    /** "http://github.com/affaan-m/hermes" → "github.com › affaan-m/hermes", so a link reads as a
     *  place rather than truncating mid-path with the domain lost off the front. */
    private static String formatUrl(String raw) {
        try {
            URI uri = URI.create(raw);
            String host = uri.getHost();
            if (blank(host)) return clip(raw, 60);
            if (host.startsWith("www.")) host = host.substring(4);
            String path = uri.getRawPath();
            String tail = blank(path) || "/".equals(path) ? "" : clip(path.substring(1), 34);
            return tail.isEmpty() ? host : host + " › " + tail;
        } catch (Exception e) {
            return clip(raw, 60);
        }
    }

    private static String kindLabel(String kind) {
        if (KINDS[0].equals(kind)) return "voice";
        if (KINDS[1].equals(kind)) return "photo";
        if (KINDS[2].equals(kind)) return "note";
        if (KINDS[3].equals(kind)) return "file";
        return "mixed";
    }

    private static String statusLabel(String word) {
        if ("received".equals(word)) return "queued";
        if ("working".equals(word)) return "processing";
        return word;
    }

    private static int statusTint(String word) {
        if ("done".equals(word)) return Ui.GREEN;
        if ("working".equals(word)) return Ui.AMBER;
        if ("failed".equals(word)) return Ui.RED;
        return Ui.SUB;
    }

    private void fillPanel(Row row, EssentialsApi.Item item) {
        if (item == null) return;
        String triage = item.triage == null ? "" : item.triage.trim();
        String note = item.note == null ? "" : item.note.trim();
        if (triage.isEmpty() && "working".equals(statusWord(item.status))) triage = "Hermes is triaging this capture…";
        section(row.triageLabel, row.triageText, triage);
        section(row.transcriptLabel, row.transcriptText, item.transcript);
        section(row.noteLabel, row.noteText, note);
        row.actionsBox.removeAllViews();
        List<String> actions = item.actions;
        if (actions == null || actions.isEmpty()) {
            row.actionsLabel.setVisibility(View.GONE);
            row.actionsScroll.setVisibility(View.GONE);
        } else {
            row.actionsLabel.setVisibility(View.VISIBLE);
            row.actionsScroll.setVisibility(View.VISIBLE);
            for (String action : actions) {
                TextView chip = Ui.text(this, n(action, "action"), 12, Ui.SUB, false);
                chip.setPadding(dp(11), dp(6), dp(11), dp(6));
                chip.setBackground(Ui.stroke(this, Ui.RAISED, Ui.LINE, 99));
                chip.setOnClickListener(v -> {
                    row.panel();
                    row.commandInput.setText(action + " ");
                    row.commandInput.setSelection(row.commandInput.length());
                    focusField(row.commandInput);
                });
                LinearLayout.LayoutParams cp = new LinearLayout.LayoutParams(-2, -2);
                cp.setMargins(0, 0, dp(6), 0);
                row.actionsBox.addView(chip, cp);
            }
        }

        row.commandsBox.removeAllViews();
        List<EssentialsApi.Command> commands = item.commands;
        if (commands == null || commands.isEmpty()) {
            row.commandsLabel.setVisibility(View.GONE);
        } else {
            row.commandsLabel.setVisibility(View.VISIBLE);
            for (EssentialsApi.Command command : commands) {
                if (command == null) continue;
                LinearLayout.LayoutParams ccp = new LinearLayout.LayoutParams(-1, -2);
                ccp.setMargins(0, dp(3), 0, 0);
                row.commandsBox.addView(commandChip(command), ccp);
            }
        }

        if (row.panelError != null && row.panelError.getVisibility() == View.VISIBLE && !blank(item.lastError)) {
            row.panelError.setVisibility(View.GONE);
        }
    }

    private void section(TextView label, TextView value, String text) {
        String body = text == null ? "" : text.trim();
        boolean has = !body.isEmpty();
        label.setVisibility(has ? View.VISIBLE : View.GONE);
        value.setVisibility(has ? View.VISIBLE : View.GONE);
        if (has) value.setText(body);
    }

    private View commandChip(EssentialsApi.Command command) {
        LinearLayout line = new LinearLayout(this);
        line.setGravity(Gravity.CENTER_VERTICAL);
        line.setPadding(dp(10), dp(6), dp(10), dp(6));
        line.setBackground(Ui.round(this, Ui.RAISED, 10));
        String state = n(command.state, "pending").toLowerCase(Locale.ROOT);
        String tick = state.startsWith("applied") || state.startsWith("done") ? "✓" : state.startsWith("fail") || state.startsWith("error") ? "✕" : "·";
        int tint = tick.equals("✓") ? Ui.GREEN : tick.equals("✕") ? Ui.RED : Ui.AMBER;
        TextView mark = Ui.text(this, tick, 13, tint, true);
        mark.setGravity(Gravity.CENTER);
        line.addView(mark, new LinearLayout.LayoutParams(dp(20), -2));
        TextView text = Ui.text(this, n(command.text, "command"), 12, Ui.TEXT, false);
        text.setMaxLines(2);
        text.setLineSpacing(0, 1.1f);
        line.addView(text, new LinearLayout.LayoutParams(0, -2, 1));
        TextView badge = Ui.text(this, tick.equals("✓") ? "applied" : tick.equals("✕") ? "failed" : "pending", 12, tint, true);
        badge.setLetterSpacing(.05f);
        line.addView(badge, new LinearLayout.LayoutParams(-2, -2));
        return line;
    }

    // ── commands ──────────────────────────────────────────────────────────────────────────────────

    private void sendCommand(Row row) {
        if (row == null || row.commandInput == null) return;
        final String payload = row.commandInput.getText().toString().trim();
        if (payload.isEmpty()) {
            if (row.panelError != null) { row.panelError.setText("Type a command first, or tap a quick chip."); row.panelError.setVisibility(View.VISIBLE); }
            return;
        }
        if (api == null) {
            if (row.panelError != null) { row.panelError.setText("Not connected — the command wasn't sent and your text is still here."); row.panelError.setVisibility(View.VISIBLE); }
            return;
        }
        final String id = row.id;
        if (!payload.equals(row.pendingCommandText)) {
            row.pendingCommandText = payload;
            row.pendingCommandId = freshCommandId();
        }
        final String commandId = row.pendingCommandId;
        if (row.panelError != null) row.panelError.setVisibility(View.GONE);
        row.commandSend.setEnabled(false);
        row.commandSend.setTextColor(Ui.FAINT);
        getPool().submit(() -> {
            try {
                EssentialsApi.Item updated = api.command(id, payload, commandId);
                runOnUiThread(() -> {
                    row.commandSend.setEnabled(true);
                    row.commandSend.setTextColor(Ui.BLUE);
                    if (payload.equals(row.commandInput.getText().toString().trim())) row.commandInput.setText("");
                    row.pendingCommandText = row.pendingCommandId = null;
                    if (row.panelError != null) row.panelError.setVisibility(View.GONE);
                    if (updated != null) updateRow(row, updated);
                    else appendPending(row, payload);
                    toast("Command sent");
                    pollNow();
                });
            } catch (Exception e) {
                runOnUiThread(() -> {
                    row.commandSend.setEnabled(true);
                    row.commandSend.setTextColor(Ui.BLUE);
                    if (row.panelError != null) {
                        row.panelError.setText("Command failed · " + msg(e) + "\nText kept — tap SEND to retry.");
                        row.panelError.setVisibility(View.VISIBLE);
                    }
                    toast("Command not sent");
                });
            }
        });
    }

    /** Server answer had no command list: show the optimistic pending row so the tap is visible. */
    private void appendPending(Row row, String text) {
        if (row.item == null) return;
        row.panel();
        LinearLayout line = new LinearLayout(this);
        line.setGravity(Gravity.CENTER_VERTICAL);
        line.setPadding(dp(10), dp(6), dp(10), dp(6));
        line.setBackground(Ui.round(this, Ui.RAISED, 10));
        TextView mark = Ui.text(this, "·", 13, Ui.AMBER, true);
        mark.setGravity(Gravity.CENTER);
        line.addView(mark, new LinearLayout.LayoutParams(dp(20), -2));
        TextView body = Ui.text(this, text, 12, Ui.TEXT, false);
        body.setMaxLines(2);
        line.addView(body, new LinearLayout.LayoutParams(0, -2, 1));
        TextView badge = Ui.text(this, "pending", 12, Ui.AMBER, true);
        line.addView(badge, new LinearLayout.LayoutParams(-2, -2));
        if (row.commandsBox.getChildCount() == 0) row.commandsLabel.setVisibility(View.VISIBLE);
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(-1, -2);
        p.setMargins(0, dp(3), 0, 0);
        row.commandsBox.addView(line, p);
    }

    private String freshCommandId() {
        commandSeq++;
        return "android-" + System.currentTimeMillis() + "-" + commandSeq;
    }

    // ── capture: hold to record ───────────────────────────────────────────────────────────────────

    private boolean captureTouch(MotionEvent e) {
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                if (!hasMic()) { requestMic(); return true; }
                holdArmed = true;
                holdTask = this::beginRecording;
                captureButton.postDelayed(holdTask, HOLD_MS);
                setArmed(true);
                return true;
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                release();
                return true;
            default:
                return true;
        }
    }

    private void release() {
        if (holdTask != null) { captureButton.removeCallbacks(holdTask); holdTask = null; }
        boolean wasArmed = holdArmed;
        holdArmed = false;
        setArmed(false);
        if (recording) { finishRecording(); return; }
        if (wasArmed) openComposer(null);
    }

    private boolean hasMic() {
        return Build.VERSION.SDK_INT < 23 || checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED;
    }

    private void requestMic() {
        requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO}, MIC_REQ);
    }

    @Override public void onRequestPermissionsResult(int request, String[] permissions, int[] grants) {
        super.onRequestPermissionsResult(request, permissions, grants);
        if (request != MIC_REQ) return;
        if (grants.length > 0 && grants[0] == PackageManager.PERMISSION_GRANTED) {
            dockHint.setText(DOCK_HINT);
            if (autoRecordOnGrant) {
                // The tap came from the widget or the tile: finish what it asked for instead of
                // making him tap again now that the dialog is gone.
                autoRecordOnGrant = false;
                setArmed(true);
                beginRecording();
            } else {
                toast("Microphone allowed — hold the circle to record");
            }
        } else {
            autoRecordOnGrant = false;
            dockHint.setText("Microphone blocked · tap to type instead");
        }
    }

    private void setArmed(boolean armed) {
        captureButton.setBackground(Ui.circle(armed ? Ui.RED : Ui.BLUE));
        captureButton.animate().scaleX(armed ? 1.08f : 1f).scaleY(armed ? 1.08f : 1f).setDuration(120).start();
        if (!armed && !recording) timerView.setText("");
    }

    /** A soft ring that grows and fades around the mic while a capture is live — the only cue once
     *  the finger has stopped moving that the hold is still being heard. */
    private void startPulse() {
        if (pulseRing == null) return;
        stopPulse();
        pulseRing.setVisibility(View.VISIBLE);
        if (!Ui.motionEnabled()) { pulseRing.setScaleX(1.35f); pulseRing.setScaleY(1.35f); pulseRing.setAlpha(.3f); return; }
        pulseAnimator = ValueAnimator.ofFloat(0f, 1f);
        pulseAnimator.setDuration(1100);
        pulseAnimator.setRepeatCount(ValueAnimator.INFINITE);
        pulseAnimator.setInterpolator(new DecelerateInterpolator());
        pulseAnimator.addUpdateListener(a -> {
            float f = (float) a.getAnimatedValue();
            float scale = 1f + f * 0.7f;
            pulseRing.setScaleX(scale);
            pulseRing.setScaleY(scale);
            pulseRing.setAlpha((1f - f) * 0.45f);
        });
        pulseAnimator.start();
    }

    private void stopPulse() {
        if (pulseAnimator != null) { pulseAnimator.cancel(); pulseAnimator = null; }
        if (pulseRing != null) {
            pulseRing.setVisibility(View.INVISIBLE);
            pulseRing.setAlpha(0f);
            pulseRing.setScaleX(1f);
            pulseRing.setScaleY(1f);
        }
    }

    private void beginRecording() {
        holdTask = null;
        if (!holdArmed || recording) return;
        MediaRecorder r = null;
        File file = new File(getCacheDir(), "capture-" + System.currentTimeMillis() + ".m4a");
        WakeWordService.suspendAndWait();
        try {
            r = Build.VERSION.SDK_INT >= 31 ? new MediaRecorder(this) : new MediaRecorder();
            r.setAudioSource(MediaRecorder.AudioSource.MIC);
            r.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4);
            r.setAudioEncoder(MediaRecorder.AudioEncoder.AAC);
            r.setAudioSamplingRate(44100);
            r.setAudioEncodingBitRate(96000);
            r.setOutputFile(file.getAbsolutePath());
            r.prepare();
            r.start();
            recorder = r;
            recordingFile = file;
            recording = true;
            recordStart = System.currentTimeMillis();
            vibrate(24);
            dockHint.setText("Recording · release to send");
            startTicking();
            startPulse();
        } catch (Exception e) {
            try { if (r != null) r.release(); } catch (Exception ignored) { /* nothing to surface, the banner below does */ }
            WakeWordService.resume();
            recorder = null;
            recording = false;
            recordingFile = null;
            setArmed(true);
            stopPulse();
            showBanner("Couldn't start the microphone · " + msg(e), null, null);
            dockHint.setText("Recording unavailable · tap to type instead");
        }
    }

    private void finishRecording() {
        MediaRecorder r = recorder;
        recorder = null;
        recording = false;
        stopTicking();
        stopPulse();
        timerView.setText("");
        File file = recordingFile;
        recordingFile = null;
        if (r != null) {
            try { r.stop(); } catch (Exception e) { /* stop() fails when nothing was written; the size check below decides */ }
            try { r.release(); } catch (Exception ignored) { }
            WakeWordService.resume();
        }
        vibrate(16);
        if (file == null || !file.exists() || file.length() < 1024) {
            if (file != null) file.delete();
            dockHint.setText("Too short to send · tap to type");
            return;
        }
        dockHint.setText(DOCK_HINT);
        uploadAudio(file);
    }

    private void startTicking() {
        tickTask = new Runnable() {
            @Override public void run() {
                if (!recording) return;
                timerView.setText(String.format(Locale.US, "%.1fs", (System.currentTimeMillis() - recordStart) / 1000.0));
                captureButton.removeCallbacks(tickTask);
                captureButton.postDelayed(tickTask, TICK_MS);
            }
        };
        captureButton.postDelayed(tickTask, TICK_MS);
    }

    private void stopTicking() {
        if (tickTask != null) { captureButton.removeCallbacks(tickTask); tickTask = null; }
    }

    /** The recording is never thrown away: on failure the file stays in the cache and RETRY re-sends it. */
    private void uploadAudio(final File file) {
        if (api == null) {
            showBanner("Voice capture saved on device · not connected, so it wasn't sent (" + file.getName() + ")", "RETRY", () -> uploadAudio(file));
            return;
        }
        uploading = true;
        uploadLabel = "Uploading voice capture…";
        dockSpinner.setVisibility(View.VISIBLE);
        dockHint.setText(uploadLabel);
        final String captureId = audioIds.computeIfAbsent(file.getAbsolutePath(), key -> freshCommandId());
        final ArrayList<File> files = new ArrayList<>();
        files.add(file);
        getPool().submit(() -> {
            try {
                EssentialsApi.Item item = api.upload(captureId, "audio", null, "Voice capture", files, this::uploadPercent);
                runOnUiThread(() -> {
                    audioIds.remove(file.getAbsolutePath());
                    uploading = false;
                    dockSpinner.setVisibility(View.INVISIBLE);
                    dockHint.setText(DOCK_HINT);
                    hideBanner();
                    if (item != null) applyItems(single(item), true);
                    else pollNow();
                });
            } catch (Exception e) {
                runOnUiThread(() -> {
                    uploading = false;
                    dockSpinner.setVisibility(View.INVISIBLE);
                    dockHint.setText(DOCK_HINT);
                    showBanner("Voice capture not uploaded · " + msg(e) + "\nKept on device as " + file.getName(), "RETRY", () -> uploadAudio(file));
                });
            }
        });
    }

    /** Progress arrives on the network thread: hop to the UI thread and only while that send is live. */
    private void uploadPercent(int percent) {
        final String label = uploadLabel;
        runOnUiThread(() -> { if (uploading && !label.isEmpty()) dockHint.setText(label + " " + percent + "%"); });
    }

    private List<EssentialsApi.Item> single(EssentialsApi.Item item) {
        ArrayList<EssentialsApi.Item> one = new ArrayList<>();
        one.add(item);
        return one;
    }

    // ── composer ──────────────────────────────────────────────────────────────────────────────────

    private void openComposer(String initial) {
        final Dialog dialog = new Dialog(this);
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);
        dialog.setCancelable(true);
        dialog.setCanceledOnTouchOutside(true);
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setBackground(Ui.round(this, Ui.BAR, 20));
        box.setPadding(dp(16), dp(16), dp(16), dp(14));
        box.addView(Ui.text(this, "New capture", 20, Ui.TEXT, true));
        TextView sub = Ui.text(this, "Saved to the Essentials inbox · Enter sends, back cancels", 12, Ui.SUB, false);
        sub.setPadding(0, dp(4), 0, dp(12));
        box.addView(sub);
        final EditText input = new EditText(this);
        input.setText(initial == null ? "" : initial);
        String start = input.getText().toString();
        dialog.setOnCancelListener(x -> { if (!start.equals(input.getText().toString()))
            getWindow().getDecorView().post(() -> Sheets.confirm(this, "Discard changes?",
                    "Your capture has not been sent.", "Discard", true, () -> {}, dialog::show)); });
        input.setSelection(input.length());
        input.setTextColor(Ui.TEXT);
        input.setHintTextColor(Ui.FAINT);
        input.setHint("Write the note…");
        input.setTextSize(16);
        input.setPadding(dp(12), dp(10), dp(12), dp(10));
        input.setMinLines(3);
        input.setMaxLines(6);
        input.setGravity(Gravity.TOP);
        input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
        input.setImeOptions(EditorInfo.IME_ACTION_SEND);
        input.setBackground(Ui.stroke(this, Ui.SURFACE, Ui.LINE, 12));
        box.addView(input, new LinearLayout.LayoutParams(-1, -2));
        input.setOnEditorActionListener((v, action, event) -> {
            boolean enter = event != null && event.getKeyCode() == KeyEvent.KEYCODE_ENTER && event.getAction() == KeyEvent.ACTION_DOWN;
            if (action == EditorInfo.IME_ACTION_SEND || (enter && !event.isShiftPressed())) {
                submitNote(dialog, input);
                return true;
            }
            return false;
        });
        LinearLayout buttons = new LinearLayout(this);
        buttons.setGravity(Gravity.END);
        buttons.setPadding(0, dp(10), 0, 0);
        TextView cancel = Ui.text(this, "CANCEL", 13, Ui.SUB, true);
        cancel.setGravity(Gravity.CENTER);
        cancel.setLetterSpacing(.06f);
        cancel.setBackground(Ui.ripple(this, Color.TRANSPARENT, 10));
        cancel.setOnClickListener(v -> dialog.cancel());
        buttons.addView(cancel, new LinearLayout.LayoutParams(dp(96), dp(48)));
        TextView send = Ui.text(this, "SEND", 13, Ui.BLUE, true);
        send.setGravity(Gravity.CENTER);
        send.setLetterSpacing(.06f);
        send.setBackground(Ui.ripple(this, Color.TRANSPARENT, 10));
        send.setOnClickListener(v -> submitNote(dialog, input));
        buttons.addView(send, new LinearLayout.LayoutParams(dp(96), dp(48)));
        box.addView(buttons, new LinearLayout.LayoutParams(-1, -2));

        FrameLayout frame = new FrameLayout(this);
        frame.setPadding(dp(8), dp(8), dp(8), dp(8));
        frame.addView(box, new FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM));
        dialog.setContentView(frame);
        Window window = dialog.getWindow();
        if (window != null) {
            window.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
            window.setGravity(Gravity.BOTTOM);
            window.addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);
            WindowManager.LayoutParams attrs = window.getAttributes();
            attrs.width = -1;
            attrs.height = -2;
            attrs.dimAmount = .62f;
            window.setAttributes(attrs);
            window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_VISIBLE | WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
        }
        dialog.show();
        input.requestFocus();
        input.post(() -> {
            InputMethodManager imm = (InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
            if (imm != null) imm.showSoftInput(input, InputMethodManager.SHOW_IMPLICIT);
        });
    }

    private void submitNote(Dialog dialog, EditText input) {
        String text = input.getText().toString().trim();
        if (text.isEmpty()) { toast("Type something first"); return; }
        dialog.dismiss();
        uploadNote(text);
    }

    /** Keeps the typed text in pendingText until a send succeeds, so a failure is never a lost note. */
    private void uploadNote(final String text) {
        if (!text.equals(pendingText)) pendingTextId = freshCommandId();
        pendingText = text;
        if (api == null) {
            showBanner("Note kept on screen · not connected, so it wasn't sent", "RETRY", () -> openComposer(pendingText));
            return;
        }
        uploading = true;
        uploadLabel = "Sending note…";
        dockSpinner.setVisibility(View.VISIBLE);
        dockHint.setText(uploadLabel);
        final String captureId = pendingTextId;
        getPool().submit(() -> {
            try {
                EssentialsApi.Item item = api.upload(captureId, "text", text, null, null, this::uploadPercent);
                runOnUiThread(() -> {
                    uploading = false;
                    dockSpinner.setVisibility(View.INVISIBLE);
                    dockHint.setText(DOCK_HINT);
                    pendingText = pendingTextId = null;
                    hideBanner();
                    if (item != null) applyItems(single(item), true);
                    else pollNow();
                });
            } catch (Exception e) {
                runOnUiThread(() -> {
                    uploading = false;
                    dockSpinner.setVisibility(View.INVISIBLE);
                    dockHint.setText(DOCK_HINT);
                    showBanner("Note not sent · " + msg(e) + "\nText kept — RETRY to reopen it.", "RETRY", () -> openComposer(pendingText));
                });
            }
        });
    }

    private void focusField(final EditText field) {
        field.requestFocus();
        field.post(() -> {
            InputMethodManager imm = (InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
            if (imm != null) imm.showSoftInput(field, InputMethodManager.SHOW_IMPLICIT);
        });
    }

    // ── misc ──────────────────────────────────────────────────────────────────────────────────────

    private void vibrate(int ms) {
        try {
            Vibrator v;
            if (Build.VERSION.SDK_INT >= 31) {
                VibratorManager manager = (VibratorManager) getSystemService(Context.VIBRATOR_MANAGER_SERVICE);
                v = manager == null ? null : manager.getDefaultVibrator();
            } else {
                v = (Vibrator) getSystemService(Context.VIBRATOR_SERVICE);
            }
            if (v == null || !v.hasVibrator()) return;
            if (Build.VERSION.SDK_INT >= 26) v.vibrate(VibrationEffect.createOneShot(ms, VibrationEffect.DEFAULT_AMPLITUDE));
            else v.vibrate(ms);
        } catch (Exception ignored) { /* haptics are decoration: never worth a crash or a dialog */ }
    }


    private static boolean blank(String s) { return s == null || s.trim().isEmpty(); }

    private static String firstLine(String s) {
        if (s == null) return "";
        int cut = s.indexOf('\n');
        String line = cut < 0 ? s : s.substring(0, cut);
        return clip(line.trim(), 160);
    }

    private static String clip(String s, int max) {
        String value = s == null ? "" : s.trim();
        return value.length() <= max ? value : value.substring(0, max) + "…";
    }

    private static String clock(double ts) {
        if (ts <= 0) return "just now";
        double ms = ts < 100000000000d ? ts * 1000d : ts;
        Calendar when = Calendar.getInstance();
        when.setTimeInMillis((long) ms);
        Calendar now = Calendar.getInstance();
        if (when.get(Calendar.YEAR) == now.get(Calendar.YEAR) && when.get(Calendar.DAY_OF_YEAR) == now.get(Calendar.DAY_OF_YEAR))
            return new SimpleDateFormat("HH:mm", Locale.getDefault()).format(new Date((long) ms));
        return new SimpleDateFormat("d MMM HH:mm", Locale.getDefault()).format(new Date((long) ms));
    }

    private static String kindOf(String raw) {
        String k = raw == null ? "" : raw.trim().toLowerCase(Locale.ROOT);
        if (k.startsWith("audio") || k.startsWith("voice") || k.contains("record")) return KINDS[0];
        if (k.startsWith("photo") || k.startsWith("image") || k.contains("picture") || k.contains("camera")) return KINDS[1];
        if (k.startsWith("text") || k.startsWith("note") || k.isEmpty()) return KINDS[2];
        if (k.startsWith("file") || k.startsWith("doc") || k.contains("attach")) return KINDS[3];
        return KINDS[4];
    }

    private static String kindGlyph(String kind) {
        if (KINDS[0].equals(kind)) return "♪";
        if (KINDS[1].equals(kind)) return "▣";
        if (KINDS[2].equals(kind)) return "✎";
        if (KINDS[3].equals(kind)) return "▤";
        return "◇";
    }

    private static int kindColor(String kind) {
        if (KINDS[0].equals(kind)) return 0xff3f82d0;
        if (KINDS[1].equals(kind)) return 0xff7658b6;
        if (KINDS[2].equals(kind)) return 0xff4f9a83;
        if (KINDS[3].equals(kind)) return 0xffb07a47;
        return 0xff6f64c2;
    }

    private static String statusWord(String raw) {
        String s = raw == null ? "" : raw.trim().toLowerCase(Locale.ROOT);
        if (s.isEmpty() || s.startsWith("rec") || s.startsWith("new") || s.startsWith("pending") || s.startsWith("captur")) return "received";
        if (s.startsWith("work") || s.startsWith("run") || s.startsWith("proc") || s.startsWith("triag") || s.startsWith("queued")) return "working";
        if (s.startsWith("done") || s.startsWith("appli") || s.startsWith("complet") || s.startsWith("ok")) return "done";
        if (s.startsWith("fail") || s.startsWith("error")) return "failed";
        return clip(s, 12);
    }

    @Override protected void onResume() {
        super.onResume();
        screenControlReminder.setVisibility(new SettingsStore(this).shouldShowScreenControlReminder(HermesAccessibilityService.listed(this)) ? View.VISIBLE : View.GONE);
        startPolling();
        if (api == null) getPool().submit(() -> {
            readConnection();
            if (api != null) runOnUiThread(() -> {
                if (isFinishing() || isDestroyed()) return;
                hideState();
                loadFirst();
            });
        });
    }

    @Override protected void onPause() {
        super.onPause();
        stopPolling();
        if (recording) finishRecording();
        if (holdTask != null && captureButton != null) { captureButton.removeCallbacks(holdTask); holdTask = null; }
        holdArmed = false;
        if (captureButton != null) setArmed(false);
    }

    @Override public void onBackPressed() {
        boolean collapsed = false;
        for (Row row : rows.values()) if (row.expanded) { toggle(row); collapsed = true; }
        if (collapsed) return;
        if (banner.getVisibility() == View.VISIBLE) { hideBanner(); return; }
        if (Nav.leaveFromTab(this, Layout.Tab.CAPTURE)) return;
        // Capture-first launch lands here as the task root; keep it the app's front door.
        if (isTaskRoot()) moveTaskToBack(true); else finish();
    }

    @Override protected void onDestroy() {
        super.onDestroy();
        stopTicking();
        stopPulse();
        if (recorder != null) {
            try { recorder.stop(); } catch (Exception ignored) { }
            try { recorder.release(); } catch (Exception ignored) { }
            WakeWordService.resume();
            recorder = null;
        }
        network.shutdownNow();
    }
}
