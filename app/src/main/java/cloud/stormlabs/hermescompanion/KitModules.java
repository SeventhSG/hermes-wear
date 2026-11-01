package cloud.stormlabs.hermescompanion;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The server kit's module catalogue (server-kit/modules.json, shipped as an asset) and the install command line for a
 * selection. Pure data, so the setup sheet and the prompt sent to Hermes are testable without a device.
 */
final class KitModules {
    static final class Module {
        final String id, name, summary, detail, group;
        final boolean required, defaultOn, experimental;
        final List<String> children, depends;

        Module(JSONObject o) throws org.json.JSONException {
            id = o.getString("id"); name = o.optString("name", id); summary = o.optString("summary", "");
            detail = o.optString("detail", summary); group = o.optString("group", "");
            required = o.optBoolean("required"); defaultOn = o.optBoolean("default"); experimental = o.optBoolean("experimental");
            children = strings(o.optJSONArray("children")); depends = strings(o.optJSONArray("depends"));
        }
        boolean isGroup() { return !children.isEmpty(); }
        boolean isSkill() { return id.startsWith("skill-"); }
    }

    final Map<String, Module> byId = new LinkedHashMap<>();
    final Map<String, List<String>> presets = new LinkedHashMap<>();
    final Map<String, String> presetTitles = new LinkedHashMap<>();
    final Map<String, String> groupTitles = new LinkedHashMap<>();
    final String kitVersion;

    private KitModules(JSONObject root) throws org.json.JSONException {
        kitVersion = root.optString("kit_version", "");
        JSONArray mods = root.getJSONArray("modules");
        for (int i = 0; i < mods.length(); i++) { Module m = new Module(mods.getJSONObject(i)); byId.put(m.id, m); }
        JSONObject ps = root.optJSONObject("presets");
        if (ps != null) for (java.util.Iterator<String> it = ps.keys(); it.hasNext(); ) {
            String k = it.next(); JSONObject p = ps.getJSONObject(k);
            presets.put(k, strings(p.optJSONArray("modules"))); presetTitles.put(k, p.optString("title", k));
        }
        JSONArray gs = root.optJSONArray("groups");
        if (gs != null) for (int i = 0; i < gs.length(); i++) groupTitles.put(gs.getJSONObject(i).getString("id"), gs.getJSONObject(i).optString("title"));
    }

    static KitModules parse(String json) {
        try { return new KitModules(new JSONObject(json)); } catch (Exception e) { throw new IllegalArgumentException("bad modules.json: " + e.getMessage(), e); }
    }

    static KitModules load(android.content.Context c) throws Exception {
        try (InputStream in = c.getAssets().open("server-kit-modules.json")) {
            ByteArrayOutputStream out = new ByteArrayOutputStream(); byte[] buf = new byte[8192]; int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
            return parse(new String(out.toByteArray(), StandardCharsets.UTF_8));
        }
    }

    static List<String> strings(JSONArray a) {
        List<String> out = new ArrayList<>();
        if (a != null) for (int i = 0; i < a.length(); i++) out.add(a.optString(i));
        return out;
    }

    /** Rows for the chooser, in manifest order: top-level modules, and the skills as one group row followed by its children. */
    List<Module> rows() {
        List<Module> out = new ArrayList<>();
        for (Module m : byId.values()) if (!m.isSkill()) out.add(m);
        return out;
    }

    List<Module> skills() {
        List<Module> out = new ArrayList<>();
        for (Module m : byId.values()) if (m.isSkill()) out.add(m);
        return out;
    }

    /** Preset or group names expanded to installable module ids. */
    Set<String> expand(Iterable<String> names) {
        Set<String> out = new LinkedHashSet<>();
        for (String n : names) {
            Module m = byId.get(n);
            if (m == null) continue;
            if (m.isGroup()) out.addAll(m.children); else out.add(m.id);
        }
        return out;
    }

    Set<String> preset(String name) { List<String> p = presets.get(name); return normalize(expand(p == null ? Collections.<String>emptyList() : p)); }

    Set<String> defaults() {
        Set<String> out = new LinkedHashSet<>();
        for (Module m : byId.values()) if (m.defaultOn && !m.isGroup()) out.add(m.id);
        return normalize(out);
    }

    /** Adds the hub and everything the chosen modules depend on; drops unknown ids; keeps the catalogue's order. */
    Set<String> normalize(Set<String> selected) {
        Set<String> want = new LinkedHashSet<>();
        want.add("hub");
        for (String id : selected) add(want, id);
        Set<String> out = new LinkedHashSet<>();
        for (String id : byId.keySet()) if (want.contains(id)) out.add(id);
        return out;
    }

    private void add(Set<String> out, String id) {
        Module m = byId.get(id);
        if (m == null || m.isGroup()) return;
        for (String d : m.depends) add(out, d);
        out.add(id);
    }

    /** The installer's selection arguments: whole skill set as `skills`, a part of it as `--skills a,b`. */
    String args(Set<String> selected) {
        Set<String> sel = normalize(selected);
        List<String> modules = new ArrayList<>(), skills = new ArrayList<>();
        for (String id : sel) { if (byId.get(id).isSkill()) skills.add(id.substring("skill-".length())); else modules.add(id); }
        if (skills.size() == skills().size()) modules.add("skills");
        StringBuilder b = new StringBuilder("--modules ").append(join(modules));
        if (!skills.isEmpty() && skills.size() != skills().size()) b.append(" --skills ").append(join(skills));
        return b.toString();
    }

    String names(Set<String> selected) {
        List<String> out = new ArrayList<>();
        int skills = 0;
        for (String id : normalize(selected)) { Module m = byId.get(id); if (m.isSkill()) skills++; else out.add(m.name); }
        if (skills > 0) out.add(1, skills == skills().size() ? "all " + skills + " skills" : skills + " of " + skills().size() + " skills");
        return join(out, ", ");
    }

    boolean needsVoiceDownloads(Set<String> selected) { return selected.contains("voice-stt") || selected.contains("voice-tts"); }

    static String join(List<String> l) { return join(l, ","); }
    static String join(List<String> l, String sep) {
        StringBuilder b = new StringBuilder();
        for (String s : l) b.append(b.length() == 0 ? "" : sep).append(s);
        return b.toString();
    }

    static Set<String> fromCsv(String csv) {
        Set<String> out = new LinkedHashSet<>();
        if (csv != null) for (String s : csv.split(",")) if (!s.trim().isEmpty()) out.add(s.trim());
        return out;
    }

    static String toCsv(Set<String> ids) { return join(new ArrayList<>(ids)); }
}
