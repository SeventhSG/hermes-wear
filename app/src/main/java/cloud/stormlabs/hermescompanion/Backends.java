package cloud.stormlabs.hermescompanion;

public final class Backends {
    private Backends() {}

    public static AgentBackend create(SettingsStore store) {
        return create(store, store.profileId());
    }

    static AgentBackend create(SettingsStore store, String profileId) {
        SettingsStore.Connection c = store.connection(profileId);
        if (c == null) throw new IllegalStateException("Profile no longer available");
        AgentBackend backend = create(c.type, c.endpoint, c.key, c.session);
        if (backend instanceof HermesApi && "Hermes".equals(c.type)) {
            ((HermesApi) backend).settingsSource = new HermesSettings(store);
            ((HermesApi) backend).useGateway = store.useGateway();
        }
        return backend;
    }

    /** The harness a client talks to, as {@link SettingsStore#serverType()} names it. */
    static String type(AgentBackend b) { return b instanceof CodexBridgeApi ? "Codex" : b instanceof ClaudeBridgeApi ? "Claude Code" : "Hermes"; }

    static AgentBackend create(String type, String endpoint, String key, String sessionId) {
        switch (type) {
            case "Hermes": return new HermesApi(endpoint, key, sessionId);
            case "Claude Code": return new ClaudeBridgeApi(endpoint, key, sessionId);
            case "Codex": return new CodexBridgeApi(endpoint, key, sessionId);
            default: throw new IllegalArgumentException("Unsupported server type: " + type);
        }
    }
}
