package cloud.stormlabs.hermescompanion;

import android.Manifest;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.location.LocationManager;
import android.net.Uri;
import android.os.Build;

import java.util.List;

/** Framework proximity alerts survive process death; boot requires registration again. */
public final class PresenceReceiver extends BroadcastReceiver {
    private static final String ACTION = "cloud.stormlabs.hermescompanion.PRESENCE";

    static void sync(Context context) {
        for (HomeData.Place p : new SettingsStore(context).places()) register(context, p);
    }

    static void register(Context context, HomeData.Place p) {
        LocationManager lm = (LocationManager) context.getSystemService(Context.LOCATION_SERVICE);
        if (lm == null) return;
        boolean fine = context.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED;
        boolean background = Build.VERSION.SDK_INT < 29 || context.checkSelfPermission(Manifest.permission.ACCESS_BACKGROUND_LOCATION) == PackageManager.PERMISSION_GRANTED;
        PendingIntent pi = intent(context, p.id);
        try { lm.removeProximityAlert(pi); } catch (SecurityException ignored) { }
        if (p.enabled && fine && background) {
            new SettingsStore(context).markPlaceRegistered(p.id);
            try {
                lm.addProximityAlert(p.latitude, p.longitude, p.radius, -1, pi);
            }
            catch (SecurityException ignored) { }
        }
    }

    static void remove(Context context, String id) {
        LocationManager lm = (LocationManager) context.getSystemService(Context.LOCATION_SERVICE);
        if (lm != null) try { lm.removeProximityAlert(intent(context, id)); } catch (SecurityException ignored) { }
        new SettingsStore(context).forgetPlace(id);
    }

    private static PendingIntent intent(Context c, String id) {
        Intent i = new Intent(c, PresenceReceiver.class).setAction(ACTION).setData(Uri.parse("hermes-presence:" + id));
        return PendingIntent.getBroadcast(c, 0, i, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_MUTABLE);
    }

    @Override public void onReceive(Context context, Intent intent) {
        // The app's only boot/update hook, so it also brings back the wake word.
        if (Intent.ACTION_BOOT_COMPLETED.equals(intent.getAction())) {
            // elapsedRealtime restarts at boot; expire any pause from the previous boot.
            new SettingsStore(context).wakePause(0, 0);
            sync(context); WakeWordService.onBoot(context); return;
        }
        if (Intent.ACTION_MY_PACKAGE_REPLACED.equals(intent.getAction())) { WakeWordService.onBoot(context); return; }
        if (!ACTION.equals(intent.getAction()) || intent.getData() == null) return;
        String id = intent.getData().getSchemeSpecificPart();
        boolean entering = intent.getBooleanExtra(LocationManager.KEY_PROXIMITY_ENTERING, false);
        HomeData.Place place = null;
        SettingsStore state = new SettingsStore(context);
        List<HomeData.Place> places = state.places();
        for (HomeData.Place p : places) if (p.id.equals(id) && p.enabled) { place = p; break; }
        if (place == null) return;
        int lastState = state.placeState(id);
        long now = System.currentTimeMillis();
        boolean shouldRun = PresenceDecision.shouldRun(entering, lastState, state.placeLastRun(id), state.placeRegisteredAt(id), now);
        state.placeState(id, entering);
        if (!shouldRun) return;
        String phrase = entering ? place.arrive : place.leave;
        if (phrase.trim().isEmpty()) return;
        state.placeLastRun(id, now);
        String title = place.name + (entering ? " · arrived" : " · left");
        Context app = context.getApplicationContext();
        PendingResult pending = goAsync();
        HomeData.Place target = place;
        new Thread(() -> {
            try {
                SettingsStore store = new SettingsStore(app);
                String profileId = store.profileId();
                AgentBackend api = Backends.create(store, profileId);
                String sid = api.ensureSession("Home").id; store.homeSessionId(sid);
                RunManager manager = RunManager.get(app);
                RunManager.LiveRun run = manager.start(api, profileId, sid, "Home", phrase, DeviceContext.systemMessage(app, store), phrase, true);
                if (run == null) {
                    ActivityLog.log(app, "presence", title, "Home session busy", false, sid);
                    Notifier.presence(app, title, "Home session busy", profileId, sid);
                    return;
                }
                String session = sid;
                RunManager.Observer observer = new RunManager.Observer() {
                    @Override public void onRunUpdate(RunManager.LiveRun updated, String kind) {
                        if (updated != run || (!RunManager.UPDATE_FINISHED.equals(kind) && !RunManager.UPDATE_ERROR.equals(kind))) return;
                        manager.removeObserver(this);
                        boolean ok = updated.error == null && updated.outcome != null && updated.outcome.completed();
                        String reply = updated.error == null ? updated.finalText() : updated.error;
                        ActivityLog.log(app, "presence", title, reply, ok, session);
                        Notifier.presence(app, title, reply.isEmpty() ? "Done" : reply, run.profileId, session);
                    }
                };
                manager.addObserver(observer);
                if (!run.active()) observer.onRunUpdate(run, run.error == null ? RunManager.UPDATE_FINISHED : RunManager.UPDATE_ERROR);
            } catch (Exception e) { ActivityLog.log(app, "presence", title, HermesApi.friendly(e), false, null); }
            finally { pending.finish(); }
        }, "presence-run").start();
    }
}
