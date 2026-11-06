package cloud.stormlabs.hermescompanion;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.view.View;

/** A circular-arrow skip button with "10" inside; {@code forward} mirrors the arrow. */
final class SkipView extends View {
    private final Paint arc = new Paint(Paint.ANTI_ALIAS_FLAG), fill = new Paint(Paint.ANTI_ALIAS_FLAG), text = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF oval = new RectF();
    private final Path head = new Path();
    private final boolean forward;

    SkipView(Context c, boolean forward, String description) {
        super(c);
        this.forward = forward;
        setClickable(true); setFocusable(true);
        setContentDescription(description);
        Ui.press(this);
        float d = c.getResources().getDisplayMetrics().density;
        arc.setStyle(Paint.Style.STROKE); arc.setStrokeCap(Paint.Cap.ROUND); arc.setStrokeWidth(2 * d); arc.setColor(0xFFFFFFFF);
        fill.setColor(0xFFFFFFFF);
        text.setColor(0xFFFFFFFF); text.setTextAlign(Paint.Align.CENTER); text.setTextSize(11 * d);
        text.setTypeface(Ui.num(c, "", 12, 0).getTypeface());
    }

    @Override protected void onDraw(Canvas c) {
        float w = getWidth(), h = getHeight(), cx = w / 2f, cy = h / 2f, r = Math.min(w, h) * .31f;
        oval.set(cx - r, cy - r, cx + r, cy + r);
        c.save();
        if (forward) c.scale(-1, 1, cx, cy);
        c.drawArc(oval, -90 + 22, 290, false, arc);
        float a = r * .5f;   // arrow head at the top, pointing counter-clockwise (left)
        head.reset();
        head.moveTo(cx - a * .9f, cy - r);
        head.lineTo(cx + a * .2f, cy - r - a * .75f);
        head.lineTo(cx + a * .2f, cy - r + a * .75f);
        head.close();
        c.drawPath(head, fill);
        c.restore();
        c.drawText("10", cx, cy - (text.ascent() + text.descent()) / 2f, text);
    }
}
