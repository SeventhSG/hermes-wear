package cloud.stormlabs.hermescompanion;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class AppLockTest {
    @Test public void locksOnColdStartAndAfterTheGracePeriod() {
        assertTrue(AppLock.shouldLock(true, 0, 1_000));                       // cold start
        assertFalse(AppLock.shouldLock(false, 10_000, 10_000 + 30_000));       // back within a minute
        assertTrue(AppLock.shouldLock(false, 10_000, 10_000 + AppLock.GRACE_MS)); // away a minute or more
        assertFalse(AppLock.shouldLock(false, 0, 99_000));                     // never left
    }
}
