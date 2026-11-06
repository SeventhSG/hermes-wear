package cloud.stormlabs.hermescompanion;

import static org.junit.Assert.*;

import org.junit.Test;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import org.json.JSONObject;

public class ScreenNodesTest {
    private static ScreenNodes.Element el(String id, String text, String description, String hint, String viewId,
            boolean clickable, boolean editable, boolean password) {
        return new ScreenNodes.Element(id, text, description, hint, viewId, "android.widget.EditText",
                clickable, false, editable, false, false, false, false, password, 0, 0, 0, 0);
    }

    @Test public void formatsVisibleDataWithIdsAndFlagsWithoutPasswordText() {
        ScreenNodes.Element button = el("e0", "  Save\n now ", "Submit", "", "com.test:id/save", true, false, false);
        ScreenNodes.Element password = el("e1", "secret", "Password", "", "com.test:id/pass", false, true, true);
        String text = ScreenNodes.format("com.test", Arrays.asList(button, password));
        assertTrue(text.contains("App: com.test"));
        assertTrue(text, text.contains("id=e0 text=Save now desc=Submit view=save clickable"));
        assertTrue(text, text.contains("id=e1 view=pass editable password"));
        assertFalse(text.contains("secret"));
        assertFalse(text.contains("desc=Password"));
        ScreenNodes.Element longNode = el("e2", String.join("", Collections.nCopies(1000, "x")), "", "", "", false, false, false);
        assertTrue(ScreenNodes.format("pkg", Collections.nCopies(100, longNode)).length() <= ScreenNodes.LIMIT);
    }

    @Test public void descendantLabelAppearsOnParentInBothScreenAndJevDescriptions() {
        String label = ScreenNodes.descendantLabel(Arrays.asList("  Wi-Fi\n settings ", "", "  Connected ", "   "));
        assertEquals("Wi-Fi settings · Connected", label);
        ScreenNodes.Element row = new ScreenNodes.Element("e4", "", "", "", "", "android.view.ViewGroup",
                true, false, false, false, false, false, false, false, 0, 0, 100, 48, label);
        assertTrue(ScreenNodes.format("pkg", Collections.singletonList(row)).contains("- id=e4 label=Wi-Fi settings · Connected clickable"));
        assertTrue(row.describe().contains("label=Wi-Fi settings · Connected"));
        assertFalse(row.describe().contains("(unlabeled)"));
        assertEquals("e4", row.id);
        assertEquals(80, ScreenNodes.descendantLabel(Arrays.asList(String.join("", Collections.nCopies(100, "x")), "More")).length());
        assertEquals("", ScreenNodes.descendantLabel(Arrays.asList("", "  ")));
    }

    @Test public void formatReportsElementsHiddenByCharAndNodeCaps() {
        ScreenNodes.Element longNode = el("e0", String.join("", Collections.nCopies(180, "x")), "", "", "", true, false, false);
        List<ScreenNodes.Element> elements = Collections.nCopies(100, longNode);
        String formatted = ScreenNodes.format("pkg", elements);
        int shown = 0;
        for (String line : formatted.split("\n")) if (line.startsWith("- id=")) shown++;
        assertTrue(shown > 0 && shown < elements.size());
        assertTrue(formatted.length() <= ScreenNodes.LIMIT);
        assertTrue(formatted.endsWith("(+" + (elements.size() - shown) + " more elements not shown; scroll to see more)\n"));
        assertTrue(ScreenNodes.format("pkg", Collections.singletonList(longNode), 7)
                .endsWith("(+7 more elements not shown; scroll to see more)\n"));
        assertFalse(ScreenNodes.format("pkg", Collections.singletonList(longNode)).contains("more elements"));
    }

