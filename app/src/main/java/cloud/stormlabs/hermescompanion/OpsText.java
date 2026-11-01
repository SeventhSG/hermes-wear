package cloud.stormlabs.hermescompanion;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Pure copy and ordering rules for the System screen and the Dashboard hero: one severity model, human wording first. */
final class OpsText {
    private OpsText() {}

    static final int CALM = 0, WATCH = 1, CRITICAL = 2;

    /** SystemHealth level to the one on-screen severity: OK calm, WARN watch (amber), BAD and DOWN critical (red). */
    static int severity(int level) {
        return level <= SystemHealth.OK ? CALM : level == SystemHealth.WARN ? WATCH : CRITICAL;
    }

    /** Hero state word. */
    static String word(int severity, boolean waiting) {
        if (waiting) return "Checking";
        return severity == CALM ? "Healthy" : severity == WATCH ? "Attention" : "Critical";
    }

    /** "Disk 98% full on /vault · 5 more", or the healthy/loading sentence when nothing is wrong. */
    static String sentence(List<SystemHealth.Reason> reasons, boolean waiting, String healthy) {
        if (reasons.isEmpty()) return waiting ? "Checking Hermes and the host" : healthy;
        String first = reasons.get(0).text;
        return reasons.size() == 1 ? first : first + " · " + (reasons.size() - 1) + " more";
    }

    /** Hermes headline: "Idle", "Working", "Working on 2 tasks". */
    static String hermesHeadline(String activeAgents, String busy) {
        int n = 0;
        try { n = Integer.parseInt(activeAgents == null ? "" : activeAgents.trim()); } catch (NumberFormatException ignored) { }
        if (n > 0) return "Working on " + n + (n == 1 ? " task" : " tasks");
        String b = busy == null ? "" : busy.trim().toLowerCase(Locale.ROOT);
        return b.equals("true") || b.equals("1") || b.equals("yes") ? "Working" : "Idle";
    }

    /** "Gateway running"; empty when the server reports no gateway state. */
    static String gatewayLine(String state) {
        return state == null || state.isEmpty() ? "" : "Gateway " + state.toLowerCase(Locale.ROOT);
    }

    private static final Pattern EXITED = Pattern.compile("^exited \\((-?\\d+)\\)\\s*(.*)$", Pattern.CASE_INSENSITIVE);
    private static final Pattern RESTARTING = Pattern.compile("^restarting \\((-?\\d+)\\)\\s*(.*)$", Pattern.CASE_INSENSITIVE);
    private static final Pattern UP = Pattern.compile("^up\\s+(.*?)\\s*(\\((.*)\\))?$", Pattern.CASE_INSENSITIVE);

    /** Docker's status text in plain words: "Exited (137) 5 days ago" becomes "Stopped 5 days ago · exit 137". Other states pass through. */
    static String dockerStatus(String raw) {
        if (raw == null) return "";
        String s = raw.trim();
        Matcher m = EXITED.matcher(s);
        if (m.matches()) return "Stopped" + sp(m.group(2)) + (m.group(1).equals("0") ? "" : " · exit " + m.group(1));
        m = RESTARTING.matcher(s);
        if (m.matches()) return "Restarting · exit " + m.group(1) + (m.group(2).trim().isEmpty() ? "" : " · " + m.group(2).trim());
        m = UP.matcher(s);
        if (m.matches() && !m.group(1).isEmpty()) return "Running " + m.group(1) + (m.group(3) == null ? "" : " · " + m.group(3).trim());
        if (s.equalsIgnoreCase("created")) return "Created, never started";
        return s;
    }

    private static String sp(String s) { return s == null || s.trim().isEmpty() ? "" : " " + s.trim(); }

    /** Storage percent used; unreachable counts as 100 so it sorts first. */
    static double storagePct(HubApi.PveHost.Storage s) {
        return !s.reachable() ? 100 : s.total > 0 ? 100 * s.used / s.total : 0;
    }

    /** Healthy storage collapses behind "N healthy": reachable and under 80% (the host-meter amber threshold). */
    static boolean storageHealthy(HubApi.PveHost.Storage s) { return s.reachable() && storagePct(s) < 80; }

