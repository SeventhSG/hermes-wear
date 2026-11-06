package cloud.stormlabs.hermescompanion;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

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

    @Test public void suggestionWithEvent() {
        ReplyExtract.Suggestion s = ReplyExtract.suggestion("```json\n{\"reply\": \"Sounds good, see you there!\", \"next\": \"Book a table\", "
                + "\"event\": {\"title\": \"Dinner with Maria\", \"start\": \"2026-11-08T19:30\", \"end\": \"\", \"location\": \"Alfama\"}}\n```");
        assertEquals("Sounds good, see you there!", s.reply);
        assertEquals("Book a table", s.next);
        assertTrue(s.hasEvent());
        assertFalse(s.allDay());
        assertEquals("Alfama", s.location);
        assertEquals("", s.end);
        assertEquals(java.time.LocalDateTime.of(2026, 11, 8, 19, 30).atZone(java.time.ZoneOffset.UTC).toInstant().toEpochMilli(),
                ReplyExtract.millis(s.start, java.time.ZoneOffset.UTC));
    }

    @Test public void suggestionWithoutJsonOrWithBadDateIsJustTheReply() {
        ReplyExtract.Suggestion plain = ReplyExtract.suggestion("Sure, on my way");
        assertEquals("Sure, on my way", plain.reply);
        assertFalse(plain.hasEvent());
        ReplyExtract.Suggestion vague = ReplyExtract.suggestion("{\"reply\": \"ok\", \"event\": {\"title\": \"Drinks\", \"start\": \"next Friday\"}}");
        assertFalse(vague.hasEvent());
        assertTrue(ReplyExtract.suggestion("{\"reply\": \"ok\", \"event\": {\"title\": \"Exam\", \"start\": \"2026-12-01\"}}").allDay());
        assertEquals(0, ReplyExtract.millis("garbage", java.time.ZoneOffset.UTC));
    }

    @Test public void hermesOwnChannelsAreSkipped() {
        assertTrue(ReplyExtract.fromHermes("Hermesbot", null));
        assertTrue(ReplyExtract.fromHermes("the owner", "Hermes Agent"));
        assertFalse(ReplyExtract.fromHermes("Maria", null));
    }
}
