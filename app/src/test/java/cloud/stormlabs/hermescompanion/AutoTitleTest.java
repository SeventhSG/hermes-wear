package cloud.stormlabs.hermescompanion;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class AutoTitleTest {
    @Test public void longTextCutsAtWordBoundary() {
        String title = AutoTitle.fromMessage("Planning a weekend trip to the mountains with friends and family");
        assertEquals("Planning a weekend trip to the mountains with…", title);
        assertTrue(title.length() <= 48);
    }

    @Test public void firstLineOnlyAndWhitespaceCollapsed() {
        assertEquals("Plan the trip", AutoTitle.fromMessage("  Plan\t the   trip  \nIgnore this line"));
        assertEquals("Plan the trip", AutoTitle.fromMessage("\u2003Plan\u2003the trip\u2003"));
        assertEquals("", AutoTitle.fromMessage("   \nSecond line"));
        assertEquals("", AutoTitle.fromMessage(" \t "));
    }

    @Test public void slashCommandsAreSkipped() {
        assertEquals("", AutoTitle.fromMessage("  /rename Anything"));
    }

    @Test public void placeholderTitlesAreExact() {
        assertTrue(AutoTitle.isPlaceholder("New conversation"));
        assertTrue(AutoTitle.isPlaceholder("New conversation 7"));
        assertTrue(AutoTitle.isPlaceholder("Mobile"));
        assertTrue(AutoTitle.isPlaceholder("Mobile 12"));
        assertFalse(AutoTitle.isPlaceholder("Mobile notes"));
        assertFalse(AutoTitle.isPlaceholder("New conversation 0x"));
        assertFalse(AutoTitle.isPlaceholder("Other"));
    }

    @Test public void smartTitlesDropGreetingsAndPolitenessAndStayShort() {
        assertEquals("Fix my docker compose file", AutoTitle.smart("Hey, can you fix my docker compose file?"));
        assertEquals("Research whisper speed on a GTX 1650", AutoTitle.smart("Please research whisper speed on a GTX 1650 card"));
        assertEquals("hello", AutoTitle.smart("hello"));            // nothing left after the greeting: keep what was said
        assertEquals("Plan a trip", AutoTitle.smart("Plan a trip"));
        assertEquals("", AutoTitle.smart("/goal do things"));
        assertEquals("Set up fail2ban on ssh", AutoTitle.smart("I need you to set up fail2ban on ssh."));
    }
}
