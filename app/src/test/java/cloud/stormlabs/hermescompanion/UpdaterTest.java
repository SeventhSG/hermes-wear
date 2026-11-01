package cloud.stormlabs.hermescompanion;

import static org.junit.Assert.*;
import org.json.JSONObject;
import org.junit.Test;

public class UpdaterTest {
    @Test public void versionComparison() {
        assertTrue(Updater.newer(41, 40));
        assertFalse(Updater.newer(40, 40));
        assertFalse(Updater.newer(39, 40));
    }
    @Test public void nameComparison() throws Exception {
        assertTrue(Updater.newerName("7.10.0", "7.9.1"));
        assertTrue(Updater.newerName("7.6.1", "7.6"));
        assertFalse(Updater.newerName("7.6", "7.6.0"));
        assertFalse(Updater.newerName("7.5.9", "7.6.0"));
        assertFalse(Updater.newerName("7.6.0-debug", "7.6.0"));
        assertTrue(Updater.newer(new JSONObject().put("versionName", "7.7.0"), 76, "7.6.0"));
        assertFalse(Updater.newer(new JSONObject().put("versionName", "7.6.0"), 76, "7.6.0"));
        assertTrue(Updater.newer(new JSONObject().put("versionCode", 77).put("versionName", "1"), 76, "7.6.0")); // code wins
    }
    @Test public void throttle() {
        long h = 3600_000L;
        assertTrue(Updater.due(0, 100 * h));
        assertFalse(Updater.due(10 * h, 10 * h + h / 2));
        assertTrue(Updater.due(10 * h, 11 * h));
        assertTrue(Updater.due(20 * h, 10 * h)); // clock went back
    }
    @Test public void hexAndVerify() throws Exception {
        assertEquals("00ff10", Updater.hex(new byte[]{0, (byte) 0xff, 0x10}));
        JSONObject o = new JSONObject().put("sha256", "00FF10").put("size", 3);
        assertTrue(Updater.verified(o, "00ff10", 3));
        assertFalse(Updater.verified(o, "00ff11", 3));
        assertFalse(Updater.verified(o, "00ff10", 4));
        assertFalse(Updater.verified(new JSONObject(), "", 0));
    }
}
