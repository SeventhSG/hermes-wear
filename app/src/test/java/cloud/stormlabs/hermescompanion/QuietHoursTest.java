package cloud.stormlabs.hermescompanion;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class QuietHoursTest {
    @Test public void windowWrapsPastMidnight() {
        assertTrue(Notifier.quietNow(23, 7, 23));
        assertTrue(Notifier.quietNow(23, 7, 2));
        assertFalse(Notifier.quietNow(23, 7, 7));
        assertFalse(Notifier.quietNow(23, 7, 15));
        assertTrue(Notifier.quietNow(0, 8, 5));
        assertFalse(Notifier.quietNow(0, 8, 23));
        assertFalse(Notifier.quietNow(5, 5, 5)); // empty window
    }

    @Test public void onlyPriorityFiveBreaksThrough() {
        assertTrue(Notifier.critical("5"));
        assertFalse(Notifier.critical("4"));
        assertFalse(Notifier.critical(""));
    }
}
