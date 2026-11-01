package cloud.stormlabs.hermescompanion;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.os.Bundle;
import android.view.MotionEvent;
import android.view.View;
import android.view.accessibility.AccessibilityNodeInfo;

/** Thin video scrubber: 3dp track that grows to 6dp with a thumb while dragged, plus a buffered segment. Draws nothing per frame but rects. */
final class ScrubberView extends View {
    interface Listener {
        void onScrubStart();
        /** {@code thumbX} is in this view's coordinates, for placing the time bubble. */
        void onScrub(int ms, float thumbX);
        void onScrubEnd(int ms);
        /** Accessibility nudge: -1 / +1 for a 10 s step. */
        void onNudge(int direction);
    }

    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF r = new RectF();
    private final float pad, thin, thick, thumbR;
    private Listener listener;
    private ValueAnimator growAnim;
    private float grow, dragFraction;
    private int duration, position;
    private float buffered;
    private boolean dragging;

    ScrubberView(Context c) {
        super(c);
        float d = c.getResources().getDisplayMetrics().density;
        pad = 8 * d; thin = 3 * d; thick = 6 * d; thumbR = 7 * d;
        setContentDescription("Seek");
    }

    void setListener(Listener l) { listener = l; }

    boolean dragging() { return dragging; }

    /** Current state from the player; ignored for the position while the finger is down. */
    void set(int position, int duration, int bufferedMs) {
        this.duration = duration;
        if (!dragging) this.position = position;
        float b = PlayerMath.msToFraction(bufferedMs, duration);
        if (b != buffered || !dragging) { buffered = b; invalidate(); }
    }

    private float trackWidth() { return Math.max(1, getWidth() - 2 * pad); }

    private float fraction() { return dragging ? dragFraction : PlayerMath.msToFraction(position, duration); }

    float thumbX() { return pad + fraction() * trackWidth(); }

    @Override protected void onDraw(Canvas c) {
        float cy = getHeight() / 2f, h = thin + (thick - thin) * grow, left = pad, right = getWidth() - pad;
        float x = pad + fraction() * trackWidth(), rad = h / 2f;
        r.set(left, cy - rad, right, cy + rad);
        paint.setColor(0x4DFFFFFF); c.drawRoundRect(r, rad, rad, paint);
        r.right = pad + buffered * trackWidth();
        if (r.right > left) { paint.setColor(0x80FFFFFF); c.drawRoundRect(r, rad, rad, paint); }
        r.right = x;
        if (r.right > left) { paint.setColor(Ui.BLUE); c.drawRoundRect(r, rad, rad, paint); }
        if (grow > 0) { paint.setColor(Color.WHITE); c.drawCircle(x, cy, thumbR * grow, paint); }
    }

    private void animateGrow(boolean on) {
        if (growAnim != null) growAnim.cancel();
        if (!Ui.motionEnabled()) { grow = on ? 1 : 0; invalidate(); return; }
        growAnim = ValueAnimator.ofFloat(grow, on ? 1 : 0);
        growAnim.setDuration(160); growAnim.setInterpolator(Ui.EASE_OUT);
        growAnim.addUpdateListener(a -> { grow = (float) a.getAnimatedValue(); invalidate(); });
        growAnim.start();
    }

    @Override public boolean onTouchEvent(MotionEvent e) {
        if (!isEnabled() || duration <= 0) return false;
        int a = e.getActionMasked();
        if (a == MotionEvent.ACTION_DOWN) {
            dragging = true;
            if (getParent() != null) getParent().requestDisallowInterceptTouchEvent(true);
            animateGrow(true);
            if (listener != null) listener.onScrubStart();
        }
        if (!dragging) return false;
        if (a == MotionEvent.ACTION_DOWN || a == MotionEvent.ACTION_MOVE) {
            dragFraction = PlayerMath.trackFraction(e.getX(), pad, trackWidth());
            invalidate();
            if (listener != null) listener.onScrub(PlayerMath.fractionToMs(dragFraction, duration), thumbX());
        } else if (a == MotionEvent.ACTION_UP || a == MotionEvent.ACTION_CANCEL) {
            dragFraction = PlayerMath.trackFraction(e.getX(), pad, trackWidth());
            int ms = PlayerMath.fractionToMs(dragFraction, duration);
            dragging = false; position = ms;
            animateGrow(false);
            if (a == MotionEvent.ACTION_UP) performClick();
            if (listener != null) listener.onScrubEnd(ms);
        }
        return true;
    }

    @Override public boolean performClick() { return super.performClick(); }

    @Override public void onInitializeAccessibilityNodeInfo(AccessibilityNodeInfo info) {
        super.onInitializeAccessibilityNodeInfo(info);
        info.addAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_FORWARD);
        info.addAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_BACKWARD);
    }

    @Override public boolean performAccessibilityAction(int action, Bundle args) {
        if (listener != null && (action == AccessibilityNodeInfo.ACTION_SCROLL_FORWARD || action == AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD)) {
            listener.onNudge(action == AccessibilityNodeInfo.ACTION_SCROLL_FORWARD ? 1 : -1);
            return true;
        }
        return super.performAccessibilityAction(action, args);
    }
}
