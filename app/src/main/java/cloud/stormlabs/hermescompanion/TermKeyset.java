package cloud.stormlabs.hermescompanion;

import android.view.KeyEvent;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Every button the terminal's key row can hold, and the presets. A layout is just an ordered list of key ids (saved in
 * {@link TermStore}); ids starting with "c:" are the user's own keys, whose text is a sequence like {@code ^b c}.
 */
final class TermKeyset {
    private TermKeyset() {}

    static final int MOD = 0, KEY = 1, TEXT = 2, PAIR = 3, SEQ = 4, ACT = 5, CUSTOM = 6;

    static final class Key {
        final String id, label, group; final int type, code; final String data;
        Key(String id, String label, String group, int type, int code, String data) { this.id = id; this.label = label; this.group = group; this.type = type; this.code = code; this.data = data; }
    }

    private static final Map<String, Key> ALL = new LinkedHashMap<>();

    private static void add(String id, String label, String group, int type, int code, String data) { ALL.put(id, new Key(id, label, group, type, code, data)); }

    static {
        add("kbd", "⌨", "Actions", ACT, 0, null);
        add("snip", "Snip", "Actions", ACT, 0, null);
        add("paste", "Paste", "Actions", ACT, 0, null);
        add("copy", "Copy", "Actions", ACT, 0, null);
        add("sel", "Select", "Actions", ACT, 0, null);
        add("mouse", "Mouse", "Actions", ACT, 0, null);
        add("ctrl", "Ctrl", "Modifiers", MOD, 0, null);
        add("alt", "Alt", "Modifiers", MOD, 0, null);
        add("esc", "Esc", "Keys", KEY, KeyEvent.KEYCODE_ESCAPE, null);
        add("tab", "Tab", "Keys", KEY, KeyEvent.KEYCODE_TAB, null);
        add("enter", "Enter", "Keys", KEY, KeyEvent.KEYCODE_ENTER, null);
        add("bksp", "⌫", "Keys", KEY, KeyEvent.KEYCODE_DEL, null);
        add("left", "←", "Keys", KEY, KeyEvent.KEYCODE_DPAD_LEFT, null);
        add("down", "↓", "Keys", KEY, KeyEvent.KEYCODE_DPAD_DOWN, null);
        add("up", "↑", "Keys", KEY, KeyEvent.KEYCODE_DPAD_UP, null);
        add("right", "→", "Keys", KEY, KeyEvent.KEYCODE_DPAD_RIGHT, null);
        add("home", "Home", "Keys", KEY, KeyEvent.KEYCODE_MOVE_HOME, null);
        add("end", "End", "Keys", KEY, KeyEvent.KEYCODE_MOVE_END, null);
        add("pgup", "PgUp", "Keys", KEY, KeyEvent.KEYCODE_PAGE_UP, null);
        add("pgdn", "PgDn", "Keys", KEY, KeyEvent.KEYCODE_PAGE_DOWN, null);
        add("del", "Del", "Keys", KEY, KeyEvent.KEYCODE_FORWARD_DEL, null);
        add("ins", "Ins", "Keys", KEY, KeyEvent.KEYCODE_INSERT, null);
        for (int i = 1; i <= 12; i++) add("f" + i, "F" + i, "Function keys", KEY, KeyEvent.KEYCODE_F1 + i - 1, null);
        for (String c : "| ~ / - _ $ & * \\ ` : ; ! ? # @ % ^ + , . \" ' =".split(" ")) add("t:" + c, c, "Symbols", TEXT, 0, c);
        add("p:{}", "{ }", "Symbols", PAIR, 0, "{}");
        add("p:[]", "[ ]", "Symbols", PAIR, 0, "[]");
        add("p:()", "( )", "Symbols", PAIR, 0, "()");
        add("p:<>", "< >", "Symbols", PAIR, 0, "<>");
        for (char c = 'A'; c <= 'Z'; c++) add("^" + c, "^" + c, "Ctrl combos", SEQ, 0, String.valueOf((char) (c - '@')));
        add("alt-b", "Alt+B", "Alt combos", SEQ, 0, "\u001bb");
        add("alt-f", "Alt+F", "Alt combos", SEQ, 0, "\u001bf");
        add("alt-d", "Alt+D", "Alt combos", SEQ, 0, "\u001bd");
        add("alt-bksp", "Alt+⌫", "Alt combos", SEQ, 0, "\u001b\u007f");
        add("alt-dot", "Alt+.", "Alt combos", SEQ, 0, "\u001b.");
    }

