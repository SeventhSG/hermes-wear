package cloud.stormlabs.hermescompanion;

import static org.junit.Assert.*;
import org.junit.Test;

public class KitWakeTest {
    @Test public void firstPokeIsAlwaysDue() { assertTrue(KitWake.due(1_000, 0)); }

    @Test public void repeatPokesAreHeldBackForThreeMinutes() {
        long t = 10_000_000L;
        assertFalse(KitWake.due(t + 60_000, t));
        assertFalse(KitWake.due(t + KitWake.THROTTLE_MS - 1, t));
        assertTrue(KitWake.due(t + KitWake.THROTTLE_MS, t));
    }

    @Test public void aClockThatMovedBackwardsDoesNotBlockWakingForever() { assertTrue(KitWake.due(5_000, 9_000_000L)); }
}
