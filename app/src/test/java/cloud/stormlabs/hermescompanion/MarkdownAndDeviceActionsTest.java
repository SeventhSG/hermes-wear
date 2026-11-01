package cloud.stormlabs.hermescompanion;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;
import org.json.JSONObject;

import java.util.Arrays;
import java.util.List;

public class MarkdownAndDeviceActionsTest {
    @Test public void blocksAreRecognised() {
        String md = "# Title\n\nSome **bold** text\nsecond line\n\n- one\n- two\n  continued\n1. first\n\n```python\nprint(1)\n```\n> quoted\n---\n![chart](data:image/png;base64,AAAA)\n| a | b |\n|---|---|\n| 1 | 2 |";
        List<Markdown.Block> b = Markdown.parse(md);
        assertEquals(Markdown.Kind.HEADING, b.get(0).kind);
        assertEquals(1, b.get(0).level);
        assertEquals(Markdown.Kind.PARAGRAPH, b.get(1).kind);
        assertEquals("Some **bold** text\nsecond line", b.get(1).text);
        assertEquals(Markdown.Kind.BULLET, b.get(2).kind);
        assertEquals("two continued", b.get(3).text);
        assertEquals(Markdown.Kind.ORDERED, b.get(4).kind);
        assertEquals("1", b.get(4).extra);
        assertEquals(Markdown.Kind.CODE, b.get(5).kind);
        assertEquals("python", b.get(5).extra);
        assertEquals("print(1)", b.get(5).text);
        assertEquals(Markdown.Kind.QUOTE, b.get(6).kind);
        assertEquals(Markdown.Kind.RULE, b.get(7).kind);
        assertEquals(Markdown.Kind.IMAGE, b.get(8).kind);
        assertEquals("data:image/png;base64,AAAA", b.get(8).extra);
        assertEquals(Markdown.Kind.TABLE, b.get(9).kind);
        assertEquals(2, Markdown.tableRows(b.get(9).text).size()); // header + row; separator dropped
    }

    @Test public void unclosedCodeFenceWhileStreaming() {
        List<Markdown.Block> b = Markdown.parse("Here:\n```js\nconst a = 1;");
        assertEquals(2, b.size());
        assertEquals("const a = 1;", b.get(1).text);
    }

    @Test public void inlineSpans() {
        Markdown.Inline in = Markdown.inline("Use **bold**, *it*, `code`, ~~gone~~ and [docs](https://x.io) or https://y.io/a.");
        assertEquals("Use bold, it, code, gone and docs or https://y.io/a.", in.text);
        assertEquals(6, in.spans.size());
        Markdown.Span link = null;
        for (Markdown.Span s : in.spans) if (s.style == Markdown.Style.LINK && "https://y.io/a".equals(s.url)) link = s;
        assertTrue(link != null);
        assertEquals("https://y.io/a", in.text.substring(link.start, link.end));
    }

    @Test public void snakeCaseIsNotItalic() {
        assertEquals("my_var_name", Markdown.inline("my_var_name").text);
        assertEquals("2 * 3 * 4", Markdown.inline("2 * 3 * 4").text);
    }

    @Test public void plainAndSpeakable() {
        String md = "**Done.** See https://example.com\n\n```\na\nb\nc\n```";
        assertEquals("Done. See https://example.com\na\nb\nc", Markdown.plain(md));
        String spoken = Markdown.speakable(md);
        assertTrue(spoken, spoken.contains("a link"));
        assertTrue(spoken, spoken.contains("code on screen"));
    }

    @Test public void deviceBlocksAreExtractedAndHidden() {
        String reply = "Timer set for five minutes.\n\n```device\n{\"action\":\"set_timer\",\"seconds\":300,\"label\":\"Tea\"}\n```\n"
                + "<device>[{\"action\":\"flashlight\",\"on\":true},{\"action\":\"format_disk\"}]</device>";
        DeviceActions.Parsed p = DeviceActions.parse(reply);
        assertEquals("Timer set for five minutes.", p.cleanText);
        assertEquals(2, p.actions.size()); // unknown action dropped
        assertEquals("Timer · 5m · Tea", p.actions.get(0).describe());
        assertEquals("Flashlight on", p.actions.get(1).describe());
    }

    @Test public void malformedBlockNeverLeaks() {
        DeviceActions.Parsed p = DeviceActions.parse("ok\n```device\n{not json\n```");
        assertEquals("ok", p.cleanText);
        assertTrue(p.actions.isEmpty());
    }

    @Test public void streamingHidesPartialDeviceBlock() {
        assertEquals("Setting it now.\n\n", DeviceActions.stripForDisplay("Setting it now.\n\n```device\n{\"action\":\"set_ti"));
        assertFalse(DeviceActions.stripForDisplay("x <device>{\"a\"").contains("device"));
    }

    @Test public void streamingHidesSeveralCompleteAndUnclosedBlocks() {
        assertEquals("before  middle  after", DeviceActions.stripForDisplay(
                "before ```device\n{}\n``` middle <device>{}</device> after"));
        assertEquals("before ", DeviceActions.stripForDisplay("before ```HERMES-DEVICE\n{}"));
        assertEquals("before <device>one ", DeviceActions.stripForDisplay("before <device>one <device>two"));
    }

    @Test(timeout = 5000) public void manyUnclosedDeviceTagsStayFast() {
        StringBuilder text = new StringBuilder("start ");
        for (int i = 0; i < 20000; i++) text.append("<device>x");
        assertEquals(text.substring(0, text.lastIndexOf("<device>")), DeviceActions.stripForDisplay(text.toString()));
    }

