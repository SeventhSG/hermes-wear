package cloud.stormlabs.hermescompanion;

import org.junit.Test;
import static org.junit.Assert.*;

public class SpeechRecognitionTest {
    private final byte[] pcm = {0, 0};
    private final SpeechToText server = (audio, language) -> "server";
    private final SpeechToText phone = (audio, language) -> "phone";

    @Test public void autoUsesTheServerEvenWithTheModelInstalled() throws Exception {
        assertEquals("server", SpeechRecognition.transcribe("auto", true, server, phone, pcm, "el"));
        assertEquals("server", SpeechRecognition.transcribe("auto", false, server, phone, pcm, "en"));
    }

    @Test public void explicitModesDoNotChangeEngine() throws Exception {
        assertEquals("server", SpeechRecognition.transcribe("server", true, server, phone, pcm, "en"));
        assertEquals("phone", SpeechRecognition.transcribe("phone", true, server, phone, pcm, "en"));
        assertThrows(IllegalStateException.class, () -> SpeechRecognition.transcribe("phone", false, server, phone, pcm, "en"));
    }

    @Test public void autoFallsBackOnlyWhenModelIsInstalled() throws Exception {
        SpeechToText unreachable = (audio, language) -> { throw new java.io.IOException("offline"); };
        assertEquals("phone", SpeechRecognition.transcribe("auto", true, unreachable, phone, pcm, "en"));
        assertThrows(java.io.IOException.class, () -> SpeechRecognition.transcribe("auto", false, unreachable, phone, pcm, "en"));
        assertEquals("phone", SpeechRecognition.transcribe("server", true, unreachable, phone, pcm, "en"));
        SpeechToText badReply = (audio, language) -> { throw new IllegalStateException("protocol"); };
        assertThrows(IllegalStateException.class, () -> SpeechRecognition.transcribe("server", true, badReply, phone, pcm, "en"));
    }

    @Test public void phoneModeNeverSendsAudioToTheServer() throws Exception {
        SpeechToText brokenPhone = (audio, language) -> { throw new IllegalStateException("model"); };
        assertThrows(IllegalStateException.class, () -> SpeechRecognition.transcribe("phone", true, server, brokenPhone, pcm, "en"));
    }

    @Test public void removesNonSpeechFromEveryRecognizerResult() throws Exception {
        for (String noise : new String[] {"[music]", "*cough*", "[BLANK_AUDIO]", "[MUSIC]", "(music)", "[Applause]", "♪", "[music] *cough* ♪"}) {
            SpeechToText noisy = (audio, language) -> noise;
            assertEquals(noise, "", SpeechRecognition.transcribe("server", false, noisy, phone, pcm, "en"));
            assertEquals(noise, "", SpeechRecognition.transcribe("phone", true, server, noisy, pcm, "en"));
        }
        SpeechToText unreachable = (audio, language) -> { throw new java.io.IOException("offline"); };
        SpeechToText noisyPhone = (audio, language) -> "[music]";
        assertEquals("", SpeechRecognition.transcribe("server", true, unreachable, noisyPhone, pcm, "en"));
    }

    @Test public void keepsSpeechAndCollapsesSpacesAfterRemovingAnnotations() throws Exception {
        SpeechToText mixed = (audio, language) -> "[music] turn  it\t down *cough* (music)";
        assertEquals("turn it down", SpeechRecognition.transcribe("server", false, mixed, phone, pcm, "en"));
        SpeechToText joined = (audio, language) -> "turn[music]down";
        assertEquals("turn down", SpeechRecognition.transcribe("phone", true, server, joined, pcm, "en"));
        SpeechToText normal = (audio, language) -> "Turn it down, please!";
        assertEquals("Turn it down, please!", SpeechRecognition.transcribe("server", false, normal, phone, pcm, "en"));
        SpeechToText punctuation = (audio, language) -> "♪ !? *cough*";
        assertEquals("", SpeechRecognition.transcribe("server", false, punctuation, phone, pcm, "en"));
    }
}
