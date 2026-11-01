package cloud.stormlabs.hermescompanion;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.json.JSONObject;
import org.junit.Test;

public class AgentOverlayTest {
    @Test public void tapWordingIncludesTargetAndApp() throws Exception {
        DeviceActions.Action tap = new DeviceActions.Action("tap", new JSONObject().put("target", "Timer"));
        assertEquals("Tapping “Timer” in Clock", AgentOverlay.stepWords(tap, "Clock"));
        assertEquals("Tapping “Timer”", AgentOverlay.stepWords(tap, ""));
        assertEquals("Tapping “Timer”", AgentOverlay.stepWords(tap, null));
    }

    @Test public void typeWordingMasksNothingButNamesTarget() throws Exception {
        DeviceActions.Action type = new DeviceActions.Action("type", new JSONObject().put("target", "Search"));
        assertEquals("Typing into “Search” in Maps", AgentOverlay.stepWords(type, "Maps"));
    }

    @Test public void scrollBackHomeWording() throws Exception {
        DeviceActions.Action scroll = new DeviceActions.Action("scroll", new JSONObject().put("direction", "down"));
        assertEquals("Scrolling down in Maps", AgentOverlay.stepWords(scroll, "Maps"));
        assertEquals("Going back", AgentOverlay.stepWords(new DeviceActions.Action("back", new JSONObject()), "Maps"));
        assertEquals("Going to the home screen", AgentOverlay.stepWords(new DeviceActions.Action("home", new JSONObject()), "Maps"));
    }

    @Test public void openAppWordingMatchesDescribe() throws Exception {
        DeviceActions.Action open = new DeviceActions.Action("open_app", new JSONObject().put("name", "Clock"));
        assertEquals(open.describe(), AgentOverlay.stepWords(open, "anything"));
    }

    @Test public void finishedHeadlineReflectsOutcome() {
        assertEquals("Done", AgentOverlay.finishedHeadline(true));
        assertEquals("Stopped", AgentOverlay.finishedHeadline(false));
        assertFalse(AgentOverlay.finishedHeadline(true).equals(AgentOverlay.finishedHeadline(false)));
        assertTrue(AgentOverlay.finishedHeadline(true).length() > 0);
    }
}
