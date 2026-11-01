package cloud.stormlabs.hermescompanion;

import android.app.Notification;
import android.app.RemoteInput;
import android.app.Person;
import android.os.Build;
import android.os.Bundle;
import android.os.Parcelable;
import android.service.notification.NotificationListenerService;
import android.service.notification.StatusBarNotification;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** Opt-in notification bridge. The source app's reply action never fires until Send is tapped. */
public final class ReplyListenerService extends NotificationListenerService {
    static final String[][] SUGGESTED_APPS = {
            {"com.instagram.android", "Instagram"}, {"com.whatsapp", "WhatsApp"}, {"org.telegram.messenger", "Telegram"},
            {"com.facebook.orca", "Messenger"}, {"com.viber.voip", "Viber"},
            {"org.thoughtcrime.securesms", "Signal"}, {"com.google.android.apps.messaging", "Google Messages"},
            {"com.samsung.android.messaging", "Samsung Messages"}, {"com.google.android.gm", "Gmail"}};
    static final long LIFETIME_MS = 10 * 60 * 1000L;
    static final Map<String, Source> SOURCES = new ConcurrentHashMap<>();

    static final class Source {
        final Notification.Action action;
        final RemoteInput input;
        final String app, sender, message;
        final long seen = System.currentTimeMillis();
        String draft;
        boolean canSend() { return action != null && input != null; }
        Source(Notification.Action action, RemoteInput input, String app, String sender, String message) {
            this.action = action; this.input = input; this.app = app; this.sender = sender; this.message = message;
        }
        boolean fresh() { return System.currentTimeMillis() - seen < LIFETIME_MS; }
    }

    @Override public void onNotificationPosted(StatusBarNotification sbn) {
        try { NotificationReader.consider(this, sbn, importanceOf(sbn)); } catch (Throwable ignored) { /* reading aloud must never break reply drafting */ }
        SettingsStore store = new SettingsStore(this);
        if (!store.replyDrafting() || store.agentPaused() || !store.replyApps().contains(sbn.getPackageName())) return;
        SOURCES.entrySet().removeIf(entry -> !entry.getValue().fresh());
        Notification n = sbn.getNotification();
        if (n == null || (n.flags & Notification.FLAG_GROUP_SUMMARY) != 0) return;
        Bundle extras = n.extras;
        if (extras == null) return;
        ReplyExtract.Result r = ReplyExtract.extract(false, str(extras.getCharSequence(Notification.EXTRA_TITLE)),
                str(extras.getCharSequence(Notification.EXTRA_TEXT)), str(extras.getCharSequence("android.conversationTitle")),
                extras.getBoolean("android.isGroupConversation"), messages(extras));
        if (r == null) return;
        Notification.Action replyAction = null;
        RemoteInput replyInput = null;
        if (n.actions != null) {
            for (Notification.Action action : n.actions) {
                if (action.actionIntent == null || action.getRemoteInputs() == null) continue;
                for (RemoteInput input : action.getRemoteInputs()) {
                    if (!input.getAllowFreeFormInput()) continue;
                    replyAction = action; replyInput = input; break;
                }
                if (replyInput != null) break;
            }
        }
        String key = sbn.getKey();
        Source source = new Source(replyAction, replyInput, sbn.getPackageName(), r.sender, r.message);
        Source old = SOURCES.putIfAbsent(key, source);
        if (old != null) {
            if (old.message.equals(source.message)) return;
            SOURCES.put(key, source);
        }
        ActivityLog.log(this, "listener", "Read message · " + source.app, source.sender + ": " + source.message, true, null);
        Notifier.replyOffer(this, key, source.sender);
    }

    /** The channel importance the user gave this notification (-1 when unknown). */
    private int importanceOf(StatusBarNotification sbn) {
        try {
            RankingMap map = getCurrentRanking();
            Ranking r = new Ranking();
            return map != null && map.getRanking(sbn.getKey(), r) ? r.getImportance() : -1;
        } catch (Throwable t) { return -1; }
    }

    private static String str(CharSequence c) { return c == null ? null : c.toString(); }
    private static String strOr(CharSequence c) { return c == null ? "" : c.toString(); }

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
                    if (person instanceof Person) sender = str(((Person) person).getName());
                }
                out.add(new ReplyExtract.Msg(str(b.getCharSequence("text")), sender));
            }
        } catch (Exception ignored) { }
        return out;
    }

    private static volatile ReplyListenerService connected;
    @Override public void onListenerConnected() { connected = this; }
    @Override public void onListenerDisconnected() { if (connected == this) connected = null; }
    static boolean connected() { return connected != null; }

    /**
     * "Stop the timer": press Dismiss/Stop on the clock app's own notification, which works on every clock app
     * (Samsung ignores ACTION_DISMISS_TIMER). Returns how many were stopped, or -1 without notification access.
     */
    static int dismissClock(String kind) {
        ReplyListenerService s = connected;
        if (s == null) return -1;
        int stopped = 0;
        StatusBarNotification[] all;
        try { all = s.getActiveNotifications(); } catch (Exception e) { return -1; }
        if (all == null) return 0;
        for (StatusBarNotification sbn : all) {
            String pkg = sbn.getPackageName();
            if (!(pkg.contains("clock") || pkg.contains("alarm"))) continue;
            Notification n = sbn.getNotification();
            String channel = Build.VERSION.SDK_INT >= 26 && n.getChannelId() != null ? n.getChannelId().toLowerCase(java.util.Locale.ROOT) : "";
            String text = (strOr(n.extras.getCharSequence(Notification.EXTRA_TITLE)) + " " + channel).toLowerCase(java.util.Locale.ROOT);
            if (!kind.isEmpty() && !text.contains(kind) && !channel.contains(kind)) continue;
            if (n.actions == null) continue;
            for (Notification.Action a : n.actions) {
                String t = a.title == null ? "" : a.title.toString().toLowerCase(java.util.Locale.ROOT);
                if (t.equals("dismiss") || t.equals("stop") || t.equals("cancel") || t.equals("reset") || t.equals("delete")) {
                    try { a.actionIntent.send(); stopped++; } catch (Exception ignored) {}
                    break;
                }
            }
        }
        return stopped;
    }

    @Override public void onNotificationRemoved(StatusBarNotification sbn) {
        SOURCES.remove(sbn.getKey());
        Notifier.cancelReplyDraft(this, sbn.getKey());
    }
}