    /** Worst first; pools with no capacity data are dropped. */
    static List<HubApi.PveHost.Storage> sortStorage(List<HubApi.PveHost.Storage> in) {
        List<HubApi.PveHost.Storage> out = new ArrayList<>();
        for (HubApi.PveHost.Storage s : in) if (!s.reachable() || s.total > 0) out.add(s);
        out.sort((a, b) -> Double.compare(storagePct(b), storagePct(a)));
        return out;
    }

    /** The two things the hero gauge can show: {label, percent-like value 0..100+, display}. Temperature competes as degrees. */
    static final class Worst {
        final String label, display; final double value;
        Worst(String label, double value, String display) { this.label = label; this.value = value; this.display = display; }
    }

    /** The most pressured of CPU %, RAM %, storage % and CPU temperature (°C reads on the same 0-100 scale); null when nothing is known. */
    static Worst worst(double cpu, double mem, double storage, double temp) {
        Worst w = null;
        w = pick(w, "CPU", cpu, "%");
        w = pick(w, "RAM", mem, "%");
        w = pick(w, "Storage", storage, "%");
        return pick(w, "Temp", temp, "°");
    }

    private static Worst pick(Worst cur, String label, double v, String unit) {
        if (Double.isNaN(v) || v <= 0) return cur;
        return cur == null || v > cur.value ? new Worst(label, v, Math.round(v) + unit) : cur;
    }

    /** "morning-briefing couldn't run". */
    static String agentFailure(String name) { return (name == null || name.isEmpty() ? "An agent" : name) + " couldn't run"; }

    /** systemd's "Fri 2026-09-25 09:19:38 EEST" as "6 days" (local time), or "" when it can't be read. */
    static String systemdAge(String since, long nowMs) {
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("(\\d{4}-\\d{2}-\\d{2} \\d{2}:\\d{2}:\\d{2})").matcher(since == null ? "" : since);
        if (!m.find()) return "";
        try {
            long t = new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.ROOT).parse(m.group(1)).getTime();
            long min = Math.max(0, (nowMs - t) / 60000);
            if (min < 60) return min + (min == 1 ? " minute" : " minutes");
            long h = min / 60; if (h < 48) return h + (h == 1 ? " hour" : " hours");
            return (h / 24) + " days";
        } catch (java.text.ParseException e) { return ""; }
    }

    /** One line for a systemd unit: "Running 6 days" when healthy, else state, sub-state and age. */
    static String systemdStatus(String state, String sub, String since, long nowMs) {
        String age = systemdAge(since, nowMs);
        if ("active".equals(state) && "running".equals(sub) && !age.isEmpty()) return "Running " + age;
        return state + (sub == null || sub.isEmpty() ? "" : " \u00b7 " + sub) + (age.isEmpty() ? "" : " \u00b7 for " + age);
    }

    /** "Backup of servarr", "Start jellyfin", "Update package lists" for a Proxmox task type and id. */
    static String taskLabel(String type, String id, java.util.Map<String, String> names) {
        String who = id == null || id.isEmpty() ? "" : names != null && names.containsKey(id) ? names.get(id) : id;
        String t = type == null ? "" : type;
        String verb;
        switch (t) {
            case "vzdump": verb = "Backup"; break;
            case "qmstart": case "vzstart": verb = "Start"; break;
            case "qmstop": case "vzstop": verb = "Force stop"; break;
            case "qmshutdown": case "vzshutdown": verb = "Shut down"; break;
            case "qmreboot": case "vzreboot": verb = "Reboot"; break;
            case "qmcreate": case "vzcreate": verb = "Create"; break;
            case "qmdestroy": case "vzdestroy": verb = "Delete"; break;
            case "qmigrate": case "vzmigrate": verb = "Migrate"; break;
            case "qmsnapshot": case "vzsnapshot": verb = "Snapshot"; break;
            case "qmrollback": case "vzrollback": verb = "Roll back"; break;
            case "qmdelsnapshot": case "vzdelsnapshot": verb = "Delete snapshot of"; break;
            case "startall": return "Start all guests";
            case "stopall": return "Stop all guests";
            case "aptupdate": return "Update package lists";
            case "srvreload": case "srvrestart": return "Restart " + (who.isEmpty() ? "a service" : who);
            default: verb = t.isEmpty() ? "Task" : t;
        }
        return who.isEmpty() ? verb : verb + ("Backup".equals(verb) ? " of " : " ") + who;
    }
}
