package cloud.stormlabs.hermescompanion;

final class AutoTitle {
    private AutoTitle() {}

    static boolean isPlaceholder(String title) {
        return title != null && title.matches("^(New conversation|Mobile)( \\d+)?$");
    }

    static String fromMessage(String message) {
        if (message == null || message.trim().startsWith("/")) return "";
        // No regex: \\R and (?U) passed on the JVM but hung Android's ICU engine, and plain \\s differs between them.
        int nl = message.indexOf('\n');
        StringBuilder b = new StringBuilder();
        for (char ch : (nl >= 0 ? message.substring(0, nl) : message).toCharArray()) {
            if (!Character.isWhitespace(ch) && !Character.isSpaceChar(ch)) b.append(ch);
            else if (b.length() > 0 && b.charAt(b.length() - 1) != ' ') b.append(' ');
        }
        String first = b.toString().trim();
        if (first.length() <= 48) return first;
        int end = first.lastIndexOf(' ', 47);
        if (end < 1) end = 47;
        return first.substring(0, end).trim() + "…";
    }

    /** First line, whitespace collapsed (no regex, see fromMessage). */
    private static String line(String m) {
        if (m == null) return "";
        int nl = m.indexOf('\n');
        StringBuilder b = new StringBuilder();
        for (char ch : (nl >= 0 ? m.substring(0, nl) : m).toCharArray()) {
            if (!Character.isWhitespace(ch) && !Character.isSpaceChar(ch)) b.append(ch);
            else if (b.length() > 0 && b.charAt(b.length() - 1) != ' ') b.append(' ');
        }
        return b.toString().trim();
    }

    /**
     * True when the session still has no real title: empty, a placeholder, or just the server's instant
     * title (the first message cut short), which Hermes replaces with a model title after the first reply.
     * A title the user set never matches the first message, so it is never touched.
     */
    static boolean needsTitle(String title, String firstUserMessage) {
        String t = title == null ? "" : title.trim();
        if (t.isEmpty() || isPlaceholder(t)) return true;
        String first = line(firstUserMessage);
        if (first.isEmpty()) return false;
        if (t.endsWith("…")) t = t.substring(0, t.length() - 1).trim();
        else if (t.endsWith("...")) t = t.substring(0, t.length() - 3).trim();
        return !t.isEmpty() && first.startsWith(t);
    }

    private static final String[] OPENERS = {"hey there", "hi there", "hello there", "hey", "hi", "hello", "yo", "ok so", "okay so", "so",
            "please", "can you please", "could you please", "can you", "could you", "would you", "will you", "i want you to", "i need you to",
            "i would like you to", "i'd like you to", "i want to", "i need to", "i would like to", "i'd like to", "help me", "let's", "lets"};

    /**
     * A short title from the opening message when the server's own title never arrived: greeting and politeness
     * stripped, sentence case, at most seven words. Falls back to the plain cut when little is left.
     */
    static String smart(String message) {
        String base = fromMessage(message);
        if (base.isEmpty()) return "";
        String t = base;
        boolean stripped = true;
        while (stripped) {
            stripped = false;
            String low = t.toLowerCase(java.util.Locale.ROOT);
            for (String o : OPENERS) {
                if (low.equals(o)) return base;
                if (low.startsWith(o) && low.length() > o.length() && !Character.isLetterOrDigit(low.charAt(o.length()))) {
                    t = t.substring(o.length()).replaceFirst("^[\\s,.:;!?-]+", "");
                    stripped = true;
                    break;
                }
            }
        }
        if (t.length() < 3) return base;
        String[] words = t.split(" ");
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < Math.min(words.length, 7); i++) b.append(i == 0 ? "" : " ").append(words[i]);
        String out = b.toString().replaceFirst("[\\s,.:;!?-]+$", "");
        if (out.isEmpty()) return base;
        return Character.toUpperCase(out.charAt(0)) + out.substring(1);
    }

    /** Last-resort title when the server never produced one and the message is a slash command: the reply's first line. */
    static String fallback(String firstUserMessage, String assistantReply) {
        String t = smart(firstUserMessage);
        if (!t.isEmpty()) return t;
        String r = line(assistantReply);
        while (r.startsWith("#") || r.startsWith("*") || r.startsWith(">")) r = r.substring(1).trim();
        return r.isEmpty() ? "" : fromMessage(r);
    }
}
