package cloud.stormlabs.hermescompanion;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
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
 * Client for the hermes-hub companion service: push registration, the event feed, job outputs,
 * file uploads, cross-session search and host/service ops. Mirrors {@link HermesApi}'s plumbing
 * (open/read/errorText/friendly) so both clients fail the same readable way.
 */
public final class HubApi {
    public static final class Event {
        public final long id;
        public final double ts;
        public final String type, title, body, priority, sessionId, jobId, url;
        Event(JSONObject o) {
            id = o.optLong("id", 0);
            ts = o.optDouble("ts", 0);
            type = HermesApi.clean(o.optString("type", ""));
            title = HermesApi.clean(o.optString("title", ""));
            body = HermesApi.clean(o.optString("body", ""));
            priority = HermesApi.clean(o.optString("priority", ""));
            sessionId = HermesApi.clean(o.optString("session_id", ""));
            jobId = HermesApi.clean(o.optString("job_id", ""));
            url = HermesApi.clean(o.optString("url", ""));
        }
    }

    public static final class JobOutput {
        public final double ts;
        public final String name, text;
        JobOutput(JSONObject o) {
            ts = o.optDouble("ts", 0);
            name = HermesApi.clean(o.optString("name", ""));
            text = HermesApi.clean(o.optString("text", ""));
        }
    }

    public static final class SearchResult {
        public final String sessionId, title, messageId, role, snippet;
        public final double ts;
        SearchResult(JSONObject o) {
            sessionId = HermesApi.clean(o.optString("session_id", ""));
            title = HermesApi.clean(o.optString("title", ""));
            messageId = HermesApi.clean(o.optString("message_id", ""));
            role = HermesApi.clean(o.optString("role", ""));
            snippet = HermesApi.clean(o.optString("snippet", ""));
            ts = o.optDouble("ts", 0);
        }
    }

    public static final class Service {
        public final String name, kind, state, sub, since, description;
        Service(JSONObject o) {
            name = HermesApi.clean(o.optString("name", ""));
            kind = HermesApi.clean(o.optString("kind", ""));
            state = HermesApi.clean(o.optString("state", ""));
            sub = HermesApi.clean(o.optString("sub", ""));
            since = HermesApi.clean(o.optString("since", ""));
            description = HermesApi.clean(o.optString("description", ""));
        }
    }

    public static final class Host {
        public final double cpuPct, load1, diskUsedGb, diskTotalGb;
        public final long memUsedMb, memTotalMb, uptimeS;
        public final int cores;
        public final String hostname, kind, osPretty, pveVersion;
        Host(JSONObject o) {
            kind = o.optString("kind", "");
            JSONObject os = o.optJSONObject("os");
            osPretty = os == null ? "" : HermesApi.clean(os.optString("pretty", os.optString("name", "")));
            pveVersion = o.optString("pveversion", "");
            cpuPct = o.optDouble("cpu_pct", 0);
            load1 = o.optDouble("load1", 0);
            memUsedMb = o.optLong("mem_used_mb", 0);
            memTotalMb = o.optLong("mem_total_mb", 0);
            diskUsedGb = o.optDouble("disk_used_gb", 0);
            diskTotalGb = o.optDouble("disk_total_gb", 0);
            uptimeS = o.optLong("uptime_s", 0);
            cores = o.optInt("cpu_cores", o.optInt("cores", 0));
            hostname = o.optString("hostname", o.optString("name", "host"));
        }
    }

    public static final class Services {
        public final List<Service> services;
        public final Host host;
        Services(List<Service> services, Host host) { this.services = services; this.host = host; }
    }

    /** A Proxmox node or guest row; type is "node", "qemu" or "lxc". Missing numbers are NaN. */
    public static final class Pve {
        public final String type, node, name, status, ostype;
        public final long vmid;
        /** Offline node with a Wake-on-LAN MAC in Proxmox: the hub can wake it. */
        public final boolean wol;
        public final double cpu, maxcpu, mem, maxmem, disk, maxdisk, uptime;
        Pve(JSONObject o, String type) {
            this.type = type;
            node = o.optString("node", ""); status = o.optString("status", "unknown"); ostype = o.optString("ostype", "");
            vmid = o.optLong("vmid", 0);
            String n = HermesApi.clean(o.optString("name", ""));
            name = type.equals("node") ? node : n.isEmpty() ? ("lxc".equals(type) ? "CT " : "VM ") + vmid : n;
            cpu = o.optDouble("cpu", Double.NaN); maxcpu = o.optDouble("maxcpu", Double.NaN);
            mem = o.optDouble("mem", Double.NaN); maxmem = o.optDouble("maxmem", Double.NaN);
            disk = o.optDouble("disk", Double.NaN); maxdisk = o.optDouble("maxdisk", Double.NaN);
            uptime = o.optDouble("uptime", Double.NaN);
            wol = o.optBoolean("wol", false);
        }
        public boolean up() { return "online".equals(status) || "running".equals(status); }
    }

    /** Live stats of the Proxmox host itself (hub probe over ssh, independent of pvestatd). Byte counts; NaN when absent. */
    public static final class PveHost {
        public final String node, model, pveVersion, kernel;
        public final int cores;
        public final double cpuPct, load1, memTotal, memAvail, swapTotal, swapFree, rootTotal, rootUsed, uptime;
        public final List<String[]> temps = new ArrayList<>(); // {label, celsius}
        public final List<Storage> storage = new ArrayList<>();
        public final int running, stopped;
        public static final class Storage {
            public final String name, type, status; public final double total, used;
            Storage(JSONObject o) { name = o.optString("name"); type = o.optString("type"); status = o.optString("status"); total = o.optDouble("total", 0); used = o.optDouble("used", 0); }
            public boolean reachable() { return !"unreachable".equals(status); }
        }
        PveHost(JSONObject o) {
            node = o.optString("node"); model = o.optString("model"); pveVersion = o.optString("pveversion"); kernel = o.optString("kernel");
            cores = o.optInt("cores", 0);
            cpuPct = o.optDouble("cpu_pct", Double.NaN);
            JSONArray load = o.optJSONArray("load"); load1 = load == null ? Double.NaN : load.optDouble(0, Double.NaN);
            memTotal = o.optDouble("mem_total", 0); memAvail = o.optDouble("mem_avail", 0);
            swapTotal = o.optDouble("swap_total", 0); swapFree = o.optDouble("swap_free", 0);
            rootTotal = o.optDouble("root_total", 0); rootUsed = o.optDouble("root_used", 0); uptime = o.optDouble("uptime", 0);
            JSONArray t = o.optJSONArray("temps");
            if (t != null) for (int i = 0; i < t.length(); i++) { JSONObject x = t.optJSONObject(i); if (x != null) temps.add(new String[]{x.optString("label"), String.valueOf(x.optDouble("c"))}); }
            JSONArray s = o.optJSONArray("storage");
            if (s != null) for (int i = 0; i < s.length(); i++) { JSONObject x = s.optJSONObject(i); if (x != null && !x.optString("type").startsWith("nfsd")) storage.add(new Storage(x)); }
            JSONObject g = o.optJSONObject("guests"); int r = 0, st = 0;
            if (g != null) for (java.util.Iterator<String> it = g.keys(); it.hasNext(); ) { if ("running".equals(g.optString(it.next()))) r++; else st++; }
            running = r; stopped = st;
        }
        /** Hottest CPU package/core reading, NaN when unknown. */
        public double cpuTemp() {
            double max = Double.NaN;
            for (String[] t : temps) if (t[0].startsWith("Package") || t[0].startsWith("Core") || t[0].startsWith("Tctl") || t[0].startsWith("Tdie")) {
                double c = Double.parseDouble(t[1]); if (Double.isNaN(max) || c > max) max = c; }
            return max;
        }
        public double tempOf(String label) { for (String[] t : temps) if (t[0].equals(label)) return Double.parseDouble(t[1]); return Double.NaN; }
    }

