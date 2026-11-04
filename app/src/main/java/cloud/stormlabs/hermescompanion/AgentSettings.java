package cloud.stormlabs.hermescompanion;

import java.util.ArrayList;
import java.util.List;
import org.json.JSONArray;
import org.json.JSONObject;

/** Server-advertised controls; effort values vary with the selected model. */
final class AgentSettings {
    static final class Option {
        final String value, label, description;
        final boolean selected, needsConfirm;
        Option(String value, String label, String description, boolean selected) {
            this.value = value; this.label = label; this.description = description; this.selected = selected; this.needsConfirm = needsConfirm(value);
        }
    }

    static boolean needsConfirm(String value) {
        return "bypassPermissions".equals(value) || "bypass".equals(value) || "full-access".equals(value)
                || "off".equals(value) || "yolo_chat".equals(value);
    }

    private static String permissionLabel(String value, String fallback) {
        switch (value) {
            case "default": return "Default";
            case "acceptEdits": return "Accept edits";
            case "plan": return "Plan";
            case "bypassPermissions": return "Bypass";
            case "read-only": return "Read only";
            case "workspace-write": return "Workspace";
            case "full-access": return "Full access";
            case "manual": return "Ask";
            case "smart": return "Smart";
            case "off": return "Approvals off (all chats)";
            case "yolo_chat": return "YOLO in this chat";
            default: return fallback;
        }
    }

    static Option next(JSONObject settings) {
        List<Option> cycle = new ArrayList<>();
        for (Option o : options(settings, true)) if (!"custom".equals(o.value)) cycle.add(o);
        if (cycle.size() < 2) return null;
        for (int i = 0; i < cycle.size(); i++) if (cycle.get(i).selected) return cycle.get((i + 1) % cycle.size());
        return cycle.get(0);
    }

    static Boolean planMode(JSONObject settings) {
        return settings.opt("plan_mode") instanceof Boolean ? settings.optBoolean("plan_mode") : null;
    }

    static List<Option> fastOptions(JSONObject settings) { return options(settings, "fast_mode", "fast_options", false); }

    static List<Option> options(JSONObject settings, boolean permissions) {
        return options(settings, permissions ? "permission_mode" : "reasoning_effort",
                permissions ? "permission_options" : "reasoning_options", permissions);
    }

    private static List<Option> options(JSONObject settings, String field, String optionsField, boolean permissions) {
        String current = settings.optString(field, "");
        JSONArray values = settings.optJSONArray(optionsField);
        List<Option> out = new ArrayList<>();
        if (values == null) return out;
        for (int i = 0; i < values.length(); i++) {
            JSONObject item = values.optJSONObject(i);
            if (item == null) continue;
            String value = item.optString("value", "").trim();
            if (value.isEmpty() || (permissions && "custom".equals(value) && !value.equals(current))) continue;
            String label = item.optString("label", value);
            out.add(new Option(value, permissions ? permissionLabel(value, label) : label, item.optString("description", ""), value.equals(current)));
        }
        return out;
    }
}
