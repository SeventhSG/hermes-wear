package cloud.stormlabs.hermescompanion;

import android.content.Context;
import android.media.AudioDeviceInfo;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioRecord;
import android.media.MediaRecorder;
import android.media.audiofx.AcousticEchoCanceler;
import android.media.audiofx.AutomaticGainControl;
import android.media.audiofx.NoiseSuppressor;
import android.os.Build;

import java.io.ByteArrayOutputStream;

/**
 * Listens while Hermes is talking so you can talk over it. Uses the voice-communication mic source with
 * the platform's echo canceller; still, on a loudspeaker the phone can hear itself, so by default this is
 * only armed when audio goes to headphones or a Bluetooth/USB headset (see {@link #outputIsPrivate}).
 * When it hears sustained speech it hands back the last second of audio so no words are lost.
 */
final class BargeIn {
    interface Listener { void onBarge(byte[] preroll, double noiseFloor); }

    private static final int RATE = WhisperClient.RATE, FRAME = RATE / 50;     // 20 ms
    private static final int NEED_FRAMES = 11;                                // ~220 ms of sustained speech
    private volatile boolean running;
    private Thread thread;

    /** True when playback is on a headset, headphones or Bluetooth/USB audio: no loudspeaker echo to worry about. */
    static boolean outputIsPrivate(Context c) {
        try {
            AudioManager am = (AudioManager) c.getSystemService(Context.AUDIO_SERVICE);
            for (AudioDeviceInfo d : am.getDevices(AudioManager.GET_DEVICES_OUTPUTS)) {
                switch (d.getType()) {
                    case AudioDeviceInfo.TYPE_WIRED_HEADSET: case AudioDeviceInfo.TYPE_WIRED_HEADPHONES:
                    case AudioDeviceInfo.TYPE_BLUETOOTH_A2DP: case AudioDeviceInfo.TYPE_BLUETOOTH_SCO:
                    case AudioDeviceInfo.TYPE_USB_HEADSET: return true;
                    default: if (Build.VERSION.SDK_INT >= 31 && d.getType() == AudioDeviceInfo.TYPE_BLE_HEADSET) return true;
                }
            }
        } catch (Throwable ignored) {}
        return false;
    }

    boolean running() { return running; }

    @SuppressWarnings("MissingPermission")
    void start(Listener l) {
        if (running) return;
        running = true;
        thread = new Thread(() -> watch(l), "barge-in");
        thread.setDaemon(true);
        thread.start();
    }

    void stop() { running = false; }

    @SuppressWarnings("MissingPermission")
    private void watch(Listener l) {
        AudioRecord rec = null; AcousticEchoCanceler aec = null; NoiseSuppressor ns = null; AutomaticGainControl agc = null;
        try {
            int min = AudioRecord.getMinBufferSize(RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT);
            rec = new AudioRecord(MediaRecorder.AudioSource.VOICE_COMMUNICATION, RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, Math.max(min, RATE));
            if (rec.getState() != AudioRecord.STATE_INITIALIZED) return;
            try { if (AcousticEchoCanceler.isAvailable()) { aec = AcousticEchoCanceler.create(rec.getAudioSessionId()); if (aec != null) aec.setEnabled(true); } } catch (Throwable ignored) {}
            try { if (NoiseSuppressor.isAvailable()) { ns = NoiseSuppressor.create(rec.getAudioSessionId()); if (ns != null) ns.setEnabled(true); } } catch (Throwable ignored) {}
            try { if (AutomaticGainControl.isAvailable()) { agc = AutomaticGainControl.create(rec.getAudioSessionId()); if (agc != null) agc.setEnabled(false); } } catch (Throwable ignored) {}
            rec.startRecording();
            short[] buf = new short[FRAME];
            final int ringFrames = 50;                       // 1 s of history
            byte[][] ring = new byte[ringFrames][];
            int head = 0, loud = 0, seen = 0;
            double noise = 0;
            while (running) {
                int n = rec.read(buf, 0, FRAME);
                if (n < 0) return;
                if (n == 0) { Thread.sleep(5); continue; }
                double sum = 0; byte[] b = new byte[n * 2];
                for (int i = 0; i < n; i++) { sum += buf[i] * (double) buf[i]; b[2 * i] = (byte) (buf[i] & 0xff); b[2 * i + 1] = (byte) ((buf[i] >> 8) & 0xff); }
                double rms = Math.sqrt(sum / n);
                ring[head] = b; head = (head + 1) % ringFrames;
                seen++;
                if (seen <= 20) { noise = seen == 1 ? rms : (noise * (seen - 1) + rms) / seen; continue; }   // let the canceller settle, learn the floor
                if (rms < noise * 1.6) noise = noise * 0.95 + rms * 0.05; else noise *= 1.001;
                if (rms > Math.max(1100, noise * 4.5)) loud++; else loud = Math.max(0, loud - 1);
                if (loud >= NEED_FRAMES) {
                    ByteArrayOutputStream pre = new ByteArrayOutputStream();
                    for (int i = 0; i < ringFrames; i++) { byte[] f = ring[(head + i) % ringFrames]; if (f != null) pre.write(f, 0, f.length); }
                    running = false;
                    l.onBarge(pre.toByteArray(), noise);
                    return;
                }
            }
        } catch (Throwable ignored) {
        } finally {
            running = false;
            try { if (rec != null) rec.stop(); } catch (Throwable ignored) {}
            try { if (rec != null) rec.release(); } catch (Throwable ignored) {}
            try { if (aec != null) aec.release(); } catch (Throwable ignored) {}
            try { if (ns != null) ns.release(); } catch (Throwable ignored) {}
            try { if (agc != null) agc.release(); } catch (Throwable ignored) {}
        }
    }
}