    public static final class Infra {
        public final Host host;
        public final PveHost pveHost;
        public final boolean reachable;
        public final String error;
        public final List<Pve> nodes = new ArrayList<>(), guests = new ArrayList<>();
        Infra(JSONObject o) {
            JSONObject h = o.optJSONObject("host"), p = o.optJSONObject("pve");
            host = new Host(h == null ? new JSONObject() : h);
            reachable = p != null && p.optBoolean("reachable", false);
            error = p == null ? "" : HermesApi.clean(p.optString("error", ""));
            JSONObject ph = p == null ? null : p.optJSONObject("host");
            pveHost = ph == null ? null : new PveHost(ph);
            if (p == null) return;
            JSONArray n = p.optJSONArray("nodes"), g = p.optJSONArray("guests");
            if (n != null) for (int i = 0; i < n.length(); i++) { JSONObject x = n.optJSONObject(i); if (x != null) nodes.add(new Pve(x, "node")); }
            if (g != null) for (int i = 0; i < g.length(); i++) { JSONObject x = g.optJSONObject(i); if (x != null) guests.add(new Pve(x, x.optString("type", "lxc"))); }
        }
    }

    /** Detail sheet content: label/value rows. */
    public static final class Detail {
        public final List<String[]> rows = new ArrayList<>();
        void add(String k, String v) { if (v != null && !v.isEmpty() && !v.equals("null")) rows.add(new String[]{k, v}); }
        static String gb(double b) { return Double.isNaN(b) ? "" : String.format(java.util.Locale.US, "%.1f GB", b / 1073741824.0); }
        static String pair(double used, double total) { return Double.isNaN(used) || Double.isNaN(total) ? "" : gb(used) + " / " + gb(total); }
    }

    private final String base, key;
    private int connectMs = 3_000, readMs = 15_000;
    interface Connections { HttpURLConnection open(URL url) throws java.io.IOException; }
    private final Connections connections;

    /** Short timeouts for widgets, tiles and receivers, which only get ~10 s before the system gives up on them. */
    public HubApi quick() { connectMs = 3_000; readMs = 6_000; return this; }

    public HubApi(String base, String key) {
        this(base, key, url -> (HttpURLConnection) url.openConnection());
    }

    HubApi(String base, String key, Connections connections) {
        this.connections = connections;
        this.base = UrlUtils.normalize(base);
        this.key = key == null ? "" : key.trim();
    }

    public HermesApi.Result health() {
        try {
            JSONObject o = requestJson("GET", "/health", null);
            return HermesApi.Result.ok(o.optBoolean("ok", false) ? "Hub online · v" + o.optString("version", "?") : "Hub replied without ok");
        } catch (Exception e) { return HermesApi.Result.error(HermesApi.friendly(e)); }
    }

    public void registerPush(String endpoint) throws Exception {
        requestJson("POST", "/push/register", new JSONObject().put("endpoint", endpoint));
    }

    /** Returns the new event's id. */
    public long notify(String title, String body, String type, String priority, String sessionId, String jobId, String url) throws Exception {
        JSONObject req = new JSONObject().put("title", title == null ? "" : title).put("body", body == null ? "" : body)
                .put("type", type == null || type.isEmpty() ? "info" : type);
        if (priority != null && !priority.isEmpty()) req.put("priority", priority);
        if (sessionId != null && !sessionId.isEmpty()) req.put("session_id", sessionId);
        if (jobId != null && !jobId.isEmpty()) req.put("job_id", jobId);
        if (url != null && !url.isEmpty()) req.put("url", url);
        return requestJson("POST", "/notify", req).optLong("id", 0);
    }

    /** Hermes profiles this server serves at /p/&lt;name&gt;, each with its own key (main key only). */
    /** {url, token} for one-tap Essentials setup (hub /essentials/config, main key only). */
    public String[] essentialsConfig() throws Exception {
        JSONObject o = requestJson("GET", "/essentials/config", null);
        return new String[]{o.optString("url", ""), o.optString("token", "")};
    }

    public List<String[]> serverProfiles() throws Exception {
        JSONArray arr = requestJson("GET", "/profiles", null).optJSONArray("profiles");
        List<String[]> out = new ArrayList<>();
        if (arr != null) for (int i = 0; i < arr.length(); i++) {
            JSONObject o = arr.optJSONObject(i);
            if (o != null && !o.optString("name").isEmpty() && !o.optString("key").isEmpty()) out.add(new String[]{o.optString("name"), o.optString("key")});
        }
        return out;
    }

    public List<Event> events(long since, int limit) throws Exception { return events("/events?since=" + since + "&limit=" + limit); }

    /** The newest {@code limit} events, oldest first (hubs before 7.4 ignore latest and return the oldest). */
    public List<Event> latestEvents(int limit) throws Exception { return events("/events?since=0&latest=1&limit=" + limit); }

    private List<Event> events(String endpoint) throws Exception {
        JSONObject root = requestJson("GET", endpoint, null);
        JSONArray arr = root.optJSONArray("events");
        List<Event> out = new ArrayList<>();
        if (arr != null) for (int i = 0; i < arr.length(); i++) { JSONObject o = arr.optJSONObject(i); if (o != null) out.add(new Event(o)); }
        return out;
    }

    public List<JobOutput> jobOutputs(String jobId, int limit) throws Exception {
        JSONObject root = requestJson("GET", "/jobs/" + path(jobId) + "/outputs?limit=" + limit, null);
        JSONArray arr = root.optJSONArray("outputs");
        List<JobOutput> out = new ArrayList<>();
        if (arr != null) for (int i = 0; i < arr.length(); i++) { JSONObject o = arr.optJSONObject(i); if (o != null) out.add(new JobOutput(o)); }
        return out;
    }

