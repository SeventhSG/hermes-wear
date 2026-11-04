package cloud.stormlabs.hermescompanion;

import static org.junit.Assert.*;

import org.junit.Test;

public class BrandTest {
    @Test public void customNameReplacesHermesOnly() {
        assertEquals("Jarvis", Brand.name(" Jarvis ", "Hermes"));
        assertEquals("Hermes", Brand.name("", "Hermes"));
        assertEquals("Codex", Brand.name("Jarvis", "Codex"));
    }

    @Test public void hexParsesSixDigitsOnly() {
        assertEquals(Integer.valueOf(0xff101418), Accent.hex("#101418"));
        assertEquals(Integer.valueOf(0xffF4F1EA), Accent.hex("f4f1ea"));
        assertNull(Accent.hex("fff"));
        assertNull(Accent.hex("zzzzzz"));
    }

    @Test public void customThemeKeepsTextReadable() {
        for (int bg : new int[]{0xff000000, 0xff101418, 0xff3a2f5c, 0xff7a7a7a, 0xfff4f1ea, 0xffffffff}) {
            Concept k = Concept.custom(bg);
            assertTrue(Integer.toHexString(bg), Accent.contrast(k.text, k.bg) >= 7);
            assertTrue(Integer.toHexString(bg), Accent.contrast(k.sub, k.bg) >= 3);
            assertEquals(k.light, Accent.luminance(bg) > .35);
            assertEquals(k.bg, k.tier(0));
        }
        Concept.custom = Concept.custom(0xff223344);
        assertSame(Concept.custom, Concept.byId("custom"));
    }
}
