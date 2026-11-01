package cloud.stormlabs.hermescompanion;

/** Matches a locally executed action to the assistant reply that requested it. */
final class ReplyKeys {
    private ReplyKeys() { }
    static String key(String session, String cleanedReply) {
        return session + "\u0000" + cleanedReply.trim();
    }
}
