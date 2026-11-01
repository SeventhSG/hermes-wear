package cloud.stormlabs.hermescompanion;

import android.app.assist.AssistContent;
import android.app.assist.AssistStructure;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.service.voice.VoiceInteractionSession;
import android.text.TextUtils;
import android.util.Base64;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.EditorInfo;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * A real assistant overlay: a bottom card shown over whatever app the user was in, invoked by the
 * assist gesture or a power-button hold. It listens, streams the reply from {@link RunManager} and
 * speaks it, without ever leaving the app the user was already using.
 */
public final class HermesVoiceSession extends VoiceInteractionSession implements RunManager.Observer {
    /** Show arg: opened by the wake word, which is already recording the command. */
    static final String ARG_FROM_WAKE = "hermes_from_wake";


    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService bg = Executors.newCachedThreadPool();

    private SettingsStore store;
    private AgentBackend api;
    private RunManager runs;

    // Resolved lazily on first show, cached across shows within this session's lifetime.
    private String sessionId, sessionTitle = "Hermes";

    private RunManager.LiveRun currentRun;
    private VoiceRecorder recorder;
    private boolean listening;
    private boolean takeInFlight, waitingForWake;
    private int takeToken;
    private Speaker speaker;
    private int invocationToken;
    private int submissionToken = -1;

    // Screen context captured by the framework for this invocation only.
    private String screenText;
    private String screenshotDataUrl;
    private boolean useScreen = true;

    // Views
    private FrameLayout root;
    private LinearLayout card;
    private ColorDrawable scrim;
    private TextView statusLine, hintLine, transcriptLine, toolLine, replyText, errorText, errorRetry;
    private ScrollView replyScroll;
    private LinearLayout approvalRow, errorRow;
    private View suggestionScroll;
    private EditText textInput;
    private AssistantOrb micButton;
    private ImageButton sendButton;
    private TextView stopButton, openInAppButton, useScreenChip, accessView;
    private View permissionRow;

    // Overlay state
    private AssistantState.State state = AssistantState.State.IDLE;
    private int step;
    private String lastTool = "", retryText;
    private boolean hasReply, replyShownOnce;
    private float smoothedLevel;
    private Runnable retryAction;
    private final FadeSpan cursor = new FadeSpan();
    private final java.util.ArrayList<FadeSpan> transcriptWords = new java.util.ArrayList<>();
    private String transcriptShown = "";
    private android.animation.ValueAnimator wordAnim;
    private final Runnable blink = new Runnable() {
        @Override public void run() {
            if (currentRun == null || !currentRun.active()) return;
            cursor.alpha = cursor.alpha > .5f ? 0f : 1f;
            replyText.invalidate();
            main.postDelayed(this, 530);
        }
    };

    public HermesVoiceSession(Context context) { super(context); }

