package cloud.stormlabs.hermescompanion;

import android.content.Context;
import android.content.SharedPreferences;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.Base64;

import org.json.JSONArray;
import org.json.JSONObject;

import java.nio.charset.StandardCharsets;
import java.security.KeyStore;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

public final class SettingsStore {
    private static final String PREFS = "hermes_companion";
    private static final String KEY_ALIAS = "hermes_companion_api_key";
    /** Appended to the legacy pref name for any server type other than Hermes. */
    private static final String CLAUDE_CODE_SUFFIX = ".claude_code", CODEX_SUFFIX = ".codex";
    private final SharedPreferences prefs;
    /** Debug builds (what the test and user phones run for now) default to Full access. */
    private final boolean debuggable;

    SettingsStore(SharedPreferences prefs) { this.prefs = prefs; this.debuggable = false; }

    public SettingsStore(Context context) {
        prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        debuggable = (context.getApplicationInfo().flags & android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE) != 0;
    }

    /**
     * Per-server-type storage key. Hermes keeps the original, unsuffixed name so upgrades from
     * before multi-backend support keep reading the same endpoint/api_key/session values.
     */
    static String keyFor(String baseKey, String serverType) {
        if ("Hermes".equals(serverType)) return baseKey;
        if ("Codex".equals(serverType)) return baseKey + CODEX_SUFFIX;
        return "Claude Code".equals(serverType) ? baseKey + CLAUDE_CODE_SUFFIX : baseKey + ".unsupported";
    }

    public String serverType() { return prefs.getString("server_type", "Hermes"); }
    /** This profile has an address and key saved for that harness (Hermes, Claude Code, Codex). */
    public boolean harnessReady(String type) { return !prefs.getString(keyFor("endpoint", type), "").isEmpty() && !prefs.getString(keyFor("api_key", type), "").isEmpty(); }
    public void serverType(String value) { prefs.edit().putString("server_type", value).apply(); }
    public String endpoint() { return UrlUtils.normalize(prefs.getString(keyFor("endpoint", serverType()), "")); }
    public void endpoint(String value) { prefs.edit().putString(keyFor("endpoint", serverType()), UrlUtils.normalize(value)).apply(); Reach.configure(this); }
    public String sessionId() {
        String key = keyFor("session", serverType());
        String current = prefs.getString(key, "");
        if (current == null || current.isEmpty()) { current = SessionIds.normalize(""); prefs.edit().putString(key, current).apply(); }
        return current;
    }
    /** Title last seen for a conversation this phone opened; empty for ids it never opened (e.g. a generated one). */
    public String sessionTitle(String id) { return prefs.getString("title:" + id, ""); }
    public void sessionTitle(String id, String title) { if (id != null && !id.isEmpty() && title != null && !title.trim().isEmpty()) prefs.edit().putString("title:" + id, title.trim()).apply(); }

    public void sessionId(String value) { prefs.edit().putString(keyFor("session", serverType()), SessionIds.normalize(value)).apply(); }
    /* ---- profiles ----
       The active profile lives in the keys every screen already reads (server_type plus that type's
       endpoint/api_key/session slot, and assistant_session); other profiles wait in "profiles" with
       their keys still sealed. Switching swaps them. */
    public static final class Profile {
        public final String id, name, type, endpoint;
        Profile(String id, String name, String type, String endpoint) { this.id = id; this.name = name; this.type = type; this.endpoint = endpoint; }
    }
    public static final class Connection {
        public final String type, endpoint, key, session;
        Connection(String type, String endpoint, String key) { this(type, endpoint, key, ""); }
        Connection(String type, String endpoint, String key, String session) { this.type = type; this.endpoint = endpoint; this.key = key; this.session = session; }
    }
    public String profileId() { return prefs.getString("profile_id", "default"); }
    /** Returns null when the profile was removed. Saved keys are unsealed only for this lookup. */
    public Connection connection(String id) {
        JSONObject profile = profileJson(prefs.getAll(), id);
        return profile == null ? null : new Connection(profile.optString("type", "Hermes"),
                UrlUtils.normalize(profile.optString("endpoint")), unsealPacked(profile.optString("key"), KEY_ALIAS), profile.optString("session"));
    }

    /** One preference snapshot prevents a profile switch from mixing endpoint and credentials. */
    private static JSONObject profileJson(java.util.Map<String, ?> values, String id) {
        if (id == null) return null;
        try {
            String active = (String) values.get("profile_id");
            if (id.equals(active == null ? "default" : active)) {
                String type = (String) values.get("server_type");
                if (type == null) type = "Hermes";
                return new JSONObject().put("id", id).put("type", type)
                        .put("endpoint", values.get(keyFor("endpoint", type)))
                        .put("key", values.get(keyFor("api_key", type)))
                        .put("session", values.get(keyFor("session", type)))
                        .put("hub_url", values.get("hub_url")).put("hub_key", values.get("hub_key"));
            }
            Object packed = values.get("profiles");
            JSONArray saved = new JSONArray(packed == null ? "[]" : packed.toString());
            for (int i = 0; i < saved.length(); i++) {
                JSONObject p = saved.optJSONObject(i);
                if (p != null && id.equals(p.optString("id"))) return p;
            }
        } catch (Exception ignored) { }
        return null;
    }

    /** The hub's root profile, independent of the currently selected profile. */
    public String mainProfileId() { return mainProfileId(profileId()); }
    public String mainProfileId(String id) { return mainProfileId(prefs.getAll(), id); }
    private static String mainProfileId(java.util.Map<String, ?> values, String id) {
        JSONObject source = profileJson(values, id);
        if (source == null) return null;
        String root = Profiles.serverRoot(source.optString("endpoint"));
        java.util.List<String> ids = new java.util.ArrayList<>();
        String active = (String) values.get("profile_id");
        ids.add(active == null ? "default" : active);
        try {
            Object packed = values.get("profiles");
            JSONArray saved = new JSONArray(packed == null ? "[]" : packed.toString());
            for (int i = 0; i < saved.length(); i++) if (saved.optJSONObject(i) != null) ids.add(saved.optJSONObject(i).optString("id"));
        } catch (Exception ignored) { }
        for (String candidate : ids) {
            JSONObject p = profileJson(values, candidate);
            if (p != null && "Hermes".equals(p.optString("type", "Hermes"))
                    && root.equals(UrlUtils.normalize(p.optString("endpoint")))) return candidate;
        }
        return null;
    }

