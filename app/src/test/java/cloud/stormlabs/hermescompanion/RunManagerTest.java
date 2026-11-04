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

    @Test public void stopButtonFallsBackToTheCurrentProfile() {
        assertEquals("main", RunActionReceiver.profileOrDefault(null, "main"));
        assertEquals("main", RunActionReceiver.profileOrDefault("", "main"));
        assertEquals("work", RunActionReceiver.profileOrDefault("work", "main"));
    }

    @Test public void ownStopIsNotAFailureToNotifyAbout() {
        org.junit.Assert.assertTrue(RunManager.isCancelled("Cancelled"));
        org.junit.Assert.assertFalse(RunManager.isCancelled("Connection lost"));
        org.junit.Assert.assertFalse(RunManager.isCancelled(null));
    }

    @Test public void shownErrorIsNotFinishedAgainOnResume() {
        RunManager.LiveRun run = new RunManager.LiveRun(null, "p", "s", "Hermes", "go", false);
        run.voice = true; run.error = "Server error";
        org.junit.Assert.assertTrue(run.needsFinish());
        run.errorShown();
        org.junit.Assert.assertFalse(run.needsFinish());
    }

    @Test public void toolOutputKeepsOnlyTheNewestChars() {
        assertEquals("abcdef", RunManager.appendCapped("abc", "def", 10));
        assertEquals("cdefgh", RunManager.appendCapped("abcdef", "gh", 6));
        assertEquals("abc", RunManager.appendCapped("abc", null, 6));
        String big = "";
        for (int i = 0; i < 2000; i++) big = RunManager.appendCapped(big, "0123456789", 1000);
        assertEquals(1000, big.length());
    }

    @Test public void parallelToolsUseIdsForOutputAndCompletion() {
        RunManager.LiveRun run = new RunManager.LiveRun(null, "p", "s", "Codex", "go", false);
        run.onToolEvent("started", "terminal", "first", "{}", "one");
        run.onToolEvent("started", "terminal", "second", "{}", "two");
        run.onToolEvent("output", "terminal", "first output", "", "one");
        run.onToolEvent("output", "terminal", "second output", "", "two");
        run.onToolEvent("output", "terminal", "ignored", "", "unknown");
        run.onToolEvent("completed", "terminal", "ignored", "", "unknown");
        assertEquals("first output", run.tools.get(0).result);
        assertEquals("second output", run.tools.get(1).result);
        assertEquals("running", run.tools.get(1).status);
        run.onToolEvent("completed", "terminal", "first final", "", "one");
        assertEquals("completed", run.tools.get(0).status);
        assertEquals("running", run.tools.get(1).status);
        assertEquals("first final", run.tools.get(0).result);
        run.onToolEvent("output", "terminal", " tail", "", "two");
        run.onToolEvent("failed", "terminal", "", "", "two");
        assertEquals("failed", run.tools.get(1).status);
        assertEquals("second output tail", run.tools.get(1).result);
        assertEquals("one", run.tools.get(0).id);
        assertEquals("two", run.tools.get(1).id);
        assertSame(run.tools.get(0), run.timeline.get(0));
        assertSame(run.tools.get(1), run.timeline.get(1));
    }

    @Test public void toolsWithoutIdsKeepLatestRunningNameFallback() {
        RunManager.LiveRun run = new RunManager.LiveRun(null, "p", "s", "Hermes", "go", false);
        run.onToolEvent("started", "terminal", "first", "{}", "");
        run.onToolEvent("started", "terminal", "second", "{}", null);
        run.onToolEvent("output", "terminal", "second output", "", "");
        run.onToolEvent("completed", "terminal", "", "", null);
        assertEquals("running", run.tools.get(0).status);
        assertEquals("completed", run.tools.get(1).status);
        assertEquals("second output", run.tools.get(1).result);
        run.onToolEvent("output", "terminal", "first output", "", null);
        run.onToolEvent("completed", "terminal", "", "", "");
        assertEquals("completed", run.tools.get(0).status);
        assertEquals("first output", run.tools.get(0).result);
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
