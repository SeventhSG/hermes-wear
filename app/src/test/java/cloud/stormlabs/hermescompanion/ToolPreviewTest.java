package cloud.stormlabs.hermescompanion;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class ToolPreviewTest {
    @Test public void aStartedToolShowsItsPreviewWhenTheServerSendsNoArguments() {
        assertEquals("{\"command\":\"ls -la /srv\"}", HermesApi.ToolCall.startArgs("terminal", "ls -la /srv", ""));
        assertEquals("{\"preview\":\"cats\"}", HermesApi.ToolCall.startArgs("web_search", "cats", "{}"));
        assertEquals("{\"query\":\"cats\"}", HermesApi.ToolCall.startArgs("web_search", "x", "{\"query\":\"cats\"}"));
        assertEquals("{}", HermesApi.ToolCall.startArgs("terminal", "", ""));
        assertEquals("ls -la /srv", HermesApi.ToolCall.live("terminal", HermesApi.ToolCall.startArgs("terminal", "ls -la /srv", null)).summary());
    }

    @Test public void harnessOnlyCommandsNeedTheirOwnBackend() {
        Capabilities codex = CodexBridgeApi.CODEX;
        assertFalse(codex.allowsCommand("undo"));
        assertFalse(codex.allowsCommand("home"));
        assertTrue(codex.allowsCommand("new"));
        assertTrue(Capabilities.HERMES.allowsCommand("undo"));
        assertTrue(codex.images);
    }
}
