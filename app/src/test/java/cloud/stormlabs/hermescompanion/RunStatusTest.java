package cloud.stormlabs.hermescompanion;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class RunStatusTest {
    @Test public void states() {
        assertEquals("Needs your approval", RunStatus.text(true, "terminal", "x", false));
        assertEquals("Thinking…", RunStatus.text(false, null, null, false));
        assertEquals("Writing reply…", RunStatus.text(false, null, null, true));
        assertEquals("Running terminal: apt update", RunStatus.text(false, "terminal", "apt  update", false));
        assertEquals("Reading file /etc/hosts", RunStatus.text(false, "read_file", "/etc/hosts", false));
        assertEquals("Running web search…", RunStatus.text(false, "web_search", "", false));
    }

    @Test public void longArgShortened() {
        assertTrue(RunStatus.text(false, "terminal", new String(new char[200]).replace('\0', 'a'), false).length() < 80);
        assertEquals("step 1", RunStatus.step(0));
        assertEquals("step 3", RunStatus.step(3));
    }
}
