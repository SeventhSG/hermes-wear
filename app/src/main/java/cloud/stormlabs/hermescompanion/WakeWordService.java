package cloud.stormlabs.hermescompanion;

import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ServiceInfo;
import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.MediaRecorder;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.SystemClock;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.util.Log;
import android.service.voice.VoiceInteractionSession;

import com.k2fsa.sherpa.onnx.FeatureConfig;
import com.k2fsa.sherpa.onnx.KeywordSpotter;
import com.k2fsa.sherpa.onnx.KeywordSpotterConfig;
import com.k2fsa.sherpa.onnx.OnlineModelConfig;
import com.k2fsa.sherpa.onnx.OnlineStream;
import com.k2fsa.sherpa.onnx.OnlineTransducerModelConfig;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;

/**
 * Always-on "Hermes" wake word, fully on-device: a small streaming keyword-spotting model
 * (sherpa-onnx zipformer, ~5 MB) listens to the mic in a microphone foreground service.
 *
 * On a wake it keeps the same audio stream running to capture the command that follows (so
 * "Hermes, what time is it" in one breath loses nothing) while the assistant overlay opens and
 * shows it listening; the overlay then transcribes and sends it. Without the overlay (Hermes not
 * the default assistant) the service transcribes itself and offers the result as a heads-up.
 *
 * Every recorder in the app calls {@link #suspendAndWait()} first, so the mic is released while
 * the user talks to Hermes some other way. Failures (mic busy, model error) back off and retry.
 */
public final class WakeWordService extends Service {
    // ── model ───────────────────────────────────────────────────────────────────────────────────
    static final long MODEL_SIZE = 17_626_723L;
    private static final String MODEL_URL = "https://github.com/k2-fsa/sherpa-onnx/releases/download/kws-models/sherpa-onnx-kws-zipformer-gigaspeech-3.3M-2024-01-01.tar.bz2";
    private static final String MODEL_SHA256 = "f170013b4716e41b62b9bfd809687c207cef798ef9bc6534d524e17af9b6561a";
    private static final String ENCODER = "encoder-epoch-12-avg-2-chunk-16-left-64.int8.onnx",
            DECODER = "decoder-epoch-12-avg-2-chunk-16-left-64.int8.onnx",
            JOINER = "joiner-epoch-12-avg-2-chunk-16-left-64.int8.onnx", TOKENS = "tokens.txt";
    private static final String[] MODEL_FILES = {ENCODER, DECODER, JOINER, TOKENS};
    static final String WAKE = "HERMES";
    /**
     * BPE spellings of the wake word, plus decoys for words that sound like it. The spotter reports
     * whichever keyword matches best, so "hermits" lands on a decoy instead of waking Hermes.
     * Measured on synthesized speech: decoys took false wakes from 2/16 to 0/16 with no lost hits.
     */
    static final String KEYWORDS = "▁HER ME S @HERMES\n▁HER ME Z @HERMES\n▁HER M IS @HERMES\n▁HER ME ES @HERMES\n"
            + "▁HER M IT S @DECOY\n▁HER M IT @DECOY\n▁HER ▁ME S S @DECOY\n▁HER M ION E @DECOY\n▁HER B S @DECOY\n";

    static File modelDir(Context c) { return new File(c.getFilesDir(), "kws-hermes"); }
    static boolean modelInstalled(Context c) { return OnDeviceModel.present(modelDir(c), MODEL_FILES); }
    static void installModel(Context c, OnDeviceModel.Progress p) throws Exception {
        OnDeviceModel.fetch(c, modelDir(c), MODEL_URL, MODEL_SHA256, MODEL_SIZE, MODEL_FILES, p);
    }

