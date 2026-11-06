package cloud.stormlabs.hermescompanion;

import static org.junit.Assert.*;

import org.junit.Test;

import java.io.FileInputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Map;

public class WakePhraseTest {
    private static Map<String, Float> vocab() throws Exception {
        return WakePhrase.read(new InputStreamReader(new FileInputStream("src/main/assets/kws-vocab.txt"), StandardCharsets.UTF_8));
    }

    @Test public void spellsLikeTheModelsOwnKeywordsFile() throws Exception {
        Map<String, Float> v = vocab();
        assertEquals("▁HE LL O ▁WORLD", WakePhrase.tokens("hello world", v));
        assertEquals("▁HI ▁GO O G LE", WakePhrase.tokens("Hi, Google!", v));
        assertEquals("▁HE Y ▁S I RI", WakePhrase.tokens("hey siri", v));
        assertEquals("▁HER ME S", WakePhrase.tokens("Hermes", v));  // matches the built-in spelling
        assertNull(WakePhrase.tokens("  ", v));
    }

    @Test public void picksTheStrictestThresholdThatStillHearsMostTries() {
        assertEquals(.32f * WakePhrase.MARGIN, WakePhrase.pick(new int[]{2, 5, 9, 10, 10, 10, 10, 10}), 1e-6);
        assertEquals(.45f * WakePhrase.MARGIN, WakePhrase.pick(new int[]{10, 10, 10, 10, 10, 10, 10, 10}), 1e-6);
        assertEquals(.08f * WakePhrase.MARGIN, WakePhrase.pick(new int[]{0, 0, 0, 0, 0, 1, 3, 10}), 1e-6);
        assertEquals(0f, WakePhrase.pick(new int[8]), 1e-6);
    }

    @Test public void keywordLineAndLabel() {
        assertEquals("▁HE Y #0.200 @HERMES", WakePhrase.keyword("▁HE Y", .2f));
        assertEquals("▁HE Y @HERMES", WakePhrase.keyword("▁HE Y", 0));
        assertEquals("Hey Jarvis", WakePhrase.label(" hey   JARVIS! "));
    }

    @Test public void stripsTheCustomPhraseFromTheCommand() {
        try {
            WakeWordService.phrase = "Hey Jarvis";
            assertEquals("lights off", WakeWordService.stripWake("Hey, Jarvis. lights off"));
            assertEquals("Hermes, lights off", WakeWordService.stripWake("Hermes, lights off"));
        } finally { WakeWordService.phrase = ""; }
        assertEquals("lights off", WakeWordService.stripWake("Hermes, lights off"));
    }
}
