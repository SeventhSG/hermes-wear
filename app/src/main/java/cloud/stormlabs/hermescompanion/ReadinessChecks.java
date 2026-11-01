package cloud.stormlabs.hermescompanion;

import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;

final class ReadinessChecks {
    private ReadinessChecks() { }

    static List<String> lines(JSONObject checks) {
        List<String> lines = new ArrayList<>();
        if (checks == null) return lines;
        Iterator<String> names = checks.keys();
        while (names.hasNext()) {
            String name = names.next();
            JSONObject check = checks.optJSONObject(name);
            if (check == null) continue;
            Object status = check.opt("status");
            if (!(status instanceof String) || ((String) status).isEmpty() || "ok".equalsIgnoreCase((String) status)) continue;

            if ("disk".equals(name) && check.opt("used_percent") instanceof Number
                    && check.opt("free_bytes") instanceof Number) {
                double used = ((Number) check.opt("used_percent")).doubleValue();
                double free = ((Number) check.opt("free_bytes")).doubleValue();
                if (Double.isFinite(used) && Double.isFinite(free) && free >= 0) {
                    double unit = free >= 1024.0 * 1024 * 1024 ? 1024.0 * 1024 * 1024 : 1024.0 * 1024;
                    lines.add(String.format(Locale.US, "Disk · %.1f%% used, %.1f %s free",
                            used, free / unit, unit > 1024 * 1024 ? "GB" : "MB"));
                    continue;
                }
            }

            List<String> details = new ArrayList<>();
            Iterator<String> keys = check.keys();
            while (keys.hasNext()) {
                String key = keys.next();
                Object value = check.opt(key);
                if (!"status".equals(key) && value != null && value != JSONObject.NULL)
                    details.add(key.replace('_', ' ') + " " + value);
            }
            String title = name.replace('_', ' ');
            if (!title.isEmpty()) title = title.substring(0, 1).toUpperCase(Locale.ROOT) + title.substring(1);
            lines.add(title + (details.isEmpty() ? "" : " · " + String.join(", ", details)));
        }
        return lines;
    }
}
