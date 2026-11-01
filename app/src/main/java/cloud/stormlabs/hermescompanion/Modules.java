package cloud.stormlabs.hermescompanion;

import android.Manifest;
import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Everything outside base Hermes chat is a module: the companion hub, server speech-to-text and voice, the wake word,
 * the phone agent, media, the capture inbox, the Codex remote, homelab status and waking the voice machine. Each can be
 * switched off (screens then leave it out) and set up from one place: the first-run setup and Settings › Modules.
 * Modules that already have a setting keep it as their switch, so nothing is stored twice.
 */
final class Modules {
    private Modules() {}

    static final String HUB = "hub", STT = "stt", TTS = "tts", WAKE_WORD = "wake_word", PHONE = "phone_agent", MEDIA = "media",
            ESSENTIALS = "essentials", CODEX = "codex", HOMELAB = "homelab", KIT_WAKE = "kit_wake", TERMINAL = "terminal";

    /** id, title, what it gives you, icon, whether it has an on/off switch (the hub is set up or not). */
    static final Object[][] ALL = {
            {HUB, "Companion hub", "Push notifications, files in chat, the System screen; the other server modules go through it", R.drawable.ic_server, false},
            {STT, "Server speech-to-text", "Whisper or Parakeet on your server; off uses this phone's recognizer", R.drawable.ic_mic, true},
            {TTS, "Server voice", "Natural voices from a Wyoming TTS server; off uses the phone's voice", R.drawable.ic_speaker, true},
            {WAKE_WORD, "“Hey Hermes” wake word", "Start talking from any app, hands-free", R.drawable.ic_voice, true},
            {PHONE, "Phone agent", "Hermes can open apps, read and tap the screen, call and message when you ask", R.drawable.ic_phone, true},
            {MEDIA, "Media", "Sonarr, Radarr, qBittorrent, Prowlarr and media servers (Jellyfin, Emby, Plex, Ampache)", R.drawable.ic_movie, true},
            {ESSENTIALS, "Capture inbox", "Essentials: notes, photos and voice memos filed later", R.drawable.ic_compose, true},
            {CODEX, "Codex remote", "Follow and steer Codex sessions running on your server", R.drawable.ic_session, true},
            {HOMELAB, "Homelab status", "Proxmox nodes, VMs and backups on the System screen", R.drawable.ic_pulse, true},
            {KIT_WAKE, "Wake the voice machine", "Wake-on-LAN for a sleeping speech server before you speak", R.drawable.ic_bolt, true},
            {TERMINAL, "Terminal", "Shells and ssh sessions on your server with tabs, saved hosts and snippets; over Tailscale only", R.drawable.ic_terminal, true},
    };

    private static final ExecutorService NET = Executors.newSingleThreadExecutor();

    /** Whether the module is on. Defaults keep what existing installs already use. */
    static boolean on(Context c, String id) {
        SettingsStore s = new SettingsStore(c);
        switch (id) {
            case HUB: return !s.hubUrl().isEmpty();
            case STT: return !"phone".equals(s.speechRecognition());
            case TTS: return s.serverVoice();
            case WAKE_WORD: return s.wakeEnabled();
            case PHONE: return s.remotePhoneActions();
            case TERMINAL: return s.moduleOnDefault(id, false); // a shell: only after you switch it on
            default: return s.moduleOn(id);
        }
    }

