package cloud.stormlabs.hermescompanion;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.widget.Toast;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.security.SecureRandom;
import java.util.Locale;

/**
 * Installs the bundled server kit (assets/server-kit.zip, modules listed in assets/server-kit-modules.json) through Hermes, with the modules the user picked.
 * Hermes' API cannot receive files, so the setup prompt asks Hermes to run a one-shot receiver on a random high port; the app
 * uploads the kit to it with a one-time token, and Hermes installs it. Every command goes through Hermes' approvals.
 */
final class ServerSetup {
    private static final String TAG = "ServerSetup";

    private ServerSetup() { }

    /** True when a hub already answers at the configured hub URL (off the UI thread). */
    static boolean hubUp(SettingsStore store) { return new HubApi(store.hubUrl(), store.hubKey()).health().ok; }

    /** Lets the user choose the modules, then opens a "Server setup" chat in which Hermes installs them; {@code opened} runs once the chat starts. */
    static void offer(Activity a, SettingsStore store, Runnable opened) {
        String host = host(store.endpoint());
        KitModules kit;
        try { kit = KitModules.load(a); }
        catch (Exception e) { Toast.makeText(a, "The bundled server pack is damaged: " + e.getMessage(), Toast.LENGTH_LONG).show(); return; }
        java.util.Set<String> saved = KitModules.fromCsv(store.kitModules());
        java.util.Set<String> initial = saved.isEmpty() ? kit.preset("recommended") : saved;
        new Thread(() -> {
            boolean update = hubUp(store);
            a.runOnUiThread(() -> {
                if (a.isFinishing() || a.isDestroyed()) return;
                Sheets.kitModules(a, kit, initial, host, update, chosen -> {
                    java.util.Set<String> picked = kit.normalize(chosen);
                    store.kitModules(KitModules.toCsv(picked));
                    start(a, store, host, kit, picked, update, opened);
                });
            });
        }, "server-setup-probe").start();
    }

