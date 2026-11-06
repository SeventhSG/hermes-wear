package cloud.stormlabs.hermescompanion;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Finding a conversation by what it is called: every word of the query must appear, in any order. Pure, so it is tested. */
final class SessionSearch {
    private SessionSearch() { }

    static final int NO_MATCH = -1, IN_PREVIEW = 1, IN_TITLE = 2, TITLE_WORDS_START = 3, TITLE_STARTS = 4;

    static List<String> tokens(String query) {
        List<String> out = new ArrayList<>();
        if (query != null) for (String t : query.toLowerCase(Locale.ROOT).trim().split("\\s+")) if (!t.isEmpty()) out.add(t);
        return out;
    }

    /** How well a conversation matches; {@link #NO_MATCH} when any word of the query is missing from both title and preview. */
    static int score(String title, String preview, String query) {
        List<String> tokens = tokens(query);
        if (tokens.isEmpty()) return IN_TITLE;
        String t = title == null ? "" : title.toLowerCase(Locale.ROOT), p = preview == null ? "" : preview.toLowerCase(Locale.ROOT);
        boolean allInTitle = true;
        for (String tok : tokens) {
            boolean inTitle = t.contains(tok);
            if (!inTitle && !p.contains(tok)) return NO_MATCH;
            allInTitle &= inTitle;
        }
        if (!allInTitle) return IN_PREVIEW;
        String joined = String.join(" ", tokens);
        if (t.startsWith(joined)) return TITLE_STARTS;
        if (t.startsWith(tokens.get(0)) || t.contains(" " + tokens.get(0))) return TITLE_WORDS_START;
        return IN_TITLE;
    }
}
