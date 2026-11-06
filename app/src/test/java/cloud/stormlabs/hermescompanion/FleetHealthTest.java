package cloud.stormlabs.hermescompanion;

import org.json.JSONObject;
import org.junit.Test;
import static org.junit.Assert.*;

public class FleetHealthTest {
    @Test public void classifiesHealthJson() throws Exception {
        FleetHealth.Status ok = FleetHealth.classify(new JSONObject("{\"version\":\"v0.21.5\",\"gateway_state\":\"running\"}"));
        assertEquals(FleetHealth.ONLINE, ok.level);
        assertEquals("Online · v0.21.5", ok.label());
        assertEquals(FleetHealth.DEGRADED, FleetHealth.classify(new JSONObject("{\"gateway_state\":\"stopped\"}")).level);
        assertEquals(FleetHealth.DEGRADED, FleetHealth.classify(new JSONObject("{\"status\":\"degraded\"}")).level);
        assertEquals("Online", FleetHealth.classify(new JSONObject("{}")).label());
    }

    @Test public void cacheExpiresAfterTtl() {
        FleetHealth.Status s = new FleetHealth.Status(FleetHealth.ONLINE, "1", "");
        long t = 1_000_000;
        FleetHealth.put("a", s, t);
        assertSame(s, FleetHealth.cached("a", t + FleetHealth.TTL_MS - 1));
        assertNull(FleetHealth.cached("a", t + FleetHealth.TTL_MS));
        assertNull(FleetHealth.cached("missing", t));
    }
}
