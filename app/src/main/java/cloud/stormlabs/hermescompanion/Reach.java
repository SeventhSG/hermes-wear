package cloud.stormlabs.hermescompanion;

import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.URL;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

/**
 * Backup addresses for the same server, like Hermes Desktop's saved remote connection: the LAN IP,
 * the Tailscale IP / MagicDNS name, or a public https URL. A bare host ("100.x.y.z") stands in for
 * the server on every port (Hermes API, hub and the Wyoming voice ports); a full URL ("https://hermes.example.com") stands in
 * for the Hermes API, with the hub at its /hub path, the Codex bridge at /codex and the Claude Code bridge at /claude. Requests go to whichever address last answered; when a connect fails
 * all the other addresses are probed at once and the first to accept a TCP connection takes over for every client. A network
 * change (Wi-Fi to mobile data and back) re-runs that race in the background, so the next request does not wait on a dead address.
 * With a device certificate ({@link DeviceCert}) nothing goes over plain HTTP except across Tailscale (WireGuard): LAN http
 * addresses drop out in favour of the https one, and voice on the LAN moves to the server's mutual-TLS ports.
 */
final class Reach {
    private Reach() {}

    private static volatile List<String> backups = Collections.emptyList();
    private static volatile int apiPort = -1;
    static final int HUB_PORT = 8791, CODEX_PORT = 8795, CLAUDE_PORT = 8796, GATEWAY_PORT = 8797;
    /** "host:port" of the configured address -> base ("scheme://host:port") currently in use */
    private static final Map<String, String> active = new ConcurrentHashMap<>();

    private static final Map<String, String> hubActive = new ConcurrentHashMap<>();
    /** Configured urls seen by {@link #apply} and hub primaries, re-raced after a network change */
    private static final Set<String> seen = ConcurrentHashMap.newKeySet(), seenHubs = ConcurrentHashMap.newKeySet();
    /** host:port keys (and hub origins) already raced on the current network: the first request of each races once, the rest reuse it. */
    private static final Set<String> decided = ConcurrentHashMap.newKeySet();
    private static final Map<String, Object> deciding = new ConcurrentHashMap<>();
    /** Proxy-denied service bases on this network; a TCP handshake alone cannot make them usable. */
    private static final Set<String> blocked = ConcurrentHashMap.newKeySet();
    /** First try on an address while backups exist: a dead LAN IP on mobile data must not stall a request for 15 s. */
    static final int FIRST_CONNECT_MS = 4_000;
    private static final int PROBE_MS = 3_000;
    /** Set by {@link DeviceCert#load}: encrypted connections only. */
    static volatile boolean secureOnly;

    static void configure(SettingsStore store) {
        int port = -1;
        // Only a Hermes profile's port maps to a public URL's root (the Hermes API); the Codex bridge port maps to its /codex
        // path (see candidates), so a Codex request never lands on the Hermes API.
        if ("Hermes".equals(store.serverType()))
            try { URL u = new URL(store.endpoint()); port = u.getPort() < 0 ? u.getDefaultPort() : u.getPort(); } catch (Exception ignored) {}
        configure(store.backupHosts(), port);
    }

    static void configure(String csv, int hermesPort) {
        List<String> out = new ArrayList<>();
        for (String raw : (csv == null ? "" : csv).split("[,\\s]+")) {
            String v = raw.trim();
            if (v.isEmpty()) continue;
            if (!v.contains("://") && !v.matches(".*:\\d+$")) { v = v.toLowerCase(Locale.ROOT); if (!out.contains(v)) out.add(v); continue; }
            String url = UrlUtils.normalize(v);
            if (UrlUtils.isUsableEndpoint(url) && !out.contains(url)) out.add(url);
        }
        apiPort = hermesPort;
        backups = out;
        active.clear();
        hubActive.clear();
        decided.clear();
        blocked.clear();
    }

    static boolean hasBackups() { return !backups.isEmpty(); }

    /** Tailscale (100.64.0.0/10, MagicDNS) or this device: already encrypted or never leaves the phone. */
    static boolean tailnet(String host) {
        String h = host.toLowerCase(Locale.ROOT).replace("[", "").replace("]", "");
        if (h.endsWith(".ts.net") || h.equals("localhost") || h.startsWith("127.") || h.equals("::1") || h.startsWith("fd7a:115c:a1e0")) return true;
        String[] q = h.split("\\.");
        try { return q.length == 4 && Integer.parseInt(q[0]) == 100 && Integer.parseInt(q[1]) >= 64 && Integer.parseInt(q[1]) <= 127; }
        catch (NumberFormatException e) { return false; }
    }

