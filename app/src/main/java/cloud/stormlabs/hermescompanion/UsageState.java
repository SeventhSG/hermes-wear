package cloud.stormlabs.hermescompanion;

import org.json.JSONArray;
import org.json.JSONObject;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Optional harness usage. A snapshot owns its JSON; merging never mutates an earlier snapshot. */
final class UsageState {
    final JSONObject json;
    final long asOf;
    final String plan;
    final Context context;
    final Session session;
    final List<Limit> limits;
    final Resets resets;

    static final class Context {
        final Long usedTokens, windowTokens, autoCompactAt;
        final Double percent;
        final String source;
        Context(JSONObject o) {
            usedTokens = integer(o, "used_tokens"); windowTokens = integer(o, "window_tokens");
            autoCompactAt = integer(o, "auto_compact_at"); source = o.optString("source", "");
            Double p = decimal(o, "percent");
            if (p == null && usedTokens != null && windowTokens != null && windowTokens > 0) p = usedTokens * 100d / windowTokens;
            percent = p == null ? null : clamp(p);
        }
    }
    static final class Session {
        final Long inputTokens, outputTokens, cachedInputTokens, reasoningTokens;
        final Double costUsd;
        final String costLabel;
        Session(JSONObject o) {
            inputTokens = integer(o, "input_tokens"); outputTokens = integer(o, "output_tokens");
            cachedInputTokens = integer(o, "cached_input_tokens"); reasoningTokens = integer(o, "reasoning_tokens");
            costUsd = decimal(o, "cost_usd"); costLabel = o.optString("cost_label", "");
        }
    }
    static final class Limit {
        final String id, label, status;
        final Double usedPercent;
        final long resetsAt;
        Limit(JSONObject o) {
            id = o.optString("id", ""); label = o.optString("label", "Limit"); status = o.optString("status", "");
            Double p = decimal(o, "used_percent"); usedPercent = p == null ? null : clamp(p);
            resetsAt = o.optLong("resets_at", 0);
        }
    }
    static final class Resets {
        final Long available;
        final String label;
        Resets(JSONObject o) { available = integer(o, "available"); label = o.optString("label", "Use a limit reset"); }
    }
    private UsageState(JSONObject o) {
        json = o; asOf = o.optLong("as_of", 0); plan = o.optString("plan", "");
        context = o.optJSONObject("context") == null ? null : new Context(o.optJSONObject("context"));
        session = o.optJSONObject("session") == null ? null : new Session(o.optJSONObject("session"));
        resets = o.optJSONObject("resets") == null ? null : new Resets(o.optJSONObject("resets"));
        List<Limit> rows = new ArrayList<>(); JSONArray a = o.optJSONArray("limits");
        if (a != null) for (int i = 0; i < a.length(); i++) if (a.optJSONObject(i) != null) rows.add(new Limit(a.optJSONObject(i)));
        limits = Collections.unmodifiableList(rows);
    }
    static UsageState parse(JSONObject o) {
        try { return new UsageState(o == null ? new JSONObject() : new JSONObject(o.toString())); }
        catch (Exception e) { return new UsageState(new JSONObject()); }
    }
    static UsageState merge(UsageState state, JSONObject event) {
        JSONObject copy = parse(state == null ? null : state.json).json;
        if (event != null) for (String key : new String[]{"as_of", "context", "session", "limits", "plan", "resets", "yolo"}) {
            if (event.has(key)) try { copy.put(key, event.get(key)); } catch (Exception ignored) { }
        }
        return parse(copy);
    }
    static String resetsIn(long now, long resetsAt) {
        if (resetsAt <= 0) return "";
        if (resetsAt <= now) return "now";
        long minutes = (resetsAt - now + 59) / 60;
        return minutes < 60 ? minutes + "m" : minutes / 60 + "h" + (minutes % 60 == 0 ? "" : " " + minutes % 60 + "m");
    }
    private static double clamp(double p) { return Math.max(0, Math.min(100, p)); }
    private static Long integer(JSONObject o, String key) { return !o.has(key) || o.isNull(key) || !(o.opt(key) instanceof Number) ? null : Math.max(0, o.optLong(key)); }
    private static Double decimal(JSONObject o, String key) {
        double d = o.optDouble(key, Double.NaN); return Double.isFinite(d) ? d : null;
    }
}
