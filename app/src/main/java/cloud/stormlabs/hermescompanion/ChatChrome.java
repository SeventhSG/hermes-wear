package cloud.stormlabs.hermescompanion;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Shader;
import android.os.Build;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.LinearInterpolator;
import android.widget.TextView;

/** Chat depth and liveness: scroll-aware hairlines, edge fades, a running-edge shimmer, and the pure helpers behind them. */
final class ChatChrome {
    private ChatChrome() {}

    /** 0..1 opacity for a bar hairline: 0 while content is flush with the bar, 1 once it has scrolled {@code ramp} px under it. */
    static float edgeAlpha(int distance, int ramp) {
        if (distance <= 0) return 0f;
        if (ramp <= 0) return 1f;
        return Math.min(1f, distance / (float) ramp);
    }

    /** Collapsed tool-run chip text: "Used 3 tools", "Used 3 tools · 1 failed". */
    static String toolSummary(int total, int failed) {
        String base = "Used " + total + (total == 1 ? " tool" : " tools");
        return failed > 0 ? base + " · " + failed + " failed" : base;
    }

    /** Jump-to-latest pill label: "Latest" with nothing new, else the count ("3 new", "9+ new"). */
    static String jumpLabel(int unread) { return unread <= 0 ? "Latest" : ChatFeel.badge(unread) + " new"; }

    /** Streaming follows the tail only while the reader is at it; scrolling up releases it. */
    static boolean followTail(boolean nearBottom, boolean userDragging) { return nearBottom && !userDragging; }

    /** Assistant reading rhythm: 1.5x line height on running text (code and mono keep their own). Walks the tree once, at build time. */
    static void rhythm(View v) {
        if (v instanceof TextView) {
            TextView t = (TextView) v;
            if (t.getTypeface() == null || !t.getTypeface().equals(android.graphics.Typeface.MONOSPACE)) Ui.leading(t, 1.5f);
        } else if (v instanceof ViewGroup) {
            ViewGroup g = (ViewGroup) v;
            for (int i = 0; i < g.getChildCount(); i++) rhythm(g.getChildAt(i));
        }
    }

    /** Soft shadow tinted with the accent (API 28+), plain elevation below. The view needs an outline (a GradientDrawable background has one). */
    static void softShadow(View v, int tint, int elevationDp) {
        v.setElevation(Ui.dp(v.getContext(), elevationDp));
        if (Build.VERSION.SDK_INT >= 28) {
            int c = 0xff000000 | (tint & 0xffffff);
            v.setOutlineAmbientShadowColor(c); v.setOutlineSpotShadowColor(c);
        }
    }

    /** Vertical fade from the ground colour to clear: content dissolves under the bars instead of hitting them. Shader is built on size change only. */
    static final class EdgeFade extends View {
        private final Paint p = new Paint();
        private final boolean top;
        EdgeFade(Context c, boolean top) {
            super(c); this.top = top; setClickable(false);
            setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
        }
        @Override protected void onSizeChanged(int w, int h, int ow, int oh) {
            int solid = (Ui.BG & 0xffffff) | 0xd0000000, clear = Ui.BG & 0xffffff;
            p.setShader(new LinearGradient(0, 0, 0, h, top ? solid : clear, top ? clear : solid, Shader.TileMode.CLAMP));
        }
        @Override protected void onDraw(Canvas c) { c.drawRect(0, 0, getWidth(), getHeight(), p); }
    }

    /** 2dp highlight that sweeps along a card's edge while a tool runs. Static faint line when motion is off; stops when detached. */
    static final class Shimmer extends View {
        private final Paint line = new Paint(), sweep = new Paint();
        private final Matrix m = new Matrix();
        private float phase, band;
        private ValueAnimator anim;
        Shimmer(Context c, int color) {
            super(c); setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
            line.setColor((color & 0xffffff) | 0x22000000); sweep.setColor(color);
            sweep.setShader(new LinearGradient(0, 0, 1, 0, new int[]{color & 0xffffff, color, color & 0xffffff}, null, Shader.TileMode.CLAMP));
        }
        @Override protected void onSizeChanged(int w, int h, int ow, int oh) { band = w * .35f; }
        @Override protected void onAttachedToWindow() {
            super.onAttachedToWindow();
            if (!Ui.motionEnabled()) return;
            anim = ValueAnimator.ofFloat(0f, 1f); anim.setDuration(1400); anim.setRepeatCount(ValueAnimator.INFINITE);
            anim.setInterpolator(new LinearInterpolator());
            anim.addUpdateListener(a -> { phase = (float) a.getAnimatedValue(); invalidate(); });
            anim.setCurrentPlayTime(android.os.SystemClock.uptimeMillis() % 1400); // phase-locked across live rebuilds
            anim.start();
        }
        @Override protected void onDetachedFromWindow() { if (anim != null) { anim.cancel(); anim = null; } super.onDetachedFromWindow(); }
        @Override protected void onDraw(Canvas c) {
            int w = getWidth(), h = getHeight();
            c.drawRect(0, 0, w, h, line);
            if (anim == null || band <= 0) return;
            m.setScale(band, 1f); m.postTranslate(phase * (w + band) - band, 0f);
            sweep.getShader().setLocalMatrix(m);
            c.drawRect(0, 0, w, h, sweep);
        }
    }
}