    static List<Key> catalog() { return new ArrayList<>(ALL.values()); }

    static List<String> groups() {
        List<String> g = new ArrayList<>();
        for (Key k : ALL.values()) if (!g.contains(k.group)) g.add(k.group);
        return g;
    }

    static Key find(String id) { return ALL.get(id); }

    /** The key for an id, including the user's own ("c:name" with its sequence from {@code custom}). Null when unknown. */
    static Key resolve(String id, List<TermStore.Entry> custom) {
        Key k = ALL.get(id);
        if (k != null || !id.startsWith("c:")) return k;
        for (TermStore.Entry e : custom) if (("c:" + e.name).equals(id)) return new Key(id, e.name, "Mine", CUSTOM, 0, e.value);
        return null;
    }

    /**
     * Turns what a person types for a custom key into bytes: {@code ^b} is Ctrl+B, {@code \e} Escape, {@code \r} Enter,
     * {@code \t} Tab, {@code \\} a backslash; anything else is sent as typed. {@code ^b c} is tmux's new window.
     */
    static String parseSequence(String s) {
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '\\' && i + 1 < s.length()) {
                char n = s.charAt(++i);
                b.append(n == 'e' ? '\u001b' : n == 'r' ? '\r' : n == 'n' ? '\n' : n == 't' ? '\t' : n);
            } else if (c == '^' && i + 1 < s.length() && Character.isLetter(s.charAt(i + 1))) {
                b.append((char) (Character.toUpperCase(s.charAt(++i)) - '@'));
            } else if (c == '^' && i + 1 < s.length() && s.charAt(i + 1) == '[') {
                b.append('\u001b'); i++;
            } else b.append(c);
        }
        return b.toString();
    }

    static final String[] PRESET_NAMES = {"Default", "Minimal", "Shell", "Editor (vim, nano)", "tmux, herdr", "Everything"};

    private static List<String> ids(String spaced) {
        List<String> out = new ArrayList<>();
        for (String s : spaced.trim().split("\\s+")) out.add(s);
        return out;
    }

    static List<String> preset(String name) {
        switch (name) {
            case "Minimal": return ids("kbd esc tab ctrl alt left down up right");
            case "Shell": return ids("kbd esc tab ctrl alt up down left right ^C ^D ^Z ^L ^R ^A ^E ^U t:| t:~ t:/ t:- snip paste");
            case "Editor (vim, nano)": return ids("kbd esc t::  t:/ ctrl left down up right home end pgup pgdn ^O ^X ^W ^S ^Q ^G t:; t:\" p:{} p:[] p:() paste");
            case "tmux, herdr": return ids("kbd esc tab ctrl alt ^B left down up right pgup pgdn enter sel mouse ^C ^D t:/ paste snip");
            case "Everything": {
                List<String> out = new ArrayList<>();
                for (Key k : ALL.values()) out.add(k.id);
                return out;
            }
            default: return ids("kbd snip paste esc tab ctrl alt left down up right home end pgup pgdn del ins t:| t:~ t:/ t:- t:_ t:$ t:& t:* t:\\ t:` p:{} p:[] p:() p:<> t:\" t:' t:= f1 f2 f3 f4 f5 f6 f7 f8 f9 f10 f11 f12");
        }
    }
}
