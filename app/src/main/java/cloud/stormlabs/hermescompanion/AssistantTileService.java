package cloud.stormlabs.hermescompanion;

import android.app.PendingIntent;
import android.content.Intent;
import android.os.Build;
import android.service.quicksettings.Tile;
import android.service.quicksettings.TileService;

public class AssistantTileService extends TileService {
    @Override public void onStartListening() { super.onStartListening(); if (getQsTile() != null) { getQsTile().setState(Tile.STATE_ACTIVE); getQsTile().setLabel("Ask Hermes"); getQsTile().updateTile(); } }
    @android.annotation.SuppressLint("StartActivityAndCollapseDeprecated") // pre-34 path only
    @Override public void onClick() {
        super.onClick();
        Intent i = new Intent(this, MainActivity.class).putExtra(MainActivity.EXTRA_START_VOICE, true)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        if (Build.VERSION.SDK_INT >= 34) startActivityAndCollapse(PendingIntent.getActivity(this, 9, i, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE));
        else startActivityAndCollapse(i);
    }
}
