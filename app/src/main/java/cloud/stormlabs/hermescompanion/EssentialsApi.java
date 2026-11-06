package cloud.stormlabs.hermescompanion;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedOutputStream;
import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FilterOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * Plain HTTP client for the Essentials capture inbox. Kept free of Android UI and of the
 * Hermes API so the capture screen can talk to a second service without a second code path.
 */
public final class EssentialsApi {
    public static final class Command {
        public final String id, text, state, at;
        Command(JSONObject o) {
            id = o.optString("id", ""); text = o.optString("text", "");
            state = o.optString("state", ""); at = o.optString("at", "");
        }
    }

    public static final class Item {
        public final String id, kind, text, title, transcript, triage, note, status, lastError;
        public final double ts, updatedAt;
        public final List<String> actions;
        public final List<Command> commands;
        Item(JSONObject o) {
            id = o.optString("id", ""); kind = o.optString("kind", ""); text = o.optString("text", "");
            title = o.optString("title", ""); transcript = o.optString("transcript", "");
            triage = o.optString("triage", ""); note = o.optString("note", "");
            status = o.optString("status", ""); lastError = o.optString("last_error", "");
            ts = o.optDouble("ts", 0); updatedAt = o.optDouble("updated_at", 0);
            actions = new ArrayList<>();
            JSONArray rawActions = o.optJSONArray("actions");
            if (rawActions != null) for (int i = 0; i < rawActions.length(); i++) actions.add(rawActions.optString(i, ""));
            commands = new ArrayList<>();
            JSONArray rawCommands = o.optJSONArray("commands");
            if (rawCommands != null) for (int i = 0; i < rawCommands.length(); i++) {
                JSONObject c = rawCommands.optJSONObject(i);
                if (c != null) commands.add(new Command(c));
            }
        }
    }

    public static final class Sync {
        public final double version;
        public final List<Item> items;
        Sync(double version, List<Item> items) { this.version = version; this.items = items; }
    }

    public interface ProgressListener {
        void onProgress(int percent);
    }

    private final String base, token;
    public EssentialsApi(String base, String token) {
        this.base = UrlUtils.normalize(base);
        this.token = token == null ? "" : token.trim();
    }

    /** Pulls only the captures changed after the watermark so the inbox can refresh cheaply. */
    public Sync sync(double since) throws Exception {
        HttpURLConnection c = open("GET", "/api/sync?since=" + number(since));
        try {
            int code = c.getResponseCode();
            boolean unchanged = code == HttpURLConnection.HTTP_NOT_MODIFIED;
            String body = unchanged ? "" : read(c, code);
            if (!unchanged && (code < 200 || code >= 300)) throw new IllegalStateException(errorText(code, body));
            return syncResult(code, body, since);
        } finally { c.disconnect(); }
    }

    /** Lists the newest captures for the inbox list view. */
    public List<Item> items(int limit) throws Exception {
        return parseItems(get("/api/items?limit=" + limit));
    }

    /** Fetches one capture so the detail view can show live status and commands. */
    public Item item(String id) throws Exception {
        return parseItem(requestJson("GET", "/api/item/" + path(id), null, "capture " + id + " not found"));
    }

    /** Queues a follow-up command on a capture; replaying a command id is safe, the server de-duplicates it. */
    public Item command(String id, String text, String commandId) throws Exception {
        JSONObject body = new JSONObject()
                .put("text", text == null ? "" : text)
                .put("command_id", commandId == null ? "" : commandId);
        JSONObject root = requestJson("POST", "/api/item/" + path(id) + "/command", body, "capture " + id + " not found");
        JSONObject item = root.optJSONObject("item");
        return parseItem(item == null ? root : item);
    }

    /** Deletes a capture, reporting false instead of throwing when it is busy or already gone. */
    public boolean delete(String id) throws Exception {
        HttpURLConnection c = open("DELETE", "/api/item/" + path(id));
        try {
            int code = c.getResponseCode();
            if (code == HttpURLConnection.HTTP_CONFLICT || code == HttpURLConnection.HTTP_NOT_FOUND) return false;
            String body = read(c, code);
            if (code < 200 || code >= 300) throw new IllegalStateException(errorText(code, body));
            return true;
        } finally { c.disconnect(); }
    }

