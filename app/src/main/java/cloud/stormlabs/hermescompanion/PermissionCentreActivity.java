package cloud.stormlabs.hermescompanion;

import android.Manifest;
import android.app.Activity;
import android.app.NotificationManager;
import android.content.res.ColorStateList;
import android.content.Intent;
import android.content.ComponentName;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.text.format.DateUtils;
import android.view.Gravity;
import android.view.View;
import android.widget.ImageButton;
import android.widget.CheckBox;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.text.DateFormat;
import java.util.Date;
import java.util.List;
import java.util.function.Consumer;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** One place to pause phone actions, manage opt-ins and inspect their local history. */
public final class PermissionCentreActivity extends Activity {
    private SettingsStore store;
    private LinearLayout body;
    private final ExecutorService logWorker = Executors.newSingleThreadExecutor();

    private int dp(int n) { return Ui.dp(this, n); }
    private LinearLayout.LayoutParams match(int h) { return new LinearLayout.LayoutParams(-1, h < 0 ? h : dp(h)); }

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        Ui.legacyWindowInsets(this);
        store = new SettingsStore(this);
        getWindow().setStatusBarColor(Ui.BAR);
        getWindow().setNavigationBarColor(Ui.BG);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Ui.BG);
        LinearLayout bar = new LinearLayout(this);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setBackgroundColor(Ui.BAR);
        ImageButton back = Ui.icon(this, R.drawable.ic_back, "Back");
        back.setOnClickListener(v -> onBackPressed());
        bar.addView(back, new LinearLayout.LayoutParams(dp(52), dp(56)));
        bar.addView(Ui.text(this, "Permission centre", 19, Ui.TEXT, true));
        root.addView(bar, match(56));

        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        body = new LinearLayout(this);
        body.setOrientation(LinearLayout.VERTICAL);
        body.setPadding(dp(12), dp(16), dp(12), dp(28));
        scroll.addView(body);
        root.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));
        setContentView(root);
        Ui.fitInsets(root, root);
    }

    @Override protected void onResume() { super.onResume(); if (body != null) render(); }

    private void render() {
        body.removeAllViews();
        LinearLayout master = new LinearLayout(this);
        master.setOrientation(LinearLayout.VERTICAL);
        master.setPadding(dp(16), dp(16), dp(16), dp(12));
        master.setBackground(Ui.stroke(this, store.agentPaused() ? Ui.pick(0xff2b2023, (Ui.RED & 0xffffff) | 0x1a000000) : Ui.pick(0xff1b2a29, (Ui.GREEN & 0xffffff) | 0x1a000000),
                store.agentPaused() ? Ui.RED : Ui.GREEN, 16));
        TextView eyebrow = Ui.text(this, store.agentPaused() ? "PAUSED" : "READY", 11,
                store.agentPaused() ? Ui.RED : Ui.GREEN, true);
        eyebrow.setLetterSpacing(.1f);
        master.addView(eyebrow);
        toggle(master, "Hermes can act on this phone", "Pause all phone actions and lookups at once",
                !store.agentPaused(), checked -> { store.agentPaused(!checked); render(); });
        body.addView(master, match(-2));

        LinearLayout control = section("ACTION MODE");
        LinearLayout mode = row("Action mode", store.actionMode());
        mode.addView(Ui.text(this, "›", 25, Ui.FAINT, false));
        mode.setBackground(Ui.ripple(this, Ui.SURFACE, 12));
        mode.setOnClickListener(v -> Sheets.actionMode(this, store, this::render));
        mode.setMinimumHeight(dp(68));
        control.addView(mode, match(-2));

        LinearLayout access = section("HERMES OPT-INS");
        toggle(access, "Device actions", "Timers, apps, navigation and phone controls", store.deviceActions(), store::deviceActions);
        TextView tiers = Ui.text(this, "READ: phone lookups · LOCAL: reversible phone controls · SENSITIVE: calls, messages, sharing and capture ask in Ask mode", 12, Ui.SUB, false);
        tiers.setPadding(dp(16), dp(4), dp(16), dp(12));
        access.addView(tiers);
        toggle(access, "Phone context", "Time, battery and network sent with a turn", store.shareDeviceContext(), store::shareDeviceContext);
        toggle(access, "Location", "Approximate location when available", store.shareLocation(), store::shareLocation);
        toggle(access, "Assistant sees screen text", "Visible text when you invoke the assistant", store.useScreenContext(), store::useScreenContext);
        toggle(access, "Screen content", "Read current screen when asked; requires screen control", store.allowScreenContent(), store::allowScreenContent);
        toggle(access, "Calendar", "Read upcoming events", store.allowCalendar(), store::allowCalendar);
        toggle(access, "Contacts", "Find people to call or message", store.allowContacts(), store::allowContacts);
        toggle(access, "Clipboard", "Read copied text when asked", store.allowClipboard(), store::allowClipboard);

        LinearLayout android = section("ANDROID PERMISSIONS");
        permission(android, "Microphone", granted(Manifest.permission.RECORD_AUDIO));
        NotificationManager notifications = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        permission(android, "Notifications", notifications != null && notifications.areNotificationsEnabled());
        permission(android, "Contacts", granted(Manifest.permission.READ_CONTACTS));
        permission(android, "Calendar", granted(Manifest.permission.READ_CALENDAR));
        permission(android, "Location", granted(Manifest.permission.ACCESS_COARSE_LOCATION)
                || granted(Manifest.permission.ACCESS_FINE_LOCATION));
        permission(android, "Background location", Build.VERSION.SDK_INT < 29
                || granted(Manifest.permission.ACCESS_BACKGROUND_LOCATION));

        if (!store.places().isEmpty()) {
            LinearLayout places = section("PLACES");
            for (HomeData.Place place : store.places()) toggle(places, place.name,
                    Math.round(place.radius) + " m · arrival and departure commands", place.enabled, checked -> {
                        List<HomeData.Place> all = store.places();
                        HomeData.Place changed = place.enabled(checked);
                        for (int i = 0; i < all.size(); i++) if (all.get(i).id.equals(place.id)) all.set(i, changed);
                        store.places(all); PresenceReceiver.register(this, changed); render();
                    });
        }

        LinearLayout replies = section("REPLY DRAFTING");
        toggle(replies, "Draft replies from notifications", listenerAccess() ? "Hermes asks before reading; only Send sends a reply" : "Requires notification access in Android settings",
                store.replyDrafting(), on -> {
                    store.replyDrafting(on);
                    if (on && !listenerAccess()) startActivity(new Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS));
                    render();
                });
        TextView appsHint = Ui.text(this, "Choose apps Hermes may read for reply drafts", 12, Ui.SUB, false);
        appsHint.setPadding(dp(16), dp(12), dp(16), dp(4));
        replies.addView(appsHint);
        for (String[] app : ReplyListenerService.SUGGESTED_APPS) {
            if (getPackageManager().getLaunchIntentForPackage(app[0]) == null) continue;
            LinearLayout choice = row(app[1], app[0]);
            CheckBox check = new CheckBox(this);
            check.setButtonTintList(ColorStateList.valueOf(Ui.BLUE));
            check.setContentDescription("Allow " + app[1] + " reply drafts");
            check.setChecked(store.replyApps().contains(app[0]));
            check.setOnCheckedChangeListener((button, on) -> store.replyApp(app[0], on));
            choice.addView(check, new LinearLayout.LayoutParams(dp(48), dp(48)));
            choice.setOnClickListener(v -> check.performClick());
            choice.setMinimumHeight(dp(72));
            replies.addView(choice, match(-2));
        }

        LinearLayout reader = section("READ ALOUD");
        toggle(reader, "Read notifications aloud", listenerAccess() ? "Into your earbuds while the screen is off" : "Requires notification access in Android settings",
                store.readNotifications(), on -> {
                    store.readNotifications(on);
                    if (on && !listenerAccess()) startActivity(new Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS));
                    render();
                });
        if (store.readNotifications()) {
            toggle(reader, "Only on headphones", "Stay silent on the phone's speaker", store.readNotifHeadphonesOnly(), on -> store.readNotifHeadphonesOnly(on));
            toggle(reader, "Only with the screen off", "Stop as soon as you pick the phone up", store.readNotifScreenOffOnly(), on -> store.readNotifScreenOffOnly(on));
            toggle(reader, "Read the message text", "Off: say only who it is from", store.readNotifContent(), on -> store.readNotifContent(on));
            TextView readHint = Ui.text(this, "Apps that are read aloud. Quiet hours and phone calls are respected.", 12, Ui.SUB, false);
            readHint.setPadding(dp(16), dp(12), dp(16), dp(4));
            reader.addView(readHint);
            java.util.Set<String> readApps = NotificationReader.allowedApps(store);
            java.util.LinkedHashMap<String, String> shown = new java.util.LinkedHashMap<>();
            for (String[] app : ReplyListenerService.SUGGESTED_APPS) if (getPackageManager().getLaunchIntentForPackage(app[0]) != null) shown.put(app[0], app[1]);
            for (String pkg : readApps) if (!shown.containsKey(pkg) && !pkg.equals("none.selected")) shown.put(pkg, appLabel(pkg));
            for (java.util.Map.Entry<String, String> app : shown.entrySet()) {
                LinearLayout choice = row(app.getValue(), app.getKey());
                CheckBox check = new CheckBox(this);
                check.setButtonTintList(ColorStateList.valueOf(Ui.BLUE));
                check.setContentDescription("Read " + app.getValue() + " aloud");
                check.setChecked(readApps.contains(app.getKey()));
                check.setOnCheckedChangeListener((button, on) -> {
                    java.util.Set<String> next = NotificationReader.allowedApps(store);
                    if (on) next.add(app.getKey()); else next.remove(app.getKey());
                    store.readNotifApps(next.isEmpty() ? new java.util.HashSet<>(java.util.Collections.singleton("none.selected")) : next);
                });
                choice.addView(check, new LinearLayout.LayoutParams(dp(48), dp(48)));
                choice.setOnClickListener(v -> check.performClick());
                choice.setMinimumHeight(dp(72));
                reader.addView(choice, match(-2));
            }
            TextView more = Ui.text(this, "Add another app…", 14, Ui.BLUE, true);
            more.setPadding(dp(16), dp(12), dp(16), dp(12));
            more.setOnClickListener(v -> pickReadApp());
            reader.addView(more);
        }

        LinearLayout activity = section("ACTIVITY");
        LinearLayout actions = new LinearLayout(this);
        actions.setGravity(Gravity.END | Gravity.CENTER_VERTICAL);
        actions.addView(button("EXPORT", () -> export()), new LinearLayout.LayoutParams(dp(82), dp(48)));
        actions.addView(button("CLEAR", () -> Sheets.confirm(this, "Clear activity?",
                "This removes the local log and the action cards in chat.", "Clear", true,
                () -> logWorker.submit(() -> { ActivityLog.clear(this); runOnUiThread(this::render); }))), new LinearLayout.LayoutParams(dp(72), dp(48)));
        activity.addView(actions, match(48));
        logWorker.submit(() -> {
            List<ActivityLog.Entry> entries = ActivityLog.recent(this, 100);
            runOnUiThread(() -> {
                if (activity.getParent() == null) return;
                if (entries.isEmpty()) {
                    TextView empty = Ui.text(this, "No phone activity yet", 14, Ui.SUB, false);
                    empty.setPadding(dp(16), dp(16), dp(16), dp(20));
                    activity.addView(empty);
                }
                for (ActivityLog.Entry entry : entries) activity.addView(activityRow(entry), match(-2));
            });
        });
    }

    private LinearLayout section(String name) {
        TextView label = Ui.text(this, name, 11, Ui.BLUE, true);
        label.setLetterSpacing(.09f);
        label.setPadding(dp(10), dp(22), 0, dp(8));
        body.addView(label);
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setBackground(Ui.round(this, Ui.SURFACE, 16));
        body.addView(card, match(-2));
        return card;
    }

    private void toggle(LinearLayout parent, String title, String subtitle, boolean checked, Consumer<Boolean> save) {
        LinearLayout row = row(title, subtitle);
        HermesSwitch sw = new HermesSwitch(this);
        sw.setContentDescription(title);
        sw.setChecked(checked);
        sw.setListener(on -> save.accept(on));
        row.addView(sw, new LinearLayout.LayoutParams(dp(44), dp(44)));
        row.setOnClickListener(v -> sw.performClick());
        row.setMinimumHeight(dp(72));
        parent.addView(row, match(-2));
    }

    private void permission(LinearLayout parent, String title, boolean granted) {
        LinearLayout row = row(title, granted ? "Granted · manage in Android settings" : "Not granted · open Android settings");
        TextView arrow = Ui.text(this, "›", 25, Ui.FAINT, false);
        row.addView(arrow);
        row.setBackground(Ui.ripple(this, Ui.SURFACE, 12));
        row.setOnClickListener(v -> {
            Intent intent = "Notifications".equals(title)
                    ? new Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, getPackageName())
                    : new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + getPackageName()));
            startActivity(intent);
        });
        row.setMinimumHeight(dp(68));
        parent.addView(row, match(-2));
    }

    private LinearLayout row(String title, String subtitle) {
        LinearLayout row = new LinearLayout(this);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(16), dp(5), dp(16), dp(5));
        LinearLayout copy = new LinearLayout(this);
        copy.setOrientation(LinearLayout.VERTICAL);
        copy.setPadding(0, 0, dp(12), 0); // keep text clear of the trailing switch/arrow
        copy.addView(Ui.text(this, title, 15, Ui.TEXT, false));
        TextView sub = Ui.text(this, subtitle, 12, Ui.SUB, false);
        sub.setMaxLines(2);
        copy.addView(sub);
        row.addView(copy, new LinearLayout.LayoutParams(0, -2, 1));
        return row;
    }

    private View activityRow(ActivityLog.Entry entry) {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(16), dp(10), dp(16), dp(10));
        card.setBackground(Ui.ripple(this, Ui.SURFACE, 0));
        String icon = "query".equals(entry.kind) ? "⌕" : "push".equals(entry.kind) ? "↑"
                : "approval".equals(entry.kind) ? "✓" : "restart".equals(entry.kind) ? "↻" : "⚡";
        String relative = DateUtils.getRelativeTimeSpanString(entry.ts, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS).toString();
        TextView line = Ui.text(this, icon + "  " + entry.title, 14, entry.ok ? Ui.TEXT : Ui.RED, false);
        line.setMaxLines(2);
        card.addView(line);
        TextView meta = Ui.text(this, relative + "  ·  " + (entry.ok ? "OK" : "FAILED"), 11,
                entry.ok ? Ui.GREEN : Ui.RED, true);
        meta.setPadding(dp(26), dp(3), 0, 0);
        card.addView(meta);
        TextView detail = Ui.text(this, entry.detail, 12, Ui.SUB, false);
        detail.setTextIsSelectable(true);
        detail.setPadding(dp(26), dp(8), 0, dp(3));
        detail.setVisibility(View.GONE);
        card.addView(detail);
        card.setOnClickListener(v -> detail.setVisibility(detail.getVisibility() == View.GONE ? View.VISIBLE : View.GONE));
        return card;
    }

    private TextView button(String title, Runnable action) {
        TextView button = Ui.text(this, title, 12, Ui.BLUE, true);
        button.setGravity(Gravity.CENTER);
        button.setBackground(Ui.ripple(this, Ui.SURFACE, 10));
        button.setOnClickListener(v -> action.run());
        return button;
    }

    private boolean granted(String permission) { return checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED; }

    private boolean listenerAccess() {
        String enabled = Settings.Secure.getString(getContentResolver(), "enabled_notification_listeners");
        if (enabled == null) return false;
        ComponentName own = new ComponentName(this, ReplyListenerService.class);
        for (String name : enabled.split(":")) if (own.equals(ComponentName.unflattenFromString(name))) return true;
        return false;
    }

    private void export() {
        logWorker.submit(() -> {
            StringBuilder text = new StringBuilder("Hermes Companion activity\n\n");
            for (ActivityLog.Entry entry : ActivityLog.recent(this, 100)) {
                text.append(DateFormat.getDateTimeInstance().format(new Date(entry.ts))).append(" · ")
                        .append(entry.kind).append(" · ").append(entry.ok ? "OK" : "FAILED").append(" · ")
                        .append(entry.title).append('\n').append(entry.detail).append("\n\n");
            }
            runOnUiThread(() -> startActivity(Intent.createChooser(new Intent(Intent.ACTION_SEND).setType("text/plain")
                    .putExtra(Intent.EXTRA_TEXT, text.toString()), "Export activity")));
        });
    }

    @Override protected void onDestroy() { super.onDestroy(); logWorker.shutdown(); }
    @Override public void onBackPressed() { Ui.back(this); }

    private String appLabel(String pkg) {
        try { return String.valueOf(getPackageManager().getApplicationLabel(getPackageManager().getApplicationInfo(pkg, 0))); }
        catch (Exception e) { return pkg; }
    }

    /** Every app that can be opened from the launcher, to add one to the read-aloud list. */
    private void pickReadApp() {
        android.content.Intent main = new android.content.Intent(android.content.Intent.ACTION_MAIN).addCategory(android.content.Intent.CATEGORY_LAUNCHER);
        java.util.List<android.content.pm.ResolveInfo> apps = getPackageManager().queryIntentActivities(main, 0);
        java.util.TreeMap<String, String> byName = new java.util.TreeMap<>(String.CASE_INSENSITIVE_ORDER);
        for (android.content.pm.ResolveInfo ri : apps) {
            String pkg = ri.activityInfo.packageName;
            if (!pkg.equals(getPackageName())) byName.put(String.valueOf(ri.loadLabel(getPackageManager())), pkg);
        }
        java.util.List<Sheets.Item> items = new java.util.ArrayList<>();
        for (java.util.Map.Entry<String, String> e : byName.entrySet()) {
            items.add(Sheets.Item.of(R.drawable.ic_speaker, Ui.SUB, e.getKey(), e.getValue(), () -> {
                java.util.Set<String> next = NotificationReader.allowedApps(store);
                next.add(e.getValue());
                store.readNotifApps(next);
                render();
            }));
        }
        Sheets.actions(this, "Read aloud", "Pick an app", items.toArray(new Sheets.Item[0]));
    }
}
