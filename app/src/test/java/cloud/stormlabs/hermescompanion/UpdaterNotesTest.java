package cloud.stormlabs.hermescompanion;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

public class UpdaterNotesTest {
    @Test public void releaseNotesAreShownAsPlainText() {
        assertEquals("7.53.0\n\n- Terminal: buttons.\n\n- More.", Updater.plain("## 7.53.0\n\n\n\n- **Terminal**: `buttons`.\n\n- More.\n"));
    }
}