    /** Resolve credentials off the UI thread, from one snapshot of the task's original profile. */
    /** The FCM endpoint last registered with this hub ("" if none), so we register once per token. */
    public String fcmRegistered(String hub) { return prefs.getString("fcm_registered:" + hub, ""); }
    public void fcmRegistered(String hub, String endpoint) { prefs.edit().putString("fcm_registered:" + hub, endpoint).apply(); }
    public Connection hubConnection(String id) {
        java.util.Map<String, ?> values = prefs.getAll();
        JSONObject source = profileJson(values, id);
        if (source == null) return null;
        String override = source.optString("hub_url");
        String pairedKey = source.optString("hub_key");
        List<HubCredentials.Profile> candidates = new ArrayList<>();
        Object activeId = values.get("profile_id");
        candidates.add(hubProfile(profileJson(values, activeId == null ? "default" : activeId.toString())));
        try {
            Object packed = values.get("profiles");
            JSONArray saved = new JSONArray(packed == null ? "[]" : packed.toString());
            for (int i = 0; i < saved.length(); i++) if (saved.optJSONObject(i) != null) candidates.add(hubProfile(saved.optJSONObject(i)));
        } catch (Exception ignored) { }
        HubCredentials.Profile root = HubCredentials.select(hubProfile(source), candidates);
        if (root == null && !pairedKey.isEmpty()) return new Connection("Hermes",
                override.isEmpty() ? HubApi.defaultFor(source.optString("endpoint")) : UrlUtils.normalize(override), unsealPacked(pairedKey, KEY_ALIAS));
        return root == null ? null : new Connection("Hermes", override.isEmpty() ? root.hubUrl() : UrlUtils.normalize(override),
                unsealPacked(root.key, KEY_ALIAS));
    }

    private static HubCredentials.Profile hubProfile(JSONObject p) {
        return new HubCredentials.Profile(p.optString("type", "Hermes"), p.optString("endpoint"), p.optString("hub_url"), p.optString("key"));
    }

    public String profileName() {
        String n = prefs.getString("profile_name", "");
        // A /p/<name> profile ("Pentest") is a Hermes profile: under Claude Code or Codex the harness is the identity.
        if (!"Hermes".equals(serverType()) && (isHermesSubProfile() || n == null || n.trim().isEmpty() || "Hermes".equals(n))) return serverType();
        return n == null || n.trim().isEmpty() ? Profiles.defaultName(endpoint()) : n;
    }

    /** The active profile talks to a /p/<name> Hermes profile rather than the gateway's main one. */
    public boolean isHermesSubProfile() {
        String hermes = UrlUtils.normalize(prefs.getString(keyFor("endpoint", "Hermes"), ""));
        return !hermes.equals(Profiles.serverRoot(hermes));
    }

    /** Saved connections that belong to the active harness; Hermes profiles mean nothing to Claude Code or Codex. */
    public List<Profile> profilesForHarness() {
        List<Profile> out = new ArrayList<>();
        for (Profile p : profiles()) if (p.type.equals(serverType())) out.add(p);
        return out;
    }
    public void profileName(String v) { prefs.edit().putString("profile_name", v == null ? "" : v.trim()).apply(); }

    /** Every profile, the active one first. */
    public List<Profile> profiles() {
        List<Profile> out = new ArrayList<>();
        out.add(new Profile(profileId(), profileName(), serverType(), endpoint()));
        JSONArray saved = savedProfiles();
        for (int i = 0; i < saved.length(); i++) {
            JSONObject o = saved.optJSONObject(i);
            if (o == null) continue;
            String name = o.optString("name"), url = o.optString("endpoint");
            out.add(new Profile(o.optString("id"), name.isEmpty() ? Profiles.defaultName(url) : name, o.optString("type", "Hermes"), url));
        }
        return out;
    }

    /** Parks the active profile and makes a new one active, starting from a fresh conversation. */
    public void addProfile(String name, String type, String endpoint, String key) {
        JSONArray saved = savedProfiles();
        saved.put(activeAsJson());
        String sealed;
        try { sealed = seal(Keys.clean(key), KEY_ALIAS); }
        catch (Exception e) { throw new IllegalStateException("Could not protect API key", e); }
        SharedPreferences.Editor e = prefs.edit();
        clearSlots(e);
        e.remove("hub_url");
        e.putString("profiles", saved.toString())
                .putString("profile_id", UUID.randomUUID().toString()).putString("profile_name", name == null ? "" : name.trim())
                .putString("server_type", type).putString(keyFor("endpoint", type), UrlUtils.normalize(endpoint))
                .putString(keyFor("api_key", type), sealed).remove("assistant_session")
                .apply();
    }

    /** Saves a profile without switching to it ("Add all" from the server's list). */
    public void saveProfile(String name, String type, String endpoint, String key) {
        JSONArray saved = savedProfiles();
        JSONObject o = new JSONObject();
        try {
            o.put("id", UUID.randomUUID().toString()).put("name", name == null ? "" : name.trim()).put("type", type)
                    .put("endpoint", UrlUtils.normalize(endpoint)).put("key", seal(Keys.clean(key), KEY_ALIAS));
        } catch (Exception e) { throw new IllegalStateException("Could not protect API key", e); }
        saved.put(o);
        prefs.edit().putString("profiles", saved.toString()).apply();
    }

    /**
     * The hub only accepts the server's main key; a /p/&lt;profile&gt; profile's own key gets 401
     * there, so push, events, search and ops would break while switched to one.
     */
    public String hubKey() {
        Connection hub = hubConnection(profileId());
        return hub == null ? "" : hub.key;
    }

    public void hubKey(String value) {
        try { prefs.edit().putString("hub_key", seal(Keys.clean(value), KEY_ALIAS)).apply(); }
        catch (Exception e) { throw new IllegalStateException("Could not protect hub key", e); }
    }

    /** False if {@code id} is already active or unknown. */
    public boolean switchProfile(String id) {
        if (id == null || id.equals(profileId())) return false;
        JSONArray saved = savedProfiles(), rest = new JSONArray();
        JSONObject target = null;
        for (int i = 0; i < saved.length(); i++) {
            JSONObject o = saved.optJSONObject(i);
            if (o == null) continue;
            if (target == null && id.equals(o.optString("id"))) target = o; else rest.put(o);
        }
        if (target == null) return false;
        rest.put(activeAsJson());
        String type = target.optString("type", "Hermes");
        SharedPreferences.Editor e = prefs.edit();
        clearSlots(e);
        e.putString("hub_url", target.optString("hub_url", ""));
        e.putString("hub_key", target.optString("hub_key", ""));
        JSONObject slots = target.optJSONObject("slots");
        for (String t : SERVER_TYPES) {
            JSONObject slot = slots != null ? slots.optJSONObject(t) : t.equals(type) ? target : null;
            if (slot == null) continue;
            e.putString(keyFor("endpoint", t), slot.optString("endpoint")).putString(keyFor("api_key", t), slot.optString("key"))
                    .putString(keyFor("session", t), slot.optString("session"));
        }
        e.putString("profiles", rest.toString())
                .putString("profile_id", id).putString("profile_name", target.optString("name")).putString("server_type", type)
                .putString("assistant_session", target.optString("assistant_session"))
                .apply();
        return true;
    }

