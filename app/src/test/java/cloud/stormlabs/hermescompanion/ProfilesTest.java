package cloud.stormlabs.hermescompanion;

import org.junit.Test;
import static org.junit.Assert.*;

public class ProfilesTest {
    @Test public void namesComeFromTheHermesProfilePath() {
        assertEquals("Worker", Profiles.defaultName("http://192.168.1.10:8889/p/worker"));
        assertEquals("Fitness", Profiles.defaultName("http://192.168.1.10:8889/p/fitness/"));
    }
    @Test public void otherwiseHermes() {
        assertEquals("Hermes", Profiles.defaultName("http://192.168.1.10:8889"));
        assertEquals("Hermes", Profiles.defaultName(""));
    }
    @Test public void serverRootDropsTheProfilePath() {
        assertEquals("http://192.168.1.10:8889", Profiles.serverRoot("http://192.168.1.10:8889/p/worker"));
        assertEquals("http://192.168.1.10:8889", Profiles.serverRoot("http://192.168.1.10:8889"));
    }
    @Test public void whereOmitsTheScheme() {
        assertEquals("192.168.1.10:8889/p/worker", Profiles.where("http://192.168.1.10:8889/p/worker"));
    }
    @Test public void newProfileNeedsNameAfterNormalizedProfilePrefix() {
        assertTrue(Profiles.needsProfileName("https://hermes.local/p"));
        assertTrue(Profiles.needsProfileName("https://hermes.local/p//"));
        assertFalse(Profiles.needsProfileName("https://hermes.local/p/worker//"));
    }
    @Test public void nextIndexStepsForwardAndWrapsAround() {
        assertEquals(1, Profiles.nextIndex(0, 1, 3));
        assertEquals(0, Profiles.nextIndex(2, 1, 3));
    }
    @Test public void nextIndexStepsBackwardAndWrapsAround() {
        assertEquals(1, Profiles.nextIndex(2, -1, 3));
        assertEquals(2, Profiles.nextIndex(0, -1, 3));
    }
    @Test public void nextIndexWithOneProfileStaysPut() {
        assertEquals(0, Profiles.nextIndex(0, 1, 1));
        assertEquals(0, Profiles.nextIndex(0, -1, 1));
    }
    @Test public void nextIndexWithNoProfilesIsZero() {
        assertEquals(0, Profiles.nextIndex(0, 1, 0));
    }
}
