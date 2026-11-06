package cloud.stormlabs.hermescompanion;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;

public final class ReplyKeysTest {
    @Test public void matchesCleanedReplyWithinItsSession() {
        assertEquals(ReplyKeys.key("chat-1", " answer "), ReplyKeys.key("chat-1", "answer"));
        assertNotEquals(ReplyKeys.key("chat-1", "answer"), ReplyKeys.key("chat-2", "answer"));
        assertNotEquals(ReplyKeys.key("chat-1", "answer"), ReplyKeys.key("chat-1", "other"));
    }
}
