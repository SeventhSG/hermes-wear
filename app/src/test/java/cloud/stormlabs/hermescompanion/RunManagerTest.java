package cloud.stormlabs.hermescompanion;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;

import org.junit.Test;

public class RunManagerTest {
    @Test public void sessionIdentityIncludesTheOriginProfile() {
        RunManager.LiveRun run = new RunManager.LiveRun(null, "main", "same-session", "Hermes", "text", false);
        org.junit.Assert.assertTrue(run.belongsTo("main", "same-session"));
        org.junit.Assert.assertFalse(run.belongsTo("other", "same-session"));
        org.junit.Assert.assertFalse(run.belongsTo("main", "other-session"));
        org.junit.Assert.assertNotEquals(RunManager.runKey("main", "same-session"), RunManager.runKey("other", "same-session"));
        org.junit.Assert.assertNotEquals(RunManager.runKey("ab", "c"), RunManager.runKey("a", "bc"));
    }

    @Test public void liveRunKeepsOriginalRetryInput() {
        Object payload = new Object();
        RunManager.LiveRun run = new RunManager.LiveRun(null, "profile", "session", "Hermes",
                "📷 1 image", false, payload, "device context");

        assertEquals("📷 1 image", run.userText);
        assertSame(payload, run.payload);
        assertEquals("device context", run.systemMessage);
    }

    @Test public void olderLiveRunHasNoOriginalRetryInput() {
        RunManager.LiveRun run = new RunManager.LiveRun(null, "profile", "session", "Hermes", "text", false);

        assertNull(run.payload);
        assertNull(run.systemMessage);
    }

    @Test public void timelineKeepsMessagesToolsAndSteersInOrder() {
        RunManager.LiveRun run = new RunManager.LiveRun(null, "p", "s", "Codex", "go", false);
        run.appendText("Looking", false); run.appendText(" now.", false);
        run.timeline.add(HermesApi.ToolCall.live("terminal", "{}"));
        run.appendText("\n\n", false);           // whitespace after a tool opens no empty bubble
        run.appendText("Found it.", false);
        org.junit.Assert.assertTrue(run.addSteer("also check logs"));
        org.junit.Assert.assertFalse(run.addSteer("also check logs")); // stream echo of our own steer
        run.appendText("Logs are clean.", false);
        run.appendText("\n\n", true); run.appendText("Done.", false); // bridge-marked new message
        assertEquals(6, run.timeline.size());
        assertEquals("Looking now.", run.timeline.get(0).toString());
        assertEquals("Found it.", run.timeline.get(2).toString());
        assertEquals("also check logs", ((RunManager.LiveRun.Note) run.timeline.get(3)).text);
        assertEquals("Logs are clean.", run.timeline.get(4).toString());
        assertEquals("Done.", run.timeline.get(5).toString());
        assertEquals("Looking now.\n\nFound it.Logs are clean.\n\nDone.", run.text.toString());
    }
}
