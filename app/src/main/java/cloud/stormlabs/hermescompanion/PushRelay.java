package cloud.stormlabs.hermescompanion;

import org.json.JSONObject;

import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;

/**
 * Optional push relay for people who share someone else's Firebase project.
 * Contract (server side is separate): GET /health; POST /register {"token": "<fcm token>"} with
 * "Authorization: Bearer <relay key>" -> {"ok": true}; POST /notify (server only, sends the FCM data message).
 */
final class PushRelay {
    private PushRelay() { }

    /** Normalised base URL; a pasted /register, /notify or /health suffix is dropped. */
    static String base(String url) {
        String u = UrlUtils.normalize(url);
        for (String tail : new String[]{"/register", "/notify", "/health"}) if (u.endsWith(tail)) u = u.substring(0, u.length() - tail.length());
        return u;
    }

    static String registerBody(String token) {
        try { return new JSONObject().put("token", token).toString(); } catch (Exception e) { throw new IllegalStateException(e); }
    }

    /** Network thread only. Throws on any failure so the caller retries on the next resume. */
    static void register(String relay, String key, String token) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(base(relay) + "/register").openConnection();
        c.setConnectTimeout(6000); c.setReadTimeout(8000);
        c.setRequestMethod("POST"); c.setDoOutput(true);
        c.setRequestProperty("Authorization", "Bearer " + key);
        c.setRequestProperty("Content-Type", "application/json");
        c.getOutputStream().write(registerBody(token).getBytes(StandardCharsets.UTF_8));
        int code = c.getResponseCode();
        if (code / 100 != 2) throw new java.io.IOException("Relay answered " + code);
    }
}
