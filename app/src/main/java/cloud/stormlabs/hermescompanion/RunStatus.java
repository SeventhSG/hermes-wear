package cloud.stormlabs.hermescompanion;

/** Pure wording for the ongoing run notification. */
final class RunStatus {
    private RunStatus() {}

    /** tool is the running tool's name (null if none); arg its short argument. */
    static String text(boolean approval, String tool, String arg, boolean replying) {
        if (approval) return "Needs your approval";
        if (tool != null) {
            String a = arg == null ? "" : arg.replaceAll("\\s+", " ").trim();
            if (a.length() > 60) a = a.substring(0, 57) + "…";
            String n = tool.toLowerCase(java.util.Locale.ROOT);
            String verb = n.contains("read") ? "Reading file" : n.contains("write") || n.contains("patch") ? "Writing file" : "Running " + Tools.label(tool).toLowerCase(java.util.Locale.ROOT);
            return a.isEmpty() ? verb + "…" : verb + (verb.startsWith("Running") ? ": " : " ") + a;
        }
        return replying ? "Writing reply…" : "Thinking…";
    }

    static String step(int tools) { return "step " + Math.max(1, tools); }
}
