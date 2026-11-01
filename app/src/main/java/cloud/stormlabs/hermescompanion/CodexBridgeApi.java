package cloud.stormlabs.hermescompanion;

/**
 * Codex remote (bridge/codex_bridge.py): the Hermes session and run wire contract over the local Codex
 * app-server daemon, so terminal Codex sessions show up here live. No jobs, skills, toolsets or hub.
 */
public final class CodexBridgeApi extends HermesApi {
    public CodexBridgeApi(String base, String key, String memoryScope) { super(base, key, memoryScope); }

    static final Capabilities CODEX = new Capabilities(false, false, false, true, true, true, false, true, false, true, true, true);

    @Override public boolean supportsAgentSettings() { return true; }

    @Override public boolean supportsCommands() { return true; }

    @Override public Capabilities capabilitiesForUi() { return CODEX; }
}
