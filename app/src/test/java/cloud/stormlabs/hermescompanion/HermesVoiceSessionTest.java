package cloud.stormlabs.hermescompanion;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class HermesVoiceSessionTest {
    @Test public void failedVoiceRunNeverSpeaksOrListens() {
        assertFalse(HermesVoiceSession.shouldSpeakReply(true, false, true, "Network error"));
        assertFalse(HermesVoiceSession.shouldListenAfterRun(true, true, "Network error"));
        assertTrue(HermesVoiceSession.shouldSpeakReply(true, false, true, null));
        assertTrue(HermesVoiceSession.shouldListenAfterRun(true, true, null));
    }

    @Test public void delayedAgentCommandRequiresSameVisibleInvocation() {
        assertTrue(HermesVoiceSession.shouldSubmitDelayed(4, 4, true));
        assertFalse(HermesVoiceSession.shouldSubmitDelayed(4, 5, true));
        assertFalse(HermesVoiceSession.shouldSubmitDelayed(4, 4, false));
    }

    @Test public void onlyRealCommandsStopOldSpeech() {
        assertTrue(HermesVoiceSession.shouldStopSpeakerForSubmit("new command"));
        assertFalse(HermesVoiceSession.shouldStopSpeakerForSubmit("  "));
        assertFalse(HermesVoiceSession.shouldStopSpeakerForSubmit(null));
    }

    @Test public void stopCommandNormalizesCasePunctuationAndWakeWord() {
        assertEquals("thats enough", HermesVoiceSession.normalizeStopCommand("Hermes, that's enough!"));
        for (String text : new String[] {"STOP!", "cancel", "Hermes, stop it.", "hermes cancel it", "That's enough"})
            assertTrue(text, HermesVoiceSession.isAgentStopCommand(text));
        for (String text : new String[] {"stopwatch", "stop the timer", "Hermes, what time is it?", null})
            assertFalse(String.valueOf(text), HermesVoiceSession.isAgentStopCommand(text));
    }

    @org.junit.Test public void hangUpPhrasesEndACall() {
        org.junit.Assert.assertTrue(HermesVoiceSession.isHangUp("Hang up."));
        org.junit.Assert.assertTrue(HermesVoiceSession.isHangUp("Hermes, goodbye!"));
        org.junit.Assert.assertTrue(HermesVoiceSession.isHangUp("\u0427\u0430\u0432"));
        org.junit.Assert.assertFalse(HermesVoiceSession.isHangUp("hang up the washing"));
        org.junit.Assert.assertFalse(HermesVoiceSession.isHangUp(""));
    }
}
