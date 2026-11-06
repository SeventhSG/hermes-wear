package cloud.stormlabs.hermescompanion;

import static org.junit.Assert.*;
import org.junit.Test;

public class VoiceInputSettingsTest {
    private static SettingsStore fresh() { return new SettingsStore(NotificationOriginTest.preferences()); }

    @Test public void chatDefaultsGiveYouTimeToThinkAndTurnOnHoldToTalk() {
        SettingsStore s = fresh();
        assertEquals(2000, s.voicePauseMs());
        assertTrue(s.voiceHold());
        assertFalse(s.voiceManualStop());
        assertEquals("send", s.voiceSend());
        assertFalse("the server's early end-of-turn must not cut a 2 s pause short", s.serverMayEndTake());
    }

    @Test public void theAssistantStaysQuickUntilThePauseIsChosen() {
        SettingsStore s = fresh();
        assertEquals(1300, s.assistantPauseMs());
        assertTrue(s.assistantMayEndTake());
        s.voicePauseMs(3000);
        assertEquals(3000, s.assistantPauseMs());
        assertFalse(s.assistantMayEndTake());
    }

    @Test public void quickestPauseWithAutoStopRestoresTheServerShortcut() {
        SettingsStore s = fresh();
        s.voicePauseMs(1300);
        assertTrue(s.serverMayEndTake());
        s.voiceManualStop(true);
        assertFalse(s.serverMayEndTake());
        s.voiceManualStop(false);
        s.fastEndpoint(false);
        assertFalse(s.serverMayEndTake());
    }

    @Test public void valuesAreClampedAndReviewModeRoundTrips() {
        SettingsStore s = fresh();
        s.voicePauseMs(100); assertEquals(1300, s.voicePauseMs());
        s.voicePauseMs(99_000); assertEquals(8000, s.voicePauseMs());
        s.voiceSend("review"); assertEquals("review", s.voiceSend());
        s.voiceSend("junk"); assertEquals("send", s.voiceSend());
    }

    @Test public void speakingFromTheFirstFrameFoolsTheEnergyDetectorSoHoldToTalkMustNotTrustIt() {
        VoiceRecorder.Endpointer ep = new VoiceRecorder.Endpointer(1300, 9000);
        short[] loud = new short[800];
        for (int i = 0; i < loud.length; i++) loud[i] = (short) (i % 2 == 0 ? 2500 : -2500);
        for (int i = 0; i < 20; i++) ep.feed(loud, loud.length);   // steady speech from the very first frame
        assertFalse("calibrated on speech, the detector reports none", ep.speech);
        assertTrue(HoldToTalk.MIN_AUDIO_BYTES <= 16_000);
    }
}
