package cloud.stormlabs.hermescompanion;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class WhatsNewTest {
    @Test public void onlyOnUpdatesOncePerVersion() {
        assertFalse(WhatsNew.shouldShow(0, 39, false)); // fresh install
        assertTrue(WhatsNew.shouldShow(0, 39, true));   // upgraded from a build without the sheet
        assertTrue(WhatsNew.shouldShow(38, 39, false));
        assertFalse(WhatsNew.shouldShow(39, 39, true)); // already seen
    }
}
