package cloud.stormlabs.hermescompanion;

import java.util.Locale;

/** Pure helpers for the media viewer: paging index math, zoom clamping and time formatting. */
final class MediaMath {
    private MediaMath() {}

    static final float MAX_ZOOM = 5f, DOUBLE_TAP_ZOOM = 2.5f;
    static final float FLING_SNAP_VELOCITY = 800f;   // px/s: a flick this fast pages even from a short drag
    static final float DISMISS_FRACTION = .18f;      // of the screen height, or a fast downward flick

    static float clamp(float v, float lo, float hi) { return v < lo ? lo : v > hi ? hi : v; }

    static int clampIndex(int i, int n) { return n <= 0 ? 0 : Math.max(0, Math.min(n - 1, i)); }

    /** "3 of 8" for the top bar. */
    static String counter(int index, int count) { return (clampIndex(index, count) + 1) + " of " + count; }

    /** The page to settle on after a drag: {@code offset} is how far the pages moved right (positive reveals the previous
     *  one), {@code velocity} the finger velocity in px/s (negative flicks towards the next). */
    static int snapTarget(int current, float offset, float velocity, int width, int count) {
        int t = current;
        if (Math.abs(velocity) > FLING_SNAP_VELOCITY && velocity * offset >= 0) t = velocity < 0 ? current + 1 : current - 1;
        else if (Math.abs(offset) > width * .35f) t = offset < 0 ? current + 1 : current - 1;
        return clampIndex(t, count);
    }

    /** Drag past the first/last item feels rubbery: 35% of the finger travel. */
    static float edgeResistance(float drag, boolean hasPrev, boolean hasNext) {
        return (drag > 0 && !hasPrev) || (drag < 0 && !hasNext) ? drag * .35f : drag;
    }

    /** 0..1 progress of a swipe-to-dismiss drag. */
    static float dismissProgress(float dy, int height) { return height <= 0 ? 0 : clamp(Math.abs(dy) / (height * .5f), 0, 1); }

    static boolean shouldDismiss(float dy, float velocityY, int height) {
        return Math.abs(dy) > height * DISMISS_FRACTION || (Math.abs(velocityY) > 1500 && velocityY * dy > 0);
    }

    /** Clamps a content offset so the content never leaves a gap at an edge; content smaller than the view is centred. */
    static float clampOffset(float offset, float content, float view) {
        return content <= view ? (view - content) / 2f : clamp(offset, view - content, 0);
    }

    /** Size of a w x h source fitted inside a bw x bh box, aspect kept. */
    static float[] fit(float w, float h, float bw, float bh) {
        if (w <= 0 || h <= 0 || bw <= 0 || bh <= 0) return new float[]{Math.max(bw, 0), Math.max(bh, 0)};
        float s = Math.min(bw / w, bh / h);
        return new float[]{w * s, h * s};
    }

    /** "1:05", "12:09", "1:02:03". */
    static String time(long ms) {
        long s = Math.max(0, ms) / 1000;
        return s >= 3600 ? String.format(Locale.ROOT, "%d:%02d:%02d", s / 3600, s / 60 % 60, s % 60)
                : String.format(Locale.ROOT, "%d:%02d", s / 60, s % 60);
    }

    /** Volume as a whole percent of the stream maximum. */
    static int percent(int level, int max) { return max <= 0 ? 0 : Math.round(100f * clamp(level, 0, max) / max); }

    /** Pan/zoom state of one piece of content that fits the view at zoom 1 (size cw x ch, centred). */
    static final class Zoom {
        float vw, vh, cw, ch, zoom = 1, tx, ty;

        void setSizes(float vw, float vh, float cw, float ch) {
            this.vw = vw; this.vh = vh; this.cw = cw; this.ch = ch;
            zoom = 1; tx = (vw - cw) / 2f; ty = (vh - ch) / 2f;
        }

        boolean zoomed() { return zoom > 1.02f; }

        /** Zoom that makes the content cover the whole view (video "fill"), or the double-tap zoom when it's already about that size. */
        float fillZoom() {
            float z = cw <= 0 || ch <= 0 ? 1 : Math.max(vw / cw, vh / ch);
            return z < 1.1f ? DOUBLE_TAP_ZOOM : Math.min(z, MAX_ZOOM);
        }

        /** Where a double tap should go: back to fit when zoomed, otherwise {@code in}. */
        float toggleTarget(float in) { return zoomed() ? 1f : in; }

        void zoomAt(float newZoom, float fx, float fy) {
            float nz = clamp(newZoom, 1f, MAX_ZOOM), f = nz / zoom;
            tx = fx - (fx - tx) * f; ty = fy - (fy - ty) * f; zoom = nz;
            clampOffsets();
        }

        /** Moves the content by (dx, dy); returns the horizontal part that didn't fit (for the pager to take). */
        float pan(float dx, float dy) {
            float wantX = tx + dx;
            tx = wantX; ty += dy; clampOffsets();
            return wantX - tx;
        }

        void clampOffsets() { tx = clampOffset(tx, cw * zoom, vw); ty = clampOffset(ty, ch * zoom, vh); }

        float minTx() { return Math.min(0, vw - cw * zoom); }
        float minTy() { return Math.min(0, vh - ch * zoom); }
        boolean canPanX() { return cw * zoom > vw + .5f; }
        boolean canPanY() { return ch * zoom > vh + .5f; }
    }
}