    static void set(Activity a, String id, boolean on) {
        SettingsStore s = new SettingsStore(a);
        switch (id) {
            case STT: s.speechRecognition(on ? "auto" : "phone"); break;
            case TTS: s.serverVoice(on); break;
            case WAKE_WORD:
                if (on && a.checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED)
                    a.requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO}, 41);
                s.wakeEnabled(on); WakeWordService.ensure(a); break;
            case PHONE: s.remotePhoneActions(on); break;
            default: s.moduleOn(id, on);
        }
    }

    /** One line on where the module stands: the address in use, or what is missing. */
    static String status(Context c, String id) {
        SettingsStore s = new SettingsStore(c);
        switch (id) {
            case HUB: return s.hubUrl().isEmpty() ? "Not connected" : "Connected · " + host(s.hubUrl());
            case STT: return "phone".equals(s.speechRecognition()) ? "Off · this phone recognizes speech" : "Server " + s.whisperAddress();
            case TTS: return !s.serverVoice() ? "Off · phone voice" : "Server " + (s.ttsAddress().isEmpty() ? ServerVoice.defaultFor(s.endpoint()) : s.ttsAddress());
            case WAKE_WORD: return s.wakeEnabled() ? "Listening for “Hey Hermes”" : "Off";
            case PHONE: return HermesAccessibilityService.listed(c) ? "Screen control allowed" : "Screen control not allowed yet: tap to set up";
            case MEDIA: {
                List<MediaModules.Module> m = MediaModules.cached(c);
                if (m.isEmpty()) return "Nothing set up yet: tap to add a service";
                StringBuilder b = new StringBuilder();
                for (MediaModules.Module x : m) b.append(b.length() == 0 ? "" : ", ").append(x.name);
                return b.toString();
            }
            case ESSENTIALS: return s.essentialsReady() ? host(s.essentialsBase()) : "Not set up";
            case CODEX: for (SettingsStore.Profile p : s.profiles()) if ("Codex".equals(p.type)) return "Paired"; return "Not paired: tap to pair over Tailscale";
            case HOMELAB: return s.hubUrl().isEmpty() ? "Needs the hub" : "Set up on the server (server kit: Proxmox)";
            case KIT_WAKE: return s.hubUrl().isEmpty() ? "Needs the hub" : "Tap to choose the machine";
            case TERMINAL: return s.hubUrl().isEmpty() ? "Needs the hub" : s.moduleOnDefault(id, false) ? "On · tap to open" : "Off";
            default: return "";
        }
    }

    private static String host(String url) { try { return new java.net.URL(url).getHost(); } catch (Exception e) { return url; } }

    /** Draws one row per module (switch + status; tap for setup). {@code changed} runs after anything changes. */
    static void render(Activity a, LinearLayout box, Runnable changed) {
        for (Object[] m : ALL) {
            String id = (String) m[0];
            LinearLayout row = new LinearLayout(a);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setPadding(Ui.dp(a, 4), Ui.dp(a, 8), 0, Ui.dp(a, 8));
            row.addView(Ui.iconTile(a, (Integer) m[3], Ui.iconColor(Ui.BLUE_DARK)), new LinearLayout.LayoutParams(Ui.dp(a, 38), Ui.dp(a, 38)));
            LinearLayout copy = new LinearLayout(a);
            copy.setOrientation(LinearLayout.VERTICAL);
            copy.setPadding(Ui.dp(a, 14), 0, Ui.dp(a, 6), 0);
            copy.addView(Ui.text(a, (String) m[1], 15, Ui.TEXT, false));
            TextView what = Ui.text(a, (String) m[2], 12, Ui.SUB, false);
            copy.addView(what);
            TextView st = Ui.text(a, status(a, id), 12, on(a, id) ? Ui.BLUE : Ui.FAINT, false);
            copy.addView(st);
            row.addView(copy, new LinearLayout.LayoutParams(0, -2, 1));
            if ((Boolean) m[4]) {
                HermesSwitch sw = new HermesSwitch(a);
                sw.setChecked(on(a, id));
                sw.setContentDescription(m[1] + ", " + (on(a, id) ? "On" : "Off"));
                sw.setListener(v -> { set(a, id, v); st.setText(status(a, id)); st.setTextColor(v ? Ui.BLUE : Ui.FAINT); if (changed != null) changed.run(); });
                row.addView(sw, new LinearLayout.LayoutParams(Ui.dp(a, 44), Ui.dp(a, 44)));
            }
            row.setBackground(Ui.ripple(a, Ui.BAR, 12));
            row.setOnClickListener(v -> setup(a, id, () -> { st.setText(status(a, id)); st.setTextColor(on(a, id) ? Ui.BLUE : Ui.FAINT); if (changed != null) changed.run(); }));
            box.addView(row, new LinearLayout.LayoutParams(-1, -2));
        }
    }

    /** The setup step for one module; {@code done} runs on the UI thread when something was saved. */
    static void setup(Activity a, String id, Runnable done) {
        SettingsStore s = new SettingsStore(a);
        boolean hub = !s.hubUrl().isEmpty();
        switch (id) {
            case HUB:
                if (!hub) a.startActivity(new Intent(a, ConnectionActivity.class));
                else ServerSetup.offer(a, s, null);
                return;
            case STT:
                Sheets.input(a, "Speech-to-text server", "host:port of a Wyoming server (Whisper or Parakeet), or https://your-domain to go through the hub from anywhere. Empty uses your Hermes server, and https when its voice port is out of reach.",
                        s.customWhisperAddress(), "host:10300", v -> {
                            s.whisperAddress(v); set(a, STT, true); done.run();
                            test(a, "Testing " + s.whisperAddress() + "…", () -> SpeechRecognition.test(s));
                        }, "Save", true);
                return;
            case TTS:
                Sheets.input(a, "Voice server", "host:port of a Wyoming TTS server (Piper, Kokoro). Empty uses your Hermes server on port 10200.",
                        s.ttsAddress(), "host:10200", v -> {
                            s.ttsAddress(v); set(a, TTS, true); done.run();
                            String address = s.ttsAddress().isEmpty() ? ServerVoice.defaultFor(s.endpoint()) : s.ttsAddress();
                            test(a, "Testing " + address + "…", () -> ServerVoice.probe(address));
                        }, "Save", true);
                return;
            case WAKE_WORD: set(a, WAKE_WORD, !on(a, WAKE_WORD)); done.run(); return;
            case PHONE: a.startActivity(new Intent(android.provider.Settings.ACTION_ACCESSIBILITY_SETTINGS)); return;
            case MEDIA: if (needsHub(a, hub)) return; mediaSetup(a, done); return;
            case ESSENTIALS:
                if (!hub) { a.startActivity(new Intent(a, ConnectionActivity.class)); return; }
                test(a, "Asking the hub…", () -> {
                    String[] cfg = new HubApi(s.hubUrl(), s.hubKey()).essentialsConfig();
                    s.essentialsBase(cfg[0]); s.essentialsToken(cfg[1]);
                    a.runOnUiThread(done);
                    return "Capture inbox set up";
                });
                return;
            case CODEX:
                test(a, "Pairing Codex over Tailscale…", () -> { String err = Pairing.addCodex(s); if (err != null) throw new IllegalStateException(err); a.runOnUiThread(done); return "Codex paired"; });
                return;
            case HOMELAB: if (needsHub(a, hub)) return; ServerSetup.offer(a, s, null); return;
            case KIT_WAKE: if (needsHub(a, hub)) return; wakeSetup(a, done); return;
            case TERMINAL: if (needsHub(a, hub)) return; s.moduleOn(id, true); done.run(); a.startActivity(new Intent(a, TerminalActivity.class)); return;
            default:
        }
    }

    private static boolean needsHub(Activity a, boolean hub) {
        if (!hub) Toast.makeText(a, "Connect the companion hub first", Toast.LENGTH_LONG).show();
        return !hub;
    }

    private interface Job { String run() throws Exception; }

    /** Runs a check off the UI thread and toasts the outcome. */
    private static void test(Activity a, String start, Job job) {
        Toast.makeText(a, start, Toast.LENGTH_SHORT).show();
        NET.execute(() -> {
            String r;
            try { r = job.run(); } catch (Exception e) { r = HermesApi.friendly(e); }
            String msg = r;
            a.runOnUiThread(() -> Toast.makeText(a, msg, Toast.LENGTH_LONG).show());
        });
    }

    private static EditText field(Activity a, LinearLayout f, String label, String value, String hint, boolean secret) {
        TextView l = Ui.label(a, label, Ui.SUB);
        l.setPadding(Ui.dp(a, 4), Ui.dp(a, 10), 0, Ui.dp(a, 4));
        f.addView(l);
        EditText e = new EditText(a);
        e.setText(value); e.setHint(hint); e.setHintTextColor(Ui.FAINT); e.setTextColor(Ui.TEXT); e.setSingleLine(true);
        e.setInputType(secret ? InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD : InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        e.setBackground(Ui.round(a, Ui.RAISED, 10)); e.setPadding(Ui.dp(a, 12), Ui.dp(a, 10), Ui.dp(a, 12), Ui.dp(a, 10));
        f.addView(e, new LinearLayout.LayoutParams(-1, -2));
        return e;
    }

    private static LinearLayout form(Activity a) {
        LinearLayout f = new LinearLayout(a);
        f.setOrientation(LinearLayout.VERTICAL);
        f.setPadding(Ui.dp(a, 16), 0, Ui.dp(a, 16), Ui.dp(a, 8));
        return f;
    }

    /** Lists every media service the hub supports; picking one asks for its URL and key, and the hub saves it only if it answers. */
    private static void mediaSetup(Activity a, Runnable done) {
        SettingsStore s = new SettingsStore(a);
        HubApi hub = new HubApi(s.hubUrl(), s.hubKey());
        NET.execute(() -> {
            try {
                JSONArray cat = hub.media("GET", "config", null).optJSONArray("modules");
                a.runOnUiThread(() -> {
                    List<Sheets.Item> items = new ArrayList<>();
                    for (int i = 0; cat != null && i < cat.length(); i++) {
                        JSONObject m = cat.optJSONObject(i);
                        String mid = m.optString("id"), name = m.optString("name");
                        boolean configured = m.optBoolean("configured");
                        items.add(Sheets.Item.of(m.optString("kind").equals("server") ? R.drawable.ic_play : R.drawable.ic_movie, MediaModules.color(mid), name,
                                configured ? "Set up · tap to change or remove" : "Not set up", () -> mediaService(a, hub, mid, name, m.optBoolean("needs_key"), configured, done))
                                .checked(configured));
                    }
                    Sheets.actions(a, "Media services", "The hub keeps the keys; each is tested before it is saved", items.toArray(new Sheets.Item[0]));
                });
            } catch (Exception e) {
                a.runOnUiThread(() -> Toast.makeText(a, "The hub can't set up media yet (update the server kit): " + HermesApi.friendly(e), Toast.LENGTH_LONG).show());
            }
        });
    }

    private static void mediaService(Activity a, HubApi hub, String id, String name, boolean needsKey, boolean configured, Runnable done) {
        LinearLayout f = form(a);
        String hint = "plex".equals(id) ? "http://192.168.0.20:32400" : "emby".equals(id) ? "http://192.168.0.20:8096/emby" : "http://192.168.0.20:port";
        EditText url = field(a, f, "Address", "", hint, false);
        EditText key = needsKey ? field(a, f, "plex".equals(id) ? "Plex token" : "API key", "", "From the service's settings", true) : null;
        if (configured) {
            TextView remove = Ui.ghostButton(a, "Remove " + name, Ui.RED);
            remove.setOnClickListener(v -> save(a, hub, id, "", "", done));
            f.addView(remove);
        }
        Sheets.form(a, name, f, "Test and save", () -> {
            String u = url.getText().toString().trim();
            if (u.isEmpty()) { url.setError("Needed"); return false; }
            save(a, hub, id, u, key == null ? "" : key.getText().toString().trim(), done);
            return true;
        });
    }

    private static void save(Activity a, HubApi hub, String id, String url, String key, Runnable done) {
        test(a, url.isEmpty() ? "Removing…" : "Testing " + url + "…", () -> {
            hub.media("POST", "config", new JSONObject().put("id", id).put("url", url).put("key", key));
            MediaModules.refresh(a, hub);
            a.runOnUiThread(done);
            return url.isEmpty() ? "Removed" : "Works, saved on the hub";
        });
    }

    /** Which machine Wake-on-LAN wakes (the hub sends the packet, since the phone may be away from home). */
    private static void wakeSetup(Activity a, Runnable done) {
        SettingsStore s = new SettingsStore(a);
        HubApi hub = new HubApi(s.hubUrl(), s.hubKey());
        NET.execute(() -> {
            JSONObject cur;
            try { cur = hub.wakeConfig(); } catch (Exception e) { cur = new JSONObject(); }
            JSONObject c = cur;
            a.runOnUiThread(() -> {
                LinearLayout f = form(a);
                EditText mac = field(a, f, "MAC address", c.optString("mac"), "D8:BB:C1:37:A8:89", false);
                EditText host = field(a, f, "Machine IP or name", c.optString("host"), "192.168.0.150", false);
                Sheets.form(a, "Wake the voice machine", f, "Save", () -> {
                    String m = mac.getText().toString().trim(), h = host.getText().toString().trim();
                    test(a, "Saving…", () -> { hub.wakeConfigSet(m, h); a.runOnUiThread(done); return m.isEmpty() ? "Cleared" : "Saved on the hub"; });
                    return true;
                });
            });
        });
    }

    /** Settings › Modules: the same rows as the first-run step. */
    static void open(Activity a) { a.startActivity(new Intent(a, ModulesActivity.class)); }
}
