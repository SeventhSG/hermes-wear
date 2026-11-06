package cloud.stormlabs.hermescompanion;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A small, forgiving Markdown parser for agent replies.
 *
 * It splits a reply into blocks (paragraphs, headings, fenced code, quotes, list items, rules,
 * images, tables) and inline spans (bold, italic, strike, code, links, bare URLs). It is pure Java
 * so it is unit-tested on the JVM; {@link MarkdownView} turns the result into Android views.
 * Unclosed constructs are tolerated because text renders while it is still streaming.
 */
public final class Markdown {
    private Markdown() {}

    public enum Kind { PARAGRAPH, HEADING, CODE, QUOTE, BULLET, ORDERED, RULE, IMAGE, TABLE, FILE }

    public static final class Block {
        public final Kind kind; public final String text; public final int level; public final String extra;
        Block(Kind kind, String text, int level, String extra) { this.kind = kind; this.text = text; this.level = level; this.extra = extra; }
        @Override public String toString() { return kind + "(" + level + "," + text + (extra == null ? "" : "," + extra) + ")"; }
    }

    public enum Style { BOLD, ITALIC, STRIKE, CODE, LINK }

    public static final class Span {
        public final int start, end; public final Style style; public final String url;
        Span(int start, int end, Style style, String url) { this.start = start; this.end = end; this.style = style; this.url = url; }
    }

    public static final class Inline {
        public final String text; public final List<Span> spans;
        Inline(String text, List<Span> spans) { this.text = text; this.spans = spans; }
    }

    private static final Pattern IMAGE_LINE = Pattern.compile("^!\\[([^\\]]*)\\]\\((\\S+?)(?:\\s+\"[^\"]*\")?\\)\\s*$");
    private static final Pattern HEADING = Pattern.compile("^(#{1,6})\\s+(.*?)\\s*#*\\s*$");
    private static final Pattern BULLET = Pattern.compile("^(\\s*)[-*+•]\\s+(.*)$");
    private static final Pattern ORDERED = Pattern.compile("^(\\s*)(\\d{1,4})[.)]\\s+(.*)$");
    private static final Pattern RULE = Pattern.compile("^\\s*([-*_])(\\s*\\1){2,}\\s*$");
    private static final Pattern TABLE_SEP = Pattern.compile("^\\s*\\|?\\s*:?-{2,}:?\\s*(\\|\\s*:?-{2,}:?\\s*)*\\|?\\s*$");

    // MEDIA:/absolute/path is how Hermes attaches a file on the server; the hub serves it (/media).
    private static final Pattern IMG_URL = Pattern.compile("(?i)^(?:MEDIA:\\s*)?(https?://\\S+?\\.(?:png|jpe?g|gif|webp)(?:\\?\\S*)?|data:image/\\S+)$|^MEDIA:\\s*(/\\S+\\.(?:png|jpe?g|gif|webp))$");
    private static final Pattern PDF_URL = Pattern.compile("(?i)^(?:MEDIA:\\s*)?(https?://\\S+?\\.pdf(?:\\?\\S*)?)$|^MEDIA:\\s*(/\\S+\\.pdf)$");
    /** Any other server file Hermes attaches: audio, video, documents, archives. */
    private static final Pattern FILE_MEDIA = Pattern.compile("^MEDIA:\\s*(/\\S+)$");
    /** A line that is only an absolute server path to a file (models sometimes drop the MEDIA: prefix). */
    private static final Pattern BARE_PATH = Pattern.compile("(?i)^`?(/(?:[\\w.@+-]+/)*[\\w@+-][\\w.@+-]*\\."
            + "(?:png|jpe?g|gif|webp|pdf|csv|xlsx|docx|pptx|odt|epub|zip|mp3|m4a|wav|ogg|opus|flac|mp4|webm|mov))`?$");
    /** A MEDIA tag inside a sentence; trailing punctuation is not part of the path. */
    private static final Pattern MEDIA_INLINE = Pattern.compile("MEDIA:[ \\t]*(/[^\\s`\"'<>]+?)[.,;:!?)]*(?=\\s|$)");
    private static final Pattern PDF_LINK = Pattern.compile("(?i)^(?:📄\\s*)?\\[([^\\]]*)\\]\\((\\S+?\\.pdf(?:\\?\\S*)?)\\)\\s*$");