    @Override public void onCreate() {
        super.onCreate();
        store = new SettingsStore(getContext());
        KitWake.poke(store);
        Ui.apply(getContext());
        apiProfile = store.profileId();
        api = Backends.create(store, apiProfile);
        runs = RunManager.get(getContext());
        // The typed-input keyboard must lift the sheet (via IME insets), not cover it.
        try { android.view.Window w = getWindow() == null ? null : getWindow().getWindow(); if (w != null) w.setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE); } catch (Throwable ignored) {}
    }

    /** The session outlives hide(): after a profile switch it must not keep talking to the old profile. */
    private String apiProfile;

    /** A span whose alpha is animated: word-by-word transcript fade and the blinking reply cursor. */
    private static final class FadeSpan extends android.text.style.CharacterStyle {
        float alpha = 1f;
        @Override public void updateDrawState(android.text.TextPaint tp) { tp.setAlpha(Math.round(255 * alpha)); }
    }

    // ── content view ────────────────────────────────────────────────────────────────────────────

    @Override public View onCreateContentView() {
        Context c = getContext();
        root = new FrameLayout(c);
        scrim = new ColorDrawable((Ui.BG & 0xffffff) | 0xff000000);
        scrim.setAlpha(0);
        root.setBackground(scrim);
        root.setOnClickListener(v -> dismiss());

        card = new LinearLayout(c);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setBackground(Ui.card(c, 3));
        card.setPadding(0, 0, 0, dp(c, 8));
        card.setOnClickListener(v -> { });
        Ui.elevate(card, 3);

        // Handle + header are the drag zone for swipe-down dismiss.
        LinearLayout top = new LinearLayout(c);
        top.setOrientation(LinearLayout.VERTICAL);
        top.setOnTouchListener(this::dragCard);
        View handle = new View(c);
        handle.setBackground(Ui.round(c, Ui.FAINT, 99));
        LinearLayout.LayoutParams hp = new LinearLayout.LayoutParams(dp(c, 36), dp(c, 4));
        hp.gravity = Gravity.CENTER_HORIZONTAL; hp.setMargins(0, dp(c, 10), 0, dp(c, 6));
        top.addView(handle, hp);

        LinearLayout headerRow = new LinearLayout(c);
        headerRow.setGravity(Gravity.CENTER_VERTICAL);
        headerRow.setPadding(dp(c, 16), 0, dp(c, 4), 0);
        View mark = Ui.hermesLogo(c, 28);
        mark.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        headerRow.addView(mark, new LinearLayout.LayoutParams(dp(c, 28), dp(c, 28)));
        statusLine = Ui.label(c, AssistantState.label(AssistantState.State.IDLE, 0), Ui.TEXT);
        statusLine.setTextSize(12);
        statusLine.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
        statusLine.setPadding(dp(c, 10), 0, dp(c, 8), 0);
        statusLine.setSingleLine(true); statusLine.setEllipsize(TextUtils.TruncateAt.END);
        headerRow.addView(statusLine, new LinearLayout.LayoutParams(0, -2, 1));
        // Chips sit on their own row under the header so the state label always has room.
        LinearLayout chipRow = new LinearLayout(c);
        chipRow.setGravity(Gravity.CENTER_VERTICAL);
        chipRow.setPadding(dp(c, 12), 0, dp(c, 12), 0);
        accessView = Ui.text(c, "Full access", 12, Ui.AMBER, true);
        accessView.setGravity(Gravity.CENTER);
        accessView.setPadding(dp(c, 12), dp(c, 6), dp(c, 12), dp(c, 6));
        accessView.setBackground(Ui.stroke(c, Color.TRANSPARENT, (Ui.AMBER & 0xffffff) | 0x66000000, 99));
        accessView.setVisibility(View.GONE);
        LinearLayout.LayoutParams ap = new LinearLayout.LayoutParams(-2, -2); ap.rightMargin = dp(c, 8);
        chipRow.addView(accessView, ap);
        useScreenChip = Ui.text(c, "Use screen", 12, Ui.BLUE, true);
        useScreenChip.setGravity(Gravity.CENTER);
        useScreenChip.setMinHeight(dp(c, 48));
        useScreenChip.setVisibility(View.GONE);
        useScreenChip.setPadding(dp(c, 10), dp(c, 5), dp(c, 10), dp(c, 5));
        useScreenChip.setOnClickListener(v -> { useScreen = !useScreen; renderScreenChip(); });
        chipRow.addView(useScreenChip, new LinearLayout.LayoutParams(-2, -2));
        TextView close = Ui.text(c, "×", 26, Ui.SUB, false);
        close.setGravity(Gravity.CENTER);
        close.setContentDescription("Close assistant");
        close.setOnClickListener(v -> dismiss());
        Ui.press(close);
        headerRow.addView(close, new LinearLayout.LayoutParams(dp(c, 48), dp(c, 48)));
        top.addView(headerRow, new LinearLayout.LayoutParams(-1, -2));
        top.addView(chipRow, new LinearLayout.LayoutParams(-1, -2));
        card.addView(top, new LinearLayout.LayoutParams(-1, -2));

        hintLine = Ui.caption(c, "", Ui.SUB);
        hintLine.setPadding(dp(c, 16), 0, dp(c, 16), dp(c, 4));
        hintLine.setVisibility(View.GONE);
        card.addView(hintLine, new LinearLayout.LayoutParams(-1, -2));

        transcriptLine = Ui.title(c, "", Ui.TEXT);
        transcriptLine.setPadding(dp(c, 16), dp(c, 4), dp(c, 16), dp(c, 8));
        transcriptLine.setLineSpacing(0, 1.12f);
        transcriptLine.setVisibility(View.GONE); // shown once there are words, so an idle sheet has no empty band
        transcriptLine.setOnClickListener(v -> editPendingSend());
        card.addView(transcriptLine, new LinearLayout.LayoutParams(-1, -2));

        toolLine = Ui.caption(c, "", Ui.AMBER);
        toolLine.setPadding(dp(c, 16), 0, dp(c, 16), dp(c, 4));
        toolLine.setVisibility(View.GONE);
        card.addView(toolLine, new LinearLayout.LayoutParams(-1, -2));

        replyScroll = new ScrollView(c);
        replyText = Ui.body(c, "", Ui.TEXT);
        replyText.setTextSize(15);
        replyText.setLineSpacing(0, 1.18f);
        replyText.setPadding(dp(c, 16), 0, dp(c, 16), dp(c, 8));
        replyScroll.addView(replyText);
        replyScroll.setVisibility(View.GONE);
        card.addView(replyScroll, new LinearLayout.LayoutParams(-1, -2));

        errorRow = new LinearLayout(c);
        errorRow.setGravity(Gravity.CENTER_VERTICAL);
        errorRow.setPadding(dp(c, 16), 0, dp(c, 8), dp(c, 4));
        errorRow.setVisibility(View.GONE);
        errorText = Ui.body(c, "", Ui.TEXT);
        errorText.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
        errorRow.addView(errorText, new LinearLayout.LayoutParams(0, -2, 1));
        errorRetry = chip(c, "Try again", Ui.BLUE);
        errorRetry.setOnClickListener(v -> { Runnable r = retryAction; if (r != null) r.run(); });
        errorRow.addView(errorRetry, new LinearLayout.LayoutParams(-2, dp(c, 48)));
        card.addView(errorRow, new LinearLayout.LayoutParams(-1, -2));

        approvalRow = new LinearLayout(c);
        approvalRow.setPadding(dp(c, 8), 0, dp(c, 8), dp(c, 4));
        approvalRow.setVisibility(View.GONE);
        HorizontalScrollView approvalScroll = new HorizontalScrollView(c);
        approvalScroll.setHorizontalScrollBarEnabled(false);
        approvalScroll.addView(approvalRow);
        card.addView(approvalScroll, new LinearLayout.LayoutParams(-1, -2));

        permissionRow = buildPermissionRow(c);
        permissionRow.setVisibility(View.GONE);
        card.addView(permissionRow, new LinearLayout.LayoutParams(-1, -2));

        suggestionScroll = buildSuggestions(c);
        card.addView(suggestionScroll, new LinearLayout.LayoutParams(-1, -2));

        card.addView(buildInputRow(c), new LinearLayout.LayoutParams(-1, -2));
        card.addView(buildButtonRow(c), new LinearLayout.LayoutParams(-1, -2));

        FrameLayout.LayoutParams cp = new FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM);
        cp.setMargins(dp(c, 8), 0, dp(c, 8), dp(c, 8));
        root.addView(card, cp);
        Ui.liftAboveSystemBars(card);
        return root;
    }

    private TextView chip(Context c, String label, int color) {
        TextView t = Ui.text(c, label, 13, color, true);
        t.setGravity(Gravity.CENTER);
        t.setMinHeight(dp(c, 48));
        t.setPadding(dp(c, 14), 0, dp(c, 14), 0);
        t.setBackground(Ui.press(c, Color.TRANSPARENT, color, 99));
        Ui.press(t);
        return t;
    }

    private View buildSuggestions(Context c) {
        LinearLayout row = new LinearLayout(c);
        row.setPadding(dp(c, 12), 0, dp(c, 12), dp(c, 2));
        for (String s : new String[]{"What's on my screen?", "How's my homelab?", "Set a timer"}) {
            TextView chip = chip(c, s, Ui.TEXT);
            chip.setBackground(Ui.press(c, Ui.SURFACE, Ui.LINE, 99));
            chip.setOnClickListener(v -> submit(s, false));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, dp(c, 48));
            lp.setMargins(dp(c, 4), 0, dp(c, 4), 0);
            row.addView(chip, lp);
        }
        HorizontalScrollView scroll = new HorizontalScrollView(c);
        scroll.setHorizontalScrollBarEnabled(false);
        scroll.addView(row);
        return scroll;
    }

    /** Swipe-down on the handle/header drags the sheet; past 96dp or a flick it dismisses, else it springs back. */
    private float dragStartY, dragVelocity, dragLastY; private long dragLastT;
    private boolean dragCard(View v, android.view.MotionEvent e) {
        switch (e.getActionMasked()) {
            case android.view.MotionEvent.ACTION_DOWN:
                dragStartY = dragLastY = e.getRawY(); dragLastT = e.getEventTime(); dragVelocity = 0; return true;
            case android.view.MotionEvent.ACTION_MOVE: {
                float dy = Math.max(0, e.getRawY() - dragStartY);
                card.setTranslationY(dy);
                scrim.setAlpha(Math.round(SCRIM_ALPHA * Math.max(0f, 1f - dy / (card.getHeight() + 1f))));
                long dt = Math.max(1, e.getEventTime() - dragLastT);
                dragVelocity = (e.getRawY() - dragLastY) / dt; dragLastY = e.getRawY(); dragLastT = e.getEventTime();
                return true;
            }
            case android.view.MotionEvent.ACTION_UP:
                if (card.getTranslationY() > dp(getContext(), 96) || dragVelocity > 1.2f) dismiss();
                else settleSheet();
                return true;
            case android.view.MotionEvent.ACTION_CANCEL: settleSheet(); return true;
            default: return false;
        }
    }

    private static final int SCRIM_ALPHA = 0xb0;

    private void settleSheet() {
        if (!Ui.motionEnabled()) { card.setTranslationY(0); scrim.setAlpha(SCRIM_ALPHA); return; }
        card.animate().translationY(0).setDuration(220).setInterpolator(Ui.EASE_OUT).start();
        fadeScrim(SCRIM_ALPHA, 220);
    }

    private void fadeScrim(int to, long ms) {
        android.animation.ValueAnimator a = android.animation.ValueAnimator.ofInt(scrim.getAlpha(), to);
        a.setDuration(ms); a.setInterpolator(Ui.EASE_OUT);
        a.addUpdateListener(x -> scrim.setAlpha((int) x.getAnimatedValue()));
        a.start();
    }

    /** Slide down and fade the dim, then hide. Instant when animations are off. */
    private void dismiss() {
        if (!Ui.motionEnabled() || card == null || !shown) { hide(); return; }
        card.animate().cancel();
        card.animate().translationY(card.getHeight() + dp(getContext(), 16)).setDuration(220).setInterpolator(Ui.EASE_OUT)
                .withEndAction(this::hide).start();
        fadeScrim(0, 220);
    }

    private void slideIn() {
        card.animate().cancel();
        if (!Ui.motionEnabled()) { card.setTranslationY(0); scrim.setAlpha(SCRIM_ALPHA); return; }
        card.setTranslationY(getContext().getResources().getDisplayMetrics().heightPixels);
        scrim.setAlpha(0);
        card.animate().translationY(0).setDuration(320).setInterpolator(Ui.EASE_OUT).start();
        fadeScrim(SCRIM_ALPHA, 320);
    }

    private View buildPermissionRow(Context c) {
        LinearLayout row = new LinearLayout(c);
        row.setOrientation(LinearLayout.VERTICAL);
        row.setPadding(dp(c, 16), dp(c, 4), dp(c, 16), dp(c, 10));
        TextView msg = Ui.body(c, "Hermes needs microphone access to listen.", Ui.SUB);
        row.addView(msg, new LinearLayout.LayoutParams(-1, -2));
        TextView grant = Ui.text(c, "Grant microphone access", 14, Color.WHITE, true);
        grant.setGravity(Gravity.CENTER);
        grant.setBackground(Ui.press(c, Ui.BLUE, Ui.BLUE, 12));
        LinearLayout.LayoutParams gp = new LinearLayout.LayoutParams(-1, dp(c, 48));
        gp.topMargin = dp(c, 8);
        grant.setOnClickListener(v -> {
            try {
                getContext().startActivity(new Intent(getContext(), MainActivity.class)
                        .putExtra(MainActivity.EXTRA_START_VOICE, true)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP));
            } catch (Throwable ignored) { }
            hide();
        });
        row.addView(grant, gp);
        return row;
    }

    private View buildInputRow(Context c) {
        LinearLayout row = new LinearLayout(c);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(c, 8), dp(c, 4), dp(c, 10), dp(c, 4));
        row.setClipChildren(false);

        micButton = new AssistantOrb(c);
        micButton.setContentDescription("Listen");
        micButton.setOnClickListener(v -> { if (listening) stopListening(); else if (state == AssistantState.State.SPEAKING) interruptSpeech(); else startListening(false); });
        row.addView(micButton, new LinearLayout.LayoutParams(dp(c, 64), dp(c, 64)));

        LinearLayout pill = new LinearLayout(c);
        pill.setGravity(Gravity.CENTER_VERTICAL);
        pill.setBackground(Ui.stroke(c, Ui.SURFACE, Ui.LINE, 22));
        textInput = new EditText(c);
        textInput.setTextColor(Ui.TEXT); textInput.setHintTextColor(Ui.FAINT);
        textInput.setHint("Message Hermes"); textInput.setTextSize(15);
        textInput.setTypeface(Ui.WORD);
        textInput.setBackground(null);
        textInput.setMinHeight(dp(c, 48));
        textInput.setPadding(dp(c, 16), dp(c, 10), dp(c, 6), dp(c, 10));
        textInput.setMinLines(1); textInput.setMaxLines(4);
        textInput.setImeOptions(EditorInfo.IME_ACTION_SEND);
        textInput.setOnEditorActionListener((v, a, e) -> {
            if (a == EditorInfo.IME_ACTION_SEND) { submitTyped(); return true; }
            return false;
        });
        pill.addView(textInput, new LinearLayout.LayoutParams(0, -2, 1));
        row.addView(pill, new LinearLayout.LayoutParams(0, -2, 1));

        sendButton = Ui.icon(c, R.drawable.ic_send, "Send");
        sendButton.setImageTintList(android.content.res.ColorStateList.valueOf(Color.WHITE));
        sendButton.setBackground(Ui.circle(Ui.BLUE));
        sendButton.setOnClickListener(v -> submitTyped());
        LinearLayout.LayoutParams sp = new LinearLayout.LayoutParams(dp(c, 48), dp(c, 48));
        sp.setMargins(dp(c, 8), 0, 0, 0);
        row.addView(sendButton, sp);
        return row;
    }

    private View buildButtonRow(Context c) {
        LinearLayout row = new LinearLayout(c);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(c, 16), dp(c, 2), dp(c, 16), dp(c, 2));

        stopButton = Ui.text(c, "Stop", 14, Color.WHITE, true);
        stopButton.setGravity(Gravity.CENTER);
        stopButton.setBackground(Ui.press(c, Ui.RED, Ui.RED, 12));
        stopButton.setContentDescription("Stop Hermes");
        stopButton.setVisibility(View.GONE);
        stopButton.setOnClickListener(v -> { if (state == AssistantState.State.SPEAKING) stopSpeaking(); else if (currentRun != null) runs.stop(currentRun); });
        Ui.press(stopButton);
        LinearLayout.LayoutParams stp = new LinearLayout.LayoutParams(0, dp(c, 48), 1);
        stp.setMarginEnd(dp(c, 8));
        row.addView(stopButton, stp);

        openInAppButton = Ui.text(c, "Open in Hermes", 13, Ui.SUB, true);
        openInAppButton.setGravity(Gravity.CENTER);
        openInAppButton.setOnClickListener(v -> openInApp());
        Ui.press(openInAppButton);
        row.addView(openInAppButton, new LinearLayout.LayoutParams(0, dp(c, 48), 1));
        return row;
    }

    private int dp(Context c, int v) { return Ui.dp(c, v); }

    // ── state, label, transcript, errors ────────────────────────────────────────────────────────

    /** Single place the overlay changes state: orb, label (180 ms crossfade), hint, chips. */
    private void setState(AssistantState.State s, String hint) {
        boolean changed = s != state;
        state = s;
        if (s != AssistantState.State.ERROR) { errorRow.setVisibility(View.GONE); retryAction = null; }
        micButton.state(s);
        String label = AssistantState.label(s, step);
        if (!label.contentEquals(statusLine.getText())) {
            if (Ui.motionEnabled() && statusLine.isAttachedToWindow()) {
                statusLine.animate().cancel();
                statusLine.animate().alpha(0f).setDuration(90).withEndAction(() -> {
                    statusLine.setText(AssistantState.label(state, step));
                    statusLine.animate().alpha(1f).setDuration(90).start();
                }).start();
            } else { statusLine.setText(label); statusLine.setAlpha(1f); }
        }
        hintLine.setText(hint == null ? "" : hint);
        hintLine.setVisibility(hint == null || hint.isEmpty() ? View.GONE : View.VISIBLE);
        // While Hermes talks, the Stop button stops the voice (tapping the orb also interrupts and listens).
        if (s == AssistantState.State.SPEAKING) { stopButton.setText("Stop talking"); stopButton.setVisibility(View.VISIBLE); }
        else if ("Stop talking".contentEquals(stopButton.getText())) {
            stopButton.setText("Stop");
            if (currentRun == null || !currentRun.active()) stopButton.setVisibility(View.GONE);
        }
        if (s == AssistantState.State.SPEAKING) startBargeWatch(); else stopBargeWatch();
        boolean chips = AssistantState.showSuggestions(s, hasReply);
        if (chips && suggestionScroll.getVisibility() != View.VISIBLE) { suggestionScroll.setVisibility(View.VISIBLE); if (changed) Ui.fadeIn(suggestionScroll); }
        else if (!chips) suggestionScroll.setVisibility(View.GONE);
    }

    private void idle(String hint) { setState(AssistantState.State.IDLE, hint); }

    // A spoken request waits a moment on screen before it goes out, so a misheard one can be fixed.
    private Runnable pendingSend; private String pendingText;

    static boolean isHangUp(String text) {
        switch (normalizeStopCommand(text)) {
            case "hang up": case "end call": case "end the call": case "goodbye": case "bye": case "bye bye": case "that is all": case "thats all":
            case "\u0437\u0430\u043a\u0430\u0447\u0438": case "\u0447\u0430\u0432": case "\u0434\u043e\u0432\u0438\u0436\u0434\u0430\u043d\u0435": return true;
            default: return false;
        }
    }

    private void sendAfterPause(String text) {
        cancelPendingSend();
        if (SettingsStore.callActive && isHangUp(text)) { setTranscript(text); speak("Bye."); main.postDelayed(this::hide, 1500); return; }
        if (localCommand(text) != null) { submit(text, true); return; }
        setTranscript(text);
        setState(AssistantState.State.TRANSCRIBING, store.voiceSendDelayMs() > 0 ? "Sending\u2026 tap the text to edit" : null);
        final int token = invocationToken, take = takeToken;
        pendingText = text;
        pendingSend = () -> { pendingSend = null; pendingText = null; if (token == invocationToken && take == takeToken && shown) submit(text, true); };
        main.postDelayed(pendingSend, store.voiceSendDelayMs());
    }

    private void cancelPendingSend() {
        if (pendingSend != null) main.removeCallbacks(pendingSend);
        pendingSend = null;
    }

    private void editPendingSend() {
        if (pendingSend == null || pendingText == null) return;
        String text = pendingText;
        cancelPendingSend(); pendingText = null;
        setTranscript("");
        textInput.setText(text); textInput.setSelection(text.length()); textInput.requestFocus();
        android.view.inputmethod.InputMethodManager imm = (android.view.inputmethod.InputMethodManager) getContext().getSystemService(Context.INPUT_METHOD_SERVICE);
        if (imm != null) imm.showSoftInput(textInput, 0);
        idle("Fix it, then send");
    }

    private void stopSpeaking() {
        if (currentRun != null) currentRun.muted = true;   // the reply may still be streaming: don't start talking again
        if (speaker != null) speaker.stop();
        idle(null);
    }

    /** Human-first problem with an optional retry chip. */
    private void showError(String raw, Runnable retry) {
        setState(AssistantState.State.ERROR, null);
        errorText.setText(AssistantState.humanError(raw));
        retryAction = retry;
        errorRetry.setVisibility(retry == null ? View.GONE : View.VISIBLE);
        if (errorRow.getVisibility() != View.VISIBLE) { errorRow.setVisibility(View.VISIBLE); Ui.riseIn(errorRow); }
    }

    private void showNotice(String message, Runnable retry) {
        showError(message, retry);
        errorText.setText(message);
    }

    private void retryLast() {
        if (retryText != null) submit(retryText, lastVoice); else startListening(false);
    }

    /** User words fade in one at a time; words already shown keep full alpha as partial text grows. */
    private void setTranscript(String text) {
        if (wordAnim != null) wordAnim.cancel();
        if (text == null || text.isEmpty()) { transcriptLine.setText(""); transcriptLine.setVisibility(View.GONE); transcriptWords.clear(); transcriptShown = ""; return; }
        transcriptLine.setVisibility(View.VISIBLE);
        boolean grow = !transcriptShown.isEmpty() && text.startsWith(transcriptShown);
        int from = grow ? transcriptWords.size() : 0;
        if (!grow) transcriptWords.clear();
        android.text.SpannableString sp = new android.text.SpannableString(text);
        int i = 0, n = text.length(), idx = 0;
        while (i < n) {
            while (i < n && Character.isWhitespace(text.charAt(i))) i++;
            int st = i;
            while (i < n && !Character.isWhitespace(text.charAt(i))) i++;
            if (i > st) {
                FadeSpan f;
                if (idx < transcriptWords.size()) f = transcriptWords.get(idx);
                else { f = new FadeSpan(); f.alpha = Ui.motionEnabled() ? 0f : 1f; transcriptWords.add(f); }
                sp.setSpan(f, st, i, android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                idx++;
            }
        }
        transcriptShown = text;
        transcriptLine.setText(sp);
        final int total = transcriptWords.size();
        if (!Ui.motionEnabled() || from >= total) return;
        final int first = from;
        wordAnim = android.animation.ValueAnimator.ofFloat(0f, total - first);
        wordAnim.setDuration(Math.min(900, 70L * (total - first) + 140));
        wordAnim.setInterpolator(new android.view.animation.LinearInterpolator());
        wordAnim.addUpdateListener(a -> {
            float v = (float) a.getAnimatedValue();
            for (int k = first; k < total; k++) transcriptWords.get(k).alpha = Math.max(0f, Math.min(1f, v - (k - first) + 1f));
            transcriptLine.invalidate();
        });
        wordAnim.start();
    }

    // ── lifecycle ───────────────────────────────────────────────────────────────────────────────

    @Override public void onShow(Bundle args, int flags) {
        super.onShow(args, flags);
        accessView.setVisibility("Full access".equals(store.actionMode()) ? View.VISIBLE : View.GONE);
        final int token = ++invocationToken;
        if (recorder != null) { recorder.cancel(); recorder = null; }
        listening = false;
        if (!shown) { shown = true; WakeWordService.suspendForOverlay(); }
        String selectedProfile = store.profileId();
        if (!selectedProfile.equals(apiProfile)) { apiProfile = selectedProfile; api = Backends.create(store, apiProfile); sessionId = null; }
        boolean fromWake = args != null && args.getBoolean(ARG_FROM_WAKE);
        final boolean call = store.callMode() || (args != null && args.getBoolean(ARG_CALL));
        final String opening = args == null ? null : args.getString(ARG_OPENING);
        SettingsStore.callActive = call;
        try { android.view.Window w = getWindow() == null ? null : getWindow().getWindow(); if (w != null) { if (call) w.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON); else w.clearFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON); } } catch (Throwable ignored) {}
        try {
            screenText = null; screenshotDataUrl = null; useScreen = true;
            renderScreenChip();
            approvalRow.setVisibility(View.GONE);
            toolLine.setVisibility(View.GONE);
            replyScroll.setVisibility(View.GONE);
            setTranscript("");
            stopButton.setVisibility(View.GONE);
            step = 0; lastTool = ""; hasReply = false; replyShownOnce = false; smoothedLevel = 0;
            slideIn();
            idle("Tap the mic, or type below");
            takeInFlight = false;
            ++takeToken;
            runs.addObserver(this);
            boolean hasMic = getContext().checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED;
            permissionRow.setVisibility(hasMic ? View.GONE : View.VISIBLE);
            micButton.setVisibility(hasMic ? View.VISIBLE : View.GONE);
            micButton.setContentDescription("Listen");
            waitingForWake = fromWake;
            if (fromWake) {
                listening = true; micButton.setContentDescription("Stop listening"); setState(AssistantState.State.LISTENING, null);
                main.postDelayed(() -> {
                    if (token != invocationToken || !shown || !waitingForWake) return;
                    waitingForWake = false;
                    listening = false;
                    WakeWordService.abortCapture();
                    WakeWordService.detachCapture(wakeCapture);
                    micButton.setContentDescription("Listen");
                    idle("Tap the mic, or type below");
                }, 35_000);
                wakeCapture = new WakeWordService.Capture() {
                    public void onLevel(float level) { if (token == invocationToken && shown && listening && waitingForWake) micButton.level(level); }
                    public void onCaptured(byte[] pcm, boolean heardSpeech) {
                        if (token == invocationToken && shown && waitingForWake) {
                            waitingForWake = false;
                            handleTake(pcm, heardSpeech, true);
                        }
                    }
                };
                WakeWordService.attachCapture(wakeCapture);
            }
            resolveSession(() -> {
                RunManager.LiveRun active = runs.active(apiProfile, sessionId);
                if (active != null) { attach(active); return; }
                if (fromWake || submissionToken == token) return;
                if (call && opening != null && !opening.trim().isEmpty()) {
                    // Hermes is calling you: say why, then listen (conversation mode is on for the call).
                    replyScroll.setVisibility(View.VISIBLE); replyText.setText(opening);
                    speak(Markdown.speakable(opening));
                    return;
                }
                if (hasMic) startListening(false);
                else idle("Microphone access needed");
            });
        } catch (Throwable t) { showError(t.getMessage(), null); }
    }

    @Override public void onHide() {
        super.onHide();
        cancelPendingSend();
        ++invocationToken;
        waitingForWake = false;
        WakeWordService.detachCapture(wakeCapture);
        if (shown) { shown = false; WakeWordService.resumeForOverlay(); }
        stopListening();
        main.removeCallbacks(blink);
        if (wordAnim != null) wordAnim.cancel();
        if (card != null) card.animate().cancel();
        if (recorder != null) { recorder.cancel(); recorder = null; }
        stopBargeWatch();
        SettingsStore.callActive = false;
        if (speaker != null) { speaker.shutdown(); speaker = null; }
        runs.removeObserver(this);
        if (sessionId != null && runs.isVisible(apiProfile, sessionId)) runs.clearVisibleSession(apiProfile, sessionId);
        currentRun = null;
    }

    @Override public void onDestroy() {
        super.onDestroy();
        ++invocationToken;
        bg.shutdownNow();
    }

    // ── screen context ──────────────────────────────────────────────────────────────────────────

    @Override public void onHandleAssist(Bundle data, AssistStructure structure, AssistContent content) {
        super.onHandleAssist(data, structure, content);
        if (!store.useScreenContext() || structure == null) return;
        try {
            // Our own chat on screen is the conversation itself; sending it back derails every turn.
            android.content.ComponentName from = structure.getActivityComponent();
            if (from != null && getContext().getPackageName().equals(from.getPackageName())) return;
            String extracted = assistHeader(from, content) + extractText(structure);
            main.post(() -> { screenText = extracted; renderScreenChip(); });
        } catch (Throwable ignored) { }
    }

    @Override public void onHandleScreenshot(Bitmap screenshot) {
        super.onHandleScreenshot(screenshot);
        if (!store.useScreenContext() || screenshot == null) return;
        bg.submit(() -> {
            try {
                int w = screenshot.getWidth(), h = screenshot.getHeight();
                int max = Math.max(w, h);
                Bitmap scaled = max > 1280 ? Bitmap.createScaledBitmap(screenshot, Math.round(w * 1280f / max), Math.round(h * 1280f / max), true) : screenshot;
                ByteArrayOutputStream out = new ByteArrayOutputStream();
                scaled.compress(Bitmap.CompressFormat.JPEG, 80, out);
                final String url = "data:image/jpeg;base64," + Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP);
                main.post(() -> { screenshotDataUrl = url; renderScreenChip(); });
            } catch (Throwable ignored) { }
        });
    }

    /** What the foreground app itself tells assistants (Google Assistant reads the same): app, page URL, schema.org data. */
    static String assistHeader(android.content.ComponentName from, AssistContent content) {
        StringBuilder b = new StringBuilder();
        if (from != null) b.append("App: ").append(from.getPackageName()).append('\n');
        if (content != null) {
            if (content.getWebUri() != null) b.append("Page: ").append(content.getWebUri()).append('\n');
            String data = content.getStructuredData();
            if (data != null && !data.isEmpty()) b.append("Structured data: ").append(data, 0, Math.min(1500, data.length())).append('\n');
        }
        return b.length() == 0 ? "" : b.append('\n').toString();
    }

    private String extractText(AssistStructure structure) {
        StringBuilder sb = new StringBuilder();
        int windows = structure.getWindowNodeCount();
        for (int i = 0; i < windows && sb.length() < 6000; i++) {
            AssistStructure.WindowNode w = structure.getWindowNodeAt(i);
            AssistStructure.ViewNode root = w == null ? null : w.getRootViewNode();
            if (root != null) collectText(root, sb);
        }
        return sb.length() > 6000 ? sb.substring(0, 6000) : sb.toString();
    }

    private void collectText(AssistStructure.ViewNode node, StringBuilder sb) {
        if (node == null || sb.length() >= 6000) return;
        CharSequence t = node.getText();
        if (t != null && t.length() > 0) sb.append(t).append('\n');
        CharSequence cd = node.getContentDescription();
        if (cd != null && cd.length() > 0 && !cd.equals(t)) sb.append(cd).append('\n');
        int n = node.getChildCount();
        for (int i = 0; i < n && sb.length() < 6000; i++) collectText(node.getChildAt(i), sb);
    }

    private void renderScreenChip() {
        boolean has = (screenText != null && !screenText.trim().isEmpty()) || screenshotDataUrl != null;
        useScreenChip.setVisibility(has ? View.VISIBLE : View.GONE);
        useScreenChip.setBackground(Ui.stroke(getContext(), useScreen ? 0x225a9cf4 : Color.TRANSPARENT, Ui.BLUE, 99));
        useScreenChip.setText(useScreen ? "Use screen ✓" : "Use screen");
        useScreenChip.setContentDescription("Use screen, " + (useScreen ? "On" : "Off"));
    }

    // ── session resolution ──────────────────────────────────────────────────────────────────────

    private boolean sessionResolving;
    private final List<Runnable> sessionWaiters = new ArrayList<>();
    private void resolveSession(Runnable then) {
        if (sessionId != null) { then.run(); return; }
        final int token = invocationToken;
        sessionWaiters.add(() -> {
            if (token != invocationToken) return;
            if (sessionId != null) then.run();
        });
        if (sessionResolving) return;
        sessionResolving = true;
        bg.submit(() -> {
            String sid; String stitle = "Hermes", error = null;
            try {
                if (store.assistantOwnSession()) {
                    String cached = store.assistantSessionId();
                    if (cached != null && !cached.isEmpty()) { sid = cached; stitle = "Assistant"; }
                    else {
                        HermesApi.SessionInfo s;
                        try { s = api.ensureSession("Assistant"); }
                        catch (Exception first) { s = api.ensureSession("Assistant"); }
                        sid = s.id; stitle = s.displayTitle(); store.assistantSessionId(sid);
                    }
                } else {
                    sid = store.sessionId(); stitle = "Hermes";
                }
                // The assistant's own conversation runs on a fast model (the server default can be slow);
                // checked once per session + choice, best effort.
                String pinKey = sid + "|" + (store.agentModel().isEmpty() ? "default:" + PhoneAgent.DEFAULT_AGENT_MODEL : store.agentModel());
                if (store.assistantOwnSession() && sid != null && !pinKey.equals(store.assistantModelPinned())) {
                    try { PhoneAgent.pinModel(api, sid, store.agentModel()); store.assistantModelPinned(pinKey); }
                    catch (Exception ignored) { /* keep the current model */ }
                }
            } catch (Throwable t) {
                String stored = store.assistantOwnSession() ? store.assistantSessionId() : store.sessionId();
                sid = stored == null || stored.isEmpty() ? null : stored;
                stitle = "Hermes";
                error = t.getMessage() == null ? t.toString() : t.getMessage();
            }
            final String fsid = sid, ftitle = stitle, failure = error;
            main.post(() -> {
                if (token == invocationToken) {
                    sessionId = fsid; sessionTitle = ftitle;
                    if (fsid == null) showError(failure, () -> { resolveSession(() -> { }); idle(null); });
                }
                sessionResolving = false;
                List<Runnable> waiters = new ArrayList<>(sessionWaiters);
                sessionWaiters.clear();
                for (Runnable waiter : waiters) waiter.run();
            });
        });
    }

    // ── speech recognition ──────────────────────────────────────────────────────────────────────

    /** Record with end-of-speech detection and use the selected recognizer. */
    /** {@code followUp}: opened by conversation mode after a reply, where silence just means "done". */
    private void startListening(boolean followUp) {
        if (!shown) return;
        cancelPendingSend();
        this.followUp = followUp;
        if (getContext().checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            permissionRow.setVisibility(View.VISIBLE); micButton.setVisibility(View.GONE);
            return;
        }
        if (currentRun != null && currentRun.active()) return;
        if (speaker != null) speaker.stop();
        // running() rather than != null: a stopped recorder whose callback never comes must not disable the mic;
        // stale callbacks from it are ignored by the activeRecorder checks below.
        if (recorder != null && recorder.running()) return;
        ++takeToken;
        takeInFlight = false;
        listening = true;
        micButton.setContentDescription("Stop listening");
        setTranscript("");
        setState(AssistantState.State.LISTENING, null);
        Ui.tick(micButton);
        final VoiceRecorder activeRecorder = new VoiceRecorder(true, store.assistantPauseMs(), 60_000);
        if (bargePreroll != null) { activeRecorder.withPreroll(bargePreroll, bargeNoise); bargePreroll = null; }
        recorder = activeRecorder;
        final int token = invocationToken;
        final boolean[] heardWords = {false};
        final LiveWhisper live = SpeechRecognition.live(getContext(), store, t -> main.post(() -> { if (token == invocationToken && shown && recorder == activeRecorder && listening) { heardWords[0] = true; setTranscript(t); } }), store.assistantMayEndTake() ? activeRecorder::stop : null);
        recorder.start(new VoiceRecorder.Listener() {
            public void onAudio(byte[] pcm, int length) { if (live != null) live.feed(pcm, length); }
            public void onLevel(float level) { main.post(() -> { if (token == invocationToken && shown && recorder == activeRecorder) { micButton.level(smoothedLevel = AssistantState.smooth(smoothedLevel, level)); if (!heardWords[0]) { String r = SpeechRecognition.route(live, store.speechRecognition(), OnDeviceModel.installed(getContext())); if (!r.equals(transcriptShown)) setTranscript(r); } } }); }
            public void onError(String message) { main.post(() -> { if (token != invocationToken || !shown || recorder != activeRecorder) return; recorder = null; listening = false; micButton.setContentDescription("Listen"); showError("Microphone problem · " + message, () -> startListening(false)); }); }
            public void onDone(byte[] pcm, boolean heardSpeech) { main.post(() -> { if (token == invocationToken && shown && recorder == activeRecorder) { recorder = null; takeLive = heardSpeech ? live : null; if (!heardSpeech && live != null) live.abort(); handleTake(pcm, heardSpeech, false); } else if (live != null) live.abort(); }); }
        });
    }

    private boolean shown, followUp;

    /** Open the overlay as a call: it keeps listening after every answer and keeps the screen awake. */
    static final String ARG_CALL = "call", ARG_OPENING = "opening";

    /** The live Whisper stream of the take handleTake is about to transcribe (mic button path only). */
    private LiveWhisper takeLive;

    /** The command the wake word recorded right after "Hermes". */
    private WakeWordService.Capture wakeCapture;

    /** Main thread: a finished recording, from the mic button, conversation mode or the wake word. */
    private void handleTake(byte[] pcm, boolean heardSpeech, boolean fromWake) {
        if (takeInFlight) return;
        takeInFlight = true;
        final int token = invocationToken;
        final int take = takeToken;
        listening = false;
        micButton.setContentDescription("Listen");
        Ui.tick(micButton);
        if (heardSpeech) setState(AssistantState.State.TRANSCRIBING, null);
        else if (SettingsStore.callActive && !fromWake) { startListening(true); }
        else if (followUp && !fromWake) idle("Tap the mic to keep talking");
        else showNotice("I didn't catch that. Try again, or type below.", () -> startListening(false));
        if (!heardSpeech) return;
        final LiveWhisper live = fromWake ? null : takeLive;
        takeLive = null;
        new Thread(() -> {
            try {
                String raw = SpeechRecognition.finish(getContext(), store, live, pcm);
                String text = fromWake ? WakeWordService.stripWake(raw) : raw.trim();
                main.post(() -> {
                    if (token != invocationToken || take != takeToken || !shown) return;
                    if (WakeWordService.junkTranscript(text)) {
                        if (fromWake && !raw.trim().isEmpty() && text.isEmpty()) startListening(false);
                        else showNotice("I didn't catch that. Try again, or type below.", () -> startListening(false));
                    }
                    else if (!text.isEmpty()) sendAfterPause(text);
                    // Only "Hermes" made it into the take: the command is still coming, so listen for it.
                    else if (fromWake && !raw.trim().isEmpty()) startListening(false);
                    else showNotice("I couldn't make out any words. Try again a little closer to the mic.", () -> startListening(false));
                });
            } catch (Exception e) {
                main.post(() -> { if (token == invocationToken && take == takeToken && shown) showNotice("Speech recognition failed. Try again, or type below.", () -> startListening(false)); });
            }
        }, "whisper").start();
    }

    private void stopListening() {
        if (listening) { if (shown) Ui.tick(micButton); idle("Tap the mic, or type below"); }
        listening = false;
        waitingForWake = false;
        micButton.setContentDescription("Listen");
        WakeWordService.abortCapture();
        if (recorder != null) recorder.stop();
    }

    private void submitTyped() {
        String text = textInput.getText().toString().trim();
        if (text.isEmpty()) return;
        textInput.setText("");
        submit(text, false);
    }

    // ── sending ─────────────────────────────────────────────────────────────────────────────────

    private String lastText;
    private int agentRound;
    private boolean lastVoice;

    private void submit(String text, boolean voice) { submit(text, voice, false); }

    private void submit(String text, boolean voice, boolean automatic) {
        cancelPendingSend();
        if (!shouldStopSpeakerForSubmit(text)) return;
        if (speaker != null && !automatic) speaker.stop();
        ++takeToken;
        if (recorder != null) { recorder.cancel(); recorder = null; }
        PhoneAgent agent = PhoneAgent.current();
        if (!automatic && agent != null && isAgentStopCommand(text)) {
            stopListening();
            agent.stop();
            setTranscript("Stopped");
            idle(null);
            return;
        }
        if (!automatic) agentRound = 0;
        if (!automatic && runLocally(text, voice)) return;
        submissionToken = invocationToken;
        lastText = text; lastVoice = voice;
        if (!automatic) { retryText = text; step = 0; lastTool = ""; }
        stopListening();
        setTranscript(automatic ? "Hermes is continuing…" : text);
        setState(AssistantState.State.THINKING, null);
        resolveSession(() -> {
            List<String> images = (!automatic && useScreen && screenshotDataUrl != null) ? java.util.Collections.singletonList(screenshotDataUrl) : null;
            String screenForContext = !automatic && useScreen ? screenText : null;
            String screenBlock = DeviceContext.screenBlock(screenForContext == null ? null : screenForContext.substring(0, Math.min(6000, screenForContext.length())));
            String userText = text + (screenBlock.isEmpty() ? "" : "\n\n" + screenBlock);
            Object payload = ChatActivity.payload(userText, images);
            String systemMessage = DeviceContext.systemMessage(getContext(), store, voice);
            RunManager.LiveRun run = runs.start(api, apiProfile, sessionId, sessionTitle, payload, systemMessage, text, false);
            if (run == null) { showNotice("Hermes is already working. Give it a moment.", () -> submit(text, voice, automatic)); return; }
            run.voice = voice;
            attach(run);
        });
    }

    /**
     * Google-Assistant-style fast path: a plain phone command ("timer for 5 minutes", "flashlight on") runs on
     * the phone at once and is confirmed aloud, without waiting seconds for the model. Commands that need a
     * confirmation in the current action mode still go to Hermes.
     */
    private boolean runLocally(String text, boolean voice) {
        DeviceActions.Action local = localCommand(text);
        if (local == null) return false;
        stopListening();
        setTranscript(text);
        List<DeviceActionRunner.Outcome> results = DeviceActionRunner.runAll(getContext(), java.util.Collections.singletonList(local), null);
        String said = LocalCommands.confirmation(local, results.get(0));
        idle(said);
        if (voice) speak(said);
        return true;
    }

    private DeviceActions.Action localCommand(String text) {
        if (!store.deviceActions() || store.agentPaused() || !store.localCommands()) return null;
        DeviceActions.Action local = LocalCommands.parse(text);
        return local == null || DeviceActions.needsConfirmation(local, store) ? null : local;
    }

    static boolean shouldStopSpeakerForSubmit(String text) { return text != null && !text.trim().isEmpty(); }
    static boolean shouldSpeakReply(boolean voice, boolean spoken, boolean enabled, String error) {
        return voice && !spoken && enabled && error == null;
    }
    static boolean shouldListenAfterRun(boolean conversation, boolean voice, String error) {
        return conversation && voice && error == null;
    }
    static boolean shouldSubmitDelayed(int expectedToken, int currentToken, boolean shown) {
        return expectedToken == currentToken && shown;
    }
    static boolean isAgentStopCommand(String text) {
        switch (normalizeStopCommand(text)) {
            case "stop": case "cancel": case "stop it": case "cancel it": case "thats enough": return true;
            default: return false;
        }
    }

    static String normalizeStopCommand(String text) {
        if (text == null) return "";
        String lower = text.toLowerCase(java.util.Locale.ROOT);
        StringBuilder words = new StringBuilder();
        for (int i = 0; i < lower.length(); i++) {
            char c = lower.charAt(i);
            if (Character.isLetterOrDigit(c)) words.append(c);
            else if (c != '\'' && c != '’' && words.length() > 0 && words.charAt(words.length() - 1) != ' ') words.append(' ');
        }
        String normalized = words.toString().trim();
        return normalized.startsWith("hermes ") ? normalized.substring(7) : normalized;
    }

    private void attach(RunManager.LiveRun run) {
        currentRun = run;
        // The overlay is showing this run, so the manager should not also notify about it.
        runs.setVisibleSession(run.profileId, run.sessionId);
        render(run);
    }

    // ── RunManager.Observer ─────────────────────────────────────────────────────────────────────

    @Override public void onRunUpdate(RunManager.LiveRun run, String kind) {
        if (currentRun == null || !run.belongsTo(currentRun.profileId, currentRun.sessionId) || run != currentRun) return;
        if (RunManager.UPDATE_ERROR.equals(kind) && resendInFreshSession(run)) return;
        if (RunManager.UPDATE_FINISHED.equals(kind) || RunManager.UPDATE_ERROR.equals(kind)) finishRun(run);
        else render(run);
    }

    /** The saved assistant session was deleted (drawer, another client): start a fresh one and send again, once. */
    private boolean resendInFreshSession(RunManager.LiveRun run) {
        if (lastText == null || run.error == null || !run.error.contains("Session not found") || !run.sessionId.equals(store.assistantSessionId())) return false;
        store.assistantSessionId(null);
        runs.forget(run.profileId, run.sessionId);
        currentRun = null; sessionId = null;
        submit(lastText, lastVoice);
        lastText = null; // submit() set it again; a second 404 should show, not loop
        return true;
    }

    private void render(RunManager.LiveRun run) {
        String text;
        synchronized (run) { text = run.text.toString(); }
        CharSequence shown = MarkdownView.compact(getContext(), text);
        hasReply = !shown.toString().trim().isEmpty();
        boolean wasHidden = replyScroll.getVisibility() != View.VISIBLE;
        replyScroll.setVisibility(hasReply ? View.VISIBLE : View.GONE);
        if (hasReply && wasHidden && !replyShownOnce) { replyShownOnce = true; Ui.riseIn(replyScroll); }
        boolean streaming = run.active();
        replyText.setAccessibilityLiveRegion(streaming ? View.ACCESSIBILITY_LIVE_REGION_NONE : View.ACCESSIBILITY_LIVE_REGION_POLITE);
        if (streaming && hasReply) {
            android.text.SpannableStringBuilder sb = new android.text.SpannableStringBuilder(shown).append('\u258D');
            sb.setSpan(cursor, shown.length(), sb.length(), android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            replyText.setText(sb);
            if (Ui.motionEnabled()) { main.removeCallbacks(blink); main.postDelayed(blink, 530); } else cursor.alpha = 1f;
        } else { main.removeCallbacks(blink); replyText.setText(shown); }
        if (!(replyText.getMovementMethod() instanceof android.text.method.LinkMovementMethod)) replyText.setMovementMethod(android.text.method.LinkMovementMethod.getInstance());
        toolLine.setVisibility(TextUtils.isEmpty(run.lastToolLine) || run.approval != null ? View.GONE : View.VISIBLE);
        toolLine.setText(run.lastToolLine);
        stopButton.setVisibility(run.active() ? View.VISIBLE : View.GONE);
        // Keep the orb on screen while Hermes works (its thinking animation is the feedback); just not tappable.
        micButton.setVisibility(getContext().checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED ? View.VISIBLE : View.GONE);
        micButton.setEnabled(!run.active() || (run.spokenChars > 0 && !run.muted));
        if (run.active() && !TextUtils.isEmpty(run.lastToolLine) && !run.lastToolLine.equals(lastTool)) { lastTool = run.lastToolLine; step++; }
        if (run.error != null) showError(run.error, this::retryLast);
        else if (run.approval != null) setState(AssistantState.State.APPROVAL, null);
        else if (run.active() && run.spokenChars > 0 && !run.muted) { if (state != AssistantState.State.SPEAKING) setState(AssistantState.State.SPEAKING, null); }
        else if (run.active()) setState(run.lastToolLine.isEmpty() ? AssistantState.State.THINKING : AssistantState.State.WORKING, null);
        else if (state != AssistantState.State.SPEAKING) idle(null);
        renderApproval(run);
        capReplyHeight();
        if (run.active()) speakStreaming(run);
    }

    private void renderApproval(RunManager.LiveRun run) {
        approvalRow.removeAllViews();
        View scroller = (View) approvalRow.getParent();
        if (!api.capabilitiesForUi().approvals || run.approval == null) { scroller.setVisibility(View.GONE); return; }
        scroller.setVisibility(View.VISIBLE);
        for (String choice : run.approval.choices) {
            boolean deny = "deny".equals(choice);
            TextView b = Ui.text(getContext(), HermesApi.Approval.label(choice), 13, deny ? Ui.RED : Ui.BLUE, true);
            b.setPadding(dp(getContext(), 14), dp(getContext(), 8), dp(getContext(), 14), dp(getContext(), 8));
            b.setBackground(Ui.stroke(getContext(), Color.TRANSPARENT, deny ? Ui.RED : Ui.BLUE, 99));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, -2); lp.setMargins(dp(getContext(), 8), 0, 0, 0);
            b.setOnClickListener(v -> runs.approve(run, choice, r -> { if (run.active()) render(run); }));
            approvalRow.addView(b, lp);
        }
    }

    /** The reply area is capped to roughly half the screen and scrolls beyond that. */
    private void capReplyHeight() {
        replyScroll.post(() -> {
            int max = getContext().getResources().getDisplayMetrics().heightPixels / 2;
            ViewGroup.LayoutParams lp = replyScroll.getLayoutParams();
            int wanted = replyText.getHeight() > max ? max : ViewGroup.LayoutParams.WRAP_CONTENT;
            if (lp.height != wanted) { lp.height = wanted; replyScroll.setLayoutParams(lp); }
        });
    }

    private void finishRun(RunManager.LiveRun run) {
        render(run);
        if (PhoneAgent.isRunning(run.profileId, run.sessionId)) { if (currentRun == run) currentRun = null; runs.forget(run.profileId, run.sessionId); return; }
        boolean screenConfirmation = false;
        String followUp = null;
        if (!run.actionsHandled) {
            if (PhoneAgent.shouldStart(run)) {
                run.actionsHandled = true;
                PhoneAgent.start(getContext(), run);
                if (currentRun == run) currentRun = null;
                runs.forget(run.profileId, run.sessionId);
                hide(); // don't cover the app while the agent works
                return;
            }
            run.actionsHandled = true;
            if (!run.pendingActions.isEmpty()) {
                List<DeviceActions.Action> screenSteps = new ArrayList<>();
                List<DeviceActions.Action> otherActions = new ArrayList<>();
                for (DeviceActions.Action action : run.pendingActions)
                    (DeviceActions.isScreenStep(action.type) ? screenSteps : otherActions).add(action);
                List<DeviceActionRunner.Outcome> results = DeviceActionRunner.runAll(getContext(), otherActions, run.sessionId);
                followUp = DeviceActionRunner.resultsMessage(results);
                String summary = DeviceActionRunner.summary(results);
                if (!screenSteps.isEmpty() && !DeviceActions.needsConfirmation(screenSteps.get(0), store)) {
                    results.addAll(DeviceActionRunner.runAll(getContext(), screenSteps, run.sessionId));
                    summary = DeviceActionRunner.summary(results);
                } else if (!screenSteps.isEmpty()) {
                    if (HermesAccessibilityService.enabled()) {
                        Intent confirmation = ScreenStepConfirmActivity.intent(getContext(), screenSteps, run.sessionId,
                                DeviceActionRunner.screenStepApp(results));
                        try {
                            try { startAssistantActivity(confirmation); }
                            catch (Throwable fallback) { getContext().startActivity(confirmation); }
                            screenConfirmation = true;
                            summary += (summary.isEmpty() ? "" : "\n") + "Screen steps awaiting confirmation";
                        } catch (Throwable unavailable) {
                            results.addAll(DeviceActionRunner.runAll(getContext(), screenSteps, run.sessionId));
                            summary = DeviceActionRunner.summary(results);
                        }
                    } else {
                        results.addAll(DeviceActionRunner.runAll(getContext(), screenSteps, run.sessionId));
                        summary = DeviceActionRunner.summary(results);
                    }
                }
                if (!summary.isEmpty()) toolLine.setText(summary);
            }
        }
        if (screenConfirmation) {
            if (currentRun == run) currentRun = null;
            runs.forget(run.profileId, run.sessionId);
            hide();
            return;
        }
        if (run.error == null && followUp != null && agentRound < store.agentRounds()) {
            agentRound++;
            if (currentRun == run) currentRun = null;
            runs.forget(run.profileId, run.sessionId);
            String next = followUp;
            final int token = invocationToken;
            main.postDelayed(() -> { if (shouldSubmitDelayed(token, invocationToken, shown)) submit(next, run.voice, true); }, 150);
            return;
        }
        boolean shouldSpeak = shouldSpeakReply(run.voice, run.spoken, store.speakReplies(), run.error);
        if (run.spokenChars > 0 && run.error == null) { finishStreamedSpeech(run); }
        else if (shouldSpeak) { run.spoken = true; speak(Markdown.speakable(run.finalText())); }
        else if (shouldListenAfterRun(store.conversationMode(), run.voice, run.error)) startListening(true);
        if (currentRun == run) currentRun = null;
        runs.forget(run.profileId, run.sessionId);
    }

    // ── text to speech ──────────────────────────────────────────────────────────────────────────

    private Speaker speaker() {
        if (speaker == null) {
            final int token = invocationToken;
            speaker = new Speaker(getContext(), store);
            speaker.onDone(id -> main.post(() -> {
                if (token != invocationToken || !shown) return;
                if ("part".equals(id)) return;
                if (state == AssistantState.State.SPEAKING) idle(null);
                if ("converse".equals(id) && store.conversationMode()) startListening(true);
            }));
        }
        return speaker;
    }

    private void speak(String text) {
        if (text == null || text.trim().isEmpty()) {
            if (shown && store.conversationMode()) startListening(true);
            return;
        }
        setState(AssistantState.State.SPEAKING, null);
        speaker().speak(text, store.conversationMode() ? "converse" : "hermes");
    }

    /** While a spoken request is answered, say each finished sentence as it arrives instead of waiting for the whole reply. */
    private void speakStreaming(RunManager.LiveRun run) {
        if (!run.voice || run.muted || run.error != null || !store.speakReplies() || !store.streamSpeech()) return;
        String text;
        synchronized (run) { text = run.text.toString(); }
        int end = SpeechStream.readyEnd(text, run.spokenChars);
        if (end <= run.spokenChars) return;
        String piece = Markdown.speakable(text.substring(run.spokenChars, end));
        run.spokenChars = end;
        run.spoken = true;
        if (piece.isEmpty()) return;
        setState(AssistantState.State.SPEAKING, null);
        speaker().enqueue(piece, "part");
    }

    /** The reply is complete: say what is left and, once everything queued has been spoken, go back to listening. */
    private void finishStreamedSpeech(RunManager.LiveRun run) {
        if (run.muted) return;
        String full = run.finalText();
        String streamed;
        synchronized (run) { streamed = run.text.substring(0, Math.min(run.spokenChars, run.text.length())); }
        // The server's final text normally continues what streamed in; if it was rewritten, don't guess offsets (skip rather than repeat).
        String rest = full.startsWith(streamed) ? Markdown.speakable(full.substring(streamed.length())) : "";
        run.spokenChars = Math.max(run.spokenChars, full.length());
        String id = store.conversationMode() ? "converse" : "hermes";
        if (rest.isEmpty()) { speaker().marker(id); return; }
        setState(AssistantState.State.SPEAKING, null);
        speaker().enqueue(rest, id);
    }

    private BargeIn barge;
    private byte[] bargePreroll; private double bargeNoise;

    /** While Hermes talks, watch the mic for you talking over it (headphones by default, see Settings › Voice). */
    private void startBargeWatch() {
        if (barge != null && barge.running()) return;
        if (!shown || listening || !store.bargeIn()) return;
        if (!store.bargeInSpeaker() && !BargeIn.outputIsPrivate(getContext())) return;
        if (getContext().checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) return;
        final int token = invocationToken;
        barge = new BargeIn();
        barge.start((pre, noise) -> main.post(() -> {
            if (token != invocationToken || !shown || state != AssistantState.State.SPEAKING) return;
            bargePreroll = pre; bargeNoise = noise;
            interruptSpeech();
        }));
    }

    private void stopBargeWatch() { if (barge != null) { barge.stop(); barge = null; } }

    /** Talking over Hermes (orb tap or barge-in): quiet it, drop the rest of the reply, and listen. */
    private void interruptSpeech() {
        RunManager.LiveRun r = currentRun;
        if (r != null) {
            r.muted = true;
            if (r.active()) { runs.stop(r); runs.forget(r.profileId, r.sessionId); }
            currentRun = null;
        }
        if (speaker != null) speaker.stop();
        startListening(false);
    }

    // ── open in app / close ─────────────────────────────────────────────────────────────────────

    private void openInApp() {
        try {
            Intent i = new Intent(getContext(), MainActivity.class)
                    .putExtra(MainActivity.EXTRA_OPEN_CHAT, true)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
            if (sessionId != null) i.putExtra(MainActivity.EXTRA_SESSION, sessionId);
            if (Build.VERSION.SDK_INT >= 34) {
                try { startAssistantActivity(i); } catch (Throwable fallback) { getContext().startActivity(i); }
            } else {
                getContext().startActivity(i);
            }
        } catch (Throwable ignored) { }
        hide();
    }
}
