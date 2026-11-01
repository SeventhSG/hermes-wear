package cloud.stormlabs.hermescompanion;

import android.app.Notification;
import android.app.NotificationManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.media.AudioAttributes;
import android.media.AudioDeviceInfo;
import android.media.AudioFocusRequest;
import android.media.AudioManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.Parcelable;
import android.os.PowerManager;
import android.service.notification.StatusBarNotification;

import java.util.ArrayList;
import java.util.List;

/**
 * Reads notifications aloud into your earbuds while the screen is off (Settings › Voice › Read notifications aloud).
 * Driven by {@link ReplyListenerService}; speaks through {@link Speaker}, so the homelab voice and its pitch, speed and
 * phone fallback all apply. The speaker lives only while there is something to say.
 */
final class NotificationReader {
    private NotificationReader() { }

    private static final long IDLE_MS = 90_000, DEDUPE_MS = 60_000;
    private static final int MAX_QUEUE = 4;
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static final NotificationSpeech.Recent RECENT = new NotificationSpeech.Recent(DEDUPE_MS);

    private static Speaker speaker;
    private static AudioFocusRequest focus;
    private static BroadcastReceiver screenReceiver;
    private static int pending, dropped, nextId;
    private static Context app;

    /** Called for every posted notification (main thread). Cheap when the feature is off. */
    static void consider(Context context, StatusBarNotification sbn, int importance) {
        SettingsStore store = new SettingsStore(context);
        if (!store.readNotifications()) return;
        Notification n = sbn.getNotification();
        if (n == null) return;
        NotificationSpeech.Situation s = new NotificationSpeech.Situation();
        s.enabled = true;
        s.agentPaused = store.agentPaused();
        s.ownApp = sbn.getPackageName().equals(context.getPackageName());
        s.appAllowed = allowedApps(store).contains(sbn.getPackageName());
        s.ongoing = (n.flags & Notification.FLAG_ONGOING_EVENT) != 0;
        s.groupSummary = (n.flags & Notification.FLAG_GROUP_SUMMARY) != 0;
        s.category = n.category == null ? "" : n.category;
        s.silentChannel = importance >= 0 && importance < NotificationManager.IMPORTANCE_DEFAULT;   // a silent channel is silent for a reason
        s.screenOffOnly = store.readNotifScreenOffOnly();
        s.screenOn = screenOn(context);
        s.headphonesOnly = store.readNotifHeadphonesOnly();
        s.headphones = s.headphonesOnly && headphones(context);
        s.inCall = inCall(context);
        s.quietNow = store.quietHours() && Notifier.quietNow(store.quietStart(), store.quietEnd(), java.util.Calendar.getInstance().get(java.util.Calendar.HOUR_OF_DAY));
        if (NotificationSpeech.skipReason(s) != null) return;

        Bundle extras = n.extras;
        if (extras == null) return;
        String title = str(extras.getCharSequence(Notification.EXTRA_TITLE)), text = str(extras.getCharSequence(Notification.EXTRA_TEXT));
        ReplyExtract.Result r = ReplyExtract.extract(false, title, text, str(extras.getCharSequence("android.conversationTitle")),
                extras.getBoolean("android.isGroupConversation"), messages(extras));
        String sender = r != null ? r.sender : title, message = r != null ? r.message : text;
        if ((sender == null || sender.trim().isEmpty()) && (message == null || message.trim().isEmpty())) return;
        if (!RECENT.fresh(sbn.getKey(), message == null ? "" : message, System.currentTimeMillis())) return;
        say(context, store, NotificationSpeech.phrase(appName(context, sbn.getPackageName()), sender, message, store.readNotifContent()));
    }

    static List<String> defaultApps() {
        List<String> out = new ArrayList<>();
        for (String[] app : ReplyListenerService.SUGGESTED_APPS) out.add(app[0]);
        return out;
    }

    static java.util.Set<String> allowedApps(SettingsStore store) {
        java.util.Set<String> chosen = store.readNotifApps();
        return chosen.isEmpty() ? new java.util.HashSet<>(defaultApps()) : chosen;
    }

    // ── speaking ────────────────────────────────────────────────────────────────────────────────

    private static void say(Context context, SettingsStore store, String phrase) {
        app = context.getApplicationContext();
        if (pending >= MAX_QUEUE) { dropped++; return; }
        if (!takeFocus()) return;
        if (speaker == null) {
            speaker = new Speaker(app, store);
            speaker.onDone(id -> MAIN.post(() -> done(id)));
            watchScreen(store);
        }
        MAIN.removeCallbacks(release);
        pending++;
        speaker.enqueue(phrase, "notif-" + (++nextId));
    }

    private static void done(String id) {
        if (pending > 0) pending--;
        if (pending == 0) {
            if (dropped > 0 && speaker != null) {
                String more = NotificationSpeech.overflow(dropped);
                dropped = 0;
                pending++;
                speaker.enqueue(more, "notif-more");
                return;
            }
            MAIN.postDelayed(release, 2500);   // stay briefly for a follow-up message, then let the music come back
        }
    }

