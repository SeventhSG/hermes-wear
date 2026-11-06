package cloud.stormlabs.hermescompanion;

import android.Manifest;
import android.app.Activity;
import android.app.Dialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.res.ColorStateList;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.text.Editable;
import android.text.TextUtils;
import android.text.TextWatcher;
import android.text.SpannableString;
import android.text.Spanned;
import android.text.style.BackgroundColorSpan;
import android.text.style.StyleSpan;
import android.view.Gravity;
import android.view.HapticFeedbackConstants;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.view.animation.DecelerateInterpolator;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * The conversation screen.
 *
 * Runs are owned by {@link RunManager}, not by this Activity: the screen attaches as an observer,
 * draws the live run (streamed Markdown, thinking, tool cards, approvals) at the bottom of the
 * transcript, and reloads the server transcript when the run ends. Leaving the screen never
 * interrupts Hermes.
 */
public final class ChatActivity extends Activity implements RunManager.Observer {
    public static final String EXTRA_SESSION_ID = "session_id", EXTRA_TITLE = "title", EXTRA_MODEL = "model", EXTRA_PROMPT = "prompt",
            EXTRA_VOICE = "voice", EXTRA_IMAGES = "images", EXTRA_AUTOSEND = "autosend", EXTRA_DICTATE = "dictate";
    private static final int CONTACTS_REQ = 95;
    private static final int MIC_REQ = 92, CAMERA_REQ = 81, GALLERY_REQ = 82, FILE_REQ = 83, DOCUMENT_REQ = 84, NOTIFY_REQ = 93;
    private static final int PAGE = 60, MAX_IMAGES = 6;
    private static final String[][] COMMANDS = {
            {"new", "Start a fresh conversation"}, {"sessions", "Switch conversation"}, {"stop", "Stop the active run"},
            {"steer", "Redirect the active run"}, {"model", "Change this conversation's model"}, {"fork", "Branch this conversation"},
            {"rename", "Rename this conversation"}, {"pin", "Pin or unpin this conversation"}, {"archive", "Archive this conversation"},
            {"delete", "Delete this conversation"}, {"skills", "Browse installed skills"}, {"tools", "Browse toolsets"},
            {"jobs", "Scheduled jobs"}, {"status", "Server status and usage"}, {"media", "TV, movies and downloads"}, {"health", "Test the API connection"},
            {"voice", "Talk to Hermes"}, {"export", "Share this transcript"}, {"server", "Audit homelab services"},
            {"home", "Check Home Assistant"}, {"next", "Highest-value next actions"}, {"settings", "Companion settings"}, {"profile", "Switch Hermes profile or server"},
            {"assistant", "Set Hermes as the phone's assistant"}, {"undo", "Back up turns and re-prompt (/undo N)"},
            {"retry", "Run the last prompt again"}, {"rollback", "Restore a filesystem checkpoint"}};

    private SettingsStore store; private AgentBackend api; private RunManager runs;
    private String apiProfile;
    private final ExecutorService network = Executors.newCachedThreadPool();
    private final Handler ui = new Handler(Looper.getMainLooper());
    private String sessionId, title, model;
    private boolean pinned, resumed, resumedBefore, lastInputVoice, liveDirty, hasMore;
    private int loadedCount;

    private TextView titleView, subView, runStrip, replyText, attachLabel, jumpButton, lastTime;
    private View topLine, composerLine; private boolean dragging;
    private View screenControlReminder;
    private LinearLayout messages, liveBox, replyBar, commandPanel, attachBar, attachThumbs;
    private ScrollView scroller; private EditText composer; private ImageButton sendMic, accessChip, voiceKeyboard;
    private TextView voiceHint, effortChip, permsChip; private LinearLayout composerPill, agentStrip; private boolean typingMode;
    private ContextRing contextRing;
    private UsageState usageState = UsageState.parse(null);
    private UsageSheet usageSheet;
    private int usageRequest, usageEventVersion, agentSettingsRequest;
    private JSONObject agentSettings = new JSONObject();
    private TextView planChip, fastChip;
    private boolean modeSaving, modeConfirming, usageResetting;
    private static final java.util.Set<String> confirmedModes = new java.util.LinkedHashSet<>();
    private ChatViews.Sweep sweep;
    private final java.util.Set<Long> freshMessages = new java.util.HashSet<>();
    private RunManager.LiveRun animatedRun;
    private boolean animatedUser, animatedReply, animatedTyping;
    private HermesApi.Message replyTarget; private Speaker speaker;
    private final List<HermesApi.Message> history = new ArrayList<>();
    private java.util.Map<String, String> actionReports = new java.util.HashMap<>();
    private final List<String> pendingImages = new ArrayList<>();
    /** Text files ride inline as fenced blocks: the gateway rejects file parts on this endpoint. */
    private final List<String[]> pendingFiles = new ArrayList<>();
    private Uri pendingCameraUri;
    private VoiceSheet.Mode requestedVoiceMode = VoiceSheet.Mode.CHAT;
    private String lastDraft;

    private int dp(int n) { return Ui.dp(this, n); }
    private LinearLayout.LayoutParams match(int h) { return new LinearLayout.LayoutParams(-1, h < 0 ? h : dp(h)); }

    // ── lifecycle ───────────────────────────────────────────────────────────────────────────────

    @Override public void onCreate(Bundle b) {
        super.onCreate(b);
        CrashReporter.install(this);
        Ui.legacyWindowInsets(this);
        store = new SettingsStore(this);
        apiProfile = store.profileId();
        runs = RunManager.get(this);
        sessionId = getIntent().getStringExtra(EXTRA_SESSION_ID);
        if (sessionId == null || sessionId.isEmpty()) { startActivity(new Intent(this, MainActivity.class).putExtra(MainActivity.EXTRA_OPEN_CHAT, true)); finish(); return; }
        title = n(getIntent().getStringExtra(EXTRA_TITLE), store.serverType());
        model = HermesApi.clean(n(getIntent().getStringExtra(EXTRA_MODEL), ""));
        api = client();
        getWindow().setStatusBarColor(Ui.BAR); getWindow().setNavigationBarColor(Ui.BG);
        buildUi();
        initTts();
        composer.setText(store.draft(sessionId));
        composer.setSelection(composer.length());
        loadMessages(true);
        // A restored activity (process killed in the background, reopened from recents) gets its launch intent
        // back with the prompt still in it; handling it again re-sent a message the user never typed.
        if (b == null && (getIntent().getFlags() & Intent.FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY) == 0) handleLaunchExtras(getIntent());
        refreshSessionMeta();
    }

