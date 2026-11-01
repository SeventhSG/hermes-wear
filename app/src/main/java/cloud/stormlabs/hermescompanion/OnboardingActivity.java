package cloud.stormlabs.hermescompanion;

import android.Manifest;
import android.app.Activity;
import android.app.role.RoleManager;
import android.content.ComponentName;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.view.Gravity;
import android.view.GestureDetector;
import android.view.MotionEvent;
import android.view.View;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * First-run tour: welcome, what you need, feature tour, permissions, connect. Swipe or use Back/Next;
 * Skip always works and nothing is forced. Replayed from Settings › About › Show introduction.
 */
public final class OnboardingActivity extends Activity {
    static final String EXTRA_REPLAY = "replay";
    private static final int ROLE_REQ = 200, PERM_REQ = 100;
    private static final int RUNTIME = 0, ACCESSIBILITY = 1, ROLE = 2, LISTENER = 3;

    /** One permission row: what it is for, and how it is granted. */
    private static final class Perm {
        final String title, why; final int kind; final String[] runtime;
        Perm(String title, String why, int kind, String... runtime) { this.title = title; this.why = why; this.kind = kind; this.runtime = runtime; }
    }

    private final Perm[] perms = {
            new Perm("Notifications", "Agent replies, approvals and events arrive as alerts.", RUNTIME,
                    Build.VERSION.SDK_INT >= 33 ? new String[]{Manifest.permission.POST_NOTIFICATIONS} : new String[0]),
            new Perm("Microphone", "Voice chat, dictation and the \"Hey Hermes\" wake word.", RUNTIME, Manifest.permission.RECORD_AUDIO),
            new Perm("Location (optional)", "Location-aware answers and finding places near you.", RUNTIME,
                    Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION),
            new Perm("Contacts & Calendar (optional)", "Lets Hermes look up a contact or check your calendar when you ask.", RUNTIME,
                    Manifest.permission.READ_CONTACTS, Manifest.permission.READ_CALENDAR),
            new Perm("Screen control", "Lets the phone agent read and tap the screen when you ask. Sideloaded? "
                    + "App info › menu › Allow restricted settings first.", ACCESSIBILITY),
            new Perm("Default assistant", "Makes \"Hey Hermes\" and the assistant gesture open Hermes.", ROLE),
            new Perm("Notification access (optional)", "Lets Hermes draft replies to your messages, only when you ask.", LISTENER),
    };

