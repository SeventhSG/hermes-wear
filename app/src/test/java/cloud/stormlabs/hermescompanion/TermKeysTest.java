package cloud.stormlabs.hermescompanion;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import android.view.KeyEvent;

import org.junit.Test;

public class TermKeysTest {
    private static final String E = "\u001b";

    @Test public void arrowsFollowApplicationCursorMode() {
        assertEquals(E + "[A", TermKeys.special(KeyEvent.KEYCODE_DPAD_UP, false, false, false, false));
        assertEquals(E + "OA", TermKeys.special(KeyEvent.KEYCODE_DPAD_UP, false, false, false, true));
        assertEquals(E + "[1;5C", TermKeys.special(KeyEvent.KEYCODE_DPAD_RIGHT, false, false, true, true));
    }

    @Test public void editingAndFunctionKeys() {
        assertEquals(E + "[5~", TermKeys.special(KeyEvent.KEYCODE_PAGE_UP, false, false, false, false));
        assertEquals(E + "[3;3~", TermKeys.special(KeyEvent.KEYCODE_FORWARD_DEL, false, true, false, false));
        assertEquals(E + "OP", TermKeys.special(KeyEvent.KEYCODE_F1, false, false, false, false));
        assertEquals(E + "[15~", TermKeys.special(KeyEvent.KEYCODE_F5, false, false, false, false));
        assertEquals(E + "[24~", TermKeys.special(KeyEvent.KEYCODE_F12, false, false, false, false));
        assertEquals(E + "[Z", TermKeys.special(KeyEvent.KEYCODE_TAB, true, false, false, false));
        assertEquals("\u007f", TermKeys.special(KeyEvent.KEYCODE_DEL, false, false, false, false));
        assertNull(TermKeys.special(KeyEvent.KEYCODE_A, false, false, false, false));
    }

    @Test public void ctrlAndAltOnText() {
        assertEquals("\u0003", TermKeys.text("c", false, true));
        assertEquals("\u0004", TermKeys.text("D", false, true));
        assertEquals("\u001b" + "x", TermKeys.text("x", true, false));
        assertEquals("\u001b\u0002", TermKeys.text("b", true, true));
        assertEquals("\u001b", TermKeys.text("[", false, true));
        assertEquals("plain", TermKeys.text("plain", false, false));
    }

    @Test public void pasteUsesCarriageReturnsAndBrackets() {
        assertEquals("a\rb", TermKeys.paste("a\r\nb", false));
        assertEquals(E + "[200~a" + E + "[201~", TermKeys.paste("a", true));
    }

    @Test public void mouseReportsSgrAndClassic() {
        assertEquals(E + "[<0;5;3M", TermKeys.mouse(TermKeys.LEFT, 5, 3, false, false, true));
        assertEquals(E + "[<0;5;3m", TermKeys.mouse(TermKeys.LEFT, 5, 3, true, false, true));
        assertEquals(E + "[<32;6;3M", TermKeys.mouse(TermKeys.LEFT, 6, 3, false, true, true));
        assertEquals(E + "[<65;1;1M", TermKeys.mouse(TermKeys.WHEEL_DOWN, 1, 1, false, false, true));
        assertEquals(E + "[M !#", TermKeys.mouse(TermKeys.LEFT, 1, 3, false, false, false));
        assertEquals(E + "[M#!#", TermKeys.mouse(TermKeys.LEFT, 1, 3, true, false, false));
        assertNull(TermKeys.mouse(TermKeys.LEFT, 300, 3, false, false, false));
    }
}
