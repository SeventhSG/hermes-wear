package cloud.stormlabs.hermescompanion;

import org.json.JSONException;
import org.json.JSONObject;

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

    /** Hermes' suggestion for a message: the reply, an optional next step, and a calendar event when the message sets one up. */
    static final class Suggestion {
        final String reply, next, title, start, end, location;
        Suggestion(String reply, String next, String title, String start, String end, String location) {
            this.reply = reply; this.next = next; this.title = title; this.start = start; this.end = end; this.location = location;
        }
        boolean hasEvent() { return !title.isEmpty() && !start.isEmpty(); }
        boolean allDay() { return start.length() == 10; }
    }

    private static final String LOCAL_TIME = "\\d{4}-\\d{2}-\\d{2}(T\\d{2}:\\d{2})?";

    /** Parses the JSON suggestion; any other answer is taken as the reply text alone. */
    static Suggestion suggestion(String raw) {
        String t = raw == null ? "" : raw.trim();
        int a = t.indexOf('{'), b = t.lastIndexOf('}');
        if (a >= 0 && b > a) try {
            JSONObject o = new JSONObject(t.substring(a, b + 1));
            JSONObject e = o.optJSONObject("event");
            String start = e == null ? "" : e.optString("start").trim(), end = e == null ? "" : e.optString("end").trim();
            boolean dated = start.matches(LOCAL_TIME) && !e.optString("title").trim().isEmpty();
            return new Suggestion(o.optString("reply").trim(), o.optString("next").trim(), dated ? e.optString("title").trim() : "",
                    dated ? start : "", dated && end.matches(LOCAL_TIME) ? end : "", dated ? e.optString("location").trim() : "");
        } catch (JSONException ignored) { }
        return new Suggestion(t, "", "", "", "", "");
    }

    /** "2026-11-08T19:30" or "2026-11-08" in the given zone, as epoch millis; 0 when unparseable. */
    static long millis(String local, java.time.ZoneId zone) {
        try {
            return local.length() == 10 ? java.time.LocalDate.parse(local).atStartOfDay(zone).toInstant().toEpochMilli()
                    : java.time.LocalDateTime.parse(local).atZone(zone).toInstant().toEpochMilli();
        } catch (Exception e) { return 0; }
    }

    /** Hermes' own channels (its Telegram bot "Hermesbot", other bridges) talk to the user; they are not messages to watch. */
    static boolean fromHermes(String sender, String conversationTitle) {
        String who = ((sender == null ? "" : sender) + " " + (conversationTitle == null ? "" : conversationTitle)).toLowerCase(java.util.Locale.ROOT);
        return who.contains("hermes");
    }

    private static boolean blank(String s) { return s == null || s.trim().isEmpty(); }
}
