package cloud.stormlabs.hermescompanion;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Rect;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.text.InputType;
import android.view.GestureDetector;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.ScaleGestureDetector;
import android.view.View;
import android.view.inputmethod.BaseInputConnection;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputConnection;
import android.view.inputmethod.InputMethodManager;

/** Draws a {@link TermScreen}: cell grid, colours, cursor, scrollback by dragging, pinch to zoom, long-press to select and copy, soft and hardware keys. */
final class TermView extends View {
    interface Host {
        void input(String s);                 // bytes for the session
        void resized(int rows, int cols);
        void zoomed(float sp);
        void copied(String text);
        boolean ctrl();                       // sticky Ctrl/Alt from the extra-keys row; each consume() clears the one-shot
        boolean alt();
        void consume();
    }

    static final class Theme {
        final String name; final int bg, fg, cursor, select;
        Theme(String name, int bg, int fg, int cursor, int select) { this.name = name; this.bg = bg; this.fg = fg; this.cursor = cursor; this.select = select; }
    }
    static final Theme[] THEMES = {
            new Theme("Hermes dark", 0xff0e1116, 0xffd7dde5, 0xff5c8fd6, 0x665c8fd6),
            new Theme("Black", 0xff000000, 0xffc8c8c8, 0xffffffff, 0x66888888),
            new Theme("Solarized dark", 0xff002b36, 0xff93a1a1, 0xff93a1a1, 0x66268bd2),
            new Theme("Dracula", 0xff282a36, 0xfff8f8f2, 0xfff8f8f2, 0x66bd93f9),
            new Theme("Light", 0xfffafafa, 0xff24292e, 0xff0366d6, 0x550366d6),
    };

    private TermScreen screen;
    private Host host;
    private Theme theme = THEMES[0];
    private final Paint text = new Paint(Paint.ANTI_ALIAS_FLAG), fill = new Paint();
    private float sizeSp = 13f, cellW, cellH, baseline;
    private int scroll;                      // lines back from the live screen
    private float dragRemainder;
    private int selA = -1, selB = -1;        // selection endpoints as (line * 10000 + col) in the scroll-independent line numbering
    private boolean selecting;
    private final GestureDetector taps;
    private final ScaleGestureDetector pinch;
    private boolean blinkOn = true;
    /** The "Select" key: take touches for text selection even while a program has the mouse. */
    boolean localSelect;
    /** The "Mouse" key: switched off, touches never go to the program as mouse events. */
    boolean mouseOff;
    private boolean mouseDrag;
    private int lastCol, lastRow;
    private final Runnable blink = new Runnable() { @Override public void run() { blinkOn = !blinkOn; invalidate(); postDelayed(this, 550); } };