    // ── process-wide state ──────────────────────────────────────────────────────────────────────
    private static final int RATE = WhisperClient.RATE, CHUNK = RATE / 10; // 100 ms
    /**
     * The spotter confirms "Hermes" ~0.55 s after the word ends, while a command usually starts
     * 0.25–0.4 s after it; the capture begins with this much of the audio already heard.
     */
    private static final int PREROLL = RATE * 8 / 10; // 0.8 s, so the onset of "Hey Hermes" survives the energy gate
    /** A suspension older than this is treated as leaked (its resume() never came). */
    private static final long SUSPEND_LEASE_MS = 10 * 60_000L;
    static final long PAUSE_MS = 60 * 60_000L;
    private static final Object lock = new Object();
    private static final Object workerLock = new Object();
    private static int suspensions;
    private static int overlaySuspensions;
    private static long lastSuspendAt;
    private static volatile boolean running, micHeld;
    /** The app's own TTS is talking: a reply that says "Hermes" must not wake Hermes. */
    private static volatile long speakingSince;
    static void speaking(boolean on) { speakingSince = on ? SystemClock.elapsedRealtime() : 0; }
    // A TTS engine that dies mid-reply never reports done; don't stay deaf longer than a long reply.
    private static boolean speaking() { long t = speakingSince; return t != 0 && SystemClock.elapsedRealtime() - t < 120_000; }

    static void start(Context c) {
        try {
            Intent i = new Intent(c, WakeWordService.class);
            if (Build.VERSION.SDK_INT >= 26) c.startForegroundService(i); else c.startService(i);
        } catch (Throwable ignored) {
            // Background-start restrictions: ensure() retries the next time the app is in front.
        }
    }

    static void stop(Context c) { try { c.stopService(new Intent(c, WakeWordService.class)); } catch (Throwable ignored) { } }

    static boolean isRunning() { return running; }

    /** Restarts the listener when it should run but doesn't (killed process, reboot, OEM task killer). */
    static void ensure(Context c) {
        if (!running && new SettingsStore(c).wakeEnabled() && modelInstalled(c)
                && c.checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) start(c);
    }

    /** After a reboot or app update: Android 11+ gives a background-started mic service no audio. */
    static void onBoot(Context c) {
        if (!new SettingsStore(c).wakeEnabled()) return;
        if (Build.VERSION.SDK_INT >= 30) Notifier.wakePaused(c); else ensure(c);
    }

    /** Remaining time is bounded by both wall time and elapsed time, even if the clock moves back. */
    static long pauseRemaining(long endWall, long startedElapsed, long nowWall, long nowElapsed) {
        if (endWall <= nowWall || startedElapsed <= 0 || nowElapsed < startedElapsed) return 0;
        long elapsed = nowElapsed - startedElapsed;
        return elapsed >= PAUSE_MS ? 0 : Math.min(endWall - nowWall, PAUSE_MS - elapsed);
    }

    static long pauseRemaining(SettingsStore store) {
        return pauseRemaining(store.wakePauseEnd(), store.wakePauseStartedElapsed(),
                System.currentTimeMillis(), SystemClock.elapsedRealtime());
    }

    static void pause(Context c) {
        new SettingsStore(c).wakePause(System.currentTimeMillis() + PAUSE_MS, SystemClock.elapsedRealtime());
        Notifier.post(c, Notifier.ID_WAKE, Notifier.wake(c));
    }

    static void unpause(Context c) {
        new SettingsStore(c).wakePause(0, 0);
        Notifier.post(c, Notifier.ID_WAKE, Notifier.wake(c));
    }

    /** Someone else in the app needs the mic: release it (up to 500 ms) before they open it. */
    static void suspendAndWait() {
        synchronized (lock) { suspensions++; lastSuspendAt = SystemClock.elapsedRealtime(); }
        long until = SystemClock.elapsedRealtime() + 500;
        while (micHeld && SystemClock.elapsedRealtime() < until) SystemClock.sleep(20);
    }

    static void suspendForOverlay() { synchronized (lock) { overlaySuspensions++; lastSuspendAt = SystemClock.elapsedRealtime(); } }
    static void resumeForOverlay() { synchronized (lock) { if (overlaySuspensions > 0) overlaySuspensions--; } }
    static void resume() { synchronized (lock) { if (suspensions > 0) suspensions--; } }

    private static boolean suspended() {
        synchronized (lock) {
            if (suspensions + overlaySuspensions > 0 && SystemClock.elapsedRealtime() - lastSuspendAt > SUSPEND_LEASE_MS) {
                suspensions = 0;
                overlaySuspensions = 0;
            }
            return suspensions + overlaySuspensions > 0;
        }
    }

    private static boolean handoffRequested() { synchronized (lock) { return suspensions > 0; } }

