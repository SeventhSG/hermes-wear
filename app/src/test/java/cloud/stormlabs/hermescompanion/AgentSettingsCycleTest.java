package cloud.stormlabs.hermescompanion;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import static org.junit.Assert.*;

public class AgentSettingsCycleTest {
    private JSONObject settings(String current, String... values) throws Exception {
        JSONArray a = new JSONArray();
        for (String v : values) a.put(new JSONObject().put("value", v).put("label", v));
        return new JSONObject().put("permission_mode", current).put("permission_options", a);
    }
    @Test public void cyclesInServerOrderAndWraps() throws Exception {
        assertEquals("acceptEdits", AgentSettings.next(settings("default", "default", "acceptEdits", "plan", "bypassPermissions")).value);
        assertEquals("default", AgentSettings.next(settings("bypassPermissions", "default", "acceptEdits", "plan", "bypassPermissions")).value);
        assertEquals("workspace-write", AgentSettings.next(settings("read-only", "read-only", "workspace-write", "full-access")).value);
    }
    @Test public void skipsCustomAndNeedsAtLeastTwoRealChoices() throws Exception {
        assertEquals("full-access", AgentSettings.next(settings("read-only", "read-only", "custom", "full-access")).value);
        assertEquals("read-only", AgentSettings.next(settings("custom", "read-only", "custom", "full-access")).value);
        assertNull(AgentSettings.next(settings("default", "default", "custom")));
        assertNull(AgentSettings.next(new JSONObject()));
    }
    @Test public void escalationIsReportedForEveryRedMode() throws Exception {
        for (String mode : new String[]{"bypassPermissions", "full-access", "off", "yolo_chat"}) {
            assertTrue(mode, AgentSettings.next(settings("safe", "safe", mode)).needsConfirm);
        }
        assertFalse(AgentSettings.next(settings("default", "default", "plan")).needsConfirm);
    }
    @Test public void nativeModeNamesReadAsPlainWords() throws Exception {
        JSONObject s = settings("default", "default", "acceptEdits", "plan", "bypassPermissions");
        assertEquals("Accept edits", AgentSettings.next(s).label);
        assertEquals("Bypass", AgentSettings.options(s, true).get(3).label);
        assertEquals("Workspace", AgentSettings.next(settings("read-only", "read-only", "workspace-write")).label);
    }

    @Test public void planAndFastAreOptionalAndFollowTheServer() throws Exception {
        assertNull(AgentSettings.planMode(new JSONObject())); assertTrue(AgentSettings.fastOptions(new JSONObject()).isEmpty());
        JSONObject s = new JSONObject("{\"plan_mode\":false,\"fast_mode\":\"fast\",\"fast_options\":[{\"value\":\"off\",\"label\":\"Standard\"},{\"value\":\"fast\",\"label\":\"Fast\"}]}");
        assertEquals(Boolean.FALSE, AgentSettings.planMode(s)); assertEquals(2, AgentSettings.fastOptions(s).size());
        assertTrue(AgentSettings.fastOptions(s).get(1).selected);
    }
}
