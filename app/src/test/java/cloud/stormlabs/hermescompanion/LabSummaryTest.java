package cloud.stormlabs.hermescompanion;

import org.json.JSONObject;
import org.junit.Test;

import static org.junit.Assert.*;

public class LabSummaryTest {
    private static LabSummary of(String json) throws Exception { return new LabSummary(new JSONObject(json)); }

    @Test public void parsesFieldsAndPicksFirstProblem() throws Exception {
        LabSummary s = of("{\"updated\":100,\"host\":{\"node\":\"pve\",\"cpu_pct\":41.4,\"mem_pct\":73.7,\"root_pct\":62.2,\"temp_c\":62},"
                + "\"guests_running\":3,\"guests_total\":16,\"nodes_online\":2,\"nodes_total\":2,\"services_bad\":0,"
                + "\"worst_storage\":{\"name\":\"/vault\",\"pct\":91},\"problems\":[\"storage /vault 91%\",\"x\"]}");
        assertEquals("pve", s.node);
        assertEquals("storage /vault 91%", s.problem);
        assertEquals(100000L, s.updatedMs);
        assertEquals("41%", LabSummary.pct(s.cpu));
        assertEquals(LabSummary.WARN, s.level());
    }

    @Test public void okWhenNoProblems() throws Exception {
        LabSummary s = of("{\"host\":{\"node\":\"a\",\"temp_c\":null},\"nodes_online\":1,\"nodes_total\":1,\"problems\":[]}");
        assertEquals(LabSummary.OK, s.level());
        assertEquals("", s.problem);
        assertEquals("-", LabSummary.pct(s.temp));
    }

    @Test public void badOnFailedServiceOfflineNodeOrHotCpu() throws Exception {
        assertEquals(LabSummary.BAD, of("{\"services_bad\":1,\"nodes_online\":1,\"nodes_total\":1,\"problems\":[\"f\"]}").level());
        assertEquals(LabSummary.WARN, of("{\"nodes_online\":1,\"nodes_total\":2,\"problems\":[\"o\"]}").level());
        assertEquals(LabSummary.BAD, of("{\"nodes_online\":0,\"nodes_total\":2,\"problems\":[\"o\"]}").level());
        assertEquals(LabSummary.BAD, of("{\"host\":{\"temp_c\":92},\"nodes_online\":1,\"nodes_total\":1,\"problems\":[\"t\"]}").level());
    }
}
