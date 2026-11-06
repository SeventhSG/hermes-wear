package cloud.stormlabs.hermescompanion;

import android.content.Context;
import android.speech.tts.TextToSpeech;
import android.speech.tts.UtteranceProgressListener;

import java.util.List;
import java.util.Locale;

/**
 * Text-to-speech that stays off Google: it binds the engine chosen in Settings, or else the first
 * installed engine that is not Google's (Samsung TTS on Galaxy phones), and only falls back to the
 * system default when nothing else exists.
 */
final class Speaker {
    interface Done { void onDone(String utteranceId); }

    static final String GOOGLE = "com.google.android.tts";
    private TextToSpeech tts;
    private volatile boolean ready;
    private volatile boolean failed, closed;
    private final SettingsStore store;
    private Done done;
    /** Utterances asked for before the engine finished starting: {text (null = end marker), id, flush}. */
    private final java.util.ArrayList<String[]> pending = new java.util.ArrayList<>();
    private ServerVoice server;

    Speaker(Context c, SettingsStore store) {
        this.store = store;
        Context app = c.getApplicationContext();
        if (store.serverVoice()) {
            String addr = store.ttsAddress();
            if (addr.isEmpty()) addr = ServerVoice.defaultFor(store.endpoint());
            server = new ServerVoice(addr, store, new ServerVoice.Events() {
                public void onStart(String id) { }
                public void onDone(String id) { if (done != null) done.onDone(id); }
                public void onFail(String text, String id) { androidSpeak(text, id, false); }
            });
        }
        String engine = pickEngine(app, store.ttsEngine());
        TextToSpeech.OnInitListener init = status -> {
            if (closed) return;
            if (status != TextToSpeech.SUCCESS) {
                failed = true;
                java.util.ArrayList<String[]> lost = new java.util.ArrayList<>(pending); pending.clear();
                if (done != null) for (String[] p : lost) done.onDone(p[1]);
                return;
            }
            ready = true;
            try { tts.setLanguage(Locale.getDefault()); } catch (Throwable ignored) {}
            tts.setOnUtteranceProgressListener(new UtteranceProgressListener() {
                public void onStart(String id) { WakeWordService.speaking(true); }
                public void onError(String id) { WakeWordService.speaking(false); if (done != null) done.onDone(id); }
                public void onDone(String id) { WakeWordService.speaking(false); if (done != null) done.onDone(id); }
                @Override public void onStop(String id, boolean interrupted) { WakeWordService.speaking(false); }
            });
            java.util.ArrayList<String[]> queued = new java.util.ArrayList<>(pending); pending.clear();
            for (String[] p : queued) { if (p[0] == null) androidMarker(p[1]); else androidSpeak(p[0], p[1], "1".equals(p[2])); }
        };
        tts = engine == null ? new TextToSpeech(app, init) : new TextToSpeech(app, init, engine);
    }

    /** Preferred engine if installed, else the first non-Google engine, else null (system default). */
    static String pickEngine(Context c, String preferred) {
        List<TextToSpeech.EngineInfo> engines = engines(c);
        if (preferred != null && !preferred.isEmpty()) for (TextToSpeech.EngineInfo e : engines) if (e.name.equals(preferred)) return preferred;
        for (TextToSpeech.EngineInfo e : engines) if (!GOOGLE.equals(e.name)) return e.name;
        return null;
    }

    /** Installed engines, found by package query so no engine (Google's included) gets bound just to list them. */
    static List<TextToSpeech.EngineInfo> engines(Context c) {
        List<TextToSpeech.EngineInfo> out = new java.util.ArrayList<>();
        android.content.pm.PackageManager pm = c.getPackageManager();
        for (android.content.pm.ResolveInfo ri : pm.queryIntentServices(new android.content.Intent(TextToSpeech.Engine.INTENT_ACTION_TTS_SERVICE), 0)) {
            if (ri.serviceInfo == null) continue;
            TextToSpeech.EngineInfo e = new TextToSpeech.EngineInfo();
            e.name = ri.serviceInfo.packageName;
            e.label = String.valueOf(ri.loadLabel(pm));
            e.icon = ri.getIconResource();
            out.add(e);
        }
        return out;
    }

    void onDone(Done d) { done = d; }
    boolean ready() { return ready; }

    /** Say this now, replacing anything still queued. */
    void speak(String text, String utteranceId) { play(text, utteranceId, true); }
    /** Say this after whatever is already queued. */
    void enqueue(String text, String utteranceId) { play(text, utteranceId, false); }

    /** {@code onDone(id)} fires once everything queued before this call has been spoken. */
    void marker(String utteranceId) {
        if (useServer()) { server.marker(utteranceId); return; }
        androidMarker(utteranceId);
    }

    private boolean useServer() { return server != null && server.available(); }

    private void play(String text, String id, boolean flush) {
        if (text == null || text.trim().isEmpty() || failed) { if (done != null) done.onDone(id); return; }
        if (useServer()) { if (flush) server.stop(); server.enqueue(text, id); return; }
        androidSpeak(text, id, flush);
    }

    private void androidSpeak(String text, String id, boolean flush) {
        if (failed) { if (done != null) done.onDone(id); return; }
        if (!ready) { pending.add(new String[]{text, id, flush ? "1" : "0"}); return; }
        try {
            tts.setSpeechRate(store.speechRate());
            tts.setPitch(store.speechPitch());
            int mode = flush ? TextToSpeech.QUEUE_FLUSH : TextToSpeech.QUEUE_ADD;
            if (tts.speak(text, mode, null, id) != TextToSpeech.SUCCESS && done != null) done.onDone(id);
        } catch (Throwable t) { if (done != null) done.onDone(id); }
    }

    private void androidMarker(String id) {
        if (failed) { if (done != null) done.onDone(id); return; }
        if (!ready) { pending.add(new String[]{null, id, "0"}); return; }
        try {
            if (tts.playSilentUtterance(1, TextToSpeech.QUEUE_ADD, id) != TextToSpeech.SUCCESS && done != null) done.onDone(id);
        } catch (Throwable t) { if (done != null) done.onDone(id); }
    }

    void stop() {
        pending.clear(); WakeWordService.speaking(false);
        if (server != null) server.stop();
        try { if (tts != null) tts.stop(); } catch (Throwable ignored) {}
    }
    boolean speaking() {
        if (server != null && server.speaking()) return true;
        try { return tts != null && tts.isSpeaking(); } catch (Throwable t) { return false; }
    }
    void shutdown() {
        closed = true; pending.clear(); WakeWordService.speaking(false);
        if (server != null) server.shutdown();
        try { if (tts != null) tts.shutdown(); } catch (Throwable ignored) {} tts = null; ready = false;
    }
}
