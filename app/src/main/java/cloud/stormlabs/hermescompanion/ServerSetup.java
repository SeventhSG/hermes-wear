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

    /** Opens a "Server setup" chat: Hermes interviews the user about what applies to them (default), or installs the modules
     *  the user ticks; {@code opened} runs once the chat starts. */
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
                Sheets.actions(a, update ? "Update the server pack" : "Set up your server", "Hermes does the work in a chat and asks before changing anything.",
                        Sheets.Item.of(R.drawable.ic_settings, Ui.BLUE, "Set it up with Hermes", "Hermes checks your server and asks what you want, one question at a time",
                                () -> start(a, store, host, (token, port) -> guided(token, port, update), opened)),
                        Sheets.Item.of(R.drawable.ic_file, Ui.BLUE_DARK, "Choose modules myself", "Tick the modules, Hermes installs exactly those",
                                () -> Sheets.kitModules(a, kit, initial, host, update, chosen -> {
                                    java.util.Set<String> picked = kit.normalize(chosen);
                                    store.kitModules(KitModules.toCsv(picked));
                                    start(a, store, host, (token, port) -> prompt(token, port, kit, picked, update), opened);
                                })));
            });
        }, "server-setup-probe").start();
    }

    /** One tap from Settings when the gateway plugin is missing or broken: Hermes (re)installs just that module. */
    static void repairGateway(Activity a, SettingsStore store) {
        KitModules kit;
        try { kit = KitModules.load(a); }
        catch (Exception e) { Toast.makeText(a, "The bundled server pack is damaged: " + e.getMessage(), Toast.LENGTH_LONG).show(); return; }
        java.util.Set<String> picked = kit.normalize(java.util.Collections.singleton("gateway-platform"));
        start(a, store, host(store.endpoint()), (token, port) -> prompt(token, port, kit, picked, true) + RESTART_LAST, null);
    }

    /** Restarting hermes-gateway ends the chat doing the work, so it must be the very last step. */
    static final String RESTART_LAST = "\n\nRestarting hermes-gateway ends this chat, so never do it mid-way: give me the summary first, then "
            + "schedule it with `systemd-run --on-active=10 systemctl restart hermes-gateway`. The app reconnects by itself.";

    private static void start(Activity a, SettingsStore store, String host, java.util.function.BiFunction<String, Integer, String> prompt, Runnable opened) {
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
                            .putExtra(ChatActivity.EXTRA_PROMPT, prompt.apply(token, port))
                            .putExtra(ChatActivity.EXTRA_AUTOSEND, true)
                            .putExtra(MainActivity.EXTRA_PROFILE_SECRET, store.intentSecret())
                            // A fresh task: from onboarding the old task's MainActivity is gone, and delivering to it lost the chat.
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK));
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
                + "1. " + receive(token, port)
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

    /** Step 1 of both prompts: a one-shot receiver the app uploads the bundled kit to. */
    private static String receive(String token, int port) {
        return "Receive the kit from my phone. Run this as one command; it waits up to 3 minutes for the app to upload the kit, "
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
                + "```\n\n";
    }

    /** The guided setup: Hermes surveys the server itself, asks only what it can't find out, then installs what applies. */
    static String guided(String token, int port, boolean update) {
        return "Please " + (update ? "update" : "set up") + " the Hermes Companion server kit on this server for me, as a guided setup. "
                + "You do the work; I only answer questions and approve changes.\n\n"
                + "How to ask me things: use the clarify tool, one question per call, with 2 to 5 short choices whenever the answer is one "
                + "of a few options (multi-select when several can apply). Put a one-line explanation of what each option means in the "
                + "question itself (what it does, what it needs, roughly how much disk, RAM or download). Never ask what you can find out "
                + "yourself, never ask for passwords or keys in chat (ask for a file path instead), and skip questions that don't apply to "
                + "this server. If you have no clarify tool, ask the same question as a short numbered list and wait for my reply.\n\n"
                + "1. " + receive(token, port)
                + "If the upload never arrives after two tries (for example my phone reaches you through a public URL that doesn't pass "
                + "that port), tell me and ask whether to download the same kit from https://github.com/storm546/hermes-companion-app instead.\n"
                + "2. Unzip it into a new temporary folder and work inside server-kit/. Read README.md, then run `python3 install.py list --json`: "
                + "it and the README are the source of truth for the modules, what each needs and how to install it (the dry run in step 5 checks prerequisites).\n"
                + "3. Look around before asking, read-only: OS, whether you are root or can sudo, systemd, an NVIDIA GPU (nvidia-smi), "
                + "Tailscale, the firewall, which agent CLIs exist (codex, claude), Proxmox (pveversion, or an SSH alias), and media "
                + "servers already running (Jellyfin, Emby, Plex, Sonarr, Radarr, Prowlarr: docker ps, listening ports). "
                + (update ? "Also run `python3 install.py status --json` to see what is installed now and keep those modules unless I say otherwise. " : "")
                + "Change nothing yet.\n"
                + "4. Interview me, one topic at a time, only about what applies. The hub is always installed (push, the System screen, "
                + "files). Then ask: which skills I want (multi-select); phone actions from other chats (phone-mcp); app chats as gateway "
                + "chats so risky commands and questions show as cards (gateway-platform, recommended); slash commands; Codex or Claude Code "
                + "from the phone (only if their CLI is here, or ask if I plan to install one); Proxmox (only if you found one; then ask for "
                + "user@host and a read-only SSH key path); voice servers (none, speech-to-text, text-to-speech, or both; GPU or CPU; say "
                + "they need --with-deps and about 450 MB of models); media (if you found media servers, tell me which and that I connect "
                + "them in the app under Settings, Modules, Media once the hub runs; the kit installs nothing for media). Respect a no: "
                + "never install something I declined.\n"
                + "5. Sum up the plan in a short list, run `python3 install.py <your options> --dry-run --yes` (add `--user` if you are not "
                + "root and cannot sudo; use sudo if you can) and show me what it would change and any check that is an error. Then ask with "
                + "clarify: Install, Change something, or Cancel.\n"
                + "6. On Install, run the same command without --dry-run. It backs up what it replaces and keeps my edits. Do the steps it "
                + "prints under \"Left for you\", except restarting hermes-gateway: that ends this chat, so it comes last (step 8).\n"
                + "7. Run `python3 install.py verify`, check that port 8791 answers from my phone's network (change the firewall only if I "
                + "agree), delete the temporary files, and give me a short summary: what is installed, what I should switch on in the app "
                + "(Settings, Modules), and anything left for me.\n"
                + "8. If a gateway restart is needed, say so in the summary, then schedule it so this reply finishes first: "
                + "`systemd-run --on-active=10 systemctl restart hermes-gateway` (or tell me the command if you can't). The app reconnects "
                + "on its own within a minute.\n\n"
                + "If a command fails, show me the error and ask what to do (Retry, Skip this module, Stop) instead of working around it. "
                + "Don't edit the kit's files or install anything it doesn't include.";
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
