package cloud.stormlabs.hermescompanion;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.view.View;
import android.widget.OverScroller;

/** A bitmap that pans, flings and zooms (1x fit to 5x) with a Matrix; gestures arrive from {@link MediaPager}. */
final class ZoomImageView extends View implements MediaPager.Page {
    private final MediaMath.Zoom z = new MediaMath.Zoom();
    private final Matrix matrix = new Matrix();
    private final Paint paint = new Paint(Paint.FILTER_BITMAP_FLAG | Paint.ANTI_ALIAS_FLAG);
    private final OverScroller scroller;
    private Bitmap bmp;
    private ValueAnimator anim;
    private final Runnable step = new Runnable() {
        @Override public void run() {
            if (!scroller.computeScrollOffset()) return;
            z.tx = scroller.getCurrX(); z.ty = scroller.getCurrY();
            z.clampOffsets(); invalidate(); postOnAnimation(this);
        }
    };

    ZoomImageView(Context c) { super(c); scroller = new OverScroller(c); setWillNotDraw(false); }

    Bitmap bitmap() { return bmp; }

    /** Shows (or swaps in a sharper version of) the image; an existing zoom and position are kept across a swap. */
    void setBitmap(Bitmap b) {
        Bitmap old = bmp;
        bmp = b;
        if (b == null) { invalidate(); return; }
        if (old != null && z.cw > 0) {
            float zoom = z.zoom, ftx = z.tx, fty = z.ty;
            fit(); z.zoom = zoom; z.tx = ftx; z.ty = fty; z.clampOffsets();
        } else fit();
        invalidate();
    }

    private void fit() {
        if (bmp == null || getWidth() == 0) return;
        float[] s = MediaMath.fit(bmp.getWidth(), bmp.getHeight(), getWidth(), getHeight());
        z.setSizes(getWidth(), getHeight(), s[0], s[1]);
    }

    @Override protected void onSizeChanged(int w, int h, int ow, int oh) { super.onSizeChanged(w, h, ow, oh); fit(); }

    @Override protected void onDraw(Canvas c) {
        if (bmp == null || z.cw <= 0) return;
        float s = z.cw * z.zoom / bmp.getWidth();
        matrix.setScale(s, s);
        matrix.postTranslate(z.tx, z.ty);
        c.drawBitmap(bmp, matrix, paint);
    }

    @Override public boolean zoomed() { return z.zoomed(); }

    @Override public void zoomBy(float f, float fx, float fy) { stop(); z.zoomAt(z.zoom * f, fx, fy); invalidate(); }

    @Override public void toggleZoom(float x, float y) {
        stop();
        final float to = z.toggleTarget(MediaMath.DOUBLE_TAP_ZOOM), from = z.zoom;
        if (!Ui.motionEnabled()) { z.zoomAt(to, x, y); invalidate(); return; }
        anim = ValueAnimator.ofFloat(from, to);
        anim.setDuration(260); anim.setInterpolator(Ui.EASE_OUT);
        anim.addUpdateListener(a -> { z.zoomAt((float) a.getAnimatedValue(), x, y); invalidate(); });
        anim.start();
    }

    @Override public float pan(float dx, float dy) { float left = z.pan(dx, dy); invalidate(); return left; }

    @Override public void fling(float vx, float vy) {
        stop();
        if (vx == 0 && vy == 0 || !z.zoomed()) return;
        int x = Math.round(z.tx), y = Math.round(z.ty);
        scroller.fling(x, y, Math.round(z.canPanX() ? vx : 0), Math.round(z.canPanY() ? vy : 0),
                z.canPanX() ? Math.round(z.minTx()) : x, z.canPanX() ? 0 : x, z.canPanY() ? Math.round(z.minTy()) : y, z.canPanY() ? 0 : y);
        postOnAnimation(step);
    }

    private void stop() {
        scroller.forceFinished(true); removeCallbacks(step);
        if (anim != null) { anim.cancel(); anim = null; }
    }

    @Override public void setActive(boolean active) { if (!active) { stop(); if (z.zoomed()) { fit(); invalidate(); } } }

    @Override public void recycle() { stop(); bmp = null; }
}
