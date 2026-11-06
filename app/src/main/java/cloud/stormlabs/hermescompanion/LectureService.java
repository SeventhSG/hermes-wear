package cloud.stormlabs.hermescompanion;

import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.os.Build;
import android.os.IBinder;

/** Keeps the process (and the microphone) alive while Live notes listens with the screen off. It only holds the notification. */
public final class LectureService extends Service {
    static void start(Context c) {
        Intent i = new Intent(c, LectureService.class);
        if (Build.VERSION.SDK_INT >= 26) c.startForegroundService(i); else c.startService(i);
    }
    static void stop(Context c) { c.stopService(new Intent(c, LectureService.class)); }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        Notifier.channels(this);
        try {
            if (Build.VERSION.SDK_INT >= 30) startForeground(Notifier.ID_LECTURE, Notifier.lecture(this), ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE);
            else startForeground(Notifier.ID_LECTURE, Notifier.lecture(this));
        } catch (Throwable t) { stopSelf(); return START_NOT_STICKY; }
        return START_NOT_STICKY;
    }

    @Override public IBinder onBind(Intent intent) { return null; }
}
