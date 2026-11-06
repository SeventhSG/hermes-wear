package cloud.stormlabs.hermescompanion;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class SnapshotNameTest {
    @Test public void matchesProxmoxRules() {
        assertTrue(HubApi.snapshotName("phone-1001-0212"));
        assertTrue(HubApi.snapshotName("before_update"));
        assertFalse(HubApi.snapshotName("1bad"));
        assertFalse(HubApi.snapshotName("a"));
        assertFalse(HubApi.snapshotName("has space"));
        assertFalse(HubApi.snapshotName(null));
    }
}
