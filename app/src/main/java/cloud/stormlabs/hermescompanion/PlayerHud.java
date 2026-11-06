package cloud.stormlabs.hermescompanion;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.view.View;

/** Transparent overlay that draws the double-tap skip ripple and its "-10s" bubble (YouTube style) in the left or right third. */
final class PlayerHud extends View {
    private final Paint ripple = new Paint(Paint.ANTI_ALIAS_FLAG), text = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final ValueAnimator anim = ValueAnimator.ofFloat(0, 1);
    private final Runnable clear = () -> { label = null; invalidate(); };
    private float t = 1, x, y;
    private int zone;
    private String label;

    PlayerHud(Context c) {
        super(c);
        setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
        float d = c.getResources().getDisplayMetrics().density;
        ripple.setColor(0xFFFFFFFF);
        text.setColor(0xFFFFFFFF); text.setTextAlign(Paint.Align.CENTER); text.setTextSize(18 * d);
        text.setTypeface(Ui.num(c, "", 12, 0).getTypeface());
        text.setShadowLayer(4 * d, 0, d, 0x99000000);
        anim.setDuration(650); anim.setInterpolator(Ui.EASE_OUT);
        anim.addUpdateListener(a -> { t = (float) a.getAnimatedValue(); invalidate(); });
    }

    /** Shows {@code text} in the {@code zone} third, with a ripple spreading from the touch point. */
    void skip(int zone, String text, float x, float y) {
        this.zone = zone; this.label = text; this.x = x; this.y = y;
        removeCallbacks(clear);
        if (Ui.motionEnabled()) { anim.cancel(); anim.start(); }
        else { t = .3f; invalidate(); postDelayed(clear, 700); }
    }

    @Override protected void onDraw(Canvas c) {
        if (label == null) return;
        float w = getWidth(), h = getHeight(), third = w / 3f, left = zone < 0 ? 0 : w - third;
        float fade = t < .7f ? 1f : 1f - (t - .7f) / .3f;
        c.save();
        c.clipRect(left, 0, left + third, h);
        ripple.setAlpha(Math.round(56 * (1f - t) * (1f - t) + 14 * fade));
        c.drawCircle(x, y, third * 1.4f * Math.min(1f, t * 3f), ripple);
        c.restore();
        text.setAlpha(Math.round(255 * Math.max(0, fade)));
        c.drawText(label, left + third / 2f, h / 2f - (text.ascent() + text.descent()) / 2f, text);
    }

    @Override protected void onDetachedFromWindow() { super.onDetachedFromWindow(); anim.cancel(); removeCallbacks(clear); }
}