    // ── command capture handed to the overlay ──────────────────────────────────────────────────
    interface Capture {
        void onLevel(float level);
        void onCaptured(byte[] pcm, boolean heardSpeech);
    }
    private static Capture capture;
    private static byte[] pendingPcm;
    private static boolean pendingHeard;
    private static long pendingAt;

    /** The overlay opened by a wake takes the command; delivers at once if it already finished. */
    static void attachCapture(Capture c) {
        byte[] pcm; boolean heard;
        synchronized (lock) {
            capture = c;
            if (pendingPcm == null || SystemClock.elapsedRealtime() - pendingAt > 15_000) { pendingPcm = null; return; }
            pcm = pendingPcm; heard = pendingHeard; pendingPcm = null;
        }
        c.onCaptured(pcm, heard);
    }

    static void detachCapture(Capture c) { synchronized (lock) { if (capture == c) capture = null; } }

    private static final java.util.regex.Pattern LEADING_WAKE = java.util.regex.Pattern.compile(
            "^\\W*(?:(?:hey|hi|ok|okay)\\W+)?h[aeu]?rm[aeèéi]?[sz]\\b\\W*", java.util.regex.Pattern.CASE_INSENSITIVE);

    /** A command transcript without any wake word that slipped into the pre-roll. */
    private static final java.util.Set<String> JUNK = new java.util.HashSet<>(java.util.Arrays.asList(
            "", "you", "thank you", "thanks", "thanks for watching", "thank you for watching", "bye", "bye bye",
            "ok", "okay", "so", "hmm", "uh", "um", "oh", "yeah", "the", "a", "i"));

    /** Whisper's stock output for silence or noise ("Thank you.", "you"), or nothing usable: never send it. */
    static boolean junkTranscript(String text) {
        String t = text == null ? "" : text.toLowerCase(java.util.Locale.ROOT).replaceAll("[^\\p{L}\\p{N} ]", " ").replaceAll("\\s+", " ").trim();
        return t.replace(" ", "").length() < 2 || JUNK.contains(t);
    }

    static String stripWake(String text) {
        return text == null ? "" : LEADING_WAKE.matcher(text.trim()).replaceFirst("").trim();
    }

    // ── service ─────────────────────────────────────────────────────────────────────────────────
    private final Handler main = new Handler(Looper.getMainLooper());
    private Thread worker;
    /** Per instance: a worker left over from a destroyed instance must stop, not see the next one's state. */
    private volatile boolean stopped;
    private static volatile boolean abort;
    /** Set by onWake when the overlay refused to show; deliver() then uses the no-overlay path. */
    private volatile boolean showFailed;

