package cloud.stormlabs.hermescompanion;

import android.app.Activity;
import android.widget.TextView;

import org.json.JSONObject;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.function.Consumer;

/** Live per-profile health for the profile switcher: short-timeout probes, cached 60 s per profile. */
final class FleetHealth {
    private FleetHealth() {}

    static final int CHECKING = 0, ONLINE = 1, DEGRADED = 2, OFFLINE = 3;
    static final long TTL_MS = 60_000;

    static final class Status {
        final int level;
        final String version, hint;
        Status(int level, String version, String hint) { this.level = level; this.version = version; this.hint = hint; }
        Status withHint(String h) { return new Status(level, version, h); }
        String label() {
            switch (level) {
                case ONLINE: return version.isEmpty() ? "Online" : "Online · v" + version;
                case DEGRADED: return "Degraded";
                case OFFLINE: return "Offline";
                default: return "Checking…";
            }
        }
        int color() { return level == ONLINE ? Ui.GREEN : level == DEGRADED ? Ui.AMBER : level == OFFLINE ? Ui.RED : Ui.SUB; }
    }

    /** Reachable server: degraded when its gateway or overall state is not healthy. */
    static Status classify(JSONObject h) {
        boolean ok = SystemHealth.stateLevel(SystemHealth.text(h, "gateway_state", "gateway")) == SystemHealth.OK
                && SystemHealth.stateLevel(SystemHealth.text(h, "status", "state")) == SystemHealth.OK;
        String v = SystemHealth.text(h, "version", "hermes_version", "app_version");
        return new Status(ok ? ONLINE : DEGRADED, v.startsWith("v") ? v.substring(1) : v, "");
    }

    /** "pve1 38% CPU · 2 problems" */
    static String hint(LabSummary s) {
        String h = (s.node.isEmpty() ? "" : s.node + " ") + LabSummary.pct(s.cpu) + " CPU";
        return s.problems == 0 ? h : h + String.format(Locale.US, " · %d problem%s", s.problems, s.problems == 1 ? "" : "s");
    }

    private static final class Entry { final Status s; final long at; Entry(Status s, long at) { this.s = s; this.at = at; } }
    private static final Map<String, Entry> cache = new HashMap<>();

    static synchronized Status cached(String id, long now) {
        Entry e = cache.get(id);
        return e != null && now - e.at < TTL_MS && now >= e.at ? e.s : null;
    }
    static synchronized void put(String id, Status s, long now) { cache.put(id, new Entry(s, now)); }

    /** Calls {@code out} on the UI thread: at once with the cached or "Checking…" status, then as probes finish. */
    static void watch(Activity a, String id, Consumer<Status> out) {
        Status hit = cached(id, System.currentTimeMillis());
        out.accept(hit != null ? hit : new Status(CHECKING, "", ""));
        if (hit != null) return;
        new Thread(() -> probe(a, id, st -> {
            put(id, st, System.currentTimeMillis());
            a.runOnUiThread(() -> { if (!a.isFinishing() && !a.isDestroyed()) out.accept(st); });
        }), "fleet-health").start();
    }

    /** Each profile is probed with its own endpoint and key (and its hub's key), never another's. */
    private static void probe(Activity a, String id, Consumer<Status> out) {
        SettingsStore store = new SettingsStore(a);
        SettingsStore.Connection c = store.connection(id);
        if (c == null) return;
        Status st;
        try {
            HermesApi api = ((HermesApi) Backends.create(c.type, c.endpoint, c.key, c.session)).quick();
            JSONObject h;
            try { h = "Hermes".equals(c.type) ? api.healthDetailed() : api.capabilities(); }
            catch (IllegalStateException e) { if (!String.valueOf(e.getMessage()).contains("404")) throw e; h = api.capabilities(); }
            st = classify(h);
        } catch (Exception e) { st = new Status(OFFLINE, "", ""); }
        out.accept(st);
        SettingsStore.Connection hub = store.hubConnection(id);
        if (st.level == OFFLINE || hub == null) return;
        try { out.accept(st.withHint(hint(new LabSummary(new HubApi(hub.endpoint, hub.key).quick().summary())))); }
        catch (Exception ignored) { /* no hub: no hint */ }
    }

    /** Updates chip and hint in place with a brief fade. */
    static void paint(TextView chip, TextView sub, String base, Status st) {
        chip.setAlpha(0.3f);
        chip.setText(st.label());
        chip.setTextColor(st.color());
        chip.setBackground(Ui.round(chip.getContext(), (st.color() & 0xffffff) | 0x26000000, 10));
        chip.animate().alpha(1f).setDuration(250).start();
        if (sub != null) sub.setText(st.hint.isEmpty() ? base : base + "\n" + st.hint);
    }
}
