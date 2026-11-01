package cloud.stormlabs.hermescompanion;

import org.json.JSONObject;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * The thinking half of Live notes. Finished sentences go in; every few seconds {@link #tick} turns the
 * new ones into 0-3 insight cards, and {@link #summarize} keeps a short running outline that is fed back
 * into the next ticks. No Android, no threads of its own: the caller supplies the model and the timer.
 */
final class InsightEngine {
    /** One blocking model call. */
    interface Llm { String ask(String system, String user, boolean slow) throws Exception; }

    static final class Card {
        final String type, text; final long at;
        Card(String type, String text, long at) { this.type = type; this.text = text; this.at = at; }
    }

    static final String[] TYPES = {"concept", "definition", "exam_point", "example", "question", "recap"};
    static final int MIN_WORDS = 15, RECENT = 5, MAX_CARD_CHARS = 200;

    static final String FAST_SYSTEM = "You are a live study assistant listening to a lecture in real time. "
            + "Every few seconds you get SUMMARY (what the lecture covered so far), RECENT_CARDS (insights you already gave: never repeat them) "
            + "and NEW_TRANSCRIPT (the last few seconds of speech, may be messy speech-to-text).\n"
            + "Reply with 0-3 insight cards, one JSON object per line, and nothing else:\n"
            + "{\"type\":\"concept|definition|exam_point|example|question\",\"text\":\"...\"}\n"
            + "Rules: only react to NEW_TRANSCRIPT; if nothing meaningful is new reply with an empty message. "
            + "Each card at most 25 words, plain complete sentences. exam_point only when the lecturer stresses, repeats or says it matters. "
            + "question = something a student might want to ask. Never invent facts the transcript or standard knowledge does not support. "
            + "Do not use tools. Do not add commentary.";

    static final String SLOW_SYSTEM = "You keep a running outline of a live lecture. Update the outline with the new transcript. "
            + "Nested bullet outline, at most 200 words, newest topic last, keep earlier points unless the lecturer corrected them. "
            + "Reply with the outline only. Do not use tools.";

    private final List<String> sentences = new ArrayList<>();
    private final List<Card> cards = new ArrayList<>();
    private int consumedTick, consumedSummary;
    private String summary = "";
    private String context = "";
    private final AtomicBoolean busy = new AtomicBoolean(), summarising = new AtomicBoolean();

    /** Course name, level and the like, folded into the prompts. */
    synchronized void context(String text) { context = text == null ? "" : text.trim(); }
    synchronized void addSentence(String s) { if (s != null && !s.trim().isEmpty()) sentences.add(s.trim()); }
    synchronized List<String> sentences() { return new ArrayList<>(sentences); }
    synchronized List<Card> cards() { return new ArrayList<>(cards); }
    synchronized String summary() { return summary; }

    /** Restores a saved session. */
    synchronized void restore(List<String> sents, List<Card> saved, String sum, int tickDone, int sumDone) {
        sentences.clear(); sentences.addAll(sents); cards.clear(); cards.addAll(saved); summary = sum == null ? "" : sum;
        consumedTick = Math.min(tickDone, sentences.size()); consumedSummary = Math.min(sumDone, sentences.size());
    }
    synchronized int consumedTick() { return consumedTick; }
    synchronized int consumedSummary() { return consumedSummary; }

    static int words(String s) { String t = s.trim(); return t.isEmpty() ? 0 : t.split("\\s+").length; }

    private String since(int from) {
        StringBuilder b = new StringBuilder();
        for (int i = from; i < sentences.size(); i++) b.append(sentences.get(i)).append(' ');
        return b.toString().trim();
    }

    /** New cards from the speech heard since the last tick, an empty list when the model had nothing, or null when the tick was skipped. */
    List<Card> tick(Llm llm, long now) throws Exception {
        final String fresh; final String user; final int upto;
        synchronized (this) {
            fresh = since(consumedTick);
            if (words(fresh) < MIN_WORDS) return null;
            upto = sentences.size();
            StringBuilder u = new StringBuilder();
            if (!context.isEmpty()) u.append("CONTEXT: ").append(context).append("\n");
            u.append("SUMMARY: ").append(summary.isEmpty() ? "(nothing yet)" : summary).append("\nRECENT_CARDS:\n");
            int from = Math.max(0, cards.size() - RECENT);
            if (cards.isEmpty()) u.append("(none)\n");
            for (int i = from; i < cards.size(); i++) u.append("- ").append(cards.get(i).type).append(": ").append(cards.get(i).text).append('\n');
            u.append("NEW_TRANSCRIPT: ").append(fresh);
            user = u.toString();
        }
        if (!busy.compareAndSet(false, true)) return null; // never queue a second call behind a slow one
        try {
            String out = llm.ask(FAST_SYSTEM, user, false);
            List<Card> made = parse(out, now);
            synchronized (this) {
                consumedTick = Math.max(consumedTick, upto);
                List<Card> fresher = new ArrayList<>();
                for (Card c : made) if (!duplicate(c)) { cards.add(c); fresher.add(c); }
                return fresher;
            }
        } finally { busy.set(false); }
    }

    /** Refreshes the outline from the transcript heard since the last refresh; false when there was nothing new or one is running. */
    boolean summarize(Llm llm) throws Exception {
        final String user; final int upto;
        synchronized (this) {
            String fresh = since(consumedSummary);
            if (words(fresh) < MIN_WORDS) return false;
            upto = sentences.size();
            user = (context.isEmpty() ? "" : "CONTEXT: " + context + "\n") + "PREVIOUS_SUMMARY: " + (summary.isEmpty() ? "(none)" : summary) + "\nNEW_TRANSCRIPT: " + fresh;
        }
        if (!summarising.compareAndSet(false, true)) return false;
        try {
            String out = llm.ask(SLOW_SYSTEM, user, true);
            if (out == null || out.trim().isEmpty()) return false;
            synchronized (this) { summary = out.trim(); consumedSummary = Math.max(consumedSummary, upto); }
            return true;
        } finally { summarising.set(false); }
    }

    private boolean duplicate(Card c) {
        Set<String> a = tokens(c.text);
        int from = Math.max(0, cards.size() - 12);
        for (int i = from; i < cards.size(); i++) {
            Card o = cards.get(i);
            if (o.text.equalsIgnoreCase(c.text)) return true;
            Set<String> b = tokens(o.text);
            if (a.isEmpty() || b.isEmpty()) continue;
            Set<String> both = new HashSet<>(a); both.retainAll(b);
            Set<String> any = new HashSet<>(a); any.addAll(b);
            if ((double) both.size() / any.size() >= 0.7) return true;
        }
        return false;
    }

    private static Set<String> tokens(String s) {
        Set<String> out = new HashSet<>();
        for (String w : s.toLowerCase(Locale.ROOT).split("[^\\p{L}\\p{N}]+")) if (w.length() > 2) out.add(w);
        return out;
    }

    /** JSON lines in, cards out. Tolerates code fences, prose around the lines, unknown types and junk. */
    static List<Card> parse(String out, long now) {
        List<Card> cards = new ArrayList<>();
        if (out == null) return cards;
        for (String raw : out.split("\n")) {
            String line = raw.trim();
            int a = line.indexOf('{'), b = line.lastIndexOf('}');
            if (a < 0 || b <= a) continue;
            try {
                JSONObject o = new JSONObject(line.substring(a, b + 1));
                String type = o.optString("type", "").trim().toLowerCase(Locale.ROOT).replace(' ', '_').replace('-', '_');
                String text = o.optString("text", "").trim();
                if (text.isEmpty()) continue;
                boolean known = false;
                for (String t : TYPES) if (t.equals(type)) known = true;
                if (!known) type = "concept";
                if (text.length() > MAX_CARD_CHARS) text = text.substring(0, MAX_CARD_CHARS - 1).trim() + "…";
                cards.add(new Card(type, text, now));
                if (cards.size() == 3) break;
            } catch (Exception ignored) { /* not a card */ }
        }
        return cards;
    }


    static final String ASK_SYSTEM = "You answer a student's question about a lecture that is happening right now. Use the lecture so far (SUMMARY and TRANSCRIPT_SO_FAR) first, "
            + "then standard knowledge, and say which one you are using when it matters. Plain language, at most 120 words. Do not use tools.";
    static final String RECAP_SYSTEM = "A student just missed what the lecturer said. Explain it in plain words as if to a classmate: what was said and why it matters, "
            + "at most 60 words. Use SUMMARY only for context. Do not use tools.";
    static final String FLASH_SYSTEM = "Turn these lecture notes into study flashcards. One JSON object per line: {\"q\":\"...\",\"a\":\"...\"}. "
            + "8 to 20 cards covering definitions, exam points and key examples, each question answerable in one or two sentences. Reply with the lines only. Do not use tools.";

    /** The last {@code maxWords} or so of the transcript, whole sentences only. */
    synchronized String tail(int maxWords) {
        int n = 0, from = sentences.size();
        while (from > 0 && n < maxWords) { from--; n += words(sentences.get(from)); }
        return since(from);
    }

    synchronized void addCard(Card c) { cards.add(c); }

    synchronized String askUser(String question) {
        return (context.isEmpty() ? "" : "CONTEXT: " + context + "\n") + "SUMMARY: " + (summary.isEmpty() ? "(none yet)" : summary)
                + "\nTRANSCRIPT_SO_FAR: " + tail(2500) + "\nQUESTION: " + question.trim();
    }

    synchronized String recapUser() {
        return "SUMMARY: " + (summary.isEmpty() ? "(none yet)" : summary) + "\nJUST_SAID: " + tail(120);
    }

    synchronized String flashUser() {
        StringBuilder b = new StringBuilder();
        if (!context.isEmpty()) b.append("CONTEXT: ").append(context).append('\n');
        b.append("SUMMARY: ").append(summary.isEmpty() ? "(none)" : summary).append("\nCARDS:\n");
        for (Card c : cards) b.append("- ").append(c.type).append(": ").append(c.text).append('\n');
        if (cards.isEmpty()) b.append("TRANSCRIPT: ").append(tail(1500));
        return b.toString();
    }

    /** {question, answer} pairs from JSON lines; junk lines are skipped. */
    static List<String[]> parseFlashcards(String out) {
        List<String[]> cards = new ArrayList<>();
        if (out == null) return cards;
        for (String raw : out.split("\n")) {
            int a = raw.indexOf('{'), b = raw.lastIndexOf('}');
            if (a < 0 || b <= a) continue;
            try {
                JSONObject o = new JSONObject(raw.substring(a, b + 1));
                String q = o.optString("q", "").trim(), ans = o.optString("a", "").trim();
                if (!q.isEmpty() && !ans.isEmpty()) cards.add(new String[]{q, ans});
            } catch (Exception ignored) { /* not a card */ }
        }
        return cards;
    }

    /** Tab-separated, one card per line: Anki's import dialog reads it as is. */
    static String anki(List<String[]> cards) {
        StringBuilder b = new StringBuilder();
        for (String[] c : cards) b.append(field(c[0])).append('\t').append(field(c[1])).append('\n');
        return b.toString();
    }

    private static String field(String s) { return s.replace('\t', ' ').replace('\r', ' ').replace('\n', ' ').trim(); }

    static String label(String type) {
        switch (type) {
            case "definition": return "Definition";
            case "exam_point": return "Exam point";
            case "example": return "Example";
            case "question": return "Question";
            case "recap": return "Recap";
            default: return "Concept";
        }
    }

    /** The whole session as one Markdown note. */
    synchronized String markdown(String title, String when, long seconds) {
        StringBuilder m = new StringBuilder("# ").append(title.isEmpty() ? "Live notes" : title).append("\n\n");
        m.append("*").append(when).append(" · ").append(seconds / 60).append(" min*\n\n");
        if (!summary.isEmpty()) m.append("## Summary\n\n").append(summary).append("\n\n");
        List<Card> exam = new ArrayList<>();
        for (Card c : cards) if ("exam_point".equals(c.type)) exam.add(c);
        if (!exam.isEmpty()) { m.append("## Likely exam points\n\n"); for (Card c : exam) m.append("- ").append(c.text).append('\n'); m.append('\n'); }
        if (!cards.isEmpty()) {
            m.append("## Insights\n\n");
            for (String t : TYPES) {
                boolean head = false;
                for (Card c : cards) if (c.type.equals(t)) {
                    if (!head) { m.append("### ").append(label(t)).append("s\n\n"); head = true; }
                    m.append("- ").append(c.text).append('\n');
                }
                if (head) m.append('\n');
            }
        }
        m.append("## Transcript\n\n");
        for (String s : sentences) m.append(s).append(' ');
        return m.toString().trim() + "\n";
    }
}
