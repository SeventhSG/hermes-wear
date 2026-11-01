package cloud.stormlabs.hermescompanion;

import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.MediaRecorder;
import android.media.audiofx.AutomaticGainControl;
import android.media.audiofx.NoiseSuppressor;

import java.io.ByteArrayOutputStream;

/**
 * Records 16 kHz mono PCM for Whisper with a simple energy-based end-of-speech detector:
 * once speech has been heard, a stretch of silence ends the take on its own, so hands-free
 * voice works without a button press.
 */
final class VoiceRecorder {
    interface Listener {
        /** 0..1 loudness, about 20 times a second, on the recording thread. */
        void onLevel(float level);
        /** Recording finished (silence, stop() or the time limit), on the recording thread. */
        void onDone(byte[] pcm, boolean heardSpeech);
        void onError(String message);
        /** Each captured frame of 16 kHz mono PCM as it is recorded (for live streaming), on the recording thread. */
        default void onAudio(byte[] pcm, int length) {}
    }

    private static final int RATE = WhisperClient.RATE;
    private volatile boolean running, cancelled;
    private Thread thread;
    private volatile boolean autoStop;
    private final int silenceMs, maxMs;
    private boolean streamOnly;

    VoiceRecorder(boolean autoStop) { this(autoStop, 1300, 60_000); }
    VoiceRecorder(boolean autoStop, int silenceMs, int maxMs) { this.autoStop = autoStop; this.silenceMs = silenceMs; this.maxMs = maxMs; }

    boolean running() { return running; }
    boolean autoStopOn() { return autoStop; }
    /** Turn the end-on-silence behaviour on or off while recording (the voice sheet's "pauses end my turn" switch). */
    void autoStop(boolean on) { this.autoStop = on; }

    /** Hand frames to the listener without keeping them: for hours-long sessions that would otherwise fill memory. */
    VoiceRecorder streamOnly() { this.streamOnly = true; return this; }

    private byte[] preroll; private double preNoise;
    /** Audio captured just before this take (barge-in): it counts as speech and is streamed first. */
    VoiceRecorder withPreroll(byte[] pcm, double noiseFloor) { this.preroll = pcm; this.preNoise = noiseFloor; return this; }

    void start(Listener l) {
        if (running) return;
        running = true; cancelled = false;
        thread = new Thread(() -> record(l), "voice-recorder");
        thread.start();
    }

    /** Finish now and deliver what was recorded. */
    void stop() { running = false; }
    /** Finish now and drop the audio. */
    void cancel() { cancelled = true; running = false; }

    private void record(Listener l) {
        WakeWordService.suspendAndWait();
        Take take = null;
        String error = null;
        try { take = recordNow(l); }
        catch (Throwable t) { error = t.getMessage() == null ? "Recording failed" : t.getMessage(); }
        finally { running = false; WakeWordService.resume(); }
        if (cancelled) return;
        if (error != null) l.onError(error);
        else if (take != null) l.onDone(take.pcm, take.heardSpeech);
    }

    private static final class Take {
        final byte[] pcm;
        final boolean heardSpeech;
        Take(byte[] pcm, boolean heardSpeech) { this.pcm = pcm; this.heardSpeech = heardSpeech; }
    }

