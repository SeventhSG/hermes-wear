package cloud.stormlabs.hermescompanion;

import android.os.Build;
import android.service.quicksettings.Tile;
import android.service.quicksettings.TileService;

/** Quick-settings kill switch: pauses Hermes' phone actions at once; resuming needs the phone unlocked. */
public class PauseTileService extends TileService {
    @Override public void onStartListening() { super.onStartListening(); show(); }

    @Override public void onClick() {
        super.onClick();
        SettingsStore store = new SettingsStore(this);
        if (!store.agentPaused()) { store.agentPaused(true); show(); return; }
        Runnable resume = () -> { store.agentPaused(false); show(); };
        if (isLocked()) unlockAndRun(resume); else resume.run();
    }

    private void show() {
        Tile t = getQsTile();
        if (t == null) return;
        boolean paused = new SettingsStore(this).agentPaused();
        t.setState(paused ? Tile.STATE_ACTIVE : Tile.STATE_INACTIVE);
        t.setLabel(paused ? "Hermes paused" : "Pause Hermes");
        if (Build.VERSION.SDK_INT >= 29) t.setSubtitle(paused ? "Tap to resume" : "Phone actions on");
        t.updateTile();
    }
}