    /** Confirms the inbox service is reachable and identifies it, without touching capture data. */
    public String health() throws Exception {
        String body = get("/health").trim();
        if (!body.startsWith("{")) return body;
        return new JSONObject(body).optString("source_id", "");
    }

    /** Uploads a capture with its attachments; files are streamed so large queues do not exhaust the heap. */
    public Item upload(String captureId, String kind, String text, String title,
                       List<File> files, ProgressListener progress) throws Exception {
        List<File> payload = files == null ? new ArrayList<File>() : files;
        String boundary = "----HermesCompanion" + System.currentTimeMillis();
        long total = 0;
        for (File f : payload) if (f != null) total += f.length();
        HttpURLConnection c = open("POST", "/api/dump");
        c.setRequestProperty("Content-Type", "multipart/form-data; boundary=" + boundary);
        c.setChunkedStreamingMode(64 * 1024);
        try {
            try (OutputStream out = new ProgressOutputStream(
                    new BufferedOutputStream(c.getOutputStream()), total, progress)) {
                writeField(out, boundary, "capture_id", captureId);
                writeField(out, boundary, "kind", kind);
                writeField(out, boundary, "text", text);
                writeField(out, boundary, "title", title);
                for (File f : payload) if (f != null) writeFile(out, boundary, f);
                out.write(("--" + boundary + "--\r\n").getBytes(StandardCharsets.UTF_8));
            }
            int code = c.getResponseCode();
            String body = read(c, code);
            if (code == HttpURLConnection.HTTP_CONFLICT) throw new IllegalStateException("HTTP 409: capture id " + captureId + " already holds a different payload");
            if (code == 413) throw new IllegalStateException("HTTP 413: upload too large");
            if (code < 200 || code >= 300) throw new IllegalStateException(errorText(code, body));
            JSONObject root = json(body);
            JSONObject item = root.optJSONObject("item");
            return parseItem(item == null ? root : item);
        } finally { c.disconnect(); }
    }

    /** Parses one capture, tolerating absent keys so a sparse payload never breaks the inbox. */
    static Item parseItem(JSONObject o) { return new Item(o == null ? new JSONObject() : o); }

    /** Parses a list payload that may arrive either bare or wrapped, so both server shapes render. */
    static List<Item> parseItems(String body) throws Exception {
        String trim = body == null ? "" : body.trim();
        if (trim.isEmpty()) return new ArrayList<>();
        JSONArray data = trim.startsWith("[") ? new JSONArray(trim) : new JSONObject(trim).optJSONArray("items");
        return parseItems(data);
    }

    static List<Item> parseItems(JSONArray data) throws Exception {
        List<Item> out = new ArrayList<>();
        if (data == null) return out;
        for (int i = 0; i < data.length(); i++) {
            JSONObject o = data.optJSONObject(i);
            if (o != null) out.add(parseItem(o));
        }
        return out;
    }

    /** Parses a sync body, treating an empty body as "nothing changed" at the given watermark. */
    static Sync parseSync(String body, double since) throws Exception {
        String trim = body == null ? "" : body.trim();
        if (trim.isEmpty()) return new Sync(since, new ArrayList<Item>());
        JSONObject root = new JSONObject(trim);
        return new Sync(root.optDouble("version", since), parseItems(root.optJSONArray("items")));
    }

    /** Turns an HTTP status into a sync result, mapping 304 to the unchanged watermark. */
    static Sync syncResult(int code, String body, double since) throws Exception {
        if (code == HttpURLConnection.HTTP_NOT_MODIFIED) return new Sync(since, new ArrayList<Item>());
        return parseSync(body, since);
    }

    private String get(String endpoint) throws Exception {
        HttpURLConnection c = open("GET", endpoint);
        try {
            int code = c.getResponseCode(); String body = read(c, code);
            if (code < 200 || code >= 300) throw new IllegalStateException(errorText(code, body));
            return body;
        } finally { c.disconnect(); }
    }

    private JSONObject requestJson(String method, String endpoint, JSONObject body, String notFound) throws Exception {
        HttpURLConnection c = open(method, endpoint);
        try {
            if (body != null) {
                byte[] data = body.toString().getBytes(StandardCharsets.UTF_8);
                try (OutputStream out = c.getOutputStream()) { out.write(data); }
            }
            int code = c.getResponseCode(); String response = read(c, code);
            if (code == HttpURLConnection.HTTP_NOT_FOUND && notFound != null) throw new IllegalStateException("HTTP 404: " + notFound);
            if (code < 200 || code >= 300) throw new IllegalStateException(errorText(code, response));
            return json(response);
        } finally { c.disconnect(); }
    }

