package cloud.stormlabs.hermescompanion;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

import java.io.File;
import java.io.FileOutputStream;

/**
 * QA hook: saves a frame of the hidden agent display to the app's external files dir
 * (Android/data/&lt;pkg&gt;/files/agent-shot.jpg). Guarded by DUMP, so only adb shell can send it:
 * adb shell am broadcast -n com.stormlabs.hermescompanion/cloud.stormlabs.hermescompanion.ShotReceiver
 */
public final class ShotReceiver extends BroadcastReceiver {
    @Override public void onReceive(Context c, Intent intent) {
        PendingResult result = goAsync();
        new Thread(() -> {
            try {
                byte[] jpeg = AgentDisplay.screenshot();
                File f = new File(c.getExternalFilesDir(null), "agent-shot.jpg");
                try (FileOutputStream out = new FileOutputStream(f)) { out.write(jpeg); }
                result.setResultData(jpeg.length + " bytes");
            } catch (Exception e) {
                result.setResultData("failed: " + e);
            } finally { result.finish(); }
        }, "shot").start();
    }
}
