package cloud.stormlabs.hermescompanion;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.IBinder;
import android.util.DisplayMetrics;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import rikka.shizuku.Shizuku;

/**
 * Hermes' hidden screen ("Work in the background"). With Shizuku, a shell-level service creates a virtual
 * display; Hermes opens apps there and screen control reads, taps and types on it, so the phone stays free.
 * Without Shizuku everything falls back to the real screen.
 */
final class AgentDisplay {
    private AgentDisplay() {}

    static final String SHIZUKU_PACKAGE = "moe.shizuku.privileged.api";
    private static volatile IAgentDisplay service;
    private static volatile int displayId = -1;
    private static volatile long lastUse;

    enum State { NEEDS_ANDROID_11, NOT_INSTALLED, NOT_RUNNING, NEEDS_PERMISSION, READY }

    static State state(Context c) {
        if (Build.VERSION.SDK_INT < 30) return State.NEEDS_ANDROID_11;
        try { c.getPackageManager().getPackageInfo(SHIZUKU_PACKAGE, 0); } catch (PackageManager.NameNotFoundException e) { return State.NOT_INSTALLED; }
        try {
            if (!Shizuku.pingBinder()) return State.NOT_RUNNING;
            return Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED ? State.READY : State.NEEDS_PERMISSION;
        } catch (Throwable t) { return State.NOT_RUNNING; }
    }

    static String label(State s) {
        switch (s) {
            case NEEDS_ANDROID_11: return "Needs Android 11 or newer";
            case NOT_INSTALLED: return "Install Shizuku to let Hermes work on a hidden screen";
            case NOT_RUNNING: return "Start Shizuku (wireless debugging) to use it";
            case NEEDS_PERMISSION: return "Allow Hermes in Shizuku";
            default: return displayId > 0 ? "Hidden screen ready · display " + displayId : "Ready";
        }
    }

    static void requestPermission() { try { Shizuku.requestPermission(7301); } catch (Throwable ignored) {} }

    /** True when Hermes' screen work should go to the hidden display. */
    static boolean enabled(Context c) { return new SettingsStore(c).backgroundAgent() && state(c) == State.READY; }

    /** Binds the shell service and creates the display. Network/worker thread only; -1 when unavailable. */
    static synchronized int ensure(Context c) {
        lastUse = System.currentTimeMillis();
        if (displayId > 0 && service != null && service.asBinder().isBinderAlive()) return displayId;
        if (!enabled(c)) return -1;
        IAgentDisplay s = bind(c);
        if (s == null) return -1;
        try {
            DisplayMetrics m = c.getResources().getDisplayMetrics();
            displayId = s.create(m.widthPixels, m.heightPixels, m.densityDpi);
        } catch (Exception e) { displayId = -1; }
        HermesAccessibilityService.agentDisplay(displayId);
        return displayId;
    }

    static int displayId() { return displayId; }

    /** Ready to launch right now without waiting for a bind (safe on the main thread). */
    static boolean prepared() { IAgentDisplay s = service; return displayId > 0 && s != null && s.asBinder().isBinderAlive(); }

    private static boolean warned;

    /** Background mode is on but Shizuku stopped (it does after every reboot): say so once, with a one-tap way back. */
    static void warnIfStopped(Context c) {
        if (warned || !new SettingsStore(c).backgroundAgent() || state(c) != State.NOT_RUNNING) return;
        warned = true;
        Intent open = c.getPackageManager().getLaunchIntentForPackage(SHIZUKU_PACKAGE);
        if (open == null) return;
        Notifier.channels(c);
        android.app.PendingIntent tap = android.app.PendingIntent.getActivity(c, 7302, open, android.app.PendingIntent.FLAG_IMMUTABLE | android.app.PendingIntent.FLAG_UPDATE_CURRENT);
        android.app.Notification.Builder b = Build.VERSION.SDK_INT >= 26 ? new android.app.Notification.Builder(c, Notifier.CH_EVENTS) : new android.app.Notification.Builder(c);
        b.setSmallIcon(R.drawable.ic_notification).setContentTitle("Hermes works on your screen for now")
                .setContentText("Shizuku stopped (it does after a restart). Tap to start it so Hermes can work in the background again.")
                .setContentIntent(tap).setAutoCancel(true);
        android.app.NotificationManager nm = c.getSystemService(android.app.NotificationManager.class);
        if (nm != null) nm.notify(7302, b.build());
    }

