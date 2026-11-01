package cloud.stormlabs.hermescompanion;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.time.Instant;

public class TimeFmtTest {
    private static final long NOW = 1_780_000_000_000L;

    @Test public void clockSkewIsJustNow() {
        assertEquals("just now", TimeFmt.relativeAt("2026-09-29T13:35:16.172328+00:00",
                Instant.parse("2026-09-29T13:35:10Z").toEpochMilli(), true));
        assertEquals("just now", TimeFmt.relativeAt(Instant.ofEpochMilli(NOW + 5_000).toString(), NOW, false));
        assertEquals("just now", TimeFmt.relativeAt(Instant.ofEpochMilli(NOW - 90_000).toString(), NOW, false));
        assertEquals("just now", TimeFmt.relativeAt(Instant.ofEpochMilli(NOW + 90_000).toString(), NOW, false));
    }

    @Test public void scheduledFutureKeepsFutureLabelButLastSeenDoesNot() {
        String future = Instant.ofEpochMilli(NOW + 120_000).toString();
        assertEquals("in 2m", TimeFmt.relativeAt(future, NOW, false));
        assertEquals("just now", TimeFmt.relativeAt(future, NOW, true));
        assertEquals("2m ago", TimeFmt.relativeAt(Instant.ofEpochMilli(NOW - 120_000).toString(), NOW, true));
    }

    @Test public void heartbeatShowsLocalTimeOnlyAfterOneHour() {
        assertEquals("1h ago", TimeFmt.lastSeenAt(Instant.ofEpochMilli(NOW - 3_600_000).toString(), NOW));
        String older = TimeFmt.lastSeenAt(Instant.ofEpochMilli(NOW - 3_600_001).toString(), NOW);
        assertTrue(older.matches("1h ago · \\d{2}:\\d{2}"));
        assertFalse(older.contains("T"));
    }
}
