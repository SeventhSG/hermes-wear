package cloud.stormlabs.hermescompanion;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.view.View;
import java.util.Locale;

/** Quiet 20dp context meter inside a 48dp accessible touch target. */
final class ContextRing extends View {
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF oval = new RectF();
    private UsageState.Context context;
    ContextRing(Context c) { super(c); setVisibility(GONE); setFocusable(true); }
    void setUsage(UsageState state) {
        context = state == null ? null : state.context;
        setVisibility(context == null ? GONE : VISIBLE);
        setContentDescription(context == null || context.percent == null ? "Context usage, tap for usage"
                : String.format(Locale.getDefault(), "Context %s%.0f%% used, tap for usage", "estimate".equals(context.source) ? "about " : "", context.percent));
        invalidate();
    }
    static int color(double percent) { return percent > 95 ? Ui.RED : percent > 80 ? Ui.AMBER : Ui.BLUE; }
    @Override protected void onDraw(Canvas c) {
        super.onDraw(c);
        if (context == null) return;
        float density = getResources().getDisplayMetrics().density;
        float cx = getWidth() / 2f, cy = getHeight() / 2f, radius = 9 * density;
        oval.set(cx - radius, cy - radius, cx + radius, cy + radius);
        paint.setStyle(Paint.Style.STROKE); paint.setStrokeWidth(2 * density); paint.setStrokeCap(Paint.Cap.ROUND);
        paint.setColor(Ui.FAINT); c.drawOval(oval, paint);
        if (context.percent == null) return;
        float used = Gauge.clamp(context.percent.floatValue() / 100);
        paint.setColor(Gauge.colorAt(context.percent > 95 ? 1 : context.percent > 80 ? .5f : 0, Ui.BLUE, Ui.AMBER, Ui.RED));
        c.drawArc(oval, -90, used * 360, false, paint);
        if (context.percent >= 80) {
            paint.setStyle(Paint.Style.FILL); paint.setTypeface(Ui.MONO); paint.setTextAlign(Paint.Align.CENTER);
            paint.setTextSize(7 * density); paint.setFontFeatureSettings("tnum");
            String number = ("estimate".equals(context.source) ? "~" : "") + Math.round(context.percent);
            c.drawText(number, cx, cy - (paint.ascent() + paint.descent()) / 2, paint);
        }
    }
}
