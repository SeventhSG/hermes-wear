package cloud.stormlabs.hermescompanion;

/** Homelab alert identity: "Recovered: X" shares a key with the earlier alert titled "X". */
final class AlertKey {
    private AlertKey() {}
    static final String RECOVERED = "Recovered:";

    static boolean recovered(String title) { return title != null && title.trim().regionMatches(true, 0, RECOVERED, 0, RECOVERED.length()); }

    static String of(String title) {
        String t = title == null ? "" : title.trim();
        if (recovered(t)) t = t.substring(RECOVERED.length()).trim();
        // Numbers change between alerts for one condition ("91% full" then "96% full"): drop them from the key.
        return t.toLowerCase(java.util.Locale.ROOT).replaceAll("\\d+([.,]\\d+)?", "#");
    }

    static boolean muted(long until, long now) { return now < until; }
}
