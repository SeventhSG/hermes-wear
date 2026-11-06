package cloud.stormlabs.hermescompanion;

import android.content.Context;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Which media modules the hub has set up (GET /media/modules): the *arr services (Sonarr, Radarr, qBittorrent, Prowlarr)
 * and media servers (Jellyfin, Emby, Plex, Ampache, ... in one normalized API). Cached so every screen decides at once
 * what to show; nothing for an unconfigured service is drawn or fetched.
 */
final class MediaModules {
    private MediaModules() {}

    static final class Module {
        final String id, name, kind; final List<String> features;
        Module(String id, String name, String kind, List<String> features) { this.id = id; this.name = name; this.kind = kind; this.features = features; }
        boolean server() { return "server".equals(kind); }
        boolean can(String feature) { return features.contains(feature); }
    }

    private static volatile List<Module> cache;

    /** Last known list (empty before the hub was ever asked). */
    static List<Module> cached(Context c) {
        List<Module> m = cache;
        if (m == null) cache = m = parse(c.getSharedPreferences("media_modules", Context.MODE_PRIVATE).getString("json", "{}"));
        return m;
    }

    /** Asks the hub, stores the answer and returns it. A hub without /media/modules (older kit) reports nothing set up. */
    static List<Module> refresh(Context c, HubApi hub) throws Exception {
        String json;
        try { json = hub.media("GET", "modules", null).toString(); }
        catch (IllegalStateException notFound) { if (!"Not set up on the hub".equals(notFound.getMessage())) throw notFound; json = "{}"; }
        c.getSharedPreferences("media_modules", Context.MODE_PRIVATE).edit().putString("json", json).apply();
        return cache = parse(json);
    }

    static List<Module> parse(String json) {
        List<Module> out = new ArrayList<>();
        try {
            JSONArray a = new JSONObject(json).optJSONArray("modules");
            for (int i = 0; a != null && i < a.length(); i++) {
                JSONObject o = a.getJSONObject(i);
                List<String> f = new ArrayList<>();
                JSONArray fa = o.optJSONArray("features");
                for (int j = 0; fa != null && j < fa.length(); j++) f.add(fa.optString(j));
                out.add(new Module(o.optString("id"), o.optString("name", o.optString("id")), o.optString("kind"), Collections.unmodifiableList(f)));
            }
        } catch (Exception ignored) {}
        return Collections.unmodifiableList(out);
    }

    /** Whether to offer Media at all: until the hub has answered once, yes; after that only if something is set up. */
    static boolean any(Context c) {
        if (!Modules.on(c, Modules.MEDIA)) return false;
        return !c.getSharedPreferences("media_modules", Context.MODE_PRIVATE).contains("json") || !cached(c).isEmpty();
    }

    static boolean has(Context c, String id) { return has(cached(c), id); }

    static boolean has(List<Module> mods, String id) { for (Module m : mods) if (m.id.equals(id)) return true; return false; }

    static List<Module> servers(List<Module> mods) { List<Module> out = new ArrayList<>(); for (Module m : mods) if (m.server()) out.add(m); return out; }

    static Module find(List<Module> mods, String id) { for (Module m : mods) if (m.id.equals(id)) return m; return null; }

    /** Brand colour per module, so each keeps one accent across tabs, tiles and posters. */
    static int color(String id) {
        switch (id) {
            case "sonarr": return Arr.SONARR;
            case "radarr": return Arr.RADARR;
            case "qbit": return Arr.QBIT;
            case "prowlarr": return Arr.PROWLARR;
            case "jellyfin": return Arr.JELLYFIN;
            case "emby": return 0xff52b54b;
            case "plex": return 0xffe5a00d;
            case "ampache": return 0xfff47a2a;
            default: return Ui.BLUE;
        }
    }
}
