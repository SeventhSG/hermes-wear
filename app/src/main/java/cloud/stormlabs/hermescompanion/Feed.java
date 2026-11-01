package cloud.stormlabs.hermescompanion;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Pure helpers for the dashboard Activity feed. */
final class Feed {
    private Feed() {}

    /** An event plus how many consecutive same-title events it stands for. */
    static final class Row {
        final HubApi.Event e; int count = 1;
        Row(HubApi.Event e) { this.e = e; }
    }

    /** Newest-first events -> rows; consecutive same titles merge (the newest represents the run). */
    static List<Row> collapse(List<HubApi.Event> events) {
        List<Row> out = new ArrayList<>();
        for (HubApi.Event e : events) {
            Row last = out.isEmpty() ? null : out.get(out.size() - 1);
            if (last != null && last.e.title.equals(e.title)) last.count++; else out.add(new Row(e));
        }
        return out;
    }

    /** kind: "all", "alert", "job" or "info" (info includes recovered). */
    static List<Row> filter(List<Row> rows, String kind) {
        List<Row> out = new ArrayList<>();
        for (Row r : rows) if ("all".equals(kind) || kind.equals(kindOf(r.e))) out.add(r);
        return out;
    }

    static String kindOf(HubApi.Event e) {
        String t = e.type.toLowerCase(Locale.ROOT);
        return t.equals("recovered") ? "info" : t;
    }

    /** "now", "4 min ago", "3 h ago", "2 d ago" from epoch seconds. */
    static String ago(double ts, long nowMs) {
        if (ts <= 0) return "";
        long min = Math.max(0, nowMs - (long) (ts * 1000)) / 60_000;
        return min < 1 ? "now" : min < 60 ? min + " min ago" : min < 1440 ? min / 60 + " h ago" : min / 1440 + " d ago";
    }
}
