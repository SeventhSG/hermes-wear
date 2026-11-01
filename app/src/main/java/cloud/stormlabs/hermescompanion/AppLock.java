package cloud.stormlabs.hermescompanion;

import android.app.Activity;
import android.app.KeyguardManager;
import android.content.Context;
import android.graphics.Color;
import android.os.Build;
import android.os.SystemClock;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * Optional app lock: the app is covered until the phone's own biometrics or screen lock confirm it is you,
 * on a cold start and after a minute in the background. With the lock on, Recents shows no preview.
 */
final class AppLock {
    private AppLock() {}

    static final long GRACE_MS = 60_000;
    private static boolean locked = true;
    private static long backgroundAt;
    private static int started;
    private static boolean prompting, autoAsked;

    /** Locking needs a screen lock to check against; without one the setting can't be turned on. */
    static boolean available(Context c) {
        KeyguardManager k = (KeyguardManager) c.getSystemService(Context.KEYGUARD_SERVICE);
        return k != null && k.isDeviceSecure();
    }

    /** True when coming back to the app should ask again (pure, for tests). */
    static boolean shouldLock(boolean wasLocked, long backgroundAt, long now) {
        return wasLocked || backgroundAt > 0 && now - backgroundAt >= GRACE_MS;
    }

    static void onStarted(Activity a) {
        if (started++ == 0 && shouldLock(locked, backgroundAt, SystemClock.elapsedRealtime())) { locked = true; autoAsked = false; }
        backgroundAt = 0;
    }

    static void onStopped(Activity a) {
        if (--started <= 0) { started = 0; backgroundAt = SystemClock.elapsedRealtime(); }
    }

    static void onResumed(Activity a) {
        boolean on = new SettingsStore(a).appLock() && available(a);
        if (Build.VERSION.SDK_INT >= 33) a.setRecentsScreenshotEnabled(!on);
        View cover = a.getWindow().getDecorView().findViewWithTag(TAG);
        if (!on || !locked) { if (cover != null) ((ViewGroup) cover.getParent()).removeView(cover); if (!on) locked = false; return; }
        if (cover == null) ((ViewGroup) a.getWindow().getDecorView()).addView(cover(a), new FrameLayout.LayoutParams(-1, -1));
        // Ask by itself once per lock; after a cancel the prompt's own dismissal resumes us, so wait for Unlock.
        if (!autoAsked) { autoAsked = true; ask(a); }
    }

    private static final String TAG = "app-lock-cover";

    private static void ask(Activity a) {
        if (prompting) return;
        prompting = true;
        Confirm.strong(a, "Unlock Hermes", "Confirm it’s you", () -> {
            prompting = false; locked = false;
            View cover = a.getWindow().getDecorView().findViewWithTag(TAG);
            if (cover != null) cover.animate().alpha(0f).setDuration(Ui.motionEnabled() ? 180 : 0).withEndAction(() -> {
                if (cover.getParent() != null) ((ViewGroup) cover.getParent()).removeView(cover);
            }).start();
        }, () -> prompting = false);
    }

    private static View cover(Activity a) {
        LinearLayout box = new LinearLayout(a);
        box.setTag(TAG);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setGravity(Gravity.CENTER);
        box.setBackgroundColor(Ui.BG == 0 ? Color.BLACK : Ui.BG);
        box.setClickable(true); // nothing underneath can be touched
        box.addView(Ui.hermesLogo(a, 72), new LinearLayout.LayoutParams(Ui.dp(a, 72), Ui.dp(a, 72)));
        TextView t = Ui.title(a, "Hermes is locked", Ui.TEXT);
        t.setGravity(Gravity.CENTER); t.setPadding(0, Ui.dp(a, 18), 0, Ui.dp(a, 18));
        box.addView(t);
        TextView unlock = Ui.primaryButton(a, "Unlock", Ui.BLUE);
        unlock.setOnClickListener(v -> ask(a));
        box.addView(unlock, new LinearLayout.LayoutParams(Ui.dp(a, 200), Ui.dp(a, 52)));
        return box;
    }
}
