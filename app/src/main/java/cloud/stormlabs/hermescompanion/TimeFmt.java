package cloud.stormlabs.hermescompanion;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Locale;

/**
 * Turns a server timestamp (ISO-8601, or a raw epoch in seconds/millis) into a short, human
 * relative label. Every parse is wrapped so a timestamp the server changes shape on never crashes
 * a screen — it just falls back to showing the raw text.
 */
public final class TimeFmt {
    private TimeFmt() {}

    public static String relative(String raw) {
        return relativeAt(raw, System.currentTimeMillis(), false);
    }

    public static String relativePast(String raw) {
        return relativeAt(raw, System.currentTimeMillis(), true);
    }

    public static String lastSeen(String raw) {
        return lastSeenAt(raw, System.currentTimeMillis());
    }

    static String lastSeenAt(String raw, long now) {
        String label = relativeAt(raw, now, true);
        try {
            long epoch = toEpochMillis(raw.trim());
            if (now - epoch > 3_600_000L) {
                String local = DateTimeFormatter.ofPattern("HH:mm", Locale.getDefault())
                        .withZone(ZoneId.systemDefault()).format(Instant.ofEpochMilli(epoch));
                return label + " · " + local;
            }
        } catch (Exception ignored) { }
        return label;
    }

    static String relativeAt(String raw, long now, boolean lastSeen) {
        if (raw == null) return "";
        String v = raw.trim();
        if (v.isEmpty()) return "";
        try {
            long epochMillis = toEpochMillis(v);
            long diffMs = epochMillis - now;
            if (lastSeen && diffMs > 0) return "just now";
            boolean future = diffMs > 0;
            long abs = Math.abs(diffMs);
            long sec = abs / 1000, min = sec / 60, hr = min / 60, day = hr / 24, week = day / 7;
            String amount;
            if (abs <= 90_000L) return "just now";
            else if (min < 60) amount = min + "m";
            else if (hr < 24) amount = hr + "h";
            else if (day < 7) amount = day + "d";
            else if (week < 8) amount = week + "w";
            else amount = (day / 30) + "mo";
            return future ? "in " + amount : amount + " ago";
        } catch (Exception e) {
            return v.length() > 28 ? v.substring(0, 28) : v;
        }
    }

    /** Epoch millis of a server timestamp, or -1 when it is empty or cannot be read (for sorting). */
    static long epochOrNeg(String raw) {
        if (raw == null || raw.trim().isEmpty()) return -1;
        try { return toEpochMillis(raw.trim()); } catch (Exception e) { return -1; }
    }

    private static long toEpochMillis(String v) {
        if (v.matches("\\d+(\\.\\d+)?")) {
            double d = Double.parseDouble(v);
            // Server timestamps in seconds are ~1.7e9; in millis they're ~1.7e12.
            return (long) (d > 3_000_000_000.0 ? d : d * 1000);
        }
        try { return Instant.parse(v).toEpochMilli(); }
        catch (Exception e) { return OffsetDateTime.parse(v).toInstant().toEpochMilli(); }
    }

    public static String humanizeKey(String key) {
        if (key == null || key.isEmpty()) return "";
        String[] parts = key.replace('_', ' ').replace('-', ' ').trim().split("\\s+");
        StringBuilder b = new StringBuilder();
        for (String p : parts) {
            if (p.isEmpty()) continue;
            if (b.length() > 0) b.append(' ');
            b.append(Character.toUpperCase(p.charAt(0))).append(p.length() > 1 ? p.substring(1).toLowerCase(Locale.ROOT) : "");
        }
        return b.toString();
    }
}
