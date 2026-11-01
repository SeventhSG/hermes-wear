package cloud.stormlabs.hermescompanion;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import java.util.Arrays;
import java.util.Collections;
import org.junit.Test;

public class ReplyExtractTest {
    private static ReplyExtract.Msg m(String t, String s) { return new ReplyExtract.Msg(t, s); }

    @Test public void messagingStyleUsesNewestMessageAndSender() {
        ReplyExtract.Result r = ReplyExtract.extract(false, "alice", "2 new messages", null, false,
                Arrays.asList(m("hi", "alice"), m("you free?", "alice")));
        assertEquals("alice", r.sender);
        assertEquals("you free?", r.message);
    }

    @Test public void missingSenderFallsBackToTitle() {
        ReplyExtract.Result r = ReplyExtract.extract(false, "bob", null, null, false, Arrays.asList(m("yo", null)));
        assertEquals("bob", r.sender);
    }

    @Test public void groupAddsConversationTitle() {
        ReplyExtract.Result r = ReplyExtract.extract(false, "Crew", "x", "Crew", true, Arrays.asList(m("lunch?", "carl")));
        assertEquals("carl (Crew)", r.sender);
    }

    @Test public void plainTitleAndTextWhenNoMessages() {
        ReplyExtract.Result r = ReplyExtract.extract(false, "dana", "sent a reel", null, false, Collections.<ReplyExtract.Msg>emptyList());
        assertEquals("dana", r.sender);
        assertEquals("sent a reel", r.message);
    }

    @Test public void summaryAndEmptyAreIgnored() {
        assertNull(ReplyExtract.extract(true, "Instagram", "5 messages", null, false, null));
        assertNull(ReplyExtract.extract(false, "alice", "  ", null, false, Arrays.asList(m(" ", "alice"))));
        assertNull(ReplyExtract.extract(false, null, "text", null, false, null));
    }
}
