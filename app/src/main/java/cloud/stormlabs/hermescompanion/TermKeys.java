package cloud.stormlabs.hermescompanion;

import android.view.KeyEvent;

/** What a terminal expects on the wire for a key: xterm sequences, with Ctrl/Alt/Shift folded in. */
final class TermKeys {
    private TermKeys() {}

    static final String ESC = "\u001b";

    /** xterm modifier parameter: 1 + shift(1) + alt(2) + ctrl(4). */
    private static int mod(boolean shift, boolean alt, boolean ctrl) { return 1 + (shift ? 1 : 0) + (alt ? 2 : 0) + (ctrl ? 4 : 0); }

    private static String csi(String letter, int mod, boolean app) {
        if (mod > 1) return ESC + "[1;" + mod + letter;
        return ESC + (app ? "O" : "[") + letter;
    }

    private static String tilde(int n, int mod) { return ESC + "[" + n + (mod > 1 ? ";" + mod : "") + "~"; }

    /** The bytes for a non-text key, or null when the key is not one a terminal has a sequence for. */
    static String special(int keyCode, boolean shift, boolean alt, boolean ctrl, boolean appCursor) {
        int m = mod(shift, alt, ctrl);
        switch (keyCode) {
            case KeyEvent.KEYCODE_DPAD_UP: return csi("A", m, appCursor);
            case KeyEvent.KEYCODE_DPAD_DOWN: return csi("B", m, appCursor);
            case KeyEvent.KEYCODE_DPAD_RIGHT: return csi("C", m, appCursor);
            case KeyEvent.KEYCODE_DPAD_LEFT: return csi("D", m, appCursor);
            case KeyEvent.KEYCODE_MOVE_HOME: return csi("H", m, appCursor);
            case KeyEvent.KEYCODE_MOVE_END: return csi("F", m, appCursor);
            case KeyEvent.KEYCODE_PAGE_UP: return tilde(5, m);
            case KeyEvent.KEYCODE_PAGE_DOWN: return tilde(6, m);
            case KeyEvent.KEYCODE_INSERT: return tilde(2, m);
            case KeyEvent.KEYCODE_FORWARD_DEL: return tilde(3, m);
            case KeyEvent.KEYCODE_TAB: return shift ? ESC + "[Z" : (alt ? ESC : "") + "\t";
            case KeyEvent.KEYCODE_ESCAPE: return ESC;
            case KeyEvent.KEYCODE_ENTER: case KeyEvent.KEYCODE_NUMPAD_ENTER: return (alt ? ESC : "") + "\r";
            case KeyEvent.KEYCODE_DEL: return (alt ? ESC : "") + (ctrl ? "\b" : "\u007f");
            default:
        }
        if (keyCode >= KeyEvent.KEYCODE_F1 && keyCode <= KeyEvent.KEYCODE_F12) {
            int f = keyCode - KeyEvent.KEYCODE_F1 + 1;
            if (f <= 4) return m > 1 ? ESC + "[1;" + m + "PQRS".charAt(f - 1) : ESC + "O" + "PQRS".charAt(f - 1);
            int[] code = {15, 17, 18, 19, 20, 21, 23, 24};
            return tilde(code[f - 5], m);
        }
        return null;
    }

    /** Typed text with the sticky/held modifiers applied: Ctrl+letter is a control code, Alt prefixes ESC. */
    static String text(String s, boolean alt, boolean ctrl) {
        if (s.isEmpty() || (!alt && !ctrl)) return s;
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (ctrl) {
                if (c >= 'a' && c <= 'z') c = (char) (c - 'a' + 1);
                else if (c >= 'A' && c <= 'Z') c = (char) (c - 'A' + 1);
                else if (c == ' ' || c == '2' || c == '@') c = 0;
                else if (c >= '[' && c <= '_') c = (char) (c - '@');
                else if (c == '?') c = 127;
                else if (c >= '3' && c <= '7') c = (char) (c - '3' + 27);
            }
            if (alt) b.append(ESC);
            b.append(c);
        }
        return b.toString();
    }

    /** Pasted text; wrapped in the bracketed-paste markers when the program asked for them. */
    static String paste(String s, boolean bracketed) {
        String t = s.replace("\r\n", "\r").replace('\n', '\r');
        return bracketed ? ESC + "[200~" + t + ESC + "[201~" : t;
    }

    static final int LEFT = 0, MIDDLE = 1, RIGHT = 2, WHEEL_UP = 64, WHEEL_DOWN = 65;

    /**
     * A mouse report for a program that asked for the mouse. {@code col} and {@code row} start at 1. SGR form
     * (ESC [ < b ; x ; y M/m) when the program asked for it, else the classic form, which cannot reach past column 223.
     * Returns null when the event cannot be expressed.
     */
    static String mouse(int button, int col, int row, boolean release, boolean motion, boolean sgr) {
        int b = button + (motion ? 32 : 0);
        if (sgr) return ESC + "[<" + b + ";" + col + ";" + row + (release ? "m" : "M");
        if (col > 223 || row > 223) return null;
        if (release) b = 3 + (motion ? 32 : 0);
        return ESC + "[M" + (char) (32 + b) + (char) (32 + col) + (char) (32 + row);
    }
}
