package cloud.stormlabs.hermescompanion;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class ModulesTest {
    @Test public void optionalModulesAreOnUntilSwitchedOffAndAnOffInboxIsNotReady() {
        SettingsStore s = new SettingsStore(NotificationOriginTest.preferences());
        assertTrue(s.moduleOn(Modules.MEDIA));
        s.moduleOn(Modules.MEDIA, false);
        assertFalse(s.moduleOn(Modules.MEDIA));
        assertTrue(s.moduleOn(Modules.CODEX));
        s.moduleOn(Modules.ESSENTIALS, false);
        assertFalse(s.essentialsReady());
    }

    @Test public void everyModuleHasAnIdTitleDescriptionIconAndSwitchFlag() {
        for (Object[] m : Modules.ALL) {
            assertTrue(m.length == 5 && m[0] instanceof String && m[1] instanceof String && m[2] instanceof String && m[3] instanceof Integer && m[4] instanceof Boolean);
        }
    }
}
