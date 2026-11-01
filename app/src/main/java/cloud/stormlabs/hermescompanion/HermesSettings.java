package cloud.stormlabs.hermescompanion;

import org.json.JSONArray;
import org.json.JSONObject;

/**
 * Reasoning effort and permissions for plain Hermes, in the same shape the Codex bridge serves. Effort is
 * remembered per conversation here and sent with each run as {@code model_options}; the approval mode is a
 * server-wide setting, changed through the hub.
 */
final class HermesSettings implements HermesApi.SettingsSource {
    private static final String[][] EFFORTS = {
            {"default", "Default", "Whatever Hermes is set to use"},
            {"low", "Low", "Quick answers, least thinking"},
            {"medium", "Medium", "Balanced"},
            {"high", "High", "Think it through"},
            {"xhigh", "Extra high", "Deeper reasoning, slower"},
            {"max", "Max", "As much thinking as the model allows"}};
    private final SettingsStore store;

    HermesSettings(SettingsStore store) { this.store = store; }

    @Override public JSONObject get(String sessionId) throws Exception {
        String effort = store.agentEffort(sessionId);
        JSONArray efforts = new JSONArray();
        for (String[] e : EFFORTS) efforts.put(new JSONObject().put("value", e[0]).put("label", e[1]).put("description", e[2]));
        JSONObject out = new JSONObject()
                .put("reasoning_effort", effort.isEmpty() ? "default" : effort).put("reasoning_options", efforts)
                .put("reasoning_scope", "Applies from your next message in this conversation.")
                .put("permission_scope", "Applies to everything Hermes does, in every conversation.");
        if (store.hubUrl().isEmpty()) {
            out.put("permission_note", "Permissions need the hub: add its address in Settings first.");
            return out;
        }
        try {
            JSONObject hub = new HubApi(store.hubUrl(), store.hubKey()).approvals();
            out.put("permission_mode", hub.optString("mode", "")).put("permission_options", hub.optJSONArray("options"));
        } catch (Exception e) {
            out.put("permission_note", String.valueOf(e.getMessage()).startsWith("HTTP 404")
                    ? "The hub is too old for this: update it from the server kit." : "Could not reach the hub: " + HermesApi.friendly(e));
        }
        return out;
    }

    @Override public HermesApi.Result set(String sessionId, JSONObject fields) {
        try {
            if (fields.has("reasoning_effort")) {
                String effort = fields.optString("reasoning_effort", "");
                store.agentEffort(sessionId, "default".equals(effort) ? "" : effort);
                return HermesApi.Result.ok("Reasoning set to " + effort + " from your next message.");
            }
            if (fields.has("permission_mode")) {
                if (store.hubUrl().isEmpty()) return HermesApi.Result.error("Permissions need the hub: add its address in Settings first.");
                String mode = fields.optString("permission_mode", "");
                new HubApi(store.hubUrl(), store.hubKey()).setApprovals(mode);
                return HermesApi.Result.ok("Permissions set to " + mode + " for all of Hermes.");
            }
            return HermesApi.Result.error("Nothing to change");
        } catch (Exception e) { return HermesApi.Result.error(HermesApi.friendly(e)); }
    }

    @Override public JSONObject runOptions(String sessionId) {
        String effort = store.agentEffort(sessionId);
        try { return effort.isEmpty() ? null : new JSONObject().put("reasoning_effort", effort); }
        catch (Exception e) { return null; }
    }
}
