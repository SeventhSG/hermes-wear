package cloud.stormlabs.hermescompanion;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;
import java.nio.charset.StandardCharsets;

/**
 * Speech-to-text against a self-hosted Whisper server speaking the Wyoming protocol
 * (wyoming-faster-whisper, the same server Home Assistant uses), so voice never touches Google.
 *
 * Wire format: each event is one JSON header line, then {@code data_length} bytes of JSON data,
 * then {@code payload_length} bytes of payload. A transcription is transcribe → audio-start →
 * audio-chunk… → audio-stop, answered by a single {@code transcript} event.
 */
public final class WhisperClient implements SpeechToText {
    public static final int RATE = 16000, WIDTH = 2, CHANNELS = 1;
    public static final int DEFAULT_PORT = 10300;

    private final String host; private final int port;

    public WhisperClient(String host, int port) { this.host = host; this.port = port; }

    /** "host:port" or "tcp://host:port"; a bare host gets the default port. */
    public static WhisperClient parse(String address) {
        String a = address == null ? "" : address.trim();
        if (a.contains("://")) a = a.substring(a.indexOf("://") + 3);
        while (a.endsWith("/")) a = a.substring(0, a.length() - 1);
        int colon = a.lastIndexOf(':');
        if (colon > 0) {
            try { return new WhisperClient(a.substring(0, colon), Integer.parseInt(a.substring(colon + 1))); }
            catch (NumberFormatException ignored) {}
        }
        return new WhisperClient(a, DEFAULT_PORT);
    }

    /** The Whisper address to use when none is set: the Hermes host on the Wyoming default port. */
    public static String defaultFor(String hermesEndpoint) {
        try { return new java.net.URL(hermesEndpoint).getHost() + ":" + DEFAULT_PORT; }
        catch (Exception e) { return ""; }
    }

    public String address() { return host + ":" + port; }
    String host() { return host; }
    int port() { return port; }

    /** Transcribe 16 kHz mono 16-bit PCM. {@code language} may be null for the server's default. */
    public String transcribe(byte[] pcm, String language) throws Exception {
        try (Socket s = Reach.socket(host, port, 8000)) {
            s.setSoTimeout(90_000);
            OutputStream out = s.getOutputStream();
            JSONObject fmt = new JSONObject().put("rate", RATE).put("width", WIDTH).put("channels", CHANNELS);
            JSONObject req = new JSONObject();
            if (language != null && !language.isEmpty() && !"auto".equals(language)) req.put("language", language);
            send(out, "transcribe", req, null);
            send(out, "audio-start", fmt, null);
            int chunk = RATE * WIDTH / 5; // 200 ms per chunk
            for (int i = 0; i < pcm.length; i += chunk) {
                int n = Math.min(chunk, pcm.length - i);
                byte[] part = new byte[n];
                System.arraycopy(pcm, i, part, 0, n);
                send(out, "audio-chunk", fmt, part);
            }
            send(out, "audio-stop", new JSONObject(), null);
            out.flush();
            InputStream in = s.getInputStream();
            while (true) {
                JSONObject header = new JSONObject(readLine(in));
                JSONObject data = header.optJSONObject("data");
                int dataLength = header.optInt("data_length", 0);
                if (dataLength > 0) data = new JSONObject(new String(readExactly(in, dataLength), StandardCharsets.UTF_8));
                int payloadLength = header.optInt("payload_length", 0);
                if (payloadLength > 0) readExactly(in, payloadLength);
                String type = header.optString("type", "");
                if ("transcript".equals(type)) return data == null ? "" : data.optString("text", "").trim();
                if ("error".equals(type)) throw new IllegalStateException("Whisper: " + (data == null ? "error" : data.optString("text", "error")));
            }
        }
    }

    static void send(OutputStream out, String type, JSONObject data, byte[] payload) throws Exception {
        byte[] d = data.toString().getBytes(StandardCharsets.UTF_8);
        JSONObject header = new JSONObject().put("type", type).put("data_length", d.length);
        if (payload != null) header.put("payload_length", payload.length);
        out.write((header.toString() + "\n").getBytes(StandardCharsets.UTF_8));
        out.write(d);
        if (payload != null) out.write(payload);
    }

    static String readLine(InputStream in) throws Exception {
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        int c;
        while ((c = in.read()) != -1 && c != '\n') b.write(c);
        if (c == -1 && b.size() == 0) throw new java.io.EOFException("Whisper closed the connection");
        return b.toString(StandardCharsets.UTF_8.name());
    }

    static byte[] readExactly(InputStream in, int n) throws Exception {
        byte[] buf = new byte[n]; int off = 0;
        while (off < n) { int r = in.read(buf, off, n - off); if (r < 0) throw new java.io.EOFException(); off += r; }
        return buf;
    }
}