    /** The user stopped or closed the overlay while the wake word was recording the command. */
    static void abortCapture() {
        abort = true;
        synchronized (lock) { pendingPcm = null; }
    }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        try {
            if (Build.VERSION.SDK_INT >= 30) startForeground(Notifier.ID_WAKE, Notifier.wake(this), ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE);
            else startForeground(Notifier.ID_WAKE, Notifier.wake(this));
        } catch (Throwable t) {
            Notifier.wakePaused(this);
            stopSelf(); return START_NOT_STICKY;
        }
        Notifier.cancelWakePaused(this);
        if (worker == null || !worker.isAlive()) {
            running = true;
            worker = new Thread(this::loop, "wake-word");
            worker.start();
        }
        return START_STICKY;
    }

    @Override public void onDestroy() {
        stopped = true;
        running = false;
        if (worker != null) worker.interrupt();
        Notifier.cancelWake(this);
        super.onDestroy();
    }

    @Override public IBinder onBind(Intent intent) { return null; }

    private void loop() {
        int failures = 0;
        while (!stopped) {
            try {
                // A destroyed instance may still be releasing its recorder when a new one starts.
                synchronized (workerLock) { if (!stopped) listen(); }
                failures = 0;
                continue;
            } catch (Throwable t) {
                failures++;
            } finally { if (!stopped) micHeld = false; }
            try { Thread.sleep(Math.min(30_000L, 1000L << Math.min(failures, 5))); } catch (InterruptedException e) { return; }
        }
    }

    /** One spotter session; returns normally only when the service stops. */
    @SuppressWarnings("MissingPermission")
    private void listen() throws Exception {
        if (!modelInstalled(this) || checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            stopped = true;
            main.post(this::stopSelf);
            return;
        }
        SettingsStore store = new SettingsStore(this);
        String sensitivity = store.wakeSensitivity();
        KeywordSpotter kws = new KeywordSpotter(null, spotterConfig(sensitivity));
        OnlineStream stream = null;
        AudioRecord rec = null;
        try {
            stream = kws.createStream("");
            short[] buf = new short[CHUNK];
            float[] samples = new float[CHUNK];
            short[] ring = new short[PREROLL];
            int ringPos = 0, quiet = 0;
            double ambient = 0, peak = 0;
            boolean open = false;
            boolean wasPaused = false;
            while (!stopped) {
                boolean paused = pauseRemaining(store) > 0;
                if (wasPaused && !paused) Notifier.post(this, Notifier.ID_WAKE, Notifier.wake(this));
                wasPaused = paused;
                if (suspended() || paused) {
                    if (rec != null) { Log.i("WakeWord", "suspended"); rec.stop(); rec.release(); rec = null; micHeld = false; kws.reset(stream); open = false; java.util.Arrays.fill(ring, (short) 0); }
                    SystemClock.sleep(200);
                    continue;
                }
                if (!sensitivity.equals(store.wakeSensitivity())) return; // finally releases this spotter and recorder first
                if (rec == null) {
                    int min = AudioRecord.getMinBufferSize(RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT);
                    synchronized (lock) {
                        if (suspended()) continue;
                        micHeld = true; // Reserve the mic before suspendAndWait() can return.
                    }
                    rec = new AudioRecord(MediaRecorder.AudioSource.VOICE_RECOGNITION, RATE, AudioFormat.CHANNEL_IN_MONO,
                            AudioFormat.ENCODING_PCM_16BIT, Math.max(min, RATE));
                    if (rec.getState() != AudioRecord.STATE_INITIALIZED) throw new IllegalStateException("Microphone unavailable");
                    rec.startRecording();
                    Log.i("WakeWord", "listening, record state " + rec.getRecordingState());
                }
                int n = rec.read(buf, 0, buf.length);
                if (n < 0) throw new IllegalStateException("Microphone read failed: " + n);
                if (n == 0) { SystemClock.sleep(20); continue; }
                if (speaking()) { if (open) { open = false; kws.reset(stream); } continue; }
                double sum = 0;
                for (int i = 0; i < n; i++) {
                    samples[i] = buf[i] / 32768f; sum += buf[i] * (double) buf[i];
                    ring[ringPos] = buf[i]; ringPos = (ringPos + 1) % ring.length;
                }
                double rms = Math.sqrt(sum / n);
                // Noise floor: falls fast, rises over ~20 s, so steady fan/room noise stops counting as sound
                // while a few seconds of speech barely move it.
                ambient = ambient == 0 ? rms : rms < ambient ? ambient * 0.9 + rms * 0.1 : ambient * 0.995 + rms * 0.005;
                // Energy gate: decoding every chunk cost ~18% of a core in a silent room. Decode only
                // around sound, starting with the ring so the onset of "Hermes" is not lost.
                // 1.8x the floor (was 2.5x, minimum 150) so a normal voice across the room still opens the gate.
                boolean loud = rms > Math.max(90, ambient * 1.8);
                quiet = loud ? 0 : quiet + 1;
                if (!open) {
                    if (!loud) continue;
                    open = true;
                    peak = rms;
                    float g = wakeGain(peak);
                    float[] back = new float[ring.length];
                    for (int i = 0; i < ring.length; i++) back[i] = ring[(ringPos + i) % ring.length] * g / 32768f;
                    stream.acceptWaveform(back, RATE);
                } else {
                    if (loud) peak = Math.max(peak, rms);
                    float g = wakeGain(peak);
                    float[] fed = n == CHUNK ? samples : java.util.Arrays.copyOf(samples, n);
                    if (g != 1f) for (int i = 0; i < fed.length; i++) fed[i] *= g;
                    stream.acceptWaveform(fed, RATE);
                }
                while (kws.isReady(stream)) {
                    kws.decode(stream);
                    String keyword = kws.getResult(stream).getKeyword();
                    if (keyword.isEmpty()) continue;
                    kws.reset(stream);
                    Log.i("WakeWord", "keyword " + keyword);
                    if (WAKE.equals(keyword)) {
                        short[] preroll = new short[ring.length];
                        for (int i = 0; i < ring.length; i++) preroll[i] = ring[(ringPos + i) % ring.length];
                        Captured command = captureCommand(rec, buf, ambient, preroll);
                        try { rec.stop(); } catch (Throwable ignored) { }
                        rec.release(); rec = null; micHeld = false;
                        if (command != null) deliver(command.pcm, command.heard, command.overlay);
                        open = false;
                        java.util.Arrays.fill(ring, (short) 0);
                    }
                }
                if (open && quiet > 20) { open = false; kws.reset(stream); }
            }
        } finally {
            if (rec != null) { try { rec.stop(); } catch (Throwable ignored) { } rec.release(); }
            micHeld = false;
            if (stream != null) stream.release();
            kws.release();
        }
    }

    private KeywordSpotterConfig spotterConfig(String sensitivity) throws Exception {
        File dir = modelDir(this), keywords = new File(dir, "keywords.txt");
        try (FileOutputStream out = new FileOutputStream(keywords)) { out.write(KEYWORDS.getBytes(StandardCharsets.UTF_8)); }
        OnlineTransducerModelConfig transducer = new OnlineTransducerModelConfig();
        transducer.setEncoder(new File(dir, ENCODER).getAbsolutePath());
        transducer.setDecoder(new File(dir, DECODER).getAbsolutePath());
        transducer.setJoiner(new File(dir, JOINER).getAbsolutePath());
        OnlineModelConfig model = new OnlineModelConfig();
        model.setTransducer(transducer);
        model.setTokens(new File(dir, TOKENS).getAbsolutePath());
        model.setNumThreads(1);
        FeatureConfig feat = new FeatureConfig();
        feat.setSampleRate(RATE);
        feat.setFeatureDim(80);
        KeywordSpotterConfig config = new KeywordSpotterConfig();
        config.setFeatConfig(feat);
        config.setModelConfig(model);
        config.setMaxActivePaths(4);
        config.setKeywordsFile(keywords.getAbsolutePath());
        config.setKeywordsScore(1.5f); // boosts the keyword path in the beam; decoys keep near-misses out
        config.setKeywordsThreshold(threshold(sensitivity));
        config.setNumTrailingBlanks(1);
        return config;
    }

    /** Quiet or distant speech is amplified toward a normal speaking level before the spotter sees it
     *  (never attenuated, at most 30x): on synthetic tests this doubled detection of quiet "Hermes". */
    static float wakeGain(double loudestRms) {
        return loudestRms <= 0 ? 1f : (float) Math.max(1.0, Math.min(30.0, 3000.0 / loudestRms));
    }

    static float threshold(String sensitivity) {
        return "Low".equals(sensitivity) ? 0.22f : "High".equals(sensitivity) ? 0.10f : 0.13f;
    }

    private static final class Captured {
        final byte[] pcm;
        final boolean heard, overlay;
        Captured(byte[] pcm, boolean heard, boolean overlay) { this.pcm = pcm; this.heard = heard; this.overlay = overlay; }
    }

    /** Woken: open the overlay and keep recording on the same stream until the command ends. */
    private Captured captureCommand(AudioRecord rec, short[] buf, double ambient, short[] preroll) {
        // Decided here, not on the main thread: deliver() must agree with what onWake() does.
        boolean overlay = HermesVoiceInteractionService.active();
        abort = false;
        synchronized (lock) { showFailed = false; }
        main.post(() -> onWake(overlay));
        ByteArrayOutputStream pcm = new ByteArrayOutputStream();
        byte[] bytes = new byte[buf.length * 2];
        // The pre-roll may end with the tail of "Hermes": keep it in the audio but not in the end-of-speech
        // timing, or a pause after the wake word would end the take before the command starts.
        boolean prerollSpeech = false;
        for (int off = 0; off < preroll.length; off += buf.length) {
            int n = Math.min(buf.length, preroll.length - off);
            double sum = 0;
            for (int i = 0; i < n; i++) { short v = preroll[off + i]; sum += v * (double) v; bytes[2 * i] = (byte) (v & 0xff); bytes[2 * i + 1] = (byte) ((v >> 8) & 0xff); }
            pcm.write(bytes, 0, n * 2);
            if (Math.sqrt(sum / n) > Math.max(350, ambient * 2.6)) prerollSpeech = true;
        }
        // Detection lags the keyword by ~0.5 s, so a short one-breath command ("Hermes, stop") can be
        // over before live audio starts: then waiting the full 6 s for speech only delays it.
        VoiceRecorder.Endpointer ep = new VoiceRecorder.Endpointer(1300, prerollSpeech ? 2500 : 6000, ambient);
        long started = SystemClock.elapsedRealtime();
        SettingsStore store = new SettingsStore(this);
        while (!stopped && !abort && pauseRemaining(store) == 0 && SystemClock.elapsedRealtime() - started < 30_000) {
            if (handoffRequested()) break;
            int n = rec.read(buf, 0, buf.length);
            if (n < 0) break;
            if (n == 0) { SystemClock.sleep(20); continue; }
            for (int i = 0; i < n; i++) { bytes[2 * i] = (byte) (buf[i] & 0xff); bytes[2 * i + 1] = (byte) ((buf[i] >> 8) & 0xff); }
            pcm.write(bytes, 0, n * 2);
            boolean done = ep.feed(buf, n);
            Capture c; synchronized (lock) { c = capture; }
            if (c != null) { float level = ep.level; main.post(() -> c.onLevel(level)); }
            if (done) break;
        }
        if (abort || stopped || pauseRemaining(store) > 0) return null;
        return new Captured(pcm.toByteArray(), ep.speech || prerollSpeech, overlay);
    }

    private void onWake(boolean overlay) {
        KitWake.poke(new SettingsStore(this));
        try {
            Vibrator v = (Vibrator) getSystemService(Context.VIBRATOR_SERVICE);
            if (v != null) v.vibrate(VibrationEffect.createOneShot(40, VibrationEffect.DEFAULT_AMPLITUDE));
        } catch (Throwable ignored) { }
        if (!overlay) return;
        Bundle args = new Bundle();
        args.putBoolean(HermesVoiceSession.ARG_FROM_WAKE, true);
        int flags = new SettingsStore(this).useScreenContext()
                ? VoiceInteractionSession.SHOW_WITH_ASSIST | VoiceInteractionSession.SHOW_WITH_SCREENSHOT : 0;
        if (!HermesVoiceInteractionService.show(args, flags)) {
            byte[] pcm; boolean heard;
            synchronized (lock) {
                showFailed = true;
                pcm = pendingPcm; heard = pendingHeard; pendingPcm = null;
            }
            if (pcm != null) new Thread(() -> fallback(pcm, heard), "wake-fallback").start();
        }
    }

    private void deliver(byte[] pcm, boolean heard, boolean overlay) {
        if (abort) return;
        if (overlay) {
            Capture c;
            boolean failed;
            synchronized (lock) {
                failed = showFailed;
                c = failed ? null : capture;
                if (abort) return;
                if (!failed && c == null) { pendingPcm = pcm; pendingHeard = heard; pendingAt = SystemClock.elapsedRealtime(); }
            }
            if (!failed) {
                if (c != null) main.post(() -> c.onCaptured(pcm, heard));
                return;
            }
        }
        fallback(pcm, heard);
    }

    private void fallback(byte[] pcm, boolean heard) {
        // No overlay available: Android blocks opening an activity from
        // the background, so transcribe here and offer the result as a heads-up the user taps.
        String text = "";
        if (heard) try { text = stripWake(SpeechRecognition.transcribe(this, new SettingsStore(this), pcm)); } catch (Exception ignored) { }
        Intent open = new Intent(this, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP)
                .putExtra(MainActivity.EXTRA_PROFILE_SECRET, new SettingsStore(this).intentSecret());
        if (text.isEmpty()) open.putExtra(MainActivity.EXTRA_START_VOICE, true); else open.putExtra(ChatActivity.EXTRA_PROMPT, text);
        Notifier.wakeHeard(this, open, text);
        try { startActivity(open); } catch (Throwable ignored) { }
    }
}