    /** Drops parked profiles that point at the same server URL as the active one or an earlier parked one. */
    public int dedupeProfiles() {
        JSONArray saved = savedProfiles(), rest = new JSONArray();
        java.util.Set<String> seen = new java.util.HashSet<>();
        seen.add(serverType() + " " + endpoint());
        for (int i = 0; i < saved.length(); i++) {
            JSONObject o = saved.optJSONObject(i);
            if (o == null) continue;
            if (seen.add(o.optString("type", "Hermes") + " " + UrlUtils.normalize(o.optString("endpoint")))) rest.put(o);
        }
        int removed = saved.length() - rest.length();
        if (removed > 0) prefs.edit().putString("profiles", rest.toString()).apply();
        return removed;
    }

    /** Forgets a parked profile and its key; the active one can't be removed. */
    public void removeProfile(String id) {
        JSONArray saved = savedProfiles(), rest = new JSONArray();
        for (int i = 0; i < saved.length(); i++) {
            JSONObject o = saved.optJSONObject(i);
            if (o != null && !o.optString("id").equals(id)) rest.put(o);
        }
        prefs.edit().putString("profiles", rest.toString()).apply();
    }

    private JSONArray savedProfiles() {
        try { return new JSONArray(prefs.getString("profiles", "[]")); } catch (Exception e) { return new JSONArray(); }
    }

    /**
     * Every server type's slot belongs to the profile: the Server type picker switches type in place,
     * so snapshotting only the current type lost the other type's credentials on the next switch.
     */
    private static final String[] SERVER_TYPES = {"Hermes", "Claude Code", "Codex"};

    private JSONObject activeAsJson() {
        String type = serverType();
        JSONObject o = new JSONObject();
        try {
            JSONObject slots = new JSONObject();
            for (String t : SERVER_TYPES) {
                if (!prefs.contains(keyFor("endpoint", t)) && !prefs.contains(keyFor("api_key", t))) continue;
                slots.put(t, new JSONObject().put("endpoint", UrlUtils.normalize(prefs.getString(keyFor("endpoint", t), "")))
                        .put("key", prefs.getString(keyFor("api_key", t), "")).put("session", prefs.getString(keyFor("session", t), "")));
            }
            o.put("id", profileId()).put("name", prefs.getString("profile_name", "")).put("type", type).put("endpoint", endpoint())
                    .put("key", prefs.getString(keyFor("api_key", type), "")).put("session", prefs.getString(keyFor("session", type), ""))
                    .put("assistant_session", prefs.getString("assistant_session", "")).put("hub_url", prefs.getString("hub_url", ""))
                    .put("hub_key", prefs.getString("hub_key", "")).put("slots", slots);
        } catch (Exception ignored) { }
        return o;
    }

    private static void clearSlots(SharedPreferences.Editor e) {
        e.remove("hub_key");
        for (String t : SERVER_TYPES) e.remove(keyFor("endpoint", t)).remove(keyFor("api_key", t)).remove(keyFor("session", t));
    }

