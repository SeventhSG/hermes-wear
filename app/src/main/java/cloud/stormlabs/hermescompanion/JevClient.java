package cloud.stormlabs.hermescompanion;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;

/**
 * Client for the hub's Jev fast-decision endpoint (POST /jev/decisions): a "System One" model
 * (TypeSafe/jev-latest, served by NanoGPT) that answers typed choice/noul questions about the
 * current screen in well under a second, so {@link PhoneAgent} can take some on-screen steps
 * itself instead of paying a full Hermes turn for every tap.
 *
 * Any failure (network, timeout, non-2xx, unparsable body) returns null; callers always fall
 * back to the normal, slower path (Hermes, or asking in chat), so a broken Jev never blocks a
 * task. Uses the same hub base URL and Bearer key as {@link HubApi}.
 */
final class JevClient {
    private static final int TIMEOUT_MS = 3000;
    private static final String MODEL = "typesafe/jev-latest";

    private final String base, key;

    JevClient(String hubBase, String hubKey) {
        this.base = hubBase == null ? "" : hubBase;
        this.key = hubKey == null ? "" : hubKey.trim();
    }

    /** Wraps {@code state} and {@code questions} per the /jev/decisions protocol. */
    static JSONObject request(Object state, JSONObject questions) {
        try {
            return new JSONObject().put("model", MODEL).put("state", state).put("questions", questions);
        } catch (Exception e) {
            return new JSONObject();
        }
    }

    /** Posts {@code request} (as built by {@link #request}) and returns its "answers" object, or null on any failure. */
    JSONObject decide(JSONObject request, String sessionId) {
        try {
            JSONObject body = new JSONObject(request.toString());
            if (sessionId != null && !sessionId.isEmpty()) body.put("session_id", sessionId);
            HttpURLConnection c = (HttpURLConnection) new URL(UrlUtils.endpoint(base, "/jev/decisions")).openConnection();
            c.setRequestMethod("POST");
            c.setConnectTimeout(TIMEOUT_MS);
            c.setReadTimeout(TIMEOUT_MS);
            c.setDoOutput(true);
            c.setRequestProperty("Content-Type", "application/json; charset=utf-8");
            c.setRequestProperty("Authorization", "Bearer " + key);
            byte[] data = body.toString().getBytes(StandardCharsets.UTF_8);
            try (OutputStream out = c.getOutputStream()) { out.write(data); }
            int code = c.getResponseCode();
            String response = read(c, code);
            c.disconnect();
            if (code < 200 || code >= 300) return null;
            return new JSONObject(response.trim()).optJSONObject("answers");
        } catch (Exception e) {
            return null;
        }
    }

    private static String read(HttpURLConnection c, int code) throws Exception {
        InputStream stream = code >= 400 ? c.getErrorStream() : c.getInputStream();
        if (stream == null) return "";
        StringBuilder b = new StringBuilder();
        try (BufferedReader r = new BufferedReader(new InputStreamReader(stream, StandardCharsets.UTF_8))) {
            String line; while ((line = r.readLine()) != null) b.append(line).append('\n');
        }
        return b.toString();
    }
}