    /** Raw-body upload; returns the server-side path. */
    public String uploadFile(String filename, String contentType, byte[] data) throws Exception {
        HttpURLConnection c = open("POST", "/files");
        c.setRequestProperty("Content-Type", contentType == null || contentType.isEmpty() ? "application/octet-stream" : contentType);
        c.setRequestProperty("X-Filename", filename == null || filename.isEmpty() ? "file" : filename);
        c.setDoOutput(true);
        try (OutputStream out = c.getOutputStream()) { out.write(data); }
        int code = c.getResponseCode();
        String body = read(c, code);
        if (code < 200 || code >= 300) throw new IllegalStateException(HermesApi.errorText(code, body));
        return new JSONObject(body).optString("path", "");
    }

    /** One-shot speech-to-text over the hub's https route, for when the Wyoming voice port is out of reach (off the LAN without Tailscale). */
    public String transcribe(byte[] pcm, String language) throws Exception {
        return postAudio("/voice/stt?language=" + path(language == null ? "en" : language), pcm, 60_000).optString("text", "");
    }

    /** A live take kept open on the hub (same as the voice port, over https): start, send audio, stop. */
    public String liveStart(String language) throws Exception {
        return postAudio("/voice/live?language=" + path(language == null ? "en" : language), new byte[0], 8_000).getString("id");
    }
    /** Sends audio; the reply has the newest {@code partial} text and whether the server heard an {@code endpoint}. */
    // Short timeouts: a network switch mid-take (Wi-Fi, Tailscale coming up) leaves a dead connection; failing fast lets
    // the take fall back to a fresh one-shot request instead of hanging.
    public JSONObject liveAudio(String id, byte[] pcm) throws Exception { return postAudio("/voice/live/" + path(id) + "/audio", pcm, 8_000); }
    public String liveStop(String id, byte[] pcm) throws Exception { return postAudio("/voice/live/" + path(id) + "/stop", pcm, 20_000).optString("text", ""); }

    private JSONObject postAudio(String endpoint, byte[] pcm, int timeoutMs) throws Exception {
        readMs = timeoutMs;
        HttpURLConnection c = open("POST", endpoint);
        c.setRequestProperty("Content-Type", "application/octet-stream");
        c.setFixedLengthStreamingMode(pcm.length);
        try (OutputStream out = c.getOutputStream()) { out.write(pcm); }
        int code = c.getResponseCode();
        String body = read(c, code);
        if (code < 200 || code >= 300) throw new java.io.IOException(HermesApi.errorText(code, body));
        JSONObject o = new JSONObject(body);
        if (!o.optBoolean("ok", true)) throw new java.io.IOException(o.optString("error", "speech server error"));
        return o;
    }

    public List<SearchResult> search(String q, int limit) throws Exception {
        JSONObject root = requestJson("GET", "/search?q=" + URLEncoder.encode(q == null ? "" : q, StandardCharsets.UTF_8.name()) + "&limit=" + limit, null);
        JSONArray arr = root.optJSONArray("results");
        List<SearchResult> out = new ArrayList<>();
        if (arr != null) for (int i = 0; i < arr.length(); i++) { JSONObject o = arr.optJSONObject(i); if (o != null) out.add(new SearchResult(o)); }
        return out;
    }

    public Services opsServices() throws Exception { return parseServices(opsServicesRaw()); }
    public JSONObject opsServicesRaw() throws Exception { return requestJson("GET", "/ops/services", null); }
    /** Hermes' approval mode (manual / smart / off) as kept in its config; the hub rewrites that one line. */
    public JSONObject approvals() throws Exception { return requestJson("GET", "/ops/approvals", null); }
    public JSONObject setApprovals(String mode) throws Exception { return requestJson("POST", "/ops/approvals", new JSONObject().put("mode", mode)); }
    /** Ask the hub to wake the voice server if it is asleep (a no-op when it is already up). */
    public JSONObject wake() throws Exception { return requestJson("POST", "/ops/wake", new JSONObject()); }
    public JSONObject opsInfraRaw() throws Exception { return requestJson("GET", "/ops/infra", null); }

    static Services parseServices(JSONObject root) {
        JSONArray arr = root.optJSONArray("services");
        List<Service> services = new ArrayList<>();
        if (arr != null) for (int i = 0; i < arr.length(); i++) { JSONObject o = arr.optJSONObject(i); if (o != null) services.add(new Service(o)); }
        JSONObject host = root.optJSONObject("host");
        return new Services(services, new Host(host == null ? new JSONObject() : host));
    }

    /** Small status roll-up; throws "HTTP 404" on a hub without /ops/summary. */
    public JSONObject summary() throws Exception { return requestJson("GET", "/ops/summary", null); }

    public void phoneHello(JSONObject body) throws Exception { requestJson("POST", "/phone/hello", body); }
    public void phoneResult(JSONObject body) throws Exception { requestJson("POST", "/phone/result", body); }

    /** The hub's list of other addresses for this server (Tailscale IP, public URL). */
    public List<String> backupAddresses() throws Exception {
        JSONArray arr = requestJson("GET", "/app/backup-addresses", null).optJSONArray("addresses");
        List<String> out = new ArrayList<>();
        if (arr != null) for (int i = 0; i < arr.length(); i++) if (!arr.optString(i).trim().isEmpty()) out.add(arr.optString(i).trim());
        return out;
    }

    // ── Hermes gateway bridge: slash commands, rewind, checkpoints (hub ↔ loopback `hermes serve`) ──

    /** The gateway profile behind a Hermes endpoint ("http://host:8889/p/nsfw" -> "nsfw"; "" for the main one). */
    static String gatewayProfile(String hermesEndpoint) {
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("/p/([A-Za-z0-9_.-]+)/?$").matcher(UrlUtils.normalize(hermesEndpoint));
        return m.find() ? m.group(1) : "";
    }

    /** Every slash command the server knows (built-ins, skills, plugins, quick commands): {name, description}. */
    public List<String[]> hermesCommands() throws Exception {
        JSONArray arr = requestJson("GET", "/hermes/commands", null).optJSONArray("commands");
        List<String[]> out = new ArrayList<>();
        if (arr != null) for (int i = 0; i < arr.length(); i++) {
            JSONObject o = arr.optJSONObject(i);
            if (o != null) out.add(new String[]{o.optString("name").replaceFirst("^/", ""), o.optString("description")});
        }
        return out;
    }

    /** Runs a slash command in a stored chat: {type: exec|send|skill, output, message, notice, warning}. */
    public JSONObject hermesSlash(String profile, String sessionId, String command) throws Exception {
        readMs = 700_000; // /compress may legitimately take ten minutes
        return requestJson("POST", "/hermes/slash", new JSONObject().put("session_id", sessionId).put("command", command).put("profile", profile));
    }

