package cloud.stormlabs.hermescompanion;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/** A pasted key must reach the gateway byte-identical, whatever the clipboard added. */
public class KeysTest {

    private static final String KEY = "dummy-test-key-not-a-credential";

    @Test public void keepsAPlainKeyUnchanged() {
        assertEquals(KEY, Keys.clean(KEY));
        assertFalse(Keys.hadNoise(KEY));
    }

    @Test public void stripsZeroWidthAndNonBreakingSpaces() {
        assertEquals(KEY, Keys.clean("\u200B" + KEY + "\u00A0"));
        assertEquals(KEY, Keys.clean(KEY + "\uFEFF"));
        assertEquals(KEY, Keys.clean("\u200E" + KEY));
    }

    @Test public void stripsWhitespaceAndNewlines() {
        assertEquals(KEY, Keys.clean("  " + KEY + "\n"));
        assertEquals(KEY, Keys.clean(KEY + "\t\r\n"));
        assertFalse(Keys.hadNoise(" " + KEY + " "));   // plain spaces are not "noise", just trimmed
    }

    @Test public void reportsNoiseSoTheUserIsTold() {
        assertTrue(Keys.hadNoise(KEY + "\u200B"));
        assertTrue(Keys.hadNoise("\u00A0" + KEY));
        assertTrue(Keys.hadNoise(KEY + "\u00A0"));   // trim() leaves NBSP behind: that is the trap
    }

    @Test public void handlesNullAndEmpty() {
        assertEquals("", Keys.clean(null));
        assertEquals("", Keys.clean(""));
        assertFalse(Keys.hadNoise(null));
    }

    @Test public void keepsUrlsClean() {
        assertEquals("http://192.168.1.10:8889",
                Keys.clean(" http://192.168.1.10:8889\u200B "));
    }
}
