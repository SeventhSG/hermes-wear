package cloud.stormlabs.hermescompanion;

import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class PresenceDecisionTest {
    @Test public void repeatedStatesDoNotRun() {
        assertFalse(PresenceDecision.shouldRun(true, 1, 0, 0, 1_000_000));
        assertFalse(PresenceDecision.shouldRun(false, 0, 0, 0, 1_000_000));
    }

    @Test public void reportRightAfterRegisteringIsOnlyTheCurrentState() {
        long reg = 1_000_000;
        assertFalse(PresenceDecision.shouldRun(true, -1, 0, reg, reg + 2_000));
        assertFalse(PresenceDecision.shouldRun(true, 0, 0, reg, reg + 29_999));
    }

    @Test public void firstRealArrivalAfterRegisteringRuns() {
        long reg = 1_000_000;
        assertTrue(PresenceDecision.shouldRun(true, -1, 0, reg, reg + 3_600_000));
    }

    @Test public void transitionRunsOnlyAfterCooldown() {
        long lastRun = 1_000_000;
        assertFalse(PresenceDecision.shouldRun(false, 1, lastRun, 0, lastRun + 599_999));
        assertTrue(PresenceDecision.shouldRun(false, 1, lastRun, 0, lastRun + 600_000));
        assertTrue(PresenceDecision.shouldRun(true, 0, 0, 0, lastRun));
    }
}
