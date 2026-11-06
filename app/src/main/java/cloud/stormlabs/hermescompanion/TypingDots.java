package cloud.stormlabs.hermescompanion;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.view.View;
import android.view.animation.LinearInterpolator;

/** Three bouncing dots: the "Hermes is typing" signal while nothing has streamed yet. */
final class TypingDots extends View {
    private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final ValueAnimator anim = ValueAnimator.ofFloat(0, 1);
    private float t;

    TypingDots(Context c) {
        super(c);
        anim.setDuration(1100); anim.setRepeatCount(ValueAnimator.INFINITE); anim.setInterpolator(new LinearInterpolator());
        anim.addUpdateListener(a -> { t = (float) a.getAnimatedValue(); invalidate(); });
        setContentDescription("Hermes is thinking");
    }

    @Override protected void onAttachedToWindow() { super.onAttachedToWindow(); if (Ui.motionEnabled()) anim.start(); else invalidate(); }
    @Override protected void onDetachedFromWindow() { anim.cancel(); super.onDetachedFromWindow(); }

    @Override protected void onMeasure(int w, int h) { setMeasuredDimension(Ui.dp(getContext(), 44), Ui.dp(getContext(), 20)); }

    @Override protected void onDraw(Canvas c) {
        float d = getResources().getDisplayMetrics().density, cy = getHeight() / 2f;
        for (int i = 0; i < 3; i++) {
            float phase = (t - i * .18f + 1f) % 1f;
            float lift = phase < .5f ? (float) Math.sin(phase * 2 * Math.PI) : 0f;
            p.setColor(lift > .05f ? Ui.pick(0xffc9d3e0, Ui.TEXT) : Ui.pick(0xff7d8795, Ui.FAINT));
            c.drawCircle(d * (8 + i * 13), cy - lift * d * 4, d * 3.6f, p);
        }
    }
}