    private SettingsStore store; private boolean replay, connecting;
    private int page; private final Set<Integer> blocked = new HashSet<>();
    private LinearLayout dots, column;
    private ScrollView scroll; private TextView skip, back, next;
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
        if (column != null && (page == Onboarding.PERMISSIONS_PAGE || Onboarding.isLast(page))) show(0); // states may have changed in system settings
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
        next.setOnClickListener(v -> { if (Onboarding.isLast(page)) primary(); else go(1); });
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
            case 1: needs(c); break;
            case 2: tour(c, "Chat, voice and your phone", new String[][]{
                    {"session", "Chat & approvals", "Proposal cards let you approve or refuse what Hermes wants to do."},
                    {"mic", "Voice & wake word", "Talk hands-free, or say \"Hey Hermes\" from any app."},
                    {"phone", "Phone agent", "Hermes can read and operate your screen when you ask."},
                    {"notification", "Push notifications", "Replies and events reach you even when the app is closed."}}); break;
            case 3: tour(c, "Your whole setup", new String[][]{
                    {"clock", "Agents & jobs", "Start, watch and schedule background work."},
                    {"compose", "Capture inbox", "Drop notes, photos and voice memos to file later."},
                    {"pulse", "System screen", "See the health of your server and homelab."},
                    {"home", "Profiles", "Keep several servers or agents and switch between them."},
                    {"palette", "Layouts & themes", "Four looks and several layouts, all in Settings."}}); break;
            case 4: permissions(c); break;
            default: connect(c);
        }
        skip.setVisibility(Onboarding.isLast(page) ? View.INVISIBLE : View.VISIBLE);
        back.setVisibility(page == 0 ? View.INVISIBLE : View.VISIBLE);
        next.setText(Onboarding.isLast(page) ? Onboarding.primaryLabel(!store.apiKey().isEmpty()) : "Next");
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
        TextView body = centered("A phone front-end for your own self-hosted Hermes agent: chat, voice, phone actions, push "
                + "notifications, agents and jobs, a capture inbox and a system view of your homelab.", 15, Ui.SUB, false);
        body.setPadding(0, dp(14), 0, dp(14));
        c.addView(body);
        TextView tag = centered("Your server, your data.", 13, Ui.BLUE, true);
        tag.setPadding(dp(14), dp(6), dp(14), dp(6));
        tag.setBackground(Ui.round(this, (Ui.BLUE & 0xffffff) | 0x22000000, 20));
        LinearLayout.LayoutParams tp = new LinearLayout.LayoutParams(-2, -2);
        tp.gravity = Gravity.CENTER_HORIZONTAL;
        c.addView(tag, tp);
    }

    private void needs(LinearLayout c) {
        heading(c, "What you need", "Hermes Companion talks to servers you run. Set them up first, or later.");
        rows(c, new String[][]{
                {"server", "Hermes server", "Its API URL and API key.", "Required"},
                {"pulse", "hermes-hub", "Push, homelab status and ops, usually on port 8791."},
                {"compose", "Essentials capture server", "Powers the capture inbox."},
                {"notification", "Push relay", "Delivers push if you have no Firebase project of your own."}});
    }

    private void tour(LinearLayout c, String title, String[][] items) {
        heading(c, title, null);
        rows(c, items);
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

    private void permissions(LinearLayout c) {
        int granted = 0;
        for (Perm p : perms) if (held(p)) granted++;
        heading(c, "Permissions", "All optional. Allow what you want now and change it later in Settings. "
                + granted + " of " + perms.length + " allowed.");
        for (int i = 0; i < perms.length; i++) {
            final int index = i; final Perm p = perms[i];
            int state = Onboarding.permState(held(p), blocked.contains(i));
            LinearLayout row = new LinearLayout(this);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setPadding(dp(14), dp(12), dp(10), dp(12));
            row.setBackground(Ui.stroke(this, Ui.SURFACE, Ui.LINE, 14));
            LinearLayout text = new LinearLayout(this);
            text.setOrientation(LinearLayout.VERTICAL);
            text.addView(Ui.text(this, p.title, 15, Ui.TEXT, true));
            TextView why = Ui.text(this, p.why, 12, Ui.SUB, false);
            why.setPadding(0, dp(3), dp(8), 0);
            text.addView(why);
            row.addView(text, new LinearLayout.LayoutParams(0, -2, 1));
            LinearLayout actions = new LinearLayout(this);
            actions.setOrientation(LinearLayout.VERTICAL);
            actions.setGravity(Gravity.CENTER_HORIZONTAL);
            boolean ok = state == Onboarding.GRANTED;
            TextView button = Ui.pillButton(this, Onboarding.actionLabel(state), 0, ok ? Color.TRANSPARENT : Ui.SURFACE,
                    ok ? Color.TRANSPARENT : Ui.LINE, ok ? Ui.GREEN : Ui.BLUE);
            if (!ok) button.setOnClickListener(v -> allow(index, state));
            actions.addView(button);
            if (p.kind == ACCESSIBILITY && !ok && state != Onboarding.BLOCKED) {
                TextView info = Ui.pillButton(this, "App info", 0, Color.TRANSPARENT, Color.TRANSPARENT, Ui.SUB);
                info.setOnClickListener(v -> appInfo());
                actions.addView(info);
            }
            row.addView(actions);
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
                connected ? "Hermes is ready. One more optional step:" :
                        "Three quick steps on the next screen:");
        if (!connected) rows(c, new String[][]{
                {"link", "Server URL", "For example http://192.168.1.10:8642 or your tailnet address."},
                {"settings", "API key", "API_SERVER_KEY from your Hermes .env file."},
                {"pulse", "Test, then save", "The app checks the connection before it keeps anything."}});
        if (connected) { serverKit(c); modules(c); }
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
                if (isFinishing() || isDestroyed() || box.getParent() == null) return;
                box.removeAllViews();
                if (up) { rows(box, new String[][]{{"pulse", "hermes-hub is running", "Push, the System screen and file delivery are ready."}}); return; }
                rows(box, new String[][]{
                        {"server", "Set up the rest on your server", "Hermes looks at your server, asks what you want and installs only that:"},
                        {"notification", "hermes-hub", "Push notifications, the System screen, homelab status and files in chat."},
                        {"send", "hermes-notify", "A command that lets scripts and jobs notify this phone."},
                        {"skills", "Companion skills", "Ten skills: phone agent, reminders, read-later, receipts, homelab and more. Pick only the ones you want."},
                        {"server", "Only if you want them", "Voice servers, media servers, Codex or Claude Code remote, Proxmox status, slash commands. Hermes asks; skip what you don't use."}});
                TextView go = Ui.pillButton(this, "Set up my server", 0, Ui.BLUE, Ui.BLUE, Color.WHITE);
                go.setOnClickListener(v -> ServerSetup.offer(this, store, this::leave));
                box.addView(go, lp(-1, dp(48), 0, 4, 0, 6));
                TextView note = Ui.text(this, "Hermes answers in a chat. You tap answers to its questions and approve every change. Skip this and do it later from Settings, Server pack.", 12, Ui.FAINT, false);
                note.setGravity(Gravity.CENTER);
                box.addView(note);
            });
        });
    }

    /** Everything beyond base chat, each with a switch and its own setup; the same list lives in Settings › Modules. */
    private void modules(LinearLayout c) {
        TextView h = Ui.text(this, "Choose your modules", 18, Ui.TEXT, true);
        h.setPadding(0, dp(18), 0, dp(2));
        c.addView(h);
        TextView sub = Ui.text(this, "Chat works on its own. Turn on what you use and tap one to set it up; you can change this later in Settings › Modules.", 13, Ui.SUB, false);
        sub.setPadding(0, 0, 0, dp(6));
        c.addView(sub);
        LinearLayout list = new LinearLayout(this);
        list.setOrientation(LinearLayout.VERTICAL);
        list.setBackground(Ui.card(this, 1));
        list.setPadding(dp(10), dp(4), dp(8), dp(4));
        Modules.render(this, list, null);
        c.addView(list, lp(-1, -2, 0, 0, 0, 8));
    }

    /** Setup took over: the tour is done, and the chat it opened is where the user goes next. */
    private void leave() { store.onboardingDone(true); finish(); }

    private void primary() {
        if (store.apiKey().isEmpty()) {
            store.onboardingDone(true);
            connecting = !replay;
            startActivity(new Intent(this, ConnectionActivity.class));
        } else finishTour();
    }

    private void autoSetup(TextView button) {
        button.setEnabled(false); button.setText("Setting up…");
        network.submit(() -> {
            String err = Pairing.pair(store);
            runOnUiThread(() -> {
                if (isFinishing() || isDestroyed()) return;
                button.setEnabled(true); button.setText("Set up automatically");
                if (err != null) { Toast.makeText(this, err, Toast.LENGTH_LONG).show(); return; }
                show(0);
            });
        });
    }

    /** Marks the tour done and leaves: replays just close, first runs continue into the app. */
    private void finishTour() {
        store.onboardingDone(true);
        if (!replay) startActivity(new Intent(this, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK));
        finish();
    }

    private boolean held(Perm p) {
        switch (p.kind) {
            case ACCESSIBILITY: return HermesAccessibilityService.listed(this);
            case ROLE: return Build.VERSION.SDK_INT >= 29 && getSystemService(RoleManager.class).isRoleHeld(RoleManager.ROLE_ASSISTANT);
            case LISTENER: return listenerAccess();
            default:
                for (String r : p.runtime) if (checkSelfPermission(r) != PackageManager.PERMISSION_GRANTED) return false;
                return true;
        }
    }

    private boolean listenerAccess() {
        String enabled = Settings.Secure.getString(getContentResolver(), "enabled_notification_listeners");
        if (enabled == null) return false;
        ComponentName own = new ComponentName(this, ReplyListenerService.class);
        for (String name : enabled.split(":")) if (own.equals(ComponentName.unflattenFromString(name))) return true;
        return false;
    }

    private void allow(int index, int state) {
        Perm p = perms[index];
        if (state == Onboarding.BLOCKED) { appInfo(); return; }
        try {
            switch (p.kind) {
                case ACCESSIBILITY: startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)); break;
                case LISTENER: startActivity(new Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)); break;
                case ROLE:
                    if (Build.VERSION.SDK_INT >= 29) {
                        RoleManager rm = getSystemService(RoleManager.class);
                        if (rm.isRoleAvailable(RoleManager.ROLE_ASSISTANT)) { startActivityForResult(rm.createRequestRoleIntent(RoleManager.ROLE_ASSISTANT), ROLE_REQ); break; }
                    }
                    startActivity(new Intent(Settings.ACTION_VOICE_INPUT_SETTINGS));
                    break;
                default: requestPermissions(p.runtime, PERM_REQ + index);
            }
        } catch (RuntimeException e) { Toast.makeText(this, "Open this in system Settings instead", Toast.LENGTH_LONG).show(); appInfo(); }
    }

    @Override public void onRequestPermissionsResult(int code, String[] permissions, int[] results) {
        super.onRequestPermissionsResult(code, permissions, results);
        int index = code - PERM_REQ;
        if (index < 0 || index >= perms.length) return;
        for (int i = 0; i < results.length; i++)
            if (results[i] != PackageManager.PERMISSION_GRANTED && !shouldShowRequestPermissionRationale(permissions[i])) blocked.add(index);
        if (page == Onboarding.PERMISSIONS_PAGE) show(0);
    }

    @Override protected void onActivityResult(int code, int result, Intent data) {
        super.onActivityResult(code, result, data);
        if (page == Onboarding.PERMISSIONS_PAGE) show(0);
    }

    private void appInfo() {
        startActivity(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", getPackageName(), null)));
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