    @Test public void matchesTextDescriptionHintViewIdAndOwnIdExactly() {
        ScreenNodes.Element e = el("e3", "Save", "Submit", "Tap to save", "com.test:id/save_button", true, false, false);
        assertTrue(ScreenNodes.matches(e, "save"));
        assertTrue(ScreenNodes.matches(e, "Submit"));
        assertTrue(ScreenNodes.matches(e, "save_button"));
        assertTrue(ScreenNodes.matches(e, "Tap to save"));
        assertTrue(ScreenNodes.matches(e, "e3"));
        assertFalse(ScreenNodes.matches(e, "sav"));
        assertFalse(ScreenNodes.matches(e, ""));
    }

    @Test public void allowsPasswordFields() {
        assertTrue(ScreenNodes.canType(el("e0", "", "", "", "pass", false, true, true)));
        assertTrue(ScreenNodes.canType(el("e0", "", "", "", "message", false, true, false)));
        assertFalse(ScreenNodes.canType(el("e0", "Send", "", "", "send", true, false, false)));
    }

    @Test public void findByIdReturnsMatchOrNull() {
        List<ScreenNodes.Element> elements = Arrays.asList(el("e0", "Timer", "", "", "", true, false, false), el("e1", "Alarm", "", "", "", true, false, false));
        assertEquals("Timer", ScreenNodes.findById(elements, "e0").text);
        assertNull(ScreenNodes.findById(elements, "e9"));
        assertNull(ScreenNodes.findById(elements, ""));
        assertNull(ScreenNodes.findById(elements, null));
    }

    @Test public void typeFallbackPrefersFocusedThenSoleEditable() {
        ScreenNodes.Element focused = new ScreenNodes.Element("e0", "", "", "hint", "", "", false, false, true, false, false, false, true, false, 0, 0, 0, 0);
        ScreenNodes.Element other = new ScreenNodes.Element("e1", "", "", "", "", "", false, false, true, false, false, false, false, false, 0, 0, 0, 0);
        assertEquals("e0", ScreenNodes.typeFallback(Arrays.asList(other, focused)).id); // focused wins even if listed second

        ScreenNodes.Element soleEditable = new ScreenNodes.Element("e2", "", "", "", "", "", false, false, true, false, false, false, false, false, 0, 0, 0, 0);
        ScreenNodes.Element button = new ScreenNodes.Element("e3", "Send", "", "", "", "", true, false, false, false, false, false, false, false, 0, 0, 0, 0);
        assertEquals("e2", ScreenNodes.typeFallback(Arrays.asList(soleEditable, button)).id);

        ScreenNodes.Element another = new ScreenNodes.Element("e4", "", "", "", "", "", false, false, true, false, false, false, false, false, 0, 0, 0, 0);
        assertNull(ScreenNodes.typeFallback(Arrays.asList(soleEditable, another))); // ambiguous: two, none focused
        assertNull(ScreenNodes.typeFallback(Collections.singletonList(button))); // none editable
    }

    @Test public void passwordTypeDescriptionAndOutcomeAreMasked() throws Exception {
        DeviceActions.Action action = new DeviceActions.Action("type", new JSONObject()
                .put("target", "pass").put("text", "secret-value"));
        action.passwordTarget = true;
        assertEquals(DeviceActions.Tier.SENSITIVE, DeviceActions.tier(action));
        assertEquals("Type \"••••••\" into pass", action.describe());
        assertFalse(DeviceActionRunner.fail(action, "Could not type into pass").message.contains("secret-value"));
        DeviceActions.Action plain = new DeviceActions.Action("type", new JSONObject()
                .put("target", "message").put("text", "hello"));
        assertEquals("Type \"hello\" into message", plain.describe());
    }

    @Test public void typeTargetFallsBackToIdWhenNoTextTargetGiven() throws Exception {
        DeviceActions.Action action = new DeviceActions.Action("type", new JSONObject().put("id", "e12").put("text", "hi"));
        assertEquals("e12", action.id());
        assertEquals("Type \"hi\" into e12", action.describe());
    }
}