    private static void start(Activity a, SettingsStore store, String host, KitModules kit, java.util.Set<String> picked, boolean update, Runnable opened) {
        Toast.makeText(a, "Starting server setup…", Toast.LENGTH_SHORT).show();
        Context app = a.getApplicationContext();
        new Thread(() -> {
            try {
                HermesApi.SessionInfo s = Backends.create(store).createSession("Server setup");
                String token = token();
                int port = 40000 + new SecureRandom().nextInt(20000);
                upload(app, host, port, token);
                a.runOnUiThread(() -> {
                    a.startActivity(new Intent(a, MainActivity.class)
                            .putExtra(MainActivity.EXTRA_SESSION, s.id)
                            .putExtra(ChatActivity.EXTRA_TITLE, "Server setup")
                            .putExtra(ChatActivity.EXTRA_PROMPT, prompt(token, port, kit, picked, update))
                            .putExtra(ChatActivity.EXTRA_AUTOSEND, true)
                            .putExtra(MainActivity.EXTRA_PROFILE_SECRET, store.intentSecret())
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
                    if (opened != null) opened.run();
                });
            } catch (Exception e) {
                a.runOnUiThread(() -> Toast.makeText(a, "Couldn't start setup: " + HermesApi.friendly(e), Toast.LENGTH_LONG).show());
            }
        }, "server-setup").start();
    }

    /** The chat message: receive the kit, preview what the installer would do, install the chosen modules, verify. */
    static String prompt(String token, int port, KitModules kit, java.util.Set<String> picked, boolean update) {
        String args = kit.args(picked);
        boolean voice = kit.needsVoiceDownloads(picked);
        return (update ? "Please update the Hermes Companion server kit on this server" : "Please install the Hermes Companion server kit on this server")
                + " with exactly the modules I chose in the app: " + kit.names(picked) + ".\n\n"
                + "1. Receive the kit from my phone. Run this as one command; it waits up to 3 minutes for the app to upload the kit, "
                + "then exits. The app keeps retrying for 12 minutes, so if it times out just run it again:\n\n"
                + "```bash\n"
                + "timeout 170 python3 - <<'PY'\n"
                + "import http.server\n"
                + "TOKEN, OUT, done = \"" + token + "\", \"/tmp/hermes-companion-kit.zip\", []\n"
                + "class H(http.server.BaseHTTPRequestHandler):\n"
                + "    def do_GET(s): s.send_response(204); s.end_headers()\n"
                + "    def do_POST(s):\n"
                + "        if s.headers.get('X-Kit-Token') != TOKEN: s.send_response(403); s.end_headers(); return\n"
                + "        open(OUT, 'wb').write(s.rfile.read(int(s.headers.get('Content-Length', '0'))))\n"
                + "        s.send_response(200); s.end_headers(); done.append(1)\n"
                + "    def log_message(s, *a): pass\n"
                + "srv = http.server.HTTPServer(('0.0.0.0', " + port + "), H)\n"
                + "while not done: srv.handle_request()\n"
                + "print('received', OUT)\n"
                + "PY\n"
                + "```\n\n"
                + "2. Unzip /tmp/hermes-companion-kit.zip into a new temporary folder. It holds server-kit/ (the installer, with a README.md) and bridge/. "
                + "Work inside server-kit/. The installer is install.py (standard library only).\n"
                + "3. Preview first. Run `python3 install.py " + args + " --dry-run --yes` (add `--user` if you are not root and cannot use sudo; "
                + "use sudo if you are not root but can) and show me the summary: what it would create or change and anything it reports under Checks. "
                + "Stop and tell me if a check is an error.\n"
                + "4. When I say go ahead, run the same command without `--dry-run`. It backs up any file that exists and differs, keeps my edits to skills, "
                + "and restarts a service only when its own files changed. Do not edit the kit's files or add options I did not ask for"
                + (voice ? " — except ask me whether to add `--with-deps` (it creates Python environments and downloads packages), `--download-models` (about 450 MB of voices) and `--stt-device gpu` if I have an NVIDIA GPU" : "") + ".\n"
                + "5. If it prints \"Left for you\", do those steps or tell me exactly what remains (for example registering the phone MCP server). "
                + (picked.contains("proxmox") ? "For the Proxmox module ask me for the host as user@host and a read-only SSH key path, then pass `--pve-ssh` and `--pve-key`. " : "")
                + "Ask me for the push relay URL and key only if I mention them; pass them with `--set HERMES_RELAY_URL=... --set HERMES_RELAY_KEY=...`.\n"
                + "6. Run `python3 install.py verify` and check that port 8791 is reachable from my phone's network; change the firewall only if I agree.\n"
                + "7. Delete the temporary files, then give me a short summary of what was installed and anything left for me to do.\n\n"
                + "If a command fails, show me the error and stop instead of working around it.";
    }

    /** Polls the receiver until it listens, then uploads the kit once (12 minutes at most, on its own thread). */
    private static void upload(Context c, String host, int port, String token) {
        new Thread(() -> {
            String url = "http://" + host + ":" + port + "/";
            long deadline = System.currentTimeMillis() + 12 * 60_000L;
            String result = "Hermes didn't open the kit receiver in time. Ask it to run step 1 again, then retry from Settings.";
            try {
                byte[] kit = kit(c);
                while (System.currentTimeMillis() < deadline) {
                    if (listening(url)) {
                        HttpURLConnection p = (HttpURLConnection) new URL(url).openConnection();
                        p.setConnectTimeout(4000); p.setReadTimeout(30_000); p.setDoOutput(true);
                        p.setRequestMethod("POST"); p.setRequestProperty("X-Kit-Token", token);
                        p.setRequestProperty("Content-Type", "application/zip"); p.setFixedLengthStreamingMode(kit.length);
                        try (OutputStream o = p.getOutputStream()) { o.write(kit); }
                        int code = p.getResponseCode(); p.disconnect();
                        result = code == 200 ? "Server kit sent to Hermes" : "Kit upload refused (HTTP " + code + ")";
                        break;
                    }
                    Thread.sleep(3000);
                }
            } catch (Exception e) { Log.w(TAG, "kit upload", e); result = "Kit upload failed: " + HermesApi.friendly(e); }
            final String message = result;
            new Handler(Looper.getMainLooper()).post(() -> Toast.makeText(c, message, Toast.LENGTH_LONG).show());
        }, "server-kit-upload").start();
    }

    private static boolean listening(String url) {
        try {
            HttpURLConnection g = (HttpURLConnection) new URL(url).openConnection();
            g.setConnectTimeout(2000); g.setReadTimeout(3000);
            int code = g.getResponseCode(); g.disconnect();
            return code == 204;
        } catch (Exception e) { return false; }
    }

    private static byte[] kit(Context c) throws Exception {
        try (InputStream in = c.getAssets().open("server-kit.zip")) {
            ByteArrayOutputStream out = new ByteArrayOutputStream(); byte[] buf = new byte[16384]; int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
            return out.toByteArray();
        }
    }

    static String host(String endpoint) {
        try { return new URL(endpoint).getHost(); } catch (Exception e) { return ""; }
    }

    private static String token() {
        byte[] b = new byte[12]; new SecureRandom().nextBytes(b);
        StringBuilder s = new StringBuilder();
        for (byte x : b) s.append(String.format(Locale.ROOT, "%02x", x));
        return s.toString();
    }
}