    public boolean speakReplies() { return prefs.getBoolean("speak", true); }
    public void speakReplies(boolean value) { prefs.edit().putBoolean("speak", value).apply(); }
    /** On by default, 23:00-07:00: at night only critical alerts make a sound. */
    public boolean quietHours() { return prefs.getBoolean("quiet_hours", true); }
    public void quietHours(boolean v) { prefs.edit().putBoolean("quiet_hours", v).apply(); }
    public int quietStart() { return prefs.getInt("quiet_start", 23); }
    public int quietEnd() { return prefs.getInt("quiet_end", 7); }
    public void quietWindow(int start, int end) { prefs.edit().putInt("quiet_start", start).putInt("quiet_end", end).apply(); }
    public boolean appLock() { return prefs.getBoolean("app_lock", false); }
    public void appLock(boolean v) { prefs.edit().putBoolean("app_lock", v).apply(); }
    public boolean wakeEnabled() { return prefs.getBoolean("wake", false); }
    public void wakeEnabled(boolean value) {
        SharedPreferences.Editor edit = prefs.edit().putBoolean("wake", value);
        if (!value) edit.remove("wake_pause_end").remove("wake_pause_started_elapsed");
        edit.apply();
    }
    /** Custom wake phrase as typed ("" = Hey Hermes), its spotter tokens, and the threshold training picked (0 = untrained). */
    public String wakePhrase() { return prefs.getString("wake_phrase", ""); }
    public String wakeTokens() { return prefs.getString("wake_tokens", ""); }
    public float wakeThreshold() { return prefs.getFloat("wake_threshold", 0f); }
    public void wakePhrase(String phrase, String tokens) { prefs.edit().putString("wake_phrase", phrase).putString("wake_tokens", tokens).remove("wake_threshold").apply(); }
    public void wakeThreshold(float t) { prefs.edit().putFloat("wake_threshold", t).apply(); }
    /** "Hey Hermes" or the custom phrase, for labels. */
    public String wakeLabel() { return wakeTokens().isEmpty() ? "Hey Hermes" : WakePhrase.label(wakePhrase()); }
    public String wakeSensitivity() { return prefs.getString("wake_sensitivity", "Normal"); }
    public void wakeSensitivity(String value) { prefs.edit().putString("wake_sensitivity", value).apply(); }
    public long wakePauseEnd() { return prefs.getLong("wake_pause_end", 0); }
    public long wakePauseStartedElapsed() { return prefs.getLong("wake_pause_started_elapsed", 0); }
    public void wakePause(long end, long startedElapsed) {
        prefs.edit().putLong("wake_pause_end", end).putLong("wake_pause_started_elapsed", startedElapsed).apply();
    }
    /* ---- assistant behaviour ---- */
    public boolean deviceActions() { return prefs.getBoolean("device_actions", true); }
    public void deviceActions(boolean v) { prefs.edit().putBoolean("device_actions", v).apply(); }
    public boolean agentPaused() { return prefs.getBoolean("agent_paused", false); }
    public void agentPaused(boolean v) { prefs.edit().putBoolean("agent_paused", v).apply(); }
    public String actionMode() { return prefs.getString("action_mode", debuggable ? "Full access" : "Ask"); }
    public void actionMode(String v) { prefs.edit().putString("action_mode", v).apply(); }
    public boolean shareDeviceContext() { return prefs.getBoolean("device_context", true); }
    public void shareDeviceContext(boolean v) { prefs.edit().putBoolean("device_context", v).apply(); }
    public boolean shareLocation() { return prefs.getBoolean("share_location", false); }
    public void shareLocation(boolean v) { prefs.edit().putBoolean("share_location", v).apply(); }
    /** After a spoken reply finishes, listen again automatically: a hands-free back-and-forth. */
    /** Speak the reply sentence by sentence while it is still being written. */
    public boolean streamSpeech() { return prefs.getBoolean("stream_speech", true); }
    public void streamSpeech(boolean v) { prefs.edit().putBoolean("stream_speech", v).apply(); }
    /** Let the speech server end a take when a sentence is finished and you went quiet (faster than waiting out the silence). */
    /** Hermes runs go to the gateway's Companion platform (server-kit/hermes-plugin) when it answers. */
    public boolean useGateway() { return prefs.getBoolean("use_gateway", true); }
    public void useGateway(boolean v) { prefs.edit().putBoolean("use_gateway", v).apply(); }
    public boolean fastEndpoint() { return prefs.getBoolean("fast_endpoint", true); }
    public void fastEndpoint(boolean v) { prefs.edit().putBoolean("fast_endpoint", v).apply(); }
    /** Read replies with the homelab's Piper voice (Wyoming TTS) instead of the phone's; the phone voice is the fallback. */
    public boolean serverVoice() { return prefs.getBoolean("server_voice", false); }
    public void serverVoice(boolean v) { prefs.edit().putBoolean("server_voice", v).apply(); }
    /** Wyoming TTS server "host:port"; empty means the Hermes host on port 10200. */
    public String ttsAddress() { return prefs.getString("tts_address", "").trim(); }
    public void ttsAddress(String v) { prefs.edit().putString("tts_address", v == null ? "" : v.trim()).apply(); }
    /** Talk over Hermes to interrupt it: on headphones by default, optionally on the speaker (echo risk). */
    public boolean bargeIn() { return prefs.getBoolean("barge_in", false); }
    public void bargeIn(boolean v) { prefs.edit().putBoolean("barge_in", v).apply(); }
    public boolean bargeInSpeaker() { return prefs.getBoolean("barge_in_speaker", false); }
    public void bargeInSpeaker(boolean v) { prefs.edit().putBoolean("barge_in_speaker", v).apply(); }
    /** Call mode: the assistant stays open, listens again after every answer, and keeps the screen awake. */
    public boolean callMode() { return prefs.getBoolean("call_mode", false); }
    public void callMode(boolean v) { prefs.edit().putBoolean("call_mode", v).apply(); }
    /** How long a spoken request waits on screen (so a misheard one can be fixed) before it is sent. */
    public int voiceSendDelayMs() { return callActive || callMode() ? 0 : prefs.getInt("voice_send_delay", 900); }
    public void voiceSendDelayMs(int ms) { prefs.edit().putInt("voice_send_delay", ms).apply(); }
    /** True while a call is open in the assistant overlay (set by the session; never persisted). */
    public static volatile boolean callActive;
    public boolean conversationMode() { return callActive || prefs.getBoolean("conversation_mode", true); }
    public void conversationMode(boolean v) { prefs.edit().putBoolean("conversation_mode", v).apply(); }
    /** Settings › Appearance: a Concept id; unknown ids fall back to Graphite in Concept.byId. */
    public String uiConcept() { return prefs.getString("ui_concept", BuildConfig.BRAND_THEME); }
    public void uiConcept(String id) { prefs.edit().putString("ui_concept", Concept.byId(id).id).apply(); }
    /** Server-kit modules last chosen in the setup sheet (comma-separated ids); empty = the kit's defaults. */
    public String kitModules() { return prefs.getString("kit_modules", ""); }
    public void kitModules(String csv) { prefs.edit().putString("kit_modules", csv == null ? "" : csv).apply(); }
    /** Agents screen ordering: SMART (default), NEXT, LAST or NAME. */
    public String jobSort() { return prefs.getString("job_sort", "SMART"); }
    public void jobSort(String mode) { prefs.edit().putString("job_sort", mode == null ? "SMART" : mode).apply(); }
    /** Accent colour override (ARGB), 0 = the theme's own. */
    public int uiAccent() { return prefs.getInt("ui_accent", BuildConfig.BRAND_ACCENT); }
    /** Background of the Custom theme (Concept.custom). */
    public int uiCustomBg() { return prefs.getInt("ui_custom_bg", BuildConfig.BRAND_BACKGROUND == 0 ? 0xff101418 : BuildConfig.BRAND_BACKGROUND); }
    public void uiCustomBg(int argb) { prefs.edit().putInt("ui_custom_bg", argb).apply(); }
    /** Branding: your name for the Hermes agent ("" = Hermes). */
    public String brandName() { return prefs.getString("brand_name", BuildConfig.BRAND_NAME); }
    public void brandName(String v) { prefs.edit().putString("brand_name", v == null ? "" : v.trim()).apply(); }
    public void uiAccent(int argb) { prefs.edit().putInt("ui_accent", argb).apply(); }
    /** Bottom-bar tabs (Tabs layout) as comma-separated Tab names; empty = the default five. */
    public String uiTabs() { return prefs.getString("ui_tabs", ""); }
    public void uiTabs(String csv) { prefs.edit().putString("ui_tabs", csv == null ? "" : csv).apply(); }
    public String uiLayout() { return prefs.getString("ui_layout", Layout.CLASSIC.id); }
    public void uiLayout(String id) { prefs.edit().putString("ui_layout", Layout.byId(id).id).apply(); }
    public boolean showReasoning() { return prefs.getBoolean("show_reasoning", true); }
    public void showReasoning(boolean v) { prefs.edit().putBoolean("show_reasoning", v).apply(); }
    /** Assistant overlay: true keeps its own "Assistant" conversation, false continues the last chat. */
    public boolean assistantOwnSession() { return prefs.getBoolean("assistant_own_session", true); }
    public void assistantOwnSession(boolean v) { prefs.edit().putBoolean("assistant_own_session", v).apply(); }
    public boolean useScreenContext() { return prefs.getBoolean("screen_context", true); }
    public void useScreenContext(boolean v) { prefs.edit().putBoolean("screen_context", v).apply(); }
    public boolean allowScreenContent() { return prefs.getBoolean("screen_content", false); }
    public void allowScreenContent(boolean v) { prefs.edit().putBoolean("screen_content", v).apply(); }
    public void screenControlConnected() { prefs.edit().putBoolean("screen_control_connected", true).putBoolean("screen_control_dismissed", false).apply(); }
    public boolean shouldShowScreenControlReminder(boolean connected) {
        return showScreenControlReminder(prefs.getBoolean("screen_control_connected", false), connected,
                prefs.getBoolean("screen_control_dismissed", false));
    }
    static boolean showScreenControlReminder(boolean wasConnected, boolean connected, boolean dismissed) {
        return wasConnected && !connected && !dismissed;
    }
    public long alertMutedUntil(String key) { return prefs.getLong("alert_mute:" + key, 0); }
    public void muteAlert(String key, long until) { prefs.edit().putLong("alert_mute:" + key, until).apply(); }

