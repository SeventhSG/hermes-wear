package cloud.stormlabs.hermescompanion;

import java.util.Locale;

/** Pure helpers for the assistant overlay: state to label, mic-level smoothing, human-first errors. */
final class AssistantState {
    enum State { IDLE, LISTENING, TRANSCRIBING, THINKING, WORKING, SPEAKING, APPROVAL, ERROR }

    private AssistantState() {}

    static String label(State s, int step) {
        switch (s) {
            case LISTENING: return "Listening";
            case TRANSCRIBING: return "Transcribing";
            case THINKING: return "Thinking";
            case WORKING: return step > 0 ? "Working · step " + step : "Working";
            case SPEAKING: return "Speaking";
            case APPROVAL: return "Needs your OK";
            case ERROR: return "Needs attention";
            default: return "Hermes";
        }
    }

    /** Quick-start chips show only when nothing else is going on and no reply is on screen. */
    static boolean showSuggestions(State s, boolean hasReply) { return s == State.IDLE && !hasReply; }

    /** One-pole smoothing, fast attack and slow release so the orb jumps with speech and settles gently. */
    static float smooth(float current, float target) {
        float t = Math.max(0f, Math.min(1f, target));
        return current + (t - current) * (t > current ? .45f : .12f);
    }

    /** Full words revealed so far, never more than the total. */
    static int clampWords(int revealed, int total) { return Math.max(0, Math.min(revealed, total)); }

    static String humanError(String raw) {
        if (raw == null || raw.trim().isEmpty()) return "Something went wrong. Try again.";
        String l = raw.toLowerCase(Locale.ROOT);
        if (l.contains("session not found")) return "That conversation is gone. Try again to start a fresh one.";
        if (l.contains("timeout") || l.contains("timed out")) return "Hermes took too long to answer. Try again.";
        if (l.contains("unable to resolve") || l.contains("unknownhost") || l.contains("failed to connect")
                || l.contains("connectexception") || l.contains("network")) return "Can't reach your Hermes server. Check your connection and try again.";
        if (l.contains("401") || l.contains("403") || l.contains("unauthor")) return "Hermes didn't accept the saved login. Re-pair in Settings.";
        String t = raw.trim();
        return "Something went wrong. " + (t.length() > 120 ? t.substring(0, 120) + "…" : t);
    }
}
