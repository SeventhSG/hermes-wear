package cloud.stormlabs.hermescompanion;

import android.app.Activity;
import android.content.SharedPreferences;

/** Once per update: a sheet listing what changed. Fresh installs skip it (onboarding covers them). */
final class WhatsNew {
    private WhatsNew() { }

    /** Bump with each release that has user-visible news; keep it to the highlights. */
    static final int VERSION = 109;
    static final String[][] ITEMS = {
            {"Approvals work again", "After the latest Hermes update, tapping Allow or Deny on an approval card was refused. Answers now carry what the new Hermes needs, and chats through the gateway plugin work again too."},
            {"Commands and tools show up as they run", "A running command now shows what it is right away (Codex also streams its output live), instead of an empty card until it finishes. Claude Code and Codex get their own slash commands (/compact, /review, /context, /undo and more), and /help lists what each one accepts."},
            {"Codex gets your photos and the phone's instructions", "Photos you attach reach Codex, and so does the note about showing files, so files it writes appear in the chat like Hermes's. Hermes profiles such as Pentest no longer show up while you are on Codex or Claude Code, and the assistant keeps a separate conversation and model per harness."},
            {"The voice server wakes before you speak", "If your voice machine is asleep to save power, opening a chat, the voice sheet or saying the wake word now wakes it straight away, so it is ready by the time you finish talking. Needs the updated hub."},
            {"Effort and permissions on every chat", "Two chips above the message box set how hard the agent thinks and what it may do without asking. They now work for Hermes as well as Codex (Hermes permissions are a server-wide setting, changed through the hub), and show up for Claude Code once its bridge is running."},
            {"Updates through your backup addresses", "Update checks now use your Hermes hub credentials even while you are in a Codex chat. They switch to your saved backup connection addresses with shorter timeouts. Interrupted APK downloads retry cleanly, with the checksum verified before installation."},
            {"Codex effort and agent permissions", "In a Codex chat, open the menu for Reasoning effort and Agent permissions. Effort choices come from the selected model. Choose read-only, workspace access or full access for the next message. These controls are separate from phone actions. Live replies also recover missing text and replay without duplication."},
            {"Jobs sorted, alerts quiet, chats named", "Agents are grouped (needs attention, running, upcoming, paused) with a Sort menu. The homelab no longer repeats the same alert every few minutes. New chats get a real title instead of your first message, and searching a conversation name finds old chats too, with any words in any order."},
            {"Notifications read into your earbuds", "Settings \u203a Voice \u203a Read notifications aloud: with the screen off and earbuds connected, messages from your chosen apps are spoken in your Hermes voice. It stops when you pick the phone up, skips calls and quiet hours, and can say only who it is from."},
            {"Voice input fixed", "Hold the chat mic to talk and let go to send (a quick tap still opens the sheet). Pauses now wait 2 seconds by default, and the voice sheet has a switch to keep listening until you tap Done."},
            {"Choose what goes on your server", "Settings \u203a Advanced \u203a Server pack: tick the modules you want (hub, any of the ten skills, phone actions, slash commands, Codex remote, Proxmox, voice servers). Hermes previews the install and you approve it."},
            {"Speak without being cut off", "Settings \u203a Voice: hold the mic to talk and let go to finish, choose how long a pause ends your turn (or end it yourself), and have the words land in the box so you can check them before sending."},
            {"More themes and your own accent", "Seven new colour themes (OLED Black, Ember, Violet Night, Nord, Forest, Sky, Sage) and an accent colour you can set over any of them (Settings \u203a Appearance)."},
            {"Layouts that fit you", "The Tabs bar now shows five tabs of your choice, including Home and Live notes, and a new Voice layout turns the chat into a walkie-talkie with a big hold-to-talk button."},
            {"Live notes: ask, recap, flashcards", "While Live notes runs, tap I missed that for a plain-words recap of the last minute, or Ask about the lecture. The menu now makes Anki flashcards from the session."},
            {"Live notes", "Open Essentials \u203a Live notes (or the Dashboard tile) in a lecture or meeting: live transcript, a fresh insight card every few seconds, a running summary, then share it as Markdown or file it in Essentials."},
            {"More voices", "36 homelab voices now, including soft mixed voices like Heart + Nicole (Settings \u203a Voice \u203a Voice)."},
            {"Smarter, current answers", "The assistant now runs on Claude Haiku 5.5: still about a second to the first word, but it searches the web properly for news and research instead of answering from old memory."},
            {"Choose how Hermes sounds", "Pick the voice, pitch and speed in Settings \u203a Voice. It plays a sample as soon as you change one."},
            {"Much faster replies", "Hermes now answers on a fast model by default (about a second to the first word instead of 3\u201310), and the homelab voice sounds far more natural in English."},
            {"Talks while it writes", "Hermes starts speaking after the first sentence instead of waiting for the whole answer, and a spoken request is sent faster."},
            {"Faster end of speech", "When you finish a sentence and pause, the speech server ends your turn right away (Settings \u203a Voice \u203a Fast end of speech)."},
            {"Natural voice and calls", "Optional Piper voice from your homelab in English and Bulgarian, talk-over-Hermes on headphones, a hands-free Call mode, and Hermes can ring you for urgent things. All in Settings \u203a Voice."},
    };

    /** Mark current news as seen before first-run setup, including after data was cleared. */
    static void onboardingStarted(Activity a) {
        a.getSharedPreferences("whats_new", 0).edit().putInt("seen", VERSION).apply();
    }

    static void maybeShow(Activity a) {
        SharedPreferences p = a.getSharedPreferences("whats_new", 0);
        int seen = p.getInt("seen", 0);
        p.edit().putInt("seen", VERSION).apply();
        boolean updated;
        try { android.content.pm.PackageInfo i = a.getPackageManager().getPackageInfo(a.getPackageName(), 0); updated = i.lastUpdateTime != i.firstInstallTime; }
        catch (Exception e) { updated = false; }
        if (!shouldShow(seen, VERSION, updated)) return;
        Sheets.info(a, "What's new", "Hermes Companion " + versionName(a), ITEMS);
    }

    /** Once per VERSION, and never on a fresh install (nothing seen yet and the package was never updated). */
    static boolean shouldShow(int seen, int version, boolean updated) { return seen < version && (seen > 0 || updated); }

    private static String versionName(Activity a) {
        try { return a.getPackageManager().getPackageInfo(a.getPackageName(), 0).versionName; } catch (Exception e) { return ""; }
    }
}