    /** False if event id was already notified; otherwise records it (last 200 ids). */
    public boolean markEventNotified(String hub, long id) {
        synchronized (SettingsStore.class) {
            String preference = "notified_events:" + UrlUtils.normalize(hub);
            String seen = prefs.getString(preference, ""), key = "," + id + ",";
            if (("," + seen + ",").contains(key)) return false;
            String next = seen.isEmpty() ? String.valueOf(id) : seen + "," + id;
            String[] parts = next.split(",");
            if (parts.length > 200) next = String.join(",", java.util.Arrays.copyOfRange(parts, parts.length - 200, parts.length));
            prefs.edit().putString(preference, next).apply();
            return true;
        }
    }
    /** Conversations with an approval request (```proposal) still waiting for the user: feeds the "Needs you" filter. */
    public java.util.Set<String> pendingProposals() { return new java.util.HashSet<>(prefs.getStringSet("pending_proposals", java.util.Collections.emptySet())); }
    public void pendingProposal(String sessionId, boolean pending) {
        if (sessionId == null || sessionId.isEmpty()) return;
        java.util.Set<String> s = pendingProposals();
        if (pending ? s.add(sessionId) : s.remove(sessionId)) prefs.edit().putStringSet("pending_proposals", s).apply();
    }
    public void dismissScreenControlReminder() { prefs.edit().putBoolean("screen_control_dismissed", true).apply(); }
    public String screenControlHint() { return prefs.getBoolean("screen_control_connected", false)
            ? "Screen control was turned off. Turn it on in Accessibility settings"
            : "Enable Hermes screen control in Accessibility settings"; }
    /** Attach a downscaled screenshot to each PhoneAgent observation, for models that accept images. */
    public boolean sendScreenshots() { return prefs.getBoolean("send_screenshots", false); }
    public void sendScreenshots(boolean v) { prefs.edit().putBoolean("send_screenshots", v).apply(); }
    public float speechRate() { return prefs.getFloat("speech_rate", 1.0f); }
    public void speechRate(float v) { prefs.edit().putFloat("speech_rate", v).apply(); }
    /** Voice pitch for spoken replies (1.0 = normal); applied to the homelab voice and the phone voice. */
    public float speechPitch() { return prefs.getFloat("speech_pitch", 1.0f); }
    public void speechPitch(float v) { prefs.edit().putFloat("speech_pitch", v).apply(); }
    /** Voice input in chat. Send mode: "send" (straight away) or "review" (text lands in the box). Stop mode: "pause" (when you stop talking) or "manual". */
    public String voiceSend() { return "review".equals(prefs.getString("voice_send", "send")) ? "review" : "send"; }
    public void voiceSend(String v) { prefs.edit().putString("voice_send", "review".equals(v) ? "review" : "send").apply(); }
    public boolean voiceManualStop() { return "manual".equals(prefs.getString("voice_stop", "pause")); }
    public void voiceManualStop(boolean v) { prefs.edit().putString("voice_stop", v ? "manual" : "pause").apply(); }
    /** How long a pause ends the take, in ms (the default is 1.3 s). */
    public int voicePauseMs() { return Math.max(1300, Math.min(8000, prefs.getInt("voice_pause_ms", 2000))); }
    /** The assistant overlay and calls stay quick (1.3 s) unless the pause was chosen explicitly. */
    public int assistantPauseMs() { return prefs.contains("voice_pause_ms") ? voicePauseMs() : 1300; }
    public boolean assistantMayEndTake() { return fastEndpoint() && assistantPauseMs() <= 1300; }
    public void voicePauseMs(int v) { prefs.edit().putInt("voice_pause_ms", Math.max(1300, Math.min(8000, v))).apply(); }
    /** Hold the chat mic button to talk and let go to finish (a plain tap still opens the voice sheet). */
    public boolean voiceHold() { return prefs.getBoolean("voice_hold", true); }
    public void voiceHold(boolean v) { prefs.edit().putBoolean("voice_hold", v).apply(); }
    /** The speech server may end a take itself at a sentence end: only when you want the quickest, default behaviour. */
    public boolean serverMayEndTake() { return fastEndpoint() && !voiceManualStop() && voicePauseMs() <= 1300; }

    /** Live notes: model for cards and summary ("" = the Assistant default), seconds between cards, and what the class is about. */
    public String lectureModel() { return prefs.getString("lecture_model", "").trim(); }
    public void lectureModel(String v) { prefs.edit().putString("lecture_model", v == null ? "" : v.trim()).apply(); }
    public int lectureTick() { return Math.max(4, Math.min(30, prefs.getInt("lecture_tick", 7))); }
    public void lectureTick(int v) { prefs.edit().putInt("lecture_tick", Math.max(4, Math.min(30, v))).apply(); }
    public String lectureContext() { return prefs.getString("lecture_context", "").trim(); }
    public void lectureContext(String v) { prefs.edit().putString("lecture_context", v == null ? "" : v.trim()).apply(); }

    /** Homelab voice name sent to the speech server ("" = the server's default). */
    public String ttsVoice() { return prefs.getString("tts_voice", "").trim(); }
    public void ttsVoice(String v) { prefs.edit().putString("tts_voice", v == null ? "" : v.trim()).apply(); }
    /** Default landing lane: false opens the chat even when Essentials capture is configured. */
    public boolean openCaptureFirst() { return prefs.getBoolean("capture_first", true); }
    public void openCaptureFirst(boolean v) { prefs.edit().putBoolean("capture_first", v).apply(); }
    /** Reasoning effort chosen for one Hermes conversation; empty = the server default. */
    public String agentEffort(String sessionId) { return prefs.getString("effort:" + sessionId, ""); }
    public void agentEffort(String sessionId, String effort) {
        if (effort == null || effort.isEmpty()) prefs.edit().remove("effort:" + sessionId).apply();
        else prefs.edit().putString("effort:" + sessionId, effort).apply();
    }
    public String draft(String sessionId) { return prefs.getString("draft:" + sessionId, ""); }
    public void draft(String sessionId, String text) {
        if (text == null || text.trim().isEmpty()) prefs.edit().remove("draft:" + sessionId).apply();
        else prefs.edit().putString("draft:" + sessionId, text).apply();
    }
    /** Per harness: a Hermes session id means nothing to Codex or Claude Code, and each keeps its own assistant conversation. */
    public String assistantSessionId() { return prefs.getString(keyFor("assistant_session", serverType()), ""); }
    public void assistantSessionId(String v) { prefs.edit().putString(keyFor("assistant_session", serverType()), v == null ? "" : v).apply(); }

