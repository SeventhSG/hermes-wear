package cloud.stormlabs.hermescompanion;

import android.content.Context;
import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.MediaRecorder;
import android.os.SystemClock;

import com.k2fsa.sherpa.onnx.KeywordSpotter;
import com.k2fsa.sherpa.onnx.OnlineStream;

/**
 * Wake phrase training: the user says the phrase {@link WakePhrase#TRAIN_TIMES} times while a spotter per
 * {@link WakePhrase#LADDER} threshold hears the same audio (sherpa ignores a per-stream threshold; only the
 * keywords file and the spotter-wide one count). The loosest stream counts the attempts;
 * {@link WakePhrase#pick} then keeps the strictest threshold that heard most of them. The model itself is not
 * retrained (it can't be on a phone); this fits its sensitivity to the user's voice and phrase.
 */
final class WakeTraining {
    interface Listener { void heard(int count); }

    private volatile boolean cancelled;

    void cancel() { cancelled = true; }

    /** Blocks until the phrase was heard TRAIN_TIMES times, 60 s passed, or {@link #cancel}; returns hits per ladder step. */
    @SuppressWarnings("MissingPermission")
    int[] run(Context c, String tokens, Listener listener) throws Exception {
        int rate = WhisperClient.RATE, chunk = rate / 10;
        int[] hits = new int[WakePhrase.LADDER.length];
        WakeWordService.suspendAndWait();
        KeywordSpotter[] kws = new KeywordSpotter[hits.length];
        OnlineStream[] streams = new OnlineStream[hits.length];
        AudioRecord rec = null;
        try {
            for (int i = 0; i < kws.length; i++) {
                float t = WakePhrase.LADDER[i];
                kws[i] = new KeywordSpotter(null, WakeWordService.spotterConfig(c, WakePhrase.keyword(tokens, t) + "\n", t));
                streams[i] = kws[i].createStream("");
            }
            int min = AudioRecord.getMinBufferSize(rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT);
            rec = new AudioRecord(MediaRecorder.AudioSource.VOICE_RECOGNITION, rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, Math.max(min, rate));
            if (rec.getState() != AudioRecord.STATE_INITIALIZED) throw new IllegalStateException("Microphone unavailable");
            rec.startRecording();
            short[] buf = new short[chunk];
            float[] samples = new float[chunk];
            long end = SystemClock.elapsedRealtime() + 60_000;
            int last = hits.length - 1;
            while (!cancelled && hits[last] < WakePhrase.TRAIN_TIMES && SystemClock.elapsedRealtime() < end) {
                int n = rec.read(buf, 0, chunk);
                if (n < 0) throw new IllegalStateException("Microphone read failed: " + n);
                if (n == 0) continue;
                for (int i = 0; i < n; i++) samples[i] = buf[i] / 32768f;
                float[] fed = n == chunk ? samples : java.util.Arrays.copyOf(samples, n);
                for (int s = 0; s < streams.length; s++) {
                    streams[s].acceptWaveform(fed, rate);
                    while (kws[s].isReady(streams[s])) {
                        kws[s].decode(streams[s]);
                        if (kws[s].getResult(streams[s]).getKeyword().isEmpty()) continue;
                        kws[s].reset(streams[s]);
                        hits[s]++;
                        if (s == last) listener.heard(hits[s]);
                    }
                }
            }
            return hits;
        } finally {
            if (rec != null) { try { rec.stop(); } catch (Throwable ignored) { } rec.release(); }
            for (OnlineStream s : streams) if (s != null) s.release();
            for (KeywordSpotter k : kws) if (k != null) k.release();
            WakeWordService.resume();
        }
    }
}
