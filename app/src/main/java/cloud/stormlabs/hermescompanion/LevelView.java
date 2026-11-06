package cloud.stormlabs.hermescompanion;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.view.MotionEvent;
import android.view.View;

/** Vertical pill showing a 0..1 level (volume / brightness). Interactive for the pop-out volume slider, passive for the gesture HUD. */
final class LevelView extends View {
    interface Listener { void onLevel(float level); void onTouch(boolean down); }

    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path clip = new Path();
    private final RectF pill = new RectF(), fillRect = new RectF();
    private final float pillW;
    private float level;
    private Listener listener;

    LevelView(Context c, boolean interactive) {
        super(c);
        pillW = 36 * c.getResources().getDisplayMetrics().density;
        if (interactive) { setClickable(true); setFocusable(true); }
    }

    void setListener(Listener l) { listener = l; }

    float level() { return level; }

    void setLevel(float v) {
        v = MediaMath.clamp(v, 0, 1);
        if (v != level) { level = v; invalidate(); }
    }

    @Override protected void onSizeChanged(int w, int h, int ow, int oh) {
        super.onSizeChanged(w, h, ow, oh);
        float left = (w - pillW) / 2f;
        pill.set(left, 0, left + pillW, h);
        clip.reset(); clip.addRoundRect(pill, pillW / 2f, pillW / 2f, Path.Direction.CW);
    }

    @Override protected void onDraw(Canvas c) {
        paint.setColor(0x8C000000); c.drawPath(clip, paint);
        c.save(); c.clipPath(clip);
        fillRect.set(pill.left, pill.bottom - pill.height() * level, pill.right, pill.bottom);
        paint.setColor(0xF2FFFFFF); c.drawRect(fillRect, paint);
        c.restore();
    }

    @Override public boolean onTouchEvent(MotionEvent e) {
        if (!isClickable()) return false;
        int a = e.getActionMasked();
        if (a == MotionEvent.ACTION_DOWN) { if (getParent() != null) getParent().requestDisallowInterceptTouchEvent(true); if (listener != null) listener.onTouch(true); }
        if (a == MotionEvent.ACTION_DOWN || a == MotionEvent.ACTION_MOVE) {
            setLevel(1f - (e.getY() - pill.top) / Math.max(1, pill.height()));
            if (listener != null) listener.onLevel(level);
        } else if (a == MotionEvent.ACTION_UP || a == MotionEvent.ACTION_CANCEL) {
            if (a == MotionEvent.ACTION_UP) performClick();
            if (listener != null) listener.onTouch(false);
        }
        return true;
    }

    @Override public boolean performClick() { return super.performClick(); }
}
