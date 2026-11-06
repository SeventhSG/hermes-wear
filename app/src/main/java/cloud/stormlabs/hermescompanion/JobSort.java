package cloud.stormlabs.hermescompanion;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/** How the Agents screen orders scheduled jobs. Pure, so the ordering rules are tested without a device. */
final class JobSort {
    private JobSort() { }

    enum Mode {
        SMART("Needs attention first"), NEXT("Next run"), LAST("Last run"), NAME("Name");
        final String label;
        Mode(String label) { this.label = label; }
        static Mode of(String id) {
            for (Mode m : values()) if (m.name().equalsIgnoreCase(id)) return m;
            return SMART;
        }
    }

    static final int FAILING = 0, RUNNING = 1, SCHEDULED = 2, PAUSED = 3, FINISHED = 4;
    static final String[] HEADERS = {"NEEDS ATTENTION", "RUNNING NOW", "UPCOMING", "PAUSED", "FINISHED"};

    /** Where a job belongs: failed last time, running, waiting for its next run, switched off, or done for good. */
    static int bucket(HermesApi.Job j) {
        String state = j.state.toLowerCase(Locale.ROOT), last = j.lastStatus.toLowerCase(Locale.ROOT);
        if (j.paused()) return PAUSED;
        if (state.contains("complete") || state.contains("done")) return FINISHED;
        if (state.contains("error") || state.contains("fail") || !j.lastError.isEmpty()
                || last.contains("error") || last.contains("fail")) return FAILING;
        if (state.contains("run")) return RUNNING;
        return SCHEDULED;
    }

    private static final Comparator<HermesApi.Job> BY_NAME = (a, b) -> a.title().compareToIgnoreCase(b.title());

    /** Soonest first; jobs without a next run go last. */
    private static final Comparator<HermesApi.Job> BY_NEXT = (a, b) -> {
        long x = TimeFmt.epochOrNeg(a.nextRunAt), y = TimeFmt.epochOrNeg(b.nextRunAt);
        if (x < 0 && y < 0) return BY_NAME.compare(a, b);
        if (x < 0) return 1;
        if (y < 0) return -1;
        int c = Long.compare(x, y);
        return c != 0 ? c : BY_NAME.compare(a, b);
    };

    /** Most recent first; jobs that never ran go last. */
    private static final Comparator<HermesApi.Job> BY_LAST = (a, b) -> {
        long x = TimeFmt.epochOrNeg(a.lastRunAt), y = TimeFmt.epochOrNeg(b.lastRunAt);
        if (x < 0 && y < 0) return BY_NAME.compare(a, b);
        if (x < 0) return 1;
        if (y < 0) return -1;
        int c = Long.compare(y, x);
        return c != 0 ? c : BY_NAME.compare(a, b);
    };

    static List<HermesApi.Job> sort(List<HermesApi.Job> jobs, Mode mode) {
        List<HermesApi.Job> out = new ArrayList<>(jobs);
        switch (mode) {
            case NEXT:
                // Switched-off jobs keep a stale next run: they sink below everything that will actually fire.
                Collections.sort(out, (a, b) -> {
                    boolean pa = bucket(a) >= PAUSED, pb = bucket(b) >= PAUSED;
                    return pa != pb ? (pa ? 1 : -1) : BY_NEXT.compare(a, b);
                });
                break;
            case LAST: Collections.sort(out, BY_LAST); break;
            case NAME: Collections.sort(out, BY_NAME); break;
            default:
                Collections.sort(out, (a, b) -> {
                    int ba = bucket(a), bb = bucket(b);
                    if (ba != bb) return Integer.compare(ba, bb);
                    switch (ba) {
                        case FAILING: case FINISHED: return BY_LAST.compare(a, b);
                        case SCHEDULED: return BY_NEXT.compare(a, b);
                        default: return BY_NAME.compare(a, b);
                    }
                });
        }
        return out;
    }

    /** Section headers show only in the grouped ordering. */
    static boolean grouped(Mode mode) { return mode == Mode.SMART; }
}