    /** The server's mutual-TLS port in front of a plain Wyoming voice port, or -1. */
    static int tlsPort(int port) { return port == 10300 ? 10340 : port == 10200 ? 10240 : -1; }

    /** Plain http (or raw TCP) to an address outside Tailscale: readable by anyone on that network. */
    static boolean insecure(String base) {
        try { URL u = new URL(base); return "http".equals(u.getProtocol()) && !tailnet(u.getHost()); } catch (Exception e) { return true; }
    }

    /** Refuses to send over plain HTTP outside Tailscale once encrypted-only is on; a ConnectException, so callers fail over. */
    static void checkSecure(String url) throws java.net.ConnectException {
        if (secureOnly && insecure(url)) throw new java.net.ConnectException("Not sent: " + hostOf(url) + " is plain HTTP outside Tailscale (encrypted connections only)");
    }

    private static String hostOf(String url) { try { return new URL(url).getHost(); } catch (Exception e) { return url; } }

    /** {@code csv} plus any of {@code add} it lacks, in order ("a, b"). */
    static String merge(String csv, List<String> add) {
        List<String> out = new ArrayList<>();
        for (String v : (csv == null ? "" : csv).split("[,\\s]+")) if (!v.trim().isEmpty() && !out.contains(v.trim().toLowerCase(Locale.ROOT))) out.add(v.trim().toLowerCase(Locale.ROOT));
        for (String v : add) if (!v.trim().isEmpty() && !out.contains(v.trim().toLowerCase(Locale.ROOT))) out.add(v.trim().toLowerCase(Locale.ROOT));
        return String.join(", ", out);
    }

    /** The default network changed: forget which address worked and race them all again, off the caller's thread. */
    static void networkChanged() {
        blocked.clear();
        decided.clear();
        if (backups.isEmpty()) return;
        if (!claimRound()) return;   // a round is already probing; it runs once more when it finishes
        Thread t = new Thread(() -> {
            do { try { raceAll(); } catch (Throwable ignored) {} } while (roundDone());
        }, "reach-race");
        t.setDaemon(true); t.start();
    }

    /** 0 idle, 1 one probe round running, 2 running with another change queued: flapping Wi-Fi/mobile data cannot pile up threads. */
    private static final java.util.concurrent.atomic.AtomicInteger rounds = new java.util.concurrent.atomic.AtomicInteger();
    /** True when the caller should start the round; false when one is running (and has now been asked to repeat). */
    static boolean claimRound() { return rounds.getAndUpdate(n -> n == 0 ? 1 : 2) == 0; }
    /** True when another change arrived meanwhile, so the round runs again. */
    static boolean roundDone() {
        if (rounds.compareAndSet(1, 0)) return false;
        rounds.set(1); return true;
    }

    private static void raceAll() {
        {
            for (String url : seen) try {
                URL u = new URL(url);
                List<String> bases = candidates(u);
                String best = race(bases, null);
                if (best == null || best.equals(bases.get(0))) active.remove(key(u)); else active.put(key(u), best);
                decided.add(key(u));
            } catch (Exception ignored) {}
            for (String primary : seenHubs) {
                hubActive.remove(primary);
                decided.add("hub " + primary);
                String best = race(hubCandidates(primary), null);
                if (best != null) hubActive.put(primary, best);
            }
        }
    }

    /** Raw TCP (Wyoming speech-to-text and voice): connect to host:port, or to the first backup address that answers. */
    static Socket socket(String host, int port, int timeoutMs) throws java.io.IOException {
        String url = "http://" + hostPart(host) + ":" + port;
        try { return open(new URL(apply(url)), backups.isEmpty() && !secureOnly ? timeoutMs : Math.min(timeoutMs, FIRST_CONNECT_MS)); }
        catch (java.io.IOException e) {
            if (!failover(url)) throw e;
            return open(new URL(apply(url)), timeoutMs);
        }
    }

    private static Socket open(URL base, int timeoutMs) throws java.io.IOException {
        if (secureOnly && insecure(base.toString())) {
            if (tlsPort(port(base)) < 0) throw new java.net.ConnectException("No encrypted port for " + base.getHost() + ":" + port(base));
            return DeviceCert.voiceSocket(base.getHost(), tlsPort(port(base)), timeoutMs);
        }
        Socket s = new Socket();
        try { s.connect(new InetSocketAddress(base.getHost(), port(base)), timeoutMs); return s; }
        catch (java.io.IOException e) { try { s.close(); } catch (Exception ignored) {} throw e; }
    }