    /** Parsed blocks per message text: the chat re-renders every bubble often, and some replies carry MB of inlined images. */
    private static final java.util.Map<String, List<Block>> PARSED = new java.util.LinkedHashMap<String, List<Block>>(64, .75f, true) {
        @Override protected boolean removeEldestEntry(java.util.Map.Entry<String, List<Block>> e) { return size() > 200; }
    };

    public static List<Block> parse(String source) {
        if (source == null || source.isEmpty()) return new ArrayList<>();
        synchronized (PARSED) { List<Block> hit = PARSED.get(source); if (hit != null) return new ArrayList<>(hit); }
        List<Block> out = parseUncached(source);
        synchronized (PARSED) { PARSED.put(source, out); }
        return new ArrayList<>(out);
    }

    private static List<Block> parseUncached(String source) {
        List<Block> out = new ArrayList<>();
        String[] lines = splitMedia(source.replace("\r\n", "\n")).split("\n", -1);
        StringBuilder para = new StringBuilder();
        int i = 0;
        while (i < lines.length) {
            String line = lines[i];
            String trimmed = line.trim();
            if (trimmed.startsWith("```") || trimmed.startsWith("~~~")) {
                flush(para, out);
                String fence = trimmed.substring(0, 3);
                String lang = trimmed.substring(3).trim();
                StringBuilder code = new StringBuilder();
                i++;
                while (i < lines.length && !lines[i].trim().startsWith(fence)) {
                    if (code.length() > 0) code.append('\n');
                    code.append(lines[i]);
                    i++;
                }
                // level 1 = the fence is closed; a live page runs only once Hermes has finished writing it
                out.add(new Block(Kind.CODE, code.toString(), i < lines.length ? 1 : 0, lang));
                i++; // closing fence (or end of a still-streaming block)
                continue;
            }
            if (trimmed.isEmpty()) { flush(para, out); i++; continue; }
            Matcher m;
            if ((m = IMAGE_LINE.matcher(trimmed)).matches()) { flush(para, out); out.add(new Block(Kind.IMAGE, m.group(1), 0, m.group(2))); i++; continue; }
            if (para.length() == 0) {
                String media = (m = BARE_PATH.matcher(trimmed)).matches() ? "MEDIA:" + m.group(1) : trimmed;
                if ((m = IMG_URL.matcher(media)).matches()) { out.add(new Block(Kind.IMAGE, "", 0, m.group(1) != null ? m.group(1) : m.group(2))); i++; continue; }
                if ((m = PDF_URL.matcher(media)).matches()) { String u = m.group(1) != null ? m.group(1) : m.group(2); out.add(new Block(Kind.FILE, fileName(u), 0, u)); i++; continue; }
                if ((m = FILE_MEDIA.matcher(media)).matches()) { out.add(new Block(Kind.FILE, fileName(m.group(1)), 0, m.group(1))); i++; continue; }
                if ((m = PDF_LINK.matcher(trimmed)).matches()) { out.add(new Block(Kind.FILE, m.group(1).isEmpty() ? fileName(m.group(2)) : m.group(1), 0, m.group(2))); i++; continue; }
            }
            if ((m = HEADING.matcher(trimmed)).matches()) { flush(para, out); out.add(new Block(Kind.HEADING, m.group(2), m.group(1).length(), null)); i++; continue; }
            if (RULE.matcher(trimmed).matches() && para.length() == 0) { out.add(new Block(Kind.RULE, "", 0, null)); i++; continue; }
            if (trimmed.startsWith(">")) {
                flush(para, out);
                StringBuilder q = new StringBuilder();
                while (i < lines.length && lines[i].trim().startsWith(">")) {
                    String l = lines[i].trim().substring(1);
                    if (l.startsWith(" ")) l = l.substring(1);
                    if (q.length() > 0) q.append('\n');
                    q.append(l);
                    i++;
                }
                out.add(new Block(Kind.QUOTE, q.toString(), 0, null));
                continue;
            }
            if (trimmed.startsWith("|") && i + 1 < lines.length && TABLE_SEP.matcher(lines[i + 1]).matches()) {
                flush(para, out);
                StringBuilder t = new StringBuilder(line.trim());
                String sep = lines[i + 1].trim();
                i += 2;
                while (i < lines.length && lines[i].trim().startsWith("|")) { t.append('\n').append(lines[i].trim()); i++; }
                out.add(new Block(Kind.TABLE, t.toString(), 0, sep));
                continue;
            }
            if ((m = BULLET.matcher(line)).matches() && !RULE.matcher(trimmed).matches()) {
                flush(para, out);
                out.add(new Block(Kind.BULLET, m.group(2), indent(m.group(1)), null));
                i++; continue;
            }
            if ((m = ORDERED.matcher(line)).matches()) {
                flush(para, out);
                out.add(new Block(Kind.ORDERED, m.group(3), indent(m.group(1)), m.group(2)));
                i++; continue;
            }
            // A continuation line of a list item stays with it.
            if (para.length() == 0 && !out.isEmpty() && (line.startsWith("  ") || line.startsWith("\t"))) {
                Block last = out.get(out.size() - 1);
                if (last.kind == Kind.BULLET || last.kind == Kind.ORDERED) {
                    out.set(out.size() - 1, new Block(last.kind, last.text + " " + trimmed, last.level, last.extra));
                    i++; continue;
                }
            }
            if (para.length() > 0) para.append('\n');
            para.append(line);
            i++;
        }
        flush(para, out);
        return out;
    }

