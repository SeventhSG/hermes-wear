package cloud.stormlabs.hermescompanion;

import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.drawable.GradientDrawable;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * The ChatGPT-agent-style on-screen presence for a running {@link PhoneAgent} task: a compact bar
 * near the top of the screen (Hermes avatar, step status, Stop — expanding to Allow/Stop in Ask
 * mode) drawn as a {@code TYPE_ACCESSIBILITY_OVERLAY} window, plus a brief highlight of the next
 * tap target. Neither window can take focus or becomes the app's active window, so both stay out
 * of describeScreen() and target matching for free; the bar is briefly made non-touchable around a
 * raw gesture dispatch so a tap near the top of the screen still reaches the app underneath.
 */
final class AgentOverlay implements PhoneAgent.Listener {
    private final HermesAccessibilityService service;
    private final PhoneAgent agent;
    private final WindowManager wm;
    private final Handler main = new Handler(Looper.getMainLooper());

    private final View barRoot;
    private final WindowManager.LayoutParams barParams;
    private boolean barAdded;
    private final TextView statusLine, stepLine, stopButton, actionButton;
    private final View progressFill;
    private final LinearLayout card;
    private boolean slid;

    private View highlightRoot;
    private boolean highlightAdded;

    AgentOverlay(HermesAccessibilityService service, PhoneAgent agent) {
        this.agent = agent;
        this.service = service;
        this.wm = (WindowManager) service.getSystemService(Context.WINDOW_SERVICE);
        service.overlay = this;

        FrameLayout root = new FrameLayout(service);
        root.setPadding(dp(12), 0, dp(12), dp(12));
        card = new LinearLayout(service);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setBackground(Ui.card(service, 3));
        Ui.elevate(card, 3);
        card.setPadding(dp(12), dp(10), dp(10), dp(10));

        LinearLayout row = new LinearLayout(service);
        row.setGravity(Gravity.CENTER_VERTICAL);
        View mark = Ui.hermesLogo(service, 30);
        mark.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        row.addView(mark, new LinearLayout.LayoutParams(dp(30), dp(30)));
        LinearLayout textCol = new LinearLayout(service);
        textCol.setOrientation(LinearLayout.VERTICAL);
        textCol.setPadding(dp(10), 0, dp(8), 0);
        statusLine = Ui.title(service, "Working", Ui.TEXT);
        statusLine.setTextSize(14);
        statusLine.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
        stepLine = Ui.caption(service, "", Ui.SUB);
        stepLine.setMaxLines(4);
        stepLine.setEllipsize(TextUtils.TruncateAt.END);
        textCol.addView(statusLine);
        textCol.addView(stepLine);
        row.addView(textCol, new LinearLayout.LayoutParams(0, -2, 1));
        stopButton = pill("Stop", Ui.RED, "Stop Hermes");
        stopButton.setTextColor(Color.WHITE);
        stopButton.setBackground(Ui.press(service, Ui.RED, Ui.RED, 99));
        stopButton.setTextSize(13);
        stopButton.setOnClickListener(v -> { agent.stop(); });
        row.addView(stopButton);
        card.addView(row, new LinearLayout.LayoutParams(-1, -2));

        FrameLayout track = new FrameLayout(service);
        track.setBackground(Ui.round(service, Ui.LINE, 99));
        progressFill = new View(service);
        progressFill.setBackground(Ui.round(service, Ui.BLUE, 99));
        progressFill.setPivotX(0f);
        progressFill.setScaleX(0f);
        track.addView(progressFill, new FrameLayout.LayoutParams(-1, -1));
        LinearLayout.LayoutParams tp = new LinearLayout.LayoutParams(-1, dp(3));
        tp.topMargin = dp(8); tp.setMarginEnd(dp(2));
        card.addView(track, tp);

        actionButton = pill("ALLOW", Ui.GREEN, "Allow this step");
        actionButton.setVisibility(View.GONE);
        LinearLayout actionRow = new LinearLayout(service);
        actionRow.setGravity(Gravity.END);
        actionRow.setPadding(0, dp(8), 0, 0);
        actionRow.addView(actionButton);
        card.addView(actionRow, new LinearLayout.LayoutParams(-1, -2));

        FrameLayout.LayoutParams cardParams = new FrameLayout.LayoutParams(-1, -2);
        cardParams.topMargin = dp(8);
        root.addView(card, cardParams);
        barRoot = root;

        barParams = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE, PixelFormat.TRANSLUCENT);
        barParams.gravity = Gravity.TOP;
    }

    private TextView pill(String label, int color, String description) {
        TextView t = Ui.text(service, label, 11, color, true);
        t.setGravity(Gravity.CENTER);
        t.setPadding(dp(12), dp(8), dp(12), dp(8));
        t.setMinHeight(dp(48));
        t.setMinWidth(dp(64));
        t.setBackground(Ui.press(service, Color.TRANSPARENT, color, 99));
        t.setContentDescription(description);
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(-2, -2);
        p.setMargins(dp(8), 0, 0, 0);
        t.setLayoutParams(p);
        return t;
    }

    private void show() {
        if (barAdded) return;
        try { wm.addView(barRoot, barParams); barAdded = true; } catch (Throwable ignored) { }
        if (barAdded && !slid) {
            slid = true;
            if (Ui.motionEnabled()) {
                card.setAlpha(0f); card.setTranslationY(-dp(24));
                card.animate().alpha(1f).translationY(0).setDuration(320).setInterpolator(Ui.EASE_OUT).start();
            }
        }
    }

    private void progress(float fraction) {
        if (Ui.motionEnabled()) progressFill.animate().scaleX(fraction).setDuration(180).setInterpolator(Ui.EASE_OUT).start();
        else progressFill.setScaleX(fraction);
    }

    void remove() {
        main.removeCallbacksAndMessages(null);
        if (barAdded) { try { wm.removeView(barRoot); } catch (Throwable ignored) { } barAdded = false; }
        removeHighlight();
        if (service.overlay == this) service.overlay = null;
    }

    /** Briefly excluded from touch/gesture dispatch so a synthetic tap near the top reaches the app. */
    void beforeGesture() {
        if (!barAdded) return;
        barParams.flags |= WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE;
        try { wm.updateViewLayout(barRoot, barParams); } catch (Throwable ignored) { }
    }

    void afterGesture() {
        if (!barAdded) return;
        barParams.flags &= ~WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE;
        try { wm.updateViewLayout(barRoot, barParams); } catch (Throwable ignored) { }
    }

    /** Screenshots for vision models must never show Hermes' own overlay. */
    void hideForCapture() {
        barRoot.setVisibility(View.INVISIBLE);
        if (highlightAdded) highlightRoot.setVisibility(View.INVISIBLE);
    }

    void showAfterCapture() {
        barRoot.setVisibility(View.VISIBLE);
        if (highlightAdded) highlightRoot.setVisibility(View.VISIBLE);
    }

    // ── PhoneAgent.Listener ─────────────────────────────────────────────────────────────────────

    @Override public void onStep(int step, int cap, DeviceActions.Action action, boolean askMode) {
        main.post(() -> {
            show();
            statusLine.setText("Working · step " + step + (cap > 0 ? "/" + cap : "") + (action.fastStep ? " · fast" : ""));
            stepLine.setText(stepWords(action, appLabel()));
            actionButton.setVisibility(View.GONE);
            stopButton.setVisibility(View.VISIBLE);
            progress(progress(step, cap));
        });
    }

    @Override public void onPendingConfirmation(DeviceActions.Action action, Runnable allow, Runnable stop) {
        main.post(() -> {
            show();
            statusLine.setText("Needs your OK");
            stepLine.setText(stepWords(action, appLabel()));
            actionButton.setText("Allow");
            actionButton.setTextColor(Ui.GREEN);
            actionButton.setBackground(Ui.press(service, Color.TRANSPARENT, Ui.GREEN, 99));
            actionButton.setContentDescription("Allow this step");
            actionButton.setOnClickListener(v -> {
                // Show progress at once; the next onStep/onFinished can be seconds away.
                statusLine.setText("Working");
                actionButton.setVisibility(View.GONE);
                allow.run();
            });
            actionButton.setVisibility(View.VISIBLE);
        });
    }

    @Override public void onFinished(String summary, boolean ok, boolean needsScreenContent) {
        main.removeCallbacksAndMessages(null);
        main.post(() -> {
            show();
            removeHighlight();
            statusLine.setText(finishedHeadline(ok));
            stepLine.setText(summary == null ? "" : summary);
            stopButton.setVisibility(View.GONE);
            progress(ok ? 1f : progressFill.getScaleX());
            actionButton.setText(needsScreenContent ? "Turn on" : "Open chat");
            actionButton.setTextColor(Ui.BLUE);
            actionButton.setBackground(Ui.press(service, Color.TRANSPARENT, Ui.BLUE, 99));
            actionButton.setContentDescription(needsScreenContent ? "Turn on Screen content" : "Open chat");
            actionButton.setOnClickListener(v -> {
                if (needsScreenContent) new SettingsStore(service).allowScreenContent(true);
                openChat();
            });
            actionButton.setVisibility(View.VISIBLE);
            main.postDelayed(this::remove, ok ? 5000 : 10000);
        });
    }

    @Override public void onBeforeTap(Rect bounds, Runnable proceed) {
        main.post(() -> {
            showHighlight(bounds);
            main.postDelayed(() -> { removeHighlight(); proceed.run(); }, 400);
        });
    }

    private void openChat() {
        String sessionId = agent.sessionId;
        try {
            Intent i = new Intent(service, MainActivity.class).putExtra(MainActivity.EXTRA_OPEN_CHAT, true)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
            if (sessionId != null) i.putExtra(MainActivity.EXTRA_SESSION, sessionId);
            Notifier.forProfile(service, i, agent.profileId);
            service.startActivity(i);
        } catch (Throwable ignored) { }
        remove();
    }

    private void showHighlight(Rect bounds) {
        if (bounds == null) return;
        if (highlightRoot == null) {
            highlightRoot = new View(service);
            GradientDrawable d = new GradientDrawable();
            d.setColor(Color.TRANSPARENT);
            d.setStroke(dp(3), Ui.BLUE);
            d.setCornerRadius(dp(6));
            highlightRoot.setBackground(d);
        }
        WindowManager.LayoutParams p = new WindowManager.LayoutParams(
                Math.max(dp(24), bounds.width()), Math.max(dp(24), bounds.height()),
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
                PixelFormat.TRANSLUCENT);
        p.gravity = Gravity.TOP | Gravity.START;
        p.x = bounds.left; p.y = bounds.top;
        try {
            if (highlightAdded) wm.updateViewLayout(highlightRoot, p);
            else { wm.addView(highlightRoot, p); highlightAdded = true; }
        } catch (Throwable ignored) { }
    }

    private void removeHighlight() {
        if (!highlightAdded) return;
        try { wm.removeView(highlightRoot); } catch (Throwable ignored) { }
        highlightAdded = false;
    }

    private String appLabel() {
        String pkg = HermesAccessibilityService.currentApp();
        if (pkg == null || pkg.isEmpty() || "Unknown app".equals(pkg)) return "";
        try {
            PackageManager pm = service.getPackageManager();
            return String.valueOf(pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)));
        } catch (Throwable t) { return ""; }
    }

    private int dp(int v) { return Ui.dp(service, v); }

    // ── pure parts: unit-tested directly ───────────────────────────────────────────────────────────

    /** The step in plain words, e.g. "Tapping "Timer" in Clock". No Android dependency. */
    static String stepWords(DeviceActions.Action action, String appLabel) {
        String in = appLabel == null || appLabel.isEmpty() ? "" : " in " + appLabel;
        switch (action.type) {
            case "tap": return "Tapping “" + action.str("target") + "”" + in;
            case "type": return "Typing into “" + action.str("target") + "”" + in;
            case "scroll": return "Scrolling " + action.str("direction") + in;
            case "back": return "Going back";
            case "home": return "Going to the home screen";
            case "recents": return "Opening recent apps";
            case "notifications": return "Opening notifications";
            case "open_app": return action.describe();
            default: return action.describe() + in;
        }
    }

    /** Progress bar fill 0..1: step/cap when the cap is known, else an asymptote that never quite fills. */
    static float progress(int step, int cap) {
        if (step <= 0) return 0f;
        return cap > 0 ? Math.min(1f, step / (float) cap) : 1f - 1f / (step + 1f);
    }

    static String finishedHeadline(boolean ok) { return ok ? "Done" : "Stopped"; }
}
