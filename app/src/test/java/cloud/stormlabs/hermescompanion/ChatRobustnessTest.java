package cloud.stormlabs.hermescompanion;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/** Overnight robustness fixes in the chat screen that are pure logic. */
public class ChatRobustnessTest {
    @Test public void unsentTypedTextGoesBackIntoAnEmptyComposer() {
        assertTrue(ChatActivity.shouldRestoreUnsent("hello", false, false, null, ""));
        assertTrue(ChatActivity.shouldRestoreUnsent("hello", false, false, null, "  "));
    }

    @Test public void restoreNeverOverwritesNewTypingOrRestoresUntypedSends() {
        assertFalse(ChatActivity.shouldRestoreUnsent("hello", false, false, null, "already typing again"));
        assertFalse(ChatActivity.shouldRestoreUnsent("hello", true, false, null, ""));        // dictated
        assertFalse(ChatActivity.shouldRestoreUnsent("hello", false, true, null, ""));        // agent follow-up
        assertFalse(ChatActivity.shouldRestoreUnsent("hello", false, false, new Object(), "")); // retry of a failed run
        assertFalse(ChatActivity.shouldRestoreUnsent("", false, false, null, ""));
    }

    @Test public void resumeWorkIsThrottled() {
        assertTrue(ChatActivity.due(0, 1_000_000, 60_000));
        assertFalse(ChatActivity.due(1_000_000, 1_030_000, 60_000));
        assertTrue(ChatActivity.due(1_000_000, 1_060_000, 60_000));
        assertTrue(ChatActivity.due(1_000_000, 500_000, 60_000));
    }

    @Test public void onlyTheNewestConversationReloadWins() {
        assertTrue(ConversationList.isLatestLoad(3, 3));
        assertFalse(ConversationList.isLatestLoad(2, 3));
    }

    @Test public void networkFlapsRunOneProbeRoundAndAtMostOneRepeat() {
        assertTrue(Reach.claimRound());          // first change starts a round
        assertFalse(Reach.claimRound());         // second and third arrive while it runs: queued once
        assertFalse(Reach.claimRound());
        assertTrue(Reach.roundDone());           // the round repeats once...
        assertFalse(Reach.roundDone());          // ...and then the probe thread ends
        assertTrue(Reach.claimRound());          // idle again: a new change starts a fresh round
        assertFalse(Reach.roundDone());
    }
}
