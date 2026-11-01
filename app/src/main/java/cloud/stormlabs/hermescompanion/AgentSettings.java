package cloud.stormlabs.hermescompanion;

import java.util.ArrayList;
import java.util.List;
import org.json.JSONArray;
import org.json.JSONObject;

/** Server-advertised controls; effort values vary with the selected model. */
final class AgentSettings {
    static final class Option {
        final String value, label, description;
        final boolean selected;
        Option(String value, String label, String description, boolean selected) {
            this.value = value; this.label = label; this.description = description; this.selected = selected;
        }
    }

    static List<Option> options(JSONObject settings, boolean permissions) {
        String field = permissions ? "permission_mode" : "reasoning_effort";
        String current = settings.optString(field, "");
        JSONArray values = settings.optJSONArray(permissions ? "permission_options" : "reasoning_options");
        List<Option> out = new ArrayList<>();
        if (values == null) return out;
        for (int i = 0; i < values.length(); i++) {
            JSONObject item = values.optJSONObject(i);
            if (item == null) continue;
            String value = item.optString("value", "").trim();
            if (value.isEmpty() || (permissions && "custom".equals(value) && !value.equals(current))) continue;
            out.add(new Option(value, item.optString("label", value), item.optString("description", ""), value.equals(current)));
        }
        return out;
    }
}
