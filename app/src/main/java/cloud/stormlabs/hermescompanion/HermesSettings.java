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
    private final String endpoint, apiKey, hubUrl, hubKey;
    private String fastMode = "";

    HermesSettings(SettingsStore store) {
        this.store = store;
        endpoint = store.endpoint(); apiKey = store.apiKey(); hubUrl = store.hubUrl(); hubKey = store.hubKey();
    }

    @Override public JSONObject get(String sessionId) throws Exception {
        String effort = store.agentEffort(sessionId);
        JSONArray efforts = new JSONArray();
        for (String[] e : EFFORTS) efforts.put(new JSONObject().put("value", e[0]).put("label", e[1]).put("description", e[2]));
        JSONObject out = new JSONObject()
                .put("reasoning_effort", effort.isEmpty() ? "default" : effort).put("reasoning_options", efforts)
                .put("reasoning_scope", "Applies from your next message in this conversation.")
                .put("permission_scope", "Applies to everything Hermes does, in every conversation.");
        JSONArray options = new JSONArray();
        if (hubUrl.isEmpty()) out.put("permission_note", "Permissions need the hub: add its address in Settings first.");
        else try {
            JSONObject hub = new HubApi(hubUrl, hubKey).approvals();
            JSONArray advertised = hub.optJSONArray("options");
            for (String value : new String[]{"manual", "smart", "off"}) {
                if (advertised != null) for (int i = 0; i < advertised.length(); i++) {
                    JSONObject option = advertised.optJSONObject(i);
                    if (option != null && value.equals(option.optString("value"))) {
                        option.put("label", "manual".equals(value) ? "Ask" : "smart".equals(value) ? "Smart" : "Approvals off (all chats)");
                        options.put(option);
                    }
                }
            }
            out.put("permission_mode", hub.optString("mode", ""));
        } catch (Exception e) {
            out.put("permission_note", String.valueOf(e.getMessage()).startsWith("HTTP 404")
                    ? "The hub is too old for this: update it from the server kit." : "Could not reach the hub: " + HermesApi.friendly(e));
        }
        try {
            JSONObject usage = gateway().usage(sessionId, false);
            if (usage.opt("yolo") instanceof Boolean) {
                options.put(new JSONObject().put("value", "yolo_chat").put("label", "YOLO in this chat")
                        .put("description", "Skip approvals for this gateway conversation only."));
                if (usage.optBoolean("yolo")) out.put("permission_mode", "yolo_chat");
            }
        } catch (Exception ignored) { /* An old gateway must not advertise an unsupported control. */ }
        if (options.length() > 0) out.put("permission_options", options);
        if (!hubUrl.isEmpty()) try {
            for (String[] command : new HubApi(hubUrl, hubKey).hermesCommands()) if ("fast".equals(command[0])) {
                out.put("fast_mode", fastMode).put("fast_options", new JSONArray()
                        .put(new JSONObject().put("value", "normal").put("label", "Standard").put("description", "Shared hermes serve setting; affects chats through the hub."))
                        .put(new JSONObject().put("value", "fast").put("label", "Fast").put("description", "Shared hermes serve setting; affects chats through the hub.")));
                break;
            }
        } catch (Exception ignored) { }
        return out;
    }

    @Override public HermesApi.Result set(String sessionId, JSONObject fields) {
        try {
            if (fields.has("fast_mode")) {
                String value = fields.optString("fast_mode");
                if (!"normal".equals(value) && !"fast".equals(value)) return HermesApi.Result.error("Unknown fast mode");
                JSONObject reply = new HubApi(hubUrl, hubKey).hermesSlash(HubApi.gatewayProfile(endpoint), sessionId, "/fast " + value);
                if (reply.has("error") || "error".equals(reply.optString("type")) || !reply.optBoolean("ok", true)) return HermesApi.Result.error(reply.optString("error", "Could not change fast mode"));
                fastMode = value;
                return HermesApi.Result.ok("Fast mode set to " + value + " on hermes serve (shared setting).");
            }
            if (fields.has("reasoning_effort")) {
                String effort = fields.optString("reasoning_effort", "");
                store.agentEffort(sessionId, "default".equals(effort) ? "" : effort);
                return HermesApi.Result.ok("Reasoning set to " + effort + " from your next message.");
            }
            if (fields.has("permission_mode")) {
                String mode = fields.optString("permission_mode", "");
                if ("yolo_chat".equals(mode)) {
                    gateway().gatewaySettings(sessionId, true);
                    return HermesApi.Result.ok("YOLO is on for this gateway conversation.");
                }
                if (hubUrl.isEmpty()) return HermesApi.Result.error("Permissions need the hub: add its address in Settings first.");
                if (!"manual".equals(mode) && !"smart".equals(mode) && !"off".equals(mode)) return HermesApi.Result.error("Unknown permission mode");
                HermesApi gateway = gateway();
                if (gateway.gatewayWanted()) {
                    JSONObject usage;
                    try { usage = gateway.usage(sessionId, false); }
                    catch (Exception e) {
                        String message = String.valueOf(e.getMessage());
                        if (!message.startsWith("HTTP 404") && !message.startsWith("HTTP 501")) throw e;
                        usage = null;
                    }
                    if (usage != null && usage.optBoolean("yolo")) gateway.gatewaySettings(sessionId, false);
                }
                new HubApi(hubUrl, hubKey).setApprovals(mode);
                return HermesApi.Result.ok("Permissions set to " + mode + " for all of Hermes.");
            }
            return HermesApi.Result.error("Nothing to change");
        } catch (Exception e) { return HermesApi.Result.error(HermesApi.friendly(e)); }
    }

    private HermesApi gateway() {
        HermesApi api = new HermesApi(endpoint, apiKey, store.sessionId());
        api.useGateway = store.useGateway();
        return api;
    }

    @Override public JSONObject usage(String sessionId, boolean refresh) throws Exception {
        if (hubUrl.isEmpty()) throw new UnsupportedOperationException("Usage needs the gateway or hub");
        return new HubApi(hubUrl, hubKey).hermesUsage(HubApi.gatewayProfile(endpoint), sessionId, refresh);
    }

    @Override public JSONObject runOptions(String sessionId) {
        String effort = store.agentEffort(sessionId);
        try { return effort.isEmpty() ? null : new JSONObject().put("reasoning_effort", effort); }
        catch (Exception e) { return null; }
    }
}
