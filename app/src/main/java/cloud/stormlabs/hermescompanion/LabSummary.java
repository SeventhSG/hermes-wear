package cloud.stormlabs.hermescompanion;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONObject;

/** Homelab roll-up from the hub's /ops/summary, cached in prefs so the widget and tile render instantly. */
final class LabSummary {
    static final int OK = 0, WARN = 1, BAD = 2;
    final String node, problem;
    final double cpu, mem, disk, temp, worstPct;
    final int guestsUp, guests, nodesUp, nodes, badServices, problems;
    final long updatedMs;

    LabSummary(JSONObject o) {
        JSONObject h = o.optJSONObject("host"), w = o.optJSONObject("worst_storage");
        if (h == null) h = new JSONObject();
        node = HermesApi.clean(h.optString("node", ""));
        cpu = h.optDouble("cpu_pct", Double.NaN); mem = h.optDouble("mem_pct", Double.NaN);
        disk = h.optDouble("root_pct", Double.NaN); temp = h.optDouble("temp_c", Double.NaN);
        worstPct = w == null ? 0 : w.optDouble("pct", 0);
        guestsUp = o.optInt("guests_running"); guests = o.optInt("guests_total");
        nodesUp = o.optInt("nodes_online"); nodes = o.optInt("nodes_total");
        badServices = o.optInt("services_bad");
        JSONArray p = o.optJSONArray("problems");
        problems = p == null ? 0 : p.length();
        problem = problems == 0 ? "" : HermesApi.clean(p.optString(0, ""));
        updatedMs = (long) (o.optDouble("updated", 0) * 1000);
    }

    /** Red for anything down or critical, amber for any other listed problem. */
    int level() {
        // One node down in a multi-node cluster is a warning (it can stay off for days); all down is red.
        if (badServices > 0 || nodes > 0 && nodesUp == 0 || temp >= 90 || worstPct >= 95) return BAD;
        return problems > 0 ? WARN : OK;
    }

    static String pct(double v) { return Double.isNaN(v) ? "-" : Math.round(v) + "%"; }

    private static SharedPreferences prefs(Context c) { return c.getSharedPreferences("lab_summary", Context.MODE_PRIVATE); }

    /** Last cached summary, or null when none was fetched yet. */
    static LabSummary cached(Context c) {
        try { return new LabSummary(new JSONObject(prefs(c).getString("json", ""))); } catch (Exception e) { return null; }
    }

    /** Fetches and caches; blocking, call off the main thread. False when no hub or the call failed (cache kept). */
    static boolean refresh(Context c) {
        try {
            SettingsStore s = new SettingsStore(c);
            if (s.hubUrl().isEmpty()) return false;
            JSONObject o = new HubApi(s.hubUrl(), s.hubKey()).quick().summary();
            prefs(c).edit().putString("json", o.toString()).apply();
            HermesWidgetProvider.update(c);
            return true;
        } catch (Exception e) { return false; }
    }
}
