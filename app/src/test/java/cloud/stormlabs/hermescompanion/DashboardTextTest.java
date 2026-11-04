package cloud.stormlabs.hermescompanion;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

public class DashboardTextTest {
    @Test public void greetingByHour() {
        assertEquals("Good night", DashboardText.greeting(2));
        assertEquals("Good morning", DashboardText.greeting(5));
        assertEquals("Good afternoon", DashboardText.greeting(12));
        assertEquals("Good evening", DashboardText.greeting(18));
    }

    @Test public void statusLine() {
        assertEquals("Checking systems…", DashboardText.status(false, 0, -1, "", Double.NaN));
        assertEquals("1 thing needs attention", DashboardText.status(true, 1, 4, "pve", 38));
        assertEquals("2 things need attention", DashboardText.status(true, 2, 4, "pve", 38));
        assertEquals("All systems normal · 4 guests running · pve1 38% CPU", DashboardText.status(true, 0, 4, "pve1", 38.2));
        assertEquals("All systems normal · 1 guest running", DashboardText.status(true, 0, 1, "", Double.NaN));
        assertEquals("All systems normal", DashboardText.status(true, 0, -1, null, Double.NaN));
    }
}
