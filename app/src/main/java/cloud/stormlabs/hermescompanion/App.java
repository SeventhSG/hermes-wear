package cloud.stormlabs.hermescompanion;

import android.app.Activity;
import android.app.Application;
import android.os.Build;
import android.os.Bundle;
import android.window.OnBackInvokedDispatcher;

/**
 * Predictive back (enableOnBackInvokedCallback): on API 33+ the system no longer calls onBackPressed. Activities
 * that override it get a callback that still routes Back there; the rest get the system's predictive animation.
 * Also drives the optional app lock ({@link AppLock}).
 */
public final class App extends Application {
    @Override public void onCreate() {
        super.onCreate();
        Reach.configure(new SettingsStore(this));
        Reach.raceOnFirstUse = true;
        DeviceCert.load(this);
        // Wi-Fi <-> mobile data: pick the address that works on the new network before the next request needs it.
        try {
            ((android.net.ConnectivityManager) getSystemService(CONNECTIVITY_SERVICE)).registerDefaultNetworkCallback(
                    new android.net.ConnectivityManager.NetworkCallback() {
                        @Override public void onAvailable(android.net.Network n) { Reach.networkChanged(); HubApi.abortTermCalls(); }
                        @Override public void onLost(android.net.Network n) { HubApi.abortTermCalls(); }
                    });
        } catch (Exception ignored) {}
        registerActivityLifecycleCallbacks(new ActivityLifecycleCallbacks() {
            @Override public void onActivityCreated(Activity a, Bundle b) {
                if (Build.VERSION.SDK_INT >= 33 && overridesBack(a)) a.getOnBackInvokedDispatcher().registerOnBackInvokedCallback(
                        OnBackInvokedDispatcher.PRIORITY_DEFAULT, a::onBackPressed);
            }
            @Override public void onActivityStarted(Activity a) { AppLock.onStarted(a); }
            @Override public void onActivityResumed(Activity a) { if (!(a instanceof CrashActivity)) AppLock.onResumed(a); }
            @Override public void onActivityPaused(Activity a) { }
            @Override public void onActivityStopped(Activity a) { AppLock.onStopped(a); }
            @Override public void onActivitySaveInstanceState(Activity a, Bundle b) { }
            @Override public void onActivityDestroyed(Activity a) { }
        });
    }

    static boolean overridesBack(Activity a) {
        try { return a.getClass().getMethod("onBackPressed").getDeclaringClass() != Activity.class; }
        catch (NoSuchMethodException e) { return false; }
    }
}