    /* ---- self-hosted voice ---- */
    /** Wyoming Whisper server "host:port"; empty means the Hermes host on port 10300. */
    public String whisperAddress() {
        String v = customWhisperAddress();
        return v == null || v.trim().isEmpty() ? WhisperClient.defaultFor(endpoint()) : v.trim();
    }
    public String customWhisperAddress() { return prefs.getString("whisper", ""); }
    public void whisperAddress(String v) { prefs.edit().putString("whisper", v == null ? "" : v.trim()).apply(); }
    /** Whisper language: "auto" leaves it to the server. */
    /** Whisper language code, or "auto". Defaults to English: whisper-tiny's language guess is wrong
     *  too often on short phone clips, and the old "auto" default is read as English too. */
    public String speechLanguage() {
        String v = prefs.getString("speech_language", null);
        if (v != null) return v;
        String old = prefs.getString("speech_lang", "auto");
        return "auto".equals(old) ? "en" : old;
    }
    public void speechLanguage(String v) { prefs.edit().putString("speech_language", v).apply(); }
    public String speechRecognition() { return prefs.getString("speech_recognition", "auto"); }
    public void speechRecognition(String v) { prefs.edit().putString("speech_recognition", v).apply(); }
    /** Text-to-speech engine package; empty picks the first non-Google engine installed. */
    public String ttsEngine() { return prefs.getString("tts_engine", ""); }
    public void ttsEngine(String v) { prefs.edit().putString("tts_engine", v == null ? "" : v).apply(); }

    /* ---- phone data Hermes may read (each off until the user allows it) ---- */
    public boolean allowCalendar() { return prefs.getBoolean("allow_calendar", false); }
    public void allowCalendar(boolean v) { prefs.edit().putBoolean("allow_calendar", v).apply(); }
    public boolean allowContacts() { return prefs.getBoolean("allow_contacts", false); }
    /** Calls and texts go straight out (after the action-mode confirmation) instead of opening the dialer / compose screen. */
    /** Hermes opens apps on a hidden display (Shizuku) so the phone stays usable during tasks. */
    /** Plain phone commands run on the phone at once instead of going through the model (LocalCommands). */
    public boolean localCommands() { return prefs.getBoolean("local_commands", true); }
    public void localCommands(boolean v) { prefs.edit().putBoolean("local_commands", v).apply(); }
    public boolean backgroundAgent() { return prefs.getBoolean("background_agent", false); }
    public void backgroundAgent(boolean v) { prefs.edit().putBoolean("background_agent", v).apply(); }
    /** Hermes may run phone actions requested from other channels (Telegram, voice, jobs) through the hub. */
    public boolean remotePhoneActions() { return prefs.getBoolean("remote_phone_actions", true); }
    public void remotePhoneActions(boolean v) { prefs.edit().putBoolean("remote_phone_actions", v).apply(); }
    public boolean directComms() { return prefs.getBoolean("direct_comms", false); }
    public void directComms(boolean v) { prefs.edit().putBoolean("direct_comms", v).apply(); }
    public void allowContacts(boolean v) { prefs.edit().putBoolean("allow_contacts", v).apply(); }
    public boolean allowClipboard() { return prefs.getBoolean("allow_clipboard", false); }
    public void allowClipboard(boolean v) { prefs.edit().putBoolean("allow_clipboard", v).apply(); }
    public boolean replyDrafting() { return prefs.getBoolean("reply_drafting", false); }
    public void replyDrafting(boolean v) { prefs.edit().putBoolean("reply_drafting", v).apply(); }
    public Set<String> replyApps() { return new HashSet<>(prefs.getStringSet("reply_apps", Collections.emptySet())); }
    public void replyApp(String name, boolean allowed) {
        Set<String> apps = replyApps();
        if (allowed) apps.add(name); else apps.remove(name);
        prefs.edit().putStringSet("reply_apps", apps).apply();
    }
    /** Reply suggestions watch every messaging app except muted ones; off = only the apps in replyApps. */
    public boolean replyAllApps() { return prefs.getBoolean("reply_all_apps", true); }
    public void replyAllApps(boolean v) { prefs.edit().putBoolean("reply_all_apps", v).apply(); }
    public Set<String> replyMuted() { return new HashSet<>(prefs.getStringSet("reply_muted", Collections.emptySet())); }
    public void replyMute(String pkg, boolean muted) {
        Set<String> apps = replyMuted();
        if (muted) apps.add(pkg); else apps.remove(pkg);
        prefs.edit().putStringSet("reply_muted", apps).apply();
    }
    /** Off (default): messages are read quietly and only a spotted plan makes a notification. */
    public boolean replyEveryMessage() { return prefs.getBoolean("reply_every_message", false); }
    public void replyEveryMessage(boolean v) { prefs.edit().putBoolean("reply_every_message", v).apply(); }
    public boolean replyWatches(String pkg) { return replyAllApps() ? !replyMuted().contains(pkg) : replyApps().contains(pkg); }
    /** Read notifications aloud into earbuds (needs notification access). Apps: empty = the messaging apps. */
    public boolean readNotifications() { return prefs.getBoolean("read_notifs", false); }
    public void readNotifications(boolean v) { prefs.edit().putBoolean("read_notifs", v).apply(); }
    public boolean readNotifHeadphonesOnly() { return prefs.getBoolean("read_notifs_headphones", true); }
    public void readNotifHeadphonesOnly(boolean v) { prefs.edit().putBoolean("read_notifs_headphones", v).apply(); }
    public boolean readNotifScreenOffOnly() { return prefs.getBoolean("read_notifs_screen_off", true); }
    public void readNotifScreenOffOnly(boolean v) { prefs.edit().putBoolean("read_notifs_screen_off", v).apply(); }
    /** False = say only who a message is from. */
    public boolean readNotifContent() { return prefs.getBoolean("read_notifs_content", true); }
    public void readNotifContent(boolean v) { prefs.edit().putBoolean("read_notifs_content", v).apply(); }
    public Set<String> readNotifApps() { return new HashSet<>(prefs.getStringSet("read_notifs_apps", Collections.emptySet())); }
    public void readNotifApps(Set<String> apps) { prefs.edit().putStringSet("read_notifs_apps", new HashSet<>(apps)).apply(); }
    /** How many query → result rounds Hermes may chain on its own before waiting for the user. */
    public int agentRounds() { return prefs.getInt("agent_rounds", 3); }
    public void agentRounds(int v) { prefs.edit().putInt("agent_rounds", v).apply(); }
    /** How many on-screen steps a background PhoneAgent task may take before it stops itself. */
    public int agentSteps() { return prefs.getInt("agent_steps", 25); }
    public void agentSteps(int v) { prefs.edit().putInt("agent_steps", Math.max(1, v)).apply(); }
    /** Between Hermes turns, let Jev (a fast "System One" model) take a few on-screen steps itself. */
    public boolean fastSteps() { return prefs.getBoolean("fast_steps", debuggable); }
    public void fastSteps(boolean v) { prefs.edit().putBoolean("fast_steps", v).apply(); }
    /** Ask mode only: skip the confirmation prompt for agent screen steps Jev judges low-risk. */
    public boolean smartAsk() { return prefs.getBoolean("smart_ask", false); }
    public void smartAsk(boolean v) { prefs.edit().putBoolean("smart_ask", v).apply(); }
    /** Model pinned for PhoneAgent tasks; empty picks PhoneAgent.DEFAULT_AGENT_MODEL when the
     *  gateway offers it, else keeps the conversation's current model. */
    public String assistantModelPinned() { return prefs.getString("assistant_model_pinned", ""); }
    public void assistantModelPinned(String v) { prefs.edit().putString("assistant_model_pinned", v == null ? "" : v).apply(); }
    /** Per harness: Haiku's id is not a Codex model. */
    public String agentModel() { return prefs.getString(keyFor("agent_model", serverType()), ""); }
    public void agentModel(String v) { prefs.edit().putString(keyFor("agent_model", serverType()), v == null ? "" : v.trim()).apply(); }

