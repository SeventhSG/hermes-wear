package cloud.stormlabs.hermescompanion;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.util.TypedValue;
import android.view.View;

/**
 * A number that rolls column by column (450ms) when its value changes: the old glyph slides out and fades,
 * the new one slides in from the same side. Draws itself, so there are no per-frame allocations.
 * Use a monospace/tabular face so columns line up; with animations off the value just changes.
 */
public class DigitRoll extends View {
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final ValueAnimator anim = ValueAnimator.ofFloat(0f, 1f);
    private char[] from = new char[0], to = new char[0];
    private int[] dir = new int[0];
    private float t = 1f, colW;
    private String value = "";

    public DigitRoll(Context c) {
        super(c);
        paint.setTypeface(Ui.MONO_SEMI);
        paint.setTextSize(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, 28, getResources().getDisplayMetrics()));
        paint.setColor(Ui.TEXT);
        paint.setFontFeatureSettings("tnum");
        anim.setDuration(450);
        anim.setInterpolator(Ui.EASE_OUT);
        anim.addUpdateListener(a -> { t = (float) a.getAnimatedValue(); invalidate(); });
        anim.addListener(new AnimatorListenerAdapter() { @Override public void onAnimationEnd(Animator a) { t = 1f; invalidate(); } });
        measureColumn();
    }

    /**
     * Per column of {@code to} (right-aligned against {@code from}, like an odometer): 0 unchanged,
     * 1 roll up (new glyph is higher or the column is new), -1 roll down (new digit is lower).
     */
    public static int[] columnDiff(String from, String to) {
        int[] d = new int[to.length()];
        int off = from.length() - to.length();
        for (int i = 0; i < to.length(); i++) {
            int j = i + off;
            char n = to.charAt(i);
            if (j < 0) { d[i] = 1; continue; }
            char o = from.charAt(j);
            if (o == n) d[i] = 0;
            else d[i] = Character.isDigit(o) && Character.isDigit(n) && n < o ? -1 : 1;
        }
        return d;
    }

    /** Sets the number; animates the changed columns unless this is the first value or animations are off. */
    public void setValue(String v) {
        if (v == null) v = "";
        if (v.equals(value)) return;
        boolean animate = !value.isEmpty() && Ui.motionEnabled() && isAttachedToWindow();
        int[] diff = columnDiff(value, v);
        from = value.toCharArray(); to = v.toCharArray();
        dir = diff;
        value = v;
        setContentDescription(v);
        requestLayout();
        anim.cancel();
        if (animate) { t = 0f; anim.start(); } else { t = 1f; invalidate(); }
    }

    public String getValue() { return value; }
    public void setTextColor(int color) { paint.setColor(color); invalidate(); }
    public void setTypeface(Typeface tf) { paint.setTypeface(tf); measureColumn(); requestLayout(); invalidate(); }
    public void setTextSize(float sp) {
        paint.setTextSize(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, sp, getResources().getDisplayMetrics()));
        measureColumn(); requestLayout(); invalidate();
    }

    private void measureColumn() { colW = paint.measureText("0"); }

    @Override protected void onDetachedFromWindow() { anim.cancel(); t = 1f; super.onDetachedFromWindow(); }

    @Override protected void onMeasure(int w, int h) {
        Paint.FontMetrics m = paint.getFontMetrics();
        setMeasuredDimension(resolveSize(Math.round(colW * to.length) + getPaddingLeft() + getPaddingRight(), w),
                resolveSize(Math.round(m.bottom - m.top) + getPaddingTop() + getPaddingBottom(), h));
    }

    @Override protected void onDraw(Canvas c) {
        Paint.FontMetrics m = paint.getFontMetrics();
        float base = getPaddingTop() - m.top, rise = (m.bottom - m.top) * .6f, x0 = getPaddingLeft();
        int alpha = paint.getColor() >>> 24;
        int off = from.length - to.length;
        for (int i = 0; i < to.length; i++) {
            float x = x0 + i * colW;
            if (t >= 1f || i >= dir.length || dir[i] == 0) {
                paint.setAlpha(alpha);
                c.drawText(to, i, 1, x, base, paint);
                continue;
            }
            float s = dir[i];
            int j = i + off;
            if (j >= 0) {
                paint.setAlpha(Math.round(alpha * (1f - t)));
                c.drawText(from, j, 1, x, base - s * rise * t, paint);
            }
            paint.setAlpha(Math.round(alpha * t));
            c.drawText(to, i, 1, x, base + s * rise * (1f - t), paint);
        }
        paint.setAlpha(alpha);
    }
}