    TermView(Context c) {
        super(c);
        setFocusable(true); setFocusableInTouchMode(true);
        text.setTypeface(Typeface.MONOSPACE);
        taps = new GestureDetector(c, new GestureDetector.SimpleOnGestureListener() {
            @Override public boolean onDown(MotionEvent e) { return true; }
            @Override public boolean onSingleTapUp(MotionEvent e) {
                if (mouseActive()) { requestFocus(); click(e); showKeyboard(); return true; } // like Termius: a tap also brings up the keyboard
                clearSelection(); requestFocus(); showKeyboard(); return true;
            }
            @Override public void onLongPress(MotionEvent e) {
                if (mouseActive()) { mouseDrag = true; performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS); report(TermKeys.LEFT, e, false, false); return; }
                beginSelection(e);
            }
            @Override public boolean onScroll(MotionEvent a, MotionEvent b, float dx, float dy) {
                if (selecting || mouseDrag) return true;
                if (pinch.isInProgress()) return true;
                dragRemainder += dy; int lines = (int) (dragRemainder / cellH); dragRemainder -= lines * cellH;
                if (lines == 0) return true;
                if (mouseActive()) { // swipe = mouse wheel (finger up scrolls the content down, like a phone list)
                    for (int i = 0; i < Math.min(Math.abs(lines), 6); i++) report(lines > 0 ? TermKeys.WHEEL_DOWN : TermKeys.WHEEL_UP, b, false, false);
                    return true;
                }
                scrollBy(-lines);
                return true;
            }
        });
        pinch = new ScaleGestureDetector(c, new ScaleGestureDetector.SimpleOnScaleGestureListener() {
            @Override public boolean onScale(ScaleGestureDetector d) { setTextSize(sizeSp * d.getScaleFactor()); if (host != null) host.zoomed(sizeSp); return true; }
        });
    }

    void bind(TermScreen s, Host h) { screen = s; host = h; scroll = 0; clearSelection(); sync(); measure(); invalidate(); }

    /** Tells the emulator what it is drawn with, for programs that ask (colours, cell size). */
    private void sync() { if (screen == null) return; synchronized (screen) { screen.defaultFg = theme.fg; screen.defaultBg = theme.bg; screen.darkScheme = (theme.bg >> 16 & 255) + (theme.bg >> 8 & 255) + (theme.bg & 255) < 384; if (cellW > 0) { screen.cellPxW = Math.round(cellW); screen.cellPxH = Math.round(cellH); } } }
    TermScreen screen() { return screen; }
    void theme(Theme t) { theme = t; setBackgroundColor(t.bg); sync(); invalidate(); }

    void setTextSize(float sp) {
        sizeSp = Math.max(6f, Math.min(30f, sp));
        text.setTextSize(sizeSp * getResources().getDisplayMetrics().scaledDensity);
        cellW = text.measureText("M"); Paint.FontMetrics fm = text.getFontMetrics();
        cellH = (float) Math.ceil(fm.descent - fm.ascent); baseline = -fm.ascent;
        sync(); measure(); invalidate();
    }

    float textSize() { return sizeSp; }

    @Override protected void onAttachedToWindow() { super.onAttachedToWindow(); postDelayed(blink, 550); if (cellW == 0) setTextSize(sizeSp); }
    @Override protected void onDetachedFromWindow() { removeCallbacks(blink); super.onDetachedFromWindow(); }
    @Override protected void onSizeChanged(int w, int h, int ow, int oh) { super.onSizeChanged(w, h, ow, oh); measure(); }

    private void measure() {
        if (screen == null || cellW <= 0 || getWidth() <= 0) return;
        int cols = Math.max(2, (int) (getWidth() / cellW)), rows = Math.max(1, (int) (getHeight() / cellH));
        boolean changed;
        synchronized (screen) { changed = rows != screen.rows || cols != screen.cols; if (changed) screen.resize(rows, cols); }
        if (changed && host != null) host.resized(rows, cols);
    }

    /** New output arrived: stay at the bottom unless the user scrolled back. */
    void changed() { if (screen == null) return; if (scroll > screen.scrollbackSize()) scroll = screen.scrollbackSize(); postInvalidateOnAnimation(); }

    void scrollBy(int lines) {
        if (screen == null) return;
        if (screen.altScreen) { // full-screen programs have no scrollback: the wheel becomes arrow keys
            if (host != null) for (int i = 0; i < Math.abs(lines); i++) host.input(TermKeys.special(lines > 0 ? KeyEvent.KEYCODE_DPAD_UP : KeyEvent.KEYCODE_DPAD_DOWN, false, false, false, screen.appCursor));
            return;
        }
        scroll = Math.max(0, Math.min(screen.scrollbackSize(), scroll + lines)); invalidate();
    }

    boolean atBottom() { return scroll == 0; }
    void toBottom() { scroll = 0; invalidate(); }

    @Override protected void onDraw(Canvas c) {
        c.drawColor(theme.bg);
        if (screen == null || cellW <= 0) return;
        synchronized (screen) { drawGrid(c); }
    }

    private void drawGrid(Canvas c) {
        for (int r = 0; r < screen.rows; r++) {
            TermScreen.Row row = screen.row(r - scroll);
            if (row == null) continue;
            float top = r * cellH;
            int runStart = 0;
            for (int i = 0; i <= row.ch.length; i++) {
                boolean end = i == row.ch.length;
                if (!end && i > runStart && row.fg[i] == row.fg[runStart] && row.bg[i] == row.bg[runStart] && row.at[i] == row.at[runStart] && !selectionEdge(r, i)) continue;
                if (i > runStart) drawRun(c, row, runStart, i, top, r);
                runStart = i;
            }
        }
        if (scroll == 0 && screen.cursorVisible && blinkOn) {
            int cx = Math.min(screen.x, screen.cols - 1);
            fill.setColor(theme.cursor); fill.setAlpha(hasFocus() ? 255 : 110);
            c.drawRect(cx * cellW, screen.y * cellH, (cx + 1) * cellW, (screen.y + 1) * cellH, fill);
            TermScreen.Row row = screen.row(screen.y);
            if (row != null && row.ch[cx] != ' ' && row.ch[cx] != TermScreen.TAIL) { text.setColor(theme.bg); text.setFakeBoldText(false); c.drawText(row.cell(cx), cx * cellW, screen.y * cellH + baseline, text); }
        }
        if (scroll > 0) { // where we are in the history
            fill.setColor(theme.cursor); fill.setAlpha(150);
            float frac = 1f - (float) scroll / Math.max(1, screen.scrollbackSize() + screen.rows);
            c.drawRect(getWidth() - 4, frac * getHeight() - 24, getWidth(), frac * getHeight() + 24, fill);
        }
    }

    private void drawRun(Canvas c, TermScreen.Row row, int from, int to, float top, int screenRow) {
        int fg = row.fg[from], bg = row.bg[from], at = row.at[from];
        int fgc = fg == 0 ? theme.fg : fg, bgc = bg == 0 ? 0 : bg;
        if ((at & TermScreen.INVERSE) != 0) { int t = fgc; fgc = bgc == 0 ? theme.bg : bgc; bgc = t; }
        if ((at & TermScreen.BOLD) != 0 && fg != 0) { /* bold keeps its colour; weight shows it */ }
        if (bgc != 0) { fill.setColor(bgc); fill.setAlpha(255); c.drawRect(from * cellW, top, to * cellW, top + cellH, fill); }
        if (selected(screenRow, from)) { fill.setColor(theme.select); c.drawRect(from * cellW, top, to * cellW, top + cellH, fill); }
        boolean blank = true; for (int i = from; i < to; i++) if (row.ch[i] != ' ') { blank = false; break; }
        if (!blank) {
            text.setColor(fgc); if ((at & TermScreen.DIM) != 0) text.setAlpha(150);
            text.setFakeBoldText((at & TermScreen.BOLD) != 0); text.setTextSkewX((at & TermScreen.ITALIC) != 0 ? -0.2f : 0);
            // draw per cell so odd-width glyphs stay on the grid
            for (int i = from; i < to; i++) if (row.ch[i] != ' ' && row.ch[i] != TermScreen.TAIL) { if (row.ext != null && row.ext[i] != null) c.drawText(row.ext[i], i * cellW, top + baseline, text); else c.drawText(row.ch, i, 1, i * cellW, top + baseline, text); }
            text.setTextSkewX(0);
        }
        if ((at & TermScreen.UNDERLINE) != 0) { text.setColor(fgc); c.drawRect(from * cellW, top + cellH - 2, to * cellW, top + cellH - 1, text); }
    }

    /* ---- mouse reporting ---- */
    /** A program has asked for the mouse and the user has not turned it off (Mouse key) or taken touches for selecting (Select key). */
    boolean mouseActive() { return screen != null && screen.mouseMode != 0 && !mouseOff && !localSelect && host != null; }

    private void report(int button, MotionEvent e, boolean release, boolean motion) {
        int col = Math.max(1, Math.min(screen.cols, (int) (e.getX() / cellW) + 1)), row = Math.max(1, Math.min(screen.rows, (int) (e.getY() / cellH) + 1));
        lastCol = col; lastRow = row;
        String s = TermKeys.mouse(button, col, row, release, motion, screen.mouseSgr);
        if (s != null) host.input(s);
    }

    private void click(MotionEvent e) { report(TermKeys.LEFT, e, false, false); report(TermKeys.LEFT, e, true, false); }

    @Override protected void onFocusChanged(boolean gained, int direction, Rect previouslyFocused) {
        super.onFocusChanged(gained, direction, previouslyFocused);
        if (screen != null && host != null && screen.focusEvents) host.input(TermKeys.ESC + (gained ? "[I" : "[O"));
    }

    /* ---- selection ---- */
    private int cellAt(MotionEvent e) {
        int col = Math.max(0, Math.min(screen.cols - 1, (int) (e.getX() / cellW)));
        int r = Math.max(0, Math.min(screen.rows - 1, (int) (e.getY() / cellH)));
        return (r - scroll) * 10000 + col;   // negative lines are scrollback; stable while the view scrolls
    }

    private void beginSelection(MotionEvent e) {
        if (screen == null) return;
        selecting = true; selA = selB = cellAt(e); performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS);
        // select the word under the finger
        int line = Math.floorDiv(selA, 10000), col = Math.floorMod(selA, 10000);
        TermScreen.Row row = screen.row(line);
        if (row != null && row.ch[col] != ' ') { int a = col, b = col; while (a > 0 && row.ch[a - 1] != ' ') a--; while (b < row.ch.length - 1 && row.ch[b + 1] != ' ') b++; selA = line * 10000 + a; selB = line * 10000 + b; }
        invalidate();
    }

    private void extendSelection(MotionEvent e) { selB = cellAt(e); invalidate(); }

    private boolean selectionEdge(int screenRow, int col) {
        if (selA < 0) return false;
        int line = screenRow - scroll, lo = Math.min(selA, selB), hi = Math.max(selA, selB);
        int p = line * 10000 + col;
        return p == lo || p == hi + 1 || line == Math.floorDiv(lo, 10000) && col == 0 || line == Math.floorDiv(hi, 10000) && col == 0;
    }

    private boolean selected(int screenRow, int col) {
        if (selA < 0) return false;
        int lo = Math.min(selA, selB), hi = Math.max(selA, selB), p = (screenRow - scroll) * 10000 + col;
        return p >= lo && p <= hi;
    }

    void clearSelection() { if (selA >= 0) { selA = selB = -1; invalidate(); } selecting = false; }

    private String selectedText() {
        if (selA < 0) return "";
        int lo = Math.min(selA, selB), hi = Math.max(selA, selB);
        StringBuilder b = new StringBuilder();
        for (int line = Math.floorDiv(lo, 10000); line <= Math.floorDiv(hi, 10000); line++) {
            TermScreen.Row row = screen.row(line); if (row == null) continue;
            int from = line == Math.floorDiv(lo, 10000) ? Math.floorMod(lo, 10000) : 0, to = line == Math.floorDiv(hi, 10000) ? Math.floorMod(hi, 10000) : row.ch.length - 1;
            String s = row.text(from, Math.min(to + 1, row.ch.length));
            if (!row.wrapped || line == Math.floorDiv(hi, 10000)) s = s.replaceAll("\\s+$", "");
            b.append(s); if (!row.wrapped && line < Math.floorDiv(hi, 10000)) b.append('\n');
        }
        return b.toString();
    }

    String allText() { return screen == null ? "" : screen.text(); }

    @Override public boolean onTouchEvent(MotionEvent e) {
        if (screen == null) return false;
        pinch.onTouchEvent(e);
        boolean handled = taps.onTouchEvent(e);
        if (e.getActionMasked() == MotionEvent.ACTION_MOVE) { // the gesture detector goes quiet after a long press: drags are ours
            if (selecting) extendSelection(e);
            else if (mouseDrag && screen.mouseMode >= 1002) {
                int col = (int) (e.getX() / cellW) + 1, row = (int) (e.getY() / cellH) + 1;
                if (col != lastCol || row != lastRow) report(TermKeys.LEFT, e, false, true);
            }
        }
        if (mouseDrag && (e.getActionMasked() == MotionEvent.ACTION_UP || e.getActionMasked() == MotionEvent.ACTION_CANCEL)) { report(TermKeys.LEFT, e, true, false); mouseDrag = false; }
        if (e.getActionMasked() == MotionEvent.ACTION_UP || e.getActionMasked() == MotionEvent.ACTION_CANCEL) {
            if (selecting && selA >= 0 && host != null) { String t = selectedText(); if (!t.isEmpty()) host.copied(t); }
            selecting = false; dragRemainder = 0;
        }
        return handled || super.onTouchEvent(e);
    }

    /* ---- keyboard ---- */
    void showKeyboard() {
        InputMethodManager m = (InputMethodManager) getContext().getSystemService(Context.INPUT_METHOD_SERVICE);
        if (m != null) m.showSoftInput(this, 0);
    }

    @Override public boolean onCheckIsTextEditor() { return true; }

    @Override public InputConnection onCreateInputConnection(EditorInfo out) {
        out.inputType = InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS;
        out.imeOptions = EditorInfo.IME_FLAG_NO_FULLSCREEN | EditorInfo.IME_FLAG_NO_EXTRACT_UI | EditorInfo.IME_ACTION_NONE;
        return new BaseInputConnection(this, false) {
            private String composing = "";
            private void send(String s) { if (host == null || s.isEmpty()) return; host.input(TermKeys.text(s, host.alt(), host.ctrl())); host.consume(); toBottom(); }
            @Override public boolean commitText(CharSequence t, int pos) {
                if (!composing.isEmpty()) { backspaces(composing.length()); composing = ""; }
                send(t.toString().replace("\n", "\r")); return true;
            }
            @Override public boolean setComposingText(CharSequence t, int pos) {
                String n = t.toString(); int common = 0;
                while (common < Math.min(n.length(), composing.length()) && n.charAt(common) == composing.charAt(common)) common++;
                backspaces(composing.length() - common); send(n.substring(common)); composing = n; return true;
            }
            @Override public boolean finishComposingText() { composing = ""; return true; }
            @Override public boolean deleteSurroundingText(int before, int after) { backspaces(Math.max(1, before)); if (composing.length() >= before) composing = composing.substring(0, composing.length() - before); else composing = ""; return true; }
            private void backspaces(int n) { if (host == null) return; StringBuilder b = new StringBuilder(); for (int i = 0; i < n; i++) b.append('\u007f'); if (b.length() > 0) host.input(b.toString()); }
            @Override public boolean sendKeyEvent(KeyEvent e) { if (e.getAction() == KeyEvent.ACTION_DOWN) return TermView.this.onKeyDown(e.getKeyCode(), e); return true; }
        };
    }

    @Override public boolean onKeyDown(int code, KeyEvent e) {
        if (host == null || screen == null) return false;
        boolean ctrl = e.isCtrlPressed() || host.ctrl(), alt = e.isAltPressed() || host.alt();
        String s = TermKeys.special(code, e.isShiftPressed(), alt, ctrl, screen.appCursor);
        if (s == null) {
            int u = e.getUnicodeChar(0);
            if (u == 0 || code == KeyEvent.KEYCODE_BACK || e.isMetaPressed()) return super.onKeyDown(code, e);
            s = TermKeys.text(String.valueOf((char) (e.isShiftPressed() ? e.getUnicodeChar(KeyEvent.META_SHIFT_ON) : u)), alt, ctrl);
        }
        host.input(s); host.consume(); toBottom();
        return true;
    }

    @Override public boolean onKeyPreIme(int code, KeyEvent e) { return super.onKeyPreIme(code, e); }
}
