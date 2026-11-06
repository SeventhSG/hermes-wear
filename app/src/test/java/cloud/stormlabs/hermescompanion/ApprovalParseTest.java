package cloud.stormlabs.hermescompanion;

import static org.junit.Assert.assertEquals;

import org.json.JSONObject;
import org.junit.Test;

public class ApprovalParseTest {
    @Test public void durableRunPromptKeepsIdAndGeneration() throws Exception {
        JSONObject o = new JSONObject("{\"kind\":\"approval\",\"prompt_id\":\"p1\",\"execution_generation\":3,\"command\":\"rm x\",\"choices\":[\"once\",\"deny\"]}");
        HermesApi.Approval a = HermesApi.Approval.parse("run_1", o);
        assertEquals("p1", a.requestId);
        assertEquals(3, a.generation);
    }

    @Test public void legacyEventHasNoGeneration() throws Exception {
        HermesApi.Approval a = HermesApi.Approval.parse("run_1", new JSONObject("{\"request_id\":\"r9\",\"command\":\"ls\"}"));
        assertEquals("r9", a.requestId);
        assertEquals(-1, a.generation);
    }
}
