package cloud.stormlabs.hermescompanion;

import android.app.PendingIntent;
import android.content.Intent;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.service.quicksettings.Tile;
import android.service.quicksettings.TileService;

/** Quick-settings "Homelab" tile: OK or the worst problem from the cached summary; tap opens the System screen. */
public class LabTileService extends TileService {
    @Override public void onStartListening() {
        super.onStartListening();
        render();
        new Thread(() -> { if (LabSummary.refresh(this)) new Handler(Looper.getMainLooper()).post(this::render); }).start();
    }

    private void render() {
        Tile t = getQsTile(); // null once we stopped listening
        if (t == null) return;
        LabSummary s = LabSummary.cached(this);
        t.setLabel("Homelab");
        t.setState(s != null && s.level() == LabSummary.OK ? Tile.STATE_ACTIVE : Tile.STATE_INACTIVE);
        if (Build.VERSION.SDK_INT >= 29) t.setSubtitle(s == null ? "No data" : s.problem.isEmpty() ? "OK" : s.problem);
        t.updateTile();
    }

    @android.annotation.SuppressLint("StartActivityAndCollapseDeprecated") // pre-34 path only
    @Override public void onClick() {
        super.onClick();
        try {
            Intent i = new Intent(this, StatusActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
            if (Build.VERSION.SDK_INT >= 34) {
                startActivityAndCollapse(PendingIntent.getActivity(this, 22, i,
                        PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE));
            } else {
                startActivityAndCollapse(i);
            }
        } catch (Throwable t) {
            CrashReporter.show(this, t);
        }
    }
}
