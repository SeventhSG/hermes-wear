package cloud.stormlabs.hermescompanion;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.widget.Toast;

import org.json.JSONObject;

import java.io.File;

/** Over-the-air updates served by the user's hub (/app/latest, /app/apk): check, notify, download, verify, install. */
final class Updater {
    private Updater() {}

    static final String EXTRA_UPDATE = "open_update";
    private static final long THROTTLE_MS = 3600_000L, RETRY_MS = 120_000L;
    private static final String PREFS = "updater", LAST = "last_check", LATEST = "latest";
    private static boolean busy, pendingInstall;

    // ---- pure pieces (unit-tested)
    static boolean newer(long remote, long installed) { return remote > installed; }
    /** Dotted numeric versions ("7.10.0" > "7.9.1"); non-numeric parts count as 0, a "-suffix" is ignored. */
    static boolean newerName(String remote, String installed) {
        String[] a = remote.split("-")[0].split("\\."), b = installed.split("-")[0].split("\\.");
        for (int i = 0; i < Math.max(a.length, b.length); i++) {
            int x = i < a.length ? num(a[i]) : 0, y = i < b.length ? num(b[i]) : 0;
            if (x != y) return x > y;
        }
        return false;
    }
    private static int num(String s) { try { return Integer.parseInt(s.trim()); } catch (NumberFormatException e) { return 0; } }
    /** versionCode when the hub sent one, else a versionName comparison. */
    static boolean newer(JSONObject o, long code, String name) {
        return o.has("versionCode") ? newer(o.optLong("versionCode"), code) : newerName(o.optString("versionName"), name);
    }
    /** Background checks run at most hourly; a clock set back also counts as due. */
    static boolean due(long last, long now) { return now - last >= THROTTLE_MS || last > now; }
    static String hex(byte[] d) { StringBuilder b = new StringBuilder(); for (byte x : d) b.append(String.format(java.util.Locale.ROOT, "%02x", x)); return b.toString(); }
    static boolean verified(JSONObject info, String sha, long size) { return info.optLong("size", -1) == size && sha.equalsIgnoreCase(info.optString("sha256")); }

