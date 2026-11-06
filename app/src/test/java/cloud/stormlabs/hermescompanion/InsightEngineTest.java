package cloud.stormlabs.hermescompanion;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.Test;

public class InsightEngineTest {
    private static final String LONG = "an eigenvector of a matrix only gets scaled when you apply the matrix and the scaling factor is called the eigenvalue";

    @Test public void parsesJsonLinesAndIgnoresJunk() {
        String out = "```json\n{\"type\":\"concept\",\"text\":\"Eigenvector: only scaled by A\"}\nsome chatter\n{\"type\":\"Exam Point\",\"text\":\"Repeated twice\"}\n{\"type\":\"weird\",\"text\":\"Odd type\"}\n{\"type\":\"concept\",\"text\":\"\"}\n```";
        List<InsightEngine.Card> cards = InsightEngine.parse(out, 1);
        assertEquals(3, cards.size());
        assertEquals("concept", cards.get(0).type);
        assertEquals("exam_point", cards.get(1).type);
        assertEquals("concept", cards.get(2).type); // unknown type falls back
    }

    @Test public void capsAtThreeCardsAndTrimsLongText() {
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < 5; i++) out.append("{\"type\":\"concept\",\"text\":\"").append(i == 0 ? new String(new char[400]).replace('\0', 'x') : "card " + i).append("\"}\n");
        List<InsightEngine.Card> cards = InsightEngine.parse(out.toString(), 1);
        assertEquals(3, cards.size());
        assertTrue(cards.get(0).text.length() <= InsightEngine.MAX_CARD_CHARS);
        assertTrue(InsightEngine.parse(null, 1).isEmpty());
        assertTrue(InsightEngine.parse("", 1).isEmpty());
    }

    @Test public void skipsTickWithTooFewWords() throws Exception {
        InsightEngine e = new InsightEngine();
        e.addSentence("short bit only");
        AtomicInteger calls = new AtomicInteger();
        assertNull(e.tick((s, u, slow) -> { calls.incrementAndGet(); return ""; }, 1));
        assertEquals(0, calls.get());
    }

    @Test public void tickSendsOnlyNewSpeechAndConsumesIt() throws Exception {
        InsightEngine e = new InsightEngine();
        e.addSentence(LONG);
        final String[] seen = new String[1];
        List<InsightEngine.Card> made = e.tick((s, u, slow) -> { seen[0] = u; return "{\"type\":\"definition\",\"text\":\"Eigenvalue: the scaling factor\"}"; }, 5);
        assertEquals(1, made.size());
        assertTrue(seen[0].contains("NEW_TRANSCRIPT: " + LONG));
        assertTrue(seen[0].contains("SUMMARY: (nothing yet)"));
        // Nothing new said since: the next tick is skipped without a model call.
        assertNull(e.tick((s, u, slow) -> { throw new AssertionError("must not call"); }, 6));
    }

    @Test public void dropsRepeatedCards() throws Exception {
        InsightEngine e = new InsightEngine();
        e.addSentence(LONG);
        e.tick((s, u, slow) -> "{\"type\":\"concept\",\"text\":\"Eigenvalue is the scaling factor of an eigenvector\"}", 1);
        e.addSentence(LONG + " again");
        List<InsightEngine.Card> second = e.tick((s, u, slow) -> "{\"type\":\"concept\",\"text\":\"The eigenvalue is the scaling factor of the eigenvector\"}", 2);
        assertTrue(second.isEmpty());
        assertEquals(1, e.cards().size());
    }

    @Test public void recentCardsGoIntoTheNextPrompt() throws Exception {
        InsightEngine e = new InsightEngine();
        e.addSentence(LONG);
        e.tick((s, u, slow) -> "{\"type\":\"exam_point\",\"text\":\"Know the definition\"}", 1);
        e.addSentence(LONG + " and one more");
        final String[] seen = new String[1];
        e.tick((s, u, slow) -> { seen[0] = u; return ""; }, 2);
        assertTrue(seen[0].contains("- exam_point: Know the definition"));
    }

    @Test public void neverQueuesBehindASlowCall() throws Exception {
        final InsightEngine e = new InsightEngine();
        e.addSentence(LONG);
        final CountDownLatch inside = new CountDownLatch(1), release = new CountDownLatch(1);
        Thread t = new Thread(() -> {
            try { e.tick((s, u, slow) -> { inside.countDown(); release.await(5, TimeUnit.SECONDS); return ""; }, 1); } catch (Exception ignored) { }
        });
        t.start();
        assertTrue(inside.await(5, TimeUnit.SECONDS));
        e.addSentence(LONG + " more words to pass the threshold again");
        assertNull(e.tick((s, u, slow) -> { throw new AssertionError("second call must be skipped"); }, 2));
        release.countDown(); t.join(5000);
    }

    @Test public void summaryRefreshFeedsBackIntoCards() throws Exception {
        InsightEngine e = new InsightEngine();
        e.addSentence(LONG);
        assertTrue(e.summarize((s, u, slow) -> { assertTrue(slow); return "- Eigenvectors\n  - scaled by A"; }));
        assertEquals("- Eigenvectors\n  - scaled by A", e.summary());
        assertFalse(e.summarize((s, u, slow) -> "never"));   // nothing new since
        e.addSentence(LONG + " plus trailing detail");
        final String[] seen = new String[1];
        e.tick((s, u, slow) -> { seen[0] = u; return ""; }, 1);
        assertTrue(seen[0].contains("SUMMARY: - Eigenvectors"));
    }

    @Test public void failedCallKeepsTheSpeechForNextTime() throws Exception {
        InsightEngine e = new InsightEngine();
        e.addSentence(LONG);
        try { e.tick((s, u, slow) -> { throw new java.io.IOException("down"); }, 1); } catch (java.io.IOException expected) { }
        final String[] seen = new String[1];
        e.tick((s, u, slow) -> { seen[0] = u; return ""; }, 2);
        assertNotNull(seen[0]);
        assertTrue(seen[0].contains(LONG));
    }

    @Test public void markdownGroupsTheSession() throws Exception {
        InsightEngine e = new InsightEngine();
        e.addSentence(LONG);
        e.tick((s, u, slow) -> "{\"type\":\"exam_point\",\"text\":\"Know eigenvalues\"}\n{\"type\":\"definition\",\"text\":\"Eigenvalue: scale\"}", 1);
        e.summarize((s, u, slow) -> "- Outline");
        String md = e.markdown("Linear algebra", "today", 600);
        assertTrue(md.startsWith("# Linear algebra"));
        assertTrue(md.contains("## Likely exam points\n\n- Know eigenvalues"));
        assertTrue(md.contains("### Definitions"));
        assertTrue(md.contains("## Summary\n\n- Outline"));
        assertTrue(md.contains("## Transcript"));
        assertTrue(md.contains("10 min"));
    }

    @Test public void restoreKeepsProgress() throws Exception {
        InsightEngine e = new InsightEngine();
        e.addSentence(LONG);
        e.tick((s, u, slow) -> "{\"type\":\"concept\",\"text\":\"x y z\"}", 1);
        InsightEngine f = new InsightEngine();
        f.restore(e.sentences(), e.cards(), e.summary(), e.consumedTick(), e.consumedSummary());
        assertNull(f.tick((s, u, slow) -> { throw new AssertionError("already handled"); }, 2));
        assertEquals(1, f.cards().size());
    }

    @Test public void askAndRecapPromptsCarryTheRightContext() {
        InsightEngine e = new InsightEngine();
        for (int i = 0; i < 40; i++) e.addSentence("sentence number " + i + " has exactly seven words");
        String recap = e.recapUser();
        assertTrue(recap.contains("sentence number 39"));
        assertFalse(recap.contains("sentence number 0 "));          // only the last ~120 words
        String ask = e.askUser("  what is an eigenvalue? ");
        assertTrue(ask.endsWith("QUESTION: what is an eigenvalue?"));
        assertTrue(ask.contains("sentence number 0 "));             // short lecture: all of it fits
    }

    @Test public void flashcardsParseAndExportAsTabSeparated() {
        List<String[]> cards = InsightEngine.parseFlashcards("```\n{\"q\":\"What is an eigenvalue?\",\"a\":\"The\\tscaling\\nfactor\"}\nnoise\n{\"q\":\"\",\"a\":\"x\"}\n```");
        assertEquals(1, cards.size());
        assertEquals("What is an eigenvalue?\tThe scaling factor\n", InsightEngine.anki(cards));
        assertTrue(InsightEngine.parseFlashcards(null).isEmpty());
    }

    @Test public void addedRecapsSurviveIntoTheMarkdown() {
        InsightEngine e = new InsightEngine();
        e.addSentence(LONG);
        e.addCard(new InsightEngine.Card("recap", "He said the factor is the eigenvalue", 1));
        assertTrue(e.markdown("T", "now", 60).contains("### Recaps\n\n- He said the factor is the eigenvalue"));
        assertTrue(e.flashUser().contains("- recap: He said"));
    }
}
