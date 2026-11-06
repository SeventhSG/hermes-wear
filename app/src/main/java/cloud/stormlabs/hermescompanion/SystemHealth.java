package cloud.stormlabs.hermescompanion;

import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Pure rules behind the System screen's summary: what is wrong, how badly, and where to look. */
final class SystemHealth {
    private SystemHealth() {}

    static final int OK = 0, WARN = 1, BAD = 2, DOWN = 3, NONE = -1;

    static final class Reason {
        final int level; final String text, section; final HubApi.Service service;
        Reason(int level, String text, String section, HubApi.Service service) {
            this.level = level; this.text = text; this.section = section; this.service = service;
        }
    }

    /** 0 all good, 1-2 needs attention, 3 down: the worst reason decides. */
    static int overall(List<Reason> reasons) {
        int worst = OK;
        for (Reason r : reasons) worst = Math.max(worst, r.level);
        return worst;
    }

    static String label(int overall) {
        return overall == OK ? "All good" : overall == DOWN ? "Down" : "Needs attention";
    }

    /**
     * Every concrete problem, worst first. A null source with no error means "not loaded yet" and
     * contributes nothing. Readiness disk is skipped when host metrics exist: one disk number only.
     */
    /** Proxmox host problems: storage nearly full or unreachable, hot CPU, RAM exhausted. */
    static List<Reason> pveReasons(HubApi.PveHost h) {
        List<Reason> out = new ArrayList<>();
        if (h == null) return out;
        for (HubApi.PveHost.Storage s : h.storage) {
            if (!s.reachable()) { out.add(new Reason(WARN, "Storage " + s.name + " unreachable on " + h.node, "pvehost", null)); continue; }
            double pct = s.total > 0 ? 100 * s.used / s.total : 0;
            if (pct >= 90) out.add(new Reason(pct >= 95 ? BAD : WARN, String.format(java.util.Locale.US, "%s %.0f%% full on %s", s.name, pct, h.node), "pvehost", null));
        }
        double t = h.cpuTemp();
        if (!Double.isNaN(t) && t >= 80) out.add(new Reason(t >= 90 ? BAD : WARN, String.format(java.util.Locale.US, "%s CPU at %.0f °C", h.node, t), "pvehost", null));
        if (h.memTotal > 0 && h.memAvail / h.memTotal < 0.05) out.add(new Reason(WARN, h.node + " is almost out of RAM", "pvehost", null));
        return out;
    }

    /** Backup age: green under 2 days, amber under 7, red older or failed; never backed up (ageDays < 0) is NONE (grey). */
    static int backupLevel(double ageDays, String status) {
        if (failed(status)) return BAD;
        return ageDays < 0 ? NONE : ageDays >= 7 ? BAD : ageDays >= 2 ? WARN : OK;
    }

    /** A finished vzdump that did not end "OK"; empty (still running) is not a failure. */
    static boolean failed(String status) { return !status.isEmpty() && !status.equalsIgnoreCase("OK"); }

    /** Round trip: green under 100 ms, amber under 300, red slower or unreachable (ms < 0). */
    /** A sync device unseen for over a week has stopped syncing; over a day is worth a glance. */
    static int syncDeviceLevel(boolean connected, long lastSeenMs, long now) {
        if (connected) return OK;
        if (lastSeenMs <= 0) return WARN;
        long days = (now - lastSeenMs) / 86_400_000L;
        return days >= 7 ? BAD : days >= 1 ? WARN : OK;
    }

    /** Let's Encrypt renews at 30 days, so under 14 means renewal has stopped; invalid or expired is bad. */
    static int certLevel(int days, String error) {
        if (error != null && error.startsWith("invalid")) return BAD;
        if (days == Integer.MIN_VALUE) return WARN;
        return days < 7 ? BAD : days < 14 ? WARN : OK;
    }

    static String certText(int days, String error) {
        if (error != null && !error.isEmpty()) return error.startsWith("invalid") ? "Invalid certificate" : "Couldn\u2019t check";
        return days < 0 ? "Expired" : days == 0 ? "Expires today" : days + (days == 1 ? " day left" : " days left");
    }

    static int latencyLevel(int ms) { return ms < 0 || ms >= 300 ? BAD : ms >= 100 ? WARN : OK; }

    /** Online first, then name order. */
    static void sortPeers(List<HubApi.Peer> peers) {
        peers.sort((a, b) -> a.online != b.online ? (a.online ? -1 : 1) : a.name.compareToIgnoreCase(b.name));
    }

