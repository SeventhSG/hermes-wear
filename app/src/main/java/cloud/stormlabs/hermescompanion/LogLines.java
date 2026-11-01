package cloud.stormlabs.hermescompanion;

import java.util.List;
import java.util.Locale;

/** Pure helpers for the live service-log view. */
final class LogLines {
    static final int MAX = 2000, NORMAL = 0, WARN = 1, ERROR = 2;
    private LogLines() {}

    static int level(String line) {
        String l = line.toLowerCase(Locale.ROOT);
        if (l.contains("error") || l.contains("fail") || l.contains("exception") || l.contains("traceback") || line.contains(" E ")) return ERROR;
        return l.contains("warn") ? WARN : NORMAL;
    }

    /** Plain case-insensitive substring; blank filter matches all. */
    static boolean matches(String line, String filter) {
        return filter.isEmpty() || line.toLowerCase(Locale.ROOT).contains(filter.toLowerCase(Locale.ROOT));
    }

    /** Drops oldest lines beyond max; true if anything was dropped. */
    static boolean cap(List<String> lines, int max) {
        int extra = lines.size() - max;
        if (extra > 0) lines.subList(0, extra).clear();
        return extra > 0;
    }

    /** Keeps the old cursor when a reply carries none, so an empty poll never rewinds. */
    static String cursor(String old, String fresh) { return fresh == null || fresh.isEmpty() ? old : fresh; }
}
