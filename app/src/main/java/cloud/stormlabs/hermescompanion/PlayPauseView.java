package cloud.stormlabs.hermescompanion;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.view.View;

/** Round translucent play/pause button whose glyph morphs between a triangle and two bars (two quads, interpolated). */
final class PlayPauseView extends View {
    // Left quad then right quad; each is TL, TR, BR, BL as (x, y) in a unit box. Play = the triangle cut in half.
    private static final float[] PLAY = {.28f, .16f, .55f, .33f, .55f, .67f, .28f, .84f, .55f, .33f, .82f, .50f, .82f, .50f, .55f, .67f};
    private static final float[] PAUSE = {.26f, .18f, .42f, .18f, .42f, .82f, .26f, .82f, .58f, .18f, .74f, .18f, .74f, .82f, .58f, .82f};

    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path path = new Path();
    private ValueAnimator anim;
    private float t;          // 0 = play, 1 = pause
    private boolean playing, set;

    PlayPauseView(Context c) {
        super(c);
        setClickable(true); setFocusable(true);
        Ui.press(this);
        setContentDescription("Play");
    }

    void setPlaying(boolean p) {
        if (set && p == playing) return;
        boolean first = !set;
        set = true; playing = p;
        setContentDescription(p ? "Pause" : "Play");
        if (anim != null) anim.cancel();
        if (first || !Ui.motionEnabled()) { t = p ? 1 : 0; invalidate(); return; }
        anim = ValueAnimator.ofFloat(t, p ? 1 : 0);
        anim.setDuration(200); anim.setInterpolator(Ui.EASE_OUT);
        anim.addUpdateListener(a -> { t = (float) a.getAnimatedValue(); invalidate(); });
        anim.start();
    }

    @Override protected void onDraw(Canvas c) {
        float w = getWidth(), h = getHeight(), s = Math.min(w, h);
        paint.setColor(0x73000000);
        c.drawCircle(w / 2f, h / 2f, s / 2f, paint);
        float box = s * .46f, ox = (w - box) / 2f + (1 - t) * box * .04f, oy = (h - box) / 2f;
        paint.setColor(0xFFFFFFFF);
        path.reset();
        for (int q = 0; q < 2; q++) {
            for (int i = 0; i < 4; i++) {
                int k = q * 8 + i * 2;
                float x = ox + (PLAY[k] + (PAUSE[k] - PLAY[k]) * t) * box, y = oy + (PLAY[k + 1] + (PAUSE[k + 1] - PLAY[k + 1]) * t) * box;
                if (i == 0) path.moveTo(x, y); else path.lineTo(x, y);
            }
            path.close();
        }
        c.drawPath(path, paint);
    }
}
