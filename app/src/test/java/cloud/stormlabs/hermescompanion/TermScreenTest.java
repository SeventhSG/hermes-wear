package cloud.stormlabs.hermescompanion;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class TermScreenTest {
    private static TermScreen run(String s) { TermScreen t = new TermScreen(24, 80); t.feed(s); return t; }
    private static final String E = "\u001b";

    @Test public void plainTextAndNewlines() { assertEquals("a\nbc", run("a\r\nbc").text()); }

    @Test public void carriageReturnOverwritesAndEraseToEndOfLine() {
        assertEquals("xyz", run("hello\rxyz" + E + "[K").text());
        assertEquals("hexlo", run("hello" + E + "[3D" + "x").screenText().split("\n")[0].trim().replace("hexlo", "hexlo"));
    }

    @Test public void backspaceRedrawLikeReadline() { assertEquals("ls", run("lss\b" + E + "[K").text()); }

    @Test public void cursorPositioningAndErase() {
        TermScreen t = run("abc" + E + "[2;5Hz" + E + "[1;1H" + E + "[Kx");
        assertEquals("x\n    z", t.text());
    }

    @Test public void colorsAndAttributesPerCell() {
        TermScreen t = run(E + "[1;31mred" + E + "[0m ok" + E + "[48;5;21mb");
        TermScreen.Row r = t.row(0);
        assertEquals("red okb", r.toString());
        assertTrue(r.fg[0] != 0 && (r.at[0] & TermScreen.BOLD) != 0);
        assertEquals(0, r.fg[4]); assertEquals(0, r.at[4]);
        assertEquals(0xff0000ff, r.bg[6]);
        assertEquals(0xffff5f00, TermScreen.xterm(202));
        assertEquals(0xff123456, run(E + "[38;2;18;52;86mx").row(0).fg[0]);
    }

    @Test public void titleAndModeSequencesAreSwallowed() {
        TermScreen t = run(E + "[?2004h" + E + "]0;root@box: ~\u0007root@box:~# ");
        assertEquals("root@box:~#", t.text()); assertEquals("root@box: ~", t.title); assertTrue(t.bracketedPaste);
    }

    @Test public void wrapAndScrollbackKeepHistory() {
        TermScreen t = new TermScreen(3, 4);
        t.feed("abcdefgh\r\nL2\r\nL3\r\nL4");
        assertEquals("abcd\nefgh\nL2\nL3\nL4", t.text());
        assertEquals(2, t.scrollbackSize()); assertEquals("efgh", t.row(-1).toString()); assertEquals("abcd", t.row(-2).toString());
    }

    @Test public void lastColumnWrapsOnlyWhenTheNextCharacterArrives() {
        TermScreen t = new TermScreen(3, 4); t.feed("abcd\r");
        assertEquals("abcd", t.screenText().split("\n")[0]); assertEquals(0, t.y);
    }

    @Test public void fullScreenProgramLeavesTheScreenAsItWas() {
        TermScreen t = run("$ vim\r\n");
        t.feed(E + "[?1049h" + E + "[Hin editor" + E + "[?1049l");
        assertFalse(t.altScreen); assertEquals("$ vim", t.text().trim()); assertEquals(1, t.y);
    }

    @Test public void scrollRegionKeepsStatusLine() {
        TermScreen t = new TermScreen(4, 10);
        t.feed(E + "[4;1Hstatus" + E + "[1;3r" + E + "[3;1Hone\r\ntwo\r\nthree");
        assertEquals("one", t.row(0).toString()); assertEquals("two", t.row(1).toString()); assertEquals("three", t.row(2).toString());
        assertEquals("status", t.row(3).toString());
    }

    @Test public void insertAndDeleteLinesAndCharacters() {
        TermScreen t = new TermScreen(4, 10);
        t.feed("a\r\nb\r\nc" + E + "[2;1H" + E + "[L");
        assertEquals("a\n\nb\nc", t.screenText().replaceAll("\n$", ""));
        t = new TermScreen(2, 10); t.feed("abcdef" + E + "[1;2H" + E + "[2P");
        assertEquals("adef", t.row(0).toString());
        t.feed(E + "[1;2H" + E + "[2@");
        assertEquals("a  def", t.row(0).toString());
    }

    @Test public void answersCursorPositionAndDeviceAttributes() {
        StringBuilder sent = new StringBuilder();
        TermScreen t = new TermScreen(24, 80); t.onReply(sent::append);
        t.feed("ab" + E + "[6n" + E + "[c");
        assertEquals(E + "[1;3R" + E + "[?62;c", sent.toString());
    }

    @Test public void lineDrawingCharacters() { assertEquals("┌─┐", run(E + "(0lqk" + E + "(B").text()); }

    @Test public void resizeKeepsTheCursorLineAndMovesTopToScrollback() {
        TermScreen t = new TermScreen(4, 10); t.feed("1\r\n2\r\n3\r\n4");
        t.resize(2, 10);
        assertEquals("3", t.row(0).toString()); assertEquals("4", t.row(1).toString()); assertEquals(1, t.y);
        assertEquals("2", t.row(-1).toString());
    }

    @Test public void scrollbackIsCapped() {
        TermScreen t = new TermScreen(5, 20);
        for (int i = 0; i < 6000; i++) t.feed("line " + i + "\r\n");
        assertTrue(t.scrollbackSize() <= 5000); assertTrue(t.text().contains("line 5999"));
    }

    @Test public void tracksMouseAndFocusModesTheProgramAsksFor() {
        TermScreen t = run(E + "[?1002h" + E + "[?1006h" + E + "[?1004h");
        assertEquals(1002, t.mouseMode); assertTrue(t.mouseSgr); assertTrue(t.focusEvents);
        t.feed(E + "[?1002l"); assertEquals(0, t.mouseMode);
        t.feed(E + "[?1000h" + E + "[?1003h" + E + "[?1000l"); assertEquals(1003, t.mouseMode);
    }

    @Test public void repeatCharacterAndBackTab() {
        assertEquals("abbbb", run("ab" + E + "[3b").text());
        TermScreen t = run("\t\tx" + E + "[Z"); assertEquals(16, t.x);
    }

    @Test public void answersTheColourSizeAndKeyboardQueriesHerdrAsks() {
        StringBuilder sent = new StringBuilder();
        TermScreen t = new TermScreen(40, 100); t.onReply(sent::append);
        t.defaultFg = 0xffd7dde5; t.defaultBg = 0xff0e1116; t.cellPxW = 11; t.cellPxH = 22;
        t.feed(E + "]10;?" + E + "\\" + E + "]11;?\u0007" + E + "[?996n" + E + "[16t" + E + "[14t" + E + "[?u" + E + "]4;1;?" + E + "\\");
        assertEquals(E + "]10;rgb:d7d7/dddd/e5e5" + E + "\\" + E + "]11;rgb:0e0e/1111/1616" + E + "\\" + E + "[?997;1n" + E + "[6;22;11t" + E + "[4;880;1100t" + E + "[?0u"
                + E + "]4;1;rgb:cccc/4444/4444" + E + "\\", sent.toString());
    }

    @Test public void keyboardProtocolPushDoesNotMoveTheCursor() {
        TermScreen t = run("ab" + E + "[>7u" + E + "[?7l");
        assertEquals(2, t.x); assertFalse(t.autowrap);
    }

    @Test public void modeQueriesAreAnswered() {
        StringBuilder sent = new StringBuilder();
        TermScreen t = new TermScreen(24, 80); t.onReply(sent::append);
        t.feed(E + "[?1006h" + E + "[?1006$p" + E + "[?2026$p" + E + "[?9999$p");
        assertEquals(E + "[?1006;1$y" + E + "[?2026;2$y" + E + "[?9999;0$y", sent.toString());
    }

    @Test public void herdrStartupSequencesLeaveMouseOnAndTheScreenIntact() {
        TermScreen t = new TermScreen(40, 100);
        t.feed(E + "[?1006l" + E + "[?1003l" + E + "[?1049h" + E + "[?1000h" + E + "[?1002h" + E + "[?1003h" + E + "[?1015h" + E + "[?1006h" + E + "[?2004h" + E + "[?1004h"
                + E + "[?2031h" + E + "[>7u" + E + "[?7l" + E + "[2J" + E + "[?2026h" + E + "]8;;" + E + "\\hello" + E + "[?2026l");
        assertEquals(1003, t.mouseMode); assertTrue(t.mouseSgr); assertTrue(t.altScreen); assertEquals("hello", t.screenText().split("\n")[0]);
    }

    @Test public void realHerdrStartupCaptureRendersWithoutErrors() throws Exception {
        byte[] raw;
        try (java.io.InputStream in = getClass().getResourceAsStream("/herdr-startup.bin")) { raw = in.readAllBytes(); }
        StringBuilder replies = new StringBuilder();
        TermScreen t = new TermScreen(40, 100); t.onReply(replies::append);
        t.feed(new String(raw, java.nio.charset.StandardCharsets.UTF_8));
        assertEquals(1003, t.mouseMode); assertTrue(t.mouseSgr); assertTrue(t.altScreen); assertTrue(t.focusEvents);
        assertTrue(replies.toString().contains("]11;rgb:"));
        assertTrue(replies.toString().contains("]4;255;rgb:"));
        assertTrue(t.screenText().replaceAll("\\s", "").length() > 50);
    }

    @Test public void wideCharactersTakeTwoCellsAndKeepColumnsAligned() {
        TermScreen t = run("日本a\r\n😀b");
        assertEquals("日本a\n😀b", t.text());
        assertEquals(TermScreen.TAIL, t.row(0).ch[1]);
        assertEquals('a', t.row(0).ch[4]);
        assertEquals('b', t.row(1).ch[2]);
        assertEquals(3, t.x);
    }

    @Test public void combiningMarksAndJoinedEmojiShareACell() {
        TermScreen t = run("e\u0301x");
        assertEquals("e\u0301x", t.text());
        assertEquals('x', t.row(0).ch[1]);
        TermScreen z = run("\uD83D\uDC69\u200D\uD83D\uDCBBz"); // woman, ZWJ, laptop
        assertEquals("\uD83D\uDC69\u200D\uD83D\uDCBBz", z.text());
        assertEquals('z', z.row(0).ch[2]);
    }

    @Test public void aWideCharacterWrapsInsteadOfSplittingAtTheLastColumn() {
        TermScreen t = new TermScreen(3, 5);
        t.feed("abcd日");
        assertEquals("abcd\n日", t.text());
        t.feed("\r" + E + "[2;2Hx"); // overwrite the right half: the left half goes blank
        assertEquals("abcd\n x", t.text());
    }

    @Test public void widthTable() {
        assertEquals(1, TermScreen.width('a'));
        assertEquals(2, TermScreen.width('日'));
        assertEquals(2, TermScreen.width(0x1F600));
        assertEquals(0, TermScreen.width(0x301));
        assertEquals(1, TermScreen.width('─'));
    }
}
