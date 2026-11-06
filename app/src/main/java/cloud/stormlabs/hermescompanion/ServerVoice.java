package cloud.stormlabs.hermescompanion;

import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioTrack;
import android.media.PlaybackParams;

import org.json.JSONObject;

import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.LinkedBlockingQueue;

/**
 * Text-to-speech from a self-hosted Wyoming TTS server (Piper on the homelab), played through one
 * streaming AudioTrack so consecutive sentences run together without gaps. Items that cannot be
 * synthesised (server down, timeouts) are handed back through {@link Events#onFail} so the phone's own
 * voice can say them; after a failure the server is not retried for a minute.
 */
final class ServerVoice {
    static final int DEFAULT_PORT = 10200;

    interface Events {
        void onStart(String id);
        void onDone(String id);
        /** Nothing of this item was played: say it some other way. */
        void onFail(String text, String id);
    }

    private static final class Item {
        final String text, id; final int gen;
        Item(String text, String id, int gen) { this.text = text; this.id = id; this.gen = gen; }
        boolean marker() { return text == null; }
    }

    private static final class Mark {
        final String id; final long frame;
        Mark(String id, long frame) { this.id = id; this.frame = frame; }
    }

    private final String host; private final int port;
    private final Events events;
    private final SettingsStore store;
    private final LinkedBlockingQueue<Item> queue = new LinkedBlockingQueue<>();
    private final List<Mark> marks = new ArrayList<>();
    private volatile int gen;
    private volatile boolean closed;
    private volatile long downUntil;
    private volatile Socket socket;
    private AudioTrack track;           // touched by the worker (and release in stop(), under `lock`)
    private int trackRate;
    private long written;               // frames written to the current track
    private final Object lock = new Object();
    private boolean playing;
    private long lastWrite;

    ServerVoice(String address, SettingsStore store, Events events) {
        String a = address == null ? "" : address.trim();
        if (a.contains("://")) a = a.substring(a.indexOf("://") + 3);
        while (a.endsWith("/")) a = a.substring(0, a.length() - 1);
        int colon = a.lastIndexOf(':'); String h = a; int p = DEFAULT_PORT;
        if (colon > 0) { try { p = Integer.parseInt(a.substring(colon + 1)); h = a.substring(0, colon); } catch (NumberFormatException ignored) {} }
        this.host = h; this.port = p; this.store = store; this.events = events;
        Thread w = new Thread(this::work, "server-voice"); w.setDaemon(true); w.start();
        Thread t = new Thread(this::poll, "server-voice-poll"); t.setDaemon(true); t.start();
    }

    /** Reachability test for Settings: synthesises a short phrase and reports how much audio came back. */
    static String probe(String address) throws Exception {
        ServerVoice v = new ServerVoice(address, null, new Events() { public void onStart(String id) {} public void onDone(String id) {} public void onFail(String t, String id) {} });
        v.shutdown();
        try (Socket s = Reach.socket(v.host, v.port, 3000)) {
            s.setSoTimeout(8000);
            OutputStream out = s.getOutputStream(); InputStream in = s.getInputStream();
            WhisperClient.send(out, "synthesize", new JSONObject().put("text", "Hello, this is Hermes."), null);
            out.flush();
            int rate = 22050; long bytes = 0;
            while (true) {
                JSONObject header = new JSONObject(WhisperClient.readLine(in));
                int dl = header.optInt("data_length", 0);
                JSONObject data = header.optJSONObject("data");
                if (dl > 0) data = new JSONObject(new String(WhisperClient.readExactly(in, dl), StandardCharsets.UTF_8));
                int pl = header.optInt("payload_length", 0);
                if (pl > 0) { WhisperClient.readExactly(in, pl); bytes += pl; }
                String type = header.optString("type", "");
                if ("audio-start".equals(type) && data != null) rate = data.optInt("rate", rate);
                else if ("audio-stop".equals(type)) break;
            }
            return String.format(java.util.Locale.US, "Voice server is reachable (%.1f s of audio)", bytes / 2.0 / rate);
        }
    }

    /** The TTS address to use when none is set: the Hermes host on the Wyoming TTS default port. */
    static String defaultFor(String hermesEndpoint) {
        try { return new java.net.URL(hermesEndpoint).getHost() + ":" + DEFAULT_PORT; }
        catch (Exception e) { return ""; }
    }

    boolean available() { return !closed && System.currentTimeMillis() >= downUntil && !host.isEmpty(); }

    void enqueue(String text, String id) { if (text != null && !text.trim().isEmpty()) queue.add(new Item(text, id, gen)); }
    void marker(String id) { queue.add(new Item(null, id, gen)); }

    boolean speaking() { synchronized (lock) { return playing || !queue.isEmpty(); } }

    void stop() {
        gen++;
        queue.clear();
        try { Socket s = socket; if (s != null) s.close(); } catch (Throwable ignored) {}
        synchronized (lock) { releaseTrack(); marks.clear(); }
        WakeWordService.speaking(false);
    }

    void shutdown() { closed = true; stop(); queue.add(new Item(null, "", gen)); }

    // ── worker ───────────────────────────────────────────────────────────────────────────────

