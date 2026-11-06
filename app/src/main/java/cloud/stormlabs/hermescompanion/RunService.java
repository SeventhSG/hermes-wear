package cloud.stormlabs.hermescompanion;

import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;

import java.util.List;

/**
 * Keeps the process (and so every open Hermes stream) alive while a run is in flight.
 *
 * Closing the stream interrupts the run server-side, so without this a reply the user is waiting
 * for dies the moment they switch apps. The service stops itself when no run is active.
 */
public final class RunService extends Service {
    private static volatile boolean running, pending;
    private static String lastSig = "";
    private static long lastPost;

    static void ensureRunning(Context c) {
        if (running) { refresh(c); return; }
        try {
            Intent i = new Intent(c, RunService.class);
            if (Build.VERSION.SDK_INT >= 26) c.startForegroundService(i); else c.startService(i);
        } catch (Throwable ignored) {
            // Background-start restrictions: the run still works while the app stays alive.
        }
    }

    static void refresh(Context c) {
        if (!running) return;
        List<RunManager.LiveRun> runs = RunManager.get(c).activeRuns();
        if (runs.isEmpty()) { c.stopService(new Intent(c, RunService.class)); return; }
        long now = android.os.SystemClock.elapsedRealtime();
        String sig = runs.size() + "|" + Notifier.runText(runs.get(0)) + "|" + runs.get(0).tools.size();
        if (sig.equals(lastSig)) return;
        if (now - lastPost < 1000) { // rate limit: post the latest state once the second is up
            if (!pending) { pending = true; new Handler(Looper.getMainLooper()).postDelayed(() -> { pending = false; refresh(c); }, 1000 - (now - lastPost)); }
            return;
        }
        lastSig = sig; lastPost = now;
        Notifier.post(c, Notifier.ID_RUNNING, Notifier.running(c, runs));
    }

    @Override public void onCreate() { super.onCreate(); running = true; lastSig = ""; }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        List<RunManager.LiveRun> runs = RunManager.get(this).activeRuns();
        try {
            if (Build.VERSION.SDK_INT >= 29) startForeground(Notifier.ID_RUNNING, Notifier.running(this, runs), ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC);
            else startForeground(Notifier.ID_RUNNING, Notifier.running(this, runs));
        } catch (Throwable t) {
            running = false; stopSelf(); return START_NOT_STICKY;
        }
        if (runs.isEmpty()) stopSelf();
        return START_NOT_STICKY;
    }

    @Override public void onTimeout(int startId, int fgsType) { stopSelf(); }
    @Override public void onDestroy() { running = false; super.onDestroy(); }
    @Override public IBinder onBind(Intent intent) { return null; }
}
