package cloud.stormlabs.hermescompanion;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.view.View;

/** Filled-area sparkline over a fixed time window, or an up/down strip (green up, red down) when {@code strip}. */
final class Sparkline extends View {
    private final Paint line = new Paint(Paint.ANTI_ALIAS_FLAG), fill = new Paint(), bg = new Paint(), txt = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path path = new Path();
    private final boolean strip;
    private final double max;
    private double[][] data;
    private long span = 86400;
    private float[] xs = new float[0], ys = new float[0];

    /** @param max fixed top of the scale (100 for percent), NaN to autoscale from 0 */
    Sparkline(Context c, int color, double max, boolean strip) {
        super(c);
        this.max = max;
        this.strip = strip;
        line.setColor(color);
        line.setStyle(Paint.Style.STROKE);
        line.setStrokeWidth(Ui.dp(c, 2));
        fill.setColor((color & 0xffffff) | 0x33000000);
        bg.setColor(Ui.RAISED);
        txt.setColor(Ui.FAINT);
        txt.setTextSize(Ui.dp(c, 12));
        txt.setTextAlign(Paint.Align.CENTER);
        setContentDescription("No history data yet");
    }

    /** @param pts [[epochSeconds, value]]; @param spanSeconds width of the window ending now */
    void setData(double[][] pts, long spanSeconds, String desc) {
        data = pts;
        span = spanSeconds;
        setContentDescription(desc);
        place();
        invalidate();
    }

    @Override protected void onSizeChanged(int w, int h, int ow, int oh) { place(); }

    private void place() {
        int n = data == null ? 0 : data.length, w = getWidth(), h = getHeight();
        if (n == 0 || w == 0) return;
        if (xs.length != n) { xs = new float[n]; ys = new float[n]; }
        double top = Double.isNaN(max) ? Math.max(1, History.stats(data)[2]) : max;
        // Less history than the range (e.g. a fresh hub): stretch what exists across the width instead of a sliver at the right.
        long now = System.currentTimeMillis() / 1000, have = Math.max(3600, now - (long) data[0][0]);
        long span = Math.min(this.span, have), start = now - span;
        float pad = line.getStrokeWidth();
        for (int i = 0; i < n; i++) {
            xs[i] = (float) Math.max(0, Math.min(1, (data[i][0] - start) / (double) span)) * w;
            ys[i] = (float) (h - pad - Math.max(0, Math.min(1, data[i][1] / top)) * (h - 2 * pad));
        }
    }

    @Override protected void onMeasure(int wSpec, int hSpec) {
        setMeasuredDimension(resolveSize(Ui.dp(getContext(), 200), wSpec), resolveSize(Ui.dp(getContext(), strip ? 14 : 44), hSpec));
    }

    @Override protected void onDraw(Canvas c) {
        int w = getWidth(), h = getHeight(), n = data == null ? 0 : data.length;
        if (n == 0) {
            c.drawRoundRect(0, 0, w, h, 6, 6, bg);
            c.drawText("no data yet", w / 2f, h / 2f + txt.getTextSize() / 3, txt);
            return;
        }
        if (strip) {
            c.drawRect(0, 0, w, h, bg);
            float seg = Math.max(2, w / 119f);
            for (int i = 0; i < n; i++) {
                line.setStyle(Paint.Style.FILL);
                line.setColor(data[i][1] > 0.5 ? Ui.GREEN : Ui.RED);
                c.drawRect(xs[i], 0, Math.min(w, xs[i] + seg), h, line);
            }
            return;
        }
        path.rewind();
        path.moveTo(xs[0], h);
        for (int i = 0; i < n; i++) path.lineTo(xs[i], ys[i]);
        path.lineTo(xs[n - 1], h);
        path.close();
        c.drawPath(path, fill);
        path.rewind();
        path.moveTo(xs[0], ys[0]);
        for (int i = 1; i < n; i++) path.lineTo(xs[i], ys[i]);
        c.drawPath(path, line);
    }
}
