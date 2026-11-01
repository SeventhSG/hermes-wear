package cloud.stormlabs.hermescompanion;

import static org.junit.Assert.*;
import org.junit.Test;

public class AlertKeyTest {
    @Test public void recoveredSharesKeyWithAlert() {
        assertEquals(AlertKey.of("Storage /vault 98% full on stormserve"), AlertKey.of("Recovered: Storage /vault 98% full on stormserve"));
        assertEquals("servarr stopped", AlertKey.of("  recovered:  Servarr stopped "));
        assertEquals(AlertKey.of("Storage /vault 91% full on stormserve"), AlertKey.of("Storage /vault 96% full on stormserve"));
    }
    @Test public void recoveredDetection() {
        assertTrue(AlertKey.recovered("Recovered: x"));
        assertFalse(AlertKey.recovered("Storage full"));
        assertFalse(AlertKey.recovered(null));
    }
    @Test public void muteExpiry() {
        assertTrue(AlertKey.muted(2000, 1000));
        assertFalse(AlertKey.muted(1000, 1000));
        assertFalse(AlertKey.muted(0, 1000));
    }
}