    @SuppressWarnings("MissingPermission")
    private Take recordNow(Listener l) throws InterruptedException {
        int min = AudioRecord.getMinBufferSize(RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT);
        AudioRecord rec = null; NoiseSuppressor ns = null; AutomaticGainControl agc = null;
        ByteArrayOutputStream pcm = new ByteArrayOutputStream();
        try {
            rec = new AudioRecord(MediaRecorder.AudioSource.VOICE_RECOGNITION, RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, Math.max(min, RATE));
            if (rec.getState() != AudioRecord.STATE_INITIALIZED) throw new IllegalStateException("Microphone unavailable");
            try { if (NoiseSuppressor.isAvailable()) { ns = NoiseSuppressor.create(rec.getAudioSessionId()); if (ns != null) ns.setEnabled(true); } } catch (Throwable ignored) {}
            try { if (AutomaticGainControl.isAvailable()) { agc = AutomaticGainControl.create(rec.getAudioSessionId()); if (agc != null) agc.setEnabled(true); } } catch (Throwable ignored) {}
            rec.startRecording();
            short[] buf = new short[RATE / 20]; // 50 ms frames
            byte[] bytes = new byte[buf.length * 2];
            Endpointer ep = preroll != null && preNoise > 0 ? new Endpointer(silenceMs, 9000, preNoise) : new Endpointer(silenceMs, 9000);
            if (preroll != null && preroll.length > 0) { pcm.write(preroll, 0, preroll.length); l.onAudio(preroll, preroll.length); ep.speech = true; }
            long start = System.currentTimeMillis();
            while (running && System.currentTimeMillis() - start < maxMs) {
                int n = rec.read(buf, 0, buf.length);
                if (n < 0) throw new IllegalStateException("Microphone read failed (" + n + ")");
                if (n == 0) { Thread.sleep(10); continue; }
                for (int i = 0; i < n; i++) { bytes[2 * i] = (byte) (buf[i] & 0xff); bytes[2 * i + 1] = (byte) ((buf[i] >> 8) & 0xff); }
                if (!streamOnly) pcm.write(bytes, 0, n * 2);
                l.onAudio(bytes, n * 2);
                boolean done = ep.feed(buf, n);
                l.onLevel(ep.level);
                if (autoStop && done) break;
            }
            return new Take(streamOnly ? new byte[0] : pcm.toByteArray(), ep.speech);
        } finally {
            try { if (rec != null) rec.stop(); } catch (Throwable ignored) {}
            try { if (rec != null) rec.release(); } catch (Throwable ignored) {}
            try { if (ns != null) ns.release(); } catch (Throwable ignored) {}
            try { if (agc != null) agc.release(); } catch (Throwable ignored) {}
        }
    }

    /**
     * Energy-based end of speech on 16 kHz frames: learns the room's noise floor from the first
     * 300 ms, then a stretch of silence after speech (or no speech at all) ends the take.
     */
    static final class Endpointer {
        private final int silenceMs, noSpeechMs;
        private long elapsedMs, lastVoiceMs;
        private double noise, peak;
        private int frames;
        boolean speech;
        float level;

        Endpointer(int silenceMs, int noSpeechMs) { this(silenceMs, noSpeechMs, 0); }
        /** {@code noiseFloor} > 0 skips learning, for audio that starts mid-speech (right after a wake word). */
        Endpointer(int silenceMs, int noSpeechMs, double noiseFloor) {
            this.silenceMs = silenceMs; this.noSpeechMs = noSpeechMs;
            if (noiseFloor > 0) { noise = noiseFloor; frames = 6; }
        }

        /** True once the take should end. */
        boolean feed(short[] buf, int n) {
            double sum = 0;
            for (int i = 0; i < n; i++) sum += buf[i] * (double) buf[i];
            double rms = Math.sqrt(sum / n);
            elapsedMs += n * 1000L / RATE;
            frames++;
            if (frames <= 6) noise = frames == 1 ? rms : (noise * (frames - 1) + rms) / frames;
            else if (rms < noise * 1.5) noise = noise * 0.95 + rms * 0.05;
            else noise *= 1.02; // a steadily louder room (fan, TV, gain lift after speech) becomes the floor within ~3 s
            // Voiced = clearly above the floor and not a small fraction of how loud this speaker has been.
            if (rms > Math.max(350, Math.max(noise * 2.6, peak * 0.1))) { lastVoiceMs = elapsedMs; speech = true; peak = Math.max(peak, rms); }
            peak *= 0.998;
            level = (float) Math.min(1.0, rms / 6000.0);
            return speech ? elapsedMs - lastVoiceMs > silenceMs : elapsedMs > noSpeechMs;
        }
    }
}
