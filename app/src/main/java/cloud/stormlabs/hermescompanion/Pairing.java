package cloud.stormlabs.hermescompanion;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;

/**
 * Zero-typing setup: the hub's /pair hands the connection details to devices of the same Tailscale
 * user (it checks with `tailscale whois`), so a fresh install needs no URLs or keys typed in.
 */
final class Pairing {
    private Pairing() { }

    /**
     * The main profile's API key from the hub's /pair (answers only this owner's devices over Tailscale),
     * tried on the pairing hub and every backup host. Network thread only; throws with a short reason.
     */
    static String mainKey(SettingsStore store) throws Exception {
        String key = pairJson(store).optString("key", "");
        if (key.isEmpty()) throw new IllegalStateException("The hub sent no key");
        return key;
    }

    /**
     * Adds a "Codex" profile from the hub's /pair (set up on the server by codex-rc) and switches to it.
     * Network thread only; returns null on success or a short reason.
     */
    static String addCodex(SettingsStore store) {
        try {
            JSONObject paired = pairJson(store);
            JSONObject codex = paired.optJSONObject("codex");
            if (codex == null || codex.optString("key").isEmpty()) return "The hub has no Codex remote; run codex-rc on the server";
            store.addProfile("Codex", "Codex", codex.optString("endpoint"), codex.optString("key"));
            store.hubUrl(paired.optString("_paired_hub"));
            store.hubKey(paired.optString("key"));
            return null;
        } catch (Exception e) { return e.getMessage(); }
    }

    /** /pair from the first hub that answers: the pairing hub, this profile's hub, then backup hosts. */
    private static JSONObject pairJson(SettingsStore store) throws Exception {
        java.util.LinkedHashSet<String> hubs = new java.util.LinkedHashSet<>();
        if (!store.pairHub().isEmpty()) hubs.add(store.pairHub());
        if (!store.hubUrl().isEmpty()) hubs.add(store.hubUrl());
        for (String b : store.backupHosts().split("[,\\s]+")) if (!b.isEmpty() && !b.contains("://")) hubs.add("http://" + b + ":" + Reach.HUB_PORT);
        String last = "Add your Tailscale address under Settings › Backup addresses";
        for (String hub : hubs) {
            try {
                HttpURLConnection c = (HttpURLConnection) new URL(hub + "/pair").openConnection();
                c.setConnectTimeout(5000); c.setReadTimeout(8000);
                int code = c.getResponseCode();
                if (code != 200) { last = code == 403 ? "Turn Tailscale on: only your own devices can pair" : "Hub answered " + code; continue; }
                ByteArrayOutputStream out = new ByteArrayOutputStream();
                try (InputStream in = c.getInputStream()) { byte[] b = new byte[4096]; int n; while ((n = in.read(b)) > 0) out.write(b, 0, n); }
                return new JSONObject(out.toString("UTF-8")).put("_paired_hub", hub);
            } catch (Exception e) { last = "Can't reach the hub over Tailscale"; }
        }
        throw new IllegalStateException(last);
    }

    /** Fetches and saves the connection; returns null on success or a short reason. Network thread only. */
    static String pair(SettingsStore store) {
        String hub = store.pairHub();
        if (hub.isEmpty()) return "Enter your pairing hub URL first";
        try {
            HttpURLConnection c = (HttpURLConnection) new URL(hub + "/pair").openConnection();
            c.setConnectTimeout(6000); c.setReadTimeout(8000);
            int code = c.getResponseCode();
            if (code == 403) return "Only works from your own devices over Tailscale";
            if (code != 200) return "Hub answered " + code;
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            try (InputStream in = c.getInputStream()) { byte[] b = new byte[4096]; int n; while ((n = in.read(b)) > 0) out.write(b, 0, n); }
            JSONObject o = new JSONObject(out.toString("UTF-8"));
            String hubKey = o.optString("key");
            if ("Codex".equals(store.serverType())) {
                o = o.optJSONObject("codex");
                if (o == null) return "The hub has no Codex remote; run codex-rc on the server";
            } else if ("Claude Code".equals(store.serverType())) {
                o = o.optJSONObject("claude");
                if (o == null) return "The hub has no Claude Code bridge; install the claude-bridge module on the server";
            }
            String endpoint = o.optString("endpoint", ""), key = o.optString("key", "");
            if (endpoint.isEmpty() || key.isEmpty()) return "The hub sent no connection";
            store.endpoint(endpoint);
            store.apiKey(key);
            store.hubUrl(hub);
            store.hubKey(hubKey);
            if (!o.optString("essentials_url", "").isEmpty()) {
                store.essentialsBase(o.optString("essentials_url"));
                store.essentialsToken(o.optString("essentials_token", ""));
            }
            return null;
        } catch (java.net.SocketTimeoutException | java.net.ConnectException e) {
            return "Can't reach the hub — is Tailscale on?";
        } catch (Exception e) {
            return HermesApi.friendly(e);
        }
    }
}
