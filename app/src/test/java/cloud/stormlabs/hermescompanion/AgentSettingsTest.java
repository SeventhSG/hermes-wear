package cloud.stormlabs.hermescompanion;

import static org.junit.Assert.*;
import java.util.List;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;

public class AgentSettingsTest {
    @Test public void reasoningUsesModelAdvertisedValuesAndLabels() throws Exception {
        JSONObject settings = new JSONObject().put("reasoning_effort", "adaptive").put("reasoning_options", new JSONArray()
                .put(new JSONObject().put("value", "adaptive").put("label", "Adaptive effort").put("description", "Selected model chooses"))
                .put(new JSONObject().put("value", "max").put("label", "Maximum"))
                .put(new JSONObject().put("label", "Missing value")));
        List<AgentSettings.Option> options = AgentSettings.options(settings, false);
        assertEquals(2, options.size());
        assertEquals("adaptive", options.get(0).value);
        assertEquals("Adaptive effort", options.get(0).label);
        assertEquals("Selected model chooses", options.get(0).description);
        assertTrue(options.get(0).selected);
        assertFalse(options.get(1).selected);
    }

    @Test public void customPermissionsAreOnlyDisplayedWhenCurrent() throws Exception {
        JSONObject settings = new JSONObject().put("permission_mode", "workspace-write").put("permission_options", new JSONArray()
                .put(new JSONObject().put("value", "workspace-write").put("label", "Workspace"))
                .put(new JSONObject().put("value", "custom").put("label", "Terminal configuration")));
        assertEquals(1, AgentSettings.options(settings, true).size());
        settings.put("permission_mode", "custom");
        List<AgentSettings.Option> options = AgentSettings.options(settings, true);
        assertEquals(2, options.size());
        assertTrue(options.get(1).selected);
        assertTrue(AgentSettings.options(new JSONObject(), true).isEmpty());
    }
}