    /** A failed last backup is a WARN; a guest never backed up is not an alert. */
    static List<Reason> backupReasons(HubApi.Backups b) {
        List<Reason> out = new ArrayList<>();
        if (b != null) for (String broken : b.brokenJobs) out.add(new Reason(BAD, "Backups will fail: " + broken, "backups", null));
        if (b != null) for (HubApi.BackupGuest g : b.guests)
            if (failed(g.status)) out.add(new Reason(WARN, "Last backup of " + g.name + " failed (" + g.status + ")", "backups", null));
        return out;
    }

    /** Reboot pending or security updates waiting are WARNs. */
    static List<Reason> updateReasons(HubApi.Updates u) {
        List<Reason> out = new ArrayList<>();
        if (u != null) for (HubApi.UpdHost h : u.hosts) {
            if (h.reboot) out.add(new Reason(WARN, h.name + " needs a reboot", "updates", null));
            if (h.security > 0) out.add(new Reason(WARN, h.name + " has " + h.security + " security updates", "updates", null));
        }
        return out;
    }

    static List<Reason> reasons(JSONObject health, String healthError, HubApi.Services ops, String opsError, int tokenBudget) {
        List<Reason> out = new ArrayList<>();
        if (healthError != null) out.add(new Reason(DOWN, "Agent unreachable: " + healthError, "hermes", null));
        if (health != null) {
            String gateway = text(health, "gateway_state", "gateway");
            int g = stateLevel(gateway);
            if (g == DOWN) out.add(new Reason(DOWN, "Gateway " + gateway, "hermes", null));
            else if (g == BAD) out.add(new Reason(BAD, "Gateway " + gateway, "hermes", null));
            JSONObject readiness = health.optJSONObject("readiness");
            List<String> lines = readinessLines(readiness, ops != null);
            for (String line : lines) out.add(new Reason(WARN, line, "hermes", null));
            String status = text(health, "status", "state");
            if (lines.isEmpty() && g == OK && !status.isEmpty() && stateLevel(status) != OK) {
                // Say which checks failed; a disk-only failure is already listed as the host disk, so don't repeat it.
                List<String> failing = failingChecks(readiness);
                boolean diskOnly = !failing.isEmpty() && ops != null && failing.size() == 1 && failing.get(0).equals("disk");
                if (!diskOnly) out.add(new Reason(stateLevel(status), "Hermes reports " + status
                        + (failing.isEmpty() ? "" : " (" + String.join(", ", failing).replace('_', ' ') + ")"), "hermes", null));
            }
            JSONObject metrics = health.optJSONObject("metrics_today");
            double tokens = metrics == null ? 0 : number(metrics, "tokens", "total_tokens", "tokens_total");
            if (tokenBudget > 0 && tokens > tokenBudget)
                out.add(new Reason(BAD, String.format(Locale.US, "Daily token budget exceeded · %,.0f / %,d", tokens, tokenBudget), "usage", null));
            JSONObject platforms = health.optJSONObject("platforms");
            if (platforms != null) for (String name : HermesApi.keys(platforms)) {
                if (platformLevel(platformState(platforms, name)) == BAD)
                    out.add(new Reason(WARN, TimeFmt.humanizeKey(name) + " " + platformState(platforms, name), "platforms", null));
            }
        }
        if (opsError != null) out.add(new Reason(WARN, "Hub unreachable: " + opsError, "host", null));
        if (ops != null) {
            HubApi.Host h = ops.host;
            double disk = HostThresholds.ratio(h.diskUsedGb, h.diskTotalGb), ram = HostThresholds.ratio(h.memUsedMb, h.memTotalMb);
            addMeter(out, HostThresholds.percent(disk), String.format(Locale.US, "Disk %.0f%% full on %s", disk, h.hostname));
            addMeter(out, HostThresholds.percent(ram), String.format(Locale.US, "RAM %.0f%% used on %s", ram, h.hostname));
            addMeter(out, HostThresholds.percent(h.cpuPct), String.format(Locale.US, "CPU %.0f%% on %s", h.cpuPct, h.hostname));
            for (HubApi.Service s : ops.services) {
                int level = serviceLevel(s.state);
                if (level != OK) out.add(new Reason(level, s.name + " " + ("docker".equalsIgnoreCase(s.kind) ? OpsText.dockerStatus(s.state) : s.state + (s.sub.isEmpty() ? "" : " · " + s.sub)), "services", s));
            }
        }
        // Stable: equal levels keep source order (Hermes, usage, platforms, host, services).
        out.sort((a, b) -> b.level - a.level);
        return out;
    }