    /** Drops user turn {@code rowId} and everything after it (rowId &lt;= 0: the last {@code turns} user turns). */
    public JSONObject hermesRewind(String profile, String sessionId, long rowId, int turns) throws Exception {
        readMs = 180_000;
        JSONObject body = new JSONObject().put("session_id", sessionId).put("profile", profile);
        if (rowId > 0) body.put("row_id", rowId); else body.put("turns", Math.max(1, turns));
        return requestJson("POST", "/hermes/rewind", body);
    }

    /** Filesystem checkpoints for the chat's working directory: {enabled, checkpoints: [{hash, timestamp, message}]}. */
    public JSONObject hermesCheckpoints(String profile, String sessionId) throws Exception {
        return requestJson("GET", "/hermes/rollback?session_id=" + URLEncoder.encode(sessionId, StandardCharsets.UTF_8.name())
                + "&profile=" + URLEncoder.encode(profile, StandardCharsets.UTF_8.name()), null);
    }

    public JSONObject hermesRestoreCheckpoint(String profile, String sessionId, String hash) throws Exception {
        readMs = 320_000;
        return requestJson("POST", "/hermes/rollback", new JSONObject().put("session_id", sessionId).put("hash", hash).put("profile", profile));
    }

    /** /ops/network: hub-side tailnet and reachability. ms < 0 = unreachable. */
    public static final class Network {
        public final String self, selfIps, exitNode, publicIp;
        public final boolean tailnet;
        public final List<Peer> peers = new ArrayList<>();
        public final List<Check> checks = new ArrayList<>();
        public final List<Cert> certs = new ArrayList<>();
        Network(JSONObject o) {
            JSONObject me = o.optJSONObject("self");
            tailnet = me != null;
            self = me == null ? "" : me.optString("name");
            exitNode = me == null ? "" : me.optString("exit_node");
            StringBuilder ips = new StringBuilder();
            JSONArray ia = me == null ? null : me.optJSONArray("ips");
            if (ia != null && ia.length() > 0) ips.append(ia.optString(0));
            selfIps = ips.toString();
            publicIp = o.optString("public_ip");
            JSONArray p = o.optJSONArray("peers"), c = o.optJSONArray("checks");
            if (p != null) for (int i = 0; i < p.length(); i++) { JSONObject x = p.optJSONObject(i); if (x != null) peers.add(new Peer(x.optString("name"), x.optString("os"), x.optBoolean("online"), x.optString("last_seen"))); }
            if (c != null) for (int i = 0; i < c.length(); i++) { JSONObject x = c.optJSONObject(i); if (x != null) checks.add(new Check(x.optString("name"), x.optString("target"), x.isNull("ms") ? -1 : x.optInt("ms", -1))); }
            JSONArray ct = o.optJSONArray("certs");
            if (ct != null) for (int i = 0; i < ct.length(); i++) { JSONObject x = ct.optJSONObject(i); if (x != null) certs.add(new Cert(x.optString("host"), x.isNull("days") ? Integer.MIN_VALUE : x.optInt("days", Integer.MIN_VALUE), x.optString("issuer"), x.optString("error"))); }
            SystemHealth.sortPeers(peers);
        }
        public int online() { int n = 0; for (Peer p : peers) if (p.online) n++; return n; }
    }

    public static final class Peer {
        public final String name, os, lastSeen; public final boolean online;
        Peer(String name, String os, boolean online, String lastSeen) { this.name = name; this.os = os; this.online = online; this.lastSeen = lastSeen; }
    }

    public static final class Check {
        public final String name, target; public final int ms;
        Check(String name, String target, int ms) { this.name = name; this.target = target; this.ms = ms; }
    }

    /** A watched TLS certificate: days left (MIN_VALUE when it couldn't be read) or why not. */
    public static final class Cert {
        public final String host, issuer, error; public final int days;
        Cert(String host, int days, String issuer, String error) { this.host = host; this.days = days; this.issuer = issuer; this.error = error; }
    }

    /** /ops/backups: last vzdump per guest (ageDays < 0 = never) plus the scheduled jobs. */
    public static final class Backups {
        public final List<BackupGuest> guests = new ArrayList<>();
        public final List<String> jobs = new ArrayList<>();
        /** Enabled jobs whose target storage is disabled or gone: they fail at their next run. */
        public final List<String> brokenJobs = new ArrayList<>();
        Backups(JSONObject o) {
            JSONArray g = o.optJSONArray("guests"), j = o.optJSONArray("jobs");
            if (g != null) for (int i = 0; i < g.length(); i++) { JSONObject x = g.optJSONObject(i); if (x != null) guests.add(new BackupGuest(x.optLong("vmid"), x.optString("name"), x.optString("last_status"), x.isNull("age_days") ? -1 : x.optDouble("age_days", -1))); }
            if (j != null) for (int i = 0; i < j.length(); i++) {
                JSONObject x = j.optJSONObject(i);
                if (x != null) jobs.add(x.optString("schedule") + " → " + x.optString("storage") + " · " + (x.optBoolean("all") ? "all guests" : x.optString("vmids")) + (x.optBoolean("enabled", true) ? "" : " (disabled)"));
                if (x != null && x.optBoolean("enabled", true) && !x.optBoolean("storage_ok", true))
                    brokenJobs.add(x.optString("id", "Backup job") + " writes to " + x.optString("storage") + ", which is disabled or missing: its next run (" + x.optString("schedule") + ") will fail");
            }
        }
    }

    public static final class BackupGuest {
        public final long vmid; public final String name, status; public final double ageDays;
        BackupGuest(long vmid, String name, String status, double ageDays) { this.vmid = vmid; this.name = name.isEmpty() ? "Guest " + vmid : name; this.status = status; this.ageDays = ageDays; }
    }

    /** /ops/updates: pending apt upgrades per host (read-only) and the running Hermes version. */
    public static final class Updates {
        public final List<UpdHost> hosts = new ArrayList<>();
        public final String hermes;
        Updates(JSONObject o) {
            JSONArray h = o.optJSONArray("hosts");
            if (h != null) for (int i = 0; i < h.length(); i++) { JSONObject x = h.optJSONObject(i); if (x != null) hosts.add(new UpdHost(x)); }
            JSONObject hm = o.optJSONObject("hermes");
            String cur = hm == null ? "" : hm.optString("current"), latest = hm == null || hm.isNull("latest") ? "" : hm.optString("latest");
            hermes = cur.isEmpty() ? "" : latest.isEmpty() || latest.equals(cur) ? cur : cur + " → " + latest + " available";
        }
    }

    public static final class UpdHost {
        public final String name; public final int packages, security; public final boolean reboot; public final List<String> sample = new ArrayList<>();
        UpdHost(JSONObject x) {
            name = x.optString("name"); packages = x.optInt("packages"); security = x.optInt("security"); reboot = x.optBoolean("reboot_required");
            JSONArray s = x.optJSONArray("sample");
            if (s != null) for (int i = 0; i < s.length(); i++) sample.add(s.optString(i));
        }
        /** "openclaw · 12 updates · 3 security · reboot needed" */
        public String line() {
            return name + " · " + (packages == 0 ? "up to date" : packages + (packages == 1 ? " update" : " updates")) + (security > 0 ? " · " + security + " security" : "") + (reboot ? " · reboot needed" : "");
        }
    }

