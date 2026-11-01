package cloud.stormlabs.hermescompanion;

import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.Iterator;

/**
 * Terminal emulator behind the Terminal module (xterm-256color): a fixed grid of cells with colours and attributes, a
 * cursor, scroll regions, an alternate screen for full-screen programs (vim, htop, tmux, less), scrollback and the
 * replies programs ask for. No Android classes, so it is unit-tested directly; {@link TermView} draws it.
 */
final class TermScreen {
    static final int BOLD = 1, UNDERLINE = 2, INVERSE = 4, ITALIC = 8, DIM = 16;
    static final int DEFAULT = 0; // fg/bg 0 means "the theme's colour"; explicit colours carry alpha 0xff
    static final char TAIL = 0;
    static final int[] ANSI = {0xff2e3436, 0xffcc4444, 0xff4caf50, 0xffc4a000, 0xff5c8fd6, 0xff9a6bbf, 0xff3fb6b6, 0xffd3d7cf,
            0xff7a7f85, 0xffef5350, 0xff7bd88f, 0xffffd54f, 0xff82aaff, 0xffc792ea, 0xff6fd8d8, 0xffffffff};
    private static final int MAX_SCROLLBACK = 5000;
    private static final String GRAPHICS = "◆▒␉␌␍␊°±␤␋┘┐┌└┼⎺⎻─⎼⎽├┤┴┬│≤≥π≠£·"; // DEC special graphics 0x60..0x7e

    interface Out { void send(String s); }

    static final class Row {
        /** {@code ch[i] == TAIL} is the right half of a double-width character; {@code ext[i]}, when set, is the whole cell (emoji, combining marks). */
        final char[] ch; final int[] fg, bg; final byte[] at; String[] ext; boolean wrapped;
        Row(int cols) { ch = new char[cols]; fg = new int[cols]; bg = new int[cols]; at = new byte[cols]; Arrays.fill(ch, ' '); }
        void clear(int from, int to, int bgColor) {
            for (int i = Math.max(0, from); i < Math.min(to, ch.length); i++) { ch[i] = ' '; fg[i] = 0; bg[i] = bgColor; at[i] = 0; if (ext != null) ext[i] = null; }
        }
        String cell(int i) { return ext != null && ext[i] != null ? ext[i] : String.valueOf(ch[i]); }
        String text(int from, int to) {
            StringBuilder b = new StringBuilder();
            for (int i = Math.max(0, from); i < Math.min(to, ch.length); i++) if (ch[i] != TAIL) b.append(cell(i));
            return b.toString();
        }
        /** Overwriting half of a double-width character blanks the other half. */
        void split(int i) {
            if (ch[i] == TAIL && i > 0) { ch[i - 1] = ' '; if (ext != null) ext[i - 1] = null; }
            else if (i + 1 < ch.length && ch[i + 1] == TAIL) ch[i + 1] = ' ';
        }
        Row copy(int cols) {
            Row r = new Row(cols); int n = Math.min(cols, ch.length);
            System.arraycopy(ch, 0, r.ch, 0, n); System.arraycopy(fg, 0, r.fg, 0, n); System.arraycopy(bg, 0, r.bg, 0, n); System.arraycopy(at, 0, r.at, 0, n);
            if (ext != null) { r.ext = new String[cols]; System.arraycopy(ext, 0, r.ext, 0, n); }
            r.wrapped = wrapped; return r;
        }
        @Override public String toString() { int e = ch.length; while (e > 0 && ch[e - 1] == ' ') e--; return text(0, e); }
    }

    int rows, cols;
    private Row[] grid;
    private final ArrayDeque<Row> scrollback = new ArrayDeque<>();
    private Row[] savedGrid; private int savedX, savedY; // the main screen while the alternate one is up
    int x, y;
    private boolean pendingWrap;
    private int top, bottom; // scroll region, inclusive
    private int fg, bg, attr;
    private int saveX, saveY, saveFg, saveBg, saveAttr;
    private boolean[] tabs;
    private int state; // 0 text, 1 ESC, 2 CSI, 3 OSC, 4 OSC+ESC, 5 charset designator, 6 other one-char escape
    private char designator;
    private boolean graphics; // G0 is DEC special graphics
    private final StringBuilder arg = new StringBuilder();
    private Out out = s -> { };

