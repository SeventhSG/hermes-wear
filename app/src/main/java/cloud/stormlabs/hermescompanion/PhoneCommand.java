package cloud.stormlabs.hermescompanion;

import android.app.Notification;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.provider.Settings;

import org.json.JSONObject;

import java.util.Collections;
import java.util.List;

/**
 * The phone as a headless node: Hermes, in any channel (Telegram, voice, cron), calls a phone tool on the hub;
 * the hub pushes {id, action, args} here over FCM; the action runs through Android APIs (no screen to drive)
 * and the result goes back to the hub. Sensitive actions follow the action mode: when it asks for confirmation,
 * a notification with Allow / Deny decides.
 */
final class PhoneCommand {
    private PhoneCommand() {}

    static final String ACTION_ALLOW = "cloud.stormlabs.hermescompanion.PHONE_ALLOW", ACTION_DENY = "cloud.stormlabs.hermescompanion.PHONE_DENY";

    /** From FcmService, on its worker thread. */
    static void handle(Context c, String json) {
        String id = "";
        try {
            JSONObject o = new JSONObject(json);
            id = o.optString("id");
            String type = o.optString("action");
            JSONObject args = o.optJSONObject("args") == null ? new JSONObject() : o.optJSONObject("args");
            SettingsStore store = new SettingsStore(c);
            if (!store.remotePhoneActions()) { report(c, id, false, "refused", "Remote phone actions are off in the app (Settings › Privacy & control)", ""); return; }
            if (!store.deviceActions() || store.agentPaused()) { report(c, id, false, "refused", "Phone actions are paused in the app", ""); return; }
            if ("call_me".equals(type)) {   // Hermes rings the user (not a phone call): an incoming-call notification
                String say = args.optString("text", args.optString("message", "")).trim();
                IncomingCall.ring(c, say);
                report(c, id, true, "done", "Ringing the phone", "");
                return;
            }
            DeviceActions.Action action = new DeviceActions.Action(type, args);
            if (!DeviceActions.isQuery(type) && DeviceActions.needsConfirmation(action, store)) {
                askFirst(c, id, json, action);
                report(c, id, false, "pending", "Waiting for the user to allow it on the phone: " + action.describe(), "");
                return;
            }
            run(c, id, action, false);
        } catch (Exception e) {
            report(c, id, false, "error", "The phone couldn't run that: " + e.getMessage(), "");
        }
    }

    private static void run(Context c, String id, DeviceActions.Action action, boolean approved) {
        List<DeviceActionRunner.Outcome> r = DeviceActionRunner.runAll(c, Collections.singletonList(action), null, null, approved);
        DeviceActionRunner.Outcome o = r.isEmpty() ? null : r.get(0);
        if (o == null) { report(c, id, false, "error", "Nothing ran", ""); return; }
        ActivityLog.log(c, "remote", "Hermes (remote) · " + action.describe(), o.message, o.ok, null, null);
        report(c, id, o.ok, "done", o.message, o.result);
    }

    private static void askFirst(Context c, String id, String json, DeviceActions.Action action) {
        Notifier.channels(c);
        int nid = 4500 + Math.abs(id.hashCode() % 400);
        Intent allow = new Intent(c, Receiver.class).setAction(ACTION_ALLOW).putExtra("json", json).putExtra("nid", nid);
        Intent deny = new Intent(c, Receiver.class).setAction(ACTION_DENY).putExtra("json", json).putExtra("nid", nid);
        int flags = PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE;
        Notification.Builder b = Build.VERSION.SDK_INT >= 26 ? new Notification.Builder(c, Notifier.CH_APPROVALS) : new Notification.Builder(c);
        b.setSmallIcon(R.drawable.ic_notification).setContentTitle("Hermes wants to: " + action.describe())
                .setContentText("Requested remotely. Allow it on this phone?").setAutoCancel(true)
                .setStyle(new Notification.BigTextStyle().bigText("Requested remotely. Allow it on this phone?\n" + action.describe()))
                .setCategory(Notification.CATEGORY_REMINDER).setTimeoutAfter(10 * 60_000L).setOnlyAlertOnce(true)
                .addAction(new Notification.Action.Builder(null, "Allow", PendingIntent.getBroadcast(c, nid * 2, allow, flags)).build())
                .addAction(new Notification.Action.Builder(null, "Deny", PendingIntent.getBroadcast(c, nid * 2 + 1, deny, flags)).build());
        NotificationManager nm = c.getSystemService(NotificationManager.class);
        if (nm != null) nm.notify(nid, b.build());
    }

    /** Allow / Deny on the confirmation notification. */
    public static final class Receiver extends BroadcastReceiver {
        @Override public void onReceive(Context c, Intent intent) {
            NotificationManager nm = c.getSystemService(NotificationManager.class);
            if (nm != null) nm.cancel(intent.getIntExtra("nid", 0));
            String json = intent.getStringExtra("json");
            if (json == null) return;
            PendingResult pending = goAsync();
            new Thread(() -> {
                try {
                    JSONObject o = new JSONObject(json);
                    String id = o.optString("id");
                    DeviceActions.Action action = new DeviceActions.Action(o.optString("action"), o.optJSONObject("args") == null ? new JSONObject() : o.optJSONObject("args"));
                    if (ACTION_ALLOW.equals(intent.getAction())) run(c.getApplicationContext(), id, action, true);
                    else report(c, id, false, "denied", "The user denied: " + action.describe(), "");
                } catch (Exception ignored) {
                } finally { pending.finish(); }
            }, "phone-approval").start();
        }
    }

    private static void report(Context c, String id, boolean ok, String status, String message, String result) {
        SettingsStore store = new SettingsStore(c);
        try {
            new HubApi(store.hubUrl(), store.hubKey()).phoneResult(new JSONObject().put("id", id).put("ok", ok).put("status", status)
                    .put("message", message == null ? "" : message).put("result", result == null ? "" : result));
        } catch (Exception ignored) { /* the hub reports "sent" and the action log still has it */ }
    }

    /** Tell the hub this phone exists and how to reach it (on every push-token refresh / app resume). */
    static void hello(Context c, String hubUrl, String hubKey, String endpoint) {
        try {
            new HubApi(hubUrl, hubKey).phoneHello(new JSONObject().put("name", deviceName(c)).put("endpoint", endpoint)
                    .put("model", Build.MANUFACTURER + " " + Build.MODEL));
        } catch (Exception ignored) {}
    }

    static String deviceName(Context c) {
        String n = null;
        try { n = Settings.Global.getString(c.getContentResolver(), Settings.Global.DEVICE_NAME); } catch (Exception ignored) {}
        return n == null || n.trim().isEmpty() ? Build.MODEL : n.trim();
    }
}
