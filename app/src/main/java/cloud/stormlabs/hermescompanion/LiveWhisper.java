package cloud.stormlabs.hermescompanion;

import org.json.JSONObject;

import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/**
 * One streaming Whisper utterance: audio goes to the server while you are still talking and the
 * server answers with {@code transcript-partial} events (live text) and a final {@code transcript}.
 * Servers that do not know partials simply send only the final one. Any failure makes
 * {@link #finish} throw, so callers fall back to the one-shot {@link WhisperClient#transcribe}.
 * When the voice port is out of reach (off the LAN without Tailscale) the same take runs through the hub over
 * https instead: audio goes up in ~250 ms POSTs and each reply carries the newest partial.
 */
final class LiveWhisper {
    private final WhisperClient client;
    private final String language;
    private final Consumer<String> onPartial;
    private final Runnable onEndpoint;
    private final HubApi hub;
    private final boolean hubFirst;
    private final java.io.ByteArrayOutputStream pending = new java.io.ByteArrayOutputStream();
    private volatile String hubTake;
    private volatile boolean endpointSent;
    /** Where this take's words come from, for the voice screens. */
    volatile String route = "Connecting to the speech server…";
    private final ExecutorService writer = Executors.newSingleThreadExecutor(r -> { Thread t = new Thread(r, "whisper-live-w"); t.setDaemon(true); return t; });
    private final CountDownLatch done = new CountDownLatch(1);
    private volatile Socket socket;
    private volatile OutputStream out;
    private volatile String finalText;
    private volatile Throwable error;
    private volatile boolean closed;

    LiveWhisper(WhisperClient client, String language, Consumer<String> onPartial, Runnable onEndpoint, HubApi hub, boolean hubFirst) {
        this.client = client; this.language = language; this.onPartial = onPartial; this.onEndpoint = onEndpoint;
        this.hub = hub; this.hubFirst = hubFirst && hub != null;
        writer.submit(this::connect);
        // Whatever happens, never hold the socket longer than the longest take.
        Thread guard = new Thread(() -> { try { if (!done.await(150, TimeUnit.SECONDS)) abort(); } catch (InterruptedException ignored) {} }, "whisper-live-g");
        guard.setDaemon(true); guard.start();
    }

    private void connect() {
        if (hubFirst) { connectHub(null); return; }
        try {
            Socket s;
            try { s = Reach.socket(client.host(), client.port(), 5000); }
            catch (java.io.IOException e) { if (hub == null) throw e; SpeechRecognition.portDown(); connectHub(e); return; }
            route = "Homelab server · live";
            s.setSoTimeout(90_000);
            socket = s; out = s.getOutputStream();
            JSONObject req = new JSONObject();
            if (language != null && !language.isEmpty() && !"auto".equals(language)) req.put("language", language);
            WhisperClient.send(out, "transcribe", req, null);
            WhisperClient.send(out, "audio-start", fmt(), null);
            out.flush();
            Thread r = new Thread(this::read, "whisper-live-r"); r.setDaemon(true); r.start();
        } catch (Throwable t) { fail(t); }
    }

    private void connectHub(Exception portError) {
        try {
            hubTake = hub.liveStart(language);
            route = "Homelab server over https · live";
        } catch (Throwable t) { if (portError != null) t.addSuppressed(portError); fail(t); }
    }

    /** Writer thread: one POST with everything recorded since the last one, then show what came back. */
    private void postPending(boolean stop) throws Exception {
        byte[] chunk;
        synchronized (pending) { chunk = pending.toByteArray(); pending.reset(); }
        if (stop) { finalText = hub.liveStop(hubTake, chunk).trim(); done.countDown(); return; }
        JSONObject r = hub.liveAudio(hubTake, chunk);
        String partial = r.optString("partial", "").trim();
        if (!closed && !partial.isEmpty()) onPartial.accept(partial);
        if (r.optBoolean("endpoint") && !endpointSent && !closed && onEndpoint != null) { endpointSent = true; onEndpoint.run(); }
    }

    private static JSONObject fmt() throws Exception {
        return new JSONObject().put("rate", WhisperClient.RATE).put("width", WhisperClient.WIDTH).put("channels", WhisperClient.CHANNELS);
    }

    private void read() {
        try {
            InputStream in = socket.getInputStream();
            while (true) {
                JSONObject header = new JSONObject(WhisperClient.readLine(in));
                JSONObject data = header.optJSONObject("data");
                int dataLength = header.optInt("data_length", 0);
                if (dataLength > 0) data = new JSONObject(new String(WhisperClient.readExactly(in, dataLength), StandardCharsets.UTF_8));
                int payloadLength = header.optInt("payload_length", 0);
                if (payloadLength > 0) WhisperClient.readExactly(in, payloadLength);
                String type = header.optString("type", "");
                if ("transcript-partial".equals(type) && data != null) {
                    if (!closed) onPartial.accept(data.optString("text", "").trim());
                } else if ("endpoint".equals(type)) {
                    // The server heard a finished sentence followed by quiet: the take can end now.
                    if (!closed && onEndpoint != null) onEndpoint.run();
                } else if ("transcript".equals(type)) {
                    finalText = data == null ? "" : data.optString("text", "").trim();
                    done.countDown(); return;
                } else if ("error".equals(type)) {
                    throw new IllegalStateException("Whisper: " + (data == null ? "error" : data.optString("text", "error")));
                }
            }
        } catch (Throwable t) { fail(t); }
    }

    /** Recording thread: queue a chunk of 16 kHz mono PCM. Runs after {@link #connect} on the writer, so the transport is known. */
    void feed(byte[] bytes, int len) {
        if (closed || error != null) return;
        final byte[] copy = java.util.Arrays.copyOf(bytes, len);
        try {
            writer.submit(() -> {
                if (error != null) return;
                try {
                    if (hubTake != null) {
                        synchronized (pending) { pending.write(copy, 0, copy.length); if (pending.size() < 8000) return; }  // ~250 ms per POST
                        postPending(false);
                    } else if (out != null) { WhisperClient.send(out, "audio-chunk", fmt(), copy); out.flush(); }
                } catch (Throwable t) { fail(t); }
            });
        } catch (java.util.concurrent.RejectedExecutionException closedMeanwhile) { /* aborted between the check and the submit: the recorder must not see it */ }
    }

    /** Stop sending and wait for the server's final transcript; throws if the stream broke. */
    String finish() throws Exception {
        writer.submit(() -> {
            if (error == null && hubTake != null) { try { postPending(true); } catch (Throwable t) { fail(t); } return; }
            if (error != null || out == null) return;
            try { WhisperClient.send(out, "audio-stop", new JSONObject(), null); out.flush(); }
            catch (Throwable t) { fail(t); }
        });
        if (!done.await(90, TimeUnit.SECONDS)) fail(new java.io.IOException("Whisper timed out"));
        close();
        if (error != null) throw error instanceof Exception ? (Exception) error : new Exception(error);
        return finalText == null ? "" : finalText;
    }

    /** Drop the stream (cancelled take, no speech). */
    void abort() { closed = true; if (error == null) error = new java.io.IOException("aborted"); done.countDown(); close(); }

    private void fail(Throwable t) { if (error == null) error = t; done.countDown(); }

    private void close() { closed = true; writer.shutdown(); try { if (socket != null) socket.close(); } catch (Throwable ignored) {} }
}
