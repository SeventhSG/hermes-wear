package cloud.stormlabs.hermescompanion;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.res.ColorStateList;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Shader;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.widget.HorizontalScrollView;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class SettingsActivity extends Activity {
    private static final int LOCATION_REQ = 201, CONTACTS_REQ = 202, CALENDAR_REQ = 203, NOTIFY_REQ = 204, WAKE_REQ = 205, DIRECT_REQ = 206;

    private SettingsStore store;
    private ScrollView scroll;
    private LinearLayout body;
    private final List<TextView> sectionTitles = new ArrayList<>();
    private final List<View[]> sections = new ArrayList<>(); // {label, card}
    private final List<String> sectionPages = new ArrayList<>();
    /** Views outside a section card that belong to one page (notes, footer). */
    private final java.util.Map<View, String> pageViews = new java.util.HashMap<>();
    /** null: the category list; else the page being shown. */
    private String page;
    private TextView barTitle;
    private String currentPage = "Privacy";
    private android.widget.EditText search;
    private boolean filtered;
    private final java.util.Set<String> shownSections = new java.util.HashSet<>();
    private java.util.Set<String> previousSections = java.util.Collections.emptySet();
    private boolean sectionsRendered;
    private TextView connectionState;
    private final ExecutorService network = Executors.newSingleThreadExecutor();
    private boolean hasApiKey;

    private int dp(int n) { return Ui.dp(this, n); }
    private LinearLayout.LayoutParams match(int h) { return new LinearLayout.LayoutParams(-1, h < 0 ? h : dp(h)); }

    @Override public void onCreate(Bundle b) {
        super.onCreate(b);
        Ui.legacyWindowInsets(this);
        store = new SettingsStore(this);
        getWindow().setStatusBarColor(Ui.BAR);
        getWindow().setNavigationBarColor(Ui.BG);
        build();
        if (getIntent().getBooleanExtra(Updater.EXTRA_UPDATE, false)) Updater.prompt(this);
    }

    @Override protected void onResume() {
        super.onResume();
        WakeWordService.ensure(this);
        Updater.resume(this);
        if (body != null) {
            render(); checkConnection();
            network.submit(() -> {
                boolean present = !store.apiKey().isEmpty();
                runOnUiThread(() -> { hasApiKey = present; render(); });
            });
        }
    }

    private void build() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Ui.BG);
        root.setFocusableInTouchMode(true); // takes initial focus so the search box doesn't open the keyboard

        LinearLayout bar = new LinearLayout(this);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setPadding(dp(2), 0, dp(8), 0);
        bar.setBackgroundColor(Ui.BAR);
        ImageButton back = Ui.icon(this, R.drawable.ic_back, "Back");
        back.setOnClickListener(v -> onBackPressed());
        bar.addView(back, new LinearLayout.LayoutParams(dp(52), dp(56)));
        barTitle = Ui.text(this, "Settings", 19, Ui.TEXT, true);
        bar.addView(barTitle, new LinearLayout.LayoutParams(0, -2, 1));
        root.addView(bar, match(56));

        search = new android.widget.EditText(this);
        search.setHint("Search settings"); search.setSingleLine(true); search.setTextSize(14);
        search.setTextColor(Ui.TEXT); search.setHintTextColor(Ui.FAINT);
        search.setPadding(dp(14), 0, dp(14), 0); search.setBackground(Ui.stroke(this, Ui.RAISED, Ui.LINE, 12));
        search.setCompoundDrawablesRelativeWithIntrinsicBounds(R.drawable.ic_search, 0, 0, 0); search.setCompoundDrawablePadding(dp(10));
        search.addTextChangedListener(new android.text.TextWatcher() {
            public void beforeTextChanged(CharSequence x, int a, int b, int c) {}
            public void onTextChanged(CharSequence x, int a, int b, int c) { filter(); }
            public void afterTextChanged(android.text.Editable e) {}
        });
        LinearLayout.LayoutParams sp = new LinearLayout.LayoutParams(-1, -2); search.setMinHeight(dp(48)); sp.setMargins(dp(12), dp(8), dp(12), dp(2));
        root.addView(search, sp);

        scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        body = new LinearLayout(this);
        body.setOrientation(LinearLayout.VERTICAL);
        body.setPadding(dp(12), dp(12), dp(12), dp(28));
        scroll.addView(body);
        root.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));

        setContentView(root);
        Ui.fitInsets(root, root);
    }

    private void render() {
        // An old build stored the wake flag with no engine behind it, and a revoked mic permission
        // stops the service without passing through here: never show it on when it can't listen.
        if (store.wakeEnabled() && (!WakeWordService.modelInstalled(this) || checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED)) {
            store.wakeEnabled(false);
            WakeWordService.stop(this);
        }
        body.setLayoutTransition(null);
        body.removeAllViews();
        previousSections = new java.util.HashSet<>(shownSections);
        shownSections.clear();
        sectionTitles.clear();
        sections.clear();
        sectionPages.clear();
        pageViews.clear();
        filtered = false;
        View heroCard = hero();
        body.addView(heroCard, match(-2));
        pageViews.put(heroCard, "");
        boolean hermes = "Hermes".equals(store.serverType());
        // Rarely touched options live on the Advanced page (their cards move to the end below).
        currentPage = "Advanced";
        LinearLayout advServer = section("SERVER & HUB");
        LinearLayout advAgent = section("PHONE AGENT LIMITS");
        LinearLayout advSpeech = section("SPEECH ENGINES");
        currentPage = "Privacy";

        LinearLayout trust = section("TRUST & CONTROL");
        addRow(trust, R.drawable.ic_bolt, 0xffb0793f, "Action mode", store.actionMode(), true,
                v -> Sheets.actionMode(this, store, this::render));
        addToggle(trust, R.drawable.ic_shield, 0xff4d675f, "Lock the app",
                AppLock.available(this) ? "Ask for your fingerprint or screen lock when Hermes opens, and hide it in Recents"
                        : "Set a screen lock on this phone first", store.appLock() && AppLock.available(this),
                checked -> {
                    if (checked && !AppLock.available(this)) { Toast.makeText(this, "Set a screen lock on this phone first", Toast.LENGTH_SHORT).show(); render(); return; }
                    // Turning it off needs the same proof as unlocking.
                    if (!checked) Confirm.strong(this, "Turn off app lock", "Confirm it\u2019s you", () -> store.appLock(false), this::render);
                    else store.appLock(true);
                });
        boolean directGranted = hasPermission(Manifest.permission.CALL_PHONE) && hasPermission(Manifest.permission.SEND_SMS);
        addToggle(trust, R.drawable.ic_phone, 0xffc06868, "Act directly",
                "Like Google Assistant: after you confirm, Hermes places calls, sends texts and saves calendar events itself instead of opening the app",
                store.directComms() && directGranted, checked -> {
                    store.directComms(checked);
                    if (checked && !hasPermission(Manifest.permission.CALL_PHONE) || checked && !hasPermission(Manifest.permission.SEND_SMS))
                        requestPermissions(new String[]{Manifest.permission.CALL_PHONE, Manifest.permission.SEND_SMS, Manifest.permission.READ_CONTACTS,
                                Manifest.permission.WRITE_CALENDAR, Manifest.permission.READ_CALENDAR}, DIRECT_REQ);
                });
        addToggle(trust, R.drawable.ic_bolt, 0xff3f82d0, "Act from anywhere",
                "Hermes on Telegram, voice or jobs can run phone actions here without opening apps; anything needing your OK asks with a notification",
                store.remotePhoneActions(), checked -> store.remotePhoneActions(checked));
        addRow(trust, R.drawable.ic_info, 0xff416f9f, "Permission centre",
                "Pause actions, review access and activity", false,
                v -> startActivity(new Intent(this, PermissionCentreActivity.class)));

        currentPage = "Connection";
        LinearLayout connection = section("CONNECTION");
        int saved = store.profilesForHarness().size();
        addRow(connection, R.drawable.ic_session, 0xff3f82d0, "Profile",
                store.profileName() + (saved > 1 ? " · " + saved + " saved · tap to switch" : " · tap to add another"), true, v -> Profiles.show(this, this::render));
        java.util.List<Sheets.Choice> serverTypeChoices = new java.util.ArrayList<>();
        serverTypeChoices.add(new Sheets.Choice("Hermes", "Hermes", "Hermes".equals(store.serverType())));
        serverTypeChoices.add(new Sheets.Choice("Claude Code", "Claude Code", "Claude Code".equals(store.serverType())));
        serverTypeChoices.add(new Sheets.Choice("Codex", "Codex remote: your terminal Codex sessions, live", "Codex".equals(store.serverType())));
        addRow(advServer, R.drawable.ic_server, 0xff4d675f, "Server type", store.serverType(), true,
                v -> Sheets.picker(this, "Server type", "Choose the agent server", serverTypeChoices,
                        choice -> { if (!store.serverType().equals(choice.title)) Harness.switchTo(this, choice.title); }));
        addRow(connection, R.drawable.ic_link, 0xff3f82d0, store.serverType() + " server",
                host(store.endpoint()), true, v -> startActivity(new Intent(this, ConnectionActivity.class)));
        addRow(connection, R.drawable.ic_link, 0xff4d675f, "Backup addresses",
                store.backupHosts().isEmpty() ? "None · used when the server address can't be reached" : store.backupHosts(), true, v -> editBackupHosts());
        addRow(connection, R.drawable.ic_skills, 0xff4d675f, "Modules", "Voice, media, capture, Codex, homelab and more: switch on, off and set up", true, v -> Modules.open(this));
        addRow(connection, R.drawable.ic_shield, 0xff4d675f, "Device certificate", certStatus(), true, v -> fetchCert());
        addRow(connection, R.drawable.ic_compose, 0xff318f88, "Essentials capture",
                store.essentialsReady() ? host(store.essentialsBase()) : "Not configured", true,
                v -> startActivity(new Intent(this, ConnectionActivity.class)));
        addRow(connection, R.drawable.ic_pulse, 0xff4d675f, "System",
                "Health, usage, host and services", true, v -> startActivity(new Intent(this, StatusActivity.class)));
        if (Modules.on(this, Modules.TERMINAL) && !store.hubUrl().isEmpty()) addRow(connection, R.drawable.ic_terminal, 0xff4d675f, "Terminal",
                "Shells and ssh on your server (Tailscale)", true, v -> startActivity(new Intent(this, TerminalActivity.class)));
        if (MediaModules.any(this)) addRow(connection, R.drawable.ic_download, 0xff916239, "Media",
                mediaSummary(), true, v -> startActivity(new Intent(this, MediaActivity.class)));
        if (hermes) addToggle(advServer, R.drawable.ic_shield, 0xff7658b6, "Chat through the gateway",
                "Turns run like Telegram chats: risky commands and code ask you instead of being blocked, Hermes can ask questions, cron results reach the phone. Falls back to the API when the gateway is out of reach",
                store.useGateway(), checked -> store.useGateway(checked));
        if (hermes) addRow(advServer, R.drawable.ic_server, 0xff318f88, "Server pack",
                "Hermes sets up or updates the server side with you, or you pick the modules yourself", true, v -> ServerSetup.offer(this, store, null));
        addRow(advServer, R.drawable.ic_pulse, 0xff916239, "Daily token budget",
                store.dailyTokenBudget() == 0 ? "Off" : store.dailyTokenBudget() + " tokens · warning in System", false,
                v -> Sheets.input(this, "Daily token budget", "0 turns off the local System warning", String.valueOf(store.dailyTokenBudget()), "Tokens", value -> {
                    try { store.dailyTokenBudget(Integer.parseInt(value)); render(); }
                    catch (NumberFormatException e) { Toast.makeText(this, "Enter a whole number", Toast.LENGTH_SHORT).show(); }
                }));
        if (hermes) addRow(connection, R.drawable.ic_clock, 0xff916239, "Scheduled jobs",
                "Cron-scheduled runs on your server", false, v -> startActivity(new Intent(this, JobsActivity.class)));

        currentPage = "Assistant";
        LinearLayout assistant = section("ASSISTANT");
        addRow(assistant, R.drawable.ic_assistant, 0xff7658b6, "Default assistant",
                "Side key, corner gesture and lock screen", true, v -> openAssistant());
        addToggle(assistant, R.drawable.ic_bolt, 0xffb0793f, "Act on this phone",
                "Timers, alarms, calls, navigation, apps…", store.deviceActions(),
                checked -> store.deviceActions(checked));
        addToggle(assistant, R.drawable.ic_info, 0xff416f9f, "Share phone context",
                "Time, battery, network with each message", store.shareDeviceContext(),
                checked -> store.shareDeviceContext(checked));
        addToggle(assistant, R.drawable.ic_share, 0xff5d56a0, "Share approximate location",
                "Sent with each message when enabled", store.shareLocation(),
                checked -> onShareLocationToggled(checked));
        addRow(assistant, R.drawable.ic_screen, 0xff318f88, "Screen control",
                (HermesAccessibilityService.enabled() ? "Enabled" : "Off") + " · Reads and taps on request. Sideloaded? Allow restricted settings in App info.", false,
                v -> startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)));
        AgentDisplay.State hidden = AgentDisplay.state(this);
        if (hidden == AgentDisplay.State.READY) {
            addToggle(assistant, R.drawable.ic_screen, 0xff318f88, "Work in the background",
                    "Hermes opens apps on its own hidden screen, so you can keep using your phone during tasks (via Shizuku)",
                    store.backgroundAgent(), checked -> { store.backgroundAgent(checked); if (checked) AgentDisplay.prepare(this); else AgentDisplay.release(); });
            if (store.backgroundAgent()) addRow(assistant, R.drawable.ic_screen, 0xff318f88, "Hermes' hidden screen",
                    AgentDisplay.label(hidden) + " · see it or bring the app to your screen", true, v -> showHiddenScreen());
        } else addRow(assistant, R.drawable.ic_screen, 0xff318f88, "Work in the background", AgentDisplay.label(hidden), true, v -> setUpShizuku(hidden));
        addToggle(assistant, R.drawable.ic_session, 0xff7658b6, "Separate assistant conversation",
                "Assistant gesture uses its own \"Assistant\" chat instead of the last one",
                store.assistantOwnSession(), checked -> store.assistantOwnSession(checked));
        addRow(advAgent, R.drawable.ic_bolt, 0xff3f82d0, "Phone lookups in a row",
                store.agentRounds() + " automatic phone lookups in a row before Hermes waits for you", false, v -> pickAgentRounds());
        addRow(advAgent, R.drawable.ic_bolt, 0xff3f82d0, "Max agent task steps",
                store.agentSteps() + " on-screen steps before a phone-agent task stops itself", false, v -> pickAgentSteps());
        addToggle(advAgent, R.drawable.ic_bolt, 0xff3f82d0, "Fast steps (Jev)",
                "Let a fast decision model take some on-screen steps between Hermes turns", store.fastSteps(),
                checked -> store.fastSteps(checked));
        addToggle(advAgent, R.drawable.ic_bolt, 0xffb0793f, "Ask only for risky steps (Jev)",
                "Ask mode only: skip confirming screen steps Jev judges safe", store.smartAsk(),
                checked -> store.smartAsk(checked));
        addRow(assistant, R.drawable.ic_bolt, 0xff3f82d0, "Assistant model",
                store.agentModel().isEmpty() ? "Default (" + PhoneAgent.DEFAULT_AGENT_MODEL + " if available)" : store.agentModel(),
                true, v -> pickAgentModel());

        currentPage = "Privacy";
        LinearLayout data = section("WHAT HERMES MAY READ ON THIS PHONE");
        addToggle(data, R.drawable.ic_clock, 0xff916239, "Calendar",
                "Upcoming events, so it can plan and find free slots", store.allowCalendar() && hasPermission(Manifest.permission.READ_CALENDAR),
                checked -> gate(checked, Manifest.permission.READ_CALENDAR, CALENDAR_REQ, store::allowCalendar));
        addToggle(data, R.drawable.ic_phone, 0xffc06868, "Contacts",
                "Look up numbers so it can call or text people by name", store.allowContacts() && hasPermission(Manifest.permission.READ_CONTACTS),
                checked -> gate(checked, Manifest.permission.READ_CONTACTS, CONTACTS_REQ, store::allowContacts));
        addToggle(data, R.drawable.ic_copy, 0xff5d56a0, "Clipboard",
                "Read what you copied when you ask about it", store.allowClipboard(),
                checked -> store.allowClipboard(checked));
        addToggle(data, R.drawable.ic_screen, 0xff318f88, "Screen content",
                "Read the current screen when asked (assistant overlay and phone tasks)", store.allowScreenContent(),
                checked -> { store.allowScreenContent(checked); store.useScreenContext(checked); });
        addToggle(data, R.drawable.ic_screen, 0xff318f88, "Send screenshots",
                "Lets Hermes see the screen during tasks. Needs a model that accepts images, e.g. deepseek/deepseek-v4.1-flash — pick it in the conversation's model menu",
                store.sendScreenshots(), store::sendScreenshots);
        TextView note = Ui.text(this, "Apps list, battery and next alarm are always available. Everything read is shown in the chat as it happens.", 12, Ui.FAINT, false);
        note.setPadding(dp(10), dp(8), dp(10), 0);
        body.addView(note);
        pageViews.put(note, "Privacy");

        currentPage = "Voice";
        LinearLayout voice = section("VOICE");
        addRow(advSpeech, R.drawable.ic_pulse, 0xff318f88, "Speech recognition",
                speechRecognitionLabel(), true, v -> pickSpeechRecognition());
        addRow(advSpeech, R.drawable.ic_download, 0xff3f82d0, "On-device model",
                OnDeviceModel.installed(this) ? OnDeviceModel.NAME + " · installed" : OnDeviceModel.NAME + " · download ~104 MB", true,
                v -> manageOnDeviceModel());
        addRow(advSpeech, R.drawable.ic_server, 0xff318f88, "Speech server",
                store.whisperAddress() + " · self-hosted speech-to-text", true, v -> editWhisper());
        addRow(voice, R.drawable.ic_session, 0xff3f82d0, "Speech language",
                languageLabel(store.speechLanguage()), true, v -> pickLanguage());
        addRow(advSpeech, R.drawable.ic_settings, 0xff7658b6, "Voice engine",
                engineLabel(), true, v -> pickEngine());
        addToggle(voice, R.drawable.ic_play, 0xff416f9f, "Speak replies",
                "Read answers aloud when you ask by voice", store.speakReplies(),
                checked -> store.speakReplies(checked));
        addRow(voice, R.drawable.ic_reply, 0xffb0793f, "After Hermes answers", afterAnswerLabel(), true, v -> pickAfterAnswer());
        addToggle(voice, R.drawable.ic_assistant, 0xff7658b6, "Wake word \"Hey Hermes\"", wakeSubtitle(),
                store.wakeEnabled(), checked -> onWakeToggled(checked));
        if (store.wakeEnabled()) addRow(voice, R.drawable.ic_tools, 0xff7658b6, "Wake word sensitivity",
                store.wakeSensitivity() + " · High wakes more easily but may wake by mistake", false,
                v -> Sheets.actions(this, "Wake word sensitivity", "High wakes more easily but may wake by mistake",
                        sensitivityItem("Low"), sensitivityItem("Normal"), sensitivityItem("High")));
        addRow(voice, R.drawable.ic_next, 0xff4d675f, "Speech rate", speechRateLabel(), false,
                v -> openSpeechRatePicker());
        addRow(voice, R.drawable.ic_palette, 0xff7658b6, "Voice pitch", pitchLabel(), false,
                v -> openPitchPicker());
        if (store.serverVoice()) addRow(voice, R.drawable.ic_voice, 0xff3f82d0, "Voice", voiceLabel(), true,
                v -> openVoicePicker());
        addToggle(voice, R.drawable.ic_compose, 0xff416f9f, "Speak while writing",
                "Start talking after the first sentence instead of waiting for the whole answer", store.streamSpeech(),
                checked -> store.streamSpeech(checked));
        addRow(voice, R.drawable.ic_send, 0xff3f82d0, "After I speak", "review".equals(store.voiceSend()) ? "Put the words in the box so I can check them first" : "Send straight away", true, v -> pickVoiceSend());
        addToggle(voice, R.drawable.ic_mic, 0xff7658b6, "Hold to talk", "Hold the chat mic button while you speak and let go to finish; slide up to cancel. A quick tap still opens the voice sheet", store.voiceHold(),
                checked -> store.voiceHold(checked));
        addRow(voice, R.drawable.ic_pause, 0xffb0793f, "Stop listening", pauseLabel(), true, v -> pickPause());
        addToggle(voice, R.drawable.ic_home, 0xff3f82d0, "Natural voice (homelab)",
                "Read replies with your server's Piper voice, English and Bulgarian; the phone voice is the fallback", store.serverVoice(),
                checked -> { store.serverVoice(checked); render(); });
        if (store.serverVoice()) addRow(voice, R.drawable.ic_link, 0xff318f88, "Voice server",
                (store.ttsAddress().isEmpty() ? ServerVoice.defaultFor(store.endpoint()) : store.ttsAddress()) + " \u00b7 tap to test", true, v -> editTtsServer());
        addToggle(voice, R.drawable.ic_bolt, 0xff7658b6, "Talk over Hermes",
                "Interrupt by speaking while it talks. Works on headphones or Bluetooth", store.bargeIn(),
                checked -> { store.bargeIn(checked); render(); });
        if (store.bargeIn()) addToggle(voice, R.drawable.ic_speaker, 0xffb0793f, "Also on the speaker",
                "May cut itself off from its own voice; best with a quiet room", store.bargeInSpeaker(),
                checked -> store.bargeInSpeaker(checked));
        addToggle(voice, R.drawable.ic_notification, 0xff3f82d0, "Read notifications aloud",
                ReplyListenerService.connected() ? "Into your earbuds while the screen is off. Apps and rules in Permissions" : "Needs notification access; Android will ask",
                store.readNotifications(), checked -> {
                    store.readNotifications(checked);
                    if (checked && !ReplyListenerService.connected()) startActivity(new Intent(android.provider.Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS));
                });
        addRow(voice, R.drawable.ic_phone, 0xff318f88, "Call Hermes now", "Start a hands-free call", false, v -> startCall());

        currentPage = "Appearance";
        LinearLayout chat = section("CHAT");
        if (hermes) addToggle(chat, R.drawable.ic_tools, 0xff4d675f, "Show reasoning",
                "Show the model's thinking as it streams in", store.showReasoning(),
                checked -> store.showReasoning(checked));
        addToggle(chat, R.drawable.ic_compose, 0xff318f88, "Open capture inbox first",
                "Only takes effect once Essentials capture is configured", store.openCaptureFirst(),
                checked -> store.openCaptureFirst(checked));

        currentPage = "Notifications";
        LinearLayout hub = section("NOTIFICATIONS");
        addRow(hub, R.drawable.ic_settings, 0xff916239, "Notifications",
                "Alerts, sounds and priority", false, v -> openNotificationSettings());
        addToggle(hub, R.drawable.ic_moon, 0xff5b5fa8, "Quiet hours",
                String.format(java.util.Locale.US, "%02d:00\u2013%02d:00 \u00b7 only critical alerts (a service down) make a sound", store.quietStart(), store.quietEnd()),
                store.quietHours(), checked -> store.quietHours(checked));
        if (store.quietHours()) addRow(hub, R.drawable.ic_clock, 0xff5b5fa8, "Quiet hours window",
                String.format(java.util.Locale.US, "%02d:00 to %02d:00", store.quietStart(), store.quietEnd()), true, v -> pickQuietWindow());
        addRow(advServer, R.drawable.ic_pulse, 0xff3f82d0, "Push relay",
                store.relayUrl().isEmpty() ? "Not set · for a shared push service" : host(store.relayUrl()), true, v -> editPushRelay());
        if (hermes) addRow(advServer, R.drawable.ic_server, 0xff4d675f, "Hub URL", host(store.hubUrl()), true, v -> editHubUrl());
        if (hermes) addRow(hub, R.drawable.ic_pulse, 0xff3f82d0, "Push notifications", pushStatusLabel(), true, v -> onPushRowTapped());
        if (hermes) addRow(advServer, R.drawable.ic_send, 0xff318f88, "Send test notification", "POST /notify to the hub", false, v -> sendTestNotification());

        currentPage = "Appearance";
        LinearLayout interfaceGroup = section("APPEARANCE");
        addRow(interfaceGroup, R.drawable.ic_palette, 0xff916239, "Style",
                Ui.concept.name + " · " + Ui.concept.blurb, true,
                v -> Sheets.concepts(this, Ui.concept, c -> Ui.switchTo(this, c)));
        addRow(interfaceGroup, R.drawable.ic_palette, 0xff7658b6, "Accent colour",
                accentName(), true, v -> Sheets.accents(this, store.uiAccent(), c -> { Ui.switchAccent(this, c); }));
        addRow(interfaceGroup, R.drawable.ic_menu, 0xff3f82d0, "Layout",
                Ui.layout.name + " · " + Ui.layout.blurb, true,
                v -> Sheets.layouts(this, Ui.layout, l -> Ui.switchLayout(this, l)));
        if (Ui.layout == Layout.TABS) addRow(interfaceGroup, R.drawable.ic_session, 0xff3f82d0, "Bottom bar tabs",
                tabsLabel(), true, v -> Sheets.tabs(this, Layout.parseTabs(store.uiTabs()), list -> { store.uiTabs(Layout.tabsToString(list)); render(); }));
        addRow(interfaceGroup, R.drawable.ic_tools, 0xff4d675f, "Tool transparency",
                "Commands, inputs, output and exit codes", false,
                v -> Sheets.info(this, "Tool transparency", "Every Hermes action stays inspectable.",
                        new String[][]{{"Terminal", "Command · output · exit code"},
                                {"Web and files", "Full input and result"},
                                {"Live runs", "Running, complete and failed states"}}));

        String version = versionName();
        currentPage = "About";
        LinearLayout about = section("ABOUT");
        String found = Updater.available(this);
        addRow(about, R.drawable.ic_info, 0xff5d56a0, "Hermes Companion", found == null ? "Version " + version + " · native Android" : "Update available: " + found,
                false, v -> Sheets.info(this, "Hermes Companion", "A mobile control surface for your private Hermes Agent.",
                        new String[][]{{"Build", version}, {"Microphone", "Never active in background"},
                                {"Storage", "Sessions remain on your server"}}));

        addRow(about, R.drawable.ic_info, 0xff3f82d0, "Check for updates", "Ask your hub for a newer version", true, v -> Updater.check(this, true));
        addRow(about, R.drawable.ic_info, 0xff4d675f, "Show introduction", "Replay the first-run tour", true,
                v -> startActivity(new Intent(this, OnboardingActivity.class).putExtra(OnboardingActivity.EXTRA_REPLAY, true)));

        TextView foot = Ui.text(this, "Private by architecture. Direct to your server.", 12, Ui.FAINT, false);
        foot.setGravity(Gravity.CENTER);
        foot.setPadding(0, dp(20), 0, dp(8));
        body.addView(foot, match(-2));

        pageViews.put(foot, "About");
        // Advanced cards were created first so rows could be added anywhere; show them last.
        for (int i = 0; i < 3; i++) { View[] sec = sections.get(i); body.removeView(sec[0]); body.removeView(sec[1]); body.addView(sec[0]); body.addView(sec[1]); }
        body.addView(categories(), 1, match(-2));
        applyPage();
        Ui.appearing(body);
        Ui.stagger(body);
        sectionsRendered = true;
        filter(); // keep an active search across re-renders

    }

    private LinearLayout categoryCard;

    /** The home page: one row per category instead of every setting at once. */
    private View categories() {
        LinearLayout wrap = new LinearLayout(this);
        wrap.setOrientation(LinearLayout.VERTICAL);
        wrap.setPadding(0, dp(14), 0, 0);
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setBackground(Ui.card(this, 1));
        String found = Updater.available(this);
        String[][] pages = {
                {"Connection", store.profileName() + " · " + host(store.endpoint())},
                {"Assistant", "Gesture, phone actions, assistant model"},
                {"Privacy", store.actionMode() + " · app lock · what Hermes may read"},
                {"Voice", (store.wakeEnabled() ? "Wake word on" : "Wake word off") + " · " + (store.speakReplies() ? "speaks replies" : "silent")},
                {"Notifications", store.quietHours() ? String.format(Locale.US, "Quiet %02d:00\u2013%02d:00", store.quietStart(), store.quietEnd()) : "Quiet hours off"},
                {"Appearance", Ui.concept.name + " · chat display"},
                {"Advanced", "Server & hub, agent limits, speech engines"},
                {"About", found == null ? "Version " + versionName() : "Update available: " + found}};
        int[] icons = {R.drawable.ic_link, R.drawable.ic_assistant, R.drawable.ic_shield, R.drawable.ic_mic,
                R.drawable.ic_settings, R.drawable.ic_palette, R.drawable.ic_tools, R.drawable.ic_info};
        int[] colors = {0xff3f82d0, 0xff7658b6, 0xff4d675f, 0xff318f88, 0xff916239, 0xffb0793f, 0xff5d56a0, 0xff416f9f};
        for (int i = 0; i < pages.length; i++) {
            String key = pages[i][0];
            addRow(card, icons[i], colors[i], pageTitle(key), pages[i][1], i < pages.length - 1, v -> { page = key; render(); scroll.scrollTo(0, 0); });
        }
        wrap.addView(card, match(-2));
        categoryCard = wrap;
        return wrap;
    }

    private static String pageTitle(String key) { return "Privacy".equals(key) ? "Privacy & control" : "Appearance".equals(key) ? "Appearance & chat" : key; }

    /** Show the category list, or only the chosen page's sections. */
    private void applyPage() {
        boolean home = page == null;
        barTitle.setText(home ? "Settings" : pageTitle(page));
        for (int i = 0; i < sections.size(); i++) {
            int vis = !home && page.equals(sectionPages.get(i)) ? View.VISIBLE : View.GONE;
            sections.get(i)[0].setVisibility(vis); sections.get(i)[1].setVisibility(vis);
        }
        for (java.util.Map.Entry<View, String> e : pageViews.entrySet())
            e.getKey().setVisibility((home ? "" : page).equals(e.getValue()) ? View.VISIBLE : View.GONE);
        if (categoryCard != null) categoryCard.setVisibility(home ? View.VISIBLE : View.GONE);
    }

    private View hero() {
        LinearLayout card = new LinearLayout(this);
        card.setGravity(Gravity.CENTER_VERTICAL);
        card.setPadding(dp(16), dp(16), dp(16), dp(16));
        card.setBackground(Ui.card(this, 2));
        IdentityGlyph glyph = new IdentityGlyph();
        glyph.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        card.addView(glyph, new LinearLayout.LayoutParams(dp(76), dp(76)));
        LinearLayout text = new LinearLayout(this);
        text.setOrientation(LinearLayout.VERTICAL);
        text.setPadding(dp(16), 0, 0, 0);
        TextView title = Ui.text(this, "Hermes", 22, Ui.TEXT, true);
        title.setLetterSpacing(-.02f);
        text.addView(title);
        text.addView(Ui.text(this, "Personal agent · " + host(store.endpoint()), 13, Ui.SUB, false));
        // render() runs again after the check (onResume's key check re-renders), so show the cached result, not "checking".
        connectionState = Ui.text(this, connOk == null ? "●  CHECKING CONNECTION" : connOk ? "●  ONLINE" : "●  UNREACHABLE", 11,
                connOk == null ? Ui.AMBER : connOk ? Ui.GREEN : Ui.RED, true);
        connectionState.setLetterSpacing(.06f);
        connectionState.setPadding(0, dp(8), 0, 0);
        text.addView(connectionState);
        card.addView(text, new LinearLayout.LayoutParams(0, -2, 1));
        return card;
    }

    private LinearLayout section(String title) {
        TextView label = Ui.label(this, title, Ui.BLUE);
        label.setPadding(dp(10), dp(20), 0, dp(8));
        body.addView(label, match(-2));
        sectionTitles.add(label);
        sections.add(new View[]{label, null});
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setBackground(Ui.card(this, 1));
        body.addView(card, match(-2));
        sections.get(sections.size() - 1)[1] = card;
        sectionPages.add(currentPage);
        shownSections.add(title);
        if (sectionsRendered && !previousSections.contains(title)) {
            label.post(() -> { Ui.fadeIn(label); Ui.fadeIn(card); });
        }
        return card;
    }

    private void addRow(LinearLayout card, int icon, int color, String title, String subtitle, boolean divider, View.OnClickListener click) {
        LinearLayout row = baseRow(icon, color, title, subtitle);
        if (click != null) {
            TextView arrow = Ui.text(this, "›", 25, Ui.FAINT, false);
            arrow.setGravity(Gravity.CENTER);
            arrow.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
            row.addView(arrow, new LinearLayout.LayoutParams(dp(26), dp(56)));
            row.setOnClickListener(click);
            row.setBackground(Ui.ripple(this, Color.TRANSPARENT, 16));
        }
        row.setMinimumHeight(Ui.space(this, 68));
        card.addView(row, match(-2));
        if (divider) addDivider(card);
    }

    private void addToggle(LinearLayout card, int icon, int color, String title, String subtitle, boolean checked, HermesSwitch.Listener listener) {
        LinearLayout row = baseRow(icon, color, title, subtitle);
        HermesSwitch sw = new HermesSwitch(this);
        sw.setChecked(checked);
        row.setContentDescription(title + ", " + (checked ? "On" : "Off"));
        sw.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        sw.setListener(value -> {
            row.setContentDescription(title + ", " + (value ? "On" : "Off"));
            listener.onChanged(value);
        });
        row.addView(sw, new LinearLayout.LayoutParams(dp(44), dp(44)));
        row.setOnClickListener(v -> sw.performClick());
        row.setMinimumHeight(Ui.space(this, 68));
        card.addView(row, match(-2));
    }

    /** Hides rows (and then whole sections) that don't match the search box; dividers only show unfiltered. */
    private void filter() {
        String q = search == null ? "" : search.getText().toString().trim().toLowerCase(Locale.ROOT);
        if (q.isEmpty()) { if (filtered) render(); return; } // clearing rebuilds rows exactly as render() makes them
        filtered = true;
        java.util.Set<View> keep = new java.util.HashSet<>();
        for (View[] sec : sections) { keep.add(sec[0]); keep.add(sec[1]); }
        for (int i = 0; i < body.getChildCount(); i++) if (!keep.contains(body.getChildAt(i))) body.getChildAt(i).setVisibility(View.GONE); // hero, chips, notes
        for (View[] sec : sections) {
            LinearLayout card = (LinearLayout) sec[1];
            if (card == null) continue;
            int shown = 0;
            for (int i = 0; i < card.getChildCount(); i++) {
                View v = card.getChildAt(i);
                boolean on = q.isEmpty() || v.getTag() instanceof String && ((String) v.getTag()).contains(q);
                v.setVisibility(on ? View.VISIBLE : View.GONE);
                if (on && v.getTag() != null) shown++;
            }
            int vis = q.isEmpty() || shown > 0 ? View.VISIBLE : View.GONE;
            sec[0].setVisibility(vis); card.setVisibility(vis);
        }
    }

    private LinearLayout baseRow(int icon, int color, String title, String subtitle) {
        LinearLayout row = new LinearLayout(this);
        row.setTag((title + " " + (subtitle == null ? "" : subtitle)).toLowerCase(Locale.ROOT));
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(Ui.space(this, 14), Ui.space(this, 4), Ui.space(this, 12), Ui.space(this, 4));
        ImageView image = new ImageView(this);
        row.addView(Ui.iconTile(this, icon, Ui.iconColor(color)), new LinearLayout.LayoutParams(dp(38), dp(38)));
        LinearLayout copy = new LinearLayout(this);
        copy.setOrientation(LinearLayout.VERTICAL);
        copy.setPadding(dp(14), 0, dp(6), 0);
        copy.addView(Ui.text(this, title, 15, Ui.TEXT, false));
        TextView sub = Ui.text(this, subtitle, 12, Ui.SUB, false);
        sub.setMaxLines(3);
        sub.setEllipsize(TextUtils.TruncateAt.END);
        copy.addView(sub);
        row.addView(copy, new LinearLayout.LayoutParams(0, -2, 1));
        return row;
    }

    private void addDivider(LinearLayout card) {
        View line = new View(this);
        line.setBackgroundColor(Ui.LINE);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, dp(1));
        lp.setMargins(dp(66), 0, 0, 0);
        card.addView(line, lp);
    }

    private Boolean connOk;

    private void checkConnection() {
        network.submit(() -> {
            HermesApi.Result r = Backends.create(store).health();
            runOnUiThread(() -> {
                connOk = r.ok;
                if (connectionState != null) {
                    connectionState.setText(r.ok ? "●  ONLINE" : "●  UNREACHABLE");
                    connectionState.setTextColor(r.ok ? Ui.GREEN : Ui.RED);
                }
            });
        });
    }

    private void onShareLocationToggled(boolean checked) {
        if (!checked) { store.shareLocation(false); return; }
        if (hasPermission(Manifest.permission.ACCESS_COARSE_LOCATION)) {
            store.shareLocation(true);
        } else {
            requestPermissions(new String[]{Manifest.permission.ACCESS_COARSE_LOCATION}, LOCATION_REQ);
        }
    }

    /** Turning a data toggle on also asks Android for the matching permission; denial turns it back off. */
    private void setUpShizuku(AgentDisplay.State state) {
        switch (state) {
            case NOT_INSTALLED:
                Sheets.info(this, "Work in the background", "Shizuku lets Hermes use a hidden second screen, the same way scrcpy and adb do, without root.",
                        new String[][]{{"1", "Install Shizuku from the Play Store (opening it now)"}, {"2", "Start it with Wireless debugging (Shizuku shows how)"},
                                {"3", "Come back here and allow Hermes"}});
                try { startActivity(new Intent(Intent.ACTION_VIEW, android.net.Uri.parse("market://details?id=" + AgentDisplay.SHIZUKU_PACKAGE))); }
                catch (Exception e) { startActivity(new Intent(Intent.ACTION_VIEW, android.net.Uri.parse("https://github.com/RikkaApps/Shizuku/releases"))); }
                break;
            case NOT_RUNNING: {
                Intent open = getPackageManager().getLaunchIntentForPackage(AgentDisplay.SHIZUKU_PACKAGE);
                if (open != null) startActivity(open);
                Toast.makeText(this, "Start Shizuku, then come back", Toast.LENGTH_LONG).show();
                break;
            }
            case NEEDS_PERMISSION: AgentDisplay.requestPermission(); break;
            default: Toast.makeText(this, AgentDisplay.label(state), Toast.LENGTH_SHORT).show();
        }
    }

    /** What Hermes' hidden screen shows right now, with a way to take that app over. */
    private void showHiddenScreen() {
        network.submit(() -> {
            AgentDisplay.ensure(this);
            byte[] jpeg = AgentDisplay.screenshot();
            runOnUiThread(() -> {
                android.app.Dialog d = new android.app.Dialog(this);
                d.requestWindowFeature(android.view.Window.FEATURE_NO_TITLE);
                LinearLayout box = new LinearLayout(this); box.setOrientation(LinearLayout.VERTICAL);
                box.setPadding(dp(14), dp(14), dp(14), dp(14)); box.setBackground(Ui.round(this, Ui.BAR, 18));
                if (jpeg.length > 0) {
                    ImageView img = new ImageView(this); img.setAdjustViewBounds(true);
                    img.setImageBitmap(android.graphics.BitmapFactory.decodeByteArray(jpeg, 0, jpeg.length));
                    box.addView(img, new LinearLayout.LayoutParams(-1, (int) (getResources().getDisplayMetrics().heightPixels * .6f)));
                } else box.addView(Ui.text(this, "Nothing on the hidden screen yet.", 14, Ui.SUB, false));
                TextView take = Ui.text(this, "Bring it to my screen", 15, Ui.BLUE, true);
                take.setPadding(0, dp(14), 0, dp(4));
                take.setOnClickListener(v -> network.submit(() -> { String r = AgentDisplay.showOnPhone(); runOnUiThread(() -> { Toast.makeText(this, r, Toast.LENGTH_SHORT).show(); d.dismiss(); }); }));
                box.addView(take);
                d.setContentView(box);
                if (d.getWindow() != null) { d.getWindow().setBackgroundDrawable(new android.graphics.drawable.ColorDrawable(Color.TRANSPARENT)); d.getWindow().setLayout((int) (getResources().getDisplayMetrics().widthPixels * .94f), -2); }
                d.show();
            });
        });
    }

    private void gate(boolean on, String permission, int request, java.util.function.Consumer<Boolean> setter) {
        setter.accept(on);
        if (on && !hasPermission(permission)) requestPermissions(new String[]{permission}, request);
    }

    private void pickAgentRounds() {
        int current = store.agentRounds();
        Sheets.actions(this, "Phone lookups in a row", "How many phone lookups Hermes may chain before waiting for you",
                roundsItem(1, current), roundsItem(3, current), roundsItem(5, current), roundsItem(8, current));
    }

    private Sheets.Item roundsItem(int n, int current) {
        return Sheets.Item.of(R.drawable.ic_bolt, Ui.BLUE_DARK, n + (n == 1 ? " step" : " steps"), null, () -> { store.agentRounds(n); render(); }).checked(n == current);
    }

    private void pickAgentSteps() {
        Sheets.input(this, "Max agent task steps", "How many on-screen steps a phone-agent task may take before it stops and waits for you",
                String.valueOf(store.agentSteps()), "Steps", value -> {
                    try { store.agentSteps(Integer.parseInt(value)); render(); }
                    catch (NumberFormatException e) { Toast.makeText(this, "Enter a whole number", Toast.LENGTH_SHORT).show(); }
                });
    }

    private void pickQuietWindow() {
        int[][] options = {{22, 7}, {23, 7}, {23, 8}, {0, 8}, {21, 6}};
        List<Sheets.Choice> choices = new ArrayList<>();
        for (int[] o : options) choices.add(new Sheets.Choice(String.format(java.util.Locale.US, "%02d:00 to %02d:00", o[0], o[1]), null,
                o[0] == store.quietStart() && o[1] == store.quietEnd()));
        Sheets.picker(this, "Quiet hours", "Alerts arrive silently in this window", choices, choice -> {
            for (int[] o : options) if (choice.title.equals(String.format(java.util.Locale.US, "%02d:00 to %02d:00", o[0], o[1]))) store.quietWindow(o[0], o[1]);
            render();
        });
    }

    private void pickAgentModel() {
        Toast.makeText(this, "Loading models…", Toast.LENGTH_SHORT).show();
        network.submit(() -> {
            try {
                final List<HermesApi.ModelOption> options = Backends.create(store).modelOptions();
                final List<Sheets.Choice> choices = new ArrayList<>();
                choices.add(new Sheets.Choice("Default", "Default = " + PhoneAgent.DEFAULT_AGENT_MODEL + " if available", store.agentModel().isEmpty()));
                for (HermesApi.ModelOption o : options) choices.add(new Sheets.Choice(o.id, o.provider, o.id.equals(store.agentModel())));
                runOnUiThread(() -> {
                    if (options.isEmpty()) { Toast.makeText(this, "The gateway reported no usable models", Toast.LENGTH_SHORT).show(); return; }
                    Sheets.picker(this, "Assistant model", "Used by \u201cHey Hermes\u201d, the assistant overlay and phone-agent tasks. Pick something fast.", choices, choice -> {
                        store.agentModel("Default".equals(choice.title) ? "" : choice.title);
                        render();
                    });
                });
            } catch (Exception e) {
                runOnUiThread(() -> Toast.makeText(this, "Could not load models: " + HermesApi.friendly(e), Toast.LENGTH_SHORT).show());
            }
        });
    }

    private void onContactsRowClicked() {
        if (!hasPermission(Manifest.permission.READ_CONTACTS)) {
            requestPermissions(new String[]{Manifest.permission.READ_CONTACTS}, CONTACTS_REQ);
        }
    }

    private String contactsSubtitle() {
        String state = hasPermission(Manifest.permission.READ_CONTACTS) ? "Granted" : "Not granted";
        return state + " · lets Hermes call or text people by name";
    }

    private boolean hasPermission(String permission) {
        return checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED;
    }

    @Override public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        boolean granted = grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED;
        if (requestCode == LOCATION_REQ) store.shareLocation(granted);
        if (requestCode == CALENDAR_REQ && !granted) store.allowCalendar(false);
        if (requestCode == CONTACTS_REQ && !granted) store.allowContacts(false);
        if (requestCode == DIRECT_REQ) {
            store.directComms(hasPermission(Manifest.permission.CALL_PHONE) && hasPermission(Manifest.permission.SEND_SMS));
            if (hasPermission(Manifest.permission.READ_CONTACTS)) store.allowContacts(true);
            if (hasPermission(Manifest.permission.READ_CALENDAR)) store.allowCalendar(true);
        }
        if (requestCode == WAKE_REQ && checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) { enableWake(); return; }
        if (body != null) render();
    }

    /** Says what the wake word will actually do: that depends on being the default assistant. */
    private String wakeSubtitle() {
        if (!store.wakeEnabled()) return "Say \"Hey Hermes\" hands-free · on-device, mic stays on";
        long remaining = WakeWordService.pauseRemaining(store);
        if (remaining > 0) return "Paused until " + android.text.format.DateFormat.format("HH:mm", System.currentTimeMillis() + remaining);
        if (!WakeWordService.isRunning()) return "Paused · open Hermes to resume";
        if (HermesVoiceInteractionService.active()) return "Listening · opens over any app";
        boolean held = Build.VERSION.SDK_INT >= 29 && getSystemService(android.app.role.RoleManager.class).isRoleHeld(android.app.role.RoleManager.ROLE_ASSISTANT);
        // An older build's assistant registration was incomplete; picking Hermes again activates the overlay.
        return held ? "Listening · pick Hermes as default assistant again" : "Listening · make Hermes the default assistant";
    }

    private Sheets.Item sensitivityItem(String level) {
        return Sheets.Item.of(R.drawable.ic_mic, Ui.BLUE_DARK, level, null,
                () -> { store.wakeSensitivity(level); render(); }).checked(level.equals(store.wakeSensitivity()));
    }

    private void onWakeToggled(boolean on) {
        if (!on) { store.wakeEnabled(false); WakeWordService.stop(this); render(); return; }
        boolean needNotify = android.os.Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED;
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED || needNotify) {
            requestPermissions(needNotify ? new String[]{Manifest.permission.RECORD_AUDIO, Manifest.permission.POST_NOTIFICATIONS}
                    : new String[]{Manifest.permission.RECORD_AUDIO}, WAKE_REQ);
            render();
            return;
        }
        enableWake();
    }

    /** Model first (one-time ~18 MB), then start listening and ask to be spared from Doze. */
    private void enableWake() {
        if (!WakeWordService.modelInstalled(this)) {
            android.app.ProgressDialog progress = new android.app.ProgressDialog(this);
            progress.setTitle("Downloading wake-word model");
            progress.setMessage("About 18 MB from GitHub, once · 0%");
            progress.setProgressStyle(android.app.ProgressDialog.STYLE_HORIZONTAL);
            progress.setMax(100);
            progress.setCancelable(false);
            progress.show();
            network.submit(() -> {
                try {
                    WakeWordService.installModel(this, percent -> runOnUiThread(() -> {
                        progress.setProgress(percent);
                        progress.setMessage(percent >= 99 ? "Verifying and installing…" : "About 18 MB from GitHub, once · " + percent + "%");
                    }));
                    runOnUiThread(() -> { progress.dismiss(); enableWake(); });
                } catch (Exception e) {
                    runOnUiThread(() -> { progress.dismiss(); render(); Toast.makeText(this, "Wake-word model download failed: " + e.getMessage(), Toast.LENGTH_LONG).show(); });
                }
            });
            return;
        }
        store.wakeEnabled(true);
        WakeWordService.start(this);
        try {
            android.os.PowerManager pm = (android.os.PowerManager) getSystemService(POWER_SERVICE);
            if (pm != null && !pm.isIgnoringBatteryOptimizations(getPackageName()))
                startActivity(new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, android.net.Uri.parse("package:" + getPackageName())));
        } catch (Throwable ignored) {
            // Not fatal: it still listens, Doze just pauses it more often.
        }
        if (body != null) body.postDelayed(this::render, 600);
    }

    private String speechRateLabel() {
        return String.format(java.util.Locale.US, "%.1fx", store.speechRate());
    }

    private void openSpeechRatePicker() {
        float current = store.speechRate();
        Sheets.actions(this, "Speech rate", "Playback speed for spoken replies",
                speechRateItem(0.8f, current), speechRateItem(1.0f, current),
                speechRateItem(1.2f, current), speechRateItem(1.5f, current), speechRateItem(1.8f, current));
    }

    private String accentName() {
        int c = store.uiAccent();
        for (int i = 0; i < Accent.COLORS.length; i++) if (Accent.COLORS[i] == c) return Accent.NAMES[i];
        return "Custom";
    }

    private String tabsLabel() {
        StringBuilder b = new StringBuilder();
        for (Layout.Tab t : Layout.parseTabs(store.uiTabs())) b.append(b.length() == 0 ? "" : " · ").append(t.label);
        return b.toString();
    }

    private String pauseLabel() {
        if (store.voiceManualStop()) return "Only when I tap Done";
        int ms = store.voicePauseMs();
        return ms <= 1300 && store.fastEndpoint() ? "As soon as I finish a sentence" : "After " + (ms / 1000f) + " s of quiet";
    }

    /** One choice for how a take ends: a finished sentence, a pause of some length, or only Done. */
    private void pickPause() {
        Sheets.actions(this, "Stop listening", "Longer lets you think mid-sentence without being cut off",
                pauseItem(1300, "As soon as I finish a sentence", "Quickest, may cut you off"), pauseItem(2000, "After a 2 s pause", "Default"),
                pauseItem(3000, "After a 3 s pause", "Time to think"), pauseItem(4500, "After a 4.5 s pause", "Long pauses"), pauseItem(7000, "After a 7 s pause", "Very patient"),
                Sheets.Item.of(R.drawable.ic_stop, Ui.BLUE_DARK, "Only when I tap Done", "Keeps going through every pause",
                        () -> { store.voiceManualStop(true); render(); }).checked(store.voiceManualStop()));
    }

    private Sheets.Item pauseItem(int ms, String title, String sub) {
        return Sheets.Item.of(R.drawable.ic_clock, Ui.BLUE_DARK, title, sub, () -> { store.voiceManualStop(false); store.fastEndpoint(true); store.voicePauseMs(ms); render(); })
                .checked(!store.voiceManualStop() && store.voicePauseMs() == ms);
    }

    private String afterAnswerLabel() {
        return store.callMode() ? "Stay on the line, like a call" : store.conversationMode() ? "Listen again after a spoken reply" : "Wait for me to tap the mic";
    }

    private void pickAfterAnswer() {
        Sheets.actions(this, "After Hermes answers", "What happens once a spoken reply ends",
                Sheets.Item.of(R.drawable.ic_mic, Ui.BLUE_DARK, "Wait for me", "Tap the mic to talk again", () -> { store.callMode(false); store.conversationMode(false); render(); })
                        .checked(!store.callMode() && !store.conversationMode()),
                Sheets.Item.of(R.drawable.ic_reply, Ui.BLUE_DARK, "Listen again", "Keeps the conversation going after each spoken reply", () -> { store.callMode(false); store.conversationMode(true); render(); })
                        .checked(!store.callMode() && store.conversationMode()),
                Sheets.Item.of(R.drawable.ic_phone, Ui.BLUE_DARK, "Stay on the line", "Like a call: screen stays awake, say \"hang up\" to end", () -> { store.callMode(true); store.conversationMode(true); render(); })
                        .checked(store.callMode()));
    }

    private void pickVoiceSend() {
        Sheets.actions(this, "After I speak", "What happens to the words when you finish",
                Sheets.Item.of(R.drawable.ic_send, Ui.BLUE_DARK, "Send straight away", "Fastest, like a voice message", () -> { store.voiceSend("send"); render(); }).checked("send".equals(store.voiceSend())),
                Sheets.Item.of(R.drawable.ic_edit, Ui.BLUE_DARK, "Put it in the box", "Check or add to it, then send yourself", () -> { store.voiceSend("review"); render(); }).checked("review".equals(store.voiceSend())));
    }

    private Sheets.Item speechRateItem(float rate, float current) {
        String label = String.format(java.util.Locale.US, "%.1fx", rate);
        return Sheets.Item.of(R.drawable.ic_voice, Ui.BLUE_DARK, label, null, () -> { store.speechRate(rate); render(); previewVoice(); }).checked(rate == current);
    }

    private String pitchLabel() {
        float p = store.speechPitch();
        return p == 1f ? "Normal" : String.format(java.util.Locale.US, "%.2fx", p);
    }

    private void openPitchPicker() {
        float current = store.speechPitch();
        float[] steps = {0.8f, 0.9f, 1.0f, 1.1f, 1.2f, 1.35f};
        String[] names = {"Deeper", "Slightly deeper", "Normal", "Slightly higher", "Higher", "Much higher"};
        Sheets.Item[] items = new Sheets.Item[steps.length];
        for (int i = 0; i < steps.length; i++) {
            final float p = steps[i];
            items[i] = Sheets.Item.of(R.drawable.ic_voice, Ui.BLUE_DARK, names[i], null,
                    () -> { store.speechPitch(p); render(); previewVoice(); }).checked(p == current);
        }
        Sheets.actions(this, "Voice pitch", "Plays a short sample when you pick one", items);
    }

    /** Homelab voices (Kokoro for English, Piper stays on Bulgarian by script): {server name, label}. */
    private static final String[][] VOICES = {
            {"", "Default (Heart)"},
            {"af_heart", "Heart \u00b7 warm, female (US)"}, {"af_nicole", "Nicole \u00b7 soft, female (US)"}, {"af_sarah", "Sarah \u00b7 clear, female (US)"},
            {"af_bella", "Bella \u00b7 expressive, female (US)"}, {"af_sky", "Sky \u00b7 light, female (US)"}, {"af_nova", "Nova \u00b7 bright, female (US)"},
            {"af_aoede", "Aoede \u00b7 female (US)"}, {"af_kore", "Kore \u00b7 female (US)"}, {"af_alloy", "Alloy \u00b7 female (US)"},
            {"af_jessica", "Jessica \u00b7 female (US)"}, {"af_river", "River \u00b7 female (US)"},
            {"bf_emma", "Emma \u00b7 female (UK)"}, {"bf_isabella", "Isabella \u00b7 female (UK)"}, {"bf_alice", "Alice \u00b7 female (UK)"}, {"bf_lily", "Lily \u00b7 female (UK)"},
            {"af_heart:0.5+af_nicole:0.5", "Heart + Nicole \u00b7 warm and soft (mix)"}, {"af_heart:0.5+bf_emma:0.5", "Heart + Emma \u00b7 warm, a little British (mix)"},
            {"af_nicole:0.5+bf_emma:0.5", "Nicole + Emma \u00b7 soft and composed (mix)"}, {"af_sarah:0.4+af_heart:0.6", "Sarah + Heart \u00b7 calm and warm (mix)"},
            {"af_heart:0.6+bf_isabella:0.4", "Heart + Isabella \u00b7 warm, mature (mix)"}, {"af_nicole:0.6+af_sarah:0.4", "Nicole + Sarah \u00b7 gentle and clear (mix)"},
            {"am_adam", "Adam \u00b7 deep, male (US)"}, {"am_michael", "Michael \u00b7 steady, male (US)"}, {"am_fenrir", "Fenrir \u00b7 strong, male (US)"},
            {"am_puck", "Puck \u00b7 playful, male (US)"}, {"am_echo", "Echo \u00b7 male (US)"}, {"am_onyx", "Onyx \u00b7 low, male (US)"},
            {"am_eric", "Eric \u00b7 male (US)"}, {"am_liam", "Liam \u00b7 male (US)"},
            {"bm_george", "George \u00b7 male (UK)"}, {"bm_fable", "Fable \u00b7 storyteller, male (UK)"}, {"bm_daniel", "Daniel \u00b7 male (UK)"}, {"bm_lewis", "Lewis \u00b7 male (UK)"},
            {"am_adam:0.5+bm_george:0.5", "Adam + George \u00b7 deep, a little British (mix)"}, {"am_michael:0.5+bm_daniel:0.5", "Michael + Daniel \u00b7 smooth (mix)"},
            {"am_onyx:0.5+bm_fable:0.5", "Onyx + Fable \u00b7 low storyteller (mix)"},
            {"piper", "Fast voice \u00b7 instant, more robotic (Piper)"},
    };

    private String voiceLabel() {
        String cur = store.ttsVoice();
        for (String[] v : VOICES) if (v[0].equals(cur)) return v[1];
        return cur;
    }

    private void openVoicePicker() {
        String cur = store.ttsVoice();
        Sheets.Item[] items = new Sheets.Item[VOICES.length];
        for (int i = 0; i < VOICES.length; i++) {
            final String[] v = VOICES[i];
            items[i] = Sheets.Item.of(R.drawable.ic_speaker, Ui.BLUE_DARK, v[1], null,
                    () -> { store.ttsVoice(v[0]); render(); previewVoice(); }).checked(v[0].equals(cur));
        }
        Sheets.actions(this, "Voice", "English voices; Bulgarian text always uses the Bulgarian voice", items);
    }

    private Speaker previewSpeaker;

    /** Say a short sample with the current voice, speed and pitch. */
    private void previewVoice() {
        try {
            if (previewSpeaker != null) previewSpeaker.shutdown();
            previewSpeaker = new Speaker(this, store);
            Speaker sp = previewSpeaker;
            sp.speak("Hi, this is how I sound. Ask me anything.", "voice-preview");
            if (body != null) body.postDelayed(() -> { if (previewSpeaker == sp) { sp.shutdown(); previewSpeaker = null; } }, 9000);
        } catch (Throwable ignored) { /* preview is best effort */ }
    }

    private String mediaSummary() {
        List<MediaModules.Module> m = MediaModules.cached(this);
        if (m.isEmpty()) return "TV, movies, downloads and media servers";
        StringBuilder b = new StringBuilder();
        for (MediaModules.Module x : m) b.append(b.length() == 0 ? "" : ", ").append(x.name);
        return b.toString();
    }

    private String certStatus() {
        if (!DeviceCert.present()) return "None · fetched from your hub over Tailscale; until then plain LAN connections are allowed";
        return "Encrypted connections only · valid until " + java.text.DateFormat.getDateInstance().format(new java.util.Date(DeviceCert.expiresMs()));
    }

    /** Asks the hub for this phone's certificate now (needs Tailscale; the hub checks the device is yours). */
    private void fetchCert() {
        if (store.hubUrl().isEmpty()) { Toast.makeText(this, "Pair with your hub first", Toast.LENGTH_SHORT).show(); return; }
        network.submit(() -> {
            String msg;
            try { DeviceCert.install(getApplicationContext(), new HubApi(store.hubUrl(), store.hubKey()).pairCert()); msg = "Certificate installed"; }
            catch (Exception e) { msg = "No certificate: " + HermesApi.friendly(e); }
            String m = msg;
            runOnUiThread(() -> { Toast.makeText(this, m, Toast.LENGTH_LONG).show(); render(); });
        });
    }

    /** Other ways to reach the same server; the app switches to whichever answers (see Reach). */
    private void editBackupHosts() {
        Sheets.input(this, "Backup addresses", "Tried in order when the server address doesn't answer. A host (Tailscale IP or MagicDNS name) "
                        + "also covers the hub; a full https URL (reverse proxy) serves the API at / and the hub at /hub. Separate with commas.",
                store.backupHosts(), "100.x.y.z, host.tailnet.ts.net", value -> {
                    store.backupHosts(value);
                    store.backupHostsEdited(true);
                    render();
                }, "Save", true);
    }

    /** Tests /health on save, so a typo shows up immediately instead of at the next push. */
    private void editHubUrl() {
        Sheets.input(this, "Hub URL", "hermes-hub server for push, events and ops (defaults to the Hermes host, port 8791)",
                store.customHubUrl(), "http://host:8791", value -> {
                    store.hubUrl(value);
                    render();
                    network.submit(() -> {
                        HermesApi.Result r = new HubApi(store.hubUrl(), store.hubKey()).health();
                        runOnUiThread(() -> Toast.makeText(this, r.text, Toast.LENGTH_LONG).show());
                    });
                }, "Save", true);
    }

    /** Push relay: URL first, then its key (stored encrypted). Empty URL clears the relay. */
    private void editPushRelay() {
        Sheets.input(this, "Push relay", "URL of a push relay someone runs for you (shared Firebase project)",
                store.relayUrl(), "https://relay.example.com", url -> {
                    store.relayUrl(url);
                    if (store.relayUrl().isEmpty()) { store.relayKey(""); render(); return; }
                    Sheets.input(this, "Relay key", "Sent as a Bearer token; stored encrypted on this device",
                            "", "Relay key", key -> {
                                if (!Keys.clean(key).isEmpty()) store.relayKey(key);
                                FcmService.ensureRegistered(this);
                                render();
                            }, "Save", true);
                }, "Next", true);
    }

    private String pushStatusLabel() {
        if (Build.VERSION.SDK_INT >= 33 && !hasPermission(Manifest.permission.POST_NOTIFICATIONS))
            return "Notifications blocked — tap to allow";
        if (!hasApiKey) return "Off";
        if (store.pushEnabled()) return "UnifiedPush via " + distributorLabel(store.pushDistributor());
        return "Polling every 15 min";
    }

    private static String distributorLabel(String pkg) { return PushReceiver.NTFY_PACKAGE.equals(pkg) ? "ntfy" : pkg; }

    private void onPushRowTapped() {
        if (Build.VERSION.SDK_INT >= 33 && !hasPermission(Manifest.permission.POST_NOTIFICATIONS)) {
            if (store.notificationPermissionAsked() && !shouldShowRequestPermissionRationale(Manifest.permission.POST_NOTIFICATIONS))
                openNotificationSettings();
            else {
                store.markNotificationPermissionAsked();
                requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, NOTIFY_REQ);
            }
            return;
        }
        String distributor = PushReceiver.findDistributor(this);
        if (distributor == null) {
            Sheets.confirm(this, "Install ntfy for push", "Install the ntfy app (F-Droid) for instant push; polling fallback is active.",
                    "Open F-Droid", false, () -> startActivity(new Intent(Intent.ACTION_VIEW,
                            android.net.Uri.parse("https://f-droid.org/packages/io.heckel.ntfy/"))));
            return;
        }
        boolean sent = PushReceiver.register(this);
        Toast.makeText(this, sent ? "Registering via " + distributorLabel(distributor) + "…" : "Could not reach the distributor", Toast.LENGTH_SHORT).show();
        render();
    }

    private void sendTestNotification() {
        Toast.makeText(this, "Sending…", Toast.LENGTH_SHORT).show();
        network.submit(() -> {
            String result;
            try { new HubApi(store.hubUrl(), store.hubKey()).notify("Test from phone", "Push works", "info", null, null, null, null); result = "Sent"; }
            catch (Exception e) { result = HermesApi.friendly(e); }
            String r = result;
            runOnUiThread(() -> Toast.makeText(this, r, Toast.LENGTH_LONG).show());
        });
    }

    private void editTtsServer() {
        Sheets.input(this, "Voice server", "host:port of a Wyoming TTS server (Piper), e.g. myserver:10200. Leave empty for your Hermes host.",
                store.ttsAddress(), "host:10200", value -> {
                    store.ttsAddress(value);
                    render();
                    String address = store.ttsAddress().isEmpty() ? ServerVoice.defaultFor(store.endpoint()) : store.ttsAddress();
                    Toast.makeText(this, "Testing " + address + "…", Toast.LENGTH_SHORT).show();
                    network.submit(() -> {
                        String result;
                        try { result = ServerVoice.probe(address); }
                        catch (Exception e) { result = "Can't reach the voice server: " + HermesApi.friendly(e); }
                        String r = result;
                        runOnUiThread(() -> Toast.makeText(this, r, Toast.LENGTH_LONG).show());
                    });
                }, "Save", true);
    }

    private void startCall() {
        android.os.Bundle args = new android.os.Bundle();
        args.putBoolean(HermesVoiceSession.ARG_CALL, true);
        if (!HermesVoiceInteractionService.show(args, 0))
            Toast.makeText(this, "Make Hermes your digital assistant first (Android Settings \u203a Apps \u203a Default apps).", Toast.LENGTH_LONG).show();
    }

    /** Test the Wyoming Whisper server with a short silent clip before saving the address. */
    private void editWhisper() {
        Sheets.input(this, "Speech server", "host:port of a Wyoming server (e.g. myserver:10300), or https://your-domain to go through the hub from anywhere. "
                        + "Empty uses your Hermes server, and https on its own when the voice port is out of reach. Voice never uses Google.",
                store.customWhisperAddress(), "host:10300", value -> {
                    store.whisperAddress(value);
                    render();
                    String address = store.whisperAddress();
                    Toast.makeText(this, "Testing " + address + "…", Toast.LENGTH_SHORT).show();
                    network.submit(() -> {
                        String result;
                        try { result = SpeechRecognition.test(store); }
                        catch (Exception e) { result = "Can't reach the speech server: " + HermesApi.friendly(e); }
                        String r = result;
                        runOnUiThread(() -> Toast.makeText(this, r, Toast.LENGTH_LONG).show());
                    });
                }, "Save", true);
    }

    private static final String[][] LANGUAGES = {{"en", "English"}, {"auto", "Auto-detect (often wrong on-device)"}, {"el", "Greek"}, {"de", "German"}, {"bg", "Bulgarian"},
            {"fr", "French"}, {"es", "Spanish"}, {"it", "Italian"}, {"nl", "Dutch"}, {"pl", "Polish"}, {"ru", "Russian"}, {"tr", "Turkish"}};

    private static String languageLabel(String code) {
        for (String[] l : LANGUAGES) if (l[0].equals(code)) return l[1];
        return code;
    }

    private void pickLanguage() {
        java.util.List<Sheets.Choice> choices = new java.util.ArrayList<>();
        for (String[] l : LANGUAGES) choices.add(new Sheets.Choice(l[1], l[0], l[0].equals(store.speechLanguage())));
        Sheets.picker(this, "Speech language", "Pick the language you speak. Auto-detect guesses from each clip and often picks the wrong one.", choices, c -> { store.speechLanguage(c.subtitle); render(); });
    }

    private String speechRecognitionLabel() {
        switch (store.speechRecognition()) {
            case "phone": return SpeechRecognition.source("phone", true);
            case "server": return "Homelab server · live words; this phone only if the server is out of reach";
            default: return "Auto · homelab server, live words" + (OnDeviceModel.installed(this) ? "; this phone if the server is out of reach" : "");
        }
    }

    private void pickSpeechRecognition() {
        java.util.List<Sheets.Choice> choices = new java.util.ArrayList<>();
        choices.add(new Sheets.Choice("Auto", "auto", "auto".equals(store.speechRecognition())));
        choices.add(new Sheets.Choice("On this phone", "phone", "phone".equals(store.speechRecognition())));
        choices.add(new Sheets.Choice("Homelab server", "server", "server".equals(store.speechRecognition())));
        Sheets.picker(this, "Speech recognition", "Homelab server: best model, words appear as you speak, works away from home too. "
                        + "On this phone: " + OnDeviceModel.NAME + ", private, no live words. Auto uses the server and falls back to the phone model when it is downloaded.",
                choices, choice -> { store.speechRecognition(choice.subtitle); render(); });
    }

    private void manageOnDeviceModel() {
        if (OnDeviceModel.installed(this)) {
            Sheets.confirm(this, "Delete on-device model?", "Removes the downloaded model. Without it there is no fallback when the server is out of reach.",
                    "Delete", true, () -> network.submit(() -> {
                        OnDeviceModel.delete(this);
                        runOnUiThread(this::render);
                    }));
            return;
        }
        Sheets.confirm(this, "Download " + OnDeviceModel.NAME + "?",
                "About 104 MB from GitHub. Stored privately on this phone. English only, works without a server.",
                "Download", false, this::downloadOnDeviceModel);
    }

    private void downloadOnDeviceModel() {
        android.app.ProgressDialog progress = new android.app.ProgressDialog(this);
        progress.setTitle("Downloading on-device model");
        progress.setMessage("From GitHub · 0%");
        progress.setProgressStyle(android.app.ProgressDialog.STYLE_HORIZONTAL);
        progress.setMax(100);
        progress.setCancelable(false);
        progress.show();
        network.submit(() -> {
            try {
                OnDeviceModel.install(this, percent -> runOnUiThread(() -> {
                    progress.setProgress(percent);
                    progress.setMessage(percent == 100 ? "Ready" : percent == 99 ? "Verifying and installing…" : "From GitHub · " + percent + "%");
                }));
                runOnUiThread(() -> { progress.dismiss(); render(); Toast.makeText(this, "On-device speech recognition ready", Toast.LENGTH_LONG).show(); });
            } catch (Exception e) {
                runOnUiThread(() -> { progress.dismiss(); Toast.makeText(this, "Model download failed: " + e.getMessage(), Toast.LENGTH_LONG).show(); });
            }
        });
    }

    private String engineLabel() {
        String picked = Speaker.pickEngine(this, store.ttsEngine());
        if (picked == null) return "System default";
        for (android.speech.tts.TextToSpeech.EngineInfo e : Speaker.engines(this)) if (e.name.equals(picked)) return e.label + (Speaker.GOOGLE.equals(e.name) ? "" : " · not Google");
        return picked;
    }

    private void pickEngine() {
        java.util.List<Sheets.Choice> choices = new java.util.ArrayList<>();
        String current = Speaker.pickEngine(this, store.ttsEngine());
        for (android.speech.tts.TextToSpeech.EngineInfo e : Speaker.engines(this))
            choices.add(new Sheets.Choice(e.label, e.name, e.name.equals(current)));
        if (choices.isEmpty()) { Toast.makeText(this, "No text-to-speech engines installed", Toast.LENGTH_SHORT).show(); return; }
        Sheets.picker(this, "Voice engine", "Used to read replies aloud", choices, c -> { store.ttsEngine(c.subtitle); render(); });
    }

    private void openNotificationSettings() {
        try {
            Intent intent = new Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS);
            intent.putExtra(Settings.EXTRA_APP_PACKAGE, getPackageName());
            startActivity(intent);
        } catch (Exception e) {
            startActivity(new Intent(Settings.ACTION_SETTINGS));
        }
    }

    private void openAssistant() {
        try { startActivity(new Intent("android.settings.VOICE_INPUT_SETTINGS")); }
        catch (Exception e) { startActivity(new Intent(Settings.ACTION_MANAGE_DEFAULT_APPS_SETTINGS)); }
    }

    private void openVoiceSettings() {
        try { startActivity(new Intent(Settings.ACTION_VOICE_INPUT_SETTINGS)); }
        catch (Exception e) { openAssistant(); }
    }

    private String versionName() {
        try {
            PackageInfo info = getPackageManager().getPackageInfo(getPackageName(), 0);
            return info.versionName == null ? "" : info.versionName;
        } catch (Exception e) { return ""; }
    }

    private static String host(String endpoint) {
        try {
            java.net.URL u = new java.net.URL(endpoint);
            return u.getHost() + ":" + (u.getPort() > 0 ? u.getPort() : u.getDefaultPort()) + ("/".equals(u.getPath()) ? "" : u.getPath());
        } catch (Exception e) { return endpoint; }
    }

    private static int light(int c) {
        int r = Math.min(255, ((c >> 16) & 255) + 26), g = Math.min(255, ((c >> 8) & 255) + 26), b = Math.min(255, (c & 255) + 26);
        return 0xff000000 | (r << 16) | (g << 8) | b;
    }

    @Override protected void onDestroy() { super.onDestroy(); network.shutdownNow(); if (previewSpeaker != null) { previewSpeaker.shutdown(); previewSpeaker = null; } }

    private final class IdentityGlyph extends View {
        private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        IdentityGlyph() { super(SettingsActivity.this); }
        protected void onDraw(Canvas c) {
            float d = getResources().getDisplayMetrics().density, cx = getWidth() / 2f, cy = getHeight() / 2f;
            p.setShader(new LinearGradient(0, 0, getWidth(), getHeight(), 0xff5a9cf4, 0xff6d62c8, Shader.TileMode.CLAMP));
            c.drawCircle(cx, cy, 30 * d, p);
            p.setShader(null);
            p.setStyle(Paint.Style.STROKE);
            p.setStrokeWidth(1 * d);
            p.setColor(0x6699c7ff);
            c.drawCircle(cx, cy, 35 * d, p);
            p.setStyle(Paint.Style.FILL);
            p.setColor(Color.WHITE);
            p.setTextSize(25 * d);
            p.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
            p.setTextAlign(Paint.Align.CENTER);
            c.drawText("H", cx, cy + 9 * d, p);
            p.setColor(Ui.GREEN);
            c.drawCircle(cx + 27 * d, cy + 20 * d, 4 * d, p);
            p.setStyle(Paint.Style.STROKE);
            p.setStrokeWidth(2 * d);
            p.setColor(Ui.BAR);
            c.drawCircle(cx + 27 * d, cy + 20 * d, 5 * d, p);
            p.setStyle(Paint.Style.FILL);
        }
    }
    @Override public void onBackPressed() {
        if (search != null && search.getText().length() > 0) { search.setText(""); return; }
        if (page != null) { page = null; render(); scroll.scrollTo(0, 0); return; }
        Ui.back(this);
    }
}