    boolean appCursor, autowrap = true, cursorVisible = true, bracketedPaste, altScreen, originMode;
    /** Mouse reporting a program asked for: 0 off, 1000 clicks, 1002 clicks and drags, 1003 all motion; SGR coordinates (1006); focus events (1004). */
    int mouseMode;
    boolean mouseSgr, focusEvents;
    private int lastChar = ' ';
    /** What the host draws with, for programs that ask (OSC 10/11/4, ?996n, CSI 14/16 t). */
    int defaultFg = 0xffd7dde5, defaultBg = 0xff0e1116, cellPxW = 10, cellPxH = 20;
    boolean darkScheme = true;
    String title = "";

    TermScreen(int rows, int cols) {
        this.rows = Math.max(1, rows); this.cols = Math.max(2, cols);
        grid = blank(this.rows, this.cols); top = 0; bottom = this.rows - 1; resetTabs();
    }

    void onReply(Out o) { out = o == null ? s -> { } : o; }

    private static Row[] blank(int rows, int cols) { Row[] g = new Row[rows]; for (int i = 0; i < rows; i++) g[i] = new Row(cols); return g; }

    private void resetTabs() { tabs = new boolean[cols]; for (int i = 8; i < cols; i += 8) tabs[i] = true; }

    /* ---- reading the screen ---- */
    int scrollbackSize() { return altScreen ? 0 : scrollback.size(); }

    /** Row {@code i} where 0..rows-1 is the screen and negative numbers reach back into the scrollback (-1 is the newest). */
    Row row(int i) {
        if (i >= 0) return i < rows ? grid[i] : null;
        if (altScreen) return null;
        int skip = scrollback.size() + i; if (skip < 0) return null;
        Iterator<Row> it = scrollback.iterator(); Row r = null;
        for (int k = 0; k <= skip && it.hasNext(); k++) r = it.next();
        return r;
    }

    /** Everything (scrollback then screen) as text with trailing blank lines dropped. */
    String text() {
        StringBuilder b = new StringBuilder();
        if (!altScreen) for (Row r : scrollback) { b.append(r).append('\n'); }
        int last = rows - 1; while (last > 0 && grid[last].toString().isEmpty() && last > y) last--;
        for (int i = 0; i <= last; i++) { if (i > 0) b.append('\n'); b.append(grid[i]); }
        return b.toString();
    }