    public String history() { return prefs.getString("history", "[]"); }
    public void history(String value) { prefs.edit().putString("history", value).apply(); }

    public java.util.List<HomeData.Tile> homeTiles() {
        if (!prefs.contains("home_tiles")) {
            java.util.List<HomeData.Tile> defaults = new java.util.ArrayList<>();
            defaults.add(new HomeData.Tile("Lights off", "Turn off all the lights"));
            defaults.add(new HomeData.Tile("Good night", "Run my good night routine in Home Assistant: lights off, check doors/windows, tell me anything left open"));
            defaults.add(new HomeData.Tile("Leaving home", "I'm leaving home"));
            defaults.add(new HomeData.Tile("I'm home", "I'm home"));
            defaults.add(new HomeData.Tile("Shopping list", "Read my Home Assistant shopping list"));
            return defaults;
        }
        return HomeData.tiles(prefs.getString("home_tiles", "[]"));
    }
    public void homeTiles(java.util.List<HomeData.Tile> tiles) { prefs.edit().putString("home_tiles", HomeData.tilesJson(tiles)).apply(); }
    public java.util.List<HomeData.Place> places() { return HomeData.places(prefs.getString("places", "[]")); }
    public void places(java.util.List<HomeData.Place> places) { prefs.edit().putString("places", HomeData.placesJson(places)).apply(); }
    public int placeState(String id) { return prefs.getInt("place_state:" + id, -1); }
    public long placeRegisteredAt(String id) { return prefs.getLong("place_reg:" + id, 0); }
    public void markPlaceRegistered(String id) { prefs.edit().putLong("place_reg:" + id, System.currentTimeMillis()).apply(); }
    public void placeState(String id, boolean inside) {
        prefs.edit().putInt("place_state:" + id, inside ? 1 : 0).apply();
    }
    public long placeLastRun(String id) { return prefs.getLong("place_run:" + id, 0); }
    public void placeLastRun(String id, long time) { prefs.edit().putLong("place_run:" + id, time).apply(); }
    public void forgetPlace(String id) { prefs.edit().remove("place_state:" + id).remove("place_reg:" + id).remove("place_run:" + id).apply(); }
    public int dailyTokenBudget() { return prefs.getInt("daily_token_budget", 0); }
    public void dailyTokenBudget(int tokens) { prefs.edit().putInt("daily_token_budget", Math.max(0, tokens)).apply(); }
    public String homeSessionId() { return prefs.getString("home_session", ""); }
    public void homeSessionId(String id) { prefs.edit().putString("home_session", id).apply(); }

    /** Other addresses for this server (Tailscale IP, MagicDNS name, public https URL); see {@link Reach}. */
    public String backupHosts() { return prefs.getString("backup_hosts", ""); }
    public boolean backupHostsChosen() { return prefs.contains("backup_hosts"); }
    /** True once the user saved the list by hand: from then on the hub's list never changes it. */
    public boolean backupHostsEdited() { return prefs.getBoolean("backup_hosts_user", false); }
    public void backupHostsEdited(boolean v) { prefs.edit().putBoolean("backup_hosts_user", v).apply(); }
    public void backupHosts(String v) { prefs.edit().putString("backup_hosts", v == null ? "" : v.trim().toLowerCase(java.util.Locale.ROOT)).apply(); Reach.configure(this); }

    /* ---- hermes-hub (push + ops) ---- */
    /** Hub base URL; empty means "derive from endpoint() on port 8791". */
    public String hubUrl() {
        String v = customHubUrl();
        if (!"Hermes".equals(serverType()) && (v == null || v.trim().isEmpty())) {
            Connection hub = hubConnection(profileId());
            if (hub != null) return hub.endpoint;
        }
        return v == null || v.trim().isEmpty() ? HubApi.defaultFor(endpoint()) : UrlUtils.normalize(v);
    }
    public String customHubUrl() { return prefs.getString("hub_url", ""); }
    public void hubUrl(String v) { prefs.edit().putString("hub_url", UrlUtils.normalize(v)).apply(); }
    /** Per hub: ids are each hub's own sequence, so another server's cursor would hide or replay events. */
    public long lastEventId() { return lastEventId(hubUrl()); }
    public long lastEventId(String hub) {
        String key = "last_event_id:" + hub;
        if (!prefs.contains(key) && prefs.contains("last_event_id")) {
            // The cursor from before profiles belongs to the hub in use when this build first runs.
            long v = prefs.getLong("last_event_id", 0);
            prefs.edit().putLong(key, v).remove("last_event_id").apply();
            return v;
        }
        return prefs.getLong(key, 0);
    }
    public void lastEventId(long v) { lastEventId(hubUrl(), v); }
    public void lastEventId(String hub, long v) { prefs.edit().putLong("last_event_id:" + hub, v).apply(); }
    public boolean eventCursorInitialized(String hub) { return prefs.getBoolean("event_cursor_initialized:" + hub, false); }
    public void markEventCursorInitialized(String hub) { prefs.edit().putBoolean("event_cursor_initialized:" + hub, true).apply(); }
    public boolean notificationPermissionAsked() { return prefs.getBoolean("notification_permission_asked", false); }
    public void markNotificationPermissionAsked() { prefs.edit().putBoolean("notification_permission_asked", true).apply(); }
    /** The UnifiedPush endpoint URL last registered with the hub, or "" when not registered. */
    public String pushEndpoint() { return prefs.getString("push_endpoint:" + hubUrl(), ""); }
    public void pushEndpoint(String hub, String v) { prefs.edit().putString("push_endpoint:" + hub, v == null ? "" : v).apply(); }
    /** The distributor package this device registered with (e.g. io.heckel.ntfy), or "" when polling only. */
    public String pushDistributor() { return prefs.getString("push_distributor:" + hubUrl(), ""); }
    public void pushDistributor(String hub, String v) { prefs.edit().putString("push_distributor:" + hub, v == null ? "" : v).apply(); }
    public boolean pushEnabled() { return !pushEndpoint().isEmpty() && !pushDistributor().isEmpty(); }
    /** Proves an activity intent came from this app's own notification (MainActivity is exported). */
    public String intentSecret() {
        String v = prefs.getString("intent_secret", "");
        if (v == null || v.isEmpty()) { v = UUID.randomUUID().toString(); prefs.edit().putString("intent_secret", v).apply(); }
        return v;
    }

