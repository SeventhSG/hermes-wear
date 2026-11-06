package cloud.stormlabs.hermescompanion;

import static org.junit.Assert.*;
import org.junit.Test;

import java.util.HashSet;
import java.util.Set;

public class ConceptTest {
    @Test public void unknownOrMissingIdIsConsoleDark() {
        assertSame(Concept.CONSOLE_DARK, Concept.DEFAULT);
        assertSame(Concept.DEFAULT, Concept.byId(null));
        assertSame(Concept.DEFAULT, Concept.byId(""));
        assertSame(Concept.DEFAULT, Concept.byId("neon"));
        for (Concept c : Concept.ALL) assertSame(c, Concept.byId(c.id));
    }

    @Test public void retiredIdsMapToTheNearestNewLook() {
        for (String old : new String[]{"graphite", "midnight", "terminal", "dynamic"}) assertSame(old, Concept.REFINED_DARK, Concept.byId(old));
        assertSame(Concept.REFINED_LIGHT, Concept.byId("paper"));
    }

    @Test public void stylesAndIds() {
        assertEquals("console", Concept.CONSOLE_DARK.id);
        assertEquals("console-light", Concept.CONSOLE_LIGHT.id);
        assertEquals("refined", Concept.REFINED_DARK.id);
        assertEquals("refined-light", Concept.REFINED_LIGHT.id);
        assertEquals(Concept.Style.CONSOLE, Concept.CONSOLE_LIGHT.style);
        assertEquals(Concept.Style.REFINED, Concept.REFINED_LIGHT.style);
        assertEquals(0xff0b0e13, Concept.CONSOLE_DARK.bg);
        assertEquals(0xfff4f1ea, Concept.CONSOLE_LIGHT.bg);
        assertEquals(0xff101214, Concept.REFINED_DARK.bg);
        assertEquals(0xff5a9cf4, Concept.REFINED_DARK.blue);
    }

    @Test public void signalColoursAliasTheLegacyStatusColours() {
        for (Concept c : Concept.ALL) {
            assertEquals(c.ok, c.green); assertEquals(c.watch, c.amber); assertEquals(c.critical, c.red); assertEquals(c.accent, c.blue);
            assertEquals(4, c.tiers.length);
            assertEquals(c.bg, c.tier(0)); assertEquals(c.tier(3), c.tier(9)); assertEquals(c.tier(0), c.tier(-1));
        }
        assertEquals(0xff3ddbb0, Concept.CONSOLE_DARK.ok);
        assertEquals(0xfff2b544, Concept.CONSOLE_DARK.watch);
        assertEquals(0xffff6b6b, Concept.CONSOLE_DARK.critical);
        assertEquals(0xff6ea8ff, Concept.CONSOLE_DARK.accent);
    }

    @Test public void consoleCardsHaveNoStrokeAndTiersRise() {
        assertFalse(Concept.CONSOLE_DARK.cardStroke); assertFalse(Concept.CONSOLE_LIGHT.cardStroke);
        assertEquals(14f, Concept.CONSOLE_DARK.cardRadius, 0); assertEquals(10f, Concept.CONSOLE_DARK.controlRadius, 0);
        for (Concept c : darkConcepts()) {
            for (int i = 1; i < 4; i++) assertTrue(c.id + " tier " + i, luminance(c.tier(i)) > luminance(c.tier(i - 1)));
        }
    }

    @Test public void conceptsAreDistinct() {
        Set<String> ids = new HashSet<>();
        for (Concept c : Concept.ALL) { assertTrue(ids.add(c.id)); assertEquals(1f, c.density, 0); }
        assertEquals(Concept.ALL.length, ids.size());
        assertEquals(11, ids.size());
    }

    @Test public void lightnessMatchesTheBackground() {
        for (Concept c : Concept.ALL) {
            assertEquals(c.id, c.light, luminance(c.bg) > .5);
            assertTrue(c.id + " text contrast", contrast(c.text, c.bg) >= 7);
            assertTrue(c.id + " sub contrast", contrast(c.sub, c.surface) >= 4.5);
            assertTrue(c.id + " faint contrast", contrast(c.faint, c.bg) >= 3);
        }
    }

    @Test public void storeMapsIdsAndFallsBack() {
        SettingsStore store = new SettingsStore(NotificationOriginTest.preferences());
        assertEquals("console", store.uiConcept());
        store.uiConcept("paper");
        assertEquals("refined-light", store.uiConcept());
        store.uiConcept("bogus");
        assertEquals("console", store.uiConcept());
    }

    private static Concept[] darkConcepts() {
        java.util.List<Concept> out = new java.util.ArrayList<>();
        for (Concept c : Concept.ALL) if (!c.light) out.add(c);
        return out.toArray(new Concept[0]);
    }

    @Test public void newThemesKeepTheirIdsAndLightDarkSplit() {
        assertEquals("oled", Concept.byId("oled").id);
        assertSame(Concept.SAGE, Concept.byId("sage"));
        assertTrue(Concept.SKY.light); assertFalse(Concept.NORD.light); assertFalse(Concept.OLED.light);
        assertEquals(0xff000000, Concept.OLED.bg);
    }

    @Test public void accentIsMadeReadableOnBothKindsOfBackground() {
        for (Concept c : Concept.ALL) for (int a : Accent.COLORS) {
            if (a == 0) continue;
            assertTrue(c.id + " accent " + Integer.toHexString(a), Accent.contrast(Accent.readableOn(a, c.bg, 4.5), c.bg) >= 4.5 - 0.01);
        }
        assertTrue(Accent.contrast(0xffffffff, Accent.fillForWhiteText(0xfff2b544)) >= 4.5);
        assertEquals(0, Accent.COLORS[0]);
        assertEquals(Accent.NAMES.length, Accent.COLORS.length);
    }

    private static double luminance(int c) {
        double[] v = {(c >> 16) & 255, (c >> 8) & 255, c & 255};
        for (int i = 0; i < 3; i++) { double s = v[i] / 255; v[i] = s <= .03928 ? s / 12.92 : Math.pow((s + .055) / 1.055, 2.4); }
        return .2126 * v[0] + .7152 * v[1] + .0722 * v[2];
    }

    private static double contrast(int a, int b) {
        double x = luminance(a), y = luminance(b);
        return (Math.max(x, y) + .05) / (Math.min(x, y) + .05);
    }
}
