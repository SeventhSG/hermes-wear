package cloud.stormlabs.hermescompanion;

import android.animation.ObjectAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.animation.ValueAnimator;
import android.view.View;
import android.view.animation.LinearInterpolator;

/** Thin loading ring; spins while visible and animations are on, a static arc otherwise. */
final class SpinnerView extends View {
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF oval = new RectF();
    private ObjectAnimator spin;

    SpinnerView(Context c) {
        super(c);
        float d = c.getResources().getDisplayMetrics().density;
        paint.setStyle(Paint.Style.STROKE); paint.setStrokeCap(Paint.Cap.ROUND); paint.setStrokeWidth(3 * d); paint.setColor(0xFFFFFFFF);
        setContentDescription("Loading");
        setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
    }

    @Override protected void onDraw(Canvas c) {
        float inset = paint.getStrokeWidth();
        oval.set(inset, inset, getWidth() - inset, getHeight() - inset);
        c.drawArc(oval, 0, 270, false, paint);
    }

    private void sync() {
        boolean want = isShown() && Ui.motionEnabled();
        if (want && spin == null) {
            spin = ObjectAnimator.ofFloat(this, ROTATION, 0, 360);
            spin.setDuration(900); spin.setInterpolator(new LinearInterpolator()); spin.setRepeatCount(ValueAnimator.INFINITE);
            spin.start();
        } else if (!want && spin != null) { spin.cancel(); spin = null; setRotation(0); }
    }

    @Override protected void onVisibilityChanged(View changed, int v) { super.onVisibilityChanged(changed, v); sync(); }
    @Override protected void onAttachedToWindow() { super.onAttachedToWindow(); sync(); }
    @Override protected void onDetachedFromWindow() { super.onDetachedFromWindow(); if (spin != null) { spin.cancel(); spin = null; } }
}