    @Test public void bracketsAndLinksKeepTheirTextAndSpans() {
        Markdown.Inline in = Markdown.inline("[loose [docs](https://x.io) and [open **bold**");
        assertEquals("loose [docs and [open bold", in.text);
        assertEquals(2, in.spans.size());
        assertEquals(Markdown.Style.LINK, in.spans.get(0).style);
        assertEquals(Markdown.Style.BOLD, in.spans.get(1).style);
    }

    @Test(timeout = 5000) public void manyUnclosedBracketsStayFast() {
        StringBuilder text = new StringBuilder();
        for (int i = 0; i < 30000; i++) text.append('[');
        text.append("tail");
        assertEquals(text.toString(), Markdown.inline(text.toString()).text);
    }

    @Test public void instructionsListEverySupportedAction() {
        String text = DeviceActions.instructions();
        for (String a : DeviceActions.SUPPORTED) assertTrue(a, text.contains("- " + a + " "));
    }

    @Test public void newScreenActionsFollowTapsTierExceptWait() throws Exception {
        for (String type : new String[]{"long_press", "swipe", "enter"}) {
            assertEquals(type, DeviceActions.Tier.SENSITIVE, DeviceActions.tier(type));
            assertTrue(type, DeviceActions.isScreenStep(type));
            assertTrue(type, DeviceActions.needsConfirmation(new DeviceActions.Action(type, new JSONObject()), "Ask"));
            assertFalse(type, DeviceActions.needsConfirmation(new DeviceActions.Action(type, new JSONObject()), "Full access"));
        }
        assertEquals(DeviceActions.Tier.LOCAL, DeviceActions.tier("wait"));
        assertFalse(DeviceActions.isScreenStep("wait"));
        assertFalse(DeviceActions.needsConfirmation(new DeviceActions.Action("wait", new JSONObject()), "Ask"));
    }

    @Test public void newScreenActionsDescribeThemselves() throws Exception {
        assertEquals("Long-press Send", new DeviceActions.Action("long_press", new JSONObject().put("target", "Send")).describe());
        assertEquals("Long-press e7", new DeviceActions.Action("long_press", new JSONObject().put("id", "e7")).describe());
        assertEquals("Swipe up", new DeviceActions.Action("swipe", new JSONObject().put("direction", "up")).describe());
        assertEquals("Press enter", new DeviceActions.Action("enter", new JSONObject()).describe());
        assertEquals("Wait 500ms", new DeviceActions.Action("wait", new JSONObject().put("ms", 500)).describe());
        assertEquals("Wait 3000ms", new DeviceActions.Action("wait", new JSONObject().put("ms", 999999)).describe()); // capped
    }

    @Test public void tapAndTypeFallBackToIdWhenNoTextTarget() throws Exception {
        DeviceActions.Action tap = new DeviceActions.Action("tap", new JSONObject().put("id", "e4"));
        assertEquals("e4", tap.id());
        assertEquals("Tap e4", tap.describe());
    }

    @Test public void launcherLabelMatchingPrefersExactThenPrefixThenContains() {
        List<String> labels = Arrays.asList("World Clock", "Clock Timer", "cLoCk", "Timer");
        assertEquals(2, DeviceActions.bestAppLabelIndex(labels, "Clock"));
        assertEquals(1, DeviceActions.bestAppLabelIndex(labels, "clock t"));
        assertEquals(0, DeviceActions.bestAppLabelIndex(labels, "world"));
        assertEquals(0, DeviceActions.bestAppLabelIndex(labels, "rld clo"));
        assertEquals(-1, DeviceActions.bestAppLabelIndex(labels, "missing"));
    }

    @Test public void openAppAcceptsPromptNameAndCommonAliases() throws Exception {
        for (String key : Arrays.asList("name", "app", "label", "package")) {
            DeviceActions.Action action = new DeviceActions.Action("open_app", new JSONObject().put(key, "Clock"));
            assertEquals("Clock", action.appName());
            assertEquals("Open Clock", action.describe());
        }
        DeviceActions.Action open = new DeviceActions.Action("open_app", new JSONObject().put("label", "Clock"));
        DeviceActionRunner.Outcome outcome = DeviceActionRunner.okResult(open, "com.sec.android.app.clockpackage");
        assertEquals("com.sec.android.app.clockpackage", DeviceActionRunner.screenStepApp(Arrays.asList(outcome)));
    }

    @Test public void queryResultsBecomeAFollowUpOnlyForQueries() {
        DeviceActions.Parsed p = DeviceActions.parse("Checking.\n```device\n{\"action\":\"get_battery\"}\n```\n```device\n{\"action\":\"flashlight\",\"on\":true}\n```");
        assertEquals(2, p.actions.size());
        assertTrue(DeviceActions.isQuery(p.actions.get(0).type));
        assertFalse(DeviceActions.isQuery(p.actions.get(1).type));
        java.util.List<DeviceActionRunner.Outcome> outcomes = new java.util.ArrayList<>();
        outcomes.add(DeviceActionRunner.okResult(p.actions.get(0), "81%, charging"));
        outcomes.add(new DeviceActionRunner.Outcome(p.actions.get(1), true, "Flashlight on"));
        String msg = DeviceActionRunner.resultsMessage(outcomes);
        assertTrue(msg, msg.startsWith(DeviceActionRunner.RESULTS_PREFIX));
        assertTrue(msg, msg.contains("81%, charging"));
        assertFalse(msg, msg.contains("Flashlight"));
        outcomes.remove(0);
        assertEquals(null, DeviceActionRunner.resultsMessage(outcomes));
    }
}
