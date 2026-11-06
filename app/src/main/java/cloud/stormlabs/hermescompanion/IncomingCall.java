package cloud.stormlabs.hermescompanion;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.media.AudioAttributes;
import android.media.RingtoneManager;
import android.os.Build;
import android.os.Bundle;

/**
 * Hermes calling you: a ringing, full-screen "incoming call" notification (hub → FCM {@code call_me}).
 * Answer opens the assistant overlay as a call that starts with Hermes saying why it called and then
 * keeps listening; Decline just dismisses it. If Hermes isn't the phone's assistant, Answer opens the chat.
 */
final class IncomingCall {
    private IncomingCall() {}

    static final String CH_CALL = "hermes-call";
    static final int NID = 4901;
    static final String EXTRA_TEXT = "text";

    static void ring(Context c, String text) {
        NotificationManager nm = c.getSystemService(NotificationManager.class);
        if (nm == null) return;
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationChannel ch = new NotificationChannel(CH_CALL, "Hermes calls", NotificationManager.IMPORTANCE_HIGH);
            ch.setDescription("Hermes rings you when it needs to talk");
            ch.setSound(RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE),
                    new AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_NOTIFICATION_RINGTONE).setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build());
            ch.enableVibration(true);
            ch.setVibrationPattern(new long[]{0, 700, 500, 700, 500, 700});
            nm.createNotificationChannel(ch);
        }
        int flags = PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE;
        Intent answer = new Intent(c, IncomingCallActivity.class).putExtra(EXTRA_TEXT, text).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        PendingIntent answerPi = PendingIntent.getActivity(c, 1, answer, flags);
        Intent decline = new Intent(c, Decline.class);
        PendingIntent declinePi = PendingIntent.getBroadcast(c, 2, decline, flags);
        Notification.Builder b = Build.VERSION.SDK_INT >= 26 ? new Notification.Builder(c, CH_CALL) : new Notification.Builder(c);
        b.setSmallIcon(R.drawable.ic_notification).setContentTitle("Hermes is calling")
                .setContentText(text == null || text.isEmpty() ? "Tap to answer" : text)
                .setCategory(Notification.CATEGORY_CALL).setOngoing(true).setAutoCancel(true).setTimeoutAfter(60_000L)
                .setContentIntent(answerPi).setFullScreenIntent(answerPi, true)
                .addAction(new Notification.Action.Builder(null, "Answer", answerPi).build())
                .addAction(new Notification.Action.Builder(null, "Decline", declinePi).build());
        nm.notify(NID, b.build());
    }

    public static final class Decline extends android.content.BroadcastReceiver {
        @Override public void onReceive(Context c, Intent i) {
            NotificationManager nm = c.getSystemService(NotificationManager.class);
            if (nm != null) nm.cancel(NID);
        }
    }
}