    @Override protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        String target = intent.getStringExtra(EXTRA_SESSION_ID);
        if (target != null && !target.isEmpty() && !target.equals(sessionId)) {
            saveDraft();
            openSession(target, n(intent.getStringExtra(EXTRA_TITLE), agentName()), HermesApi.clean(n(intent.getStringExtra(EXTRA_MODEL), "")));
        }
        handleLaunchExtras(intent);
    }

    /** Voice, a prompt to send, or images shared into the conversation. */
    private void handleLaunchExtras(Intent intent) {
        ArrayList<Uri> images = intent.getParcelableArrayListExtra(EXTRA_IMAGES);
        if (images != null) for (Uri u : images) stageImage(u);
        String prompt = intent.getStringExtra(EXTRA_PROMPT);
        boolean autosend = intent.getBooleanExtra(EXTRA_AUTOSEND, true)
                && store.intentSecret().equals(intent.getStringExtra(MainActivity.EXTRA_PROFILE_SECRET));
        boolean withImages = images != null && !images.isEmpty();
        if (prompt != null && (!prompt.trim().isEmpty() || (withImages && autosend))) {
            intent.removeExtra(EXTRA_PROMPT);
            if (autosend && withImages) sendAfterImages = prompt; // Ask Hermes on shared images: send once they are staged
            else if (autosend) ui.postDelayed(() -> sendText(prompt, false), 250);
            else loadIntoComposer(prompt);
        }
        if (intent.getBooleanExtra(EXTRA_VOICE, false)) { intent.removeExtra(EXTRA_VOICE); composer.postDelayed(this::startVoice, 450); }
        else if (intent.getBooleanExtra(EXTRA_DICTATE, false)) { intent.removeExtra(EXTRA_DICTATE); composer.postDelayed(() -> startVoice(VoiceSheet.Mode.DICTATE), 450); }
    }

    @Override protected void onResume() {
        super.onResume();
        WhatsNew.maybeShow(this);
        Updater.check(this, false);
        updateAccess();
        refreshAgentStrip();
        refreshUsage(false);
        KitWake.poke(store);
        screenControlReminder.setVisibility(store.shouldShowScreenControlReminder(HermesAccessibilityService.listed(this)) ? View.VISIBLE : View.GONE);
        WakeWordService.ensure(this);
        // Agent follow-ups and other devices add turns while we're away; onCreate already loaded once.
        if (resumedBefore && !busy() && loadedCount <= PAGE && isNearBottom()) loadMessages(false);
        if (resumedBefore) refreshSessionMeta(); // an agent task may have pinned another model
        resumedBefore = true;
        resumed = true;
        runs.addObserver(this);
        runs.setVisibleSession(apiProfile, sessionId);
        PhoneAgent.attachFallback(this, apiProfile, sessionId);
        attachLiveRun();
        ui.removeCallbacks(codexWatch); ui.postDelayed(codexWatch, 4000);
        PushPoller.schedule(this);
        PushPoller.pollOnce(this);
        loadServerCommands();
        adoptBackupAddresses();
        AgentDisplay.prepare(this);
        AgentDisplay.warnIfStopped(this);
    }

    private static long backupsAdoptedAt;

    /** True when at least {@code everyMs} passed since {@code last} (also if the clock went backwards). */
    static boolean due(long last, long now, long everyMs) { return now < last || now - last >= everyMs; }

    /** Addresses the hub lists (LAN, Tailscale, public URL) join the backup list, so the app keeps working on and off the LAN.
     *  Adds only, never removes; stops once the user edits the list by hand. */
    private void adoptBackupAddresses() {
        long now = System.currentTimeMillis();
        if (!due(backupsAdoptedAt, now, 60_000)) return;   // every screen unlock must not fan out hub calls
        backupsAdoptedAt = now;
        if (features().hub) network.submit(() -> { try { MediaModules.refresh(getApplicationContext(), hub()); } catch (Exception ignored) {} });
        if (features().hub && DeviceCert.needsRenewal()) network.submit(() -> {
            try { DeviceCert.install(getApplicationContext(), hub().pairCert()); } catch (Exception ignored) {} // retried on the next chat open
        });
        if (store.backupHostsEdited() || !features().hub) return;
        network.submit(() -> {
            try {
                String merged = Reach.merge(store.backupHosts(), hub().backupAddresses());
                if (!merged.equals(store.backupHosts()) && !store.backupHostsEdited()) store.backupHosts(merged);
            } catch (Exception ignored) {}
        });
    }

    @Override protected void onPause() {
        super.onPause();
        resumed = false;
        runs.clearVisibleSession(apiProfile, sessionId);
        runs.removeObserver(this);
        PhoneAgent.detachFallback(this);
        saveDraft();
    }

    @Override protected void onDestroy() {
        MarkdownView.Inline.stop();
        super.onDestroy();
        ui.removeCallbacksAndMessages(null);   // delayed sends/title checks would hit the shut-down executor
        if (usageSheet != null) usageSheet.dismiss();
        network.shutdownNow();
        if (speaker != null) speaker.shutdown();
        if (hold != null) hold.shutdown();
    }

    private AgentBackend client() { return Backends.create(store, apiProfile); }
    private Capabilities features() { return api.capabilitiesForUi(); }
    private Sheets.Item[] visibleItems(Sheets.Item... items) {
        List<Sheets.Item> visible = new ArrayList<>();
        Capabilities c = features();
        for (Sheets.Item item : items) {
            String title = item.title;
            if (!"Hermes".equals(Backends.type(api)) && ChatLogic.hermesAction(title)) continue;
            if ((title.equals("Skills") || title.equals("Use a skill")) && !c.skills) continue;
            if ((title.equals("Schedule") || title.equals("Schedule this")) && !c.jobs) continue;
            if ((title.equals("Audit server")) && !c.hub) continue;
            if (title.equals("Media") && (!c.hub || !MediaModules.any(this))) continue;
            if (title.equals("Terminal") && (!c.hub || !Modules.on(this, Modules.TERMINAL))) continue;
            if (title.equals("Recall") && !c.sessionSearch) continue;
            if (title.equals("Model") && !c.models) continue;
            if ((title.equals("Reasoning effort") || title.equals("Agent permissions")) && !api.supportsAgentSettings()) continue;
            if (title.equals("Fork") && !c.forks) continue;
            if (title.equals("Delete") && !c.deletes) continue;
            if ((title.equals("Camera") || title.equals("Photos") || title.equals("Receipt") || title.equals("Take photo") || title.equals("Choose photos")) && !c.images) continue;
            visible.add(item);
        }
        return visible.toArray(new Sheets.Item[0]);
    }
    private void saveDraft() { if (composer != null && sessionId != null) store.draft(sessionId, composer.getText().toString()); }

    // ── layout ──────────────────────────────────────────────────────────────────────────────────

    private void buildUi() {
        FrameLayout root = new FrameLayout(this); Ui.ambient(root);
        LinearLayout column = new LinearLayout(this); column.setOrientation(LinearLayout.VERTICAL);
        root.addView(column, new FrameLayout.LayoutParams(-1, -1));
        FrameLayout headBox = new FrameLayout(this);
        headBox.addView(buildHeader(), new FrameLayout.LayoutParams(-1, -1));
        topLine = new View(this); topLine.setBackgroundColor(Ui.LINE); topLine.setAlpha(0f); // appears only once content scrolls under the bar
        headBox.addView(topLine, new FrameLayout.LayoutParams(-1, Math.max(1, dp(1)), Gravity.BOTTOM));
        column.addView(headBox, match(focus() || voiceLayout() ? 48 : 58));
        screenControlReminder = Ui.screenControlReminder(this, store);
        column.addView(screenControlReminder);
        runStrip = Ui.caption(this, "", Ui.SUB); runStrip.setTypeface(Ui.WORD_SEMI);
        runStrip.setGravity(Gravity.CENTER_VERTICAL); runStrip.setPadding(dp(16), 0, dp(16), 0);
        runStrip.setBackgroundColor(Ui.RAISED); runStrip.setSingleLine(true); runStrip.setEllipsize(TextUtils.TruncateAt.END);
        runStrip.setVisibility(View.GONE);
        runStrip.setOnClickListener(v -> confirmStop());
        column.addView(runStrip, match(34));
        FrameLayout chatLayer = new FrameLayout(this);
        scroller = new ScrollView(this); scroller.setFillViewport(true); scroller.setClipChildren(false);
        LinearLayout content = new LinearLayout(this); content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(dp(9), dp(12), dp(9), dp(12)); content.setClipChildren(false); content.setClipToPadding(false);
        messages = new LinearLayout(this); messages.setOrientation(LinearLayout.VERTICAL);
        liveBox = new LinearLayout(this); liveBox.setOrientation(LinearLayout.VERTICAL);
        messages.setClipChildren(false); liveBox.setClipChildren(false); // soft bubble shadows spill past their rows
        content.addView(messages, match(-2)); content.addView(liveBox, match(-2));
        scroller.addView(content);
        chatLayer.addView(scroller, new FrameLayout.LayoutParams(-1, -1));
        chatLayer.addView(new ChatChrome.EdgeFade(this, true), new FrameLayout.LayoutParams(-1, dp(20), Gravity.TOP));
        chatLayer.addView(new ChatChrome.EdgeFade(this, false), new FrameLayout.LayoutParams(-1, dp(20), Gravity.BOTTOM));
        jumpButton = Ui.text(this, "↓ " + ChatChrome.jumpLabel(0), 12, Ui.TEXT, false);
        jumpButton.setGravity(Gravity.CENTER); jumpButton.setPadding(dp(12), 0, dp(12), 0); jumpButton.setMinHeight(dp(34));
        jumpButton.setBackground(Ui.round(this, Ui.tier(3), 99)); Ui.elevate(jumpButton, 2);
        jumpButton.setContentDescription("Jump to latest");
        jumpButton.setVisibility(View.GONE);
        jumpButton.setOnClickListener(v -> { scrollBottom(true); showJump(false); });
        FrameLayout.LayoutParams jp = new FrameLayout.LayoutParams(-2, dp(34), Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL); jp.setMargins(0, 0, 0, dp(10));
        chatLayer.addView(jumpButton, jp);
        scroller.setOnTouchListener((v, e) -> { int a = e.getActionMasked(); dragging = a == MotionEvent.ACTION_DOWN || a == MotionEvent.ACTION_MOVE; return false; });
        scroller.setOnScrollChangeListener((v, x, y, ox, oy) -> {
            showJump(!isNearBottom());
            View child = scroller.getChildAt(0);
            topLine.setAlpha(ChatChrome.edgeAlpha(y, dp(24)));
            if (child != null && composerLine != null) composerLine.setAlpha(ChatChrome.edgeAlpha(child.getBottom() - (scroller.getHeight() + y), dp(24)));
        });
        column.addView(chatLayer, new LinearLayout.LayoutParams(-1, 0, 1));

        replyBar = new LinearLayout(this); replyBar.setGravity(Gravity.CENTER_VERTICAL);
        replyBar.setPadding(dp(12), dp(6), dp(6), dp(6)); replyBar.setBackgroundColor(Ui.BAR);
        View line = new View(this); line.setBackgroundColor(Ui.BLUE); replyBar.addView(line, new LinearLayout.LayoutParams(dp(3), dp(36)));
        replyText = Ui.body(this, "", Ui.SUB); replyText.setPadding(dp(10), 0, dp(6), 0); replyText.setMaxLines(2);
        replyBar.addView(replyText, new LinearLayout.LayoutParams(0, -2, 1));
        TextView close = closeGlyph("Cancel reply"); close.setOnClickListener(v -> clearReply()); replyBar.addView(close, new LinearLayout.LayoutParams(dp(44), dp(44)));
        replyBar.setVisibility(View.GONE); column.addView(replyBar, match(-2));

        commandPanel = new LinearLayout(this); commandPanel.setOrientation(LinearLayout.VERTICAL); commandPanel.setBackgroundColor(Ui.BAR);
        commandPanel.setVisibility(View.GONE); column.addView(commandPanel, match(-2));

        attachBar = new LinearLayout(this); attachBar.setOrientation(LinearLayout.VERTICAL);
        attachBar.setPadding(dp(12), dp(6), dp(6), dp(6)); attachBar.setBackgroundColor(Ui.BAR);
        LinearLayout attachHead = new LinearLayout(this); attachHead.setGravity(Gravity.CENTER_VERTICAL);
        attachLabel = Ui.body(this, "", Ui.TEXT); attachLabel.setMaxLines(1); attachLabel.setEllipsize(TextUtils.TruncateAt.END);
        attachHead.addView(attachLabel, new LinearLayout.LayoutParams(0, -2, 1));
        TextView dropAttach = closeGlyph("Clear attachments"); dropAttach.setOnClickListener(v -> clearAttachments());
        attachHead.addView(dropAttach, new LinearLayout.LayoutParams(dp(44), dp(40)));
        attachBar.addView(attachHead, match(-2));
        HorizontalScrollView thumbScroll = new HorizontalScrollView(this); thumbScroll.setHorizontalScrollBarEnabled(false);
        attachThumbs = new LinearLayout(this); thumbScroll.addView(attachThumbs);
        attachBar.addView(thumbScroll, match(-2));
        attachBar.setVisibility(View.GONE); column.addView(attachBar, match(-2));

        column.addView(buildAgentStrip(), match(-2));
        column.addView(buildComposer(), match(-2));
        Nav.tabBar(this, column, Layout.Tab.ASSISTANT);
        Ui.keepAboveKeyboard(root, column);
        setContentView(root);
        if (getIntent().getBooleanExtra(MainActivity.EXTRA_SWITCHING, false)) { getIntent().removeExtra(MainActivity.EXTRA_SWITCHING); // profile switch: fade the chat in from the overlay
            SwitchOverlay.Overlay o = SwitchOverlay.still(this, store.profileName(), store.profileId());
            o.postDelayed(o::dismiss, 100);
        }
    }

    private TextView closeGlyph(String description) { TextView t = Ui.text(this, "×", 28, Ui.SUB, false); t.setGravity(Gravity.CENTER); t.setContentDescription(description); return t; }

    private View buildHeader() {
        LinearLayout bar = new LinearLayout(this); bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setPadding(dp(2), 0, dp(2), 0); bar.setBackgroundColor((Ui.BAR & 0xffffff) | 0xe6000000);
        if (focus() || voiceLayout()) return buildFocusHeader(bar);
        if (Ui.layout.drawer()) {
            ImageButton menu = Ui.icon(this, R.drawable.ic_menu, "Conversations"); menu.setOnClickListener(v -> showSessionsDrawer());
            bar.addView(menu, new LinearLayout.LayoutParams(dp(52), dp(56)));
        } else if (Ui.layout == Layout.DASHBOARD) {
            ImageButton back = Ui.icon(this, R.drawable.ic_back, "Back to dashboard"); back.setOnClickListener(v -> onBackPressed());
            bar.addView(back, new LinearLayout.LayoutParams(dp(52), dp(56)));
        } else bar.setPadding(dp(14), 0, dp(2), 0);
        // The agent's mark switches harness (Hermes, Claude Code, Codex); the subtitle names it, so no extra pill crowds the title.
        View mark = ChatViews.mark(this, 32, false, Backends.type(api)); mark.setContentDescription("Harness: " + store.serverType() + ". Tap to switch between Hermes, Claude Code and Codex"); mark.setOnClickListener(v -> Harness.picker(this));
        mark.setBackground(Ui.ripple(this, Color.TRANSPARENT, 16));
        bar.addView(mark, new LinearLayout.LayoutParams(dp(32), dp(32)));
        LinearLayout names = new LinearLayout(this); names.setOrientation(LinearLayout.VERTICAL); names.setPadding(dp(12), 0, 0, 0); names.setGravity(Gravity.CENTER_VERTICAL);
        titleView = Ui.title(this, title, Ui.TEXT); titleView.setSingleLine(true); titleView.setEllipsize(TextUtils.TruncateAt.END);
        subView = Ui.text(this, "", 12, Ui.SUB, false); subView.setSingleLine(true); subView.setEllipsize(TextUtils.TruncateAt.END);
        showModel();
        if (features().models) subView.setOnClickListener(v -> showModelPicker());
        names.addView(titleView); names.addView(subView);
        names.setOnClickListener(v -> showSessionInfo());
        bar.addView(names, new LinearLayout.LayoutParams(0, -2, 1));
        ImageButton newChat = Ui.icon(this, R.drawable.ic_new, "New conversation");
        newChat.setOnClickListener(v -> newChatFromHeader());
        bar.addView(newChat, new LinearLayout.LayoutParams(dp(48), dp(56)));
        ImageButton more = Ui.icon(this, R.drawable.ic_more, "More"); more.setOnClickListener(v -> showMore());
        bar.addView(more, new LinearLayout.LayoutParams(dp(48), dp(56)));
        return bar;
    }

    /** "Hermes ▾" / "Codex ▾" / "Claude Code ▾" in the header: one tap to switch harness (see Harness). */
    private View harnessChip() {
        TextView chip = Ui.text(this, agentName() + " \u25be", 12, Ui.SUB, false);
        chip.setGravity(Gravity.CENTER_VERTICAL); chip.setSingleLine(true);
        chip.setPadding(dp(10), 0, dp(10), 0);
        chip.setBackground(Ui.ripple(this, Color.TRANSPARENT, 8));
        chip.setContentDescription("Harness: " + store.serverType() + ". Tap to switch between Hermes, Claude Code and Codex");
        chip.setOnClickListener(v -> Harness.picker(this));
        return chip;
    }

    private boolean focus() { return Ui.layout == Layout.FOCUS; }
    /** Voice layout: a big hold-to-talk mic; the text box only appears when you ask for the keyboard. */
    private boolean voiceLayout() { return Ui.layout == Layout.VOICE; }

    /** Focus: one thin title line (tap for info), the command palette and the ⋮ menu. */
    private View buildFocusHeader(LinearLayout bar) {
        bar.setPadding(dp(16), 0, dp(2), 0);
        titleView = Ui.title(this, title, Ui.TEXT); titleView.setSingleLine(true); titleView.setEllipsize(TextUtils.TruncateAt.END);
        titleView.setGravity(Gravity.CENTER_VERTICAL); titleView.setContentDescription(null);
        titleView.setOnClickListener(v -> showSessionInfo());
        bar.addView(titleView, new LinearLayout.LayoutParams(0, dp(48), 1));
        bar.addView(harnessChip(), new LinearLayout.LayoutParams(-2, dp(32)));
        subView = Ui.num(this, "", 12, Ui.FAINT); showModel(); // not shown; the palette has Model
        ImageButton palette = Ui.icon(this, R.drawable.ic_search, "Command palette"); palette.setOnClickListener(v -> showPalette());
        bar.addView(palette, new LinearLayout.LayoutParams(dp(48), dp(48)));
        ImageButton more = Ui.icon(this, R.drawable.ic_more, "More"); more.setOnClickListener(v -> showMore());
        bar.addView(more, new LinearLayout.LayoutParams(dp(48), dp(48)));
        return bar;
    }

    /** Reasoning-effort and permission chips above the composer, for any server that lets you set them. */
    private View buildAgentStrip() {
        agentStrip = new LinearLayout(this); agentStrip.setGravity(Gravity.CENTER_VERTICAL | Gravity.START);
        agentStrip.setPadding(dp(8), dp(2), dp(8), 0);
        effortChip = agentChip("Reasoning effort", false); permsChip = agentChip("Agent permissions", true);
        permsChip.setOnClickListener(v -> cycleAgentMode());
        permsChip.setOnLongClickListener(v -> { showAgentSettings(true); return true; });
        permsChip.setContentDescription("Agent mode, tap to cycle, hold for all modes");
        planChip = agentChip("Plan mode", false); fastChip = agentChip("Fast mode", false);
        planChip.setOnClickListener(v -> togglePlan()); fastChip.setOnClickListener(v -> toggleFast());
        agentStrip.addView(effortChip, chipParams()); agentStrip.addView(permsChip, chipParams());
        agentStrip.addView(planChip, chipParams()); agentStrip.addView(fastChip, chipParams());
        agentStrip.setVisibility(View.GONE);
        HorizontalScrollView scroll = new HorizontalScrollView(this); scroll.setHorizontalScrollBarEnabled(false);
        // The backdrop spans the whole row (as the composer below does), not just the chips.
        scroll.setBackgroundColor((Ui.BG & 0xffffff) | 0xe6000000); scroll.setClipToPadding(false);
        scroll.addView(agentStrip, new FrameLayout.LayoutParams(-2, -2, Gravity.START)); return scroll;
    }

    private LinearLayout.LayoutParams chipParams() { LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(-2, dp(30)); p.setMarginEnd(dp(2)); return p; }

    private TextView agentChip(String description, boolean permissions) {
        TextView chip = Ui.text(this, "", 12, Ui.SUB, false);
        chip.setGravity(Gravity.CENTER_VERTICAL | Gravity.START); chip.setSingleLine(true); chip.setMaxWidth(dp(200));
        chip.setEllipsize(android.text.TextUtils.TruncateAt.END); chip.setPadding(dp(8), 0, dp(8), 0); chip.setContentDescription(description + ", tap to change");
        chip.setBackground(Ui.ripple(this, Color.TRANSPARENT, 8)); // quiet text buttons, not outlined pills
        chip.setOnClickListener(v -> showAgentSettings(permissions));
        chip.setVisibility(View.GONE);
        return chip;
    }

    /** Fills the chips from the server's current settings; hides the strip when it has none to offer. */
    private void refreshAgentStrip() {
        if (agentStrip == null || !api.supportsAgentSettings()) return;
        final AgentBackend target = api;
        final String sid = sessionId, profile = apiProfile;
        final int request = ++agentSettingsRequest;
        network.submit(() -> {
            String effort = null, perms = null;
            JSONObject loaded = new JSONObject();
            try {
                JSONObject settings = target.getSessionSettings(sid);
                loaded = settings;
                effort = chipLabel(settings, false); perms = chipLabel(settings, true);
            } catch (Exception ignored) { /* bridge too old or offline: no strip */ }
            final String e = effort, p = perms;
            final JSONObject settings = loaded;
            runOnUiThread(() -> {
                if (!sameAgentSettingsTarget(target, sid, profile) || request != agentSettingsRequest) return;
                agentSettings = settings;
                setChip(effortChip, e == null ? null : "Effort · " + e);
                setChip(permsChip, p == null || !target.supportsCommands() ? p : "Mode · " + p);
                permsChip.setTextColor(modeColor(settings.optString("permission_mode")));
                Boolean plan = AgentSettings.planMode(settings);
                setChip(planChip, plan == null ? null : "Plan" + (plan ? " · On" : " · Off"));
                planChip.setTextColor(Boolean.TRUE.equals(plan) ? Ui.GREEN : Ui.SUB);
                List<AgentSettings.Option> fast = AgentSettings.fastOptions(settings);
                String fastLabel = "⚡ Fast";
                for (AgentSettings.Option o : fast) if (o.selected) fastLabel = "⚡ " + o.label;
                setChip(fastChip, fast.isEmpty() ? null : fastLabel);
                agentStrip.setVisibility(e == null && p == null && plan == null && fast.isEmpty() ? View.GONE : View.VISIBLE);
            });
        });
    }

    private static String chipLabel(JSONObject settings, boolean permissions) {
        for (AgentSettings.Option o : AgentSettings.options(settings, permissions)) if (o.selected) return o.label + (permissions ? "" : " ▾");
        return AgentSettings.options(settings, permissions).isEmpty() ? null : "Not set ▾";
    }

    private static void setChip(TextView chip, String text) {
        if (text == null) { chip.setVisibility(View.GONE); return; }
        chip.setText(text); chip.setVisibility(View.VISIBLE);
    }

    private View buildComposer() {
        LinearLayout outer = new LinearLayout(this); outer.setGravity(Gravity.BOTTOM);
        outer.setPadding(dp(6), dp(6), dp(6), dp(8)); outer.setBackgroundColor((Ui.BG & 0xffffff) | 0xe6000000);
        LinearLayout pill = new LinearLayout(this); pill.setGravity(Gravity.BOTTOM);
        android.graphics.drawable.Drawable idle = Ui.stroke(this, Ui.tier(1), Ui.LINE, 24), lifted = Ui.stroke(this, Ui.tier(2), tint(Ui.BLUE, 0xaa), 24);
        pill.setBackground(idle);
        composerPill = pill;
        ImageButton plus = Ui.icon(this, R.drawable.ic_new, "Attach, skills and quick actions"); plus.setOnClickListener(v -> showPlusSheet());
        pill.addView(plus, new LinearLayout.LayoutParams(dp(48), dp(48)));
        composer = new EditText(this);
        composer.setTextColor(Ui.TEXT); composer.setHintTextColor(Ui.FAINT); composer.setHint("Message " + agentName());
        composer.setTextSize(focus() ? 18 : 16); composer.setBackgroundColor(Color.TRANSPARENT);
        composer.setPadding(dp(2), dp(focus() ? 16 : 12), dp(14), dp(focus() ? 16 : 12)); composer.setMinLines(focus() ? 2 : 1); composer.setMaxLines(focus() ? 10 : 6);
        composer.setInputType(android.text.InputType.TYPE_CLASS_TEXT | android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE | android.text.InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
        composer.setImeOptions(EditorInfo.IME_ACTION_SEND);
        composer.setOnEditorActionListener((v, a, e) -> { if (a == EditorInfo.IME_ACTION_SEND) { sendComposer(); return true; } return false; });
        composer.addTextChangedListener(new TextWatcher() {
            public void beforeTextChanged(CharSequence s, int st, int c, int a) {}
            public void onTextChanged(CharSequence s, int st, int b, int c) {
                updateComposerState(s.toString());
                // Focus: "/" typed into an empty composer opens the palette; the "/" stays for slash commands.
                if (resumed && focus() && c == 1 && st == 0 && s.length() == 1 && s.charAt(0) == '/') showPalette();
            }
            public void afterTextChanged(Editable e) {}
        });
        composer.setOnFocusChangeListener((v, focused) -> { // focus lifts the composer: raised tier, accent hairline, elevation
            if (!focused && voiceLayout() && composer.getText().toString().trim().isEmpty()) setTyping(false);
            pill.setBackground(focused ? lifted : idle);
            if (Ui.motionEnabled()) pill.animate().translationZ(focused ? dp(4) : 0).setDuration(150).setInterpolator(Ui.EASE_OUT).start();
            else pill.setTranslationZ(focused ? dp(4) : 0);
        });
        pill.addView(composer, new LinearLayout.LayoutParams(0, -2, 1));
        accessChip = Ui.icon(this, R.drawable.ic_shield, "Full access is on. Change action mode");
        accessChip.setImageTintList(ColorStateList.valueOf(Ui.AMBER));
        accessChip.setOnClickListener(v -> Sheets.actionMode(this, store, this::updateAccess));
        accessChip.setVisibility(View.GONE);
        pill.addView(accessChip, new LinearLayout.LayoutParams(dp(48), dp(48)));
        contextRing = new ContextRing(this); contextRing.setOnClickListener(v -> showUsage());
        pill.addView(contextRing, new LinearLayout.LayoutParams(dp(48), dp(48)));
        outer.addView(pill, new LinearLayout.LayoutParams(0, -2, 1));
        if (voiceLayout()) {
            voiceHint = Ui.text(this, "Hold the mic and speak", 15, Ui.SUB, false);
            voiceHint.setGravity(Gravity.CENTER);
            outer.addView(voiceHint, new LinearLayout.LayoutParams(0, dp(72), 1));
            voiceKeyboard = Ui.icon(this, R.drawable.ic_edit, "Type a message instead");
            voiceKeyboard.setOnClickListener(v -> setTyping(true));
            outer.addView(voiceKeyboard, new LinearLayout.LayoutParams(dp(48), dp(72)));
            pill.setVisibility(View.GONE);
        }
        if (Ui.motionEnabled()) for (ViewGroup g : new ViewGroup[]{pill, outer}) { // composer grows/shrinks smoothly as lines wrap
            android.animation.LayoutTransition lt = new android.animation.LayoutTransition();
            lt.enableTransitionType(android.animation.LayoutTransition.CHANGING);
            lt.setDuration(160); lt.setInterpolator(android.animation.LayoutTransition.CHANGING, Ui.EASE_OUT);
            g.setLayoutTransition(lt);
        }
        sendMic = Ui.icon(this, R.drawable.ic_mic, "Talk to " + store.serverType());
        sendMic.setTag(R.drawable.ic_mic);
        micLook(Ui.TEXT);
        sendMic.setOnClickListener(v -> {
            Ui.tick(v);
            String text = composer.getText().toString().trim();
            if (busy()) stopRun();
            else if (!text.isEmpty() || hasAttachments()) sendComposer();
            else if (talking) stopTalking();
            else if (voiceLayout() && typingMode && !busy()) { setTyping(false); startVoice(); }
            else startVoice();
        });
        sendMic.setOnTouchListener(this::micTouch);
        sendMic.setOnLongClickListener(v -> { if (busy()) { stopRun(); return true; } return false; });
        int send = voiceLayout() ? 72 : focus() ? 44 : 40;
        if (voiceLayout()) sendMic.setPadding(dp(20), dp(20), dp(20), dp(20)); else sendMic.setPadding(dp(9), dp(9), dp(9), dp(9));
        LinearLayout.LayoutParams sp = new LinearLayout.LayoutParams(dp(send), dp(send));
        if (voiceLayout()) sp.setMargins(dp(6), 0, 0, 0); else sp.setMargins(dp(2), 0, dp(4), dp(4));
        steerSend = Ui.text(this, "Steer", 13, Ui.BLUE, true);
        steerSend.setGravity(Gravity.CENTER);
        steerSend.setBackground(Ui.ripple(this, Ui.SURFACE, 12));
        steerSend.setContentDescription("Send a steer while the run continues");
        steerSend.setVisibility(View.GONE);
        steerSend.setOnClickListener(v -> sendComposer());
        outer.addView(steerSend, new LinearLayout.LayoutParams(dp(52), dp(48)));
        (voiceLayout() ? outer : pill).addView(sendMic, sp); // inside the field, like Claude and ChatGPT
        sweep = new ChatViews.Sweep(this);
        FrameLayout frame = new FrameLayout(this);
        frame.addView(outer, new FrameLayout.LayoutParams(-1, -2));
        composerLine = new View(this); composerLine.setBackgroundColor(Ui.LINE); composerLine.setAlpha(0f);
        frame.addView(composerLine, new FrameLayout.LayoutParams(-1, Math.max(1, dp(1)), Gravity.TOP));
        frame.addView(sweep, new FrameLayout.LayoutParams((int) (getResources().getDisplayMetrics().widthPixels * .4f), dp(2), Gravity.TOP));
        return frame;
    }

    /** Mic / send / stop button: a solid disc with a contrasting glyph (light disc, dark glyph at rest; red while recording). */
    private void micLook(int fill) {
        sendMic.setBackground(Ui.circle(fill));
        sendMic.setImageTintList(ColorStateList.valueOf(fill == Ui.TEXT ? Ui.BG : Color.WHITE));
    }

    /** The shield chip next to the composer: visible only while Full access is on. */
    private void updateAccess() {
        if (accessChip == null) return;
        if ("Hermes".equals(Backends.type(api)) && "Full access".equals(store.actionMode())) Ui.showFade(accessChip); else accessChip.setVisibility(View.GONE);
    }

    private String agentName() { return Brand.name(this, Backends.type(api)); }

    private boolean busy() { return runs.active(apiProfile, sessionId) != null; }

    /** Voice layout: swap between the big-mic bar and the text box. */
    private void setTyping(boolean typing) {
        if (!voiceLayout() || typing == typingMode) return;
        typingMode = typing;
        composerPill.setVisibility(typing ? View.VISIBLE : View.GONE);
        voiceHint.setVisibility(typing ? View.GONE : View.VISIBLE);
        voiceKeyboard.setVisibility(typing ? View.GONE : View.VISIBLE);
        if (typing) {
            composer.requestFocus();
            composer.post(() -> { InputMethodManager imm = (InputMethodManager) getSystemService(INPUT_METHOD_SERVICE); if (imm != null) imm.showSoftInput(composer, 0); });
        } else {
            InputMethodManager imm = (InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
            if (imm != null) imm.hideSoftInputFromWindow(composer.getWindowToken(), 0);
        }
    }

    /** Guards the header's "New conversation" button: createSession() is a bare async call with
     *  no dedupe, so a few fast taps before the first one navigates away each created their own
     *  real, persisted session. */
    private boolean creatingNewChat, conversationError;
    private String recoveredSessionId;
    private TextView steerSend;

    private void newChatFromHeader() {
        if (!ChatLogic.allowNewChat(history.isEmpty(), busy(), conversationError)) return;
        if (creatingNewChat) return;
        creatingNewChat = true;
        network.submit(() -> {
            try { HermesApi.SessionInfo s = api.createSession("New conversation"); runOnUiThread(() -> {
                creatingNewChat = false;
                String draft = conversationError ? composer.getText().toString() : null;
                List<String> images = conversationError ? new ArrayList<>(pendingImages) : new ArrayList<>();
                List<String[]> files = conversationError ? new ArrayList<>(pendingFiles) : new ArrayList<>();
                switchSession(s);
                if (draft != null) {
                    composer.setText(draft); composer.setSelection(composer.length()); store.draft(sessionId, draft);
                    pendingImages.addAll(images); pendingFiles.addAll(files); refreshAttachBar();
                }
            }); }
            catch (Exception e) { runOnUiThread(() -> { creatingNewChat = false; toast(HermesApi.friendly(e)); }); }
        });
    }

    private boolean hasAttachments() { return !pendingImages.isEmpty() || !pendingFiles.isEmpty(); }

    private void updateComposerState(String text) {
        boolean has = !text.trim().isEmpty() || hasAttachments();
        if (busy()) Ui.swapIcon(sendMic,R.drawable.ic_stop,"Stop");
        else if (has) Ui.swapIcon(sendMic,R.drawable.ic_send,"Send");
        else if (talking) Ui.swapIcon(sendMic,R.drawable.ic_stop,"Stop talking");
        else Ui.swapIcon(sendMic,R.drawable.ic_mic,"Talk to " + store.serverType());
        steerSend.setVisibility(busy() && has && features().steer ? View.VISIBLE : View.GONE);
        sweep.setActive(busy());
        composer.setHint(busy() ? (features().steer ? "Steer " + agentName() + " while it works…" : "Wait for the current run…") : "Message " + agentName());
        renderCommandSuggestions(text);
    }

    private void renderCommandSuggestions(String text) {
        commandPanel.removeAllViews();
        String q = text.trim();
        if (!q.startsWith("/") || q.contains(" ") || q.contains("\n")) { commandPanel.setVisibility(View.GONE); return; }
        String needle = q.substring(1).toLowerCase(Locale.ROOT);
        int count = 0;
        for (String[] c : COMMANDS) {
            if (!c[0].startsWith(needle) || !features().allowsCommand(c[0], api.supportsCommands())) continue;
            LinearLayout row = new LinearLayout(this); row.setGravity(Gravity.CENTER_VERTICAL);
            row.setPadding(dp(16), dp(6), dp(12), dp(6)); row.setBackground(Ui.ripple(this, Color.TRANSPARENT, 0));
            TextView cmd = Ui.text(this, "/" + c[0], 14, Ui.BLUE, true); cmd.setTypeface(Typeface.MONOSPACE);
            row.addView(cmd, new LinearLayout.LayoutParams(dp(104), dp(40)));
            row.addView(Ui.text(this, c[1].replace("Hermes", agentName()), 13, Ui.SUB, false), new LinearLayout.LayoutParams(0, -2, 1));
            String name = c[0];
            row.setOnClickListener(v -> {
                boolean args = name.equals("new") || name.equals("steer") || name.equals("fork") || name.equals("rename");
                composer.setText("/" + name + (args ? " " : "")); composer.setSelection(composer.length());
                if (!args) sendComposer();
            });
            commandPanel.addView(row, match(48));
            if (++count >= 6) break;
        }
        // Everything else the server knows (built-ins, skills, plugins), like Hermes Desktop's slash menu.
        java.util.Set<String> local = new java.util.HashSet<>();
        for (String[] c : COMMANDS) if (features().allowsCommand(c[0], api.supportsCommands())) local.add(c[0]);
        for (String[] c : serverCommands) {
            if (count >= 8) break;
            if (!c[0].startsWith(needle) || local.contains(c[0])) continue;
            LinearLayout row = new LinearLayout(this); row.setGravity(Gravity.CENTER_VERTICAL);
            row.setPadding(dp(16), dp(6), dp(12), dp(6)); row.setBackground(Ui.ripple(this, Color.TRANSPARENT, 0));
            TextView cmd = Ui.text(this, "/" + c[0], 14, Ui.BLUE, true); cmd.setTypeface(Typeface.MONOSPACE);
            cmd.setSingleLine(true); cmd.setEllipsize(android.text.TextUtils.TruncateAt.END);
            row.addView(cmd, new LinearLayout.LayoutParams(dp(104), dp(40)));
            TextView desc = Ui.text(this, c[1].replaceAll("\\s*\\(usage:.*$", ""), 13, Ui.SUB, false);
            desc.setMaxLines(2); desc.setEllipsize(android.text.TextUtils.TruncateAt.END);
            row.addView(desc, new LinearLayout.LayoutParams(0, -2, 1));
            String name = c[0]; boolean args = c[1].contains("(usage: /" + c[0] + " <");
            row.setOnClickListener(v -> {
                composer.setText("/" + name + (args ? " " : "")); composer.setSelection(composer.length());
                if (!args) sendComposer();
            });
            commandPanel.addView(row, match(48));
            count++;
        }
        commandPanel.setVisibility(count == 0 ? View.GONE : View.VISIBLE);
    }

    /** The server's slash-command catalog (from the hub's gateway bridge); empty until it loads or without a hub. */
    private static volatile List<String[]> serverCommands = java.util.Collections.emptyList();
    private static volatile long serverCommandsAt;
    private static volatile String serverCommandsFor = "";
    private HubApi hub() { return new HubApi(store.hubUrl(), store.hubKey()); }
    /** Chats on a gateway profile (/p/<name>) live in that profile's history; the hub must resume them there. */
    private String gatewayProfile() {
        SettingsStore.Connection c = store.connection(apiProfile);
        return c == null ? "" : HubApi.gatewayProfile(c.endpoint);
    }
    private void loadServerCommands() {
        String owner = store.serverType() + "|" + store.profileId();  // each harness has its own commands
        if (!owner.equals(serverCommandsFor)) { serverCommands = java.util.Collections.emptyList(); serverCommandsAt = 0; serverCommandsFor = owner; }
        if (!(features().hub || api.supportsCommands()) || System.currentTimeMillis() - serverCommandsAt < 600_000) return;
        serverCommandsAt = System.currentTimeMillis();
        network.submit(() -> {
            try { List<String[]> list = api.supportsCommands() ? api.commandCatalog() : hub().hermesCommands(); if (!list.isEmpty()) serverCommands = list; }
            catch (Exception e) { serverCommandsAt = 0; }
        });
    }

    // ── transcript ──────────────────────────────────────────────────────────────────────────────

    static boolean sameMessages(List<HermesApi.Message> a, List<HermesApi.Message> b) {
        if (a.size() != b.size()) return false;
        for (int i = 0; i < a.size(); i++) {
            HermesApi.Message x = a.get(i), y = b.get(i);
            if (x.id != y.id || !java.util.Objects.equals(x.content, y.content) || !java.util.Objects.equals(x.role, y.role)) return false;
        }
        return true;
    }

    private void loadMessages(boolean showSpinner) {
        final String sid = sessionId;
        if (showSpinner) {
            messages.removeAllViews();
            TextView loading = Ui.caption(this, "Loading messages…", Ui.SUB);
            loading.setGravity(Gravity.CENTER); loading.setPadding(0, dp(28), 0, 0);
            messages.addView(loading, match(70));
        }
        network.submit(() -> {
            try {
                List<HermesApi.Message> loaded = api.messages(sid, PAGE, 0);
                java.util.Map<String, String> reports = loadActionReports(sid);
                runOnUiThread(() -> {
                    if (!sid.equals(sessionId)) return;
                    freshMessages.clear();
                    if (!showSpinner && !history.isEmpty()) {
                        java.util.Set<Long> old = new java.util.HashSet<>();
                        for (HermesApi.Message m : history) old.add(m.id);
                        for (HermesApi.Message m : loaded) if (m.id != 0 && !old.contains(m.id)) freshMessages.add(m.id);
                    }
                    // Resume reloads: skip the full re-render when nothing changed (the common case).
                    if (!showSpinner && !conversationError && sameMessages(history, loaded) && reports.equals(actionReports)) return;
                    conversationError = false;
                    history.clear(); history.addAll(loaded);
                    actionReports = reports;
                    loadedCount = HermesApi.rawRows(loaded); hasMore = loadedCount >= PAGE;
                    renderMessages(true);
                });
            } catch (Exception e) {
                runOnUiThread(() -> {
                    if (!sid.equals(sessionId)) return;
                    messages.removeAllViews();
                    String error = HermesApi.friendly(e);
                    boolean missing = HermesApi.isSessionNotFound(error);
                    conversationError = true;
                    if (missing) {
                        forgetMissingSession(sid);
                        if (!busy()) recoverMissingSession(sid, null);
                    }
                    TextView err = systemBubble(missing ? "This conversation was deleted. Tap to start a new one."
                            : "Couldn't load this conversation. Tap to retry.\n" + error, Ui.RED);
                    err.setOnClickListener(v -> { if (missing) { recoveredSessionId = null; recoverMissingSession(sid, null); } else loadMessages(true); });
                });
            }
        });
    }

    private void loadEarlier(TextView button) {
        final String sid = sessionId;
        button.setText("Loading…");
        network.submit(() -> {
            try {
                List<HermesApi.Message> older = api.messages(sid, PAGE, loadedCount);
                java.util.Map<String, String> reports = loadActionReports(sid);
                runOnUiThread(() -> {
                    if (!sid.equals(sessionId)) return;
                    int raw = HermesApi.rawRows(older); loadedCount += raw; hasMore = raw >= PAGE;
                    history.addAll(0, older);
                    actionReports = reports;
                    int before = messages.getHeight();
                    int y = scroller.getScrollY();
                    renderMessages(false);
                    messages.post(() -> scroller.scrollTo(0, y + messages.getHeight() - before));
                });
            } catch (Exception e) { runOnUiThread(() -> { button.setText("Couldn't load earlier messages. Tap to retry."); }); }
        });
    }

    private boolean unansweredProposal;
    void proposalShownUnanswered() { unansweredProposal = true; }

    private void renderMessages(boolean toBottom) {
        messages.removeAllViews();
        lastTime = null;
        unansweredProposal = false;
        java.util.Map<String, String> reports = actionReports;
        if (history.isEmpty() && runs.run(apiProfile, sessionId) == null) { renderEmptyState(); return; }
        if (hasMore) {
            TextView more = pill("Load earlier messages", Ui.BLUE);
            more.setOnClickListener(v -> loadEarlier(more));
        }
        String lastDay = "", lastRole = "", previousUser = "";
        // Copy / read aloud / share / reply sit under the last answer of each turn only, not every step.
        java.util.Set<HermesApi.Message> turnEnds = java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>());
        HermesApi.Message lastAnswer = null;
        for (HermesApi.Message m : history) {
            if ("user".equals(m.role)) { if (lastAnswer != null) turnEnds.add(lastAnswer); lastAnswer = null; }
            else if ("assistant".equals(m.role) && !m.content.isEmpty()) lastAnswer = m;
        }
        if (lastAnswer != null) turnEnds.add(lastAnswer);
        for (HermesApi.Message m : history) {
            String day = day(m.timestamp);
            if (!day.equals(lastDay)) { datePill(day); lastDay = day; }
            if ("tool".equals(m.role) && m.pairedToolResult) continue;
            if ("tool".equals(m.role)) { addTools(messages, m.toolCalls, true); continue; }
            if ("system".equals(m.role)) { messages.addView(ChatViews.systemNote(this, m.content), match(-2)); lastRole = "system"; continue; }
            if (!m.reasoning.isEmpty() && store.showReasoning() && features().reasoning && !"user".equals(m.role)) messages.addView(reasoningBlock(m.reasoning, false), toolParams());
            // Hermes' own continuation nudges ("[System: The previous response was cut off…]") are stored
            // as user turns; show a quiet note instead of a bubble the user never wrote.
            if ("user".equals(m.role) && m.content.startsWith("[System:")) { systemNote(m.content); continue; }
            if ("user".equals(m.role) && m.content.startsWith(DeviceActionRunner.RESULTS_PREFIX)) {
                messages.addView(resultsCard(m.content), toolParams());
                lastRole = "tool";
                continue;
            }
            if (!m.content.isEmpty() || !m.images.isEmpty()) {
                groupedWithPrevious = m.role.equals(lastRole);
                View bubble = addBubble(m, messages);
                if (bubble != null && freshMessages.contains(m.id)) Ui.riseIn(bubble);
                if (bubble != null) lastRole = m.role;
                String report = "assistant".equals(m.role) ? reports.get(replyKey(sessionId, m.content)) : null;
                if (report != null) messages.addView(actionChip(report), toolParams());
                if (bubble != null && turnEnds.contains(m)) messages.addView(replyActions(m, previousUser.startsWith("Rewrite this dictation")), match(-2));
            } else if (!m.toolCalls.isEmpty()) lastRole = "tool";
            if ("user".equals(m.role)) previousUser = m.content;
            addTools(messages, m.toolCalls, true);
        }
        if (lastTime != null) lastTime.setVisibility(View.VISIBLE);
        if (toBottom) scrollBottom();
        freshMessages.clear();
        store.pendingProposal(sessionId, unansweredProposal);
    }

    private java.util.Map<String, String> loadActionReports(String sid) {
        java.util.Map<String, StringBuilder> reports = new java.util.HashMap<>();
        List<ActivityLog.Entry> entries = ActivityLog.forSession(this, sid, Integer.MAX_VALUE);
        for (int i = entries.size() - 1; i >= 0; i--) {
            ActivityLog.Entry e = entries.get(i);
            if (e.replyKey == null) continue;
            StringBuilder report = reports.computeIfAbsent(e.replyKey, key -> new StringBuilder());
            if (report.length() > 0) report.append('\n');
            report.append(e.ok ? "✓ " : "✕ ").append(e.detail.split("\n", 2)[0]);
        }
        java.util.Map<String, String> result = new java.util.HashMap<>();
        for (java.util.Map.Entry<String, StringBuilder> e : reports.entrySet()) result.put(e.getKey(), e.getValue().toString());
        return result;
    }

    /** A fresh conversation shows what Hermes can do instead of a blank screen. */
    private void renderEmptyState() {
        LinearLayout box = new LinearLayout(this); box.setOrientation(LinearLayout.VERTICAL); box.setGravity(Gravity.CENTER_HORIZONTAL);
        box.setPadding(dp(18), dp(48), dp(18), dp(12));
        View mark = ChatViews.mark(this, 56, false, Backends.type(api));
        box.addView(mark, new LinearLayout.LayoutParams(dp(56), dp(56)));
        TextView h = Ui.title(this, ChatLogic.emptyPrompt(Backends.type(api), !store.hubUrl().isEmpty()), Ui.TEXT); h.setPadding(dp(8), dp(16), dp(8), dp(8)); h.setGravity(Gravity.CENTER);
        box.addView(h);
        for (String[] idea : ChatLogic.suggestions(Backends.type(api), !store.hubUrl().isEmpty())) {
            TextView chip = Ui.body(this, idea[0], Ui.TEXT);
            chip.setGravity(Gravity.CENTER_VERTICAL); chip.setMinHeight(dp(48));
            chip.setPadding(dp(16), dp(8), dp(16), dp(8));
            chip.setBackground(new android.graphics.drawable.RippleDrawable(ColorStateList.valueOf(tint(Ui.BLUE, 0x33)), Ui.card(this, 2), Ui.round(this, Color.WHITE, 14)));
            chip.setOnClickListener(v -> sendText(idea[1], false));
            Ui.press(chip);
            LinearLayout.LayoutParams p = match(-2); p.setMargins(dp(12), dp(8), dp(12), 0);
            box.addView(chip, p);
        }
        messages.addView(box, match(-2));
    }

    private LinearLayout.LayoutParams toolParams() { LinearLayout.LayoutParams p = match(-2); p.setMargins(dp(8), dp(3), dp(8), dp(3)); return p; }

    private MarkdownView.Fetcher fetcher() {
        return url -> url.startsWith("/") ? new HubApi(store.hubUrl(), store.hubKey()).media(url, 25 * 1024 * 1024) : api.fetchBytes(url, 8 * 1024 * 1024);
    }

    private static String replyKey(String session, String reply) {
        return ReplyKeys.key(session, DeviceActions.parse(reply == null ? "" : reply).cleanText);
    }

    /** Set while rendering history: a bubble right after one from the same sender sits tighter. */
    private boolean groupedWithPrevious;

    /** Copy / read aloud / share / retry under Hermes' latest answer, without a long-press. */
    private View replyActions(HermesApi.Message m, boolean dictated) {
        LinearLayout box = new LinearLayout(this); box.setOrientation(LinearLayout.VERTICAL); box.setPadding(dp(26), 0, 0, dp(2));
        LinearLayout row = new LinearLayout(this); row.setGravity(Gravity.CENTER_VERTICAL);
        String clean = DeviceActions.parse(m.content).cleanText;
        row.addView(miniAction(R.drawable.ic_copy, "Copy", () -> copy(clean)));
        row.addView(miniAction(R.drawable.ic_speaker, "Read aloud", () -> speak(Markdown.speakable(m.content), false)));
        row.addView(miniAction(R.drawable.ic_share, "Share", () -> shareText(Markdown.plain(m.content))));
        row.addView(miniAction(R.drawable.ic_reply, "Reply", () -> beginReply(m)));
        box.addView(row);
        if (dictated) {
            LinearLayout more = new LinearLayout(this); more.setPadding(0, dp(6), 0, 0);
            more.addView(replyTextAction("Text it", R.drawable.ic_phone, () -> composeDraft("sms", clean)));
            more.addView(replyTextAction("Email it", R.drawable.ic_compose, () -> composeDraft("email", clean)));
            box.addView(more);
        }
        return box;
    }

    private View replyTextAction(String label, int icon, Runnable action) {
        TextView button = Ui.pillButton(this, label, icon, Ui.RAISED, Ui.LINE, Ui.BLUE);
        button.setOnClickListener(v -> { v.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY); action.run(); });
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(-2, -2); p.setMarginEnd(dp(8));
        button.setLayoutParams(p);
        return button;
    }

    private void composeDraft(String type, String body) {
        try {
            JSONObject args = new JSONObject().put("body", Markdown.plain(body));
            DeviceActionRunner.Outcome outcome = DeviceActionRunner.run(this, new DeviceActions.Action(type, args));
            if (!outcome.ok) toast(outcome.message);
        } catch (Exception e) { toast("No message app available"); }
    }

    private static int tint(int c, int alpha) { return (c & 0xffffff) | (alpha << 24); }

    /** Folded card: rotating chevron + label + count in a pressable header, body shown on tap. */
    private LinearLayout foldCard(String label, String count, int labelColor, View body, boolean open, int fill, int line) {
        LinearLayout card = new LinearLayout(this); card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(12), dp(4), dp(12), dp(4));
        card.setBackground(Ui.press(this, fill, line, 14)); Ui.press(card);
        LinearLayout head = new LinearLayout(this); head.setGravity(Gravity.CENTER_VERTICAL); head.setMinimumHeight(dp(40));
        ImageView chev = new ImageView(this); chev.setImageResource(R.drawable.ic_chevron);
        chev.setImageTintList(ColorStateList.valueOf(labelColor)); chev.setRotation(open ? 0 : -90);
        chev.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        LinearLayout.LayoutParams cp = new LinearLayout.LayoutParams(dp(18), dp(18)); cp.setMarginEnd(dp(8));
        head.addView(chev, cp);
        TextView l = Ui.caption(this, label, labelColor); l.setTypeface(Ui.WORD_SEMI);
        head.addView(l, new LinearLayout.LayoutParams(0, -2, 1));
        if (count != null) head.addView(Ui.caption(this, count, Ui.FAINT));
        card.addView(head, new LinearLayout.LayoutParams(-1, -2));
        LinearLayout.LayoutParams bp = new LinearLayout.LayoutParams(-1, -2); bp.setMargins(0, 0, 0, dp(6));
        body.setVisibility(open ? View.VISIBLE : View.GONE);
        card.addView(body, bp);
        card.setContentDescription(label + (count == null ? "" : ", " + count));
        card.setOnClickListener(v -> {
            boolean now = body.getVisibility() != View.VISIBLE;
            chev.animate().rotation(now ? 0 : -90).setDuration(Ui.motionEnabled() ? 150 : 0).start();
            if (!Ui.motionEnabled()) { body.setVisibility(now ? View.VISIBLE : View.GONE); return; }
            body.measure(View.MeasureSpec.makeMeasureSpec(Math.max(0, card.getWidth() - card.getPaddingLeft() - card.getPaddingRight()), View.MeasureSpec.EXACTLY), View.MeasureSpec.UNSPECIFIED);
            int full = body.getMeasuredHeight(), from = now ? 0 : body.getHeight();
            android.animation.ValueAnimator va = android.animation.ValueAnimator.ofInt(from, now ? full : 0);
            ViewGroup.LayoutParams lp = body.getLayoutParams();
            va.addUpdateListener(a -> { lp.height = (int) a.getAnimatedValue(); body.requestLayout(); });
            va.addListener(new android.animation.AnimatorListenerAdapter() { @Override public void onAnimationEnd(android.animation.Animator a) {
                lp.height = -2; body.setVisibility(now ? View.VISIBLE : View.GONE); body.requestLayout(); } });
            lp.height = from; body.setVisibility(View.VISIBLE);
            va.setDuration(180); va.setInterpolator(new DecelerateInterpolator()); va.start();
        });
        return card;
    }

    /** An automatic phone-results message: a folded card, not a bubble the user typed. */
    private View resultsCard(String content) {
        String body = content.substring(content.indexOf('\n') < 0 ? content.length() : content.indexOf('\n') + 1).trim();
        int sections = body.split("\n## ").length;
        TextView b = Ui.num(this, body.replace("## ", ""), 12, Ui.CODE_TEXT); b.setLineSpacing(0, 1.12f); b.setTextIsSelectable(true);
        b.setPadding(dp(10), dp(8), dp(10), dp(8)); b.setBackground(Ui.round(this, Ui.CODE_BG, 10));
        return foldCard("Phone → " + agentName(), sections + " result" + (sections == 1 ? "" : "s"), Ui.BLUE, b, false, tint(Ui.BLUE, 0x14), tint(Ui.BLUE, 0x44));
    }

    /** "Done on this phone" card listing each device action and whether it worked. */
    private View actionChip(String report) {
        LinearLayout card = new LinearLayout(this); card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(14), dp(10), dp(14), dp(10));
        boolean failed = report.contains("✕");
        int accent = failed ? Ui.RED : Ui.GREEN;
        card.setBackground(Ui.stroke(this, tint(accent, 0x1a), tint(accent, 0x55), 14));
        TextView h = Ui.label(this, failed ? "On this phone · failed" : "On this phone", accent);
        card.addView(h);
        TextView body = Ui.body(this, report, Ui.TEXT); body.setPadding(0, dp(4), 0, 0); body.setLineSpacing(0, 1.2f);
        card.addView(body);
        if (report.contains("in Hermes settings") || report.contains("permission not granted")) {
            TextView fix = Ui.pillButton(this, "Open settings", 0, Ui.RAISED, Ui.LINE, Ui.BLUE);
            fix.setOnClickListener(v -> startActivity(new Intent(this, SettingsActivity.class)));
            LinearLayout.LayoutParams fp = new LinearLayout.LayoutParams(-2, -2); fp.topMargin = dp(8);
            card.addView(fix, fp);
        }
        return card;
    }

    /** Quiet reply action: a 36dp faint glyph inside a 48dp touch target, no stroke. */
    private View miniAction(int icon, String label, Runnable action) {
        ImageButton b = Ui.icon(this, icon, label);
        b.setImageTintList(ColorStateList.valueOf(Ui.FAINT));
        b.setPadding(dp(14), dp(14), dp(14), dp(14));
        b.setBackground(new android.graphics.drawable.InsetDrawable(Ui.ripple(this, Color.TRANSPARENT, 18), dp(6)));
        b.setOnClickListener(v -> { v.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY); action.run(); });
        b.setLayoutParams(new LinearLayout.LayoutParams(dp(48), dp(48)));
        return b;
    }

    /**
     * Message chrome, same in every style: the user's message is a gradient bubble; Hermes' answer sits flat
     * on the background beside a small orbit mark (only at the start of a turn), which turns while it streams.
     */
    private LinearLayout.LayoutParams styleMessage(LinearLayout wrap, LinearLayout bubble, boolean mine, boolean grouped, boolean replying) {
        int top = grouped ? dp(2) : Ui.space(this, 10);
        if (mine) {
            wrap.setGravity(Gravity.END);
            bubble.setPadding(dp(14), dp(10), dp(14), dp(6));
            // Flat neutral bubble (as in Claude and ChatGPT): no accent gradient, no glow under it.
            bubble.setBackground(Ui.round(this, Ui.tier(2), 20));
            Ui.press(bubble);
            LinearLayout.LayoutParams bp = new LinearLayout.LayoutParams(-2, -2); bp.setMargins(dp(48), top, 0, dp(4));
            return bp;
        }
        View mark = grouped ? new View(this) : ChatViews.mark(this, 20, replying, Backends.type(api));
        if (grouped) mark.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        LinearLayout.LayoutParams mp = new LinearLayout.LayoutParams(dp(20), dp(20)); mp.setMargins(dp(4), top + dp(2), 0, 0);
        wrap.addView(mark, mp);
        bubble.setPadding(dp(10), 0, dp(4), 0);
        LinearLayout.LayoutParams bp = new LinearLayout.LayoutParams(0, -2, 1); bp.setMargins(0, top, 0, dp(1));
        return bp;
    }

    private View addBubble(HermesApi.Message m, LinearLayout into) {
        boolean mine = "user".equals(m.role);
        String content = mine ? ChatLogic.stripOrigin(DeviceContext.stripScreen(m.content)) : DeviceActions.parse(m.content).cleanText;
        String steer = mine ? ChatLogic.steerBody(content) : null;
        if (steer != null) content = ChatLogic.steerShown(ChatLogic.stripOrigin(steer));
        String capture = mine ? ChatLogic.captureLabel(content) : null;
        String structured = mine ? null : ChatLogic.captureReply(m.content);
        if (structured != null) content = structured;
        if (capture != null) { String body = ChatLogic.captureBody(content); content = "*" + capture + "*" + (body.isEmpty() ? "" : "\n" + body); }
        List<String> images = m.images;
        if (mine && images.isEmpty() && (ChatLogic.isImageTurn(content) || content.matches("(?s)📷 \\d+ images?(\\n.*)?"))) {
            // The server dropped the photos; ours are on the phone, found by send time.
            images = SentImages.find(this, sessionId, m.timestamp);
            content = ChatLogic.stripImageMarkers(content);
            if (!images.isEmpty()) content = content.replaceFirst("^📷 \\d+ images?\\n?", "");
            else if (content.isEmpty()) content = "📷 Photo";
        }
        if ((mine ? content : DeviceActions.stripForDisplay(m.content)).trim().isEmpty() && images.isEmpty()) return null;
        LinearLayout wrap = new LinearLayout(this);
        LinearLayout bubble = new LinearLayout(this); bubble.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams bp = styleMessage(wrap, bubble, mine, groupedWithPrevious, false);
        View.OnLongClickListener lc = v -> {
            Ui.tick(v);
            showMessageActions(m); return true; };
        for (String img : images) {
            View iv = MarkdownView.render(this, "![image](" + img + ")", Ui.TEXT, 15, lc, fetcher());
            LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(dp(220), -2); p.bottomMargin = dp(4);
            bubble.addView(iv, p);
        }
        if (!content.trim().isEmpty()) {
            View body = mine ? MarkdownView.text(this, content, Ui.TEXT, 15, lc) : MarkdownView.render(this, content, Ui.TEXT, 15, lc, fetcher());
            if (!mine) ChatChrome.rhythm(body);
            bubble.addView(body);
        }
        // Assistant timestamps stay visible after reload; tap any message to toggle its time.
        TextView time = Ui.num(this, time(m.timestamp) + (mine ? "  ✓✓" : ""), 12, Ui.FAINT);
        time.setGravity(mine ? Gravity.END : Gravity.START); time.setPadding(mine ? dp(24) : 0, dp(2), dp(2), 0);
        time.setVisibility(mine ? View.GONE : View.VISIBLE); lastTime = time;
        bubble.addView(time);
        bubble.setOnClickListener(v -> { if (time.getVisibility() == View.VISIBLE) time.setVisibility(View.GONE); else { time.setVisibility(View.VISIBLE); Ui.fadeIn(time); } });
        bubble.setOnLongClickListener(lc);
        wrap.addView(bubble, bp);
        into.addView(wrap, match(-2));
        enableSwipeToReply(wrap, m);
        groupedWithPrevious = false;
        return bubble;
    }

    /** Swipe a message to the right to reply to it, like a messaging app. Taps and long-presses still work. */
    private void enableSwipeToReply(View row, HermesApi.Message m) {
        final float threshold = dp(72), start = dp(18);
        final float[] down = new float[2];
        final boolean[] swiping = {false};
        row.setOnTouchListener((v, e) -> {
            switch (e.getActionMasked()) {
                case android.view.MotionEvent.ACTION_DOWN: down[0] = e.getRawX(); down[1] = e.getRawY(); swiping[0] = false; return false;
                case android.view.MotionEvent.ACTION_MOVE: {
                    float dx = e.getRawX() - down[0], dy = Math.abs(e.getRawY() - down[1]);
                    if (!swiping[0] && dx > start && dx > dy * 2) { swiping[0] = true; v.getParent().requestDisallowInterceptTouchEvent(true); }
                    if (swiping[0]) { v.setTranslationX(Math.min(dx, threshold * 1.4f) * .7f); return true; }
                    return false;
                }
                case android.view.MotionEvent.ACTION_UP: case android.view.MotionEvent.ACTION_CANCEL: {
                    if (!swiping[0]) return false;
                    boolean reply = e.getActionMasked() == android.view.MotionEvent.ACTION_UP && e.getRawX() - down[0] > threshold;
                    v.animate().translationX(0).setDuration(160).start();
                    if (reply) { v.performHapticFeedback(HapticFeedbackConstants.CONFIRM); beginReply(m); }
                    swiping[0] = false;
                    return true;
                }
                default: return false;
            }
        });
    }

    private final java.util.Set<String> runningTools = new java.util.HashSet<>();

    private View toolCard(HermesApi.ToolCall t) {
        LinearLayout card = new LinearLayout(this); card.setGravity(Gravity.CENTER_VERTICAL);
        // One quiet line per step (glyph, tool, command), like Claude's and ChatGPT's tool rows; tap for the full call.
        card.setMinimumHeight(dp(40));
        card.setPadding(dp(36), dp(2), dp(6), dp(2)); card.setBackground(Ui.ripple(this, Color.TRANSPARENT, 10)); Ui.press(card); // glyph lines up with the reply text
        TextView icon = Ui.caption(this, Tools.glyph(t.name), Ui.SUB); icon.setTypeface(Ui.WORD_SEMI); icon.setGravity(Gravity.CENTER);
        icon.setBackground(Ui.round(this, Ui.tier(2), 6));
        icon.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        card.addView(icon, new LinearLayout.LayoutParams(dp(26), dp(26)));
        LinearLayout text = new LinearLayout(this); text.setGravity(Gravity.CENTER_VERTICAL); text.setPadding(dp(10), 0, dp(8), 0);
        TextView name = Ui.caption(this, Tools.label(t.name), Ui.TEXT); name.setTypeface(Ui.WORD_SEMI); text.addView(name);
        String summary = Tools.summary(t);
        if ("{}".equals(summary) && !t.result.isEmpty()) summary = t.result;
        TextView sum = Ui.caption(this, summary, Ui.SUB);
        sum.setTypeface("terminal".equals(t.name) || Ui.concept.mono ? Typeface.MONOSPACE : Typeface.DEFAULT); sum.setSingleLine(true); sum.setEllipsize(TextUtils.TruncateAt.END);
        sum.setPadding(dp(8), 0, 0, 0);
        text.addView(sum, new LinearLayout.LayoutParams(0, -2, 1));
        card.addView(text, new LinearLayout.LayoutParams(0, -2, 1));
        boolean failed = "failed".equals(t.status), running = "running".equals(t.status);
        String key = t.id + t.name; boolean finished = !running && runningTools.remove(key);
        if (running) runningTools.add(key);
        int state = failed ? Ui.RED : running ? Ui.AMBER : Ui.GREEN;
        TextView pill = Ui.caption(this, (finished ? (failed ? "✕ " : "✓ ") : "") + (failed ? "Failed" : running ? "Running" : "Done"), state); pill.setTypeface(Ui.WORD_SEMI);
        pill.setPadding(dp(8), dp(3), dp(8), dp(3)); pill.setBackground(Ui.round(this, tint(state, 0x26), 20));
        pill.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        if (running) {
            // Phase-locked to the clock so the dot keeps its rhythm across the live box's rebuilds.
            View dot = new View(this); dot.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO); dot.setBackground(Ui.circle(state));
            LinearLayout.LayoutParams dp = new LinearLayout.LayoutParams(dp(7), dp(7)); dp.setMarginEnd(dp(6));
            card.addView(dot, dp);
            if (Ui.motionEnabled()) {
                android.animation.ObjectAnimator a = android.animation.ObjectAnimator.ofFloat(dot, View.ALPHA, 1f, .25f);
                a.setDuration(700); a.setRepeatCount(android.animation.ValueAnimator.INFINITE); a.setRepeatMode(android.animation.ValueAnimator.REVERSE);
                a.setCurrentPlayTime(android.os.SystemClock.uptimeMillis() % 1400);
                dot.addOnAttachStateChangeListener(new View.OnAttachStateChangeListener() {
                    public void onViewAttachedToWindow(View x) { if (!a.isStarted()) a.start(); }
                    public void onViewDetachedFromWindow(View x) { a.cancel(); }
                });
            }
        }
        if (finished && Ui.motionEnabled()) {
            pill.setScaleX(.7f); pill.setScaleY(.7f);
            pill.animate().scaleX(1f).scaleY(1f).setDuration(260).setInterpolator(new android.view.animation.OvershootInterpolator(3f)).start();
        }
        if (failed || running || finished) card.addView(pill, new LinearLayout.LayoutParams(-2, -2)); // a settled "Done" on every step is noise
        ImageView arrow = new ImageView(this); arrow.setImageResource(R.drawable.ic_chevron); arrow.setRotation(-90);
        arrow.setImageTintList(ColorStateList.valueOf(Ui.FAINT));
        arrow.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        LinearLayout.LayoutParams ap = new LinearLayout.LayoutParams(dp(16), dp(16)); ap.setMarginStart(dp(4));
        card.addView(arrow, ap);
        card.setOnClickListener(v -> openTool(t));
        card.setContentDescription(Tools.label(t.name) + ", " + (failed ? "failed" : running ? "running" : "done") + ". " + summary);
        if (!running) return card;
        // Running: the card breathes, with a highlight sweeping along its bottom edge.
        FrameLayout box = new FrameLayout(this);
        box.addView(card, new FrameLayout.LayoutParams(-1, -2));
        FrameLayout.LayoutParams sp = new FrameLayout.LayoutParams(-1, dp(2), Gravity.BOTTOM); sp.setMargins(dp(14), 0, dp(14), 0);
        box.addView(new ChatChrome.Shimmer(this, state), sp);
        return box;
    }

    /**
     * Tool cards for one turn. A finished run of two or more collapses into a single "Used N tools" chip
     * that expands in place; anything still running (or {@code collapse} false, the live box) shows each card.
     */
    private void addTools(LinearLayout into, List<HermesApi.ToolCall> tools, boolean collapse) {
        if (tools.isEmpty()) return;
        int failed = 0; boolean running = false;
        for (HermesApi.ToolCall t : tools) { if ("failed".equals(t.status)) failed++; if ("running".equals(t.status)) running = true; }
        if (!collapse || running || tools.size() < 2) { for (HermesApi.ToolCall t : tools) into.addView(toolCard(t), toolParams()); return; }
        LinearLayout group = new LinearLayout(this); group.setOrientation(LinearLayout.VERTICAL);
        LinearLayout chip = new LinearLayout(this); chip.setGravity(Gravity.CENTER_VERTICAL); chip.setMinimumHeight(dp(48));
        chip.setPadding(dp(14), 0, dp(10), 0); chip.setBackground(Ui.card(this, 1)); Ui.press(chip);
        TextView label = Ui.num(this, ChatChrome.toolSummary(tools.size(), failed), 12, failed > 0 ? Ui.RED : Ui.SUB);
        chip.addView(label, new LinearLayout.LayoutParams(0, -2, 1));
        ImageView chev = new ImageView(this); chev.setImageResource(R.drawable.ic_chevron); chev.setRotation(-90);
        chev.setImageTintList(ColorStateList.valueOf(Ui.FAINT)); chev.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        chip.addView(chev, new LinearLayout.LayoutParams(dp(20), dp(20)));
        chip.setContentDescription(label.getText() + ", tap to expand");
        LinearLayout cards = new LinearLayout(this); cards.setOrientation(LinearLayout.VERTICAL); cards.setVisibility(View.GONE);
        boolean[] built = {false};
        chip.setOnClickListener(v -> {
            boolean open = cards.getVisibility() != View.VISIBLE;
            if (open && !built[0]) { for (HermesApi.ToolCall t : tools) cards.addView(toolCard(t), toolParams()); built[0] = true; }
            if (open) Ui.showFade(cards); else cards.setVisibility(View.GONE);
            chev.animate().rotation(open ? 0 : -90).setDuration(Ui.motionEnabled() ? 150 : 0).setInterpolator(Ui.EASE_OUT).start();
        });
        LinearLayout.LayoutParams cp = match(-2); cp.setMargins(dp(8), dp(3), dp(8), dp(3));
        group.addView(chip, cp); group.addView(cards, match(-2));
        into.addView(group, match(-2));
    }

    /** Collapsible reasoning. Live reasoning starts open; stored reasoning starts folded. */
    private View reasoningBlock(String reasoning, boolean live) {
        String r = reasoning.trim();
        TextView body = Ui.body(this, live && r.length() > 700 ? "…" + r.substring(r.length() - 700) : r, Ui.SUB);
        body.setLineSpacing(0, 1.2f); body.setPadding(dp(26), 0, 0, 0);
        int words = r.isEmpty() ? 0 : r.split("\\s+").length;
        return foldCard(live ? "Thinking" : "Reasoning", live ? null : words + " words", Ui.SUB, body, live, tint(Ui.SUB, 0x14), tint(Ui.SUB, 0x33));
    }

    private void openTool(HermesApi.ToolCall t) {
        String result = t.result;
        if (result.length() > 300000) result = result.substring(0, 300000) + "\n\n[truncated in mobile viewer]";
        startActivity(new Intent(this, ToolDetailActivity.class).putExtra(ToolDetailActivity.EXTRA_NAME, t.name)
                .putExtra(ToolDetailActivity.EXTRA_ARGS, t.arguments).putExtra(ToolDetailActivity.EXTRA_RESULT, result)
                .putExtra(ToolDetailActivity.EXTRA_STATUS, t.status));
    }

    // ── live run ────────────────────────────────────────────────────────────────────────────────

    @Override public void onRunUpdate(RunManager.LiveRun run, String kind) {
        if (!apiProfile.equals(run.profileId)) return;
        if (!run.sessionId.equals(sessionId)) { if (RunManager.UPDATE_FINISHED.equals(kind) || RunManager.UPDATE_ERROR.equals(kind)) toast(agentName() + " finished in " + run.sessionTitle); return; }
        switch (kind) {
            case "usage":
                usageEventVersion++;
                if (run.usage != null) applyUsage(UsageState.merge(usageState, run.usage.json));
                return;
            case RunManager.UPDATE_APPROVAL:
                getWindow().getDecorView().performHapticFeedback(HapticFeedbackConstants.LONG_PRESS);
                renderLive(run);
                break;
            case RunManager.UPDATE_FINISHED: case RunManager.UPDATE_ERROR:
                refreshUsage(false); refreshAgentStrip();
                finishRun(run);
                break;
            default:
                scheduleLive(run);
        }
        updateRunStrip(run);
        updateComposerState(composer.getText().toString());
    }

    private void attachLiveRun() {
        RunManager.LiveRun run = runs.run(apiProfile, sessionId);
        if (run == null) { liveBox.removeAllViews(); updateRunStrip(null); return; }
        if (run.usage != null) applyUsage(UsageState.merge(usageState, run.usage.json));
        if (run.active()) { renderLive(run); updateRunStrip(run); }
        else if (run.needsFinish()) finishRun(run);
        updateComposerState(composer.getText().toString());
    }

    /** Coalesce stream deltas: re-render at most every 90 ms. */
    private void scheduleLive(RunManager.LiveRun run) {
        if (liveDirty) return;
        liveDirty = true;
        ui.postDelayed(() -> { liveDirty = false; if (run.active() && run.sessionId.equals(sessionId)) renderLive(run); }, 90);
    }

    /** Rendered Markdown per live text segment ({text, view}), so finished messages are not re-rendered per delta. */
    private final java.util.Map<Object, Object[]> liveViews = new java.util.IdentityHashMap<>();

    private View liveMarkdown(Object key, String text) {
        Object[] c = liveViews.get(key);
        if (c == null || !text.equals(c[0])) { c = new Object[]{text, MarkdownView.render(this, text, Ui.TEXT, 15, null, fetcher())}; liveViews.put(key, c); }
        View v = (View) c[1];
        if (v.getParent() != null) ((ViewGroup) v.getParent()).removeView(v);
        return v;
    }

    /** One assistant message of the live run; the streaming one re-renders only its tail after the last paragraph break. */
    private View liveBubble(Object key, String shown, boolean streaming) {
        LinearLayout wrap = new LinearLayout(this);
        LinearLayout bubble = new LinearLayout(this); bubble.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams bp = styleMessage(wrap, bubble, false, false, streaming);
        if (!streaming) bubble.addView(liveMarkdown(key, shown));
        else {
            int cut = Markdown.stableCut(shown);
            if (cut > 0) bubble.addView(liveMarkdown(key, shown.substring(0, cut)));
            View tail = MarkdownView.render(this, shown.substring(cut) + " ▍", Ui.TEXT, 15, null, fetcher());
            bubble.addView(tail); blinkCursor(tail);
        }
        wrap.addView(bubble, bp);
        return wrap;
    }

    private void renderLive(RunManager.LiveRun run) {
        if (animatedRun != run) { animatedRun = run; animatedUser = false; animatedReply = false; animatedTyping = false; runningTools.clear(); liveKids = 0; liveViews.clear(); }
        boolean newUser = false, newTyping = false;
        boolean atBottom = ChatChrome.followTail(isNearBottom(), dragging);
        liveBox.removeAllViews();
        if (run.userText != null && run.userText.startsWith(DeviceActionRunner.RESULTS_PREFIX)) {
            liveBox.addView(resultsCard(run.userText), toolParams());
        } else if (run.userText != null && !run.userText.isEmpty() && !historyEndsWith(run.userText)) {
            HermesApi.Message u = new HermesApi.Message(0, "user", run.userText, null, run.startedAt / 1000.0);
            View user = addBubble(u, liveBox);
            if (user != null && !animatedUser) { newUser = true; animatedUser = true; }
        }
        String reasoning, text; List<Object> timeline = new ArrayList<>();
        synchronized (run) {
            reasoning = run.reasoning.toString(); text = run.text.toString();
            // Snapshot: text segments are copied, tools keep their identity (their cards read live status).
            for (Object o : run.timeline) timeline.add(o instanceof StringBuilder ? new Object[]{o, o.toString()} : o);
        }
        if (!reasoning.trim().isEmpty() && store.showReasoning() && features().reasoning) liveBox.addView(reasoningBlock(reasoning, text.isEmpty()), toolParams());
        // Chronological: each assistant message is its own bubble, tools and steers sit between them.
        int lastText = -1;
        for (int i = 0; i < timeline.size(); i++)
            if (timeline.get(i) instanceof Object[] && !DeviceActions.stripForDisplay((String) ((Object[]) timeline.get(i))[1]).trim().isEmpty()) lastText = i;
        boolean textLast = lastText == timeline.size() - 1 && lastText >= 0;
        List<HermesApi.ToolCall> toolRun = new ArrayList<>();
        for (int i = 0; i < timeline.size(); i++) {
            Object o = timeline.get(i);
            if (o instanceof HermesApi.ToolCall) { toolRun.add((HermesApi.ToolCall) o); continue; }
            if (!toolRun.isEmpty()) { addTools(liveBox, toolRun, false); toolRun = new ArrayList<>(); }
            if (o instanceof RunManager.LiveRun.Note) {
                RunManager.LiveRun.Note n = (RunManager.LiveRun.Note) o;
                if (n.steer) addBubble(new HermesApi.Message(0, "user", ChatLogic.steerShown(n.text, run.queuedSteers.contains(n.text)), null, System.currentTimeMillis() / 1000.0), liveBox);
                else { TextView t = Ui.body(this, n.text.trim(), Ui.SUB); t.setPadding(dp(14), dp(4), dp(14), dp(4)); liveBox.addView(t, match(-2)); }
                continue;
            }
            Object[] seg = (Object[]) o;
            String segText = DeviceActions.stripForDisplay((String) seg[1]).trim();
            if (segText.isEmpty()) continue;
            liveBox.addView(liveBubble(seg[0], segText, i == lastText && textLast), match(-2));
            if (i != lastText || !textLast) {
                HermesApi.Message reply = new HermesApi.Message(0, "assistant", segText, null, run.startedAt / 1000.0);
                TextView time = Ui.num(this, time(reply.timestamp), 12, Ui.FAINT);
                time.setPadding(dp(26), 0, 0, 0); liveBox.addView(time, match(-2));
                liveBox.addView(replyActions(reply, false), match(-2));
            }
        }
        if (!toolRun.isEmpty()) addTools(liveBox, toolRun, false);
        if (run.approval != null && run.approval.clarify) liveBox.addView(questionCard(run), toolParams());
        else if (features().approvals && run.approval != null) liveBox.addView(approvalCard(run), toolParams());
        String shown = DeviceActions.stripForDisplay(text);
        if (!textLast) {
            // Working between messages (or before the first): a typing row says what Hermes is doing.
            LinearLayout wrap = new LinearLayout(this);
            LinearLayout bubble = new LinearLayout(this); bubble.setOrientation(LinearLayout.VERTICAL);
            LinearLayout.LayoutParams bp = styleMessage(wrap, bubble, false, lastText >= 0, true);
            String status = run.approval != null ? (run.approval.clarify ? "Waiting for your answer…" : "Waiting for your approval…") : !run.lastToolLine.isEmpty() ? "Working · " + run.lastToolLine : run.runId == null ? "Connecting…" : "Thinking…";
            LinearLayout row = new LinearLayout(this); row.setGravity(Gravity.CENTER_VERTICAL);
            TypingDots dots = new TypingDots(this);
            row.addView(dots);
            if (!animatedTyping) { newTyping = true; animatedTyping = true; }
            if (run.approval != null || !run.lastToolLine.isEmpty() || run.runId == null) {
                TextView t = Ui.body(this, status, Ui.SUB); t.setSingleLine(true); t.setEllipsize(TextUtils.TruncateAt.END);
                t.setPadding(dp(6), 0, 0, 0);
                row.addView(t, new LinearLayout.LayoutParams(0, -2, 1));
            }
            bubble.addView(row);
            wrap.addView(bubble, bp);
            liveBox.addView(wrap, match(-2));
        }
        boolean newReply = !shown.trim().isEmpty() && !animatedReply;
        if (newReply) animatedReply = true;
        if (newUser || newReply) Ui.riseIn(liveBox);
        else if (newTyping) Ui.fadeIn(liveBox);
        int kids = liveBox.getChildCount();
        if (atBottom) { scrollBottom(true); unread = 0; } else bumpUnread(kids - liveKids);
        liveKids = kids;
    }

    private TextView cursorView;
    private final Runnable blinkTick = new Runnable() { @Override public void run() {
        if (cursorView == null || !cursorView.isAttachedToWindow()) { cursorView = null; return; }
        cursorView.invalidate(); ui.postDelayed(this, 265);
    } };
    private static TextView lastText(View v) {
        if (v instanceof TextView) return (TextView) v;
        if (!(v instanceof ViewGroup)) return null;
        ViewGroup g = (ViewGroup) v;
        for (int i = g.getChildCount() - 1; i >= 0; i--) { TextView t = lastText(g.getChildAt(i)); if (t != null) return t; }
        return null;
    }
    /** Blink the trailing cursor glyph: one span + one 265ms ticker; it stops itself once the view detaches. */
    private void blinkCursor(View tail) {
        if (!Ui.motionEnabled()) return;
        TextView t = lastText(tail);
        CharSequence c = t == null ? null : t.getText();
        if (c == null || c.length() == 0 || c.charAt(c.length() - 1) != '▍') return;
        android.text.SpannableStringBuilder b = new android.text.SpannableStringBuilder(c);
        b.setSpan(new ChatFeel.Blink(), c.length() - 1, c.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        t.setText(b);
        boolean idle = cursorView == null;
        cursorView = t;
        if (idle) ui.postDelayed(blinkTick, 265);
    }

    private boolean historyEndsWith(String userText) {
        for (int i = history.size() - 1; i >= Math.max(0, history.size() - 3); i--) {
            HermesApi.Message m = history.get(i);
            if ("user".equals(m.role)) return ChatLogic.stripImageMarkers(m.content).endsWith(userText.trim());
        }
        return false;
    }

    private View approvalCard(RunManager.LiveRun run) {
        HermesApi.Approval a = run.approval;
        LinearLayout card = new LinearLayout(this); card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(16), dp(14), dp(16), dp(12)); card.setBackground(Ui.stroke(this, Ui.tier(2), Ui.AMBER, 14)); Ui.elevate(card, 2);
        card.addView(Ui.label(this, "Waiting on you", Ui.AMBER));
        TextView head = Ui.title(this, agentName() + " needs your approval", Ui.TEXT); head.setPadding(0, dp(4), 0, 0);
        card.addView(head);
        if (!a.description.isEmpty()) { TextView d = Ui.body(this, a.description, Ui.SUB); d.setPadding(0, dp(6), 0, 0); card.addView(d); }
        if (!a.command.isEmpty()) {
            TextView cmd = Ui.num(this, a.command, 12, Ui.CODE_TEXT);
            cmd.setPadding(dp(12), dp(10), dp(12), dp(10)); cmd.setBackground(Ui.round(this, Ui.CODE_BG, 10)); cmd.setTextIsSelectable(true);
            LinearLayout.LayoutParams p = match(-2); p.topMargin = dp(10); card.addView(cmd, p);
        }
        // Deny (ghost) on the left, the one primary on the right, anything else secondary between.
        LinearLayout buttons = new LinearLayout(this); buttons.setGravity(Gravity.CENTER_VERTICAL); buttons.setPadding(0, dp(12), 0, 0);
        java.util.List<String> order = new ArrayList<>(a.choices);
        java.util.Collections.sort(order, (x, y) -> rank(x) - rank(y));
        for (String choice : order) {
            String label = HermesApi.Approval.label(choice);
            TextView b = "deny".equals(choice) ? Ui.ghostButton(this, label, Ui.RED) : "once".equals(choice) ? Ui.primaryButton(this, label) : Ui.secondaryButton(this, label);
            b.setOnClickListener(v -> {
                if ("always".equals(choice)) Sheets.confirm(this, "Always allow?", agentName() + " will run commands matching this pattern without asking again.", "Always allow", true, () -> answer(run, choice));
                else answer(run, choice);
            });
            boolean ghost = "deny".equals(choice);
            LinearLayout.LayoutParams bp = new LinearLayout.LayoutParams(ghost ? -2 : 0, dp(48), ghost ? 0 : 1);
            if (!ghost) bp.setMarginStart(dp(8));
            buttons.addView(b, bp);
        }
        card.addView(buttons, match(-2));
        return card;
    }

    /** A clarify question, like Telegram's: one button per choice (checkboxes and Send when several may be picked); typing in the composer answers it too. */
    private View questionCard(RunManager.LiveRun run) {
        HermesApi.Approval q = run.approval;
        LinearLayout card = new LinearLayout(this); card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(16), dp(14), dp(16), dp(12)); card.setBackground(Ui.stroke(this, Ui.tier(2), Ui.BLUE, 14)); Ui.elevate(card, 2);
        card.addView(Ui.label(this, agentName() + " asks", Ui.BLUE));
        TextView head = Ui.title(this, q.description.isEmpty() ? agentName() + " has a question" : q.description, Ui.TEXT); head.setPadding(0, dp(4), 0, 0); head.setTextIsSelectable(true);
        card.addView(head);
        java.util.Set<String> picked = new java.util.LinkedHashSet<>();
        TextView send = Ui.primaryButton(this, "Send");
        for (String choice : q.choices) {
            TextView b = Ui.secondaryButton(this, choice);
            b.setSingleLine(false); b.setGravity(Gravity.CENTER_VERTICAL | Gravity.START); b.setPadding(dp(14), dp(10), dp(14), dp(10)); b.setMinHeight(dp(48));
            b.setOnClickListener(v -> {
                if (!q.multi) { answerQuestion(run, choice); return; }
                boolean on = picked.add(choice); if (!on) picked.remove(choice);
                b.setText((on ? "✓  " : "") + choice); b.setSelected(on);
                send.setEnabled(!picked.isEmpty()); send.setAlpha(picked.isEmpty() ? 0.5f : 1f);
            });
            LinearLayout.LayoutParams p = match(-2); p.topMargin = dp(8); card.addView(b, p);
        }
        if (q.multi) {
            send.setEnabled(false); send.setAlpha(0.5f);
            send.setOnClickListener(v -> answerQuestion(run, new org.json.JSONArray(new ArrayList<>(picked)).toString()));
            LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(-1, dp(48)); p.topMargin = dp(10); card.addView(send, p);
        }
        TextView hint = Ui.body(this, q.choices.isEmpty() ? "Type your answer below." : "Or type a different answer below.", Ui.FAINT);
        hint.setPadding(0, dp(10), 0, 0); card.addView(hint);
        return card;
    }

    private void answerQuestion(RunManager.LiveRun run, String answer) {
        runs.answer(run, answer, r -> { if (!r.ok) toast(r.text); if (run.active()) renderLive(run); });
    }

    private static int rank(String choice) { return "deny".equals(choice) ? 0 : "once".equals(choice) ? 2 : 1; }

    private void answer(RunManager.LiveRun run, String choice) {
        runs.approve(run, choice, r -> { toast(r.ok ? HermesApi.Approval.label(choice) : r.text); if (run.active()) renderLive(run); });
    }

    private void updateRunStrip(RunManager.LiveRun run) {
        showModel();
        // The thread shows progress and approvals; Stop stays beside the composer.
        runStrip.setVisibility(View.GONE);
    }

    private void finishRun(RunManager.LiveRun run) {
        liveBox.removeAllViews();
        if (run.error == null) checkTitle(run.sessionId, 0);
        updateRunStrip(null);
        if (run.error != null && !RunManager.isCancelled(run.error)) {
            run.errorShown();   // shown once: attachLiveRun re-runs finishRun on every resume until this is set
            boolean missing = HermesApi.isSessionNotFound(run.error);
            conversationError = true;
            if (missing) {
                forgetMissingSession(run.sessionId);
                if (!run.sessionId.equals(recoveredSessionId)) { recoverMissingSession(run.sessionId, run); return; }
                loadIntoComposer(ChatLogic.recoveryDraft(run.userText, composer.getText().toString()));
            }
            TextView err = systemBubble(missing ? "This conversation no longer exists on the server. Tap to send in a new one."
                    : ChatLogic.runFailure(agentName(), run.error), Ui.RED);
            final String retry = run.userText;
            err.setOnClickListener(v -> {
                if (missing) { recoveredSessionId = null; recoverMissingSession(run.sessionId, run); return; }
                runs.forget(run.profileId, run.sessionId);
                ViewGroup p = (ViewGroup) err.getParent(); if (p != null) p.removeView(err);
                if (retry != null) sendText(retry, false, false, run.payload, run.systemMessage);
            });
            return;
        }
        HermesApi.RunOutcome o = run.outcome;
        if (o != null && !o.completed()) {
            String why = "cancelled".equals(o.status) ? "Stopped" : "The run ended early" + (o.turnExitReason.isEmpty() ? "" : " (" + o.turnExitReason.replace('_', ' ') + ")");
            systemBubble(why, "cancelled".equals(o.status) ? Ui.SUB : Ui.AMBER);
        }
        if (!run.actionsHandled) {
            if (PhoneAgent.isRunning(run.profileId, run.sessionId)) {
                loadMessages(false);
            } else {
                run.actionsHandled = true;
                if (PhoneAgent.shouldStart(run)) { PhoneAgent.start(this, run); loadMessages(false); }
                else if (!run.pendingActions.isEmpty()) runPendingActions(run, 0, new ArrayList<>());
                else loadMessages(false);
            }
        } else loadMessages(false);
        if (run.voice && !run.spoken && store.speakReplies()) { run.spoken = true; speak(Markdown.speakable(run.finalText()), store.conversationMode()); }
        runs.forget(apiProfile, sessionId);
    }

    // ── sending ─────────────────────────────────────────────────────────────────────────────────

    private void sendComposer() {
        if (creatingNewChat) { toast("Opening a new chat. Your draft is kept."); return; }
        String text = composer.getText().toString().trim();
        if (text.isEmpty() && !hasAttachments()) return;
        clearComposer();
        commandPanel.setVisibility(View.GONE);
        if (editTarget != null && !text.isEmpty()) { HermesApi.Message target = editTarget; clearReply(); restoreCheckpoint(target, text, false); return; }
        CompanionCommand c = CompanionCommand.parse(text);
        if (c != null && !hasAttachments()) { executeCommand(c); return; }
        if (!hasAttachments() && replyTarget == null && runLocally(text)) return;
        sendText(text, false);
    }

    /**
     * Empty the composer and its saved draft. The keyboard can still hold the last word as "composing"
     * text and write it back after setText(""), which left the sent message in the box; dropping the
     * composing span and restarting input makes the IME forget it.
     */
    private void clearComposer() {
        android.view.inputmethod.BaseInputConnection.removeComposingSpans(composer.getText());
        composer.getText().clear();
        android.view.inputmethod.InputMethodManager imm = (android.view.inputmethod.InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
        if (imm != null) imm.restartInput(composer);
        store.draft(sessionId, "");
    }

    /** Plain phone commands ("timer for 5 minutes", "call Mom") run here at once, like Google Assistant. */
    private String pendingLocal; // a fast-path command waiting on the contacts permission
    private boolean runLocally(String text) {
        if (!store.deviceActions() || store.agentPaused() || !store.localCommands() || busy()) return false;
        DeviceActions.Action local = LocalCommands.parse(text);
        if (local == null) return false;
        systemBubble("You: " + text, Ui.SUB);
        java.util.function.Consumer<Boolean> go = approved -> {
            List<DeviceActionRunner.Outcome> r = DeviceActionRunner.runAll(this, java.util.Collections.singletonList(local), sessionId, null, approved);
            DeviceActionRunner.Outcome o = r.get(0);
            String said = LocalCommands.confirmation(local, o);
            systemBubble((o.ok ? "✓ " : "✕ ") + said + (said.contains("hidden screen") ? "" : "  · done on the phone"), o.ok ? Ui.GREEN : Ui.AMBER);
            if (!o.ok && o.message != null && o.message.startsWith("contacts access is off")
                    && checkSelfPermission(Manifest.permission.READ_CONTACTS) != PackageManager.PERMISSION_GRANTED)
            { pendingLocal = text; requestPermissions(new String[]{Manifest.permission.READ_CONTACTS}, CONTACTS_REQ); }
        };
        if (DeviceActions.needsConfirmation(local, store)) Confirm.strong(this, local.describe(), () -> go.accept(true), () -> systemBubble("Cancelled", Ui.SUB));
        else go.accept(false);
        return true;
    }

    /** Automatic follow-ups in the current agent chain; reset whenever the user sends something. */
    private int agentRound;

    private void runPendingActions(RunManager.LiveRun run, int index, List<DeviceActionRunner.Outcome> results) {
        if (index == run.pendingActions.size()) {
            loadMessages(false);
            // A call/text/message needed contacts: ask for the permission right here, like the system assistant does.
            for (DeviceActionRunner.Outcome o : results)
                if (!o.ok && o.message != null && o.message.startsWith("contacts access is off")
                        && checkSelfPermission(Manifest.permission.READ_CONTACTS) != PackageManager.PERMISSION_GRANTED) {
                    requestPermissions(new String[]{Manifest.permission.READ_CONTACTS}, CONTACTS_REQ);
                    break;
                }
            String followUp = DeviceActionRunner.resultsMessage(results);
            if (followUp != null) {
                if (agentRound < store.agentRounds()) {
                    agentRound++;
                    ui.postDelayed(() -> sendText(followUp, run.voice, true), 400);
                } else {
                    systemBubble(agentName() + " wanted more phone data — paused after " + agentRound + " steps. Reply to let it continue.", Ui.AMBER);
                }
            }
            return;
        }
        DeviceActions.Action action = run.pendingActions.get(index);
        String key = replyKey(run.sessionId, run.finalText());
        if (DeviceActions.isScreenStep(action.type) && DeviceActions.needsConfirmation(action, store) && !store.agentPaused()) {
            if (!HermesAccessibilityService.enabled()) {
                results.addAll(DeviceActionRunner.runAll(this, java.util.Collections.singletonList(action), run.sessionId, key));
                runPendingActions(run, index + 1, results);
                return;
            }
            int end = index + 1;
            while (end < run.pendingActions.size() && DeviceActions.isScreenStep(run.pendingActions.get(end).type)) end++;
            final int next = end;
            List<DeviceActions.Action> steps = run.pendingActions.subList(index, end);
            String app = DeviceActionRunner.screenStepApp(results);
            if ("Unknown app".equals(app)) {
                results.addAll(DeviceActionRunner.runAll(this, steps, run.sessionId, key));
                runPendingActions(run, next, results);
                return;
            }
            StringBuilder detail = new StringBuilder("App: ").append(app);
            for (DeviceActions.Action step : steps) detail.append("\n").append(step.describe());
            Runnable refusedSteps = () -> {
                results.addAll(DeviceActionRunner.runAll(this, steps, run.sessionId, key));
                runPendingActions(run, next, results);
            };
            Confirm.strong(this, "Screen steps in " + app, detail.toString(), () -> {
                results.addAll(DeviceActionRunner.runAll(this, steps, run.sessionId, key, true, app));
                runPendingActions(run, next, results);
            }, refusedSteps);
            return;
        }
        if (DeviceActions.isQuery(action.type)) {
            network.submit(() -> {
                List<DeviceActionRunner.Outcome> done = DeviceActionRunner.runAll(this,
                        java.util.Collections.singletonList(action), run.sessionId, key);
                runOnUiThread(() -> {
                    if (!run.sessionId.equals(sessionId) || isFinishing() || isDestroyed()) return;
                    results.addAll(done);
                    runPendingActions(run, index + 1, results);
                });
            });
            return;
        }
        Runnable refused = () -> {
            results.addAll(DeviceActionRunner.runAll(this, java.util.Collections.singletonList(action), run.sessionId, key));
            runPendingActions(run, index + 1, results);
        };
        if (!DeviceActions.needsConfirmation(action, store) || store.agentPaused()) {
            refused.run();
        } else {
            Confirm.strong(this, action.describe(), () -> {
                results.addAll(DeviceActionRunner.runAll(this, java.util.Collections.singletonList(action), run.sessionId, key, true));
                runPendingActions(run, index + 1, results);
            }, refused);
        }
    }

    private void sendText(String text, boolean voice) { sendText(text, voice, false); }

    private void sendText(String text, boolean voice, boolean automatic) {
        sendText(text, voice, automatic, null, null);
    }

    private void sendText(String text, boolean voice, boolean automatic, Object retryPayload, String retrySystemMessage) {
        // First message in a placeholder-titled chat names it; only when the whole history is loaded.
        boolean first = !hasMore && AutoTitle.isPlaceholder(title);
        if (first) for (HermesApi.Message m : history) if ("user".equals(m.role)) { first = false; break; }
        // Hermes titles the chat itself (instant title, then an AI one); renaming here would mark it as a
        // user-chosen title and switch that off. Just pick the new title up once the reply lands.
        if (first) { final String sid = sessionId; ui.postDelayed(() -> { if (sid.equals(sessionId)) refreshSessionMeta(); }, 3000); ui.postDelayed(() -> { if (sid.equals(sessionId)) refreshSessionMeta(); }, 15000); }
        sendText(text, voice, automatic, null, retryPayload, retrySystemMessage);
    }

    private void sendText(String text, boolean voice, boolean automatic, String newTitle, Object retryPayload, String retrySystemMessage) {
        if (creatingNewChat) { restoreUnsent(text, voice, automatic, retryPayload); return; }
        if (!features().images && !pendingImages.isEmpty()) { toast("This server does not support images; remove the image before sending"); restoreUnsent(text, voice, automatic, retryPayload); return; }
        if (!automatic) agentRound = 0;
        RunManager.LiveRun live = runs.active(apiProfile, sessionId);
        if (live != null) {
            if (!features().steer && (live.approval == null || !live.approval.clarify)) { toast("Wait for the current run to finish"); restoreUnsent(text, voice, automatic, retryPayload); return; }
            // The steer rides into the live run, so show it there as its own bubble; the reloaded
            // transcript carries the same row once Hermes delivers it.
            runs.steer(live, text, r -> {
                if (!r.ok) { toast(r.text); restoreUnsent(text, voice, automatic, retryPayload); return; }
                if (live.sessionId.equals(sessionId) && live.active()) { renderLive(live); scrollBottom(); }
            });
            return;
        }
        lastInputVoice = voice;
        String payloadText = text;
        if (replyTarget != null) {
            String q = replyTarget.content.replace("\n", "\n> ");
            if (q.length() > 600) q = q.substring(0, 600) + "…";
            payloadText = "[Replying to " + ("user".equals(replyTarget.role) ? "my earlier message" : "your earlier message") + "]\n> " + q + "\n\n" + text;
            clearReply();
        }
        for (String[] f : pendingFiles) payloadText += "\n\n[Attached file: " + f[0] + "]\n```" + f[2] + "\n" + f[1] + "\n```";
        if (payloadText.trim().isEmpty() && !pendingImages.isEmpty()) payloadText = "What's in this image?";
        Object payload = retryPayload == null ? payload(payloadText, pendingImages) : retryPayload;
        String shown = text.isEmpty() ? (pendingImages.isEmpty() ? "" : "📷 " + pendingImages.size() + " image" + (pendingImages.size() == 1 ? "" : "s")) : text;
        if (!pendingFiles.isEmpty()) shown += (shown.isEmpty() ? "" : "\n") + "📎 " + pendingFiles.size() + " file" + (pendingFiles.size() == 1 ? "" : "s");
        lastDraft = text;
        if (retryPayload == null && !pendingImages.isEmpty()) SentImages.save(this, sessionId, pendingImages);
        if (retryPayload == null) clearAttachments();
        RunManager.LiveRun run = runs.start(api, apiProfile, sessionId, title, payload, retryPayload == null ? DeviceContext.systemMessage(this, store, voice) : retrySystemMessage, shown, false);
        if (run == null) { toast(agentName() + " is already working here"); restoreUnsent(text, voice, automatic, retryPayload); return; }
        if (newTitle != null) {
            final String sid = sessionId;
            network.submit(() -> {
                HermesApi.Result result = api.renameSession(sid, newTitle);
                if (result.ok) runOnUiThread(() -> {
                    if (sid.equals(sessionId)) { title = newTitle; titleView.setText(newTitle); }
                });
            });
        }
        run.voice = voice;
        if (history.isEmpty()) messages.removeAllViews();
        renderLive(run);
        updateRunStrip(run);
        updateComposerState("");
        scrollBottom();
    }

    /** A typed message that was not sent goes back into the (cleared) composer; voice, automatic and retry sends were never typed. */
    static boolean shouldRestoreUnsent(String text, boolean voice, boolean automatic, Object retryPayload, CharSequence composerNow) {
        return text != null && !text.isEmpty() && !voice && !automatic && retryPayload == null && (composerNow == null || composerNow.toString().trim().isEmpty());
    }

    private void restoreUnsent(String text, boolean voice, boolean automatic, Object retryPayload) {
        if (shouldRestoreUnsent(text, voice, automatic, retryPayload, composer.getText())) loadIntoComposer(text);
    }

    static Object payload(String text, List<String> images) {
        if (images == null || images.isEmpty()) return text;
        try {
            JSONArray parts = new JSONArray();
            parts.put(new JSONObject().put("type", "text").put("text", text));
            for (String img : images) parts.put(new JSONObject().put("type", "image_url").put("image_url", new JSONObject().put("url", img)));
            return parts;
        } catch (Exception e) { return text; }
    }

    private void confirmStop() { stopRun(); }

    private void stopRun() {
        RunManager.LiveRun run = runs.active(apiProfile, sessionId);
        if (run == null) { toast("Nothing is running"); return; }
        runStrip.setText("Stopping…");
        runs.stop(run);
    }

    private void executeCommand(CompanionCommand c) {
        if (ChatLogic.serverStatus(Backends.type(api), features().hub, c.name)) {
            runServerCommand("/status" + (c.args.isEmpty() ? "" : " " + c.args)); return;
        }
        if ("help".equals(c.name) && api.supportsCommands()) { showCommandOutput("/help", commandHelp()); return; }
        if (!features().allowsCommand(c.name, api.supportsCommands())) {
            // Not an app command here: the harness may still run it itself (Codex /undo, Claude Code /compact …).
            boolean theirs = false;
            for (String[] s : serverCommands) if (s[0].equals(c.name)) theirs = true;
            if (theirs) runServerCommand("/" + c.name + (c.args.isEmpty() ? "" : " " + c.args));
            else toast("/" + c.name + " isn't available on " + store.serverType());
            return;
        }
        switch (c.name) {
            case "new": case "clear": createSession(c.args.isEmpty() ? "New conversation" : c.args); break;
            case "sessions": showSessionsDrawer(); break;
            case "stop": stopRun(); break;
            case "steer": if (c.args.isEmpty()) toast("Use /steer message"); else sendText(c.args, false); break;
            case "model": showModelPicker(); break;
            case "fork": forkSession(c.args); break;
            case "rename": if (c.args.isEmpty()) prompt("Rename conversation", title, this::rename); else rename(c.args); break;
            case "pin": togglePin(); break;
            case "archive": archiveCurrent(); break;
            case "delete": deleteSession(); break;
            case "skills": startActivity(new Intent(this, CatalogActivity.class).putExtra(CatalogActivity.EXTRA_SKILLS, true)); break;
            case "tools": startActivity(new Intent(this, CatalogActivity.class).putExtra(CatalogActivity.EXTRA_SKILLS, false)); break;
            case "jobs": startActivity(new Intent(this, JobsActivity.class)); break;
            case "status": startActivity(new Intent(this, StatusActivity.class)); break;
            case "media": startActivity(new Intent(this, MediaActivity.class)); break;
            case "export": shareTranscript(); break;
            case "server": sendText("Audit the homelab critical services now. Failures first, then warnings and direct actions.", false); break;
            case "home": sendText("Check Home Assistant now. Show important state and anything requiring attention.", false); break;
            case "next": sendText("Give me the three highest-value next actions from our current context and offer to execute the first.", false); break;
            case "health": network.submit(() -> { HermesApi.Result r = api.health(); runOnUiThread(() -> systemBubble(r.text, r.ok ? Ui.GREEN : Ui.RED)); }); break;
            case "voice": startVoice(); break;
            case "settings": startActivity(new Intent(this, SettingsActivity.class)); break;
            case "profile": Profiles.show(this); break;
            case "assistant": openAssistantSettings(); break;
            case "undo": {
                if (api.supportsCommands()) { runBridgeCommand("/undo" + (c.args.isEmpty() ? "" : " " + c.args)); break; }
                int n = 1; try { if (!c.args.isEmpty()) n = Math.max(1, Integer.parseInt(c.args.trim())); } catch (NumberFormatException ignored) {}
                undoTurns(n); break;
            }
            case "retry": regenerateLast(); break;
            case "rollback": if (c.args.isEmpty()) showCheckpoints(); else runServerCommand("/rollback " + c.args); break;
            default:
                // Not a companion command: run it on the server like Hermes Desktop does (skills, plugins, built-ins).
                runServerCommand("/" + c.name + (c.args.isEmpty() ? "" : " " + c.args));
        }
    }

    // ── server slash commands, undo and checkpoints (hub ↔ hermes serve, as in Hermes Desktop) ──

    /** Every command this chat accepts: the app's own, then the harness's. */
    private String commandHelp() {
        StringBuilder b = new StringBuilder();
        java.util.Set<String> seen = new java.util.HashSet<>();
        for (String[] c : COMMANDS) if (features().allowsCommand(c[0], api.supportsCommands()) && seen.add(c[0])) b.append('/').append(c[0]).append("  ").append(c[1].replace("Hermes", agentName())).append('\n');
        for (String[] c : serverCommands) if (seen.add(c[0])) b.append('/').append(c[0]).append("  ").append(c[1].replace("Hermes", agentName())).append('\n');
        return b.toString().trim();
    }

    /** Claude Code and Codex run their own slash commands on the bridge (Claude Code takes them as a prompt, Codex through the daemon). */
    private void runBridgeCommand(String command) {
        if (busy()) { toast("Wait for the current run to finish"); return; }
        final String sid = sessionId;
        systemBubble("Running " + command.split("\\s", 2)[0] + "…", Ui.SUB);
        network.submit(() -> {
            try {
                org.json.JSONObject r = api.runCommand(sid, command);
                runOnUiThread(() -> {
                    if (!sid.equals(sessionId)) return;
                    String type = r.optString("type"), message = r.optString("message"), output = r.optString("output");
                    if ("send".equals(type) && !message.isEmpty()) { sendText(message, false); return; }
                    if ("undo".equals(type)) { loadMessages(false); if (!r.optString("text").isEmpty()) loadIntoComposer(r.optString("text")); toast(r.optString("notice", "Undone")); return; }
                    if (!output.isEmpty()) showCommandOutput(command, output); else systemBubble(r.optString("notice", "Done"), Ui.SUB);
                    refreshUsage(false);
                    refreshSessionMeta(); // a /review or /compact turn the daemon started: follow it live
                });
            } catch (Exception e) {
                runOnUiThread(() -> systemBubble(command.split("\\s", 2)[0] + " failed: " + HermesApi.friendly(e), Ui.RED));
            }
        });
    }

    private void runServerCommand(String command) {
        if (api.supportsCommands()) { runBridgeCommand(command); return; }
        if (!features().hub) { sendText(command, false); return; } // no hub: Hermes reads it as text, as before
        if (busy()) { toast("Wait for the current run to finish"); return; }
        final String sid = sessionId;
        systemBubble("Running " + command.split("\\s", 2)[0] + "…", Ui.SUB);
        network.submit(() -> {
            try {
                org.json.JSONObject r = hub().hermesSlash(gatewayProfile(), sid, command);
                HermesApi.forgetRoutes();
                runOnUiThread(() -> {
                    if (!sid.equals(sessionId)) return;
                    String type = r.optString("type"), message = r.optString("message"), output = r.optString("output");
                    if (("send".equals(type) || "skill".equals(type)) && !message.isEmpty()) { sendText(message, false); return; }
                    String text = output.isEmpty() ? r.optString("notice", "Done") : output;
                    if (!r.optString("warning").isEmpty()) text += "\n\n" + r.optString("warning");
                    showCommandOutput(command, text);
                    refreshUsage(false);
                    loadMessages(false); // /title, /compress and friends change the chat
                    refreshSessionMeta();
                });
            } catch (Exception e) {
                runOnUiThread(() -> systemBubble(command.split("\\s", 2)[0] + " failed: " + HermesApi.friendly(e), Ui.RED));
            }
        });
    }

    /** Short results inline; long ones (/help, /usage, tables) in a scrollable monospace sheet. */
    private void showCommandOutput(String command, String text) {
        if (isFinishing() || isDestroyed()) return;   // the slow command answered after Back: Dialog.show() would throw
        String t = text.replaceAll("\\u001B\\[[;\\d]*m", "").trim();
        if (t.length() < 280 && t.split("\n").length <= 4) { systemBubble(t, Ui.SUB); return; }
        Dialog d = new Dialog(this); d.requestWindowFeature(Window.FEATURE_NO_TITLE); d.setCanceledOnTouchOutside(true);
        ScrollView sv = new ScrollView(this);
        HorizontalScrollView hs = new HorizontalScrollView(this);
        TextView v = Ui.text(this, t, 12, Ui.TEXT, false);
        v.setTypeface(Typeface.MONOSPACE); v.setTextIsSelectable(true); v.setPadding(dp(16), dp(16), dp(16), dp(16));
        hs.addView(v); sv.addView(hs); sv.setBackground(Ui.round(this, Ui.BAR, 18));
        d.setContentView(sv);
        if (d.getWindow() != null) { d.getWindow().setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT)); d.getWindow().setLayout((int) (getResources().getDisplayMetrics().widthPixels * .94f), (int) (getResources().getDisplayMetrics().heightPixels * .7f)); }
        d.show();
    }

    /**
     * Rewind like Hermes Desktop: drop user turn {@code target} (and everything after it) on the server,
     * trim the transcript here, then run {@code then} (resend, edited text, or nothing for /undo).
     */
    private void rewindTo(HermesApi.Message target, int turns, Runnable then) {
        if (!features().hub) { toast("Undo and checkpoints need the hub (Settings › Hub URL)"); return; }
        if (busy()) { toast("Stop the current run first"); return; }
        if (target != null && target.id <= 0) { toast("That message isn't saved yet; try again in a moment"); return; }
        final String sid = sessionId;
        network.submit(() -> {
            try {
                hub().hermesRewind(gatewayProfile(), sid, target == null ? 0 : target.id, turns);
                runOnUiThread(() -> {
                    if (!sid.equals(sessionId)) return;
                    int cut = -1;
                    if (target != null) { for (int i = 0; i < history.size(); i++) if (history.get(i).id == target.id) { cut = i; break; } }
                    else { int seen = 0; for (int i = history.size() - 1; i >= 0; i--) if ("user".equals(history.get(i).role) && ++seen == turns) { cut = i; break; } }
                    if (cut >= 0) { history.subList(cut, history.size()).clear(); renderMessages(true); }
                    if (then != null) then.run(); else loadMessages(false);
                });
            } catch (Exception e) { runOnUiThread(() -> toast(HermesApi.friendly(e))); }
        });
    }

    /** /undo N: back up N user turns and put the earliest one back in the composer to re-prompt. */
    private void undoTurns(int n) {
        String text = null; int seen = 0;
        for (int i = history.size() - 1; i >= 0; i--) if ("user".equals(history.get(i).role) && ++seen == n) { text = history.get(i).content; break; }
        final String restore = text;
        rewindTo(null, n, () -> { loadMessages(false); if (restore != null) loadIntoComposer(restore); toast(n == 1 ? "Undid the last turn" : "Undid " + n + " turns"); });
    }

    /** /retry and Regenerate: rerun the last prompt from its checkpoint. */
    private void regenerateLast() {
        for (int i = history.size() - 1; i >= 0; i--) if ("user".equals(history.get(i).role)) { restoreCheckpoint(history.get(i), null, false); return; }
        toast("Nothing to retry yet");
    }

    /** "Restore checkpoint — rerun from this prompt" (or send an edited version of it). */
    private void restoreCheckpoint(HermesApi.Message m, String edited, boolean confirm) {
        String text = edited != null ? edited : m.content;
        Runnable go = () -> rewindTo(m, 0, () -> sendText(text, false));
        if (!confirm) { go.run(); return; }
        Sheets.confirm(this, "Restore to this checkpoint?",
                "Everything after this prompt is removed from the conversation, and the prompt runs again from here.",
                "Restore & rerun", true, go);
    }

    /** Filesystem checkpoints (/rollback): pick one to restore files and rewind the chat to it. */
    private void showCheckpoints() {
        if (!features().hub) { runServerCommand("/rollback"); return; }
        final String sid = sessionId;
        network.submit(() -> {
            try {
                org.json.JSONObject r = hub().hermesCheckpoints(gatewayProfile(), sid);
                org.json.JSONArray rows = r.optJSONArray("checkpoints");
                runOnUiThread(() -> {
                    if (!r.optBoolean("enabled", false)) { systemBubble("Checkpoints are off for this profile (checkpoints.enabled in config.yaml).", Ui.SUB); return; }
                    if (rows == null || rows.length() == 0) { systemBubble("No filesystem checkpoints yet. The agent takes one before it edits files.", Ui.SUB); return; }
                    List<Sheets.Choice> choices = new ArrayList<>();
                    for (int i = 0; i < rows.length(); i++) {
                        org.json.JSONObject c = rows.optJSONObject(i); if (c == null) continue;
                        choices.add(new Sheets.Choice(c.optString("message", c.optString("hash")), c.optString("hash").substring(0, Math.min(8, c.optString("hash").length())) + " · " + c.optString("timestamp"), false));
                    }
                    Sheets.picker(this, "Restore checkpoint", "Files go back to this point and the chat rewinds to it", choices, choice -> {
                        String hash = choice.subtitle.split(" ", 2)[0];
                        Sheets.confirm(this, "Restore files?", "Files changed since this checkpoint are restored (your own edits are kept) and the chat rewinds to it.", "Restore", true, () ->
                                network.submit(() -> {
                                    try {
                                        org.json.JSONObject res = hub().hermesRestoreCheckpoint(gatewayProfile(), sid, hash);
                                        runOnUiThread(() -> { systemBubble(res.optBoolean("success", res.optBoolean("ok")) ? "Checkpoint restored" : "Restore failed: " + res.optString("error"), res.optBoolean("ok") ? Ui.GREEN : Ui.RED); loadMessages(false); });
                                    } catch (Exception e) { runOnUiThread(() -> toast(HermesApi.friendly(e))); }
                                }));
                    });
                });
            } catch (Exception e) { runOnUiThread(() -> toast(HermesApi.friendly(e))); }
        });
    }

    // ── sessions ────────────────────────────────────────────────────────────────────────────────

    private Dialog drawerDialog; private ConversationList drawerConversations;
    private View drawerLogo;
    private float logoDownX; private boolean logoSwiping;

    /** Tap/swipe on the drawer logo: step to the next (delta 1) or previous (delta -1) saved profile. */
    private void switchProfileRelative(int delta) {
        if (busy()) { toast("Stop the active run before switching profile"); return; }
        List<SettingsStore.Profile> profiles = store.profilesForHarness();
        if (profiles.size() < 2) { toast("Hermes".equals(store.serverType()) ? "Add another profile in Settings › Profile" : "Profiles are Hermes-only: tap the harness chip to switch to Hermes"); return; }
        int current = 0;
        for (int i = 0; i < profiles.size(); i++) if (profiles.get(i).id.equals(store.profileId())) current = i;
        int next = Profiles.nextIndex(current, delta, profiles.size());
        Profiles.switchTo(this, profiles.get(next).id, drawerLogo, delta);
        if (drawerDialog != null) drawerDialog.dismiss(); // the overlay lives in the activity window, under the drawer dialog
    }

    private void showSessionsDrawer() {
        drawerDialog = new Dialog(this); drawerDialog.requestWindowFeature(Window.FEATURE_NO_TITLE); drawerDialog.setCanceledOnTouchOutside(true);
        LinearLayout root = new LinearLayout(this); root.setOrientation(LinearLayout.VERTICAL); root.setBackgroundColor(Ui.BAR);
        LinearLayout hero = new LinearLayout(this); hero.setOrientation(LinearLayout.VERTICAL);
        hero.setPadding(dp(18), dp(22), dp(8), dp(14)); hero.setBackground(Ui.gradient(this, Ui.tier(2), Ui.tier(1), 0));
        // Profile switcher: avatar + name + server host, health chip, and a chevron that opens the profile sheet.
        LinearLayout identityRow = new LinearLayout(this); identityRow.setGravity(Gravity.CENTER_VERTICAL);
        String profile = "Hermes".equals(Backends.type(api)) ? store.profileName() : agentName();
        // The Hermes mark in the profile's accent ring: swipe it sideways (it follows the finger) or tap to step
        // through profiles, long-press for the profile sheet.
        drawerLogo = ChatViews.mark(this, 44, false, Backends.type(api));
        drawerLogo.setBackground(SwitchOverlay.ring(SwitchOverlay.accent(store.profileId()), dp(2)));
        drawerLogo.setContentDescription("Switch profile, current " + profile + ". Swipe or tap for the next profile, long-press for all");
        drawerLogo.setOnClickListener(v -> switchProfileRelative(1));
        drawerLogo.setOnLongClickListener(v -> { v.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS); if (drawerDialog != null) drawerDialog.dismiss(); Profiles.show(this); return true; });
        drawerLogo.setOnTouchListener((v, e) -> {
            switch (e.getActionMasked()) {
                case MotionEvent.ACTION_DOWN: logoDownX = e.getRawX(); logoSwiping = false; return false;
                case MotionEvent.ACTION_MOVE: {
                    float dx = e.getRawX() - logoDownX;
                    if (Math.abs(dx) > dp(12)) { logoSwiping = true; v.getParent().requestDisallowInterceptTouchEvent(true); }
                    if (logoSwiping) { v.setTranslationX(dx * .5f); v.setRotation(dx / dp(8)); return true; }
                    return false;
                }
                case MotionEvent.ACTION_UP: case MotionEvent.ACTION_CANCEL: {
                    float dx = e.getRawX() - logoDownX;
                    v.animate().translationX(0).rotation(0).setDuration(Ui.motionEnabled() ? 220 : 0).setInterpolator(Ui.EASE_OUT).start();
                    if (logoSwiping) { if (Math.abs(dx) > dp(42)) { Ui.tick(v); switchProfileRelative(dx < 0 ? 1 : -1); } return true; }
                    return false;
                }
            }
            return false;
        });
        identityRow.addView(drawerLogo, new LinearLayout.LayoutParams(dp(44), dp(44)));
        LinearLayout identity = new LinearLayout(this); identity.setOrientation(LinearLayout.VERTICAL); identity.setPadding(dp(14), 0, dp(8), 0);
        TextView name = Ui.title(this, profile, Ui.TEXT); name.setSingleLine(true); name.setEllipsize(TextUtils.TruncateAt.END);
        identity.addView(name);
        TextView where = Ui.num(this, Profiles.where(store.endpoint()), 12, Ui.SUB); where.setSingleLine(true); where.setEllipsize(TextUtils.TruncateAt.END);
        identity.addView(where);
        TextView health = Ui.caption(this, "", Ui.SUB); health.setTypeface(Ui.WORD_SEMI); health.setPadding(dp(8), dp(2), dp(8), dp(2)); health.setVisibility(View.GONE);
        LinearLayout.LayoutParams hp = new LinearLayout.LayoutParams(-2, -2); hp.topMargin = dp(6);
        identity.addView(health, hp);
        FleetHealth.watch(this, store.profileId(), st -> { health.setVisibility(View.VISIBLE); FleetHealth.paint(health, null, "", st); });
        identityRow.addView(identity, new LinearLayout.LayoutParams(0, -2, 1));
        ImageButton chevron = Ui.icon(this, R.drawable.ic_chevron, "Switch profile, current " + profile);
        chevron.setBackground(Ui.ripple(this, Color.TRANSPARENT, 24));
        identityRow.addView(chevron, new LinearLayout.LayoutParams(dp(48), dp(48)));
        View.OnClickListener openProfiles = v -> { if (drawerDialog != null) drawerDialog.dismiss(); Profiles.show(this); };
        chevron.setOnClickListener(openProfiles); identityRow.setOnClickListener(openProfiles);
        identityRow.setMinimumHeight(dp(48));
        hero.addView(identityRow, match(-2));
        root.addView(hero, match(-2));
        drawerConversations = new ConversationList(this, api, apiProfile, network, conversationHost());
        drawerConversations.addTo(root);
        root.addView(drawerTools(), match(-2));
        drawerDialog.setContentView(root); drawerDialog.show();
        ConversationList shown = drawerConversations;
        drawerDialog.setOnDismissListener(d -> shown.close());
        Window w = drawerDialog.getWindow();
        if (w != null) {
            w.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT)); w.setGravity(Gravity.START);
            WindowManager.LayoutParams a = w.getAttributes(); a.width = (int) (getResources().getDisplayMetrics().widthPixels * .9f); a.height = -1; a.dimAmount = .6f;
            w.addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND); w.setAttributes(a);
        }
        drawerConversations.load();
    }

    /** "Tools": one compact scrolling row of monochrome icon buttons under the list (New first, Settings always there). */
    private View drawerTools() {
        LinearLayout box = new LinearLayout(this); box.setOrientation(LinearLayout.VERTICAL);
        box.setBackgroundColor(Ui.tier(1)); box.setPadding(0, dp(8), 0, dp(6));
        TextView head = Ui.label(this, "Tools", Ui.FAINT); head.setPadding(dp(18), 0, 0, dp(2));
        box.addView(head);
        LinearLayout row = new LinearLayout(this); row.setPadding(dp(8), 0, dp(8), 0);
        View add = drawerTile(R.drawable.ic_new, "New", this::newChatFromHeader);
        ((ImageView) ((LinearLayout) add).getChildAt(0)).setImageTintList(ColorStateList.valueOf(Ui.BLUE));
        List<View> tiles = new ArrayList<>();
        tiles.add(add);
        if (features().jobs) tiles.add(drawerTile(R.drawable.ic_clock, "Agents", () -> startActivity(new Intent(this, JobsActivity.class))));
        if (features().skills) tiles.add(drawerTile(R.drawable.ic_skills, "Skills", () -> startActivity(new Intent(this, CatalogActivity.class).putExtra(CatalogActivity.EXTRA_SKILLS, true))));
        if (!store.hubUrl().isEmpty() && MediaModules.any(this)) tiles.add(drawerTile(R.drawable.ic_movie, "Media", () -> startActivity(new Intent(this, MediaActivity.class))));
        tiles.add(drawerTile(R.drawable.ic_pulse, "System", () -> startActivity(new Intent(this, StatusActivity.class))));
        if (!store.hubUrl().isEmpty()) tiles.add(drawerTile(R.drawable.ic_terminal, "Terminal", () -> startActivity(new Intent(this, TerminalActivity.class))));
        tiles.add(drawerTile(R.drawable.ic_home, "Home", () -> startActivity(new Intent(this, HomeActivity.class))));
        if (store.essentialsReady()) tiles.add(drawerTile(R.drawable.ic_compose, "Capture", this::openCaptureLane));
        tiles.add(drawerTile(R.drawable.ic_settings, "Settings", () -> startActivity(new Intent(this, SettingsActivity.class))));
        for (View t : tiles) row.addView(t, new LinearLayout.LayoutParams(dp(64), dp(64)));
        HorizontalScrollView hs = new HorizontalScrollView(this); hs.setHorizontalScrollBarEnabled(false); hs.addView(row);
        box.addView(hs, match(-2));
        return box;
    }

    private View drawerTile(int icon, String label, Runnable action) {
        LinearLayout t = new LinearLayout(this); t.setOrientation(LinearLayout.VERTICAL); t.setGravity(Gravity.CENTER);
        t.setBackground(Ui.ripple(this, Color.TRANSPARENT, 12));
        t.addView(Ui.iconTile(this, icon, 0), new LinearLayout.LayoutParams(dp(36), dp(36)));
        TextView l = Ui.caption(this, label, Ui.SUB); l.setPadding(0, dp(2), 0, 0);
        l.setGravity(Gravity.CENTER); l.setSingleLine(true); l.setEllipsize(TextUtils.TruncateAt.END);
        t.addView(l, new LinearLayout.LayoutParams(-1, -2));
        t.setOnClickListener(v -> { if (drawerDialog != null) drawerDialog.dismiss(); action.run(); });
        Ui.press(t);
        t.setContentDescription(label);
        return t;
    }

    private ConversationList.Host conversationHost() {
        return new ConversationList.Host() {
            public String currentSessionId() { return sessionId; }
            public void open(HermesApi.SessionInfo si) { if (drawerDialog != null) drawerDialog.dismiss(); switchSession(si); }
            public void openMessage(String id, String t) { if (drawerDialog != null) drawerDialog.dismiss(); saveDraft(); openSession(id, t, ""); }
            public void renamed(String id, String t) { store.sessionTitle(id, t); if (id.equals(sessionId)) { title = t; titleView.setText(t); } }
            public void removed(String id) { if (id.equals(sessionId)) goHome(); }
        };
    }

    static String scheduledPreview(String preview) {
        String cleaned = preview.trim();
        if (cleaned.startsWith("[IMPORTANT:")) {
            int end = cleaned.indexOf(']');
            cleaned = end >= 0 ? cleaned.substring(end + 1).trim() : ""; // previews are truncated, often mid-preamble
        }
        return cleaned.isEmpty() ? "Scheduled run" : cleaned;
    }

    private void switchSession(HermesApi.SessionInfo s) { saveDraft(); pinned = s.pinned; openSession(s.id, s.displayTitle(), s.model); }

    private void openSession(String id, String newTitle, String newModel) {
        runs.setVisibleSession(apiProfile, id);
        sessionId = id; title = newTitle; model = n(newModel, "");
        conversationError = false;
        usageRequest++; usageEventVersion++; agentSettingsRequest++; modeSaving = false; modeConfirming = false; usageResetting = false;
        agentSettings = new JSONObject();
        if (usageSheet != null) usageSheet.dismiss();
        applyUsage(UsageState.parse(null));
        store.sessionId(id);
        store.sessionTitle(id, title);
        titleView.setText(title); showModel();
        history.clear(); liveBox.removeAllViews(); clearReply(); clearAttachments();
        composer.setText(store.draft(id)); composer.setSelection(composer.length());
        api = client();
        loadMessages(true);
        attachLiveRun();
        refreshSessionMeta();
        refreshAgentStrip();
        refreshUsage(false);
    }

    /** Codex sessions are driven from the terminal too: notice a turn started there while this chat is open. */
    private final Runnable codexWatch = new Runnable() { @Override public void run() {
        if (!resumed || !"Codex".equals(store.serverType())) return;
        if (runs.active(apiProfile, sessionId) == null) refreshSessionMeta();
        ui.postDelayed(this, 4000);
    } };

    /** Pull pinned state and unread flag; opening a conversation marks it read. */
    private void refreshSessionMeta() {
        final String sid = sessionId;
        final String titleAtRequest = title;
        network.submit(() -> {
            try {
                HermesApi.SessionInfo s = api.getSession(sid);
                String resolved = "";
                if (s.model.isEmpty() && defaultModel.isEmpty() && features().models) {
                    try { for (HermesApi.ModelOption option : api.modelOptions()) if (option.current) { resolved = option.id; break; } }
                    catch (Exception ignored) {}
                }
                final String actualDefault = resolved;
                if (s.unread) api.markUnread(sid, false);
                runOnUiThread(() -> {
                    if (!sid.equals(sessionId)) return;
                    pinned = s.pinned;
                    if (!actualDefault.isEmpty()) { defaultModel = actualDefault; showModel(); }
                    // A run working on the server that this phone didn't start (a Codex turn from the terminal): follow it live.
                    RunManager.LiveRun attached = s.activeRunId.isEmpty() ? null : runs.attach(api, apiProfile, sid, title, s.activeRunId, s.activeRunInput);
                    if (attached != null) { renderLive(attached); updateRunStrip(attached); updateComposerState(composer.getText().toString()); }
                    if (!s.model.isEmpty() && !s.model.equals(model)) { model = s.model; showModel(); }
                    if (!s.displayTitle().equals(title) && !s.title.isEmpty() && title.equals(titleAtRequest)) {
                        title = s.displayTitle(); titleView.setText(title); store.sessionTitle(sessionId, title);
                    }
                });
            } catch (Exception ignored) {}
        });
    }

    /**
     * After a reply, pick up the title Hermes generates in the background (it lands seconds later, longer
     * after tool-heavy turns); as a last resort name a still-untitled chat from its content. Never touches a title the user set.
     */
    private void checkTitle(final String sid, final int attempt) {
        String first = "", reply = "";
        if (!hasMore) {
            for (HermesApi.Message m : history) { if ("user".equals(m.role) && first.isEmpty()) first = m.content; if ("assistant".equals(m.role) && reply.isEmpty()) reply = m.content; }
        }
        if (first.isEmpty() && !hasMore) first = n(lastDraft, "");
        final String firstUser = first, firstReply = reply;
        if (!sid.equals(sessionId) || !AutoTitle.needsTitle(title, firstUser)) return;
        network.submit(() -> {
            try {
                HermesApi.SessionInfo s = api.getSession(sid);
                String now = s.title;
                if (AutoTitle.needsTitle(now, firstUser)) {
                    if (attempt < 3) { ui.postDelayed(() -> checkTitle(sid, attempt + 1), attempt == 0 ? 3000 : attempt == 1 ? 7000 : 15000); return; }
                    String fb = AutoTitle.fallback(firstUser, firstReply);
                    if (fb.isEmpty() || fb.equals(now) || !api.renameSession(sid, fb).ok) return;
                    now = fb;
                }
                final String t = now;
                runOnUiThread(() -> { if (sid.equals(sessionId) && !t.isEmpty() && !t.equals(title)) { title = t; titleView.setText(t); store.sessionTitle(sid, t); } });
            } catch (Exception ignored) {}
        });
    }

    private void createSession(String name) {
        createSession(name, null);
    }
    private void createSession(String name, String text) {
        network.submit(() -> {
            try { HermesApi.SessionInfo s = api.createSession(name); runOnUiThread(() -> { switchSession(s); if (text != null) sendText(text, false); }); }
            catch (Exception e) { runOnUiThread(() -> toast(HermesApi.friendly(e))); }
        });
    }
    /** Recover once automatically; a broken server must not create sessions in a loop. */
    private void recoverMissingSession(String sid, RunManager.LiveRun failed) {
        if (creatingNewChat || !sid.equals(sessionId) || sid.equals(recoveredSessionId)) return;
        creatingNewChat = true;
        if (failed != null) {
            String kept = ChatLogic.recoveryDraft(failed.userText, composer.getText().toString());
            composer.setText(kept); composer.setSelection(composer.length());
            store.draft(sid, kept);
        }
        final AgentBackend recoveryApi = api;
        network.submit(() -> {
            try {
                HermesApi.SessionInfo fresh = recoveryApi.createSession("New conversation");
                runOnUiThread(() -> {
                    creatingNewChat = false;
                    if (!sid.equals(sessionId) || isFinishing() || isDestroyed()) return;
                    String draft = composer.getText().toString();
                    if (failed != null && failed.payload != null) draft = ChatLogic.draftAfterRecovery(failed.userText, draft);
                    List<String> images = new ArrayList<>(pendingImages);
                    List<String[]> files = new ArrayList<>(pendingFiles);
                    runs.forget(apiProfile, sid);
                    recoveredSessionId = fresh.id;
                    switchSession(fresh);
                    if (failed != null && failed.payload != null) sendText(failed.userText, failed.voice, false, failed.payload, failed.systemMessage);
                    pendingImages.addAll(images); pendingFiles.addAll(files); refreshAttachBar();
                    composer.setText(draft); composer.setSelection(composer.length());
                    store.draft(sessionId, draft);
                });
            } catch (Exception e) {
                runOnUiThread(() -> {
                    creatingNewChat = false;
                    if (!sid.equals(sessionId)) return;
                    if (failed != null) loadIntoComposer(ChatLogic.recoveryDraft(failed.userText, composer.getText().toString()));
                    TextView err = systemBubble("Couldn't open a new chat. Your message is kept. Tap to retry.\n" + HermesApi.friendly(e), Ui.RED);
                    err.setOnClickListener(v -> recoverMissingSession(sid, failed));
                });
            }
        });
    }

    private void forgetMissingSession(String id) {
        if (id.equals(store.sessionId())) store.sessionId("");
        if (id.equals(store.assistantSessionId())) store.assistantSessionId("");
    }
    private void forkSession(String name) {
        network.submit(() -> {
            try { HermesApi.SessionInfo s = api.forkSession(sessionId, name.isEmpty() ? title + " fork" : name); runOnUiThread(() -> { toast("Forked"); switchSession(s); }); }
            catch (Exception e) { runOnUiThread(() -> toast(HermesApi.friendly(e))); }
        });
    }
    private void rename(String name) {
        final String sid = sessionId;
        network.submit(() -> { HermesApi.Result r = api.renameSession(sid, name); runOnUiThread(() -> { if (r.ok) { store.sessionTitle(sid, name); if (sid.equals(sessionId)) { title = name; titleView.setText(name); } } toast(r.text); }); });
    }
    private void togglePin() {
        boolean target = !pinned;
        network.submit(() -> { HermesApi.Result r = api.pinSession(sessionId, target); runOnUiThread(() -> { if (r.ok) pinned = target; toast(r.text); }); });
    }
    private void archiveCurrent() {
        Sheets.confirm(this, "Archive conversation?", "It leaves the list but its transcript stays on the server.", "Archive", false, () -> network.submit(() -> {
            HermesApi.Result r = api.archiveSession(sessionId, true);
            runOnUiThread(() -> { toast(r.text); if (r.ok) goHome(); });
        }));
    }
    private void deleteSession() {
        Sheets.confirm(this, "Delete conversation?", "This permanently removes the server-side session and transcript.", "Delete", true, () -> network.submit(() -> {
            HermesApi.Result r = api.deleteSession(sessionId);
            runOnUiThread(() -> { toast(r.text); if (r.ok) goHome(); });
        }));
    }
    private void goHome() {
        store.sessionId("");
        if (Ui.layout.home != Layout.Home.CHAT) { startActivity(Nav.home(this)); finish(); return; }
        startActivity(new Intent(this, MainActivity.class).putExtra(MainActivity.EXTRA_OPEN_CHAT, true));
        finish();
    }

    private void prompt(String t, String value, java.util.function.Consumer<String> done) {
        if (t.startsWith("Fork")) Sheets.input(this, t, "Makes a copy to take in a new direction; this conversation stays as it is.", value, "Name for the copy", done, "Fork");
        else Sheets.input(this, t, "Changes apply to the server-side conversation.", value, "Enter a title", done);
    }

    // ── message actions ─────────────────────────────────────────────────────────────────────────

    private void showMessageActions(HermesApi.Message m) {
        boolean mine = "user".equals(m.role);
        String plain = mine ? m.content : Markdown.plain(m.content);
        Sheets.actions(this, mine ? "Your message" : agentName(), "Choose an action",
                Sheets.Item.of(R.drawable.ic_reply, Ui.BLUE, "Reply", "Quote this message", () -> beginReply(m)),
                Sheets.Item.of(R.drawable.ic_copy, Ui.BLUE_DARK, "Copy", "Copy the whole message", () -> copy(mine ? m.content : DeviceActions.parse(m.content).cleanText)),
                Sheets.Item.of(R.drawable.ic_aspect, Ui.BLUE_DARK, "Select text", "Pick out part of it", () -> selectText(plain)),
                mine ? Sheets.Item.of(R.drawable.ic_edit, Ui.BLUE_DARK, "Edit message", "Change it and run again from here", () -> beginEdit(m))
                        : Sheets.Item.of(R.drawable.ic_send, Ui.BLUE_DARK, "Use as draft", "Load it into the composer", () -> loadIntoComposer(plain)),
                mine ? Sheets.Item.of(R.drawable.ic_clock, Ui.BLUE_DARK, "Restore checkpoint", "Rerun from this prompt", () -> restoreCheckpoint(m, null, true))
                        : Sheets.Item.of(R.drawable.ic_clock, Ui.BLUE_DARK, "Regenerate", "Run its prompt again", () -> regenerateFrom(m)),
                Sheets.Item.of(R.drawable.ic_speaker, Ui.BLUE_DARK, "Read aloud", "Speak this message", () -> speak(mine ? m.content : Markdown.speakable(m.content), false)),
                Sheets.Item.of(R.drawable.ic_share, Ui.BLUE_DARK, "Share", "Send to another app", () -> shareText(plain)));
    }

    /** A plain selectable copy of the message: reliable selection without fighting long-press handlers. */
    private void selectText(String text) {
        Dialog d = new Dialog(this); d.requestWindowFeature(Window.FEATURE_NO_TITLE); d.setCanceledOnTouchOutside(true);
        ScrollView sv = new ScrollView(this);
        TextView t = Ui.text(this, text, 15, Ui.TEXT, false);
        t.setTextIsSelectable(true); t.setPadding(dp(18), dp(18), dp(18), dp(18)); t.setLineSpacing(0, 1.15f);
        sv.addView(t); sv.setBackground(Ui.round(this, Ui.BAR, 18));
        d.setContentView(sv);
        if (d.getWindow() != null) { d.getWindow().setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT)); d.getWindow().setLayout((int) (getResources().getDisplayMetrics().widthPixels * .94f), -2); }
        d.show();
    }

    private void loadIntoComposer(String text) {
        composer.setText(text); composer.setSelection(composer.getText().length()); composer.requestFocus();
        ((InputMethodManager) getSystemService(INPUT_METHOD_SERVICE)).showSoftInput(composer, InputMethodManager.SHOW_IMPLICIT);
    }
    private void shareText(String text) { try { startActivity(Intent.createChooser(new Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text), "Share")); } catch (Throwable t) { toast("Nothing to share to"); } }
    private void beginReply(HermesApi.Message m) {
        editTarget = null;
        replyTarget = m;
        String value = Markdown.plain(m.content);
        replyText.setText(value.length() > 120 ? value.substring(0, 120) + "…" : value);
        replyBar.setVisibility(View.VISIBLE);
        composer.requestFocus();
        ((InputMethodManager) getSystemService(INPUT_METHOD_SERVICE)).showSoftInput(composer, InputMethodManager.SHOW_IMPLICIT);
    }
    private void clearReply() { replyTarget = null; editTarget = null; if (replyBar != null) replyBar.setVisibility(View.GONE); }

    /** Desktop's "Edit message": the next send replaces this prompt and everything after it. */
    private HermesApi.Message editTarget;
    private void beginEdit(HermesApi.Message m) {
        clearReply();
        editTarget = m;
        replyText.setText("Editing · sending reruns the chat from this message");
        replyBar.setVisibility(View.VISIBLE);
        loadIntoComposer(m.content);
    }

    private void regenerateFrom(HermesApi.Message reply) {
        int at = -1;
        for (int i = 0; i < history.size(); i++) if (history.get(i) == reply || (reply.id != 0 && history.get(i).id == reply.id)) at = i;
        for (int i = at; i >= 0; i--) if ("user".equals(history.get(i).role)) { restoreCheckpoint(history.get(i), null, false); return; }
        toast("Couldn't find the prompt for this reply");
    }

    // ── attachments ─────────────────────────────────────────────────────────────────────────────

    /** The composer's single "+" menu: attach and act in one tap, quick prompts below. */
    private void showPlusSheet() {
        Sheets.Item[] tiles = visibleItems(
                Sheets.Item.of(R.drawable.ic_camera, Ui.BLUE, "Camera", null, this::takePhoto),
                Sheets.Item.of(R.drawable.ic_image, Ui.BLUE_DARK, "Photos", null, this::pickPhoto),
                Sheets.Item.of(R.drawable.ic_attach, Ui.BLUE_DARK, "File", null, this::pickFile),
                Sheets.Item.of(R.drawable.ic_file, Ui.BLUE_DARK, "Document", null, this::pickDocument),
                Sheets.Item.of(R.drawable.ic_search, Ui.BLUE_DARK, "Recall", null, () -> Sheets.input(this, "Recall", "Search memory and past conversations", "", "A person, decision, or topic", q -> sendText("Search your memory and our past conversations (session search) and tell me: " + q + ". Cite which conversation it came from.", false), "Search")),
                Sheets.Item.of(R.drawable.ic_copy, Ui.BLUE_DARK, "Clipboard", null, this::pasteClipboard),
                Sheets.Item.of(R.drawable.ic_mic, Ui.BLUE_DARK, "Dictate", null, () -> startVoice(VoiceSheet.Mode.DICTATE)),
                Sheets.Item.of(R.drawable.ic_receipt, Ui.BLUE_DARK, "Receipt", null, () -> { receiptPhoto = true; takePhoto(); }),
                Sheets.Item.of(R.drawable.ic_cart, Ui.BLUE_DARK, "Shopping", null, () -> Sheets.input(this, "Add to shopping list", "What should Hermes add?", "", "Milk, bread…", items -> sendText("Add to my Home Assistant shopping list: " + items + ". Reply with the updated list.", false), "Add")),
                Sheets.Item.of(R.drawable.ic_skills, Ui.BLUE_DARK, "Skills", null, () -> startActivity(new Intent(this, CatalogActivity.class).putExtra(CatalogActivity.EXTRA_SKILLS, true))),
                Sheets.Item.of(R.drawable.ic_clock, Ui.BLUE_DARK, "Schedule", null, () -> startActivity(new Intent(this, JobsActivity.class).putExtra(JobsActivity.EXTRA_PROMPT, composer.getText().toString()))));
        Sheets.grid(this, "Add to message", tiles, visibleItems(
                Sheets.Item.of(R.drawable.ic_next, Ui.BLUE_DARK, "Next actions", "Prioritise the current context", () -> sendText("Give me the three highest-value next actions from our current context and offer to execute the first.", false)),
                Sheets.Item.of(R.drawable.ic_server, Ui.BLUE_DARK, "Audit server", "Check critical homelab services", () -> sendText("Audit the homelab critical services now. Failures first.", false)),
                Sheets.Item.of(R.drawable.ic_home, Ui.BLUE_DARK, "Home Assistant", "Anything requiring attention", () -> sendText("Check Home Assistant now and show anything requiring attention.", false))));
    }

    private void showAttachSheet() {
        Sheets.actions(this, "Attach", "Photos go to " + agentName() + " as images; text files are sent inline", visibleItems(
                Sheets.Item.of(R.drawable.ic_attach, Ui.BLUE, "Take photo", "Use the camera", this::takePhoto),
                Sheets.Item.of(R.drawable.ic_attach, Ui.BLUE_DARK, "Choose photos", "Up to " + MAX_IMAGES + " from the gallery", this::pickPhoto),
                Sheets.Item.of(R.drawable.ic_share, Ui.BLUE_DARK, "Choose file", "Text, code, logs, CSV, JSON…", this::pickFile),
                Sheets.Item.of(R.drawable.ic_copy, Ui.BLUE_DARK, "Paste clipboard", "Attach what you copied", this::pasteClipboard)));
    }

    private boolean receiptPhoto;
    private void takePhoto() {
        try {
            android.content.ContentValues v = new android.content.ContentValues();
            v.put(android.provider.MediaStore.Images.Media.DISPLAY_NAME, "hermes-" + System.currentTimeMillis() + ".jpg");
            v.put(android.provider.MediaStore.Images.Media.MIME_TYPE, "image/jpeg");
            pendingCameraUri = getContentResolver().insert(android.provider.MediaStore.Images.Media.EXTERNAL_CONTENT_URI, v);
            startActivityForResult(new Intent(android.provider.MediaStore.ACTION_IMAGE_CAPTURE).putExtra(android.provider.MediaStore.EXTRA_OUTPUT, pendingCameraUri), CAMERA_REQ);
        } catch (Throwable t) { receiptPhoto = false; toast("No camera app available"); }
    }
    private void pickPhoto() {
        try { startActivityForResult(new Intent(Intent.ACTION_GET_CONTENT).setType("image/*").putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true), GALLERY_REQ); }
        catch (Throwable t) { toast("No gallery available"); }
    }
    private void pickFile() {
        try { startActivityForResult(new Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("*/*").putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true), FILE_REQ); }
        catch (Throwable t) { toast("No file picker available"); }
    }
    private void pickDocument() {
        try { startActivityForResult(new Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("*/*"), DOCUMENT_REQ); }
        catch (Throwable t) { toast("No file picker available"); }
    }
    private void pasteClipboard() {
        ClipboardManager cm = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
        ClipData clip = cm.getPrimaryClip();
        if (clip == null || clip.getItemCount() == 0) { toast("The clipboard is empty"); return; }
        ClipData.Item item = clip.getItemAt(0);
        if (item.getUri() != null) { stageUri(item.getUri()); return; }
        CharSequence text = item.coerceToText(this);
        if (text == null || text.length() == 0) { toast("The clipboard is empty"); return; }
        pendingFiles.add(new String[]{"clipboard", text.toString(), ""});
        refreshAttachBar();
    }

    private void handleAttachmentResult(int req, int res, Intent data) {
        if (req != CAMERA_REQ && req != GALLERY_REQ && req != FILE_REQ && req != DOCUMENT_REQ) return;
        if (res != RESULT_OK) { if (req == CAMERA_REQ) receiptPhoto = false; return; }
        try {
            if (req == CAMERA_REQ) { if (pendingCameraUri != null) stageImage(pendingCameraUri, receiptPhoto); receiptPhoto = false; return; }
            List<Uri> uris = new ArrayList<>();
            if (data != null && data.getClipData() != null) for (int i = 0; i < data.getClipData().getItemCount(); i++) uris.add(data.getClipData().getItemAt(i).getUri());
            else if (data != null && data.getData() != null) uris.add(data.getData());
            if (uris.isEmpty()) { toast("Nothing selected"); return; }
            for (Uri u : uris) { if (req == DOCUMENT_REQ) uploadDocument(u, queryName(u), getContentResolver().getType(u)); else stageUri(u); }
        } catch (Throwable t) { CrashReporter.show(this, t); }
    }

    private void stageUri(Uri uri) {
        String type = getContentResolver().getType(uri);
        if (type != null && type.startsWith("image/")) stageImage(uri); else stageFile(uri, type);
    }

    /** Downscale first: a 12 MP photo would otherwise be a multi-megabyte request body. */
    private void stageImage(final Uri uri) { stageImage(uri, false); }
    private String sendAfterImages; private int imagesStaging;
    private void imageStaged() {
        if (--imagesStaging > 0 || sendAfterImages == null) return;
        String prompt = sendAfterImages; sendAfterImages = null;
        if (!pendingImages.isEmpty() || !prompt.trim().isEmpty()) sendText(prompt, false);
    }
    private void stageImage(final Uri uri, boolean receipt) {
        if (pendingImages.size() >= MAX_IMAGES) { toast("Up to " + MAX_IMAGES + " images per message"); return; }
        imagesStaging++;
        network.submit(() -> {
            try {
                byte[] raw = readAll(uri, 30 * 1024 * 1024);
                Bitmap bmp = MarkdownView.decode(raw, 1600);
                if (bmp == null) { runOnUiThread(() -> { toast("Could not read that image"); imageStaged(); }); return; }
                ByteArrayOutputStream out = new ByteArrayOutputStream();
                bmp.compress(Bitmap.CompressFormat.JPEG, 82, out);
                final String url = "data:image/jpeg;base64," + android.util.Base64.encodeToString(out.toByteArray(), android.util.Base64.NO_WRAP);
                runOnUiThread(() -> { if (pendingImages.size() < MAX_IMAGES) { pendingImages.add(url); refreshAttachBar();
                    if (receipt) sendText("This is a receipt. Use the receipts skill: extract vendor, date, total, currency, category and append it to my expenses. Reply with one line confirming.", false);
                } bmp.recycle(); imageStaged(); });
            } catch (Throwable t) { runOnUiThread(() -> { toast("Could not read that image"); imageStaged(); }); }
        });
    }

    private void stageFile(final Uri uri, final String mime) {
        network.submit(() -> {
            try {
                String name = queryName(uri);
                byte[] data = readAll(uri, 512 * 1024 + 1);
                if (looksLikeText(name, mime, data)) {
                    String text = new String(data, 0, Math.min(data.length, 512 * 1024), java.nio.charset.StandardCharsets.UTF_8);
                    if (data.length > 512 * 1024) text += "\n…[truncated at 512 KB]";
                    String lang = ext(name);
                    final String body = text;
                    runOnUiThread(() -> { pendingFiles.add(new String[]{name, body, lang}); refreshAttachBar(); });
                } else {
                    runOnUiThread(() -> uploadDocument(uri, name, mime));
                }
            } catch (Throwable t) { runOnUiThread(() -> toast("Could not read that file")); }
        });
    }

    private void uploadDocument(Uri uri, String name, String mime) {
        toast("Uploading " + name + "…");
        network.submit(() -> {
            try {
                byte[] data = readAll(uri, 20 * 1024 * 1024 + 1);
                if (data.length > 20 * 1024 * 1024) throw new IllegalStateException("File is over 20 MB");
                String path = new HubApi(store.hubUrl(), store.hubKey()).uploadFile(name, mime, data);
                if (path.isEmpty()) throw new IllegalStateException("Hub returned no file path");
                runOnUiThread(() -> documentActions(path));
            } catch (Throwable t) { runOnUiThread(() -> toast("Upload failed: " + t.getMessage())); }
        });
    }

    private void documentActions(String path) {
        String prefix = "I uploaded a file for you: " + path + ". ";
        Sheets.actions(this, "Document uploaded", path,
                Sheets.Item.of(R.drawable.ic_file, Ui.BLUE, "Summarise it", "Give me the key points", () -> sendText(prefix + "Summarise it", false)),
                Sheets.Item.of(R.drawable.ic_clock, Ui.BLUE_DARK, "Extract dates and deadlines", "Add them to my calendar with device actions", () -> sendText(prefix + "Extract dates and deadlines (add them to my calendar with device actions)", false)),
                Sheets.Item.of(R.drawable.ic_search, Ui.BLUE_DARK, "Answer questions about it", "Write a question before sending", () -> loadIntoComposer(prefix + "Answer questions about it: ")));
    }

    /** ZIP/OOXML/Office/archive/media extensions are containers, never text — even when the platform
     *  MIME type claims otherwise (an openxmlformats .pptx MIME literally contains "xml", which used to
     *  make this method inline a whole binary deck as UTF-8 gibberish and take the chat renderer down). */
    private static final String BINARY_EXTS = "docx|docm|dotx|dotm|xlsx|xlsm|xlsb|xltx|pptx|pptm|potx|potm|ppsx|ppsm|odt|ods|odp|odg|otf|ott|epub|zip|gz|tgz|bz2|xz|7z|rar|tar|pdf|apk|jar|aar|war|class|so|dll|exe|msi|bin|dat|db|sqlite|ipa|woff|woff2|ttf|ttc|pt|onnx|img|iso|dmg|mp3|mp4|m4a|mov|aac|wav|flac|ogg|opus|webm|avi|mkv|png|jpg|jpeg|gif|webp|bmp|heic|tif|tiff|ico|psd|sketch|fig";

    static boolean looksLikeText(String name, String mime, byte[] data) {
        String e = ext(name);
        if (e.matches(BINARY_EXTS)) return false;
        // PK\x03\x04 / PK\x05\x06 / PK\x07\x08 — every ZIP container (Office, ODF, epub, apk, jar).
        if (data != null && data.length >= 4 && data[0] == 0x50 && data[1] == 0x4b
                && (data[2] == 0x03 || data[2] == 0x05 || data[2] == 0x07)) return false;
        if (mime != null) {
            String m = mime.toLowerCase(Locale.ROOT).trim();
            int semi = m.indexOf(';');
            if (semi >= 0) m = m.substring(0, semi).trim();
            // Exact XML media types only: substring "xml" also matches ...openxmlformats... binaries.
            if (m.equals("application/xml") || m.equals("text/xml") || m.endsWith("+xml")) return true;
            if (m.startsWith("text/") || m.contains("json") || m.contains("yaml") || m.contains("javascript") || m.contains("csv")) return true;
        }
        if (e.matches("txt|md|markdown|csv|tsv|json|jsonl|xml|yaml|yml|toml|ini|cfg|conf|log|py|js|ts|tsx|jsx|java|kt|kts|go|rs|c|h|cpp|hpp|cs|rb|php|sh|bash|zsh|sql|html|css|scss|swift|dart|lua|r|m|tex|srt|vtt|env|gradle|properties|dockerfile")) return true;
        int n = Math.min(data == null ? 0 : data.length, 4096), bad = 0;
        for (int i = 0; i < n; i++) { int b = data[i] & 0xff; if (b == 0) return false; if (b < 9 || (b > 13 && b < 32)) bad++; }
        return n > 0 && bad * 20 < n;
    }
    private static String ext(String name) { int dot = name == null ? -1 : name.lastIndexOf('.'); return dot < 0 ? "" : name.substring(dot + 1).toLowerCase(Locale.ROOT); }

    private byte[] readAll(Uri uri, int max) throws Exception {
        try (InputStream in = getContentResolver().openInputStream(uri)) {
            if (in == null) throw new IllegalStateException("unreadable");
            ByteArrayOutputStream buf = new ByteArrayOutputStream(); byte[] chunk = new byte[65536]; int n;
            while ((n = in.read(chunk)) > 0) { buf.write(chunk, 0, n); if (buf.size() >= max) break; }
            return buf.toByteArray();
        }
    }

    private String queryName(Uri uri) {
        try (android.database.Cursor c = getContentResolver().query(uri, null, null, null, null)) {
            if (c != null && c.moveToFirst()) { int i = c.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME); if (i >= 0 && c.getString(i) != null) return c.getString(i); }
        } catch (Throwable ignored) {}
        String last = uri.getLastPathSegment();
        return last == null ? "file" : last;
    }

    private void refreshAttachBar() {
        attachThumbs.removeAllViews();
        for (int i = 0; i < pendingImages.size(); i++) {
            final int idx = i;
            ImageView iv = new ImageView(this); iv.setScaleType(ImageView.ScaleType.CENTER_CROP);
            iv.setBackground(Ui.round(this, Ui.RAISED, 8)); iv.setClipToOutline(true);
            Bitmap b = MarkdownView.load(pendingImages.get(i), null);
            if (b != null) iv.setImageBitmap(b);
            iv.setOnClickListener(v -> { pendingImages.remove(idx); refreshAttachBar(); });
            iv.setContentDescription("Image " + (i + 1) + ", tap to remove");
            LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(dp(56), dp(56)); p.setMargins(0, dp(2), dp(6), dp(2));
            attachThumbs.addView(iv, p);
        }
        for (int i = 0; i < pendingFiles.size(); i++) {
            final int idx = i;
            TextView chip = Ui.text(this, "📎 " + pendingFiles.get(i)[0] + "  ×", 12, Ui.TEXT, false);
            chip.setPadding(dp(10), dp(8), dp(10), dp(8)); chip.setBackground(Ui.round(this, Ui.RAISED, 99));
            chip.setOnClickListener(v -> { pendingFiles.remove(idx); refreshAttachBar(); });
            chip.setContentDescription("Remove attachment " + pendingFiles.get(i)[0]);
            LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(-2, -2); p.setMargins(0, dp(2), dp(6), dp(2)); p.gravity = Gravity.CENTER_VERTICAL;
            attachThumbs.addView(chip, p);
        }
        int total = pendingImages.size() + pendingFiles.size();
        attachLabel.setText(total == 0 ? "" : total + " attachment" + (total == 1 ? "" : "s") + " · tap one to remove");
        attachBar.setVisibility(total == 0 ? View.GONE : View.VISIBLE);
        updateComposerState(composer.getText().toString());
    }

    private void clearAttachments() { pendingImages.clear(); pendingFiles.clear(); if (attachBar != null) refreshAttachBar(); }

    // ── menus ───────────────────────────────────────────────────────────────────────────────────

    private void openCaptureLane() {
        if (!store.essentialsReady()) { toast("Set the capture URL and token in Settings first"); return; }
        startActivity(new Intent(this, EssentialsActivity.class).putExtra(EssentialsActivity.EXTRA_BASE, store.essentialsBase()).putExtra(EssentialsActivity.EXTRA_TOKEN, store.essentialsToken()));
    }

    private void showMore() {
        Sheets.Item[] all = visibleItems(
                Sheets.Item.of(R.drawable.ic_pulse, Ui.BLUE_DARK, "System", "Server health and services", () -> startActivity(new Intent(this, StatusActivity.class))),
                Sheets.Item.of(R.drawable.ic_edit, Ui.BLUE_DARK, "Rename", null, () -> prompt("Rename conversation", title, this::rename)),
                Sheets.Item.of(R.drawable.ic_pin, Ui.BLUE_DARK, pinned ? "Unpin" : "Pin", null, this::togglePin),
                Sheets.Item.of(R.drawable.ic_assistant, Ui.BLUE_DARK, "Model", "Which model answers in this chat", this::showModelPicker),
                Sheets.Item.of(R.drawable.ic_bolt, Ui.BLUE_DARK, "Reasoning effort", "How hard it thinks, from the next message", () -> showAgentSettings(false)),
                Sheets.Item.of(R.drawable.ic_shield, Ui.BLUE_DARK, "Agent permissions", "Commands and files on the agent host", () -> showAgentSettings(true)),
                Sheets.Item.of(R.drawable.ic_settings, Ui.BLUE_DARK, "Settings", "Companion app settings", () -> startActivity(new Intent(this, SettingsActivity.class))),
                Sheets.Item.of(R.drawable.ic_fork, Ui.BLUE_DARK, "Fork", "Branch a copy to take in a new direction", () -> prompt("Fork conversation", title + " fork", this::forkSession)),
                Sheets.Item.of(R.drawable.ic_share, Ui.BLUE_DARK, "Export", "Share the transcript", this::shareTranscript),
                Sheets.Item.of(R.drawable.ic_archive, Ui.BLUE_DARK, "Archive", "Hide it, keep the transcript", this::archiveCurrent),
                Sheets.Item.danger(R.drawable.ic_delete, "Delete", "Remove the conversation and its transcript", this::deleteSession));
        List<Sheets.Item> tiles = new ArrayList<>(), rows = new ArrayList<>(), danger = new ArrayList<>();
        if (usageState.context != null || usageState.session != null || !usageState.limits.isEmpty() || usageState.resets != null)
            rows.add(Sheets.Item.of(R.drawable.ic_pulse, Ui.BLUE_DARK, "Usage", "Context, tokens and plan limits", this::showUsage));
        for (Sheets.Item item : all) (ChatLogic.overflowGroup(item.title) == 0 ? tiles : ChatLogic.overflowGroup(item.title) == 1 ? rows : danger).add(item);
        rows.addAll(danger); // Delete goes last; Sheets.grid draws a divider above a destructive row. deleteSession still asks first.
        Sheets.grid(this, title, tiles.toArray(new Sheets.Item[0]), rows.toArray(new Sheets.Item[0]));
    }

    /**
     * Focus layout's command palette: live search over conversations, a new conversation (optionally
     * about the typed text), recent conversations and the app's actions, in one sheet.
     */
    private void showPalette() {
        LinearLayout content = new LinearLayout(this); content.setOrientation(LinearLayout.VERTICAL);
        EditText search = new EditText(this); search.setHint("Search conversations and actions");
        search.setTextColor(Ui.TEXT); search.setHintTextColor(Ui.FAINT); search.setTextSize(15); search.setSingleLine(true);
        search.setPadding(dp(14), 0, dp(14), 0); search.setMinHeight(dp(48)); search.setBackground(Ui.stroke(this, Ui.RAISED, Ui.LINE, 12));
        search.setCompoundDrawablesRelativeWithIntrinsicBounds(R.drawable.ic_search, 0, 0, 0); search.setCompoundDrawablePadding(dp(10));
        LinearLayout.LayoutParams sp = match(-2); sp.setMargins(dp(16), 0, dp(16), dp(6)); content.addView(search, sp);
        ScrollView scroll = new ScrollView(this); LinearLayout rows = new LinearLayout(this); rows.setOrientation(LinearLayout.VERTICAL);
        scroll.addView(rows); content.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));
        Dialog[] sheet = new Dialog[1];
        List<HermesApi.SessionInfo> sessions = new ArrayList<>();
        List<Sheets.Item> actions = new ArrayList<>(java.util.Arrays.asList(visibleItems(
                Sheets.Item.of(R.drawable.ic_voice, Ui.BLUE_DARK, "Talk", "Hands-free conversation", this::startVoice),
                Sheets.Item.of(R.drawable.ic_mic, Ui.BLUE_DARK, "Dictate", "Speak a message, get clean text", () -> startVoice(VoiceSheet.Mode.DICTATE)),
                Sheets.Item.of(R.drawable.ic_compose, Ui.BLUE_DARK, "Capture", "Essentials inbox", this::openCaptureLane),
                Sheets.Item.of(R.drawable.ic_clock, Ui.BLUE_DARK, "Agents", "Scheduled jobs and templates", () -> startActivity(new Intent(this, JobsActivity.class))),
                Sheets.Item.of(R.drawable.ic_movie, Arr.SONARR, "Media", "TV, movies, downloads and your media servers", () -> startActivity(new Intent(this, MediaActivity.class))),
                Sheets.Item.of(R.drawable.ic_pulse, Ui.BLUE_DARK, "System", "Health, usage and services", () -> startActivity(new Intent(this, StatusActivity.class))),
                Sheets.Item.of(R.drawable.ic_settings, Ui.BLUE_DARK, "Settings", "Companion settings", () -> startActivity(new Intent(this, SettingsActivity.class))),
                Sheets.Item.of(R.drawable.ic_terminal, Ui.BLUE_DARK, "Terminal", "Shells and ssh on your server", () -> startActivity(new Intent(this, TerminalActivity.class))),
                Sheets.Item.of(R.drawable.ic_session, Ui.BLUE_DARK, "Switch harness", store.serverType() + " \u00b7 Hermes, Claude Code or Codex", () -> Harness.picker(this)),
                Sheets.Item.of(R.drawable.ic_assistant, Ui.BLUE_DARK, "Model", ChatLogic.shortModel(model.isEmpty() ? defaultModel : model), this::showModelPicker),
                Sheets.Item.of(R.drawable.ic_bolt, Ui.BLUE_DARK, "Reasoning effort", "How hard it thinks, from the next message", () -> showAgentSettings(false)),
                Sheets.Item.of(R.drawable.ic_shield, Ui.BLUE_DARK, "Agent permissions", "Commands and files on the agent host", () -> showAgentSettings(true)),
                Sheets.Item.of(R.drawable.ic_archive, Ui.BLUE_DARK, "Archive", "Hide it, keep the transcript", this::archiveCurrent))));
        Runnable[] render = new Runnable[1];
        render[0] = () -> {
            rows.removeAllViews();
            String q = search.getText().toString().trim().toLowerCase(Locale.ROOT);
            if (q.startsWith("/")) q = q.substring(1);
            String typed = q;
            paletteRow(rows, sheet, R.drawable.ic_new, Ui.BLUE, q.isEmpty() ? "New conversation" : "New conversation about “" + search.getText().toString().trim() + "”",
                    "Start with clean context", () -> createSession("New conversation", typed.isEmpty() ? null : search.getText().toString().trim()));
            int shown = 0;
            for (HermesApi.SessionInfo si : sessions) {
                if (!q.isEmpty() && !si.displayTitle().toLowerCase(Locale.ROOT).contains(q) && !ConversationList.previewOf(si).toLowerCase(Locale.ROOT).contains(q)) continue;
                if (shown == 0) paletteHeader(rows, q.isEmpty() ? "RECENT" : "CONVERSATIONS");
                paletteRow(rows, sheet, 0, Ui.avatarColor(si.id), si.displayTitle(), relTime(si.lastActive) + " · " + ConversationList.previewOf(si), () -> switchSession(si));
                if (++shown >= (q.isEmpty() ? 5 : 12)) break;
            }
            if (sessions.isEmpty() && q.isEmpty()) paletteHeader(rows, "LOADING CONVERSATIONS…");
            boolean header = false;
            for (Sheets.Item item : actions) {
                if (!q.isEmpty() && !item.title.toLowerCase(Locale.ROOT).contains(q)) continue;
                if (!header) { paletteHeader(rows, "ACTIONS"); header = true; }
                paletteRow(rows, sheet, item.icon, item.color, item.title, item.subtitle, item.action);
            }
        };
        search.addTextChangedListener(new TextWatcher() {
            public void beforeTextChanged(CharSequence s, int st, int c, int a) {}
            public void onTextChanged(CharSequence s, int st, int b, int c) { render[0].run(); }
            public void afterTextChanged(Editable e) {}
        });
        render[0].run();
        sheet[0] = Sheets.tall(this, "Commands", null, content);
        network.submit(() -> {
            try {
                List<HermesApi.SessionInfo> all = api.listSessions(150, 0);
                runOnUiThread(() -> { sessions.addAll(all); if (sheet[0].isShowing()) render[0].run(); });
            } catch (Exception ignored) { runOnUiThread(() -> { if (sheet[0].isShowing()) render[0].run(); }); }
        });
        search.postDelayed(() -> { if (!sheet[0].isShowing()) return; search.requestFocus(); ((InputMethodManager) getSystemService(INPUT_METHOD_SERVICE)).showSoftInput(search, InputMethodManager.SHOW_IMPLICIT); }, 200);
    }

    private void paletteHeader(LinearLayout rows, String t) {
        TextView h = Ui.label(this, t, Ui.FAINT); h.setPadding(dp(18), dp(12), 0, dp(4)); rows.addView(h, match(-2));
    }

    private void paletteRow(LinearLayout rows, Dialog[] sheet, int icon, int color, String label, String sub, Runnable action) {
        LinearLayout row = new LinearLayout(this); row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(16), dp(6), dp(14), dp(6)); row.setMinimumHeight(dp(56)); row.setBackground(Ui.ripple(this, Color.TRANSPARENT, 0));
        View mark;
        if (icon == 0) { mark = Ui.avatar(this, label, color, 36); }
        else { ImageView iv = new ImageView(this); iv.setImageResource(icon); iv.setImageTintList(ColorStateList.valueOf(Color.WHITE)); iv.setPadding(dp(8), dp(8), dp(8), dp(8)); iv.setBackground(Ui.round(this, color, 10)); mark = iv; }
        mark.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        row.addView(mark, new LinearLayout.LayoutParams(dp(36), dp(36)));
        LinearLayout copy = new LinearLayout(this); copy.setOrientation(LinearLayout.VERTICAL); copy.setPadding(dp(12), 0, 0, 0);
        TextView t = Ui.text(this, label, 15, Ui.TEXT, false); t.setSingleLine(true); t.setEllipsize(TextUtils.TruncateAt.END); copy.addView(t);
        if (sub != null && !sub.isEmpty()) { TextView st = Ui.text(this, sub, 12, Ui.SUB, false); st.setSingleLine(true); st.setEllipsize(TextUtils.TruncateAt.END); copy.addView(st); }
        row.addView(copy, new LinearLayout.LayoutParams(0, -2, 1));
        row.setContentDescription(label + (sub == null || sub.isEmpty() ? "" : ", " + sub));
        row.setOnClickListener(v -> { if (sheet[0] != null) sheet[0].dismiss(); action.run(); });
        rows.addView(row, match(-2));
    }

    private void showSessionInfo() {
        Sheets.info(this, title, "Current " + store.serverType() + " conversation", new String[][]{{"Conversation", sessionId}, {"Model", n(model, "Default provider model")}, {"Messages loaded", String.valueOf(history.size())}, {"Server", store.endpoint()}});
    }

    private String defaultModel = "";

    /** Short model name under the title; the full id is the accessible name. */
    private void showModel() {
        subView.setText(agentName() + " · " + ChatLogic.shortModel(model.isEmpty() ? defaultModel : model) + (features().models ? " ▾" : ""));
        subView.setContentDescription("Model " + n(model.isEmpty() ? defaultModel : model, "default") + (features().models ? ", tap to change" : ""));
    }

    private void applyUsage(UsageState next) {
        usageState = next;
        if (contextRing != null) contextRing.setUsage(next);
        if (usageSheet != null && usageSheet.showing()) usageSheet.update(next);
    }

    private void refreshUsage(boolean refresh) {
        final AgentBackend target = api;
        final String sid = sessionId, profile = apiProfile;
        final int request = ++usageRequest, eventVersion = usageEventVersion;
        network.submit(() -> {
            try {
                UsageState next = UsageState.parse(target.usage(sid, refresh));
                runOnUiThread(() -> {
                    if (sameAgentSettingsTarget(target, sid, profile) && request == usageRequest && eventVersion == usageEventVersion) applyUsage(next);
                });
            } catch (Exception e) {
                runOnUiThread(() -> {
                    if (!sameAgentSettingsTarget(target, sid, profile) || request != usageRequest) return;
                    String message = String.valueOf(e.getMessage());
                    if (message.startsWith("HTTP 404") || message.startsWith("HTTP 501") || e instanceof UnsupportedOperationException) applyUsage(UsageState.parse(null));
                    if (refresh) toast("Could not refresh usage: " + HermesApi.friendly(e));
                });
            }
        });
    }

    private void showUsage() {
        if (usageSheet != null && usageSheet.showing()) return;
        final AgentBackend target = api;
        final String sid = sessionId, profile = apiProfile;
        usageSheet = new UsageSheet(this, usageState, () -> refreshUsage(true),
                () -> { if (sameAgentSettingsTarget(target, sid, profile)) runServerCommand(api.supportsCommands() ? "/compact" : "/compress"); },
                (target.supportsCommands() || features().hub) ? () -> {
                    if (!sameAgentSettingsTarget(target, sid, profile)) return;
                    if (!target.supportsCommands()) { runServerCommand("/usage reset"); return; }
                    if (usageResetting) return;
                    usageResetting = true;
                    network.submit(() -> {
                        HermesApi.Result result = target.resetUsage(sid);
                        runOnUiThread(() -> { if (sameAgentSettingsTarget(target, sid, profile)) { usageResetting = false; toast(result.text); if (result.ok) refreshUsage(true); } });
                    });
                } : null);
        refreshUsage(false);
    }

    private int modeColor(String value) {
        return AgentSettings.needsConfirm(value) ? Ui.RED : "plan".equals(value) || "smart".equals(value) ? Ui.GREEN
                : "acceptEdits".equals(value) || "workspace-write".equals(value) ? Ui.BLUE : Ui.SUB;
    }

    private void cycleAgentMode() {
        if (modeSaving) return;
        AgentSettings.Option next = AgentSettings.next(agentSettings);
        if (next == null) { showAgentSettings(true); return; }
        permsChip.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP);
        saveAgentField("permission_mode", next.value, next.label, next.needsConfirm);
    }

    private void togglePlan() {
        Boolean current = AgentSettings.planMode(agentSettings);
        if (current != null) saveAgentField("plan_mode", !current, current ? "Plan off" : "Plan on", false);
    }

    private void toggleFast() {
        List<AgentSettings.Option> options = AgentSettings.fastOptions(agentSettings);
        if (options.size() < 2) return;
        int current = -1;
        for (int i = 0; i < options.size(); i++) if (options.get(i).selected) current = i;
        AgentSettings.Option next = options.get(current < 0 ? 1 : (current + 1) % options.size());
        saveAgentField("fast_mode", next.value, next.label, false);
    }

    private void showModeSheet(JSONObject settings) {
        final AgentBackend target = api; final String sid = sessionId, profile = apiProfile;
        List<Sheets.Item> items = new ArrayList<>();
        for (AgentSettings.Option option : AgentSettings.options(settings, true)) {
            items.add(Sheets.Item.of(R.drawable.ic_shield, modeColor(option.value), option.label, option.description,
                    () -> { if (sameAgentSettingsTarget(target, sid, profile) && !option.selected && !"custom".equals(option.value)) saveAgentField("permission_mode", option.value, option.label, option.needsConfirm); }).checked(option.selected));
        }
        Boolean plan = AgentSettings.planMode(settings);
        if (plan != null) items.add(Sheets.Item.of(R.drawable.ic_edit, Ui.GREEN, "Plan · " + (plan ? "On" : "Off"), "Think through the work before making changes", () -> { if (sameAgentSettingsTarget(target, sid, profile)) togglePlan(); }).checked(plan));
        for (AgentSettings.Option option : AgentSettings.fastOptions(settings)) {
            items.add(Sheets.Item.of(R.drawable.ic_bolt, Ui.BLUE, option.label, option.description,
                    () -> { if (sameAgentSettingsTarget(target, sid, profile) && !option.selected) saveAgentField("fast_mode", option.value, option.label, false); }).checked(option.selected));
        }
        if (items.isEmpty()) { toast(settings.optString("permission_note", "No modes available from this server")); return; }
        Sheets.actions(this, "Agent mode", settings.optString("permission_scope", settings.optString("scope", "Changes apply from your next message unless the server supports live changes.")), items.toArray(new Sheets.Item[0]));
    }

    private void saveAgentField(String field, Object value, String label, boolean dangerous) {
        if (modeSaving || modeConfirming) return;
        final AgentBackend target = api;
        final String sid = sessionId, profile = apiProfile;
        final String confirmationKey = profile + "\n" + sid + "\n" + value;
        Runnable save = () -> {
            if (modeSaving || !sameAgentSettingsTarget(target, sid, profile)) return;
            modeSaving = true;
            agentSettingsRequest++;
            network.submit(() -> {
                HermesApi.Result result;
                try { result = target.setSessionSettings(sid, new JSONObject().put(field, value)); }
                catch (Exception e) { result = HermesApi.Result.error(HermesApi.friendly(e)); }
                final HermesApi.Result saved = result;
                runOnUiThread(() -> {
                    if (!sameAgentSettingsTarget(target, sid, profile)) return;
                    modeSaving = false;
                    if (saved.ok) {
                        try { agentSettings.put(field, value); } catch (Exception ignored) { }
                        if ("permission_mode".equals(field)) { setChip(permsChip, api.supportsCommands() ? "Mode · " + label : label); permsChip.setTextColor(modeColor(String.valueOf(value))); }
                        if ("plan_mode".equals(field)) { setChip(planChip, "Plan · " + (Boolean.TRUE.equals(value) ? "On" : "Off")); planChip.setTextColor(Boolean.TRUE.equals(value) ? Ui.GREEN : Ui.SUB); }
                        if ("fast_mode".equals(field)) setChip(fastChip, "⚡ " + label);
                        if (dangerous) {
                            if (confirmedModes.size() >= 128) confirmedModes.remove(confirmedModes.iterator().next());
                            confirmedModes.add(confirmationKey);
                        }
                        Toast note = Toast.makeText(this, label, Toast.LENGTH_SHORT); note.show(); ui.postDelayed(note::cancel, 1500);
                        refreshAgentStrip();
                    } else toast(saved.text);
                });
            });
        };
        if (dangerous && !confirmedModes.contains(confirmationKey)) {
            String scope = "yolo_chat".equals(value) ? "Applies only to this gateway conversation." : agentSettings.optString("permission_scope", "Applies to this agent conversation.");
            modeConfirming = true;
            Sheets.confirm(this, "Enable " + label + "?", "Allows commands and file changes without approval. " + scope, "Enable " + label, true,
                    () -> { modeConfirming = false; save.run(); }, () -> modeConfirming = false);
        } else save.run();
    }

    private boolean sameAgentSettingsTarget(AgentBackend target, String sid, String profile) {
        return !isFinishing() && !isDestroyed() && api == target && sid.equals(sessionId) && profile.equals(apiProfile);
    }

    private void showAgentSettings(boolean permissions) {
        if (!api.supportsAgentSettings()) { toast("Agent settings are unavailable for this server"); return; }
        final AgentBackend target = api;
        final String sid = sessionId, profile = apiProfile;
        final String heading = permissions ? "Agent permissions" : "Reasoning effort";
        toast("Loading " + heading.toLowerCase(Locale.ROOT) + "…");
        network.submit(() -> {
            try {
                JSONObject settings = target.getSessionSettings(sid);
                if (permissions) {
                    runOnUiThread(() -> { if (sameAgentSettingsTarget(target, sid, profile)) { agentSettings = settings; showModeSheet(settings); } });
                    return;
                }
                List<AgentSettings.Option> options = AgentSettings.options(settings, permissions);
                List<Sheets.Choice> choices = new ArrayList<>();
                for (AgentSettings.Option option : options) choices.add(new Sheets.Choice(option.label, option.description, option.selected));
                runOnUiThread(() -> {
                    if (!sameAgentSettingsTarget(target, sid, profile)) return;
                    if (choices.isEmpty()) { toast(settings.optString(permissions ? "permission_note" : "reasoning_note", "The server reported no available " + heading.toLowerCase(Locale.ROOT) + " options")); return; }
                    String scope = settings.optString(permissions ? "permission_scope" : "reasoning_scope", settings.optString("scope", "Changes apply to your next message in this conversation."));
                    Sheets.picker(this, heading, scope, choices, choice -> {
                        if (!sameAgentSettingsTarget(target, sid, profile)) return;
                        AgentSettings.Option option = options.get(choices.indexOf(choice));
                        if (option.selected || (permissions && "custom".equals(option.value))) return;
                        Runnable save = () -> {
                            if (!sameAgentSettingsTarget(target, sid, profile)) return;
                            network.submit(() -> {
                                if (!sameAgentSettingsTarget(target, sid, profile)) return;
                                try {
                                    JSONObject fields = new JSONObject().put(permissions ? "permission_mode" : "reasoning_effort", option.value);
                                    HermesApi.Result result = target.setSessionSettings(sid, fields);
                                    runOnUiThread(() -> { if (sameAgentSettingsTarget(target, sid, profile)) { toast(result.text); if (result.ok) refreshAgentStrip(); } });
                                } catch (Exception e) {
                                    runOnUiThread(() -> { if (sameAgentSettingsTarget(target, sid, profile)) toast(HermesApi.friendly(e)); });
                                }
                            });
                        };
                        if (permissions && ("full-access".equals(option.value) || "off".equals(option.value) || "bypass".equals(option.value))) {
                            Sheets.confirm(this, "Full access?", "Allows commands, network access and file changes across the agent host without approval. " + scope, "Allow full access", true, save);
                        } else save.run();
                    });
                });
            } catch (Exception e) {
                runOnUiThread(() -> { if (sameAgentSettingsTarget(target, sid, profile)) toast("Could not load " + heading.toLowerCase(Locale.ROOT) + ": " + HermesApi.friendly(e)); });
            }
        });
    }

    private void showModelPicker() {
        if (!features().models) { toast("Model selection is unavailable for this server"); return; }
        final String sid = sessionId, profile = apiProfile;
        final AgentBackend target = api;
        toast("Loading models…");
        network.submit(() -> {
            try {
                final List<HermesApi.ModelOption> options = target.modelOptions();
                final List<Sheets.Choice> choices = new ArrayList<>();
                final java.util.Map<Sheets.Choice, HermesApi.ModelOption> models = new java.util.HashMap<>();
                for (HermesApi.ModelOption o : options) {
                    Sheets.Choice choice = new Sheets.Choice(ChatLogic.shortModel(o.id),
                            (o.description.isEmpty() ? o.provider : o.description) + (o.current ? " · server default" : ""),
                            model.isEmpty() ? o.current : o.id.equals(model));
                    choices.add(choice); models.put(choice, o);
                }
                java.util.Collections.sort(choices, (x, y) -> (y.selected ? 1 : 0) - (x.selected ? 1 : 0));
                runOnUiThread(() -> {
                    if (!sameAgentSettingsTarget(target, sid, profile)) return;
                    if (choices.isEmpty()) { toast("The server reported no usable models"); return; }
                    if (model.isEmpty()) for (HermesApi.ModelOption option : options) if (option.current) { defaultModel = option.id; showModel(); break; }
                    Sheets.picker(this, "Model", "Now: " + ChatLogic.shortModel(model.isEmpty() ? defaultModel : model), choices, choice -> {
                        HermesApi.ModelOption pick = models.get(choice);
                        if (pick == null || !sameAgentSettingsTarget(target, sid, profile)) return;
                        network.submit(() -> {
                            HermesApi.Result r = target.setSessionModel(sid, pick.provider, pick.id);
                            runOnUiThread(() -> { toast(r.text); if (r.ok && sameAgentSettingsTarget(target, sid, profile)) { model = pick.id; showModel(); } });
                        });
                    });
                });
            } catch (Exception e) { runOnUiThread(() -> toast("Could not load models: " + HermesApi.friendly(e))); }
        });
    }

    private void showActionSheet() {
        Sheets.actions(this, "Quick actions", "Fast paths into real assistant work", visibleItems(
                Sheets.Item.of(R.drawable.ic_voice, Ui.BLUE_DARK, "Talk", "Hands-free conversation", this::startVoice),
                Sheets.Item.of(R.drawable.ic_next, Ui.BLUE_DARK, "Next actions", "Prioritise the current context", () -> sendText("Give me the three highest-value next actions from our current context and offer to execute the first.", false)),
                Sheets.Item.of(R.drawable.ic_server, Ui.BLUE_DARK, "Audit server", "Check critical homelab services", () -> sendText("Audit the homelab critical services now. Failures first.", false)),
                Sheets.Item.of(R.drawable.ic_home, Ui.BLUE_DARK, "Home Assistant", "Anything requiring attention", () -> sendText("Check Home Assistant now and show anything requiring attention.", false)),
                Sheets.Item.of(R.drawable.ic_skills, Ui.BLUE_DARK, "Use a skill", "Pick from installed skills", () -> startActivity(new Intent(this, CatalogActivity.class).putExtra(CatalogActivity.EXTRA_SKILLS, true))),
                Sheets.Item.of(R.drawable.ic_clock, Ui.BLUE_DARK, "Schedule this", "Turn a prompt into a recurring job", () -> startActivity(new Intent(this, JobsActivity.class).putExtra(JobsActivity.EXTRA_PROMPT, composer.getText().toString()))),
                Sheets.Item.of(R.drawable.ic_new, Ui.BLUE, "New conversation", "Start with clean context", this::newChatFromHeader)));
    }

    @Override protected void onActivityResult(int request, int result, Intent data) {
        super.onActivityResult(request, result, data);
        handleAttachmentResult(request, result, data);
    }

    // ── voice ───────────────────────────────────────────────────────────────────────────────────

    // Hold-to-talk on the mic button (Settings › Voice › Hold to talk): record while held, finish on release,
    // slide up to cancel. Only when the box is empty and nothing is running; otherwise the button behaves as usual.
    private HoldToTalk hold;
    private boolean holdOn, holdArmed, holdCancelling;
    private float holdDownY;
    private final Runnable holdStart = new Runnable() {
        @Override public void run() {
            if (!holdArmed) return;
            if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
                holdArmed = false; requestedVoiceMode = VoiceSheet.Mode.CHAT; requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO}, MIC_REQ); return;
            }
            stopTalking();
            holdOn = true; holdCancelling = false;
            Ui.tick(sendMic);
            micLook(Ui.RED);
            sendMic.animate().scaleX(voiceLayout() ? 1.1f : 1.3f).scaleY(voiceLayout() ? 1.1f : 1.3f).setDuration(Ui.motionEnabled() ? 120 : 0).start();
            composer.setHint("Listening… let go to " + ("review".equals(store.voiceSend()) ? "add it to the box" : "send") + ", slide up to cancel");
            if (voiceHint != null) voiceHint.setText("Listening… let go to send · slide up to cancel");
            if (hold == null) hold = new HoldToTalk(ChatActivity.this, store, holdHost);
            hold.start();
        }
    };
    private final HoldToTalk.Host holdHost = new HoldToTalk.Host() {
        @Override public void level(float level) { if (holdOn && !holdCancelling) sendMic.setAlpha(.75f + Math.min(.25f, level * 1.5f)); }
        @Override public void partial(String text) { if (holdOn && !holdCancelling) { composer.setHint(text); if (voiceHint != null) voiceHint.setText(text); } }
        @Override public void transcribing() { composer.setHint("Transcribing…"); }
        @Override public void result(String text) { holdReset(); deliverVoice(text); }
        @Override public void failed(String message) { holdReset(); toast(message); }
    };

    private boolean micTouch(View v, MotionEvent e) {
        boolean eligible = (store.voiceHold() || voiceLayout()) && composer.getText().toString().trim().isEmpty() && !hasAttachments() && !busy() && !talking;
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                if (!eligible) return false;
                holdArmed = true; holdDownY = e.getRawY(); holdCancelling = false;
                v.postDelayed(holdStart, 280);
                return true;
            case MotionEvent.ACTION_MOVE:
                if (!holdArmed && !holdOn) return false;
                if (holdOn && hold != null) {
                    boolean away = holdDownY - e.getRawY() > dp(90);
                    if (away != holdCancelling) {
                        holdCancelling = away;
                        micLook(away ? Ui.FAINT : Ui.RED);
                        composer.setHint(away ? "Let go to cancel" : "Listening… let go to " + ("review".equals(store.voiceSend()) ? "add it to the box" : "send") + ", slide up to cancel");
                    }
                }
                return true;
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL: {
                if (!holdArmed && !holdOn) return false;
                v.removeCallbacks(holdStart);
                boolean wasArmed = holdArmed; holdArmed = false;
                if (!holdOn) { if (wasArmed && e.getActionMasked() == MotionEvent.ACTION_UP) { v.performClick(); } return true; } // a plain tap
                if (e.getActionMasked() == MotionEvent.ACTION_CANCEL || holdCancelling || hold == null) { if (hold != null) hold.cancel(); holdReset(); return true; }
                if (hold.heldMs() < 350) { hold.cancel(); holdReset(); toast("Hold the button while you speak"); return true; }
                micLook(Ui.TEXT);
                sendMic.animate().scaleX(1f).scaleY(1f).setDuration(Ui.motionEnabled() ? 120 : 0).start();
                sendMic.setAlpha(1f);
                hold.release();
                return true;
            }
            default: return holdArmed || holdOn;
        }
    }

    private void holdReset() {
        holdOn = false; holdArmed = false; holdCancelling = false;
        sendMic.removeCallbacks(holdStart);
        micLook(Ui.TEXT);
        sendMic.animate().scaleX(1f).scaleY(1f).setDuration(Ui.motionEnabled() ? 120 : 0).start();
        sendMic.setAlpha(1f);
        if (voiceHint != null) voiceHint.setText("Hold the mic and speak");
        updateComposerState(composer.getText().toString());
    }

    /** Spoken text goes straight out, or into the box first when Settings › Voice says to review it. */
    private void deliverVoice(String text) {
        if ("review".equals(store.voiceSend())) {
            String cur = composer.getText().toString();
            composer.setText(cur.isEmpty() ? text : cur.trim() + " " + text);
            composer.setSelection(composer.length());
            composer.requestFocus();
            return;
        }
        CompanionCommand c = CompanionCommand.parse(text);
        if (c != null) executeCommand(c); else sendText(text, true);
    }

    /** Record, transcribe with the selected speech recognizer, and send. */
    private void startVoice() { startVoice(VoiceSheet.Mode.CHAT); }
    private void startVoice(VoiceSheet.Mode mode) {
        requestedVoiceMode = mode;
        stopTalking();
        VoiceSheet.show(this, store, mode, text -> {
            if (mode == VoiceSheet.Mode.DICTATE) { sendText("Rewrite this dictation as a clean, well-punctuated message in my voice. Reply with ONLY the message text: " + text, false); return; }
            deliverVoice(text);
        });
    }

    @Override public void onRequestPermissionsResult(int request, String[] p, int[] g) {
        super.onRequestPermissionsResult(request, p, g);
        if (request == CONTACTS_REQ && g.length > 0 && g[0] == PackageManager.PERMISSION_GRANTED) {
            store.allowContacts(true);
            if (pendingLocal != null) { String again = pendingLocal; pendingLocal = null; runLocally(again); return; }
            if (!busy()) sendText("[PHONE RESULTS] Contacts access is now allowed. Retry the last phone action.", false, true);
            return;
        }
        if (request == MIC_REQ && g.length > 0) {
            if (g[0] == PackageManager.PERMISSION_GRANTED) startVoice(requestedVoiceMode);
            else if (!shouldShowRequestPermissionRationale(Manifest.permission.RECORD_AUDIO)) VoiceSheet.showPermissionDenied(this);
        }
    }

    private void openAssistantSettings() {
        try { startActivity(new Intent(Settings.ACTION_VOICE_INPUT_SETTINGS)); }
        catch (Exception e) { startActivity(new Intent(Settings.ACTION_MANAGE_DEFAULT_APPS_SETTINGS)); }
    }

    private void initTts() {
        speaker = new Speaker(this, store);
        // Hands-free: when a spoken reply finishes, listen for the follow-up.
        speaker.onDone(id -> runOnUiThread(() -> {
            talking = false; updateComposerState(composer.getText().toString());
            if ("converse".equals(id) && resumed && !busy()) startVoice();
        }));
    }

    private void speak(String text, boolean thenListen) {
        if (speaker == null || text == null || text.trim().isEmpty()) return;
        talking = true; updateComposerState(composer.getText().toString());
        speaker.speak(text, thenListen ? "converse" : "hermes");
    }

    /** Reading a reply aloud: the send button becomes Stop until it finishes or is tapped. */
    private boolean talking;
    private void stopTalking() {
        if (speaker != null) speaker.stop();
        talking = false; updateComposerState(composer.getText().toString());
    }

    // ── small helpers ───────────────────────────────────────────────────────────────────────────

    private TextView systemBubble(String text, int color) {
        TextView v = Ui.caption(this, text, color); v.setTypeface(Ui.WORD_SEMI);
        v.setGravity(Gravity.CENTER); v.setPadding(dp(12), dp(7), dp(12), dp(7)); v.setBackground(Ui.round(this, (Ui.tier(1) & 0xffffff) | 0xcc000000, 99));
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(-2, -2); p.gravity = Gravity.CENTER_HORIZONTAL; p.setMargins(dp(12), dp(6), dp(12), dp(6));
        messages.addView(v, p);
        Ui.fadeIn(v);
        scrollBottom();
        return v;
    }
    private TextView pill(String text, int color) {
        TextView v = Ui.caption(this, text, color); v.setTypeface(Ui.WORD_SEMI);
        v.setGravity(Gravity.CENTER); v.setPadding(dp(14), dp(8), dp(14), dp(8)); v.setMinHeight(dp(36)); v.setBackground(Ui.round(this, (Ui.tier(2) & 0xffffff) | 0xcc000000, 99));
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(-2, -2); p.gravity = Gravity.CENTER_HORIZONTAL; p.setMargins(0, dp(4), 0, dp(8));
        messages.addView(v, p);
        return v;
    }
    /** "Approved"/"Rejected" if the user already answered proposal #id in this conversation, else null. */
    String proposalAnswer(String id) {
        for (HermesApi.Message m : history) if ("user".equals(m.role)) {
            String c = m.content.trim();
            if (c.equalsIgnoreCase("Approved #" + id)) return "Approved";
            if (c.equalsIgnoreCase("Rejected #" + id)) return "Rejected";
        }
        return null;
    }
    void answerProposal(String id, boolean approved) {
        if (busy()) { toast("Wait for " + agentName() + " to finish, then answer"); renderMessages(false); return; }
        // Bare text via the retry path: no reply quote or attachments, so proposalAnswer() matches it later.
        String text = (approved ? "Approved #" : "Rejected #") + id;
        sendText(text, false, false, payload(text, new ArrayList<>()), DeviceContext.systemMessage(this, store, false));
    }

    private void systemNote(String content) {
        String text = content.contains("network error") ? "Reply was interrupted · " + agentName() + " continued"
                : content.contains("length limit") ? "Reply hit the length limit · " + agentName() + " continued" : agentName() + " continued";
        TextView v = Ui.caption(this, text, Ui.FAINT);
        v.setGravity(Gravity.CENTER); v.setPadding(dp(12), dp(4), dp(12), dp(4));
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(-1, -2); p.setMargins(0, dp(4), 0, dp(4));
        messages.addView(v, p);
    }
    private void datePill(String day) {
        TextView v = Ui.num(this, day, 12, Ui.SUB); // small mono pill
        v.setGravity(Gravity.CENTER); v.setPadding(dp(12), dp(5), dp(12), dp(5)); v.setBackground(Ui.round(this, (Ui.tier(2) & 0xffffff) | 0xcc000000, 99));
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(-2, -2); p.gravity = Gravity.CENTER_HORIZONTAL; p.setMargins(0, dp(8), 0, dp(6));
        messages.addView(v, p);
    }
    private int unread, liveKids;
    /** Fade/scale the jump pill in or out; leaving the bottom-zone clears the unread count. */
    private void showJump(boolean show) {
        if (!show && unread != 0) { unread = 0; jumpButton.setText("↓ " + ChatChrome.jumpLabel(0)); }
        if ((jumpButton.getVisibility() == View.VISIBLE) == show) return;
        jumpButton.animate().cancel();
        if (show) {
            jumpButton.setVisibility(View.VISIBLE);
            if (Ui.motionEnabled()) { jumpButton.setAlpha(0f); jumpButton.setTranslationY(dp(8));
                jumpButton.animate().alpha(1f).translationY(0).setDuration(200).setInterpolator(Ui.EASE_OUT).start(); }
        } else if (Ui.motionEnabled()) {
            jumpButton.animate().alpha(0f).translationY(dp(8)).setDuration(140).setInterpolator(Ui.EASE_OUT).withEndAction(() -> { if (isNearBottom()) jumpButton.setVisibility(View.GONE); }).start();
        } else jumpButton.setVisibility(View.GONE);
    }
    private void bumpUnread(int n) {
        if (n <= 0 || jumpButton.getVisibility() != View.VISIBLE) return;
        unread += n; jumpButton.setText("↓ " + ChatChrome.jumpLabel(unread)); jumpButton.setContentDescription("Jump to latest, " + unread + " new");
    }
    private boolean isNearBottom() {
        View child = scroller.getChildAt(0);
        return child == null || child.getBottom() - (scroller.getHeight() + scroller.getScrollY()) < dp(160);
    }
    private void scrollBottom() { scrollBottom(false); }
    /** Smooth while streaming (so the tail glides instead of snapping); instant for loads. */
    private void scrollBottom(boolean smooth) {
        scroller.post(() -> {
            View child = scroller.getChildAt(0);
            if (child == null) return;
            if (smooth && Ui.motionEnabled()) scroller.smoothScrollTo(0, Math.max(0, child.getHeight() - scroller.getHeight()));
            else scroller.fullScroll(View.FOCUS_DOWN);
        });
    }
    private void toast(String s) { Toast.makeText(this, s == null ? "Done" : s, Toast.LENGTH_SHORT).show(); }
    private void copy(String s) { ((ClipboardManager) getSystemService(CLIPBOARD_SERVICE)).setPrimaryClip(ClipData.newPlainText(agentName(), s)); toast("Copied"); }
    private static String n(String s, String f) { return s == null || s.isEmpty() ? f : s; }
    private static String time(double ts) { return new SimpleDateFormat("HH:mm", Locale.getDefault()).format(ts <= 0 ? new Date() : new Date((long) (ts * 1000))); }
    static String day(double ts) {
        if (ts <= 0) return "Today";
        java.util.Calendar a = java.util.Calendar.getInstance(), b = java.util.Calendar.getInstance(); b.setTimeInMillis((long) (ts * 1000));
        if (a.get(java.util.Calendar.YEAR) == b.get(java.util.Calendar.YEAR) && a.get(java.util.Calendar.DAY_OF_YEAR) == b.get(java.util.Calendar.DAY_OF_YEAR)) return "Today";
        a.add(java.util.Calendar.DAY_OF_YEAR, -1);
        if (a.get(java.util.Calendar.YEAR) == b.get(java.util.Calendar.YEAR) && a.get(java.util.Calendar.DAY_OF_YEAR) == b.get(java.util.Calendar.DAY_OF_YEAR)) return "Yesterday";
        return new SimpleDateFormat("d MMMM", Locale.getDefault()).format(b.getTime());
    }
    static String relTime(double ts) {
        if (ts <= 0) return "";
        long t = (long) (ts * 1000), diff = System.currentTimeMillis() - t;
        if (diff < 60_000) return "now";
        if (diff < 3_600_000) return (diff / 60_000) + "m";
        String d = day(ts);
        if ("Today".equals(d)) return (diff / 3_600_000) + "h";
        if ("Yesterday".equals(d)) return "yesterday";
        return new SimpleDateFormat("d MMM", Locale.getDefault()).format(new Date(t));
    }
    static String host(String endpoint) { try { return new java.net.URL(endpoint).getHost(); } catch (Exception e) { return endpoint; } }

    private void shareTranscript() {
        StringBuilder sb = new StringBuilder();
        sb.append(title == null ? agentName() : title).append(" · ").append(agentName()).append(" conversation\n\n");
        for (HermesApi.Message m : history) {
            if ("tool".equals(m.role)) continue;
            for (HermesApi.ToolCall t : m.toolCalls) sb.append("• ").append(Tools.label(t.name)).append(": ").append(t.summary()).append('\n');
            String body = "user".equals(m.role) ? m.content.trim() : DeviceActions.parse(m.content).cleanText.trim();
            if (body.isEmpty()) continue;
            sb.append("user".equals(m.role) ? "You: " : agentName() + ": ").append(body).append("\n\n");
        }
        Intent i = new Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_SUBJECT, title + " · " + agentName() + " transcript").putExtra(Intent.EXTRA_TEXT, sb.toString().trim());
        try { startActivity(Intent.createChooser(i, "Export conversation")); } catch (Exception e) { toast("Nothing to share yet"); }
    }

    @Override public void onBackPressed() {
        if (drawerDialog != null && drawerDialog.isShowing()) { drawerDialog.dismiss(); return; }
        if (replyBar.getVisibility() == View.VISIBLE) { clearReply(); return; }
        if (commandPanel.getVisibility() == View.VISIBLE) { commandPanel.setVisibility(View.GONE); return; }
        if (Nav.leaveFromTab(this, Layout.Tab.ASSISTANT)) return;
        if (isTaskRoot() && Ui.layout == Layout.DASHBOARD) { startActivity(Nav.home(this)); finish(); return; }
        if (isTaskRoot()) moveTaskToBack(true); else super.onBackPressed();
    }
}
