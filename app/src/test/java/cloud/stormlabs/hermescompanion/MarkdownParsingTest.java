package cloud.stormlabs.hermescompanion;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.List;

public class MarkdownParsingTest {
    @Test public void fencesWithAndWithoutLanguageAndStreamingEnd() {
        List<Markdown.Block> blocks = Markdown.parse("```java\nint x = 1;\n```\n~~~\nplain\n~~~\n```\npartial");
        assertEquals(3, blocks.size());
        assertEquals(Markdown.Kind.CODE, blocks.get(0).kind);
        assertEquals("java", blocks.get(0).extra);
        assertEquals("int x = 1;", blocks.get(0).text);
        assertEquals("", blocks.get(1).extra);
        assertEquals("plain", blocks.get(1).text);
        assertEquals("", blocks.get(2).extra);
        assertEquals("partial", blocks.get(2).text);
    }

    @Test public void inlineCodeKeepsMarkupLiteral() {
        Markdown.Inline in = Markdown.inline("`a*b_c` and *yes*");
        assertEquals("a*b_c and yes", in.text);
        assertEquals(2, in.spans.size());
        assertEquals(Markdown.Style.CODE, in.spans.get(0).style);
        assertEquals("a*b_c", in.text.substring(in.spans.get(0).start, in.spans.get(0).end));
        assertEquals(Markdown.Style.ITALIC, in.spans.get(1).style);
    }

    @Test public void nestedAndNumberedListsKeepDepthAndNumbers() {
        List<Markdown.Block> blocks = Markdown.parse("- outer\n  - child\n    2. second\n10) tenth");
        assertEquals(4, blocks.size());
        assertEquals(Markdown.Kind.BULLET, blocks.get(0).kind);
        assertEquals(0, blocks.get(0).level);
        assertEquals(1, blocks.get(1).level);
        assertEquals(Markdown.Kind.ORDERED, blocks.get(2).kind);
        assertEquals(2, blocks.get(2).level);
        assertEquals("2", blocks.get(2).extra);
        assertEquals("10", blocks.get(3).extra);
        assertEquals(0, blocks.get(3).level);
    }

    @Test public void tableAlignmentRaggedRowsAndCodePipes() {
        List<Markdown.Block> blocks = Markdown.parse("| Name | Value | Note |\n|:---|---:|:---:|\n| one | `a|b` | yes |\n| two | short |");
        assertEquals(1, blocks.size());
        assertEquals(Markdown.Kind.TABLE, blocks.get(0).kind);
        List<String[]> rows = Markdown.tableRows(blocks.get(0).text);
        assertEquals(3, rows.size());
        assertArrayEquals(new String[]{"Name", "Value", "Note"}, rows.get(0));
        assertArrayEquals(new String[]{"one", "`a|b`", "yes"}, rows.get(1));
        assertArrayEquals(new String[]{"two", "short"}, rows.get(2));
    }

    @Test public void headingsAndBlockquotes() {
        List<Markdown.Block> blocks = Markdown.parse("# Top\n### Detail ###\n> first\n> second\nAfter");
        assertEquals(4, blocks.size());
        assertEquals(Markdown.Kind.HEADING, blocks.get(0).kind);
        assertEquals(1, blocks.get(0).level);
        assertEquals("Detail", blocks.get(1).text);
        assertEquals(3, blocks.get(1).level);
        assertEquals(Markdown.Kind.QUOTE, blocks.get(2).kind);
        assertEquals("first\nsecond", blocks.get(2).text);
        assertEquals("After", blocks.get(3).text);
    }

    @Test public void linkUrlCanContainParentheses() {
        Markdown.Inline in = Markdown.inline("[Article](https://example.com/wiki/Foo_(bar)) next");
        assertEquals("Article next", in.text);
        assertEquals(1, in.spans.size());
        assertEquals(Markdown.Style.LINK, in.spans.get(0).style);
        assertEquals("https://example.com/wiki/Foo_(bar)", in.spans.get(0).url);
    }

    @Test(timeout = 5000) public void veryLongLineStaysWhole() {
        StringBuilder line = new StringBuilder();
        for (int i = 0; i < 50000; i++) line.append('x');
        String source = line.toString();
        List<Markdown.Block> blocks = Markdown.parse(source);
        assertEquals(1, blocks.size());
        assertEquals(source, blocks.get(0).text);
        Markdown.Inline in = Markdown.inline(source);
        assertEquals(source, in.text);
        assertTrue(in.spans.isEmpty());
    }

    @Test public void tableKeepsAlignmentRow() {
        Markdown.Block t = Markdown.parse("| a | b | c |\n|:---|:---:|---:|\n| 1 | 2 | 3 |").get(0);
        assertArrayEquals(new int[]{0, 1, 2}, Markdown.aligns(t.extra));
        assertEquals(2, Markdown.tableRows(t.text).size());
    }

    @Test public void bareImageAndPdfUrlsBecomeBlocks() {
        List<Markdown.Block> b = Markdown.parse("https://x.io/a.PNG?w=1\n\nMEDIA: https://x.io/r%20.pdf\n\n[Spec](https://x.io/s.pdf)\n\nsee https://x.io/a.png");
        assertEquals(Markdown.Kind.IMAGE, b.get(0).kind);
        assertEquals(Markdown.Kind.FILE, b.get(1).kind);
        assertEquals("r .pdf", b.get(1).text);
        assertEquals("Spec", b.get(2).text);
        assertEquals(Markdown.Kind.PARAGRAPH, b.get(3).kind);
    }

    @Test public void serverMediaPathsBecomeBlocks() {
        List<Markdown.Block> b = Markdown.parse("MEDIA:/tmp/shot.png\n\nMEDIA: /root/out/report.pdf");
        assertEquals(Markdown.Kind.IMAGE, b.get(0).kind);
        assertEquals("/tmp/shot.png", b.get(0).extra);
        assertEquals(Markdown.Kind.FILE, b.get(1).kind);
        assertEquals("report.pdf", b.get(1).text);
    }

    @Test public void inlineAndOtherMediaTagsBecomeFileBlocks() {
        List<Markdown.Block> b = Markdown.parse("Here is the chart MEDIA:/tmp/c.png, and the audio: MEDIA:/tmp/a.mp3.\n```\nMEDIA:/tmp/code.png\n```");
        assertEquals(Markdown.Kind.PARAGRAPH, b.get(0).kind);
        assertEquals(Markdown.Kind.IMAGE, b.get(1).kind);
        assertEquals("/tmp/c.png", b.get(1).extra);
        assertEquals(Markdown.Kind.FILE, b.get(3).kind);
        assertEquals("/tmp/a.mp3", b.get(3).extra);
        assertEquals(Markdown.Kind.CODE, b.get(b.size() - 1).kind);
    }

    @Test public void agentFileReplyRendersEveryFile() {
        List<Markdown.Block> b = Markdown.parse("Here are the RAM usage files.\n\nMEDIA:/root/ram_chart.png\nMEDIA:/root/ram_summary.pdf\n/root/ram_usage.csv\n\n/etc/nginx/nginx.conf");
        assertEquals(Markdown.Kind.IMAGE, b.get(1).kind);
        assertEquals(Markdown.Kind.FILE, b.get(2).kind);
        assertEquals(Markdown.Kind.FILE, b.get(3).kind);
        assertEquals("/root/ram_usage.csv", b.get(3).extra);
        assertEquals(Markdown.Kind.PARAGRAPH, b.get(4).kind);
    }
}
