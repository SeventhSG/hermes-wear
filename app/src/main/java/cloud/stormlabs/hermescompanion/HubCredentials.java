package cloud.stormlabs.hermescompanion;

import java.net.URL;
import java.util.List;

/** Select the Hermes root credentials for a hub; a bridge token never authenticates the hub. */
final class HubCredentials {
    static final class Profile {
        final String type, endpoint, hub, key;
        Profile(String type, String endpoint, String hub, String key) {
            this.type = type; this.endpoint = UrlUtils.normalize(endpoint);
            this.hub = UrlUtils.normalize(hub); this.key = key == null ? "" : key;
        }
        String hubUrl() { return hub.isEmpty() ? defaultFor(endpoint) : hub; }
    }

    static Profile select(Profile source, List<Profile> profiles) {
        if ("Hermes".equals(source.type)) {
            String root = source.endpoint.replaceFirst("/p/[^/?#]+/?$", "");
            if (root.equals(source.endpoint)) return source;
            for (Profile p : profiles) if ("Hermes".equals(p.type) && root.equals(p.endpoint)) return p;
            return null;
        }
        if (!"Codex".equals(source.type) && !"Claude Code".equals(source.type)) return null;
        Profile found = null;
        String hub = source.hubUrl();
        for (Profile p : profiles) {
            if (!"Hermes".equals(p.type) || p.endpoint.matches(".*/p/[^/?#]+/?$")) continue;
            if (!hub.equals(p.hubUrl()) && !sameHost(source.endpoint, p.endpoint)) continue;
            if (found != null && (!found.key.equals(p.key) || !found.hubUrl().equals(p.hubUrl()))) return null;
            found = p;
        }
        return found;
    }

    private static boolean sameHost(String a, String b) {
        try { return new URL(a).getHost().equalsIgnoreCase(new URL(b).getHost()); }
        catch (Exception e) { return false; }
    }

    static String defaultFor(String endpoint) {
        try { return "http://" + new URL(UrlUtils.normalize(endpoint)).getHost() + ":8791"; }
        catch (Exception e) { return ""; }
    }
}
