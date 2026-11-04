package cloud.stormlabs.hermescompanion;

import android.app.Activity;
import android.app.Dialog;
import android.content.res.ColorStateList;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;
import java.text.DateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/** A live, optional-field usage panel. Countdown work exists only while the sheet is visible. */
final class UsageSheet {
    private final Activity activity;
    private final Runnable refresh, compact, reset;
    private final LinearLayout content;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final List<Runnable> countdowns = new ArrayList<>();
    private Dialog dialog;
    private UsageState state;
    private final Runnable tick = new Runnable() {
        @Override public void run() {
            if (dialog == null || !dialog.isShowing()) return;
            for (Runnable countdown : countdowns) countdown.run();
            handler.postDelayed(this, 60_000);
        }
    };
    UsageSheet(Activity activity, UsageState state, Runnable refresh, Runnable compact, Runnable reset) {
        this.activity = activity; this.refresh = refresh; this.compact = compact; this.reset = reset;
        content = new LinearLayout(activity); content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(dp(20), dp(4), dp(20), dp(20));
        ScrollView scroll = new ScrollView(activity); scroll.setFillViewport(true); scroll.addView(content);
        update(state);
        dialog = Sheets.tall(activity, "Usage", "This conversation and your account", scroll);
        dialog.setOnDismissListener(d -> handler.removeCallbacks(tick));
        handler.post(tick);
    }
    boolean showing() { return dialog != null && dialog.isShowing(); }
    void dismiss() { if (dialog != null) dialog.dismiss(); }
    void update(UsageState next) {
        state = next == null ? UsageState.parse(null) : next;
        content.removeAllViews(); countdowns.clear();
        if (state.context != null) {
            UsageState.Context c = state.context;
            LinearLayout card = card("Context");
            if (c.percent != null || c.usedTokens != null) {
                String big = c.percent == null ? "" : ("estimate".equals(c.source) ? "~" : "") + percent(c.percent);
                String of = c.usedTokens == null ? "" : shortNumber(c.usedTokens) + (c.windowTokens == null ? " tokens" : " of " + shortNumber(c.windowTokens));
                row(card, big, of, 22, true);
            }
            if (c.percent != null) bar(card, c.percent, ContextRing.color(c.percent));
            String note = c.autoCompactAt == null ? "" : "Auto-compacts at " + shortNumber(c.autoCompactAt);
            String src = "last_turn".equals(c.source) ? "as of the last turn" : "estimate".equals(c.source) ? "estimated" : "";
            if (!note.isEmpty() || !src.isEmpty()) caption(card, note.isEmpty() ? cap(src) : note + (src.isEmpty() ? "" : " · " + src));
            if (compact != null) button(card, "Compact now", compact);
        }
        if (state.session != null) {
            UsageState.Session s = state.session;
            LinearLayout card = card("This conversation");
            tokens(card, "Input", s.inputTokens); tokens(card, "Cached input", s.cachedInputTokens);
            tokens(card, "Output", s.outputTokens); tokens(card, "Reasoning", s.reasoningTokens);
            if (s.costUsd != null) row(card, s.costLabel.isEmpty() ? "Cost" : "Cost (" + s.costLabel + ")",
                    String.format(Locale.getDefault(), "$%.2f", s.costUsd), 14, false);
        }
        if (!state.limits.isEmpty()) {
            LinearLayout card = card("Plan limits" + (state.plan.isEmpty() ? "" : " · " + cap(state.plan)));
            for (UsageState.Limit limit : state.limits) {
                int color = "rejected".equals(limit.status) ? Ui.RED : "warning".equals(limit.status) ? Ui.AMBER : Ui.TEXT;
                row(card, cap(limit.label.replace(" limit", "")), limit.usedPercent == null ? "" : percent(limit.usedPercent), 14, false);
                if (limit.usedPercent != null) bar(card, limit.usedPercent, color == Ui.TEXT ? ContextRing.color(limit.usedPercent) : color);
                TextView when = caption(card, "");
                Runnable countdown = () -> {
                    String resetIn = UsageState.resetsIn(System.currentTimeMillis() / 1000, limit.resetsAt);
                    String line = "rejected".equals(limit.status) ? "Limit reached" : "warning".equals(limit.status) ? "Approaching this limit" : "";
                    if (limit.resetsAt > 0) line += (line.isEmpty() ? "Resets " : " · resets ") + ("now".equals(resetIn) ? "now" : time(limit.resetsAt) + (resetIn.isEmpty() ? "" : " · in " + resetIn));
                    when.setText(line); when.setTextColor(color == Ui.TEXT ? Ui.FAINT : color);
                    when.setVisibility(line.isEmpty() ? View.GONE : View.VISIBLE);
                };
                countdowns.add(countdown); countdown.run();
            }
            if (state.resets != null && (state.resets.available == null || state.resets.available > 0) && reset != null) {
                String label = state.resets.label + (state.resets.available == null ? "" : " (" + state.resets.available + " left)");
                button(card, label, () -> Sheets.confirm(activity, "Use a limit reset?", "This spends one available reset credit on your account.", "Use reset", true, reset));
            }
        }
        if (state.context == null && state.session == null && state.limits.isEmpty())
            caption(content, "Usage is not available from this server yet. Send a message, then refresh.");
        LinearLayout footer = new LinearLayout(activity); footer.setGravity(android.view.Gravity.CENTER_VERTICAL);
        TextView asOf = Ui.text(activity, state.asOf > 0 ? "Updated " + time(state.asOf) : "", 12, Ui.FAINT, false);
        footer.addView(asOf, new LinearLayout.LayoutParams(0, -2, 1));
        TextView again = Ui.ghostButton(activity, "Refresh", Ui.BLUE); again.setOnClickListener(x -> refresh.run());
        footer.addView(again, new LinearLayout.LayoutParams(-2, dp(48)));
        LinearLayout.LayoutParams fp = new LinearLayout.LayoutParams(-1, -2); fp.topMargin = dp(8); content.addView(footer, fp);
    }
    private LinearLayout card(String title) {
        TextView h = Ui.text(activity, title.toUpperCase(Locale.getDefault()), 12, Ui.SUB, true);
        h.setLetterSpacing(0.06f); h.setPadding(dp(4), dp(16), 0, dp(8)); content.addView(h);
        LinearLayout card = new LinearLayout(activity); card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(16), dp(12), dp(16), dp(12)); card.setBackground(Ui.stroke(activity, Ui.tier(1), Ui.LINE, 16));
        content.addView(card, new LinearLayout.LayoutParams(-1, -2)); return card;
    }
    private void row(LinearLayout card, String label, String value, int size, boolean bigLabel) {
        LinearLayout r = new LinearLayout(activity); r.setGravity(android.view.Gravity.CENTER_VERTICAL); r.setPadding(0, dp(6), 0, dp(6));
        r.addView(Ui.text(activity, label, size, bigLabel ? Ui.TEXT : Ui.SUB, bigLabel), new LinearLayout.LayoutParams(0, -2, 1));
        TextView v = Ui.text(activity, value, bigLabel ? 14 : size, bigLabel ? Ui.SUB : Ui.TEXT, !bigLabel);
        v.setGravity(android.view.Gravity.END); r.addView(v, new LinearLayout.LayoutParams(-2, -2)); card.addView(r);
    }
    private TextView caption(LinearLayout parent, String label) {
        TextView v = Ui.text(activity, label, 12, Ui.FAINT, false); v.setPadding(0, dp(2), 0, dp(6)); parent.addView(v); return v;
    }
    private void button(LinearLayout parent, String label, Runnable action) {
        TextView v = Ui.ghostButton(activity, label, Ui.BLUE); v.setMinHeight(dp(44)); v.setGravity(android.view.Gravity.CENTER);
        v.setOnClickListener(x -> action.run());
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(-1, dp(44)); p.topMargin = dp(4); parent.addView(v, p);
    }
    private void tokens(LinearLayout card, String label, Long n) { if (n != null) row(card, label, shortNumber(n), 14, false); }
    private void bar(LinearLayout parent, double percent, int color) {
        ProgressBar bar = new ProgressBar(activity, null, android.R.attr.progressBarStyleHorizontal);
        bar.setMax(1000); bar.setProgress((int) Math.round(Math.max(1, percent) * 10));
        bar.setProgressTintList(ColorStateList.valueOf(color)); bar.setProgressBackgroundTintList(ColorStateList.valueOf(Ui.LINE));
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(-1, dp(6)); p.setMargins(0, dp(2), 0, dp(6)); parent.addView(bar, p);
        bar.setContentDescription(String.format(Locale.getDefault(), "%.0f percent used", percent));
    }
    static String shortNumber(long n) {
        if (n < 1_000) return String.valueOf(n);
        if (n < 1_000_000) return trim(n / 1_000.0) + "k";
        return trim(n / 1_000_000.0) + "M";
    }
    private static String trim(double v) {
        String s = String.format(Locale.US, v >= 100 ? "%.0f" : "%.1f", v);
        return s.endsWith(".0") ? s.substring(0, s.length() - 2) : s;
    }
    private static String cap(String s) { return s.isEmpty() ? s : Character.toUpperCase(s.charAt(0)) + s.substring(1); }
    private int dp(int n) { return Ui.dp(activity, n); }
    private static String percent(double p) { return String.format(Locale.getDefault(), "%.0f%%", p); }
    private static String time(long seconds) { return DateFormat.getTimeInstance(DateFormat.SHORT).format(new Date(seconds * 1000)); }
}
