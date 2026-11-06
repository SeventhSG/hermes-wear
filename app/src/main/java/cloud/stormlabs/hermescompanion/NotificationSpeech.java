package cloud.stormlabs.hermescompanion;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * What to say for a notification, and whether to say it at all. Pure logic (no Android) so the rules are testable;
 * {@link NotificationReader} supplies the device state and plays the result into the earbuds.
 */
final class NotificationSpeech {
    private NotificationSpeech() { }

    static final int MAX_CHARS = 220;
    /** Notification categories that are never read: playback, progress bars, calls and alarms have their own sound. */
    static final Set<String> SKIP_CATEGORIES = new HashSet<>(java.util.Arrays.asList(
            "transport", "progress", "call", "alarm", "service", "sys", "status", "err", "navigation", "stopwatch", "location_sharing"));

    private static final Pattern URL = Pattern.compile("(?i)\\b(?:https?://|www\\.)\\S+");
    private static final Pattern SPACE = Pattern.compile("[\\s\\u00a0\\u200b\\u200e\\u200f]+");
    // Emoji and pictographs read out as a long list of names; drop them.
    private static final Pattern EMOJI = Pattern.compile("[\\x{1F000}-\\x{1FAFF}\\x{2600}-\\x{27BF}\\x{FE0F}\\x{200D}\\x{2B50}\\x{2B06}\\x{2194}-\\x{21AA}]");

    /** The state of the world the decision depends on. */
    static final class Situation {
        boolean enabled, agentPaused, screenOn, screenOffOnly, headphones, headphonesOnly, inCall, quietNow;
        boolean ongoing, groupSummary, silentChannel, ownApp;
        String category = "";
        boolean appAllowed;
    }

    /** Why a notification is not read, or null when it should be. */
    static String skipReason(Situation s) {
        if (!s.enabled) return "off";
        if (s.agentPaused) return "paused";
        if (s.ownApp) return "own app";
        if (!s.appAllowed) return "app not chosen";
        if (s.ongoing) return "ongoing";
        if (s.groupSummary) return "group summary";
        if (s.silentChannel) return "silent channel";
        if (s.category != null && SKIP_CATEGORIES.contains(s.category.toLowerCase(Locale.ROOT))) return "category " + s.category;
        if (s.screenOffOnly && s.screenOn) return "screen on";
        if (s.headphonesOnly && !s.headphones) return "no headphones";
        if (s.inCall) return "in a call";
        if (s.quietNow) return "quiet hours";
        return null;
    }

    /** Tidy text for speech: links become "link", emoji and odd spacing go, long text is cut at a word. */
    static String clean(String text) {
        if (text == null) return "";
        String t = URL.matcher(text).replaceAll(" link ");
        t = EMOJI.matcher(t).replaceAll(" ");
        t = SPACE.matcher(t).replaceAll(" ").trim();
        if (t.length() <= MAX_CHARS) return t;
        int cut = t.lastIndexOf(' ', MAX_CHARS);
        if (cut < MAX_CHARS / 2) cut = MAX_CHARS;
        return t.substring(0, cut).trim() + " … and more";
    }

    /**
     * "WhatsApp. Jane: running late". With content hidden only who it is from: "WhatsApp, new message from Jane".
     * The app name is dropped when the sender already says it (a title that is the app's own name).
     */
    static String phrase(String app, String sender, String message, boolean readContent) {
        String a = clean(app), who = clean(sender), body = clean(message);
        boolean titleIsApp = !who.isEmpty() && who.equalsIgnoreCase(a);
        if (!readContent || body.isEmpty()) {
            if (who.isEmpty() || titleIsApp) return a.isEmpty() ? "New notification" : a + ", new notification";
            return (a.isEmpty() ? "" : a + ", ") + "new message from " + who;
        }
        if (who.isEmpty() || titleIsApp) return (a.isEmpty() ? "" : a + ". ") + body;
        return (a.isEmpty() ? "" : a + ". ") + who + ": " + body;
    }

    /** Remembers what was just read so a notification that is re-posted with the same text is not read twice. */
    static final class Recent {
        private final Map<String, Long> seen = new HashMap<>();
        private final long windowMs;
        Recent(long windowMs) { this.windowMs = windowMs; }

        /** True the first time this key+text is seen inside the window. */
        synchronized boolean fresh(String key, String text, long now) {
            seen.values().removeIf(t -> now - t > windowMs);
            String id = key + "\u0000" + text;
            if (seen.containsKey(id)) return false;
            seen.put(id, now);
            return true;
        }
    }

    /** Several notifications at once: read the first few, then summarise the rest instead of droning on. */
    static String overflow(int skipped) {
        return skipped <= 0 ? "" : skipped == 1 ? "And one more notification." : "And " + skipped + " more notifications.";
    }
}
