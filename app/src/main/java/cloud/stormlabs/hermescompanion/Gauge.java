package cloud.stormlabs.hermescompanion;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.SweepGradient;
import android.util.TypedValue;
import android.view.View;

/**
 * 240-degree arc gauge: 10dp round-cap stroke, accent to watch to critical sweep gradient, a mono number
 * in the middle and a label below. Sweeps in over 600ms on its first value and animates later changes.
 * Pure mapping helpers (sweep, colorAt) are static so they can be unit-tested.
 */
public class Gauge extends View {
    public static final float START = 150f, SPAN = 240f;

    /** Sweep angle in degrees for a 0..1 value (clamped). */
    public static float sweep(float v) { return SPAN * clamp(v); }

    /** Colour of the arc's leading end for a 0..1 value: low to mid to high, blended in RGB. */
    public static int colorAt(float v, int low, int mid, int high) {
        v = clamp(v);
        return v <= .5f ? mix(low, mid, v / .5f) : mix(mid, high, (v - .5f) / .5f);
    }

    static float clamp(float v) { return v != v ? 0f : Math.max(0f, Math.min(1f, v)); }

    static int mix(int a, int b, float t) {
        int al = Math.round(((a >>> 24)) * (1 - t) + (b >>> 24) * t),
            r = Math.round(((a >> 16) & 255) * (1 - t) + ((b >> 16) & 255) * t),
            g = Math.round(((a >> 8) & 255) * (1 - t) + ((b >> 8) & 255) * t),
            bl = Math.round((a & 255) * (1 - t) + (b & 255) * t);
        return (al << 24) | (r << 16) | (g << 8) | bl;
    }

    private final Paint track = new Paint(Paint.ANTI_ALIAS_FLAG), arc = new Paint(Paint.ANTI_ALIAS_FLAG),
            number = new Paint(Paint.ANTI_ALIAS_FLAG), caption = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF oval = new RectF();
    private final ValueAnimator anim = ValueAnimator.ofFloat(0f, 1f);
    private int low = Ui.BLUE, mid = Ui.AMBER, high = Ui.RED;
    private float shown, target, animFrom;
    private boolean hasValue;
    private String numberText = "", label = "";

    public Gauge(Context c) {
        super(c);
        float d = getResources().getDisplayMetrics().density;
        for (Paint p : new Paint[]{track, arc}) { p.setStyle(Paint.Style.STROKE); p.setStrokeWidth(10 * d); p.setStrokeCap(Paint.Cap.ROUND); }
        number.setTextAlign(Paint.Align.CENTER); number.setFontFeatureSettings("tnum");
        number.setTypeface(Ui.MONO_SEMI); number.setTextSize(sp(28));
        caption.setTextAlign(Paint.Align.CENTER); caption.setTypeface(Ui.WORD); caption.setTextSize(sp(12));
        anim.setDuration(600);
        anim.setInterpolator(Ui.EASE_OUT);
        anim.addUpdateListener(a -> { shown = animFrom + (target - animFrom) * (float) a.getAnimatedValue(); invalidate(); });
    }

    private float sp(float v) { return TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, v, getResources().getDisplayMetrics()); }

    /** Arc colours from low to high (default accent, watch, critical). */
    public void setColors(int low, int mid, int high) { this.low = low; this.mid = mid; this.high = high; rebuildShader(); invalidate(); }

    /** Value 0..1 and the number shown in the centre. The first value sweeps in from empty. */
    public void setValue(float v01, String display) {
        float v = clamp(v01);
        numberText = display == null ? "" : display;
        setContentDescription((label.isEmpty() ? "" : label + " ") + numberText);
        anim.cancel();
        animFrom = hasValue ? shown : 0f;
        target = v;
        if (Ui.motionEnabled() && isAttachedToWindow() && animFrom != v) anim.start();
        else shown = v;
        hasValue = true;
        invalidate();
    }

    public void setLabel(String l) { label = l == null ? "" : l; invalidate(); }

    @Override protected void onDetachedFromWindow() { anim.cancel(); shown = target; super.onDetachedFromWindow(); }

    @Override protected void onMeasure(int w, int h) {
        int side = Ui.dp(getContext(), 160);
        setMeasuredDimension(resolveSize(side, w), resolveSize(side, h));
    }

    @Override protected void onSizeChanged(int w, int h, int ow, int oh) {
        float inset = arc.getStrokeWidth() / 2f + Ui.dp(getContext(), 2);
        float side = Math.min(w, h);
        float cx = w / 2f, cy = h / 2f;
        oval.set(cx - side / 2f + inset, cy - side / 2f + inset, cx + side / 2f - inset, cy + side / 2f - inset);
        rebuildShader();
    }

    private void rebuildShader() {
        if (getWidth() == 0) return;
        float f = SPAN / 360f;
        // The start cap reaches a few degrees before 0°, which wraps to the end of the sweep: fade back to `low` there.
        arc.setShader(new SweepGradient(getWidth() / 2f, getHeight() / 2f, new int[]{low, mid, high, high, low}, new float[]{0f, f / 2f, f, Math.max(f, .95f), 1f}));
    }

    @Override protected void onDraw(Canvas c) {
        float cx = getWidth() / 2f, cy = getHeight() / 2f;
        track.setColor(Ui.tier(2));
        c.save();
        c.rotate(START, cx, cy);
        c.drawArc(oval, 0, SPAN, false, track);
        if (shown > 0f) c.drawArc(oval, 0, sweep(shown), false, arc);
        c.restore();
        number.setColor(Ui.TEXT);
        c.drawText(numberText, cx, cy + number.getTextSize() * .2f, number);
        if (!label.isEmpty()) {
            caption.setColor(Ui.SUB);
            c.drawText(label, cx, cy + number.getTextSize() * .2f + sp(18), caption);
        }
    }
}