    /** Throws "HTTP 404" on a hub without /ops/updates. */
    public Updates opsUpdates() throws Exception { return new Updates(requestJson("GET", "/ops/updates", null)); }

    /** Throws "HTTP 404" on a hub without /ops/network. */
    public Network opsNetwork() throws Exception { return new Network(requestJson("GET", "/ops/network", null)); }

    /** Throws "HTTP 404" on a hub without /ops/backups. */
    public Backups opsBackups() throws Exception { return new Backups(requestJson("GET", "/ops/backups", null)); }

    /** Throws with a "HTTP 404" message on a hub without /ops/infra. */
    public Infra opsInfra() throws Exception { return new Infra(requestJson("GET", "/ops/infra", null)); }

    /** [[epochSeconds, value]] for a sampled key, range "24h" | "7d"; throws "HTTP 404" on a hub without history. */
    public double[][] history(String key, String range) throws Exception {
        JSONArray a = requestJson("GET", "/ops/history?key=" + path(key) + "&range=" + range, null).optJSONArray("points");
        double[][] out = new double[a == null ? 0 : a.length()][];
        for (int i = 0; i < out.length; i++) { JSONArray p = a.getJSONArray(i); out[i] = new double[]{p.getDouble(0), p.getDouble(1)}; }
        return out;
    }

    /** start | shutdown | stop | reboot. The hub refuses its own guest and anything outside pve-actions.txt. */
    /** Proxmox Wake-on-LAN for an offline node; the hub explains when it isn't set up. */
    public void wakeNode(String node) throws Exception {
        requestJson("POST", "/ops/infra/node/wake", new JSONObject().put("node", node));
    }

    public void guestAction(Pve g, String action) throws Exception {
        requestJson("POST", "/ops/infra/guest/action", new JSONObject().put("node", g.node).put("type", g.type).put("vmid", g.vmid).put("action", action));
    }

    /** Snapshot names with their time (epoch seconds, 0 when unknown), newest first. */
    public List<String[]> snapshots(Pve g) throws Exception {
        JSONArray a = requestJson("GET", "/ops/infra/guest/snapshots?node=" + path(g.node) + "&type=" + path(g.type) + "&vmid=" + g.vmid, null).optJSONArray("snapshots");
        List<String[]> out = new ArrayList<>();
        if (a != null) for (int i = 0; i < a.length(); i++) { JSONObject s = a.optJSONObject(i); if (s != null) out.add(new String[]{s.optString("name"), String.valueOf(s.optLong("snaptime", 0)), s.optString("description")}); }
        java.util.Collections.sort(out, (x, y) -> Long.compare(Long.parseLong(y[1]), Long.parseLong(x[1])));
        return out;
    }

    /** create | rollback | delete. */
    public void snapshot(Pve g, String op, String name) throws Exception {
        requestJson("POST", "/ops/infra/guest/snapshot", new JSONObject().put("node", g.node).put("type", g.type).put("vmid", g.vmid).put("op", op).put("name", name));
    }

    /** Proxmox snapshot names: a letter, then 1-39 letters, digits, - or _. */
    static boolean snapshotName(String s) { return s != null && s.matches("[A-Za-z][A-Za-z0-9_-]{1,39}"); }

    /** /ops/security: CrowdSec on the reverse proxy. */
    public static final class Security {
        public final long blockedIps, droppedPackets; public final boolean blind; public final int alerts24h;
        public final List<String[]> lists = new ArrayList<>(), sources = new ArrayList<>(), staleLogs = new ArrayList<>(); public final List<String> scenarios = new ArrayList<>();
        Security(JSONObject o) {
            blockedIps = o.optLong("blocked_ips"); droppedPackets = o.optLong("dropped_packets"); blind = o.optBoolean("blind"); alerts24h = o.optInt("alerts_24h");
            JSONArray l = o.optJSONArray("lists"), src = o.optJSONArray("sources"), sc = o.optJSONArray("scenarios");
            if (l != null) for (int i = 0; i < l.length(); i++) { JSONObject x = l.optJSONObject(i); if (x != null) lists.add(new String[]{x.optString("name"), String.valueOf(x.optLong("ips")), String.valueOf(x.optLong("dropped"))}); }
            if (src != null) for (int i = 0; i < src.length(); i++) { JSONObject x = src.optJSONObject(i); if (x != null) sources.add(new String[]{x.optString("name"), String.valueOf(x.optLong("read")), String.valueOf(x.optLong("parsed"))}); }
            if (sc != null) for (int i = 0; i < sc.length(); i++) scenarios.add(sc.optString(i));
            JSONArray st = o.optJSONArray("stale_logs");
            if (st != null) for (int i = 0; i < st.length(); i++) { JSONObject x = st.optJSONObject(i); if (x != null) staleLogs.add(new String[]{x.optString("path"), x.isNull("age_days") ? "" : String.valueOf(x.optInt("age_days"))}); }
        }
    }

    /** /ops/sync: Syncthing devices {name, connected, lastSeen} and folders {label, state, need, errors, bytes, sharedWith}. */
    public static final class Sync {
        public final List<String[]> devices = new ArrayList<>(); public final List<JSONObject> folders = new ArrayList<>();
        Sync(JSONObject o) {
            JSONArray d = o.optJSONArray("devices"), f = o.optJSONArray("folders");
            if (d != null) for (int i = 0; i < d.length(); i++) { JSONObject x = d.optJSONObject(i); if (x != null) devices.add(new String[]{x.optString("name"), String.valueOf(x.optBoolean("connected")), x.optString("last_seen"), String.valueOf(x.optBoolean("paused"))}); }
            if (f != null) for (int i = 0; i < f.length(); i++) { JSONObject x = f.optJSONObject(i); if (x != null) folders.add(x); }
        }
    }

    public Sync sync() throws Exception { return new Sync(requestJson("GET", "/ops/sync", null)); }

    public Security security() throws Exception { return new Security(requestJson("GET", "/ops/security", null)); }

    public static final class Task {
        public final String upid, node, type, id, user, status; public final long start, end;
        Task(JSONObject o) {
            upid = o.optString("upid"); node = o.optString("node"); type = o.optString("type"); id = o.optString("id");
            user = o.optString("user"); status = o.optString("status", "running"); start = o.optLong("start"); end = o.optLong("end");
        }
        public boolean ok() { return "OK".equals(status) || "running".equals(status); }
    }

