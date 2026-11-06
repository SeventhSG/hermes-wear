package cloud.stormlabs.hermescompanion;

import java.util.Locale;

/**
 * Splits a reply that is still streaming into speakable pieces, so Hermes can start talking after the
 * first sentence instead of after the whole answer. Pure logic: callers keep how much they already spoke.
 */
final class SpeechStream {
    private SpeechStream() {}

    /** The first piece is spoken as soon as it is a short sentence; later ones wait for a fuller sentence. */
    static final int FIRST_MIN = 12, NEXT_MIN = 40;

    /**
     * @param text the reply so far
     * @param from characters already handed to the speaker
     * @return the exclusive end of the next piece worth speaking, or {@code from} when nothing is ready yet
     */
    static int readyEnd(String text, int from) {
        if (text == null || from >= text.length()) return from;
        int limit = limit(text);
        int min = from == 0 ? FIRST_MIN : NEXT_MIN;
        int best = from;
        for (int i = from; i < limit; i++) {
            char c = text.charAt(i);
            boolean boundary;
            if (c == '\n') boundary = true;
            else if (c == '.' || c == '!' || c == '?' || c == '…') {
                // Needs the next char to know it is a sentence end ("3.5", "e.g.", "https://x.y/z").
                if (i + 1 >= limit) continue;
                char n = text.charAt(i + 1);
                boundary = Character.isWhitespace(n) && !abbreviation(text, from, i);
            } else boundary = false;
            if (!boundary) continue;
            int end = i + 1;
            if (text.substring(from, end).trim().length() >= min) { best = end; break; }
        }
        return best;
    }

    /** Never speak into an unfinished code fence or device-action tag: stop where it opens. */
    static int limit(String text) {
        int limit = text.length();
        int fences = 0, last = -1;
        for (int i = text.indexOf("```"); i >= 0; i = text.indexOf("```", i + 3)) { fences++; last = i; }
        if (fences % 2 == 1) limit = Math.min(limit, last);
        String lower = text.toLowerCase(Locale.ROOT);
        int open = lower.lastIndexOf("<device>");
        if (open >= 0 && lower.indexOf("</device>", open) < 0) limit = Math.min(limit, open);
        return limit;
    }

    private static boolean abbreviation(String text, int from, int dot) {
        int s = dot;
        while (s > from && !Character.isWhitespace(text.charAt(s - 1))) s--;
        String word = text.substring(s, dot).toLowerCase(Locale.ROOT);
        switch (word) {
            case "e.g": case "i.e": case "etc": case "vs": case "dr": case "mr": case "mrs": case "ms": case "st": case "no": return true;
            default: break;
        }
        // "1." at the start of a numbered list item
        boolean digits = !word.isEmpty();
        for (int i = 0; i < word.length(); i++) if (!Character.isDigit(word.charAt(i))) { digits = false; break; }
        return digits && (s == from || text.charAt(s - 1) == '\n' || text.substring(from, s).trim().isEmpty());
    }
}
