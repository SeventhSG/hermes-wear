package cloud.stormlabs.hermescompanion;

import android.app.Activity;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Push-to-talk for the chat mic button: audio is recorded for exactly as long as the button is held,
 * with no silence timer, then transcribed. Letting go delivers the text; {@link #cancel} drops it.
 */
final class HoldToTalk {
    interface Host {
        void level(float level);
        /** Live words while the button is held. */
        void partial(String text);
        /** Let go: the take is being transcribed. */
        void transcribing();
        void result(String text);
        /** Nothing usable came out (no speech, mic problem, server error); the message is user-facing. */
        void failed(String message);
    }

    /** Half a second of 16 kHz 16-bit mono. */
    static final int MIN_AUDIO_BYTES = 16_000;

    private final Activity activity;
    private final SettingsStore store;
    private final Host host;
    private final ExecutorService pool = Executors.newSingleThreadExecutor();
    private VoiceRecorder recorder;
    private LiveWhisper live;
    private volatile boolean cancelled, ended, heardWords;
    private long startedAt;

    HoldToTalk(Activity activity, SettingsStore store, Host host) { this.activity = activity; this.store = store; this.host = host; }

    boolean active() { return recorder != null && recorder.running(); }

    void start() {
        if (active()) return;
        cancelled = false; ended = false; startedAt = System.currentTimeMillis();
        final VoiceRecorder rec = new VoiceRecorder(false, 1300, 5 * 60_000);
        recorder = rec;
        heardWords = false;
        live = SpeechRecognition.live(activity, store, t -> activity.runOnUiThread(() -> { if (!ended && !cancelled && !t.isEmpty()) { heardWords = true; host.partial(t); } }), null);
        final LiveWhisper stream = live;
        rec.start(new VoiceRecorder.Listener() {
            @Override public void onLevel(float level) { if (!ended) activity.runOnUiThread(() -> { host.level(level); if (!heardWords) host.partial("Listening · " + SpeechRecognition.route(stream, store.speechRecognition(), OnDeviceModel.installed(activity))); }); }
            @Override public void onAudio(byte[] pcm, int length) { if (stream != null) stream.feed(pcm, length); }
            @Override public void onError(String message) { finish(() -> host.failed(message)); }
            @Override public void onDone(byte[] pcm, boolean heardSpeech) {
                if (cancelled) { if (stream != null) stream.abort(); return; }
                // The energy detector learns the room from the first 300 ms, so speaking the instant the button goes
                // down can leave it "hearing nothing". The user held the button on purpose: let the recogniser decide.
                boolean enough = pcm.length >= MIN_AUDIO_BYTES;
                if (!heardSpeech && !enough) { if (stream != null) stream.abort(); finish(() -> host.failed("Didn't catch that. Hold the button while you speak.")); return; }
                activity.runOnUiThread(host::transcribing);
                pool.submit(() -> {
                    try {
                        String text = SpeechRecognition.finish(activity, store, stream, pcm);
                        if (cancelled) return;
                        if (text.isEmpty()) finish(() -> host.failed("Nothing recognised. Try again closer to the mic."));
                        else finish(() -> host.result(text));
                    } catch (Exception e) {
                        finish(() -> host.failed("Speech recognition failed: " + HermesApi.friendly(e)));
                    }
                });
            }
        });
    }

    /** Button let go: finish the take and transcribe it. */
    void release() { VoiceRecorder r = recorder; if (r != null && r.running()) r.stop(); }

    /** Slid away or interrupted: throw the take away. */
    void cancel() {
        cancelled = true;
        LiveWhisper l = live; if (l != null) l.abort();
        VoiceRecorder r = recorder; if (r != null && r.running()) r.cancel();
    }

    long heldMs() { return System.currentTimeMillis() - startedAt; }

    private void finish(Runnable ui) {
        if (ended) return;
        ended = true;
        activity.runOnUiThread(ui);
    }

    void shutdown() { cancel(); pool.shutdown(); }
}
