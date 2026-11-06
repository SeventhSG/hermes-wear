package cloud.stormlabs.hermescompanion;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.junit.Test;

public class LogLinesTest {
    @Test public void classifies() {
        assertEquals(LogLines.ERROR, LogLines.level("Connection FAILED"));
        assertEquals(LogLines.ERROR, LogLines.level("x Traceback (most recent call last)"));
        assertEquals(LogLines.ERROR, LogLines.level("12:00 E something"));
        assertEquals(LogLines.WARN, LogLines.level("Warning: slow"));
        assertEquals(LogLines.NORMAL, LogLines.level("started ok"));
        assertEquals(LogLines.ERROR, LogLines.level("warn: error ahead"));
    }

    @Test public void filterIsCaseInsensitiveSubstring() {
        assertTrue(LogLines.matches("Hello World", ""));
        assertTrue(LogLines.matches("Hello World", "o wO"));
        assertFalse(LogLines.matches("Hello World", "xyz"));
    }

    @Test public void capDropsOldest() {
        List<String> l = new ArrayList<>(Arrays.asList("a", "b", "c", "d"));
        assertTrue(LogLines.cap(l, 2));
        assertEquals(Arrays.asList("c", "d"), l);
        assertFalse(LogLines.cap(l, 2));
    }

    @Test public void cursorKeepsOldWhenMissing() {
        assertEquals("s=1", LogLines.cursor("s=1", ""));
        assertEquals("s=1", LogLines.cursor("s=1", null));
        assertEquals("s=2", LogLines.cursor("s=1", "s=2"));
    }
}
