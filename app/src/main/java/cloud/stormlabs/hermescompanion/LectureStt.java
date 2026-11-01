package cloud.stormlabs.hermescompanion;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Hours-long listening for Live notes: the microphone streams to the Whisper/Parakeet server in
 * segments. A segment ends when the server hears the end of a sentence (or after 25 s with no pause), its
 * final text is reported, and a fresh stream takes over without dropping audio.
 */
final class LectureStt {
    interface Events {
        void onPartial(String text);
        void onFinal(String text);
        void onLevel(float level);
        void onProblem(String message);
    }

    private static final long MAX_SEGMENT_MS = 25_000;
    private final SettingsStore store;
    private final Events events;
    private final ExecutorService finisher = Executors.newSingleThreadExecutor(r -> { Thread t = new Thread(r, "lecture-stt"); t.setDaemon(true); return t; });
    private final Object lock = new Object();
    private LiveWhisper cur;
    private long segStart;
    private VoiceRecorder recorder;
    private volatile boolean running;
    private int gaps;

    LectureStt(SettingsStore store, Events events) { this.store = store; this.events = events; }

    boolean running() { return running; }

    void start() {
        if (running) return;
        running = true;
        synchronized (lock) { cur = open(); segStart = System.currentTimeMillis(); }
        recorder = new VoiceRecorder(false, 1300, 4 * 60 * 60 * 1000).streamOnly();
        recorder.start(new VoiceRecorder.Listener() {
            @Override public void onLevel(float level) { events.onLevel(level); }
            @Override public void onDone(byte[] pcm, boolean heardSpeech) { end(); }
            @Override public void onError(String message) { events.onProblem(message); end(); }
            @Override public void onAudio(byte[] pcm, int length) {
                LiveWhisper w;
                synchronized (lock) {
                    w = cur;
                    if (System.currentTimeMillis() - segStart > MAX_SEGMENT_MS) { roll(cur); w = cur; }
                }
                if (w != null) w.feed(pcm, length);
            }
        });
    }

    void stop() { VoiceRecorder r = recorder; if (r != null) r.stop(); }

    private void end() {
        running = false;
        LiveWhisper last;
        synchronized (lock) { last = cur; cur = null; }
        if (last != null) finishAsync(last);
        finisher.shutdown();
    }

    private LiveWhisper open() {
        final LiveWhisper[] self = new LiveWhisper[1];
        self[0] = new LiveWhisper(WhisperClient.parse(store.whisperAddress()), store.speechLanguage(),
                text -> { synchronized (lock) { if (self[0] != cur) return; } events.onPartial(text); },
                () -> { synchronized (lock) { if (self[0] != cur || !running) return; roll(self[0]); } },
                SpeechRecognition.hub(store), SpeechRecognition.httpsOnly(store));
        return self[0];
    }

    /** Hands the current stream to the finisher and starts the next one. Caller holds the lock. */
    private void roll(LiveWhisper old) {
        if (old != cur) return;
        cur = open(); segStart = System.currentTimeMillis();
        finishAsync(old);
    }

    private void finishAsync(LiveWhisper w) {
        try {
            finisher.submit(() -> {
                try {
                    String text = SpeechRecognition.tidy(w.finish());
                    if (!text.isEmpty()) events.onFinal(text);
                } catch (Throwable t) {
                    if (++gaps == 1 || gaps % 10 == 0) events.onProblem("Speech server hiccup: some audio was missed");
                }
            });
        } catch (java.util.concurrent.RejectedExecutionException ignored) { /* shutting down */ }
    }
}
