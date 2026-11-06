package cloud.stormlabs.hermescompanion;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

public class StableCutTest {
    @Test public void cutsAfterLastBlankLineOutsideFences() {
        assertEquals(0, Markdown.stableCut("one line so far"));
        assertEquals(7, Markdown.stableCut("Intro\n\nMore text"));
        String fenced = "A\n\n```\ncode\n\nmore code";
        assertEquals(3, Markdown.stableCut(fenced)); // blank line inside the open fence doesn't count
        String closed = fenced + "\n```\n\nAfter";
        assertEquals(closed.indexOf("After"), Markdown.stableCut(closed));
    }
}
