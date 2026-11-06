package cloud.stormlabs.hermescompanion;

/** Pure chat helpers (no Android types) so they can be unit-tested. */
final class ChatLogic {
    private ChatLogic() {}

    static boolean serverStatus(String harness, boolean hub, String command) {
        return "Hermes".equals(harness) && hub && "status".equals(command);
    }

    /** "google/gemini-3-flash-preview" becomes "gemini-3-flash-preview"; blank becomes "Default model". */
    static String shortModel(String id) {
        String m = id == null ? "" : id.trim();
        if (m.isEmpty()) return "Default model";
        int slash = m.lastIndexOf('/');
        m = slash >= 0 && slash < m.length() - 1 ? m.substring(slash + 1) : m;
        java.util.regex.Matcher known = java.util.regex.Pattern.compile("claude-(opus|sonnet|haiku|fable)(?:-(\\d+(?:-\\d+)*))?").matcher(m);
        if (known.matches()) {
            String family = known.group(1);
            return Character.toUpperCase(family.charAt(0)) + family.substring(1)
                    + (known.group(2) == null ? "" : " " + known.group(2).replace('-', '.'));
        }
        if (m.equals("opus") || m.equals("sonnet") || m.equals("haiku")) return Character.toUpperCase(m.charAt(0)) + m.substring(1);
        return m;
    }

    /** Empty-chat suggestion chips: {label, prompt sent when tapped}. */
    static final String[][] SUGGESTIONS = {
            {"How's my homelab?", "Check my homelab's health and tell me anything that needs attention."},
            {"What's using the most CPU?", "Which processes or containers are using the most CPU right now?"},
            {"Summarise my unread", "Summarise my unread messages and tell me what needs a reply."}};

    static final String EMPTY_PROMPT = "Ask me anything — I can also check your homelab.";

    static String recoveryDraft(String failed, String draft) {
        String text = failed == null ? "" : failed;
        String current = draft == null ? "" : draft;
        if (text.isEmpty() || current.equals(text) || current.startsWith(text + "\n\n")) return current;
        return current.isEmpty() ? text : text + "\n\n" + current;
    }

    static String draftAfterRecovery(String failed, String draft) {
        if (failed == null || failed.isEmpty()) return draft;
        if (draft.equals(failed)) return "";
        return draft.startsWith(failed + "\n\n") ? draft.substring(failed.length() + 2) : draft;
    }

    static boolean allowNewChat(boolean empty, boolean busy, boolean error) {
        return !empty || busy || error;
    }

    static boolean hermesAction(String title) {
        return "Receipt".equals(title) || "Shopping".equals(title) || "Next actions".equals(title)
                || "Audit server".equals(title) || "Home Assistant".equals(title)
                || "Skills".equals(title) || "Schedule".equals(title) || "Recall".equals(title);
    }

    static String emptyPrompt(String harness, boolean hub) {
        if (!"Hermes".equals(harness)) return "What would you like to build with " + harness + "?";
        return hub ? EMPTY_PROMPT : "What can I help you with?";
    }

    static String[][] suggestions(String harness, boolean hub) {
        if (!"Hermes".equals(harness)) return new String[][] {
                {"Explore this project", "Explain this project's structure and where to start."},
                {"Find a bug", "Review this project for bugs and suggest a focused fix."},
                {"Plan a change", "Help me plan a change to this project. Ask me what I want to build."}};
        return hub ? SUGGESTIONS : new String[][] {
                {"Explain something", "Help me understand a topic. Ask me what I would like to learn."},
                {"Make a plan", "Help me make a practical plan. Ask me what I want to accomplish."},
                {"Help me write", "Help me write something clearly. Ask me what I need to write."}};
    }

    /** Overflow menu groups: 0 primary tiles, 1 "More" rows, 2 destructive (last, separated). */
    static int overflowGroup(String title) {
        if ("Delete".equals(title) || "Delete conversation".equals(title)) return 2;
        if ("Model".equals(title) || "Reasoning effort".equals(title) || "Agent permissions".equals(title)
                || "Settings".equals(title) || "Archive".equals(title)) return 1;
        return 0;
    }

    /** Accessible name of the Hermes mark: it reports presence. */
    static String presence(boolean replying) { return replying ? "Hermes is replying" : "Hermes"; }

    /** Human sentence first, the server's raw reason second (only when there is one). */
    static String runFailure(String raw) { return runFailure("Hermes", raw); }

    static String runFailure(String agent, String raw) {
        String why = raw == null ? "" : raw.trim();
        return agent + " couldn't finish that. Tap to try again." + (why.isEmpty() ? "" : "\n" + why);
    }

    /** Height/width ratio of an image, defaulting to 3:4 until it is known. */
    static float aspect(int w, int h) { return w <= 0 || h <= 0 ? .75f : (float) h / w; }

