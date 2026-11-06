package cloud.stormlabs.hermescompanion;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.json.JSONObject;
import org.junit.Test;

public class NativeActionsTest {
    @Test public void newActionsHaveTheRightTiers() {
        assertEquals(DeviceActions.Tier.SENSITIVE, DeviceActions.tier("message"));
        assertEquals(DeviceActions.Tier.SENSITIVE, DeviceActions.tier("reply"));
        assertEquals(DeviceActions.Tier.LOCAL, DeviceActions.tier("remind"));
        assertEquals(DeviceActions.Tier.READ, DeviceActions.tier("get_messages"));
    }

    @Test public void instructionsSteerTowardsDirectActions() {
        String s = DeviceActions.instructions(true);
        assertTrue(s.contains("- message {app whatsapp|telegram|viber|signal"));
        assertTrue(s.contains("- reply {id, text}"));
        assertTrue(s.contains("- remind {text, at"));
        assertTrue(s.contains("- get_messages {}"));
        assertTrue(s.contains("Always prefer one direct action over screen steps"));
    }

    @Test public void describesNewActions() throws Exception {
        assertEquals("Message Mom on whatsapp", new DeviceActions.Action("message", new JSONObject().put("app", "whatsapp").put("contact", "Mom")).describe());
        assertEquals("Remind · Call the bank", new DeviceActions.Action("remind", new JSONObject().put("text", "Call the bank")).describe());
    }
}