    /**
     * End of the last blank line outside a code fence: text before it is finished Markdown that won't change while
     * a reply streams, so its views can be reused. 0 when there is none.
     */
    static int stableCut(String s) {
        int cut = 0, at = 0; boolean fenced = false;
        for (String line : s.split("\n", -1)) {
            int next = at + line.length() + 1;
            String t = line.trim();
            if (t.startsWith("```") || t.startsWith("~~~")) fenced = !fenced;
            else if (t.isEmpty() && !fenced && next <= s.length()) cut = next;
            at = next;
        }
        return cut;
    }

    /** Puts every MEDIA:/path outside code fences on a line of its own so it renders as media, not text. */
    static String splitMedia(String s) {
        if (!s.contains("MEDIA:")) return s;
        StringBuilder out = new StringBuilder(); boolean fenced = false;
        for (String line : s.split("\n", -1)) {
            String t = line.trim();
            if (t.startsWith("```") || t.startsWith("~~~")) fenced = !fenced;
            if (out.length() > 0) out.append('\n');
            out.append(fenced || !line.contains("MEDIA:") ? line : MEDIA_INLINE.matcher(line).replaceAll("\n\nMEDIA:$1\n\n"));
        }
        return out.toString();
    }

    static String fileName(String url) {
        String p = url.replaceAll("[?#].*$", ""); p = p.substring(p.lastIndexOf('/') + 1);
        try { return java.net.URLDecoder.decode(p, "UTF-8"); } catch (Exception e) { return p; }
    }

    /** Column alignment from a table separator row: 0 left, 1 center, 2 right. */
    public static int[] aligns(String sep) {
        if (sep == null) return new int[0];
        String[] c = tableRows(sep).get(0);
        int[] a = new int[c.length];
        for (int k = 0; k < c.length; k++) a[k] = c[k].endsWith(":") ? (c[k].startsWith(":") ? 1 : 2) : 0;
        return a;
    }

    private static int indent(String ws) { int n = 0; for (char c : ws.toCharArray()) n += c == '\t' ? 4 : 1; return Math.min(3, n / 2); }

