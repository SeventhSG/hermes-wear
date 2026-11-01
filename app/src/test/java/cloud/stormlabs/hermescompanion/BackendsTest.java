package cloud.stormlabs.hermescompanion;

import static org.junit.Assert.*;
import org.junit.Test;

public class BackendsTest {
    @Test public void agentSettingsNeedACodexClaudeOrLocalSettingsSource() {
        assertTrue(Backends.create("Codex", "http://localhost", "key", "session").supportsAgentSettings());
        AgentBackend hermes = Backends.create("Hermes", "http://localhost", "key", "session");
        assertFalse(hermes.supportsAgentSettings());
        assertTrue(Backends.create("Claude Code", "http://localhost", "key", "session").supportsAgentSettings());
        // Plain Hermes gets its settings source from Backends.create(store, profile); bare, it offers none.
        assertFalse(hermes.setSessionSettings("session-1", null).ok);
        try { hermes.getSessionSettings("session-1"); fail("Expected unsupported settings"); }
        catch (UnsupportedOperationException expected) { }
        catch (Exception e) { fail("Unexpected exception: " + e); }
    }

    @Test public void hermesFactoryKeepsAllFeatures() {
        AgentBackend backend = Backends.create("Hermes", "http://localhost", "key", "session");
        assertTrue(backend instanceof HermesApi);
        Capabilities c = backend.capabilitiesForUi();
        assertTrue(c.jobs && c.skills && c.toolsets && c.approvals && c.steer && c.reasoning
                && c.sessionSearch && c.images && c.hub);
    }

    @Test public void commandGateFollowsCapabilities() {
        Capabilities c = new Capabilities(false, false, true, false, false, true, true, false, false, false, false, false);
        assertFalse(c.allowsCommand("jobs"));
        assertFalse(c.allowsCommand("skills"));
        assertFalse(c.allowsCommand("steer"));
        assertFalse(c.allowsCommand("model"));
        assertFalse(c.allowsCommand("fork"));
        assertFalse(c.allowsCommand("delete"));
        assertFalse(c.allowsCommand("server"));
        assertTrue(c.allowsCommand("tools"));
        assertTrue(c.allowsCommand("new"));
    }

    @Test public void claudeCodeFactorySelectsBridge() {
        AgentBackend backend = Backends.create("Claude Code", "http://localhost", "key", "session");
        assertTrue(backend instanceof ClaudeBridgeApi);
    }

    @Test public void claudeCodeBridgeAdvertisesWhatItImplements() {
        Capabilities c = new ClaudeBridgeApi("http://localhost", "key", "session").capabilitiesForUi();
        assertTrue(c.skills); assertTrue(c.approvals); assertTrue(c.steer); assertTrue(c.reasoning);
        assertTrue(c.images); assertTrue(c.models); assertTrue(c.forks); assertTrue(c.deletes);
        assertFalse(c.jobs); assertFalse(c.toolsets); assertFalse(c.sessionSearch); assertFalse(c.hub);
    }

    @Test public void claudeCodeUnsupportedCallsFailLocally() {
        ClaudeBridgeApi bridge = new ClaudeBridgeApi("http://localhost", "key", "session");
        try { bridge.toolsetList(); fail("expected UnsupportedOperationException"); }
        catch (UnsupportedOperationException expected) {}
        catch (Exception e) { fail("expected UnsupportedOperationException, got " + e); }
        assertFalse(bridge.pauseJob("job-1").ok);
    }

    @Test public void hermesFactoryUnaffectedByClaudeBridge() {
        AgentBackend backend = Backends.create("Hermes", "http://localhost", "key", "session");
        assertFalse(backend instanceof ClaudeBridgeApi);
        assertTrue(backend instanceof HermesApi);
    }
}
