package cloud.stormlabs.hermescompanion;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.json.JSONObject;
import org.junit.Test;

import java.util.List;

public class ReadinessChecksTest {
    @Test public void formatsDegradedDiskAndOtherChecks() throws Exception {
        JSONObject checks = new JSONObject("{" +
                "\"state_db\":{\"status\":\"ok\"},\"session_store\":{\"status\":\"ok\"}," +
                "\"config\":{\"status\":\"ok\"},\"model\":{\"status\":\"ok\"}," +
                "\"disk\":{\"status\":\"degraded\",\"used_percent\":92.9,\"free_bytes\":2396303360}," +
                "\"gateway\":{\"status\":\"ok\",\"state\":\"running\",\"connected_platforms\":5,\"platforms\":5}," +
                "\"background_queues\":{\"status\":\"ok\",\"active_api_runs\":0,\"process_completions\":0,\"active_delegations\":0}" +
                "}");
        checks.put("session_store", new JSONObject().put("status", "degraded").put("detail", "offline").put("unused", JSONObject.NULL));

        List<String> lines = ReadinessChecks.lines(checks);
        assertEquals(2, lines.size());
        assertTrue(lines.contains("Disk · 92.9% used, 2.2 GB free"));
        assertTrue(lines.contains("Session store · detail offline"));
    }

    @Test public void hidesAllOkChecks() throws Exception {
        assertTrue(ReadinessChecks.lines(new JSONObject("{\"disk\":{\"status\":\"ok\"}}")).isEmpty());
    }

    @Test public void formatsMegabytes() throws Exception {
        JSONObject checks = new JSONObject("{\"disk\":{\"status\":\"degraded\",\"used_percent\":95,\"free_bytes\":157286400}}");
        assertEquals("Disk · 95.0% used, 150.0 MB free", ReadinessChecks.lines(checks).get(0));
    }

    @Test public void skipsMalformedChecks() throws Exception {
        assertTrue(ReadinessChecks.lines(null).isEmpty());
        assertTrue(ReadinessChecks.lines(new JSONObject("{\"disk\":null,\"gateway\":42,\"model\":{},\"config\":{\"status\":null}}")).isEmpty());
    }
}