    String screenText() {
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < rows; i++) { if (i > 0) b.append('\n'); b.append(grid[i]); }
        return b.toString();
    }

    /* ---- size ---- */
    void resize(int newRows, int newCols) {
        newRows = Math.max(1, newRows); newCols = Math.max(2, newCols);
        if (newRows == rows && newCols == cols) return;
        Row[] g = blank(newRows, newCols);
        int drop = Math.max(0, rows - newRows);
        // keep the cursor's line on screen: lines pushed off the top become scrollback (main screen only)
        int shift = Math.min(drop, Math.max(0, y + 1 - newRows + (rows - 1 - y > 0 ? 0 : 0)));
        shift = Math.max(shift, Math.max(0, y - (newRows - 1)));
        for (int i = 0; i < shift; i++) if (!altScreen) pushScrollback(grid[i]);
        for (int i = shift; i < rows && i - shift < newRows; i++) g[i - shift] = grid[i].copy(newCols);
        y = Math.max(0, Math.min(newRows - 1, y - shift)); x = Math.min(x, newCols - 1);
        if (savedGrid != null) { Row[] s = blank(newRows, newCols); for (int i = 0; i < Math.min(newRows, savedGrid.length); i++) s[i] = savedGrid[i].copy(newCols); savedGrid = s; savedY = Math.min(savedY, newRows - 1); savedX = Math.min(savedX, newCols - 1); }
        rows = newRows; cols = newCols; grid = g; top = 0; bottom = rows - 1; pendingWrap = false; resetTabs();
    }

    private void pushScrollback(Row r) { scrollback.addLast(r.copy(cols)); while (scrollback.size() > MAX_SCROLLBACK) scrollback.removeFirst(); }

    /* ---- scrolling ---- */
    private void scrollUp(int n) {
        for (int k = 0; k < n; k++) {
            Row gone = grid[top];
            if (!altScreen && top == 0) pushScrollback(gone);
            System.arraycopy(grid, top + 1, grid, top, bottom - top);
            grid[bottom] = new Row(cols); grid[bottom].clear(0, cols, bg);
        }
    }

    private void scrollDown(int n) {
        for (int k = 0; k < n; k++) {
            System.arraycopy(grid, top, grid, top + 1, bottom - top);
            grid[top] = new Row(cols); grid[top].clear(0, cols, bg);
        }
    }

    private void lineFeed() {
        if (y == bottom) scrollUp(1); else if (y < rows - 1) y++;
    }

    /* ---- input ---- */
    void feed(String s) {
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (state) {
                case 0:
                    if (Character.isHighSurrogate(c) && i + 1 < s.length() && Character.isLowSurrogate(s.charAt(i + 1))) text(Character.toCodePoint(c, s.charAt(++i)));
                    else text(c);
                    break;
                case 1: escape(c); break;
                case 2:
                    if (c >= 0x40 && c <= 0x7e) { state = 0; csi(c, arg.toString()); }
                    else if (c == 27) state = 1;
                    else if (c >= 32) arg.append(c);
                    else text(c); // control characters still act inside a sequence
                    break;
                case 3: if (c == 7) { state = 0; osc(arg.toString()); } else if (c == 27) state = 4; else if (arg.length() < 512) arg.append(c); break;
                case 4: state = 0; osc(arg.toString()); if (c != '\\') escape(c); break;
                case 5: state = 0; if (designator == '(') graphics = c == '0'; break;
                default: state = 0;
            }
        }
    }

    private void osc(String s) {
        int semi = s.indexOf(';');
        if (semi > 0 && (s.startsWith("0;") || s.startsWith("2;"))) { title = s.substring(semi + 1); return; }
        if (semi < 0) return;
        String cmd = s.substring(0, semi), rest = s.substring(semi + 1);
        if (cmd.equals("10") || cmd.equals("11") || cmd.equals("12")) { if (rest.equals("?")) out.send("\u001b]" + cmd + ";" + rgb(cmd.equals("10") ? defaultFg : cmd.equals("11") ? defaultBg : defaultFg) + "\u001b\\"); return; }
        if (cmd.equals("4")) { // OSC 4 ; n ; ? [; n ; ?]...: palette colour queries
            String[] p = rest.split(";", -1);
            for (int i = 0; i + 1 < p.length; i += 2) {
                if (!p[i + 1].equals("?")) continue;
                try { int n = Integer.parseInt(p[i]); if (n >= 0 && n < 256) out.send("\u001b]4;" + n + ";" + rgb(xterm(n)) + "\u001b\\"); } catch (NumberFormatException ignored) { }
            }
        }
    }

    private static String rgb(int c) {
        return String.format(java.util.Locale.US, "rgb:%02x%02x/%02x%02x/%02x%02x", c >> 16 & 255, c >> 16 & 255, c >> 8 & 255, c >> 8 & 255, c & 255, c & 255);
    }

    private void escape(char c) {
        state = 0;
        switch (c) {
            case '[': state = 2; arg.setLength(0); break;
            case ']': state = 3; arg.setLength(0); break;
            case '(': case ')': case '*': case '+': state = 5; designator = c; break;
            case '#': state = 6; break;
            case '7': saveX = x; saveY = y; saveFg = fg; saveBg = bg; saveAttr = attr; break;
            case '8': x = Math.min(saveX, cols - 1); y = Math.min(saveY, rows - 1); fg = saveFg; bg = saveBg; attr = saveAttr; pendingWrap = false; break;
            case 'D': lineFeed(); break;
            case 'E': x = 0; lineFeed(); break;
            case 'M': if (y == top) scrollDown(1); else if (y > 0) y--; break;
            case 'H': tabs[Math.min(x, cols - 1)] = true; break;
            case 'c': reset(); break;
            case '=': case '>': break;
            default:
        }
    }

    private void reset() {
        grid = blank(rows, cols); scrollback.clear(); savedGrid = null; altScreen = false; x = y = 0; top = 0; bottom = rows - 1;
        fg = bg = attr = 0; graphics = false; appCursor = false; autowrap = true; cursorVisible = true; bracketedPaste = false; pendingWrap = false; resetTabs();
        mouseMode = 0; mouseSgr = false; focusEvents = false;
    }

    private void text(int c) {
        switch (c) {
            case 27: state = 1; return;
            case '\n': case 11: case 12: lineFeed(); pendingWrap = false; return;
            case '\r': x = 0; pendingWrap = false; return;
            case '\b': if (x > 0) x--; pendingWrap = false; return;
            case '\t': { int n = x + 1; while (n < cols - 1 && !tabs[n]) n++; x = Math.min(cols - 1, n); pendingWrap = false; return; }
            default: if (c < 32 || c == 127) return;
        }
        if (graphics && c >= 0x60 && c <= 0x7e) c = GRAPHICS.charAt(c - 0x60);
        int w = width(c);
        if (w == 0) { join(c); return; }
        if (joinsPrevious()) { join(c); return; }
        if (pendingWrap || (w == 2 && x == cols - 1)) {
            if (autowrap) { if (w == 2 && x == cols - 1 && !pendingWrap) grid[y].clear(x, x + 1, bg); grid[y].wrapped = true; x = 0; lineFeed(); }
            pendingWrap = false;
        }
        lastChar = c;
        Row r = grid[y];
        r.split(x); if (w == 2 && x + 1 < cols) r.split(x + 1);
        put(r, x, c, w == 2 && x + 1 < cols ? 2 : 1);
        x += w == 2 && x + 1 < cols ? 2 : 1;
        if (x >= cols) { x = cols - 1; pendingWrap = true; }
    }

    private void put(Row r, int at, int cp, int w) {
        char[] u = Character.toChars(cp);
        r.ch[at] = u[0]; r.fg[at] = fg; r.bg[at] = bg; r.at[at] = (byte) attr;
        if (u.length > 1) { if (r.ext == null) r.ext = new String[cols]; r.ext[at] = new String(u); } else if (r.ext != null) r.ext[at] = null;
        if (w == 2) { r.ch[at + 1] = TAIL; r.fg[at + 1] = fg; r.bg[at + 1] = bg; r.at[at + 1] = (byte) attr; if (r.ext != null) r.ext[at + 1] = null; }
    }

    /** Index of the cell holding the character just written (the head of a double-width one), or -1. */
    private int previousCell() {
        int i = pendingWrap ? x : x - 1;
        if (i >= 0 && grid[y].ch[i] == TAIL) i--;
        return i;
    }

    /** After a zero-width joiner the next character belongs to the same cell (emoji sequences). */
    private boolean joinsPrevious() {
        int i = previousCell();
        if (i < 0) return false;
        String c = grid[y].cell(i);
        return c.length() > 1 && c.charAt(c.length() - 1) == 0x200D;
    }

    /** Appends a combining mark, variation selector or joined character to the cell before the cursor. */
    private void join(int cp) {
        int i = previousCell();
        if (i < 0) return;
        Row r = grid[y];
        String cell = r.cell(i);
        if (cell.length() > 12) return;
        if (r.ext == null) r.ext = new String[cols];
        r.ext[i] = cell + new String(Character.toChars(cp));
    }

    /** Terminal cell width of a code point: 0 for combining marks and joiners, 2 for East Asian wide characters and emoji, else 1. */
    static int width(int c) {
        if (c < 0x300) return 1;
        if (c == 0x200D || c == 0xFE0F || c == 0xFE0E || (c >= 0x200B && c <= 0x200F) || c == 0x2060 || c == 0xFEFF || (c >= 0x1F3FB && c <= 0x1F3FF) || (c >= 0xE0020 && c <= 0xE007F)) return 0;
        int t = Character.getType(c);
        if (t == Character.NON_SPACING_MARK || t == Character.ENCLOSING_MARK || t == Character.COMBINING_SPACING_MARK) return 0;
        if ((c >= 0x1100 && c <= 0x115F) || (c >= 0x2E80 && c <= 0x303E) || (c >= 0x3041 && c <= 0x33FF) || (c >= 0x3400 && c <= 0x4DBF) || (c >= 0x4E00 && c <= 0x9FFF)
                || (c >= 0xA000 && c <= 0xA4CF) || (c >= 0xAC00 && c <= 0xD7A3) || (c >= 0xF900 && c <= 0xFAFF) || (c >= 0xFE30 && c <= 0xFE6F) || (c >= 0xFF00 && c <= 0xFF60)
                || (c >= 0xFFE0 && c <= 0xFFE6) || (c >= 0x1F300 && c <= 0x1F64F) || (c >= 0x1F680 && c <= 0x1F6FF) || (c >= 0x1F900 && c <= 0x1F9FF) || (c >= 0x1FA70 && c <= 0x1FAFF)
                || (c >= 0x20000 && c <= 0x3FFFD) || c == 0x1F004 || c == 0x1F0CF || c == 0x1F18E || (c >= 0x1F191 && c <= 0x1F19A) || (c >= 0x1F200 && c <= 0x1F2FF)) return 2;
        switch (c) { // emoji that sit in the BMP
            case 0x231A: case 0x231B: case 0x23E9: case 0x23EA: case 0x23EB: case 0x23EC: case 0x23F0: case 0x23F3: case 0x25FD: case 0x25FE: case 0x2614: case 0x2615:
            case 0x267F: case 0x2693: case 0x26A1: case 0x26AA: case 0x26AB: case 0x26BD: case 0x26BE: case 0x26C4: case 0x26C5: case 0x26CE: case 0x26D4: case 0x26EA:
            case 0x26F2: case 0x26F3: case 0x26F5: case 0x26FA: case 0x26FD: case 0x2705: case 0x270A: case 0x270B: case 0x2728: case 0x274C: case 0x274E: case 0x2753:
            case 0x2754: case 0x2755: case 0x2757: case 0x2795: case 0x2796: case 0x2797: case 0x27B0: case 0x27BF: case 0x2B1B: case 0x2B1C: case 0x2B50: case 0x2B55: return 2;
            default: if (c >= 0x2648 && c <= 0x2653) return 2;
        }
        return 1;
    }

    private static int num(String[] p, int i, int def) {
        if (i >= p.length || p[i].isEmpty()) return def;
        try { return Integer.parseInt(p[i]); } catch (NumberFormatException e) { return def; }
    }

    private void moveTo(int row, int col) {
        int base = originMode ? top : 0, limit = originMode ? bottom : rows - 1;
        y = Math.max(base, Math.min(limit, base + row)); x = Math.max(0, Math.min(cols - 1, col)); pendingWrap = false;
    }

    private void csi(char fin, String raw) {
        char lead = raw.isEmpty() ? 0 : raw.charAt(0);
        boolean priv = lead == '?', other = lead == '>' || lead == '=' || lead == '<';
        String[] p = (priv || other ? raw.substring(1) : raw).split(";", -1);
        int n = Math.max(1, num(p, 0, 1));
        switch (fin) {
            case 'A': y = Math.max(y < top ? 0 : top, y - n); pendingWrap = false; break;
            case 'B': y = Math.min(y > bottom ? rows - 1 : bottom, y + n); pendingWrap = false; break;
            case 'C': case 'a': x = Math.min(cols - 1, x + n); pendingWrap = false; break;
            case 'D': x = Math.max(0, x - n); pendingWrap = false; break;
            case 'E': y = Math.min(bottom, y + n); x = 0; pendingWrap = false; break;
            case 'F': y = Math.max(top, y - n); x = 0; pendingWrap = false; break;
            case 'G': case '`': x = Math.max(0, Math.min(cols - 1, num(p, 0, 1) - 1)); pendingWrap = false; break;
            case 'd': moveTo(num(p, 0, 1) - 1, x); break;
            case 'H': case 'f': moveTo(num(p, 0, 1) - 1, num(p, 1, 1) - 1); break;
            case 'J': {
                int m = num(p, 0, 0);
                if (m == 0) { grid[y].clear(x, cols, bg); for (int r = y + 1; r < rows; r++) grid[r].clear(0, cols, bg); }
                else if (m == 1) { grid[y].clear(0, x + 1, bg); for (int r = 0; r < y; r++) grid[r].clear(0, cols, bg); }
                else { for (int r = 0; r < rows; r++) grid[r].clear(0, cols, bg); if (m == 3) scrollback.clear(); }
                break;
            }
            case 'K': { int m = num(p, 0, 0); if (m == 0) grid[y].clear(x, cols, bg); else if (m == 1) grid[y].clear(0, x + 1, bg); else grid[y].clear(0, cols, bg); break; }
            case 'L': if (y >= top && y <= bottom) { int sb = bottom; int st = top; top = y; scrollDown(Math.min(n, bottom - y + 1)); top = st; bottom = sb; } x = 0; pendingWrap = false; break;
            case 'M': if (y >= top && y <= bottom) { int st = top; top = y; boolean a = altScreen; altScreen = true; scrollUp(Math.min(n, bottom - y + 1)); altScreen = a; top = st; } x = 0; pendingWrap = false; break;
            case 'P': { Row r = grid[y]; int k = Math.min(n, cols - x); shift(r, x + k, x, cols - x - k); r.clear(cols - k, cols, bg); break; }
            case '@': { Row r = grid[y]; int k = Math.min(n, cols - x); shift(r, x, x + k, cols - x - k); r.clear(x, x + k, bg); break; }
            case 'X': grid[y].clear(x, x + n, bg); break;
            case 'b': for (int i = 0; i < Math.min(n, 4096); i++) text(lastChar); break;
            case 'I': for (int i = 0; i < n; i++) { int t = x + 1; while (t < cols - 1 && !tabs[t]) t++; x = Math.min(cols - 1, t); } pendingWrap = false; break;
            case 'Z': for (int i = 0; i < n; i++) { int t = x - 1; while (t > 0 && !tabs[t]) t--; x = Math.max(0, t); } pendingWrap = false; break;
            case 'S': { boolean a = altScreen; altScreen = true; scrollUp(n); altScreen = a; break; }
            case 'T': scrollDown(n); break;
            case 'r': {
                int t = num(p, 0, 1) - 1, b = num(p, 1, rows) - 1;
                if (t >= 0 && b < rows && t < b) { top = t; bottom = b; } else { top = 0; bottom = rows - 1; }
                moveTo(0, 0); break;
            }
            case 'm': if (!other) sgr(p); break;
            case 's': if (!priv && !other) { saveX = x; saveY = y; } break;
            case 'u':
                if (priv) out.send("\u001b[?0u"); // kitty keyboard protocol: none of its flags are on
                else if (!other) { x = Math.min(saveX, cols - 1); y = Math.min(saveY, rows - 1); pendingWrap = false; }
                break;
            case 't': {
                int op = num(p, 0, 0);
                if (op == 14) out.send("\u001b[4;" + rows * cellPxH + ";" + cols * cellPxW + "t");
                else if (op == 16) out.send("\u001b[6;" + cellPxH + ";" + cellPxW + "t");
                else if (op == 18) out.send("\u001b[8;" + rows + ";" + cols + "t");
                break;
            }
            case 'p': if (priv && raw.endsWith("$")) { int m = num(new String[]{p[0].replace("$", "")}, 0, 0); out.send("\u001b[?" + m + ";" + modeState(m) + "$y"); } break;
            case 'g': if (num(p, 0, 0) == 3) Arrays.fill(tabs, false); else tabs[Math.min(x, cols - 1)] = false; break;
            case 'n': if (priv && num(p, 0, 0) == 996) out.send("\u001b[?997;" + (darkScheme ? 1 : 2) + "n"); else if (!priv && num(p, 0, 0) == 6) out.send("\u001b[" + (y + 1 - (originMode ? top : 0)) + ";" + (x + 1) + "R"); else if (!priv && num(p, 0, 0) == 5) out.send("\u001b[0n"); break;
            case 'c': if (lead == '>') out.send("\u001b[>0;276;0c"); else if (!priv) out.send("\u001b[?62;c"); break;
            case 'h': case 'l': if (priv) for (String m : p) mode(num(new String[]{m}, 0, 0), fin == 'h'); break;
            default:
        }
    }

    private static void shift(Row r, int from, int to, int count) {
        if (count <= 0) return;
        System.arraycopy(r.ch, from, r.ch, to, count); System.arraycopy(r.fg, from, r.fg, to, count);
        System.arraycopy(r.bg, from, r.bg, to, count); System.arraycopy(r.at, from, r.at, to, count);
        if (r.ext != null) System.arraycopy(r.ext, from, r.ext, to, count);
    }

    /** DECRQM answer: 1 set, 2 reset, 0 not recognised. */
    private int modeState(int m) {
        switch (m) {
            case 1: return appCursor ? 1 : 2;
            case 7: return autowrap ? 1 : 2;
            case 25: return cursorVisible ? 1 : 2;
            case 1000: case 1002: case 1003: return mouseMode == m ? 1 : 2;
            case 1004: return focusEvents ? 1 : 2;
            case 1006: return mouseSgr ? 1 : 2;
            case 1049: case 47: case 1047: return altScreen ? 1 : 2;
            case 2004: return bracketedPaste ? 1 : 2;
            case 2026: return 2;
            default: return 0;
        }
    }

    private void mode(int m, boolean on) {
        switch (m) {
            case 1: appCursor = on; break;
            case 6: originMode = on; moveTo(0, 0); break;
            case 7: autowrap = on; break;
            case 25: cursorVisible = on; break;
            case 47: case 1047: case 1049: alt(on, m == 1049); break;
            case 2004: bracketedPaste = on; break;
            case 1000: case 1002: case 1003: if (on) mouseMode = m; else if (mouseMode == m) mouseMode = 0; break;
            case 1006: mouseSgr = on; break;
            case 1004: focusEvents = on; break;
            default:
        }
    }

    private void alt(boolean on, boolean saveCursor) {
        if (on && !altScreen) {
            savedGrid = grid; savedX = x; savedY = y; grid = blank(rows, cols); altScreen = true;
            if (saveCursor) { x = 0; y = 0; }
            top = 0; bottom = rows - 1;
        } else if (!on && altScreen) {
            grid = savedGrid; savedGrid = null; altScreen = false;
            if (saveCursor) { x = savedX; y = savedY; }
            top = 0; bottom = rows - 1;
        }
        pendingWrap = false;
    }

    private void sgr(String[] p) {
        for (int i = 0; i < p.length; i++) {
            int v = num(p, i, 0);
            if (v == 0) { fg = bg = attr = 0; }
            else if (v == 1) attr |= BOLD; else if (v == 2) attr |= DIM; else if (v == 3) attr |= ITALIC; else if (v == 4) attr |= UNDERLINE; else if (v == 7) attr |= INVERSE;
            else if (v == 22) attr &= ~(BOLD | DIM); else if (v == 23) attr &= ~ITALIC; else if (v == 24) attr &= ~UNDERLINE; else if (v == 27) attr &= ~INVERSE;
            else if (v >= 30 && v <= 37) fg = ANSI[v - 30]; else if (v == 39) fg = 0;
            else if (v >= 40 && v <= 47) bg = ANSI[v - 40]; else if (v == 49) bg = 0;
            else if (v >= 90 && v <= 97) fg = ANSI[v - 90 + 8]; else if (v >= 100 && v <= 107) bg = ANSI[v - 100 + 8];
            else if (v == 38 || v == 48) {
                int kind = num(p, i + 1, 0), color;
                if (kind == 5) { color = xterm(num(p, i + 2, 7)); i += 2; }
                else if (kind == 2) { color = 0xff000000 | (num(p, i + 2, 0) & 255) << 16 | (num(p, i + 3, 0) & 255) << 8 | (num(p, i + 4, 0) & 255); i += 4; }
                else continue;
                if (v == 38) fg = color; else bg = color;
            }
        }
    }

    static int xterm(int n) {
        if (n < 16) return ANSI[Math.max(0, n)];
        if (n >= 232) { int g = 8 + (Math.min(n, 255) - 232) * 10; return 0xff000000 | g << 16 | g << 8 | g; }
        n -= 16; int[] lv = {0, 95, 135, 175, 215, 255};
        return 0xff000000 | lv[(n / 36) % 6] << 16 | lv[(n / 6) % 6] << 8 | lv[n % 6];
    }
}
