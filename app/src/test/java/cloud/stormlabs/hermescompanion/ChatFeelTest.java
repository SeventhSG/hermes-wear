package cloud.stormlabs.hermescompanion;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class ChatFeelTest {
    @Test public void badgeCapsAtNine() {
        assertEquals("", ChatFeel.badge(0));
        assertEquals("", ChatFeel.badge(-2));
        assertEquals("3", ChatFeel.badge(3));
        assertEquals("9", ChatFeel.badge(9));
        assertEquals("9+", ChatFeel.badge(10));
    }

    @Test public void blinkAlternates() {
        assertTrue(ChatFeel.blinkOn(0, 1000));
        assertTrue(ChatFeel.blinkOn(499, 1000));
        assertFalse(ChatFeel.blinkOn(500, 1000));
        assertTrue(ChatFeel.blinkOn(1000, 1000));
    }
}
