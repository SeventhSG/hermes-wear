package cloud.stormlabs.hermescompanion;

import android.app.PendingIntent;
import android.content.Intent;
import android.os.Build;
import android.service.quicksettings.Tile;
import android.service.quicksettings.TileService;

/**
 * Quick-settings tile: a capture two gestures from anywhere (swipe down, tap).
 *
 * Separate from AssistantTileService on purpose — that tile is for asking Hermes something, this one
 * is for filing something. Same gesture, different intent, and mixing them means losing whichever
 * you did not mean.
 */
public class CaptureTileService extends TileService {
    @Override public void onStartListening() {
        super.onStartListening();
        if (getQsTile() != null) {
            getQsTile().setState(Tile.STATE_ACTIVE);
            getQsTile().setLabel("Capture");
            getQsTile().updateTile();
        }
    }

    @android.annotation.SuppressLint("StartActivityAndCollapseDeprecated") // pre-34 path only
    @Override public void onClick() {
        super.onClick();
        try {
            Intent i = new Intent(this, EssentialsActivity.class)
                    .putExtra(EssentialsActivity.EXTRA_RECORD, true)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP
                            | Intent.FLAG_ACTIVITY_SINGLE_TOP);
            if (Build.VERSION.SDK_INT >= 34) {
                startActivityAndCollapse(PendingIntent.getActivity(this, 21, i,
                        PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE));
            } else {
                startActivityAndCollapse(i);
            }
        } catch (Throwable t) {
            CrashReporter.show(this, t);
        }
    }
}
