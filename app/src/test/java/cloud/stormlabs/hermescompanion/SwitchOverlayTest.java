package cloud.stormlabs.hermescompanion;

import static org.junit.Assert.*;

import org.junit.Test;

public class SwitchOverlayTest {
    @Test public void accentIsStableAndInPalette() {
        for (String id : new String[]{"a", "worker", "p-123", "", null, "long-profile-id-é"}) {
            int c = SwitchOverlay.accent(id);
            assertEquals(c, SwitchOverlay.accent(id));
            boolean found = false;
            for (int p : SwitchOverlay.PALETTE) found |= p == c;
            assertTrue(found);
        }
    }

    @Test public void mixBlendsPerChannel() {
        assertEquals(0xff000000, SwitchOverlay.mix(0xff000000, 0xffffffff, 0f));
        assertEquals(0xffffffff, SwitchOverlay.mix(0xff000000, 0xffffffff, 1f));
        assertEquals(0xff804020, SwitchOverlay.mix(0xff000000, 0xffff8040, .5f));
    }
}
