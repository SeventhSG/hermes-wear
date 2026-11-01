package cloud.stormlabs.hermescompanion;

import java.util.List;
import org.json.JSONObject;

/** App-facing agent operations. HermesApi models remain the shared transcript and run models. */
public interface AgentBackend {
    Capabilities capabilitiesForUi();
    default boolean supportsAgentSettings() { return false; }
    /** The server runs its own slash commands (Claude Code and Codex bridges); Hermes goes through the hub instead. */
    default boolean supportsCommands() { return false; }
    default List<String[]> commandCatalog() throws Exception { return java.util.Collections.emptyList(); }
    default JSONObject runCommand(String sessionId, String command) throws Exception { throw new UnsupportedOperationException("Slash commands are unavailable for this server"); }
    default JSONObject getSessionSettings(String id) throws Exception { throw new UnsupportedOperationException("Agent settings are unavailable for this server"); }
    default HermesApi.Result setSessionSettings(String id, JSONObject fields) { return HermesApi.Result.error("Agent settings are unavailable for this server"); }
    HermesApi.Result health();
    JSONObject capabilities() throws Exception;
    JSONObject healthDetailed() throws Exception;
    List<HermesApi.SessionInfo> listSessions() throws Exception;
    List<HermesApi.SessionInfo> listSessions(int limit, int offset) throws Exception;
    HermesApi.SessionInfo getSession(String id) throws Exception;
    HermesApi.SessionInfo createSession(String title) throws Exception;
    HermesApi.SessionInfo ensureSession(String title) throws Exception;
    HermesApi.SessionInfo forkSession(String id, String title) throws Exception;
    HermesApi.Result renameSession(String id, String title);
    HermesApi.Result deleteSession(String id);
    HermesApi.Result pinSession(String id, boolean pinned);
    HermesApi.Result archiveSession(String id, boolean archived);
    HermesApi.Result markUnread(String id, boolean unread);
    List<HermesApi.Message> messages(String id, int limit, int offset) throws Exception;
    void streamChat(String id, Object input, String systemMessage, HermesApi.StreamHandle handle, HermesApi.StreamListener listener);
    void followRun(String runId, HermesApi.StreamHandle handle, HermesApi.StreamListener listener);
    HermesApi.Result stopRun(String id);
    HermesApi.Result steerRun(String id, String text);
    HermesApi.Result approveRun(String id, String choice, String requestId);
    /** Hermes durable runs also need the turn generation; the bridges ignore it. */
    default HermesApi.Result approveRun(String id, String choice, String requestId, int generation) { return approveRun(id, choice, requestId); }
    List<HermesApi.ModelOption> modelOptions() throws Exception;
    HermesApi.Result setSessionModel(String id, String provider, String model);
    byte[] fetchBytes(String url, int maxBytes) throws Exception;
    List<HermesApi.Skill> skillList() throws Exception;
    List<HermesApi.Toolset> toolsetList() throws Exception;
    List<HermesApi.Job> jobs() throws Exception;
    HermesApi.Job createJob(String name, String schedule, String prompt, String deliver, List<String> skills, Integer repeat) throws Exception;
    HermesApi.Job updateJob(String id, JSONObject fields) throws Exception;
    HermesApi.Result deleteJob(String id);
    HermesApi.Result pauseJob(String id);
    HermesApi.Result resumeJob(String id);
    HermesApi.Result runJob(String id);
}