    private static SharedPreferences prefs(Context c) { return c.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE); }

    @SuppressWarnings("deprecation")
    private static long installed(Context c) throws Exception {
        android.content.pm.PackageInfo p = c.getPackageManager().getPackageInfo(c.getPackageName(), 0);
        return android.os.Build.VERSION.SDK_INT >= 28 ? p.getLongVersionCode() : p.versionCode;
    }

    private static String installedName(Context c) throws Exception {
        return c.getPackageManager().getPackageInfo(c.getPackageName(), 0).versionName;
    }

    private static JSONObject stored(Context c) {
        try { return new JSONObject(prefs(c).getString(LATEST, "")); } catch (Exception e) { return null; }
    }

    /** Version name of a newer build found earlier, else null. */
    static String available(Context c) {
        try { JSONObject o = stored(c); return o != null && newer(o, installed(c), installedName(c)) ? o.optString("versionName") : null; }
        catch (Exception e) { return null; }
    }

    private static void ui(Runnable r) { new Handler(Looper.getMainLooper()).post(r); } // lazily: no Looper in JVM unit tests

    private static void toast(Context c, String m) { ui(() -> Toast.makeText(c, m, Toast.LENGTH_LONG).show()); }

    /** Off the main thread. Background calls are throttled; user-initiated ones always check and report. */
    static void check(Context c, boolean user) {
        Context app = c.getApplicationContext();
        SharedPreferences p = prefs(app);
        long now = System.currentTimeMillis();
        if (!user && !due(p.getLong(LAST, 0), now)) return;
        SettingsStore store = new SettingsStore(app);
        final String profile = store.profileId();
        final SettingsStore.Connection hub = store.hubConnection(profile);
        if (hub == null || hub.endpoint.isEmpty() || hub.key.isEmpty()) { if (user) toast(c, "Pair your Hermes hub to get updates"); return; }
        p.edit().putLong(LAST, now).apply();
        new Thread(() -> {
            try {
                JSONObject o = new HubApi(hub.endpoint, hub.key).appLatest();
                o.put("_hub_profile", profile);
                if (!o.optBoolean("ready", true)) { // hub still fetching the release APK: look again in ~2 min, not in an hour
                    p.edit().putLong(LAST, now - THROTTLE_MS + RETRY_MS).apply();
                    if (user) toast(c, "Update " + o.optString("versionName") + " is downloading on your server, try again in a minute");
                    return;
                }
                boolean fresh = newer(o, installed(app), installedName(app));
                p.edit().putString(LATEST, fresh ? o.toString() : "").apply();
                if (!fresh) { if (user) toast(c, "Up to date"); }
                else if (user && c instanceof Activity) ui(() -> prompt((Activity) c));
                else Notifier.update(app, "Update available · " + o.optString("versionName"), o.optString("notes"), -1);
            } catch (Exception e) {
                p.edit().putLong(LAST, now - THROTTLE_MS + RETRY_MS).apply(); // offline or hub down: retry soon
                if (user) toast(c, "Update check failed: " + HermesApi.friendly(e));
            }
        }, "updater").start();
    }

    /** Release notes as plain text: no markdown headings or bold marks, blank lines squeezed. */
    static String plain(String notes) {
        String t = notes.replaceAll("(?m)^#+\\s*", "").replace("**", "").replace("`", "").replaceAll("\\n{3,}", "\n\n").trim();
        return t;
    }

    /** Confirm, then (after the unknown-sources grant, if needed) download and install. */
    static void prompt(Activity a) {
        JSONObject o = stored(a);
        if (o == null) return;
        Sheets.confirm(a, "Install Hermes Companion " + o.optString("versionName") + "?", plain(o.optString("notes")), "Install", false, () -> {
            if (a.getPackageManager().canRequestPackageInstalls()) download(a, o);
            else {
                pendingInstall = true;
                a.startActivity(new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:" + a.getPackageName())));
            }
        });
    }

    /** Back from the unknown-sources screen: continue the install if it was granted. */
    static void resume(Activity a) {
        if (!pendingInstall) return;
        pendingInstall = false;
        JSONObject o = stored(a);
        if (o != null && a.getPackageManager().canRequestPackageInstalls()) download(a, o);
    }

    private static void download(Activity a, JSONObject info) {
        if (busy) return;
        busy = true;
        SettingsStore store = new SettingsStore(a);
        final SettingsStore.Connection hub = store.hubConnection(info.optString("_hub_profile", store.profileId()));
        if (hub == null || hub.endpoint.isEmpty() || hub.key.isEmpty()) { busy = false; toast(a, "Pair your Hermes hub to get updates"); return; }
        File dir = new File(a.getCacheDir(), "media"), f = new File(dir, "hermes-update.apk");
        Toast.makeText(a, "Downloading update…", Toast.LENGTH_SHORT).show();
        new Thread(() -> {
            try {
                dir.mkdirs();
                String title = "Downloading " + info.optString("versionName"); long size = Math.max(1, info.optLong("size", 1)); int[] last = {-1};
                String sha = new HubApi(hub.endpoint, hub.key).appApk(f, n -> {
                    int pct = (int) Math.min(100, n * 100 / size);
                    if (pct / 5 != last[0]) { last[0] = pct / 5; Notifier.update(a, title, pct + "%", pct); }
                });
                Notifier.cancelUpdate(a);
                if (!verified(info, sha, f.length())) { f.delete(); throw new IllegalStateException("checksum mismatch"); }
                ui(() -> a.startActivity(new Intent(Intent.ACTION_VIEW)
                        .setDataAndType(FileViewerActivity.uri(a, f), "application/vnd.android.package-archive")
                        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_ACTIVITY_NEW_TASK)));
            } catch (Exception e) {
                Notifier.cancelUpdate(a);
                f.delete();
                toast(a, "Update failed: " + HermesApi.friendly(e));
            } finally { busy = false; }
        }, "updater-download").start();
    }
}
