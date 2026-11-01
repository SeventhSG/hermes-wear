package cloud.stormlabs.hermescompanion;

import java.util.Locale;

/** Pure helpers for the video player chrome: seek clamping, scrubber time math, speed cycling and drag-to-level mapping. */
final class PlayerMath {
    private PlayerMath() {}

    static final int SKIP_MS = 10_000;
    static final float[] SPEEDS = {.5f, .75f, 1f, 1.25f, 1.5f, 2f};
    static final float HOLD_SPEED = 2f;
    /** A full-height swipe moves a level this fraction of its range (so the thumb never has to leave the screen). */
    static final float LEVEL_SPAN = .8f;
    static final int LEFT = -1, CENTER = 0, RIGHT = 1;

    /** {@code pos + delta} kept inside the clip. Unknown duration (<= 0) only floors at 0. */
    static int seekTarget(int pos, int delta, int duration) {
        long t = (long) pos + delta;
        if (duration > 0 && t > duration) t = duration;
        return (int) Math.max(0, t);
    }

    /** Which third of the view a touch landed in: LEFT, CENTER or RIGHT. */
    static int zone(float x, float width) {
        if (width <= 0) return CENTER;
        float f = x / width;
        return f < 1f / 3 ? LEFT : f > 2f / 3 ? RIGHT : CENTER;
    }

    /** Position in ms for a 0..1 scrubber fraction. */
    static int fractionToMs(float fraction, int duration) {
        return duration <= 0 ? 0 : Math.round(MediaMath.clamp(fraction, 0, 1) * duration);
    }

    static float msToFraction(int ms, int duration) {
        return duration <= 0 ? 0 : MediaMath.clamp((float) ms / duration, 0, 1);
    }

    /** 0..1 fraction of a touch at {@code x} along a track that starts at {@code left} and is {@code width} long. */
    static float trackFraction(float x, float left, float width) {
        return width <= 0 ? 0 : MediaMath.clamp((x - left) / width, 0, 1);
    }

    /** Left edge for a bubble of {@code bubbleW} centred on {@code center}, kept inside 0..{@code viewW}. */
    static float bubbleLeft(float center, float bubbleW, float viewW) {
        return MediaMath.clamp(center - bubbleW / 2f, 0, Math.max(0, viewW - bubbleW));
    }

    /** "0:02 / 0:03". */
    static String timeLabel(int pos, int duration) { return MediaMath.time(pos) + " / " + MediaMath.time(duration); }

    /** The next speed in SPEEDS after {@code current}, wrapping from the fastest to the slowest. */
    static float nextSpeed(float current) {
        for (int i = 0; i < SPEEDS.length; i++) if (SPEEDS[i] > current + .001f) return SPEEDS[i];
        return SPEEDS[0];
    }

    /** "1×", "0.5×", "1.25×". */
    static String speedLabel(float s) {
        String n = Math.abs(s - Math.round(s)) < .001f ? String.valueOf(Math.round(s)) : String.format(Locale.ROOT, "%.2f", s).replaceAll("0+$", "");
        return n + "×";
    }

    /** New 0..1 level after a vertical drag of {@code dy} px (up is positive) on a view {@code height} px tall. */
    static float levelAfterDrag(float level, float dy, float height) {
        return height <= 0 ? level : MediaMath.clamp(level + dy / (height * LEVEL_SPAN), 0, 1);
    }

    /** Stream volume step for a 0..1 level. */
    static int levelToStep(float level, int max) { return Math.round(MediaMath.clamp(level, 0, 1) * Math.max(0, max)); }

    static float stepToLevel(int step, int max) { return max <= 0 ? 0 : MediaMath.clamp((float) step / max, 0, 1); }

    /** Label for a skip bubble: "-10s" (true minus sign), "+20s". */
    static String skipLabel(int totalMs) { return (totalMs < 0 ? "−" : "+") + Math.abs(totalMs) / 1000 + "s"; }

    /** Scale of a page that is {@code offset} px from centre in a pager {@code width} px wide (parallax between pages). */
    static float pageScale(float offset, float width) {
        if (width <= 0) return 1;
        return 1 - .1f * MediaMath.clamp(Math.abs(offset) / width, 0, 1);
    }
}
