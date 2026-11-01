package cloud.stormlabs.hermescompanion;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;

public class NotifierTest {
    @Test public void eventIdentityIncludesHubAndTheWholeEventId() {
        assertNotEquals(Notifier.eventTag("https://first.example", 42), Notifier.eventTag("https://second.example", 42));
        assertNotEquals(Notifier.eventTag("https://first.example", 42), Notifier.eventTag("https://first.example", 42 + 4096));
        assertEquals(Notifier.eventTag("https://first.example/", 42), Notifier.eventTag("https://first.example", 42));
    }

    @Test public void notificationTagsKeepFullKeysDespiteHashCollisions() {
        assertEquals("main\u0000session", Notifier.sessionKey("main", "session"));
        assertNotEquals(Notifier.resultTag("main", "session"), Notifier.resultTag("worker", "session"));
        assertEquals("FB".hashCode(), "Ea".hashCode());
        assertNotEquals(Notifier.resultTag("main", "FB"), Notifier.resultTag("main", "Ea"));
        assertNotEquals(Notifier.approvalTag("main", "FB"), Notifier.approvalTag("main", "Ea"));
        assertNotEquals(Notifier.presenceTag("main", "FB"), Notifier.presenceTag("main", "Ea"));
        assertNotEquals(Notifier.draftTag("FB"), Notifier.draftTag("Ea"));
    }

    @Test public void failedAgentResultShowsReasonInsteadOfLastReply() {
        RunManager.LiveRun run = new RunManager.LiveRun(null, "main", "session", "Hermes", "task", false);
        run.text.append("I will tap Next");
        run.agentFailure = "Ended early: step limit";
        run.actionsHandled = true;
        assertEquals("Ended early: step limit", Notifier.resultBody(run, true));
    }

    @Test public void alertsProposalsAndHighPriorityAreUrgent() {
        org.junit.Assert.assertTrue(Notifier.urgent("alert", "3"));
        org.junit.Assert.assertTrue(Notifier.urgent("proposal", ""));
        org.junit.Assert.assertTrue(Notifier.urgent("info", "4"));
        org.junit.Assert.assertTrue(Notifier.urgent("info", "high"));
        org.junit.Assert.assertFalse(Notifier.urgent("info", "3"));
        org.junit.Assert.assertFalse(Notifier.urgent("done", null));
    }
}
