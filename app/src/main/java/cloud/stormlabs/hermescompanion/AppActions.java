package cloud.stormlabs.hermescompanion;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.content.res.Resources;
import android.content.res.XmlResourceParser;
import android.net.Uri;
import android.os.Bundle;

import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * What installed apps declare they can do, read from their shortcuts.xml: App Actions capabilities
 * (actions.intent.CREATE_CALL, GET_THING …, the entry points Google Assistant used) and static shortcuts.
 * Hermes can list them and invoke one directly instead of navigating the app's screens.
 */
final class AppActions {
    private AppActions() {}

    static final class Entry {
        final String pkg, app, kind, name;          // kind: capability | shortcut
        final String action, targetPackage, targetClass, data;
        String urlTemplate;
        final List<String[]> params = new ArrayList<>(); // {name, key}
        Entry(String pkg, String app, String kind, String name, String action, String targetPackage, String targetClass, String data, String urlTemplate) {
            this.pkg = pkg; this.app = app; this.kind = kind; this.name = name; this.action = action;
            this.targetPackage = targetPackage; this.targetClass = targetClass; this.data = data; this.urlTemplate = urlTemplate;
        }
        String describe() {
            StringBuilder b = new StringBuilder(app).append(" (").append(pkg).append(") · ").append(kind).append(' ').append(name);
            if (!params.isEmpty()) { b.append(" · params:"); for (String[] p : params) b.append(' ').append(p[1]); }
            return b.toString();
        }
    }

    /** Every app's declared actions, optionally only apps whose label or package contains {@code filter}. */
    static List<Entry> list(Context c, String filter) {
        PackageManager pm = c.getPackageManager();
        String f = filter == null ? "" : filter.trim().toLowerCase(Locale.ROOT);
        List<Entry> out = new ArrayList<>();
        Intent main = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER);
        for (ResolveInfo ri : pm.queryIntentActivities(main, PackageManager.GET_META_DATA)) {
            ActivityInfo ai = ri.activityInfo;
            if (ai == null) continue;
            String label = String.valueOf(ri.loadLabel(pm));
            if (!f.isEmpty() && !label.toLowerCase(Locale.ROOT).contains(f) && !ai.packageName.toLowerCase(Locale.ROOT).contains(f)) continue;
            Bundle meta = ai.metaData;
            int xml = meta == null ? 0 : meta.getInt("android.app.shortcuts", 0);
            if (xml == 0) continue;
            try { parse(pm.getResourcesForApplication(ai.applicationInfo), xml, ai.packageName, label, out); }
            catch (Exception ignored) { /* unreadable resources: skip the app */ }
        }
        return out;
    }

    private static void parse(Resources res, int xml, String pkg, String label, List<Entry> out) throws Exception {
        XmlResourceParser p = res.getXml(xml);
        String kind = null, name = null; Entry current = null;
        for (int ev = p.getEventType(); ev != XmlResourceParser.END_DOCUMENT; ev = p.next()) {
            if (ev == XmlResourceParser.START_TAG) {
                String tag = p.getName();
                if ("capability".equals(tag)) { kind = "capability"; name = attr(p, "name"); current = null; }
                else if ("shortcut".equals(tag)) { kind = "shortcut"; name = attr(p, "shortcutId"); current = null; }
                else if ("intent".equals(tag) && kind != null && current == null) {
                    current = new Entry(pkg, label, kind, name == null ? "" : name, attr(p, "action"),
                            or(attr(p, "targetPackage"), pkg), attr(p, "targetClass"), attr(p, "data"), null);
                    out.add(current);
                } else if ("url-template".equals(tag) && current != null && current.urlTemplate == null) {
                    current.urlTemplate = attr(p, "value"); // <intent><url-template …/></intent>, as Telegram declares it
                } else if ("url-template".equals(tag) && kind != null && current == null) {
                    current = new Entry(pkg, label, kind, name == null ? "" : name, Intent.ACTION_VIEW, pkg, null, null, attr(p, "value"));
                    out.add(current);
                } else if ("parameter".equals(tag) && current != null) {
                    current.params.add(new String[]{attr(p, "name"), or(attr(p, "key"), attr(p, "name"))});
                }
            } else if (ev == XmlResourceParser.END_TAG && ("capability".equals(p.getName()) || "shortcut".equals(p.getName()))) {
                kind = null; name = null; current = null;
            }
        }
        p.close();
    }

    /** The intent for {@code name} (capability or shortcut id) of {@code pkg}, with {@code params} filled in. */
    static Intent intentFor(Context c, String pkg, String name, JSONObject params) {
        for (Entry e : list(c, pkg)) {
            if (!e.pkg.equals(pkg) || !e.name.equalsIgnoreCase(name)) continue;
            Intent i;
            if (e.urlTemplate != null) {
                i = new Intent(Intent.ACTION_VIEW, Uri.parse(expand(e.urlTemplate, params))).setPackage(pkg);
            } else {
                i = new Intent(e.action == null ? Intent.ACTION_VIEW : e.action);
                if (e.targetClass != null) i.setComponent(new ComponentName(e.targetPackage, e.targetClass)); else i.setPackage(e.targetPackage);
                if (e.data != null) i.setData(Uri.parse(e.data));
                for (String[] prm : e.params) if (params.has(prm[1])) i.putExtra(prm[1], params.optString(prm[1]));
            }
            return i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        }
        return null;
    }

    /** RFC 6570 subset used by App Actions: {var} and {?a,b,c} (only the params that were given). */
    static String expand(String template, JSONObject params) {
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("\\{([?&]?)([^}]*)\\}").matcher(template);
        StringBuffer out = new StringBuffer();
        while (m.find()) {
            String op = m.group(1); StringBuilder rep = new StringBuilder();
            for (String var : m.group(2).split(",")) {
                String v = params.optString(var.trim(), "");
                if (v.isEmpty()) continue;
                if (op.isEmpty()) rep.append(enc(v));
                else rep.append(rep.length() == 0 ? op : "&").append(var.trim()).append('=').append(enc(v));
            }
            m.appendReplacement(out, java.util.regex.Matcher.quoteReplacement(rep.toString()));
        }
        m.appendTail(out);
        return out.toString();
    }

    private static String enc(String v) {
        try { return java.net.URLEncoder.encode(v, "UTF-8").replace("+", "%20"); } catch (Exception e) { return v; }
    }

    private static String attr(XmlResourceParser p, String name) { return p.getAttributeValue("http://schemas.android.com/apk/res/android", name); }
    private static String or(String a, String b) { return a == null || a.isEmpty() ? b : a; }
}
