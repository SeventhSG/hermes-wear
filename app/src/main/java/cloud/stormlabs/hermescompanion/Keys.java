package cloud.stormlabs.hermescompanion;

/** Normalises a value that was pasted into a settings field. */
final class Keys {
    private Keys() {}

    /**
     * Keeps printable ASCII and drops everything else.
     *
     * A key or token copied out of a chat client can carry a zero-width space, a non-breaking space,
     * a byte-order mark, a direction mark or a soft hyphen. {@link String#trim()} strips only
     * characters at or below U+0020, so those survive, and the gateway compares the token as bytes —
     * one invisible character is a 401 that looks like a wrong key. Every endpoint, key and token this
     * app uses is printable ASCII, so anything else is paste noise.
     */
    static String clean(String raw) {
        if (raw == null) return "";
        StringBuilder out = new StringBuilder(raw.length());
        for (int i = 0; i < raw.length(); i++) {
            char c = raw.charAt(i);
            if (c >= 33 && c <= 126) out.append(c);
        }
        return out.toString();
    }

    /** True when {@link #clean} would change the input, i.e. the paste carried invisible junk. */
    static boolean hadNoise(String raw) {
        return raw != null && !raw.trim().equals(clean(raw));
    }
}