    /** The cluster's recent Proxmox tasks, newest first. */
    public List<Task> tasks() throws Exception {
        JSONArray a = requestJson("GET", "/ops/infra/tasks", null).optJSONArray("tasks");
        List<Task> out = new ArrayList<>();
        if (a != null) for (int i = 0; i < a.length(); i++) { JSONObject o = a.optJSONObject(i); if (o != null) out.add(new Task(o)); }
        return out;
    }

    public List<String> taskLog(Task t) throws Exception {
        JSONArray a = requestJson("GET", "/ops/infra/task/log?node=" + path(t.node) + "&upid=" + path(t.upid), null).optJSONArray("lines");
        List<String> out = new ArrayList<>();
        if (a != null) for (int i = 0; i < a.length(); i++) out.add(a.optString(i));
        return out;
    }

    /** 24 h from Proxmox: {cpu %, memory %, network KB/s in+out}, each [[epochSeconds, value]]; empty arrays when none. */
    public double[][][] rrd(String node, String type, long vmid) throws Exception {
        JSONArray a = requestJson("GET", "/ops/infra/rrd?node=" + path(node) + "&type=" + path(type) + "&vmid=" + vmid, null).optJSONArray("points");
        return rrdSeries(a);
    }

    static double[][][] rrdSeries(JSONArray a) {
        int n = a == null ? 0 : a.length();
        List<double[]> cpu = new ArrayList<>(), mem = new ArrayList<>(), net = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            JSONArray r = a.optJSONArray(i);
            if (r == null || r.length() < 5) continue;
            double t = r.optDouble(0);
            if (!r.isNull(1)) cpu.add(new double[]{t, r.optDouble(1)});
            if (!r.isNull(2)) mem.add(new double[]{t, r.optDouble(2)});
            net.add(new double[]{t, (r.optDouble(3, 0) + r.optDouble(4, 0)) / 1024.0});
        }
        return new double[][][]{cpu.toArray(new double[0][]), mem.toArray(new double[0][]), net.toArray(new double[0][])};
    }

    public Detail opsGuest(String node, String type, long vmid) throws Exception {
        JSONObject o = requestJson("GET", "/ops/infra/guest?node=" + path(node) + "&type=" + path(type) + "&vmid=" + vmid, null);
        JSONObject st = o.optJSONObject("status"), c = o.optJSONObject("config");
        Detail d = new Detail();
        if (st == null) st = new JSONObject();
        if (c == null) c = new JSONObject();
        d.add("Status", st.optString("status"));
        d.add("OS", o.optString("os") + (c.optString("ostype").isEmpty() ? "" : " (" + c.optString("ostype") + ")"));
        d.add("Node", node);
        d.add("Uptime", SystemHealth.uptime(st.optLong("uptime", 0)));
        d.add("CPU", String.format(java.util.Locale.US, "%.1f%% of %d cores", st.optDouble("cpu", 0) * 100, st.optInt("cpus", c.optInt("cores", 0))));
        d.add("Memory", Detail.pair(st.optDouble("mem", Double.NaN), st.optDouble("maxmem", Double.NaN)));
        d.add("Disk", Detail.pair(st.optDouble("disk", Double.NaN), st.optDouble("maxdisk", Double.NaN)));
        JSONArray ifs = o.optJSONArray("interfaces");
        if (ifs != null) for (int i = 0; i < ifs.length(); i++) {
            JSONObject f = ifs.optJSONObject(i);
            if (f != null && !"lo".equals(f.optString("name")) && !f.optString("ip", "").isEmpty() && !f.isNull("ip")) d.add("IP · " + f.optString("name"), f.optString("ip"));
        }
        return d;
    }

    public Detail opsNode(String node) throws Exception {
        JSONObject o = requestJson("GET", "/ops/infra/node?node=" + path(node), null);
        Detail d = new Detail();
        d.add("Proxmox", o.optString("pveversion"));
        d.add("Kernel", o.optString("kversion"));
        d.add("CPU", HermesApi.clean(o.optString("cpuinfo")));
        d.add("CPU load", String.format(java.util.Locale.US, "%.1f%% of %d cores", o.optDouble("cpu", 0) * 100, o.optInt("maxcpu", 0)));
        JSONObject m = o.optJSONObject("memory"), r = o.optJSONObject("rootfs");
        if (m != null) d.add("Memory", Detail.pair(m.optDouble("used", Double.NaN), m.optDouble("total", Double.NaN)));
        if (r != null) d.add("Disk", Detail.pair(r.optDouble("used", Double.NaN), r.optDouble("total", Double.NaN)));
        d.add("Uptime", SystemHealth.uptime(o.optLong("uptime", 0)));
        JSONArray l = o.optJSONArray("loadavg");
        if (l != null && l.length() > 0) d.add("Load avg", l.optString(0) + " " + l.optString(1) + " " + l.optString(2));
        return d;
    }

    public List<String> opsLogs(String name, int lines) throws Exception { return fetchLogs(name, lines, null).lines; }

    /** Incremental logs: empty cursor = last `lines`; later calls return only newer lines. */
    public LogPage opsLogs(String name, int lines, String cursor) throws Exception { return fetchLogs(name, lines, cursor == null ? "" : cursor); }

    public static final class LogPage {
        public final List<String> lines = new ArrayList<>();
        public String cursor = "";
    }

    private LogPage fetchLogs(String name, int lines, String after) throws Exception {
        JSONObject root = requestJson("GET", "/ops/logs?name=" + URLEncoder.encode(name == null ? "" : name, StandardCharsets.UTF_8.name()) + "&lines=" + lines
                + (after == null ? "" : "&after=" + URLEncoder.encode(after, StandardCharsets.UTF_8.name())), null);
        LogPage p = new LogPage();
        JSONArray arr = root.optJSONArray("lines");
        if (arr != null) for (int i = 0; i < arr.length(); i++) p.lines.add(arr.optString(i, ""));
        p.cursor = root.optString("cursor", "");
        return p;
    }

    /** Returns the service's new state. */
    public String opsRestart(String name) throws Exception { return opsRestart(name, "restart"); }

    /** restart, or start for a stopped docker container (hub allow-list applies to both). */
    public String opsRestart(String name, String action) throws Exception {
        return requestJson("POST", "/ops/restart", new JSONObject().put("name", name).put("action", action)).optString("state", "");
    }

    /** A file Hermes attached as MEDIA:/abs/path (images, PDFs), served by the hub's /media. */
    public byte[] media(String path, int maxBytes) throws Exception {
        HttpURLConnection c = open("GET", "/media?path=" + URLEncoder.encode(path, StandardCharsets.UTF_8.name()));
        try {
            int code = c.getResponseCode();
            if (code != 200) throw new IllegalStateException("HTTP " + code);
            try (java.io.InputStream in = c.getInputStream()) {
                java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream(); byte[] buf = new byte[16384]; int n;
                while ((n = in.read(buf)) > 0) { out.write(buf, 0, n); if (out.size() > maxBytes) throw new IllegalStateException("Too large"); }
                return out.toByteArray();
            }
        } finally { c.disconnect(); }
    }

    /** This phone's mutual-TLS certificate (/pair/cert). The hub hands it out only over Tailscale, so only Tailscale routes are tried. */
    public JSONObject pairCert() throws Exception {
        Exception last = new java.net.ConnectException("Tailscale isn't connected: the certificate is only handed out over Tailscale");
        for (String candidate : Reach.hubCandidates(base)) {
            try { if (!Reach.tailnet(new URL(candidate).getHost())) continue; } catch (Exception e) { continue; }
            HttpURLConnection c = null;
            try {
                c = openAt(candidate, "GET", "/pair/cert", 4_000, 15_000);
                int code = c.getResponseCode();
                String response = read(c, code);
                if (code != 200) throw new IllegalStateException(HermesApi.errorText(code, response));
                return new JSONObject(response);
            } catch (java.io.IOException e) { last = e; }
            finally { if (c != null) c.disconnect(); }
        }
        throw last;
    }

    /** The machine Wake-on-LAN targets ({mac, host, port}; empty when unset). */
    public JSONObject wakeConfig() throws Exception { return requestJson("GET", "/ops/wake/config", null); }

    /** Sets (or with an empty mac clears) the Wake-on-LAN target on the hub. */
    public void wakeConfigSet(String mac, String host) throws Exception {
        JSONObject r = requestJson("POST", "/ops/wake/config", new JSONObject().put("mac", mac).put("host", host));
        if (!r.optBoolean("ok", true)) throw new IllegalStateException(r.optString("error", "Not saved"));
    }

    /** Latest published app build (/app/latest); 404 means none published. */
    public JSONObject appLatest() throws Exception { return getJson("/app/latest", 2_000, 4_000, 12_000); }

    /** Streams a fresh complete APK per candidate; partial downloads never survive failed attempts. */
    public String appApk(java.io.File dest, java.util.function.LongConsumer progress) throws Exception {
        long deadline = System.nanoTime() + 240_000_000_000L;
        java.io.IOException last = null;
        boolean complete = false;
        try {
            for (String candidate : Reach.hubCandidates(base)) {
                HttpURLConnection c = null;
                try {
                    progress.accept(0);
                    c = openAt(candidate, "GET", "/app/apk", timeout(3_000, deadline), timeout(10_000, deadline));
                    c.connect();
                    c.setReadTimeout(timeout(10_000, deadline));
                    int code = c.getResponseCode();
                    if (retryCode(code)) throw new java.io.IOException("HTTP " + code);
                    if (code != 200) throw new IllegalStateException("HTTP " + code);
                    long expected = c.getContentLengthLong(), total = 0;
                    java.security.MessageDigest md = java.security.MessageDigest.getInstance("SHA-256");
                    try (InputStream in = c.getInputStream(); java.io.FileOutputStream out = new java.io.FileOutputStream(dest)) {
                        byte[] buf = new byte[65536]; int n;
                        while (true) {
                            c.setReadTimeout(timeout(10_000, deadline));
                            n = in.read(buf);
                            if (n < 0) break;
                            if (n == 0) continue;
                            out.write(buf, 0, n); md.update(buf, 0, n); progress.accept(total += n);
                        }
                    }
                    if (expected >= 0 && total != expected) throw new java.io.IOException("Incomplete APK download");
                    timeout(1, deadline);
                    Reach.hubSucceeded(base, candidate);
                    complete = true;
                    return Updater.hex(md.digest());
                } catch (java.io.IOException e) { last = e; }
                finally { if (c != null) c.disconnect(); }
            }
            throw last == null ? new java.io.IOException("No hub update route available") : last;
        } finally { if (!complete) dest.delete(); }
    }

    private JSONObject getJson(String endpoint, int connect, int read, long budgetMs) throws Exception {
        long deadline = System.nanoTime() + budgetMs * 1_000_000L;
        java.io.IOException last = null;
        for (String candidate : Reach.hubCandidates(base)) {
            HttpURLConnection c = null;
            int status = -1;
            boolean complete = false;
            try {
                c = openAt(candidate, "GET", endpoint, timeout(connect, deadline), timeout(read, deadline));
                c.connect();
                c.setReadTimeout(timeout(read, deadline));
                int code = c.getResponseCode();
                if (retryCode(code)) throw new java.io.IOException("HTTP " + code);
                status = code;
                java.io.ByteArrayOutputStream bytes = new java.io.ByteArrayOutputStream();
                InputStream stream = code >= 400 ? c.getErrorStream() : c.getInputStream();
                if (stream != null) try (InputStream in = stream) {
                    byte[] buffer = new byte[8192]; int n;
                    while (true) {
                        c.setReadTimeout(timeout(read, deadline));
                        n = in.read(buffer);
                        if (n < 0) break;
                        bytes.write(buffer, 0, n);
                        if (bytes.size() > 4_000_000) throw new IllegalStateException("Hub response too large");
                    }
                }
                timeout(1, deadline);
                String response = bytes.toString(StandardCharsets.UTF_8.name());
                if (code < 200 || code >= 300) throw new IllegalStateException(HermesApi.errorText(code, response));
                JSONObject result = response.trim().isEmpty() ? new JSONObject() : new JSONObject(response);
                Reach.hubSucceeded(base, candidate);
                complete = true;
                return result;
            } catch (java.io.IOException e) {
                if (status == 401 || status == 403) throw new IllegalStateException("HTTP " + status, e);
                last = e;
            }
            finally { if (c != null && !complete) c.disconnect(); }
        }
        throw last == null ? new java.io.IOException("No hub route available") : last;
    }

    private static boolean retryCode(int code) { return code == 502 || code == 503 || code == 504; }
    private static int timeout(int maximum, long deadline) throws java.io.IOException {
        long remaining = (deadline - System.nanoTime()) / 1_000_000L;
        if (remaining <= 0) throw new java.net.SocketTimeoutException("Hub request time budget exceeded");
        return (int) Math.min(maximum, remaining);
    }

    /** A call through the hub's /arr proxy (Sonarr, Radarr, Prowlarr, qBittorrent, Jellyfin). Returns a JSONObject or JSONArray. */
    public Object arr(String method, String path, String body, String contentType) throws Exception { return arrLike(method, "/arr/" + path, body, contentType); }

    private Object arrLike(String method, String hubPath, String body, String contentType) throws Exception {
        HttpURLConnection c = open(method, hubPath);
        c.setReadTimeout(50_000);
        if (body != null) {
            c.setDoOutput(true); c.setRequestProperty("Content-Type", contentType);
            try (OutputStream out = c.getOutputStream()) { out.write(body.getBytes(StandardCharsets.UTF_8)); }
        }
        int code = c.getResponseCode();
        String response = read(c, code);
        if (code < 200 || code >= 300) throw new IllegalStateException(code == 404 ? "Not set up on the hub" : HermesApi.errorText(code, response));
        String t = response.trim();
        return t.isEmpty() ? new JSONObject() : new org.json.JSONTokener(t).nextValue();
    }

    public Object arr(String path) throws Exception { return arr("GET", path, null, null); }

    /** A call to the hub's media-server modules: /media/modules, /media/<server>/<op>[?query] (see MediaModules). */
    public JSONObject media(String method, String path, JSONObject body) throws Exception {
        Object r = arrLike(method, "/media/" + path, body == null ? null : body.toString(), Arr.JSON);
        if (!(r instanceof JSONObject)) throw new IllegalStateException("Expected JSON object");
        return (JSONObject) r;
    }

    /** Terminal requests in flight: a network change aborts them at once instead of waiting out a dead socket. */
    private static final java.util.Set<HttpURLConnection> TERM_CALLS = java.util.concurrent.ConcurrentHashMap.newKeySet();

    /** Wi-Fi, mobile data or Tailscale just changed: drop the Terminal's open requests (they retry on the new network). */
    static void abortTermCalls() { for (HttpURLConnection c : TERM_CALLS) { try { c.disconnect(); } catch (Exception ignored) { } } }

    /** The Terminal module's shell sessions on the hub: /term/targets|list|open|read|send|resize|close (Tailscale only). */
    public JSONObject term(String method, String path, JSONObject body) throws Exception {
        HttpURLConnection c = open(method, "/term/" + path);
        c.setConnectTimeout(8_000); c.setReadTimeout("GET".equals(method) && path.startsWith("read") ? 24_000 : 12_000); // a read waits up to 15 s on the hub
        TERM_CALLS.add(c);
        boolean ok = false;
        try {
            if (body != null) { c.setDoOutput(true); try (OutputStream out = c.getOutputStream()) { out.write(body.toString().getBytes(StandardCharsets.UTF_8)); } }
            int code = c.getResponseCode();
            String response = read(c, code);
            if (code < 200 || code >= 300) throw new IllegalStateException(code == 404 ? "Not set up on the hub" : HermesApi.errorText(code, response));
            String t = response.trim();
            ok = true;
            return t.isEmpty() ? new JSONObject() : new JSONObject(t);
        } finally {
            TERM_CALLS.remove(c);
            if (!ok) { try { c.disconnect(); } catch (Exception ignored) { } }
        }
    }

    /** Artwork through a media module (paths come from the server's items, "/media/<server>/image?..."). */
    public byte[] mediaBytes(String path) throws Exception { return bytesAt(path); }

    /** Absolute URL of an /arr path (cache key for images fetched through the proxy). */
    public String arrUrl(String path) { return UrlUtils.endpoint(base, "/arr/" + path); }

    /** Binary GET through /arr (posters, Jellyfin artwork). */
    public byte[] arrBytes(String path) throws Exception { return bytesAt("/arr/" + path); }

    private byte[] bytesAt(String hubPath) throws Exception {
        HttpURLConnection c = open("GET", hubPath);
        c.setReadTimeout(60_000);
        try {
            int code = c.getResponseCode();
            if (code != 200) throw new IllegalStateException("HTTP " + code);
            try (InputStream in = c.getInputStream()) {
                java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream(); byte[] buf = new byte[16384]; int n;
                while ((n = in.read(buf)) > 0) { out.write(buf, 0, n); if (out.size() > 8_000_000) throw new IllegalStateException("Too large"); }
                return out.toByteArray();
            }
        } finally { c.disconnect(); }
    }

    private JSONObject requestJson(String method, String endpoint, JSONObject body) throws Exception {
        if ("GET".equals(method)) return getJson(endpoint, connectMs, readMs, Math.max(18_000L, (long) connectMs + readMs));
        try { return requestOnce(method, endpoint, body); }
        catch (java.io.IOException e) {
            if (!Reach.isConnectFailure(e, method) || !Reach.failover(UrlUtils.endpoint(base, endpoint))) throw e;
            return requestOnce(method, endpoint, body);
        }
    }

    private JSONObject requestOnce(String method, String endpoint, JSONObject body) throws Exception {
        HttpURLConnection c = open(method, endpoint);
        if (body != null) { byte[] data = body.toString().getBytes(StandardCharsets.UTF_8); try (OutputStream out = c.getOutputStream()) { out.write(data); } }
        int code = c.getResponseCode();
        String response = read(c, code); // no disconnect(): keep the connection for the next call
        if (code < 200 || code >= 300) throw new IllegalStateException(HermesApi.errorText(code, response));
        String trim = response.trim();
        return trim.isEmpty() ? new JSONObject() : new JSONObject(trim);
    }

    private boolean pinned;

    /** Always talk to exactly this base (no failover to other addresses): the terminal must arrive over Tailscale. */
    public HubApi pinned() { pinned = true; return this; }

    private HttpURLConnection open(String method, String endpoint) throws Exception {
        String url = UrlUtils.endpoint(base, endpoint);
        return openAt("", method, pinned ? url : Reach.apply(url), connectMs, readMs);
    }

    private HttpURLConnection openAt(String candidate, String method, String endpoint, int connect, int read) throws Exception {
        String url = candidate.isEmpty() ? endpoint : UrlUtils.endpoint(candidate, endpoint);
        Reach.checkSecure(url);
        HttpURLConnection c = connections.open(new URL(url));
        c.setInstanceFollowRedirects(false);
        c.setRequestMethod(method); c.setConnectTimeout(connect); c.setReadTimeout(read); c.setDoInput(true);
        c.setRequestProperty("Accept", "application/json"); c.setRequestProperty("Content-Type", "application/json; charset=utf-8");
        c.setRequestProperty("Authorization", "Bearer " + key);
        c.setRequestProperty("User-Agent", "Hermes-Companion-Android/7");
        if ("POST".equals(method)) c.setDoOutput(true);
        return c;
    }

    private static String path(String raw) {
        try { return URLEncoder.encode(raw, StandardCharsets.UTF_8.name()).replace("+", "%20"); }
        catch (Exception e) { return raw; }
    }

    private static String read(HttpURLConnection c, int code) throws Exception {
        InputStream stream = code >= 400 ? c.getErrorStream() : c.getInputStream();
        if (stream == null) return "";
        StringBuilder b = new StringBuilder();
        try (BufferedReader r = new BufferedReader(new InputStreamReader(stream, StandardCharsets.UTF_8))) {
            String line; while ((line = r.readLine()) != null && b.length() < 4_000_000) b.append(line).append('\n');
        }
        return b.toString();
    }

    /** Derives the default hub URL from a Hermes API endpoint: same host, fixed port 8791. */
    static String defaultFor(String hermesEndpoint) {
        return HubCredentials.defaultFor(hermesEndpoint);
    }
}
