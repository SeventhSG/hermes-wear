package cloud.stormlabs.hermescompanion;

import android.os.Bundle;
import android.service.voice.VoiceInteractionService;

public class HermesVoiceInteractionService extends VoiceInteractionService {
    /** Bound by the system only while Hermes is the phone's digital assistant. */
    private static volatile HermesVoiceInteractionService ready;

    @Override public void onReady() { super.onReady(); ready = this; }
    @Override public void onShutdown() { if (ready == this) ready = null; super.onShutdown(); }

    /** Opens the assistant overlay; false when Hermes isn't the active assistant. */
    static boolean show(Bundle args, int flags) {
        HermesVoiceInteractionService s = ready;
        if (s == null) return false;
        try { s.showSession(args, flags); return true; } catch (Throwable t) { return false; }
    }

    static boolean active() { return ready != null; }
}
