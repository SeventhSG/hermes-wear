package cloud.stormlabs.hermescompanion;

import org.junit.Test;
import static org.junit.Assert.*;

public class EndpointerTest {
    private static final int FRAME = 800; // 50 ms at 16 kHz

    private static short[] tone(int amplitude) {
        short[] s = new short[FRAME];
        for (int i = 0; i < FRAME; i++) s[i] = (short) (amplitude * Math.sin(i * 0.3));
        return s;
    }

    /** Feeds frames until done; returns elapsed ms, or -1 if it never finished. */
    private static int run(VoiceRecorder.Endpointer ep, short[][] script, int maxFrames) {
        for (int f = 0; f < maxFrames; f++) if (ep.feed(script[Math.min(f, script.length - 1)], FRAME)) return (f + 1) * 50;
        return -1;
    }

    @Test public void silenceAloneEndsAfterNoSpeechWindow() {
        VoiceRecorder.Endpointer ep = new VoiceRecorder.Endpointer(1300, 6000);
        int ms = run(ep, new short[][]{tone(50)}, 400);
        assertFalse(ep.speech);
        assertTrue("ended at " + ms, ms > 6000 && ms <= 6100);
    }

    @Test public void speechThenSilenceEndsAfterSilenceWindow() {
        VoiceRecorder.Endpointer ep = new VoiceRecorder.Endpointer(1300, 6000);
        short[][] script = new short[60][];
        for (int i = 0; i < 60; i++) script[i] = i < 10 ? tone(50) : i < 30 ? tone(8000) : tone(50);
        int ms = run(ep, script, 400);
        assertTrue(ep.speech);
        assertTrue("ended at " + ms, ms > 1500 + 1300 && ms <= 1500 + 1400);
    }

    @Test public void louderRoomAfterSpeechStillEnds() {
        // After speaking, the background is several times the quiet lead-in (AGC lift, a fan): that is not speech.
        VoiceRecorder.Endpointer ep = new VoiceRecorder.Endpointer(1300, 6000);
        short[][] script = new short[60][];
        for (int i = 0; i < 60; i++) script[i] = i < 10 ? tone(50) : i < 30 ? tone(8000) : tone(500);
        int ms = run(ep, script, 400);
        assertTrue(ep.speech);
        assertTrue("ended at " + ms, ms > 1500 + 1300 && ms <= 1500 + 1400);
    }

    @Test public void steadyNoiseWithoutSpeechIsAbsorbed() {
        // A loud steady room right from the start never counts as an endless utterance.
        VoiceRecorder.Endpointer ep = new VoiceRecorder.Endpointer(1300, 6000);
        short[][] script = new short[200][];
        for (int i = 0; i < 200; i++) script[i] = i < 10 ? tone(50) : tone(1500);
        assertTrue(run(ep, script, 400) > 0);
    }

    @Test public void seededFloorCountsSpeechFromTheFirstFrame() {
        // Right after the wake word there is no quiet lead-in to learn the room from.
        VoiceRecorder.Endpointer ep = new VoiceRecorder.Endpointer(1300, 6000, 40);
        ep.feed(tone(4000), FRAME);
        assertTrue(ep.speech);
    }

    @Test public void wakeKeywordsAreWellFormed() {
        int wake = 0;
        for (String line : WakeWordService.KEYWORDS.split("\n")) {
            assertTrue(line, line.matches("(▁?[A-Z]+ )+@(HERMES|DECOY)"));
            if (line.endsWith("@" + WakeWordService.WAKE)) wake++;
        }
        assertTrue(wake >= 1);
    }

    @Test public void junkTranscriptsAreNeverSent() {
        assertTrue(WakeWordService.junkTranscript("Thank you."));
        assertTrue(WakeWordService.junkTranscript(" you "));
        assertTrue(WakeWordService.junkTranscript("..."));
        assertTrue(WakeWordService.junkTranscript(""));
        assertFalse(WakeWordService.junkTranscript("What's the weather?"));
        assertFalse(WakeWordService.junkTranscript("Thank you, now open Spotify"));
    }

    @Test public void wakeGainOnlyAmplifiesQuietSpeech() {
        assertEquals(10f, WakeWordService.wakeGain(300), 0.01f);   // quiet: up to a normal level
        assertEquals(30f, WakeWordService.wakeGain(20), 0.01f);    // capped
        assertEquals(1f, WakeWordService.wakeGain(8000), 0.01f);   // loud speech is never attenuated
        assertEquals(1f, WakeWordService.wakeGain(0), 0.01f);
    }

    @Test public void wakeSensitivitySetsOnlyThreshold() {
        assertEquals(0.22f, WakeWordService.threshold("Low"), 0f);
        assertEquals(0.13f, WakeWordService.threshold("Normal"), 0f);
        assertEquals(0.10f, WakeWordService.threshold("High"), 0f);
        assertEquals(0.13f, WakeWordService.threshold("unknown"), 0f);
    }

    @Test public void wakePauseExpiresByFirstClockAndNeverExtendsPastOneHour() {
        long hour = WakeWordService.PAUSE_MS;
        assertEquals(hour, WakeWordService.pauseRemaining(10_000 + hour, 1_000, 10_000, 1_000));
        assertEquals(20_000, WakeWordService.pauseRemaining(10_000 + hour, 1_000, 10_000 + hour - 20_000, 1_000 + 100));
        assertEquals(10_000, WakeWordService.pauseRemaining(10_000 + hour, 1_000, 5_000, 1_000 + hour - 10_000));
        assertEquals(0, WakeWordService.pauseRemaining(10_000 + hour, 1_000, 10_000 + hour, 1_000 + 100));
        assertEquals(0, WakeWordService.pauseRemaining(10_000 + hour, 1_000, 5_000, 1_000 + hour));
        assertEquals(0, WakeWordService.pauseRemaining(10_000 + hour, 1_000, 5_000, 500));
    }

    @Test public void wakeWordIsStrippedFromCommandTranscripts() {
        assertEquals("what time is it?", WakeWordService.stripWake("Hermes, what time is it?"));
        assertEquals("what time is it?", WakeWordService.stripWake(" Hey Hermès what time is it?"));
        assertEquals("lights off", WakeWordService.stripWake("s, lights off".replaceFirst("^s, ", "Herms, ")));
        assertEquals("", WakeWordService.stripWake("Hermes."));
    }

    @Test public void ordinaryFirstWordsSurvive() {
        assertEquals("Harm reduction tips", WakeWordService.stripWake("Harm reduction tips"));
        assertEquals("Ham sandwich recipe", WakeWordService.stripWake("Ham sandwich recipe"));
        assertEquals("Hermits live alone", WakeWordService.stripWake("Hermits live alone"));
        assertEquals("What time is it?", WakeWordService.stripWake("What time is it?"));
    }
}
