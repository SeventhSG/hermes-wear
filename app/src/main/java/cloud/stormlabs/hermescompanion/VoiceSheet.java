package cloud.stormlabs.hermescompanion;

import android.Manifest;
import android.animation.ValueAnimator;
import android.app.Activity;
import android.app.Dialog;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.provider.Settings;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RadialGradient;
import android.graphics.Shader;
import android.graphics.drawable.ColorDrawable;
import android.view.Gravity;
import android.view.HapticFeedbackConstants;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;
import android.view.animation.LinearInterpolator;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.concurrent.Executors;

/**
 * Hands-free voice input: records with {@link VoiceRecorder}, transcribes on the phone or
 * the user's own Whisper server and hands the text back. No Google speech
 * services are involved.
 */
final class VoiceSheet {
    enum Mode { CHAT, DICTATE }
    interface Callback { void onTranscript(String text); }

    private VoiceSheet() {}

    static void showPermissionDenied(Activity a) {
        Sheets.confirm(a, "Microphone access needed", "Allow microphone access in App info to record your voice.",
                "App info", false, () -> a.startActivity(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                        Uri.fromParts("package", a.getPackageName(), null))));
    }

    static void show(Activity a, SettingsStore store, Mode mode, Callback cb) {
        KitWake.poke(store);
        if (a.checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            a.requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO}, 92);
            return;
        }
        final Dialog d = new Dialog(a);
        d.requestWindowFeature(Window.FEATURE_NO_TITLE);
        d.setCanceledOnTouchOutside(true);
        LinearLayout box = new LinearLayout(a);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setGravity(Gravity.CENTER_HORIZONTAL);
        box.setPadding(dp(a, 20), dp(a, 14), dp(a, 20), dp(a, 18));
        box.setBackground(Ui.round(a, Ui.BAR, 26));
        View handle = new View(a); handle.setBackground(Ui.round(a, Ui.FAINT, 99));
        box.addView(handle, new LinearLayout.LayoutParams(dp(a, 36), dp(a, 4)));
        final TextView status = Ui.text(a, mode == Mode.DICTATE ? "Dictate a message" : "Listening…", 18, Ui.TEXT, true);
        status.setGravity(Gravity.CENTER); status.setPadding(0, dp(a, 16), 0, dp(a, 2));
        box.addView(status, new LinearLayout.LayoutParams(-1, -2));
        final TextView hint = Ui.text(a, mode == Mode.DICTATE ? "Speak naturally — Hermes will polish your words and stop when you’re done" : "Speak naturally — I'll stop when you pause", 13, Ui.SUB, false);
        hint.setGravity(Gravity.CENTER);
        box.addView(hint, new LinearLayout.LayoutParams(-1, -2));
        final Orb orb = new Orb(a);
        LinearLayout.LayoutParams op = new LinearLayout.LayoutParams(dp(a, 190), dp(a, 190)); op.topMargin = dp(a, 8);
        box.addView(orb, op);
        // One tap here changes how this take (and the next ones) end, so being cut off mid-thought is fixable on the spot.
        final TextView stopMode = Ui.text(a, "", 13, Ui.BLUE, true);
        stopMode.setGravity(Gravity.CENTER); stopMode.setPadding(dp(a, 14), dp(a, 8), dp(a, 14), dp(a, 8));
        stopMode.setBackground(Ui.stroke(a, Ui.RAISED, Ui.LINE, 99));
        LinearLayout.LayoutParams smp = new LinearLayout.LayoutParams(-2, -2); smp.topMargin = dp(a, 6);
        box.addView(stopMode, smp);
        LinearLayout buttons = new LinearLayout(a); buttons.setGravity(Gravity.CENTER);
        TextView cancel = pillButton(a, "Cancel", Ui.RAISED, Ui.TEXT);
        TextView done = pillButton(a, "Done", Ui.BLUE, Color.WHITE);
        LinearLayout.LayoutParams bp = new LinearLayout.LayoutParams(0, dp(a, 50), 1); bp.setMargins(dp(a, 6), 0, dp(a, 6), 0);
        buttons.addView(cancel, bp);
        buttons.addView(done, new LinearLayout.LayoutParams(bp));
        LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(-1, -2); blp.topMargin = dp(a, 8);
        box.addView(buttons, blp);
        TextView source = Ui.text(a, SpeechRecognition.source(store.speechRecognition(), OnDeviceModel.installed(a)), 11, Ui.FAINT, false);
        source.setGravity(Gravity.CENTER); source.setPadding(0, dp(a, 12), 0, 0);
        box.addView(source, new LinearLayout.LayoutParams(-1, -2));

        FrameLayout frame = new FrameLayout(a);
        frame.setPadding(dp(a, 8), dp(a, 8), dp(a, 8), dp(a, 8));
        frame.addView(box, new FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM));
        d.setContentView(frame);
        Window w = d.getWindow();
        if (w != null) {
            w.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
            w.setGravity(Gravity.BOTTOM);
            w.addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND | WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
            WindowManager.LayoutParams attrs = w.getAttributes(); attrs.width = -1; attrs.dimAmount = .55f; w.setAttributes(attrs);
        }

        // Both modes end on their own once you stop talking; dictation waits longer because people pause mid-thought.
        final boolean manual = store.voiceManualStop();
        final int pause = store.voicePauseMs();
        final VoiceRecorder rec = manual ? new VoiceRecorder(false, pause, 300_000)
                : mode == Mode.DICTATE ? new VoiceRecorder(true, Math.max(2600, pause), 120_000) : new VoiceRecorder(true, pause, 60_000);
        final Runnable paintStop = () -> stopMode.setText(rec.autoStopOn() ? "Stops when I pause · tap to keep listening" : "Keeps listening · tap Done when finished");
        stopMode.setOnClickListener(v -> {
            boolean nowManual = rec.autoStopOn();            // switching to "keep listening"
            rec.autoStop(!nowManual);
            store.voiceManualStop(nowManual);
            hint.setText(nowManual ? "Take your time — tap Done when you've finished" : "Speak naturally — I'll stop when you pause");
            paintStop.run();
        });
        paintStop.run();
        if (manual) { status.setText(mode == Mode.DICTATE ? "Dictate a message" : "Listening…"); hint.setText("Take your time — tap Done when you've finished"); }
        else if (pause > 1300 && mode != Mode.DICTATE) hint.setText("Speak naturally — I'll stop after a " + (pause / 1000f) + " s pause");
        final boolean[] finished = {false};
        final LiveWhisper live = SpeechRecognition.live(a, store, t -> a.runOnUiThread(() -> { if (!finished[0] && !t.isEmpty()) hint.setText(t); }),
                mode == Mode.DICTATE || !store.serverMayEndTake() ? null : () -> { if (rec.autoStopOn()) rec.stop(); });
        cancel.setOnClickListener(v -> { finished[0] = true; if (live != null) live.abort(); rec.cancel(); d.dismiss(); });
        done.setOnClickListener(v -> rec.stop());
        d.setOnDismissListener(x -> { finished[0] = true; if (live != null && rec.running()) live.abort(); if (rec.running()) rec.cancel(); orb.stop(); });

        rec.start(new VoiceRecorder.Listener() {
            public void onLevel(float level) { a.runOnUiThread(() -> { orb.level(level); source.setText(SpeechRecognition.route(live, store.speechRecognition(), OnDeviceModel.installed(a))); }); }
            public void onAudio(byte[] pcm, int length) { if (live != null) live.feed(pcm, length); }
            public void onError(String message) {
                a.runOnUiThread(() -> {
                    if (finished[0]) return;
                    status.setText("Microphone problem"); hint.setText(message); orb.stop();
                    done.setText("Done"); done.setOnClickListener(v -> d.dismiss());
                });
            }
            public void onDone(byte[] pcm, boolean heardSpeech) {
                a.runOnUiThread(() -> {
                    if (finished[0]) return;
                    if (!heardSpeech && pcm.length < HoldToTalk.MIN_AUDIO_BYTES * 2) { if (live != null) live.abort(); status.setText("I didn't catch that"); hint.setText("Tap Retry to try again"); orb.idle();
                        done.setText("Retry"); done.setOnClickListener(v -> { d.dismiss(); show(a, store, mode, cb); }); return; }
                    status.setText("Transcribing…"); hint.setText(source.getText()); orb.thinking();
                    done.setEnabled(false); done.setAlpha(.5f);
                    orb.performHapticFeedback(HapticFeedbackConstants.CONFIRM);
                });
                if (!heardSpeech && pcm.length < HoldToTalk.MIN_AUDIO_BYTES * 2) return;
                Executors.newSingleThreadExecutor().submit(() -> {
                    try {
                        String text = SpeechRecognition.finish(a, store, live, pcm);
                        a.runOnUiThread(() -> {
                            if (finished[0]) return;
                            if (text.isEmpty()) { status.setText("Nothing recognised"); hint.setText("Try again a little closer to the mic"); orb.idle(); return; }
                            finished[0] = true;
                            d.dismiss();
                            cb.onTranscript(text);
                        });
                    } catch (Exception e) {
                        a.runOnUiThread(() -> {
                            if (finished[0]) return;
                            status.setText("Speech recognition failed");
                            hint.setText(HermesApi.friendly(e) + "\nCheck Settings › Voice.");
                            orb.idle();
                            done.setEnabled(true); done.setAlpha(1f); done.setText("Settings");
                            done.setOnClickListener(v -> { d.dismiss(); a.startActivity(new Intent(a, SettingsActivity.class)); });
                        });
                    }
                });
            }
        });
        d.show();
    }

    private static TextView pillButton(Context c, String text, int fill, int color) {
        TextView t = Ui.text(c, text, 15, color, true);
        t.setGravity(Gravity.CENTER);
        t.setBackground(Ui.round(c, fill, 99));
        return t;
    }

    private static int dp(Context c, int v) { return Ui.dp(c, v); }

    /** A soft glowing orb: breathes with the mic level, spins while transcribing. */
    static final class Orb extends View {
        private final Paint glow = new Paint(Paint.ANTI_ALIAS_FLAG), ring = new Paint(Paint.ANTI_ALIAS_FLAG), core = new Paint(Paint.ANTI_ALIAS_FLAG);
        private float level, shown, phase;
        private int mode; // 0 listening, 1 thinking, 2 idle
        private final ValueAnimator ticker = ValueAnimator.ofFloat(0, 1);

        Orb(Context c) {
            super(c);
            ring.setStyle(Paint.Style.STROKE);
            ticker.setDuration(1000); ticker.setRepeatCount(ValueAnimator.INFINITE); ticker.setInterpolator(new LinearInterpolator());
            ticker.addUpdateListener(x -> { phase += .045f; shown += (level - shown) * .25f; invalidate(); });
            if (Ui.motionEnabled()) ticker.start();
            setContentDescription("Voice level");
        }
        private void still() { if (!ticker.isRunning()) { shown = level; invalidate(); } } // motion off: redraw on change only
        void level(float l) { level = l; still(); }
        void thinking() { mode = 1; level = .2f; still(); }
        void idle() { mode = 2; level = 0; still(); }
        void stop() { ticker.cancel(); }

        private long glowKey = -1; private int coreBase;

        @Override protected void onDraw(Canvas c) {
            float cx = getWidth() / 2f, cy = getHeight() / 2f, max = Math.min(cx, cy);
            float d = getResources().getDisplayMetrics().density;
            float breathe = (float) Math.sin(phase * 2) * .03f;
            float r = max * (.40f + Math.min(.5f, shown * 1.4f) * .3f + breathe);
            int base = mode == 2 ? 0xff5d6673 : Ui.BLUE, accent = mode == 1 ? 0xff9b7cf0 : 0xff7fe0c4;
            // Shaders are rebuilt only when size or colour changes, not every animation frame.
            if (glowKey != getWidth() * 31L + getHeight() + ((long) base << 32)) {
                glowKey = getWidth() * 31L + getHeight() + ((long) base << 32);
                glow.setShader(new RadialGradient(cx, cy, max, new int[]{(base & 0x00ffffff) | 0x55000000, 0x00000000}, null, Shader.TileMode.CLAMP));
            }
            c.drawCircle(cx, cy, max, glow);
            for (int i = 0; i < 3; i++) {
                // Rings spread into the space left around the core but never past the view edge (they used to clip into a square).
                float room = Math.max(0, max - r - d * 3), spread = mode == 1 ? .8f : .35f + .65f * Math.min(1f, shown * 2);
                float rr = r + room * spread * (i + 1) / 3f;
                ring.setStrokeWidth(d * (2.2f - i * .6f));
                ring.setColor((accent & 0x00ffffff) | ((int) (120 - i * 35) << 24));
                if (mode == 1) {
                    float start = (phase * 180 * (i + 1)) % 360;
                    c.drawArc(cx - rr, cy - rr, cx + rr, cy + rr, start, 110, false, ring);
                } else c.drawCircle(cx, cy, rr, ring);
            }
            if (coreBase != base) {  // built at unit radius; the canvas scale below sizes it
                coreBase = base;
                core.setShader(new RadialGradient(-.3f, -.35f, 1.3f, new int[]{0xffb7d4ff, base, 0xff2a3f7a}, new float[]{0f, .55f, 1f}, Shader.TileMode.CLAMP));
            }
            c.save(); c.translate(cx, cy); c.scale(r, r);
            c.drawCircle(0, 0, 1, core);
            c.restore();
        }
    }
}
