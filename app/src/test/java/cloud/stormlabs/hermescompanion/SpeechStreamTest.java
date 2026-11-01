package cloud.stormlabs.hermescompanion;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

public class SpeechStreamTest {
    @Test public void nothingReadyUntilASentenceEnds() {
        assertEquals(0, SpeechStream.readyEnd("I checked the cluster and", 0));
        assertEquals(0, SpeechStream.readyEnd("", 0));
    }

    @Test public void firstSentenceIsSpokenAsSoonAsItEndsWithTrailingSpace() {
        String t = "Sure, checking now. The cluster";
        assertEquals("Sure, checking now.".length(), SpeechStream.readyEnd(t, 0));
    }

    @Test public void aPeriodAtTheVeryEndWaitsForTheNextCharacter() {
        assertEquals(0, SpeechStream.readyEnd("Sure, checking now.", 0));
    }

    @Test public void decimalsAndLinksAreNotSentenceEnds() {
        assertEquals(0, SpeechStream.readyEnd("It is version 3.5 and growing", 0));
        assertEquals(0, SpeechStream.readyEnd("See https://example.com/a.b for more", 0));
    }

    @Test public void abbreviationsAndListNumbersAreSkipped() {
        assertEquals(0, SpeechStream.readyEnd("Use a tool, e.g. the hub today", 0));
        assertEquals(0, SpeechStream.readyEnd("1. First item", 0));
    }

    @Test public void laterPiecesWaitForAFullerSentence() {
        String t = "Sure, checking now. Ok. The backup finished at three and everything looks healthy. Done";
        int first = SpeechStream.readyEnd(t, 0);
        assertEquals("Sure, checking now.".length(), first);
        int second = SpeechStream.readyEnd(t, first);
        assertEquals(t.indexOf("healthy.") + "healthy.".length(), second);
    }

    @Test public void newlinesEndAPiece() {
        String t = "Here is the plan for tonight\nthen more";
        assertEquals("Here is the plan for tonight\n".length(), SpeechStream.readyEnd(t, 0));
    }

    @Test public void neverSpeaksIntoAnOpenCodeFenceOrDeviceTag() {
        String t = "Let me run it. ```bash\nls -la. And more.";
        assertEquals("Let me run it.".length(), SpeechStream.readyEnd(t, 0));
        String d = "Opening it now. <device>{\"type\":\"open\"} done. ok";
        assertEquals("Opening it now.".length(), SpeechStream.readyEnd(d, 0));
        assertEquals("Opening it now.".length(), SpeechStream.readyEnd(d, "Opening it now.".length()));
    }

    @Test public void closedFenceIsFine() {
        String t = "Run this now. ```ls``` Then wait for the job to finish. Next";
        assertEquals("Run this now.".length(), SpeechStream.readyEnd(t, 0));
    }

    @Test public void fromBeyondEndReturnsFrom() {
        assertEquals(5, SpeechStream.readyEnd("abc", 5));
    }
}
