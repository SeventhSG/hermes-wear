package cloud.stormlabs.hermescompanion;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Color;
import android.os.Bundle;
import android.view.Gravity;
import android.view.GestureDetector;
import android.view.MotionEvent;
import android.view.View;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * First-run setup: welcome, connect, connected. Swipe or use Back/Next;
 * Skip always works and nothing is forced. Replayed from Settings › About › Show introduction.
 */
public final class OnboardingActivity extends Activity {
    static final String EXTRA_REPLAY = "replay";
    private SettingsStore store; private boolean replay, connecting;
    private int page;
    private LinearLayout dots, column;
    private ScrollView scroll; private TextView skip, back, next;
    private boolean hubReady;
    private GestureDetector swipe;
    private final ExecutorService network = Executors.newSingleThreadExecutor();

    private int dp(int n) { return Ui.dp(this, n); }

    @Override public void onCreate(Bundle b) {
        super.onCreate(b);
        CrashReporter.install(this);
        try {
            Ui.legacyWindowInsets(this);
            store = new SettingsStore(this);
            replay = getIntent().getBooleanExtra(EXTRA_REPLAY, false);
            if(!replay){WhatsNew.onboardingStarted(this);}
            if (b != null) page = Onboarding.move(b.getInt("page", 0), 0);
            getWindow().setStatusBarColor(Ui.BG);
            getWindow().setNavigationBarColor(Ui.BG);
            build();
            swipe = new GestureDetector(this, new GestureDetector.SimpleOnGestureListener() {
                @Override public boolean onFling(MotionEvent a, MotionEvent e, float vx, float vy) {
                    if (a == null || e == null) return false;
                    float dx = e.getX() - a.getX(), dy = e.getY() - a.getY();
                    if (Math.abs(dx) > dp(80) && Math.abs(dx) > 2 * Math.abs(dy) && Math.abs(vx) > dp(300)) go(dx < 0 ? 1 : -1);
                    return false;
                }
            });
            show(0);
        } catch (Throwable t) { CrashReporter.show(this, t); }
    }

    @Override protected void onSaveInstanceState(Bundle out) { super.onSaveInstanceState(out); out.putInt("page", page); }

    @Override public boolean dispatchTouchEvent(MotionEvent ev) { if (swipe != null) swipe.onTouchEvent(ev); return super.dispatchTouchEvent(ev); }

    @Override protected void onResume() {
        super.onResume();
        // Back from a successful connection: stay on the last page, which now offers to set up the rest of the server.
        if (connecting && !store.apiKey().isEmpty()) page = Onboarding.PAGES - 1;
        connecting = false;
        if (column != null && (page == 1 || Onboarding.isLast(page))) show(0);
    }

    private void build() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        Ui.ambient(root);

        LinearLayout top = new LinearLayout(this);
        top.setGravity(Gravity.CENTER_VERTICAL | Gravity.END);
        top.setPadding(dp(12), dp(6), dp(12), 0);
        skip = Ui.pillButton(this, "Skip", 0, Color.TRANSPARENT, Color.TRANSPARENT, Ui.SUB);
        skip.setOnClickListener(v -> finishTour());
        top.addView(skip);
        root.addView(top, new LinearLayout.LayoutParams(-1, -2));

        scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setVerticalScrollBarEnabled(false);
        scroll.setOverScrollMode(View.OVER_SCROLL_NEVER);
        column = new LinearLayout(this);
        column.setOrientation(LinearLayout.VERTICAL);
        column.setPadding(dp(24), dp(8), dp(24), dp(16));
        scroll.addView(column, new ScrollView.LayoutParams(-1, -2));
        root.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));

        dots = new LinearLayout(this);
        dots.setGravity(Gravity.CENTER);
        dots.setPadding(0, dp(8), 0, dp(12));
        root.addView(dots, new LinearLayout.LayoutParams(-1, -2));

        LinearLayout nav = new LinearLayout(this);
        nav.setGravity(Gravity.CENTER_VERTICAL);
        nav.setPadding(dp(16), 0, dp(16), dp(16));
        back = Ui.pillButton(this, "Back", 0, Color.TRANSPARENT, Ui.LINE, Ui.TEXT);
        back.setOnClickListener(v -> go(-1));
        nav.addView(back);
        nav.addView(new View(this), new LinearLayout.LayoutParams(0, 1, 1));
        next = Ui.pillButton(this, "Next", 0, Ui.BLUE, Ui.BLUE, Color.WHITE);
        next.setOnClickListener(v -> { if(page==0)go(1);else primary(); });
        nav.addView(next);
        root.addView(nav, new LinearLayout.LayoutParams(-1, -2));

        setContentView(root);
        Ui.fitInsets(root, root);
    }

    private void go(int delta) {
        int to = Onboarding.move(page, delta);
        if (to == page) return;
        int dir = to > page ? 1 : -1;
        page = to;
        show(dir);
    }

    /** Rebuilds the current page; dir slides it in from that side (0 = no animation). */
    private void show(int dir) {
        LinearLayout c = column;
        c.removeAllViews();
        c.setGravity(page == 0 ? Gravity.CENTER : Gravity.TOP);
        switch (page) {
            case 0: welcome(c); break;
            default: connect(c);

        }
        skip.setText(Onboarding.isLast(page)?"Start chatting":"Skip");
        skip.setVisibility(View.VISIBLE);
        back.setVisibility(page == 0 ? View.INVISIBLE : View.VISIBLE);
        next.setText(page==0?"Next":Onboarding.primaryLabel(!store.apiKey().isEmpty(),hubReady));
        dots.removeAllViews();
        for (int i = 0; i < Onboarding.PAGES; i++) {
            View d = new View(this);
            d.setBackground(Ui.round(this, i == page ? Ui.BLUE : Ui.LINE, 4));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(i == page ? 22 : 8), dp(8));
            lp.setMargins(dp(3), 0, dp(3), 0);
            dots.addView(d, lp);
        }
        dots.setContentDescription("Step " + (page + 1) + " of " + Onboarding.PAGES);
        scroll.scrollTo(0, 0);
        if (dir != 0 && Ui.motionEnabled()) {
            c.setAlpha(0f); c.setTranslationX(dir * dp(40));
            c.animate().alpha(1f).translationX(0).setDuration(220).start();
        } else { c.setAlpha(1f); c.setTranslationX(0); }
    }

    private void welcome(LinearLayout c) {
        LinearLayout.LayoutParams logo = new LinearLayout.LayoutParams(dp(112), dp(112));
        logo.gravity = Gravity.CENTER_HORIZONTAL; logo.bottomMargin = dp(28);
        c.addView(Ui.hermesLogo(this, dp(112)), logo);
        c.addView(centered("Hermes Companion", 26, Ui.TEXT, true));
        TextView body = centered("Chat with Hermes, Claude Code or Codex on your own server.", 15, Ui.SUB, false);
        body.setPadding(0, dp(14), 0, dp(14));
        c.addView(body);
        TextView tag = centered("Your server, your data.", 13, Ui.BLUE, true);
        tag.setPadding(dp(14), dp(6), dp(14), dp(6));
        tag.setBackground(Ui.round(this, (Ui.BLUE & 0xffffff) | 0x22000000, 20));
        LinearLayout.LayoutParams tp = new LinearLayout.LayoutParams(-2, -2);
        tp.gravity = Gravity.CENTER_HORIZONTAL;
        c.addView(tag, tp);
    }

    /** Icon rows: {icon, title, line[, badge]}. */
    private void rows(LinearLayout c, String[][] items) {
        for (String[] it : items) {
            LinearLayout row = new LinearLayout(this);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setPadding(dp(14), dp(12), dp(14), dp(12));
            row.setBackground(Ui.stroke(this, Ui.SURFACE, Ui.LINE, 14));
            ImageView icon = new ImageView(this);
            icon.setImageResource(getResources().getIdentifier("ic_" + it[0], "drawable", getPackageName()));
            icon.setColorFilter(Ui.BLUE);
            icon.setPadding(dp(9), dp(9), dp(9), dp(9));
            icon.setBackground(Ui.round(this, (Ui.BLUE & 0xffffff) | 0x22000000, 12));
            icon.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
            row.addView(icon, new LinearLayout.LayoutParams(dp(42), dp(42)));
            LinearLayout text = new LinearLayout(this);
            text.setOrientation(LinearLayout.VERTICAL);
            text.setPadding(dp(14), 0, 0, 0);
            LinearLayout titleRow = new LinearLayout(this);
            titleRow.setGravity(Gravity.CENTER_VERTICAL);
            titleRow.addView(Ui.text(this, it[1], 15, Ui.TEXT, true));
            if (it.length > 3) {
                TextView badge = Ui.text(this, it[3], 11, Ui.BLUE, true);
                badge.setPadding(dp(8), dp(2), dp(8), dp(2));
                badge.setBackground(Ui.round(this, (Ui.BLUE & 0xffffff) | 0x22000000, 10));
                LinearLayout.LayoutParams bp = new LinearLayout.LayoutParams(-2, -2);
                bp.leftMargin = dp(8);
                titleRow.addView(badge, bp);
            }
            text.addView(titleRow);
            TextView line = Ui.text(this, it[2], 13, Ui.SUB, false);
            line.setPadding(0, dp(2), 0, 0);
            text.addView(line);
            row.addView(text, new LinearLayout.LayoutParams(0, -2, 1));
            c.addView(row, lp(-1, -2, 0, 0, 0, 10));
        }
    }

    private void connect(LinearLayout c) {
        boolean connected = !store.apiKey().isEmpty();
        ImageView hero = new ImageView(this);
        hero.setImageResource(connected ? R.drawable.ic_bolt : R.drawable.ic_server);
        hero.setColorFilter(connected ? Ui.GREEN : Ui.BLUE);
        hero.setPadding(dp(16), dp(16), dp(16), dp(16));
        hero.setBackground(Ui.round(this, ((connected ? Ui.GREEN : Ui.BLUE) & 0xffffff) | 0x22000000, 20));
        hero.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        c.addView(hero, lp(dp(64), dp(64), 0, 16, 0, 4));
        heading(c, connected ? "You're connected" : "Connect your server",
                connected ? "Chat is ready. Add optional server features now, or start chatting." :
                        "Enter your server URL and key. We will test them before saving.");
        if (!connected) rows(c, new String[][]{
                {"link", "Server URL", "For example http://192.168.1.10:8642 or your tailnet address."},
                {"settings", "API key", "API_SERVER_KEY from your Hermes .env file."},
                {"pulse", "Save connection", "The app checks the connection before it keeps anything."}});
        if (connected && "Hermes".equals(store.serverType())) { serverKit(c); }
        else if(connected){hubReady=true;next.setText("Start chatting");}
        if (!connected && !store.pairHub().isEmpty()) {
            TextView auto = Ui.pillButton(this, "Set up automatically", 0, Ui.SURFACE, Ui.LINE, Ui.BLUE);
            auto.setOnClickListener(v -> autoSetup(auto));
            c.addView(auto, lp(-1, dp(48), 0, 8, 0, 0));
        }
    }

    /** Connected: check for hermes-hub and, if it's missing, offer to have Hermes install the bundled server kit. */
    private void serverKit(LinearLayout c) {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        TextView checking = Ui.text(this, "Checking your server…", 13, Ui.SUB, false);
        box.addView(checking);
        c.addView(box, lp(-1, -2, 0, 0, 0, 0));
        network.submit(() -> {
            boolean up = ServerSetup.hubUp(store);
            runOnUiThread(() -> {
                if (isFinishing() || isDestroyed() || box.getParent() != column) return;
                box.removeAllViews();
                hubReady=up;
                next.setText(Onboarding.primaryLabel(true,hubReady));
                checking.setText(up?"Your server features are ready.":"Optional setup adds notifications, files and phone actions. Choose what you need and approve each change. You can also do this later in Settings › Server pack.");
                box.addView(checking);

            });
        });
    }

    /** Setup took over: the tour is done, and the chat it opened is where the user goes next. */
    private void leave() { store.onboardingDone(true); finish(); }

    private void primary() {
        if (store.apiKey().isEmpty()) {
            connecting = true;
            startActivity(new Intent(this, ConnectionActivity.class).putExtra(ConnectionActivity.EXTRA_ONBOARDING,true));
        } else if(hubReady || !"Hermes".equals(store.serverType()))finishTour();
        else ServerSetup.offer(this,store,this::leave);
    }

    private void autoSetup(TextView button) {
        button.setEnabled(false); button.setText("Setting up…");
        network.submit(() -> {
            String err = Pairing.pair(store);
            runOnUiThread(() -> {
                if (isFinishing() || isDestroyed()) return;
                button.setEnabled(true); button.setText("Set up automatically");
                if (err != null) { Toast.makeText(this, err, Toast.LENGTH_LONG).show(); return; }
                page=Onboarding.PAGES-1;show(0);
            });
        });
    }

    /** Marks the tour done and leaves: replays just close, first runs continue into the app. */
    private void finishTour() {
        store.onboardingDone(true);
        if (!replay) startActivity(new Intent(this, MainActivity.class).setAction(MainActivity.ACTION_NEW_CHAT).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK));
        finish();
    }

    private void heading(LinearLayout c, String title, String sub) {
        c.addView(Ui.text(this, title, 24, Ui.TEXT, true), lp(-1, -2, 0, 12, 0, 6));
        if (sub != null) { TextView s = Ui.text(this, sub, 14, Ui.SUB, false); s.setPadding(0, 0, 0, dp(16)); c.addView(s); }
    }

    private TextView centered(String text, float size, int color, boolean bold) {
        TextView t = Ui.text(this, text, size, color, bold);
        t.setGravity(Gravity.CENTER);
        return t;
    }

    private LinearLayout.LayoutParams lp(int w, int h, int l, int t, int r, int b) {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(w, h);
        p.setMargins(dp(l), dp(t), dp(r), dp(b));
        return p;
    }

    @Override public void onBackPressed() { if (page > 0) go(-1); else finishTour(); }

    @Override protected void onDestroy() { super.onDestroy(); network.shutdown(); }
}
