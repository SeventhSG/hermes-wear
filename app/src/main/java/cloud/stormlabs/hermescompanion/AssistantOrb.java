package cloud.stormlabs.hermescompanion;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RadialGradient;
import android.graphics.RectF;
import android.graphics.Shader;
import android.view.View;
import android.view.animation.LinearInterpolator;

/**
 * The assistant's voice orb. Listening: rings breathe with the smoothed mic level. Thinking: arcs orbit
 * the core. Speaking: the core pulses. Nothing is allocated per frame; with animations off it redraws only on change.
 */
final class AssistantOrb extends View {
    private final Paint ring = new Paint(Paint.ANTI_ALIAS_FLAG), core = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF arc = new RectF();
    private final ValueAnimator ticker = ValueAnimator.ofFloat(0, 1);
    private AssistantState.State state = AssistantState.State.IDLE;
    private float level, shown, phase;
    private int shaderColor;

    AssistantOrb(Context c) {
        super(c);
        ring.setStyle(Paint.Style.STROKE);
        ticker.setDuration(1000);
        ticker.setRepeatCount(ValueAnimator.INFINITE);
        ticker.setInterpolator(new LinearInterpolator());
        ticker.addUpdateListener(a -> { phase += .04f; shown = AssistantState.smooth(shown, level); invalidate(); });
        Ui.pressFeedback(this);
    }

    void state(AssistantState.State s) {
        state = s;
        if (s != AssistantState.State.LISTENING) level = 0;
        sync();
    }

    void level(float l) { level = l; if (!ticker.isRunning()) { shown = l; invalidate(); } }

    private void sync() {
        boolean alive = state != AssistantState.State.IDLE && isAttachedToWindow() && Ui.motionEnabled();
        if (alive && !ticker.isRunning()) ticker.start();
        else if (!alive && ticker.isRunning()) ticker.cancel();
        invalidate();
    }

    @Override protected void onAttachedToWindow() { super.onAttachedToWindow(); sync(); }
    @Override protected void onDetachedFromWindow() { ticker.cancel(); super.onDetachedFromWindow(); }

    private int tone() {
        switch (state) {
            case ERROR: return Ui.RED;
            case SPEAKING: return Ui.GREEN;
            case APPROVAL: return Ui.AMBER;
            default: return Ui.BLUE;
        }
    }

    @Override protected void onDraw(Canvas c) {
        float cx = getWidth() / 2f, cy = getHeight() / 2f, max = Math.min(cx, cy);
        if (max <= 0) return;
        float d = getResources().getDisplayMetrics().density;
        int tone = tone();
        if (tone != shaderColor) {
            shaderColor = tone;  // built at unit radius; the canvas scale sizes it
            core.setShader(new RadialGradient(-.3f, -.35f, 1.3f, new int[]{Ui.lighten(tone, 70), tone, Ui.mixColor(tone, Ui.BG, .45f)},
                    new float[]{0f, .6f, 1f}, Shader.TileMode.CLAMP));
        }
        float base = max * .56f, r = base;
        switch (state) {
            case LISTENING: {
                r = base * (1f + shown * .22f + (float) Math.sin(phase * 2) * .02f);
                for (int i = 0; i < 2; i++) {
                    float rr = Math.min(max - d, r + d * (3 + i * 4) + max * .3f * shown * (i + 1));
                    ring.setStrokeWidth(d * (2f - i * .6f));
                    ring.setColor(tone); ring.setAlpha(150 - i * 60);
                    c.drawCircle(cx, cy, rr, ring);
                }
                break;
            }
            case TRANSCRIBING: case THINKING: case WORKING: {
                ring.setStrokeWidth(d * 2.2f);
                ring.setColor(tone);
                for (int i = 0; i < 2; i++) {
                    float rr = base + d * (4 + i * 4);
                    arc.set(cx - rr, cy - rr, cx + rr, cy + rr);
                    ring.setAlpha(i == 0 ? 200 : 110);
                    c.drawArc(arc, (phase * 160 * (i == 0 ? 1 : -.7f)) % 360, 100, false, ring);
                }
                break;
            }
            case SPEAKING: {
                float p = (phase * 1.2f) % 1f;
                r = base * (1f + (float) Math.sin(phase * 6) * .05f);
                ring.setStrokeWidth(d * 2f);
                ring.setColor(tone); ring.setAlpha(Math.round(140 * (1 - p)));
                c.drawCircle(cx, cy, Math.min(max - d, r + (max - r) * p), ring);
                break;
            }
            default: {
                ring.setStrokeWidth(d * 1.5f);
                ring.setColor(Ui.LINE); ring.setAlpha(255);
                c.drawCircle(cx, cy, base + d * 4, ring);
            }
        }
        core.setAlpha(state == AssistantState.State.IDLE ? 190 : 255);
        c.save(); c.translate(cx, cy); c.scale(r, r);
        c.drawCircle(0, 0, 1, core);
        c.restore();
    }
}
