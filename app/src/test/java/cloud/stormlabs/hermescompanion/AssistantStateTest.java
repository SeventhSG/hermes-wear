package cloud.stormlabs.hermescompanion;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class AssistantStateTest {
    @Test public void labels() {
        assertEquals("Listening", AssistantState.label(AssistantState.State.LISTENING, 0));
        assertEquals("Thinking", AssistantState.label(AssistantState.State.THINKING, 0));
        assertEquals("Speaking", AssistantState.label(AssistantState.State.SPEAKING, 0));
        assertEquals("Working", AssistantState.label(AssistantState.State.WORKING, 0));
        assertEquals("Working · step 3", AssistantState.label(AssistantState.State.WORKING, 3));
        assertEquals("Hermes", AssistantState.label(AssistantState.State.IDLE, 5));
    }

    @Test public void smoothingAttacksFasterThanItReleasesAndClamps() {
        float up = AssistantState.smooth(0f, 1f), down = 1f - AssistantState.smooth(1f, 0f);
        assertTrue(up > down);
        assertEquals(1f, AssistantState.smooth(.9f, 5f), 1f);
        float v = 0f;
        for (int i = 0; i < 60; i++) v = AssistantState.smooth(v, .8f);
        assertEquals(.8f, v, .01f);
        assertEquals(0f, AssistantState.smooth(0f, -3f), 0f);
    }

    @Test public void suggestionsOnlyWhenIdleAndEmpty() {
        assertTrue(AssistantState.showSuggestions(AssistantState.State.IDLE, false));
        assertFalse(AssistantState.showSuggestions(AssistantState.State.IDLE, true));
        assertFalse(AssistantState.showSuggestions(AssistantState.State.THINKING, false));
    }

    @Test public void errorsAreHumanFirst() {
        assertTrue(AssistantState.humanError("Session not found").startsWith("That conversation is gone"));
        assertTrue(AssistantState.humanError("java.net.UnknownHostException: x").startsWith("Can't reach"));
        assertTrue(AssistantState.humanError("HTTP 401").contains("login"));
        assertEquals("Something went wrong. Try again.", AssistantState.humanError(null));
        assertTrue(AssistantState.humanError("weird").startsWith("Something went wrong."));
    }

    @Test public void agentProgress() {
        assertEquals(0f, AgentOverlay.progress(0, 10), 0f);
        assertEquals(.3f, AgentOverlay.progress(3, 10), .001f);
        assertEquals(1f, AgentOverlay.progress(12, 10), 0f);
        assertEquals(.5f, AgentOverlay.progress(1, 0), .001f);
    }
}
