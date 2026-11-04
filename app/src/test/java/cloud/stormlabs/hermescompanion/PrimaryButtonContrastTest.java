package cloud.stormlabs.hermescompanion;

import org.junit.Test;
import static org.junit.Assert.*;

public class PrimaryButtonContrastTest {
    @Test public void whiteLabelsMeetNormalTextContrastOnEveryThemeAndAccent() {
        for (Concept theme : Concept.ALL) {
            assertTrue(theme.name, Accent.contrast(0xffffffff, Accent.fillForWhiteText(theme.blue)) >= 4.5);
            assertTrue(theme.name, Accent.contrast(0xffffffff, Accent.fillForWhiteText(theme.red)) >= 4.5);
        }
        for (int accent : Accent.COLORS) if (accent != 0)
            assertTrue(Accent.contrast(0xffffffff, Accent.fillForWhiteText(accent)) >= 4.5);
    }
}