    private void work() {
        while (!closed) {
            Item it;
            try { it = queue.take(); } catch (InterruptedException e) { return; }
            if (closed) return;
            if (it.gen != gen) continue;
            if (it.marker()) { synchronized (lock) { marks.add(new Mark(it.id, written)); } continue; }
            synth(it);
        }
    }

    private void synth(Item it) {
        boolean wrote = false;
        try (Socket s = Reach.socket(host, port, 2500)) {
            socket = s;
            s.setSoTimeout(15_000);
            OutputStream out = s.getOutputStream(); InputStream in = s.getInputStream();
            JSONObject req = new JSONObject().put("text", it.text);
            String chosen = store == null ? "" : store.ttsVoice();
            if (!chosen.isEmpty()) req.put("voice", new JSONObject().put("name", chosen));
            WhisperClient.send(out, "synthesize", req, null);
            out.flush();
            int rate = 22050;
            while (true) {
                JSONObject header = new JSONObject(WhisperClient.readLine(in));
                JSONObject data = header.optJSONObject("data");
                int dl = header.optInt("data_length", 0);
                if (dl > 0) data = new JSONObject(new String(WhisperClient.readExactly(in, dl), StandardCharsets.UTF_8));
                int pl = header.optInt("payload_length", 0);
                byte[] payload = pl > 0 ? WhisperClient.readExactly(in, pl) : null;
                String type = header.optString("type", "");
                if ("audio-start".equals(type) && data != null) rate = data.optInt("rate", rate);
                else if ("audio-chunk".equals(type) && payload != null) {
                    if (it.gen != gen) return;
                    if (!wrote) { wrote = true; events.onStart(it.id); }
                    write(payload, rate, it.gen);
                } else if ("audio-stop".equals(type)) break;
                else if ("error".equals(type)) throw new IllegalStateException("tts error");
            }
            synchronized (lock) { if (it.gen == gen) marks.add(new Mark(it.id, written)); }
        } catch (Throwable t) {
            if (it.gen != gen) return;          // stopped on purpose
            downUntil = System.currentTimeMillis() + 60_000;
            if (!wrote) events.onFail(it.text, it.id);
            else synchronized (lock) { marks.add(new Mark(it.id, written)); }
        } finally { socket = null; }
    }

    private void write(byte[] pcm, int rate, int itemGen) {
        AudioTrack t;
        synchronized (lock) {
            if (itemGen != gen) return;
            if (track == null || trackRate != rate) {
                releaseTrack();
                int min = AudioTrack.getMinBufferSize(rate, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT);
                track = new AudioTrack.Builder()
                        .setAudioAttributes(new AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ASSISTANT).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
                        .setAudioFormat(new AudioFormat.Builder().setSampleRate(rate).setChannelMask(AudioFormat.CHANNEL_OUT_MONO).setEncoding(AudioFormat.ENCODING_PCM_16BIT).build())
                        .setBufferSizeInBytes(Math.max(min * 2, rate / 2)) // ~250 ms
                        .setTransferMode(AudioTrack.MODE_STREAM).build();
                trackRate = rate; written = 0;
                float speed = Math.max(0.5f, Math.min(2f, store.speechRate()));
                float pitch = Math.max(0.5f, Math.min(2f, store.speechPitch()));
                if (speed != 1f || pitch != 1f) { try { track.setPlaybackParams(new PlaybackParams().setSpeed(speed).setPitch(pitch)); } catch (Throwable ignored) {} }
                track.play();
            }
            t = track; playing = true; WakeWordService.speaking(true);
        }
        int off = 0;
        while (off < pcm.length && itemGen == gen) {
            int n;
            try { n = t.write(pcm, off, pcm.length - off, AudioTrack.WRITE_BLOCKING); } catch (Throwable e) { return; }
            if (n <= 0) return;
            off += n;
            synchronized (lock) { if (itemGen == gen) { written += n / 2; lastWrite = System.currentTimeMillis(); } }
        }
    }

    private void releaseTrack() {
        AudioTrack t = track; track = null; written = 0; playing = false;
        if (t != null) { try { t.pause(); t.flush(); } catch (Throwable ignored) {} try { t.release(); } catch (Throwable ignored) {} }
    }

    /** Fires item-done callbacks once the speaker has actually played that far. */
    private void poll() {
        while (!closed) {
            try { Thread.sleep(40); } catch (InterruptedException e) { return; }
            List<String> fire = null; boolean idle = false;
            synchronized (lock) {
                AudioTrack t = track;
                long head = t == null ? written : (t.getPlaybackHeadPosition() & 0xFFFFFFFFL);
                for (int i = 0; i < marks.size(); ) {
                    Mark m = marks.get(i);
                    if (t == null || m.frame <= head) { if (fire == null) fire = new ArrayList<>(); fire.add(m.id); marks.remove(i); } else i++;
                }
                if (t != null && playing && head >= written && queue.isEmpty() && marks.isEmpty() && System.currentTimeMillis() - lastWrite > 300) {
                    idle = true; releaseTrack();
                }
            }
            if (fire != null) for (String id : fire) events.onDone(id);
            if (idle) WakeWordService.speaking(false);
        }
    }
}
