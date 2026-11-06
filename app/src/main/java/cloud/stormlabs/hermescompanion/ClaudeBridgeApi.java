package cloud.stormlabs.hermescompanion;

import java.util.List;
import org.json.JSONObject;

/** Claude Code remote over the Hermes session and run wire contract. */
public final class ClaudeBridgeApi extends HermesApi {
    public ClaudeBridgeApi(String base, String key, String memoryScope) { super(base, key, memoryScope); }

    @Override public boolean supportsAgentSettings() { return true; }

    @Override public boolean supportsCommands() { return true; }

    @Override public Capabilities capabilitiesForUi() {
        return new Capabilities(false, true, false, true, true, true, false, true, false, true, true, true);
    }
    @Override public List<HermesApi.Toolset> toolsetList() throws Exception {
        throw new UnsupportedOperationException("Claude Code bridge does not support toolsets yet");
    }
    @Override public List<HermesApi.Job> jobs() throws Exception {
        throw new UnsupportedOperationException("Claude Code bridge does not support jobs yet");
    }
    @Override public HermesApi.Job createJob(String name, String schedule, String prompt, String deliver, List<String> skills, Integer repeat) throws Exception {
        throw new UnsupportedOperationException("Claude Code bridge does not support jobs yet");
    }
    @Override public HermesApi.Job updateJob(String jobId, JSONObject fields) throws Exception {
        throw new UnsupportedOperationException("Claude Code bridge does not support jobs yet");
    }
    @Override public HermesApi.Result deleteJob(String jobId) {
        return HermesApi.Result.error("Claude Code bridge does not support jobs yet");
    }
    @Override public HermesApi.Result pauseJob(String jobId) {
        return HermesApi.Result.error("Claude Code bridge does not support jobs yet");
    }
    @Override public HermesApi.Result resumeJob(String jobId) {
        return HermesApi.Result.error("Claude Code bridge does not support jobs yet");
    }
    @Override public HermesApi.Result runJob(String jobId) {
        return HermesApi.Result.error("Claude Code bridge does not support jobs yet");
    }
    @Override public JSONObject healthDetailed() throws Exception {
        return capabilities().put("gateway_state", "running"); // the System screen reads this; a reply means the bridge is up
    }
}
