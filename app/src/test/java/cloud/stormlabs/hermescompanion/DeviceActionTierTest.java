package cloud.stormlabs.hermescompanion;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;
import org.json.JSONObject;

public class DeviceActionTierTest {
    @Test public void everySupportedActionHasExpectedTier() {
        for (String type : DeviceActions.SUPPORTED) {
            DeviceActions.Tier expected;
            if (DeviceActions.isQuery(type)) expected = DeviceActions.Tier.READ;
            else if ("call".equals(type) || "sms".equals(type) || "email".equals(type)
                    || "share".equals(type) || "capture".equals(type) || "open_url".equals(type)
                    || DeviceActions.isScreenStep(type)) expected = DeviceActions.Tier.SENSITIVE;
            else expected = DeviceActions.Tier.LOCAL;
            assertEquals(type, expected, DeviceActions.tier(type));
        }
        assertEquals(DeviceActions.Tier.SENSITIVE, DeviceActions.tier("unknown"));
    }

    @Test public void openUrlSchemeSetsTier() throws Exception {
        for (String url : new String[]{"https://example.com", "HTTP://example.com", "example.com"})
            assertEquals(url, DeviceActions.Tier.LOCAL, DeviceActions.tier(new DeviceActions.Action("open_url", new JSONObject().put("url", url))));
        for (String url : new String[]{"tel:123", "mailto:a@example.com", "sms:123", "geo:0,0", "market://details", "intent://scan", "javascript:alert(1)"})
            assertEquals(url, DeviceActions.Tier.SENSITIVE, DeviceActions.tier(new DeviceActions.Action("open_url", new JSONObject().put("url", url))));
    }

    @Test public void wirelessOffRequiresConfirmation() throws Exception {
        for (String type : new String[]{"wifi", "bluetooth"}) {
            assertEquals(DeviceActions.Tier.LOCAL, DeviceActions.tier(new DeviceActions.Action(type, new JSONObject().put("on", true))));
            assertEquals(DeviceActions.Tier.SENSITIVE, DeviceActions.tier(new DeviceActions.Action(type, new JSONObject().put("on", false))));
        }
        assertEquals(DeviceActions.Tier.READ, DeviceActions.tier("read_screen"));
    }

    @Test public void actionModeControlsSensitiveConfirmation() throws Exception {
        for (String type : DeviceActions.SUPPORTED) {
            DeviceActions.Action action = new DeviceActions.Action(type, new JSONObject());
            assertEquals(type, DeviceActions.tier(action) == DeviceActions.Tier.SENSITIVE,
                    DeviceActions.needsConfirmation(action, "Ask"));
            assertFalse(type, DeviceActions.needsConfirmation(action, "Full access"));
        }
        assertFalse(DeviceActions.needsConfirmation(new DeviceActions.Action("open_url",
                new JSONObject().put("url", "https://example.com")), "Ask"));
        assertTrue(DeviceActions.needsConfirmation(new DeviceActions.Action("open_url",
                new JSONObject().put("url", "tel:123")), "Ask"));
        for (String type : new String[]{"wifi", "bluetooth"}) {
            assertFalse(DeviceActions.needsConfirmation(new DeviceActions.Action(type,
                    new JSONObject().put("on", true)), "Ask"));
            assertTrue(DeviceActions.needsConfirmation(new DeviceActions.Action(type,
                    new JSONObject().put("on", false)), "Ask"));
        }
    }
}
