package cloud.stormlabs.hermescompanion;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/** The Terminal module's saved hosts, snippets, open tabs and look (font size, theme). */
final class TermStore {
    static final class Entry {
        final String name, value;
        Entry(String name, String value) { this.name = name; this.value = value; }
    }

    private final SharedPreferences p;

    TermStore(Context c) { p = c.getSharedPreferences("terminal", Context.MODE_PRIVATE); }

    List<Entry> hosts() { return list("hosts", "name", "target"); }
    List<Entry> snippets() { return list("snippets", "name", "cmd"); }
    List<Entry> tabs() { return list("tabs", "id", "target"); }

    void saveHost(String name, String target) { put("hosts", "name", name, "target", target); }
    void saveSnippet(String name, String cmd) { put("snippets", "name", name, "cmd", cmd); }
    void removeHost(String name) { remove("hosts", "name", name); }
    void removeSnippet(String name) { remove("snippets", "name", name); }
    void saveTabs(List<Entry> tabs) {
        JSONArray a = new JSONArray();
        try { for (Entry e : tabs) a.put(new JSONObject().put("id", e.name).put("target", e.value)); } catch (Exception ignored) { }
        p.edit().putString("tabs", a.toString()).apply();
    }

    /** The key row: ordered key ids (see {@link TermKeyset}); the default preset until the user changes it. */
    List<String> layout() {
        String raw = p.getString("layout", "");
        List<String> out = new ArrayList<>();
        try { JSONArray a = new JSONArray(raw); for (int i = 0; i < a.length(); i++) out.add(a.getString(i)); } catch (Exception ignored) { }
        return out.isEmpty() ? TermKeyset.preset("Default") : out;
    }
    void layout(List<String> ids) { p.edit().putString("layout", new JSONArray(ids).toString()).apply(); }
    List<Entry> customKeys() { return list("custom_keys", "name", "seq"); }
    void saveCustomKey(String name, String seq) { put("custom_keys", "name", name, "seq", seq); }
    void removeCustomKey(String name) {
        List<Entry> next = new ArrayList<>();
        for (Entry e : customKeys()) if (!e.name.equals(name)) next.add(e);
        write("custom_keys", "name", "seq", next);
    }
    int keyRows() { return Math.max(1, Math.min(3, p.getInt("key_rows", 1))); }
    void keyRows(int v) { p.edit().putInt("key_rows", v).apply(); }

    float fontSp() { return p.getFloat("font_sp", 13f); }
    void fontSp(float v) { p.edit().putFloat("font_sp", v).apply(); }
    int theme() { return Math.max(0, Math.min(TermView.THEMES.length - 1, p.getInt("theme", 0))); }
    void theme(int v) { p.edit().putInt("theme", v).apply(); }
    /** Shells run inside tmux on the hub, so they outlive a hub restart (default on). */
    boolean keepShells() { return p.getBoolean("keep_shells", true); }
    void keepShells(boolean v) { p.edit().putBoolean("keep_shells", v).apply(); }
    boolean keepAwake() { return p.getBoolean("keep_awake", true); }
    void keepAwake(boolean v) { p.edit().putBoolean("keep_awake", v).apply(); }

    /** A host the user typed: "user@host", "host:port" or an ssh alias; false for anything the hub would refuse. */
    static boolean validTarget(String t) {
        return t != null && t.matches("^(hub|(?:[A-Za-z0-9._-]{1,64}@)?[A-Za-z0-9][A-Za-z0-9._-]{0,252}(?::[0-9]{1,5})?)$");
    }

    private List<Entry> list(String key, String a, String b) {
        List<Entry> out = new ArrayList<>();
        try {
            JSONArray arr = new JSONArray(p.getString(key, "[]"));
            for (int i = 0; i < arr.length(); i++) { JSONObject o = arr.getJSONObject(i); out.add(new Entry(o.optString(a), o.optString(b))); }
        } catch (Exception ignored) { }
        return out;
    }

    private void put(String key, String a, String av, String b, String bv) {
        List<Entry> all = list(key, a, b), next = new ArrayList<>();
        for (Entry e : all) if (!e.name.equals(av)) next.add(e);
        next.add(new Entry(av, bv));
        write(key, a, b, next);
    }

    private void remove(String key, String a, String av) {
        List<Entry> next = new ArrayList<>();
        for (Entry e : list(key, a, key.equals("hosts") ? "target" : "cmd")) if (!e.name.equals(av)) next.add(e);
        write(key, a, key.equals("hosts") ? "target" : "cmd", next);
    }

    private void write(String key, String a, String b, List<Entry> items) {
        JSONArray arr = new JSONArray();
        try { for (Entry e : items) arr.put(new JSONObject().put(a, e.name).put(b, e.value)); } catch (Exception ignored) { }
        p.edit().putString(key, arr.toString()).apply();
    }
}