    /** The url pointed at the address that currently works. */
    static String apply(String url) {
        if (backups.isEmpty() || url == null || url.isEmpty()) return url;
        try {
            URL u = new URL(url);
            String self = u.getProtocol() + "://" + hostPart(u.getHost()) + ":" + port(u);
            seen.add(self);
            decide(u);
            String now = active.get(key(u));
            if (now == null && secureOnly && insecure(self)) {  // the configured address is plain http: start on the first encrypted one
                List<String> bases = candidates(u);
                if (!bases.isEmpty() && !bases.get(0).equals(self)) now = bases.get(0);
            }
            return now == null ? url : now + u.getFile();
        } catch (Exception e) { return url; }
    }

    /**
     * First use of an address on this network (app start, network change): race all its candidates once so a dead address
     * (Tailscale off, LAN IP on mobile data) costs one round trip to the live one instead of a connect timeout. Concurrent
     * first requests wait for the same race. Only in the app ({@link #raceOnFirstUse}, so unit tests stay offline), never on the main thread.
     */
    private static void decide(URL u) {
        String k = key(u);
        if (decided.contains(k) || skipRace()) return;
        synchronized (deciding.computeIfAbsent(k, x -> new Object())) {
            if (decided.contains(k)) return;
            List<String> bases = candidates(u);
            if (bases.size() > 1) {
                String best = race(bases, null);
                if (best != null) { if (best.equals(bases.get(0))) active.remove(k); else active.put(k, best); }
            }
            decided.add(k);
        }
    }

    /** Set by App: lets the first request on a network race the candidates (see decide). */
    static volatile boolean raceOnFirstUse;

    private static boolean skipRace() { return !raceOnFirstUse || "main".equals(Thread.currentThread().getName()); }

    /** After a failed connect to {@code url} (as configured, before apply): move to the next reachable address. */
    static boolean failover(String url) {
        if (backups.isEmpty() || url == null) return false;
        try {
            URL u = new URL(url);
            List<String> bases = candidates(u);
            if (bases.size() < 2) return false;
            String current = active.get(key(u));
            String best = race(bases, current == null ? bases.get(0) : current);
            if (best == null) return false;
            if (best.equals(bases.get(0))) active.remove(key(u)); else active.put(key(u), best);
            decided.add(key(u));
            return true;
        } catch (Exception ignored) {}
        return false;
    }

    /** Probe every base except {@code skip} at once; the first to accept a TCP connection wins (null if none in time). */
    static String race(List<String> bases, String skip) {
        BlockingQueue<String> answers = new LinkedBlockingQueue<>();
        int n = 0;
        for (String b : bases) {
            if (b.equals(skip) || blocked.contains(b)) continue;
            n++;
            Thread t = new Thread(() -> answers.add(reachable(b) ? b : ""), "reach-probe");
            t.setDaemon(true); t.start();
        }
        long end = System.currentTimeMillis() + PROBE_MS + 200;
        try {
            for (int i = 0; i < n; i++) {
                String r = answers.poll(Math.max(1, end - System.currentTimeMillis()), TimeUnit.MILLISECONDS);
                if (r == null) return null;
                if (!r.isEmpty() && !blocked.contains(r)) return r;
            }
        } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        return null;
    }

    /** A proxy rejected this route before the API handled the request. Structured API denials stay auth errors. */
    static boolean rejectProxy(String configured, URL actual, int code, String body) {
        if (backups.isEmpty() || (code != 403 && code != 421 && code != 495 && code != 496)) return false;
        String text = body == null ? "" : body.trim();
        try {
            org.json.JSONTokener json = new org.json.JSONTokener(text);
            Object value = json.nextValue();
            if (json.nextClean() == 0 && (!(value instanceof String) || text.startsWith("\""))) return false;
        } catch (org.json.JSONException ignored) {}
        try {
            URL u = new URL(configured);
            String primary = u.getProtocol() + "://" + hostPart(u.getHost()) + ":" + port(u);
            for (String candidate : candidates(u)) {
                if (candidate.equals(primary) && !decided.contains(key(u))) continue;
                URL target = new URL(candidate + u.getFile());
                if (target.getProtocol().equals(actual.getProtocol()) && target.getHost().equalsIgnoreCase(actual.getHost())
                        && port(target) == port(actual) && target.getPath().equals(actual.getPath())) {
                    blocked.add(candidate);
                    return true;
                }
            }
        } catch (Exception ignored) {}
        return false;
    }