    private static void addMeter(List<Reason> out, int level, String text) {
        if (level != OK) out.add(new Reason(level, text, "host", null));
    }

    /** Readiness explanation lines; the disk line is dropped when the host meter shows disk instead. */
    /** Names of readiness checks whose status isn't ok ("disk", "model"). */
    static List<String> failingChecks(JSONObject readiness) {
        List<String> out = new ArrayList<>();
        JSONObject checks = readiness == null ? null : readiness.optJSONObject("checks");
        if (checks != null) for (String k : HermesApi.keys(checks)) {
            JSONObject c = checks.optJSONObject(k);
            if (c != null && stateLevel(c.optString("status")) != OK) out.add(k);
        }
        return out;
    }

    static List<String> readinessLines(JSONObject readiness, boolean hostDisk) {
        List<String> out = new ArrayList<>();
        for (String line : ReadinessChecks.lines(readiness == null ? null : readiness.optJSONObject("checks")))
            if (!(hostDisk && line.startsWith("Disk"))) out.add(line.startsWith("Disk") ? line + " (Hermes server)" : line);
        return out;
    }

    /** Gateway / overall status words: OK when running, DOWN when stopped or failed, BAD otherwise. */
    static int stateLevel(String state) {
        String s = state == null ? "" : state.toLowerCase(Locale.ROOT);
        if (s.isEmpty()) return OK;
        if (s.matches(".*(stop|fail|down|dead|error|offline|false).*")) return DOWN;
        if (s.matches(".*(not.?ready|unhealthy|degraded).*")) return BAD;
        return s.matches(".*(running|ok|ready|healthy|online|up|connected|true).*") ? OK : BAD;
    }

    /** Service dot: running OK, starting WARN, anything else (failed, inactive, dead) BAD. */
    static int serviceLevel(String state) {
        String s = state == null ? "" : state.toLowerCase(Locale.ROOT).trim();
        // Docker reports "Up 4 days (healthy)", "Restarting (1) …", "Exited (137) …", "Created".
        if (s.startsWith("up ") || s.equals("up")) return s.contains("unhealthy") ? BAD : s.contains("starting") ? WARN : OK;
        if (s.startsWith("restarting")) return WARN;
        if (s.equals("created") || s.startsWith("exited (0)")) return WARN;
        return s.equals("active") || s.equals("running") ? OK : s.equals("activating") || s.equals("reloading") || s.equals("restarting") ? WARN : BAD;
    }

    static int platformLevel(String state) {
        String s = state.toLowerCase(Locale.ROOT);
        if (s.contains("error") || s.contains("fail") || s.contains("disconnect")) return BAD;
        return s.contains("connect") || s.contains("run") || s.contains("online") || s.contains("ok") ? OK : WARN;
    }

    static String platformState(JSONObject platforms, String name) {
        Object raw = platforms.opt(name);
        return raw instanceof JSONObject ? text((JSONObject) raw, "state", "status") : String.valueOf(raw);
    }

    /** Failed first, then starting, then running; name order within a group. */
    static List<HubApi.Service> sortServices(List<HubApi.Service> services) {
        List<HubApi.Service> out = new ArrayList<>(services);
        out.sort((a, b) -> {
            int d = serviceLevel(b.state) - serviceLevel(a.state);
            return d != 0 ? d : a.name.compareToIgnoreCase(b.name);
        });
        return out;
    }

    static String uptime(long seconds) {
        long days = seconds / 86400, hours = seconds % 86400 / 3600, minutes = seconds % 3600 / 60;
        return days > 0 ? days + "d " + hours + "h" : hours > 0 ? hours + "h " + minutes + "m" : minutes + "m";
    }

    static double number(JSONObject o, String... keys) {
        for (String key : keys) if (o.has(key)) return o.optDouble(key, 0);
        return 0;
    }

    /** NaN when absent: cost is only shown if the server reports it. */
    static double cost(JSONObject metrics) {
        for (String key : new String[]{"cost_usd", "estimated_cost_usd", "cost"}) if (metrics.has(key)) return metrics.optDouble(key, Double.NaN);
        return Double.NaN;
    }

    static String text(JSONObject o, String... keys) {
        for (String k : keys) {
            String v = HermesApi.clean(o.optString(k, ""));
            if (!v.isEmpty()) return v;
        }
        return "";
    }
}
