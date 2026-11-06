package cloud.stormlabs.hermescompanion;

import java.util.Locale;

/** Presentation for Hermes tool names: a readable label, a glyph and a colour per tool family. */
final class Tools {
    private Tools() {}

    static String label(String name) {
        if (name == null || name.trim().isEmpty()) return "Tool";
        String x = name.trim().replace('_', ' ').replace('.', ' ');
        return Character.toUpperCase(x.charAt(0)) + x.substring(1);
    }

    static String glyph(String n) {
        String s = n == null ? "" : n.toLowerCase(Locale.ROOT);
        if (s.equals("terminal") || s.contains("shell") || s.contains("process")) return ">_";
        if (s.startsWith("web") || s.contains("search")) return "W";
        if (s.contains("browser")) return "B";
        if (s.contains("file") || s.contains("patch") || s.contains("read") || s.contains("write")) return "F";
        if (s.contains("memory")) return "M";
        if (s.contains("skill")) return "S";
        if (s.contains("delegate") || s.contains("agent")) return "A";
        if (s.contains("image") || s.contains("vision")) return "I";
        if (s.contains("cron") || s.contains("schedule")) return "⏱";
        if (s.contains("home") || s.startsWith("ha_")) return "H";
        return "T";
    }

    static int color(String n) {
        String s = n == null ? "" : n.toLowerCase(Locale.ROOT);
        if (s.equals("terminal") || s.contains("shell") || s.contains("process")) return 0xff4f635f;
        if (s.startsWith("web") || s.contains("search")) return 0xff4f86c6;
        if (s.contains("browser")) return 0xff3f7fa0;
        if (s.contains("file") || s.contains("patch")) return 0xffb07a47;
        if (s.contains("memory")) return 0xff8b6aae;
        if (s.contains("delegate") || s.contains("agent")) return 0xffc06868;
        return 0xff6f64c2;
    }

    /** One plain-language line for a tool call's JSON arguments. */
    static String summary(HermesApi.ToolCall t) {
        try {
            org.json.JSONObject o = new org.json.JSONObject(t.arguments);
            org.json.JSONArray qs = o.optJSONArray("queries");
            if (qs != null && qs.length() > 0) {
                StringBuilder b = new StringBuilder("Searched: ");
                for (int i = 0; i < qs.length(); i++) b.append(i > 0 ? ", " : "").append(qs.optString(i));
                return b.toString();
            }
            String q = o.optString("query", "");
            if (!q.isEmpty()) return "Searched: " + q;
            String u = o.optString("url", "");
            if (!u.isEmpty()) return "Opened " + u.replaceFirst("^https?://", "");
            String p = o.optString("path", "");
            if (!p.isEmpty()) return p;
        } catch (Exception ignored) {}
        return t.summary();
    }
}