    private static void flush(StringBuilder para, List<Block> out) {
        if (para.length() == 0) return;
        out.add(new Block(Kind.PARAGRAPH, para.toString(), 0, null));
        para.setLength(0);
    }

    /** Parse inline markup into plain text plus style spans (offsets into the plain text). */
    public static Inline inline(String src) {
        StringBuilder out = new StringBuilder();
        List<Span> spans = new ArrayList<>();
        if (src == null) return new Inline("", spans);
        int i = 0, n = src.length(), bracketScan = 0, closeText = -1, closeUrl = -1;
        while (i < n) {
            char c = src.charAt(i);
            if (c == '\\' && i + 1 < n && "\\`*_~[]()#!>-+.|".indexOf(src.charAt(i + 1)) >= 0) { out.append(src.charAt(i + 1)); i += 2; continue; }
            if (c == '`') {
                int close = src.indexOf('`', i + 1);
                if (close > i) { int s = out.length(); out.append(src, i + 1, close); spans.add(new Span(s, out.length(), Style.CODE, null)); i = close + 1; continue; }
            }
            if ((c == '*' || c == '_') && i + 1 < n && src.charAt(i + 1) == c) {
                String mark = "" + c + c;
                int close = src.indexOf(mark, i + 2);
                if (close > i + 2) {
                    Inline inner = inline(src.substring(i + 2, close));
                    int s = out.length(); append(out, spans, inner);
                    spans.add(new Span(s, out.length(), Style.BOLD, null)); i = close + 2; continue;
                }
            }
            if (c == '~' && i + 1 < n && src.charAt(i + 1) == '~') {
                int close = src.indexOf("~~", i + 2);
                if (close > i + 2) {
                    Inline inner = inline(src.substring(i + 2, close));
                    int s = out.length(); append(out, spans, inner);
                    spans.add(new Span(s, out.length(), Style.STRIKE, null)); i = close + 2; continue;
                }
            }
            if ((c == '*' || c == '_') && i + 1 < n && !Character.isWhitespace(src.charAt(i + 1))
                    && (c == '*' || i == 0 || !Character.isLetterOrDigit(src.charAt(i - 1)))) {
                int close = findSingle(src, c, i + 1);
                if (close > i + 1) {
                    Inline inner = inline(src.substring(i + 1, close));
                    int s = out.length(); append(out, spans, inner);
                    spans.add(new Span(s, out.length(), Style.ITALIC, null)); i = close + 1; continue;
                }
            }
            if (c == '[') {
                if (closeText <= i) {
                    closeText = bracketScan < n ? src.indexOf(']', Math.max(bracketScan, i + 1)) : -1;
                    bracketScan = closeText < 0 ? n : closeText + 1;
                    closeUrl = closeText >= 0 && closeText + 1 < n && src.charAt(closeText + 1) == '('
                            ? closingParen(src, closeText + 2) : -1;
                }
                if (closeText > i && closeText + 1 < n && src.charAt(closeText + 1) == '(') {
                    if (closeUrl > closeText) {
                        String url = src.substring(closeText + 2, closeUrl).trim();
                        int sp = url.indexOf(' '); if (sp > 0) url = url.substring(0, sp);
                        Inline inner = inline(src.substring(i + 1, closeText));
                        int s = out.length(); append(out, spans, inner);
                        spans.add(new Span(s, out.length(), Style.LINK, url)); i = closeUrl + 1; continue;
                    }
                }
            }
            if ((c == 'h' || c == 'H') && (src.startsWith("http://", i) || src.startsWith("https://", i)) && (i == 0 || !Character.isLetterOrDigit(src.charAt(i - 1)))) {
                int end = i;
                while (end < n && !Character.isWhitespace(src.charAt(end)) && src.charAt(end) != '<' && src.charAt(end) != '>') end++;
                while (end > i && ".,;:!?)'\"".indexOf(src.charAt(end - 1)) >= 0) end--;
                String url = src.substring(i, end);
                int s = out.length(); out.append(url); spans.add(new Span(s, out.length(), Style.LINK, url)); i = end; continue;
            }
            out.append(c); i++;
        }
        return new Inline(out.toString(), spans);
    }

