package cloud.stormlabs.hermescompanion;

import android.os.SystemClock;
import android.text.TextPaint;
import android.text.style.CharacterStyle;

/** Small helpers for chat motion. */
final class ChatFeel {
    private ChatFeel() {}

    /** Unread badge text: "" for none, "9+" past nine. */
    static String badge(int n) { return n <= 0 ? "" : n > 9 ? "9+" : String.valueOf(n); }

    /** Blink phase: visible for the first half of each period. */
    static boolean blinkOn(long now, long period) { return now % period < period / 2; }

    /** Streaming cursor: drawn only in the "on" phase; the owner just invalidates the view. */
    static final class Blink extends CharacterStyle {
        @Override public void updateDrawState(TextPaint p) { if (!blinkOn(SystemClock.uptimeMillis(), 1060)) p.setColor(0); }
    }
}