    static List<String> candidates(URL u) {
        int port = port(u);
        List<String> out = new ArrayList<>();
        out.add(u.getProtocol() + "://" + hostPart(u.getHost()) + ":" + port);
        for (String b : backups) {
            String base;
            if (b.contains("://")) {
                // A public URL fronts the Hermes API at /, the hub at /hub, the Codex bridge at /codex, the Claude Code bridge at /claude and the gateway platform at /gateway (one reverse-proxy host).
                String sub = port == apiPort ? "" : port == HUB_PORT ? "/hub" : port == CODEX_PORT ? "/codex" : port == CLAUDE_PORT ? "/claude" : port == GATEWAY_PORT ? "/gateway" : null;
                if (sub == null) continue;
                try { URL bu = new URL(b); base = bu.getProtocol() + "://" + hostPart(bu.getHost()) + ":" + port(bu) + bu.getPath() + sub; }
                catch (Exception e) { continue; }
            } else base = u.getProtocol() + "://" + hostPart(b) + ":" + port;
            if (!out.contains(base)) out.add(base);
        }
        if (secureOnly) out.removeIf(b -> insecure(b) && tlsPort(port) < 0);  // voice ports stay: they go over TLS (see open)
        return out;
    }

    /** Complete hub bases: routing is decided by the real authenticated GET, never a TCP probe. */
    static List<String> hubCandidates(String primary) {
        String origin = UrlUtils.normalize(primary);
        if (!backups.isEmpty()) seenHubs.add(origin);
        List<String> out = new ArrayList<>();
        out.add(origin);
        for (String backup : backups) {
            String candidate = backup.contains("://") ? UrlUtils.normalize(backup) : "http://" + hostPart(backup) + ":" + HUB_PORT;
            if (backup.contains("://") && !candidate.endsWith("/hub")) candidate += "/hub";
            if (!out.contains(candidate)) out.add(candidate);
        }
        if (secureOnly) out.removeIf(Reach::insecure);
        String current = hubActive.get(origin);
        if (current == null && out.size() > 1 && !decided.contains("hub " + origin) && !skipRace())
            synchronized (deciding.computeIfAbsent("hub " + origin, x -> new Object())) {  // first hub call on this network: fastest route first
                current = hubActive.get(origin);
                if (current == null && !decided.contains("hub " + origin)) { current = race(out, null); decided.add("hub " + origin); if (current != null) hubActive.put(origin, current); }
            }
        if (current != null && out.remove(current)) out.add(0, current);
        return out;
    }

    /** The first of {@code bases} that is a Tailscale address (the hub serves the terminal only to tailnet callers), or null. */
    static String tailnetBase(List<String> bases) {
        for (String b : bases) { try { if (tailnet(new URL(b).getHost())) return b; } catch (Exception ignored) { } }
        return null;
    }

    static void hubSucceeded(String primary, String candidate) {
        hubActive.put(UrlUtils.normalize(primary), candidate);
    }

    /** The request never reached the server, so it is safe to resend elsewhere (any timeout for reads, a connect timeout for writes). */
    static boolean isConnectFailure(Throwable e, String method) {
        if (e instanceof java.net.SocketTimeoutException) {
            String m = String.valueOf(e.getMessage()).toLowerCase(Locale.ROOT);
            return "GET".equals(method) || m.contains("connect");
        }
        return e instanceof java.net.ConnectException || e instanceof java.net.NoRouteToHostException
                || e instanceof java.net.UnknownHostException || e instanceof java.net.PortUnreachableException;
    }

    private static boolean reachable(String base) {
        try (Socket s = new Socket()) {
            URL u = new URL(base);
            int p = secureOnly && insecure(base) && tlsPort(port(u)) > 0 ? tlsPort(port(u)) : port(u);
            s.connect(new InetSocketAddress(u.getHost(), p), PROBE_MS); return true;
        }
        catch (Exception e) { return false; }
    }

    private static String key(URL u) { return u.getHost().toLowerCase(Locale.ROOT) + ":" + port(u); }
    private static int port(URL u) { return u.getPort() < 0 ? u.getDefaultPort() : u.getPort(); }
    private static String hostPart(String h) { return h.contains(":") && !h.startsWith("[") ? "[" + h + "]" : h; }
}
