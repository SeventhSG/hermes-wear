package cloud.stormlabs.hermescompanion;

import java.util.List;

/** Pure notification-to-message extraction (JVM testable); a thin adapter feeds it plain values. */
final class ReplyExtract {
    private ReplyExtract() { }

    static final class Msg {
        final String text, sender;
        Msg(String text, String sender) { this.text = text; this.sender = sender; }
    }

    static final class Result {
        final String sender, message;
        Result(String sender, String message) { this.sender = sender; this.message = message; }
    }

    /** Returns the newest incoming message, or null when this notification is not a draftable message. */
    static Result extract(boolean groupSummary, String title, String text, String conversationTitle,
                          boolean group, List<Msg> messages) {
        if (groupSummary) return null;
        String who = blank(title) ? conversationTitle : title;
        if (messages != null) {
            for (int i = messages.size() - 1; i >= 0; i--) {
                Msg m = messages.get(i);
                if (m == null || blank(m.text)) continue;
                String name = blank(m.sender) ? who : m.sender;
                if (group && !blank(conversationTitle) && !blank(m.sender)) name = m.sender + " (" + conversationTitle + ")";
                if (blank(name)) return null;
                return new Result(name.trim(), m.text.trim());
            }
        }
        if (blank(text)) return null;
        // "Instagram" as a title is the app name, not a person; keep it only when nothing better exists.
        if (blank(who)) return null;
        return new Result(who.trim(), text.trim());
    }

    private static boolean blank(String s) { return s == null || s.trim().isEmpty(); }
}