    private HttpURLConnection open(String method, String endpoint) throws Exception {
        HttpURLConnection c = (HttpURLConnection)new URL(UrlUtils.endpoint(base, endpoint)).openConnection();
        c.setRequestMethod(method); c.setConnectTimeout(15_000); c.setReadTimeout(300_000); c.setDoInput(true);
        c.setRequestProperty("Accept", "application/json");
        c.setRequestProperty("Content-Type", "application/json; charset=utf-8");
        c.setRequestProperty("Authorization", "Bearer " + token);
        c.setRequestProperty("User-Agent", "Hermes-Companion-Android/3.0");
        if ("POST".equals(method) || "PATCH".equals(method)) c.setDoOutput(true);
        return c;
    }

    private static void writeField(OutputStream out, String boundary, String name, String value) throws Exception {
        out.write(("--" + boundary + "\r\nContent-Disposition: form-data; name=\"" + name + "\"\r\n\r\n"
                + (value == null ? "" : value) + "\r\n").getBytes(StandardCharsets.UTF_8));
    }

    private static void writeFile(OutputStream out, String boundary, File file) throws Exception {
        out.write(("--" + boundary + "\r\nContent-Disposition: form-data; name=\"files\"; filename=\""
                + file.getName().replace('"', '_') + "\"\r\nContent-Type: application/octet-stream\r\n\r\n")
                .getBytes(StandardCharsets.UTF_8));
        try (InputStream in = new FileInputStream(file)) {
            byte[] buffer = new byte[64 * 1024]; int read;
            while ((read = in.read(buffer)) > 0) out.write(buffer, 0, read);
        }
        out.write("\r\n".getBytes(StandardCharsets.UTF_8));
    }

    private static JSONObject json(String body) throws Exception {
        String trim = body == null ? "" : body.trim();
        return trim.isEmpty() ? new JSONObject() : new JSONObject(trim);
    }

    /** The Charset overload of URLEncoder.encode is API 33+; the String overload works on every supported release. */
    private static String path(String raw) {
        try { return URLEncoder.encode(raw == null ? "" : raw, StandardCharsets.UTF_8.name()).replace("+", "%20"); }
        catch (java.io.UnsupportedEncodingException e) { throw new IllegalStateException(e); }
    }

    private static String number(double value) {
        String text = Double.toString(value);
        return text.endsWith(".0") ? text.substring(0, text.length() - 2) : text;
    }

    private static String read(HttpURLConnection c, int code) throws Exception {
        InputStream stream = code >= 400 ? c.getErrorStream() : c.getInputStream(); if (stream == null) return "";
        StringBuilder b = new StringBuilder();
        try (BufferedReader r = new BufferedReader(new InputStreamReader(stream, StandardCharsets.UTF_8))) {
            String line; while ((line = r.readLine()) != null && b.length() < 2_000_000) b.append(line);
        }
        return b.toString();
    }

    private static String errorText(int code, String body) {
        try {
            JSONObject o = new JSONObject(body); Object e = o.opt("error");
            if (e instanceof JSONObject) return "HTTP " + code + ": " + ((JSONObject)e).optString("message", e.toString());
            String d = o.optString("detail", o.optString("message", ""));
            if (!d.isEmpty()) return "HTTP " + code + ": " + d;
        } catch (Exception ignored) {}
        return "HTTP " + code + (body.isEmpty() ? "" : ": " + body.substring(0, Math.min(body.length(), 300)));
    }

    /** Reports upload progress against the attachment bytes so the UI can show a live bar. */
    private static final class ProgressOutputStream extends FilterOutputStream {
        private final long total;
        private final ProgressListener listener;
        private long written;
        ProgressOutputStream(OutputStream out, long total, ProgressListener listener) {
            super(out); this.total = total; this.listener = listener;
        }
        @Override public void write(int b) throws IOException { out.write(b); written++; report(); }
        @Override public void write(byte[] b, int off, int len) throws IOException {
            out.write(b, off, len); written += len; report();
        }
        private void report() { if (listener != null && total > 0) listener.onProgress((int)Math.min(100, written * 100 / total)); }
    }
}