    private static final Runnable shutdown = () -> {
        if (pending > 0) return;
        if (speaker != null) speaker.shutdown();
        speaker = null;
        if (screenReceiver != null && app != null) { try { app.unregisterReceiver(screenReceiver); } catch (Exception ignored) { } screenReceiver = null; }
    };

    private static final Runnable release = () -> {
        if (pending > 0) return;
        giveFocus();
        MAIN.postDelayed(shutdown, IDLE_MS);
    };

    /** The user picked the phone up: stop talking. */
    private static void watchScreen(SettingsStore store) {
        if (!store.readNotifScreenOffOnly() || app == null || screenReceiver != null) return;
        screenReceiver = new BroadcastReceiver() {
            @Override public void onReceive(Context c, Intent i) {
                if (speaker != null) speaker.stop();
                pending = 0; dropped = 0;
                giveFocus();
            }
        };
        app.registerReceiver(screenReceiver, new IntentFilter(Intent.ACTION_SCREEN_ON));
    }

    private static boolean takeFocus() {
        AudioManager am = (AudioManager) app.getSystemService(Context.AUDIO_SERVICE);
        if (am == null) return true;
        if (focus != null) return true;
        if (Build.VERSION.SDK_INT >= 26) {
            focus = new AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
                    .setAudioAttributes(new AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ASSISTANCE_ACCESSIBILITY)
                            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
                    .setOnAudioFocusChangeListener(change -> { }).build();
            boolean ok = am.requestAudioFocus(focus) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED;
            if (!ok) focus = null;
            return ok;
        }
        return true;
    }

    private static void giveFocus() {
        if (focus == null || app == null) return;
        AudioManager am = (AudioManager) app.getSystemService(Context.AUDIO_SERVICE);
        if (am != null && Build.VERSION.SDK_INT >= 26) am.abandonAudioFocusRequest(focus);
        focus = null;
    }

    // ── device state ────────────────────────────────────────────────────────────────────────────

    static boolean screenOn(Context c) {
        PowerManager pm = (PowerManager) c.getSystemService(Context.POWER_SERVICE);
        return pm == null || pm.isInteractive();
    }

    static boolean inCall(Context c) {
        AudioManager am = (AudioManager) c.getSystemService(Context.AUDIO_SERVICE);
        if (am == null) return false;
        int mode = am.getMode();
        return mode == AudioManager.MODE_IN_CALL || mode == AudioManager.MODE_IN_COMMUNICATION || mode == AudioManager.MODE_RINGTONE;
    }

    /** True when sound would go to earbuds or headphones (Bluetooth, wired, USB, hearing aid). */
    static boolean headphones(Context c) {
        AudioManager am = (AudioManager) c.getSystemService(Context.AUDIO_SERVICE);
        if (am == null) return false;
        for (AudioDeviceInfo d : am.getDevices(AudioManager.GET_DEVICES_OUTPUTS)) {
            int t = d.getType();
            if (t == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP || t == AudioDeviceInfo.TYPE_BLUETOOTH_SCO || t == AudioDeviceInfo.TYPE_WIRED_HEADSET
                    || t == AudioDeviceInfo.TYPE_WIRED_HEADPHONES || t == AudioDeviceInfo.TYPE_USB_HEADSET || t == AudioDeviceInfo.TYPE_HEARING_AID) return true;
            if (Build.VERSION.SDK_INT >= 31 && (t == AudioDeviceInfo.TYPE_BLE_HEADSET || t == AudioDeviceInfo.TYPE_BLE_BROADCAST)) return true;
        }
        return false;
    }

    private static String appName(Context c, String pkg) {
        try {
            android.content.pm.PackageManager pm = c.getPackageManager();
            return String.valueOf(pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)));
        } catch (Exception e) { return ""; }
    }

    private static String str(CharSequence c) { return c == null ? null : c.toString(); }

    private static List<ReplyExtract.Msg> messages(Bundle extras) {
        List<ReplyExtract.Msg> out = new ArrayList<>();
        try {
            Parcelable[] raw = extras.getParcelableArray(Notification.EXTRA_MESSAGES);
            if (raw == null) return out;
            for (Parcelable p : raw) {
                if (!(p instanceof Bundle)) continue;
                Bundle b = (Bundle) p;
                String sender = str(b.getCharSequence("sender"));
                if (sender == null && Build.VERSION.SDK_INT >= 28) {
                    Parcelable person = b.getParcelable("sender_person");
                    if (person instanceof android.app.Person) sender = str(((android.app.Person) person).getName());
                }
                out.add(new ReplyExtract.Msg(str(b.getCharSequence("text")), sender));
            }
        } catch (Exception ignored) { }
        return out;
    }
}