    private static int findSingle(String src, char c, int from) {
        for (int j = from; j < src.length(); j++) {
            if (src.charAt(j) == c && (j + 1 >= src.length() || src.charAt(j + 1) != c) && !Character.isWhitespace(src.charAt(j - 1))) {
                if (c == '_' && j + 1 < src.length() && Character.isLetterOrDigit(src.charAt(j + 1))) continue;
                return j;
            }
            if (src.charAt(j) == '\n') return -1;
        }
        return -1;
    }

    private static int closingParen(String src, int from) {
        int depth = 1;
        for (int j = from; j < src.length(); j++) {
            char c = src.charAt(j);
            if (c == '\\' && j + 1 < src.length()) { j++; continue; }
            if (c == '(') depth++;
            else if (c == ')' && --depth == 0) return j;
        }
        return -1;
    }

    private static void append(StringBuilder out, List<Span> spans, Inline inner) {
        int base = out.length();
        out.append(inner.text);
        for (Span s : inner.spans) spans.add(new Span(base + s.start, base + s.end, s.style, s.url));
    }

    /** Split a Markdown table block into trimmed cells. */
    public static List<String[]> tableRows(String table) {
        List<String[]> rows = new ArrayList<>();
        for (String line : table.split("\n")) {
            String l = line.trim();
            if (l.startsWith("|")) l = l.substring(1);
            if (l.endsWith("|")) l = l.substring(0, l.length() - 1);
            List<String> cells = new ArrayList<>();
            int start = 0;
            boolean code = false;
            for (int k = 0; k < l.length(); k++) {
                char c = l.charAt(k);
                if (c == '\\' && k + 1 < l.length()) { k++; continue; }
                if (c == '`') code = !code;
                if (c == '|' && !code) { cells.add(l.substring(start, k).trim()); start = k + 1; }
            }
            cells.add(l.substring(start).trim());
            rows.add(cells.toArray(new String[0]));
        }
        return rows;
    }

    /** Readable plain text: for notifications, speech and sharing. Images and code fences are summarised. */
    public static String plain(String source) {
        StringBuilder b = new StringBuilder();
        for (Block block : parse(DeviceActions.stripForDisplay(source))) {
            if (b.length() > 0) b.append('\n');
            switch (block.kind) {
                case CODE: b.append(block.text); break;
                case IMAGE: b.append("[image").append(block.text.isEmpty() ? "" : ": " + block.text).append(']'); break;
                case RULE: break;
                case BULLET: b.append("• ").append(inline(block.text).text); break;
                case ORDERED: b.append(block.extra).append(". ").append(inline(block.text).text); break;
                case FILE: b.append("[file: ").append(block.text).append(']'); break;
                case TABLE: for (String[] row : tableRows(block.text)) b.append(String.join("  ", row)).append('\n'); break;
                default: b.append(inline(block.text).text);
            }
        }
        return b.toString().trim();
    }

    /** What text-to-speech should say: no code dumps, no URLs read out character by character. */
    public static String speakable(String source) {
        StringBuilder b = new StringBuilder();
        for (Block block : parse(DeviceActions.stripForDisplay(source))) {
            switch (block.kind) {
                case CODE: b.append(block.text.split("\n").length > 2 ? "I've included some code on screen. " : ""); break;
                case IMAGE: b.append("There's an image on screen. "); break;
                case FILE: b.append("There's a file on screen. "); break;
                case TABLE: b.append("There's a table on screen. "); break;
                case RULE: break;
                default: {
                    String t = inline(block.text).text.replaceAll("https?://\\S+", "a link");
                    b.append(t);
                    if (!t.endsWith(".") && !t.endsWith("!") && !t.endsWith("?") && !t.endsWith(":")) b.append('.');
                    b.append(' ');
                }
            }
        }
        return b.toString().trim();
    }
}