    /** Each distributor subscription has a fixed hub and profile. Legacy unbound tokens are ignored. */
    public String pushToken(String hub, String profile) {
        synchronized (SettingsStore.class) {
            String preference = "push_token:" + hub;
            String token = prefs.getString(preference, "");
            if (token.isEmpty()) token = UUID.randomUUID().toString();
            prefs.edit().putString(preference, token).putString("push_hub:" + token, hub)
                    .putString("push_profile:" + token, profile).apply();
            return token;
        }
    }
    public String pushHub(String token) { return token == null ? "" : prefs.getString("push_hub:" + token, ""); }
    public String pushProfile(String token) { return token == null ? "" : prefs.getString("push_profile:" + token, ""); }

    public String apiKey() { return unseal(keyFor("api_key", serverType()), KEY_ALIAS); }

    public void apiKey(String value) {
        try { prefs.edit().putString(keyFor("api_key", serverType()), seal(Keys.clean(value), KEY_ALIAS)).apply(); }
        catch (Exception e) { throw new IllegalStateException("Could not protect API key", e); }
    }

    /* ---- Essentials (the capture lane) ----
       Its own token and its own keystore alias: a stolen/rotated Essentials token must not be
       interchangeable with the gateway key that can drive the whole agent. */
    private static final String ESS_ALIAS = "hermes_companion_essentials_token";

    public String essentialsBase() { return UrlUtils.normalize(prefs.getString("ess_base", "")); }
    public void essentialsBase(String value) {
        prefs.edit().putString("ess_base", UrlUtils.normalize(value)).apply();
    }
    public String essentialsToken() { return unseal("ess_token", ESS_ALIAS); }
    public void essentialsToken(String value) {
        try { prefs.edit().putString("ess_token", seal(Keys.clean(value), ESS_ALIAS)).apply(); }
        catch (Exception e) { throw new IllegalStateException("Could not protect the Essentials token", e); }
    }
    public boolean essentialsReady() { return moduleOn(Modules.ESSENTIALS) && !essentialsBase().isEmpty() && !essentialsToken().isEmpty(); }

    /** Switch of an optional module that has no setting of its own (see Modules); on unless turned off. */
    public boolean moduleOn(String id) { return prefs.getBoolean("module." + id, true); }
    public boolean moduleOnDefault(String id, boolean fallback) { return prefs.getBoolean("module." + id, fallback); }
    public void moduleOn(String id, boolean on) { prefs.edit().putBoolean("module." + id, on).apply(); }

    /* ---- First-run onboarding, pairing hub, push relay ---- */
    private static final String RELAY_ALIAS = "hermes_companion_push_relay_key";
    public boolean onboardingDone() { return prefs.getBoolean("onboarding_done", false); }
    public void onboardingDone(boolean v) { prefs.edit().putBoolean("onboarding_done", v).apply(); }
    /** Tailscale pairing hub (empty by default; nothing is paired automatically without it). */
    public String pairHub() { return UrlUtils.normalize(prefs.getString("pair_hub", "")); }
    public void pairHub(String v) { prefs.edit().putString("pair_hub", UrlUtils.normalize(v)).apply(); }
    public String relayUrl() { return PushRelay.base(prefs.getString("relay_url", "")); }
    public void relayUrl(String v) { prefs.edit().putString("relay_url", PushRelay.base(v)).apply(); }
    public String relayKey() { return unseal("relay_key", RELAY_ALIAS); }
    public void relayKey(String value) {
        try { prefs.edit().putString("relay_key", seal(Keys.clean(value), RELAY_ALIAS)).apply(); }
        catch (Exception e) { throw new IllegalStateException("Could not protect the relay key", e); }
    }
    public boolean relayReady() { return !relayUrl().isEmpty() && !relayKey().isEmpty(); }
    /** The FCM token last registered with this relay ("" if none). */
    public String relayRegistered(String relay) { return prefs.getString("relay_registered:" + relay, ""); }
    public void relayRegistered(String relay, String token) { prefs.edit().putString("relay_registered:" + relay, token).apply(); }

    private String seal(String value, String alias) throws Exception {
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.ENCRYPT_MODE, getOrCreateKey(alias));
        return Base64.encodeToString(cipher.getIV(), Base64.NO_WRAP) + "." +
                Base64.encodeToString(cipher.doFinal(value.getBytes(StandardCharsets.UTF_8)), Base64.NO_WRAP);
    }

    private String unseal(String prefKey, String alias) { return unsealPacked(prefs.getString(prefKey, ""), alias); }

    private String unsealPacked(String packed, String alias) {
        try {
            if (packed == null || packed.isEmpty()) return "";
            String[] parts = packed.split("\\.", 2);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, getOrCreateKey(alias),
                    new GCMParameterSpec(128, Base64.decode(parts[0], Base64.NO_WRAP)));
            return new String(cipher.doFinal(Base64.decode(parts[1], Base64.NO_WRAP)), StandardCharsets.UTF_8);
        } catch (Exception ignored) { return ""; }
    }

    private SecretKey getOrCreateKey(String alias) throws Exception {
        KeyStore store = KeyStore.getInstance("AndroidKeyStore"); store.load(null);
        if (store.containsAlias(alias)) return (SecretKey) store.getKey(alias, null);
        KeyGenerator gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore");
        gen.init(new KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build());
        return gen.generateKey();
    }
}
