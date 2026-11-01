package cloud.stormlabs.hermescompanion;

import static org.junit.Assert.*;
import org.junit.Test;

public class OnboardingTest {
    @Test public void onlyFreshInstallsSeeTheTour() {
        assertTrue(Onboarding.shouldRun("", false));
        assertTrue(Onboarding.shouldRun(null, false));
        assertFalse(Onboarding.shouldRun("", true));
        assertFalse(Onboarding.shouldRun("key", false));
    }

    @Test public void pagingStaysInsideTheTour() {
        assertEquals(0, Onboarding.move(0, -1));
        assertEquals(1, Onboarding.move(0, 1));
        assertEquals(Onboarding.PAGES - 1, Onboarding.move(Onboarding.PAGES - 1, 1));
        assertTrue(Onboarding.isLast(Onboarding.PAGES - 1));
        assertFalse(Onboarding.isLast(0));
    }

    @Test public void permissionStateMapping() {
        assertEquals(Onboarding.GRANTED, Onboarding.permState(true, true));
        assertEquals(Onboarding.ASK, Onboarding.permState(false, false));
        assertEquals(Onboarding.BLOCKED, Onboarding.permState(false, true));
        assertEquals("Granted ✓", Onboarding.actionLabel(Onboarding.GRANTED));
        assertEquals("Allow", Onboarding.actionLabel(Onboarding.ASK));
        assertEquals("App info", Onboarding.actionLabel(Onboarding.BLOCKED));
        assertEquals("Done", Onboarding.primaryLabel(true));
    }

    @Test public void relayUrlIsNormalised() {
        assertEquals("https://relay.example.com", PushRelay.base(" https://relay.example.com/ "));
        assertEquals("https://relay.example.com", PushRelay.base("https://relay.example.com/register"));
        assertEquals("http://relay.example.com:8080", PushRelay.base("relay.example.com:8080/"));
        assertEquals("", PushRelay.base(""));
    }

    @Test public void relayRegisterBodyCarriesTheToken() throws Exception {
        assertEquals("abc", new org.json.JSONObject(PushRelay.registerBody("abc")).optString("token"));
    }
}