    /** Final size in px of an image shown at ratio (h/w) within maxW x maxH, never upscaled past natural width. */
    static int[] fit(int naturalW, int availW, int maxH, float ratio) {
        int w = Math.min(availW, Math.max(1, naturalW));
        int h = Math.round(w * ratio);
        if (h > maxH) { h = maxH; w = Math.max(1, Math.round(h / ratio)); }
        return new int[]{w, h};
    }

    // ── capture-lane messages ("capture_id: …\nkind: audio\n…\ntranscript (en):\n<words>") ──

    /** "Voice capture", "Text capture", "Photo capture" or "Capture" for a capture-lane message, else null. */
    static String captureLabel(String content) {
        if (content == null || !content.startsWith("capture_id:")) return null;
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("\\bkind:\\s*(\\w+)").matcher(content);
        String kind = m.find() ? m.group(1).toLowerCase(java.util.Locale.ROOT) : "";
        switch (kind) {
            case "audio": case "voice": return "Voice capture";
            case "text": case "note": return "Text capture";
            case "image": case "photo": return "Photo capture";
            case "file": return "File capture";
            default: return "Capture";
        }
    }

    /** The captured words (and any added instructions) without the routing header and attachment lines, else null. */
    static String captureBody(String content) {
        if (captureLabel(content) == null) return null;
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("(?m)^(?:text|transcript(?: \\([^)]*\\))?):\\s*$").matcher(content);
        if (!m.find()) return "";
        StringBuilder b = new StringBuilder();
        for (String line : content.substring(m.end()).split("\n", -1)) if (!line.startsWith("attachment:")) b.append(line).append('\n');
        return b.toString().trim();
    }

    // ── mid-run steers: Hermes stores each as its own user row wrapped in its out-of-band marker ──

    static final String STEER_OPEN = "[OUT-OF-BAND USER MESSAGE", STEER_CLOSE = "[/OUT-OF-BAND USER MESSAGE]";

    /** The steered words without Hermes' marker lines, else null. */
    static String steerBody(String content) {
        String t = content == null ? "" : content.trim();
        if (!t.startsWith(STEER_OPEN)) return null;
        int start = t.indexOf('\n'), end = t.lastIndexOf(STEER_CLOSE);
        if (start < 0) return "";
        return t.substring(start + 1, end > start ? end : t.length()).trim();
    }

    static final String ORIGIN_OPEN = "Gateway message origin (", ORIGIN_END = "fields are insufficient.";

    /** A message sent while Hermes was busy is stored behind the gateway's routing note; show only what the user wrote. */
    static String stripOrigin(String content) {
        if (content == null || !content.trim().startsWith(ORIGIN_OPEN)) return content;
        int end = content.indexOf(ORIGIN_END);
        return end < 0 ? content : content.substring(end + ORIGIN_END.length()).trim();
    }

    /** The server keeps no image parts: a plain chat stores "[screenshot]", the gateway a vision description. */
    static boolean isImageTurn(String content) {
        return content != null && (content.startsWith("[The user sent an image~") || content.contains("\n[screenshot]") || content.trim().equals("[screenshot]"));
    }

    /** What the user typed, without the server's image stand-ins. */
    static String stripImageMarkers(String content) {
        if (content == null) return "";
        return content.replaceAll("(?s)\\[The user sent an image~.*?~\\]\\s*", "").replaceAll("(?m)^\\[screenshot\\]\\s*$", "").trim();
    }

    /** How a steer reads in the chat: labelled, so it is not mistaken for a new turn. */
    static String steerShown(String text) { return steerShown(text, false); }

    static String steerShown(String text, boolean queued) {
        return "*Steer*\n" + (queued ? "Queued, will be sent after this answer\n\n" : "") + text;
    }

    static boolean queuedSteer(String status) { return "queued".equals(status) || "pending_steer".equals(status); }

    /** The capture pipeline answers with {"summary","reply","actions":[{kind,text,priority}]}: show it as words, not JSON. Else null. */
    static String captureReply(String content) {
        String t = content == null ? "" : content.trim();
        if (!t.startsWith("{") || !t.contains("\"reply\"")) return null;
        try {
            org.json.JSONObject o = new org.json.JSONObject(t);
            String reply = o.optString("reply", "").trim();
            if (reply.isEmpty()) return null;
            StringBuilder b = new StringBuilder(reply);
            org.json.JSONArray actions = o.optJSONArray("actions");
            if (actions != null && actions.length() > 0) {
                b.append("\n");
                for (int i = 0; i < actions.length(); i++) {
                    org.json.JSONObject a = actions.optJSONObject(i);
                    String text = a == null ? actions.optString(i, "") : a.optString("text", a.optString("title", ""));
                    if (text.isEmpty()) continue;
                    String kind = a == null ? "" : a.optString("kind", ""), prio = a == null ? "" : a.optString("priority", "");
                    b.append("\n- ").append(kind.isEmpty() ? "" : "**" + Character.toUpperCase(kind.charAt(0)) + kind.substring(1) + ":** ")
                            .append(text).append(prio.isEmpty() ? "" : " (" + prio + ")");
                }
            }
            return b.toString();
        } catch (org.json.JSONException e) { return null; }
    }
}
