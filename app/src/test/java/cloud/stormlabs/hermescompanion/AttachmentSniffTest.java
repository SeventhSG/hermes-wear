package cloud.stormlabs.hermescompanion;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * Regression: the "openxmlformats" Office MIME types contain the substring "xml", so the old
 * extension/MIME sniff inlined a whole binary .pptx deck as UTF-8 text. The chat then tried to lay
 * out ~1.7M characters of control bytes and took the app down. Containers must be refused as text,
 * and the renderer must never lay out an unbounded message.
 */
public class AttachmentSniffTest {
    private static final String PPTX = "application/vnd.openxmlformats-officedocument.presentationml.presentation";
    private static final String DOCX = "application/vnd.openxmlformats-officedocument.wordprocessingml.document";
    private static final String XLSX = "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";
    /** PK\x03\x04 — the ZIP local-file-header magic every Office/ODF/epub/apk container starts with. */
    private static final byte[] ZIP = {'P', 'K', 3, 4, 0, 0, 0, 0};
    private static final byte[] TEXT = "hello, world\nsecond line\n".getBytes(java.nio.charset.StandardCharsets.UTF_8);

    @Test public void officeContainersAreNeverText() {
        assertFalse(ChatActivity.looksLikeText("beetle-architecture.pptx", PPTX, ZIP));
        assertFalse(ChatActivity.looksLikeText("report.docx", DOCX, ZIP));
        assertFalse(ChatActivity.looksLikeText("book.xlsx", XLSX, ZIP));
        assertFalse(ChatActivity.looksLikeText("deck.pptx", null, ZIP));   // extension alone is enough
        assertFalse(ChatActivity.looksLikeText("mystery", PPTX, ZIP));     // PK magic alone is enough
    }

    @Test public void realTextStillInlines() {
        assertTrue(ChatActivity.looksLikeText("notes.txt", "text/plain", TEXT));
        assertTrue(ChatActivity.looksLikeText("config.yaml", null, TEXT));
        assertTrue(ChatActivity.looksLikeText("feed.xml", "application/xml", TEXT));
        assertTrue(ChatActivity.looksLikeText("data.csv", "text/csv", TEXT));
    }

    @Test public void rendererCapsRunawayMessages() {
        StringBuilder big = new StringBuilder();
        for (int i = 0; i < MarkdownView.MAX_LAYOUT_CHARS + 500; i++) big.append('*');
        String out = MarkdownView.capped(big.toString());
        assertTrue("capped output must be shorter", out.length() < big.length());
        assertTrue(out.startsWith("***"));
        assertTrue(out.contains("more characters hidden"));
        assertEquals("short", MarkdownView.capped("short"));
        assertNull(MarkdownView.capped(null));
    }
}