    /** Bind and create the display ahead of time, off the main thread. */
    static void prepare(Context c) {
        Context app = c.getApplicationContext();
        if (prepared() || !enabled(app)) return;
        new Thread(() -> ensure(app), "agent-display").start();
    }

    private static IAgentDisplay bind(Context c) {
        if (service != null && service.asBinder().isBinderAlive()) return service;
        CountDownLatch done = new CountDownLatch(1);
        Shizuku.UserServiceArgs args = new Shizuku.UserServiceArgs(new ComponentName(c.getPackageName(), AgentDisplayService.class.getName()))
                .daemon(false).processNameSuffix("agent").version(versionCode(c));
        ServiceConnection conn = new ServiceConnection() {
            @Override public void onServiceConnected(ComponentName n, IBinder b) { service = IAgentDisplay.Stub.asInterface(b); done.countDown(); }
            @Override public void onServiceDisconnected(ComponentName n) { service = null; displayId = -1; HermesAccessibilityService.agentDisplay(-1); }
        };
        try {
            Shizuku.bindUserService(args, conn);
            done.await(8, TimeUnit.SECONDS);
        } catch (Throwable t) { return null; }
        return service;
    }

    private static int versionCode(Context c) {
        try { return (int) c.getPackageManager().getPackageInfo(c.getPackageName(), 0).getLongVersionCode(); } catch (Exception e) { return 1; }
    }

    /** am start on the hidden display; true if the shell accepted it. */
    static boolean launch(Context c, Intent intent) {
        int id = ensure(c);
        if (id <= 0) return false;
        StringBuilder cmd = new StringBuilder("am start --display ").append(id).append(" --windowingMode 1 -f 0x10000000");
        if (intent.getAction() != null) cmd.append(" -a ").append(quote(intent.getAction()));
        if (intent.getData() != null) cmd.append(" -d ").append(quote(intent.getData().toString()));
        if (intent.getComponent() != null) cmd.append(" -n ").append(quote(intent.getComponent().flattenToShortString()));
        else if (intent.getPackage() != null) cmd.append(" -p ").append(quote(intent.getPackage()));
        if (intent.getCategories() != null) for (String cat : intent.getCategories()) cmd.append(" -c ").append(quote(cat));
        String out = exec(cmd.toString());
        boolean ok = out != null && !out.contains("Error") && !out.contains("error:") && !out.contains("Exception");
        if (ok) HermesAccessibilityService.useHiddenDisplay();
        return ok;
    }

    /** back / home / enter on the hidden display. */
    static boolean key(String keycode) {
        int id = displayId;
        if (id <= 0) return false;
        String out = exec("input -d " + id + " keyevent " + keycode);
        return out != null && !out.contains("error");
    }

    static String exec(String command) {
        IAgentDisplay s = service;
        if (s == null) return null;
        try { lastUse = System.currentTimeMillis(); return s.exec(command); } catch (Exception e) { return null; }
    }

    static byte[] screenshot() {
        IAgentDisplay s = service;
        try { return s == null ? new byte[0] : s.screenshot(70); } catch (Exception e) { return new byte[0]; }
    }

    /** Brings what Hermes left on the hidden screen to the real one (moves the top task to display 0). */
    static String showOnPhone() {
        int id = displayId;
        if (id <= 0) return "Nothing on the hidden screen";
        String tasks = exec("am stack list");
        if (tasks == null) return "Hidden screen unavailable";
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("taskId=(\\d+):[^\\n]*?displayId=" + id).matcher(tasks);
        String task = null;
        while (m.find()) { task = m.group(1); break; }
        if (task == null) return "Nothing on the hidden screen";
        exec("am display move-stack " + task + " 0");
        return "Moved to your screen";
    }

    static void release() {
        IAgentDisplay s = service;
        displayId = -1;
        HermesAccessibilityService.agentDisplay(-1);
        try { if (s != null) s.release(); } catch (Exception ignored) {}
    }

    private static String quote(String s) { return "'" + s.replace("'", "'\\''") + "'"; }
}
