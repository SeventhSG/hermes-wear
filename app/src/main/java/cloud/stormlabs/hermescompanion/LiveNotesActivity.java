package cloud.stormlabs.hermescompanion;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Live notes: listens to a lecture or meeting, shows the transcript as it happens, a new insight card
 * every few seconds and a running outline, then exports the lot as Markdown or files it in Essentials.
 */
public final class LiveNotesActivity extends Activity {
    private static final int MIC_REQ = 411;
    private static final long SUMMARY_EVERY_S = 150, SAVE_EVERY_S = 30;
    private static final String SAVE_FILE = "live-notes-current.json";

    private SettingsStore store;
    private final Handler ui = new Handler(Looper.getMainLooper());
    private final InsightEngine engine = new InsightEngine();
    private LectureStt stt;
    private LectureLlm llm;
    private ScheduledExecutorService tickPool, summaryPool;
    private final ExecutorService io = Executors.newSingleThreadExecutor();

    private boolean listening;
    private long startedAt, elapsedBefore;
    private String title = "";
    private String startedWhen = "";
    private int shownCards;
    private boolean shownSummary;

    private TextView statusText, timerText, startButton, partialView, transcriptView, summaryView, emptyCards, notice;
    private LinearLayout cardsBox, helpers;
    private ScrollView transcriptScroll;
    private View[] panes = new View[3];
    private TextView[] tabs = new TextView[3];
    private int tab = 1;
    private boolean followTranscript = true;

    private int dp(int n) { return Ui.dp(this, n); }

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        CrashReporter.install(this);
        Ui.legacyWindowInsets(this);
        store = new SettingsStore(this);
        getWindow().setStatusBarColor(Ui.BAR); getWindow().setNavigationBarColor(Ui.BG);
        buildUi();
        loadSaved();
        refreshButtons();
    }

    // ── layout ──────────────────────────────────────────────────────────────────────────────────

    private void buildUi() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        Ui.ambient(root);

        LinearLayout bar = new LinearLayout(this);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setBackgroundColor(Ui.BAR);
        bar.setPadding(dp(2), 0, dp(8), 0);
        ImageButton back = Ui.icon(this, R.drawable.ic_back, "Back");
        back.setOnClickListener(v -> onBackPressed());
        bar.addView(back, new LinearLayout.LayoutParams(dp(48), dp(52)));
        LinearLayout names = new LinearLayout(this);
        names.setOrientation(LinearLayout.VERTICAL);
        names.setPadding(dp(6), 0, 0, 0);
        names.addView(Ui.text(this, "Live notes", 18, Ui.TEXT, true));
        statusText = Ui.text(this, "Ready", 12, Ui.SUB, false);
        names.addView(statusText);
        bar.addView(names, new LinearLayout.LayoutParams(0, -2, 1));
        timerText = Ui.text(this, "", 14, Ui.RED, true);
        timerText.setPadding(dp(8), 0, dp(8), 0);
        bar.addView(timerText, new LinearLayout.LayoutParams(-2, -2));
        TextView more = Ui.text(this, "⋯", 24, Ui.SUB, false);
        more.setGravity(Gravity.CENTER);
        more.setContentDescription("More: class details, export, save to Essentials, clear");
        more.setBackground(Ui.ripple(this, Color.TRANSPARENT, 99));
        more.setOnClickListener(v -> openMenu());
        bar.addView(more, new LinearLayout.LayoutParams(dp(46), dp(46)));
        root.addView(bar, new LinearLayout.LayoutParams(-1, dp(60)));

        notice = Ui.text(this, "", 12, Ui.AMBER, false);
        notice.setPadding(dp(14), dp(6), dp(14), dp(6));
        notice.setVisibility(View.GONE);
        root.addView(notice, new LinearLayout.LayoutParams(-1, -2));

        boolean wide = getResources().getConfiguration().screenWidthDp >= 720;
        LinearLayout strip = new LinearLayout(this);
        strip.setPadding(dp(10), dp(6), dp(10), dp(2));
        String[] names3 = {"Transcript", "Insights", "Summary"};
        for (int i = 0; i < 3; i++) {
            final int idx = i;
            TextView t = Ui.text(this, names3[i], 13, Ui.SUB, true);
            t.setGravity(Gravity.CENTER);
            t.setPadding(dp(8), dp(9), dp(8), dp(9));
            t.setOnClickListener(v -> selectTab(idx));
            tabs[i] = t;
            LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(0, -2, 1);
            p.setMargins(dp(3), 0, dp(3), 0);
            strip.addView(t, p);
        }
        if (!wide) root.addView(strip, new LinearLayout.LayoutParams(-1, -2));

        LinearLayout body = new LinearLayout(this);
        body.setOrientation(wide ? LinearLayout.HORIZONTAL : LinearLayout.VERTICAL);
        body.addView(panes[0] = buildTranscript(), paneParams(wide));
        body.addView(panes[1] = buildCards(), paneParams(wide));
        body.addView(panes[2] = buildSummary(), paneParams(wide));
        root.addView(body, new LinearLayout.LayoutParams(-1, 0, 1));

        LinearLayout dock = new LinearLayout(this);
        dock.setOrientation(LinearLayout.VERTICAL);
        dock.setBackgroundColor(Ui.BG);
        dock.setPadding(dp(14), dp(8), dp(14), dp(12));
        helpers = new LinearLayout(this);
        helpers.setVisibility(View.GONE);
        TextView missed = Ui.secondaryButton(this, "I missed that");
        missed.setOnClickListener(v -> missedThat());
        TextView ask = Ui.secondaryButton(this, "Ask about the lecture");
        ask.setOnClickListener(v -> askLecture());
        LinearLayout.LayoutParams hp = new LinearLayout.LayoutParams(0, dp(44), 1);
        hp.setMargins(0, 0, dp(4), dp(8));
        helpers.addView(missed, hp);
        LinearLayout.LayoutParams hp2 = new LinearLayout.LayoutParams(0, dp(44), 1);
        hp2.setMargins(dp(4), 0, 0, dp(8));
        helpers.addView(ask, hp2);
        dock.addView(helpers, new LinearLayout.LayoutParams(-1, -2));
        startButton = Ui.primaryButton(this, "Start listening");
        startButton.setOnClickListener(v -> { if (listening) stopListening(); else begin(); });
        dock.addView(startButton, new LinearLayout.LayoutParams(-1, dp(52)));
        root.addView(dock, new LinearLayout.LayoutParams(-1, -2));

        Nav.tabBar(this, root, Layout.Tab.LIVE);
        setContentView(root);
        Ui.fitInsets(root, root);
        if (wide) for (TextView t : tabs) t.setVisibility(View.GONE);
        else selectTab(1);
    }

    private LinearLayout.LayoutParams paneParams(boolean wide) {
        LinearLayout.LayoutParams p = wide ? new LinearLayout.LayoutParams(0, -1, 1) : new LinearLayout.LayoutParams(-1, 0, 1);
        if (wide) p.setMargins(dp(4), 0, dp(4), 0);
        return p;
    }

    private View buildTranscript() {
        transcriptScroll = new ScrollView(this);
        transcriptScroll.setClipToPadding(false);
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(16), dp(10), dp(16), dp(16));
        transcriptView = Ui.body(this, "", Ui.TEXT);
        transcriptView.setLineSpacing(0, 1.18f);
        transcriptView.setTextIsSelectable(true);
        box.addView(transcriptView);
        partialView = Ui.body(this, "", Ui.FAINT);
        partialView.setPadding(0, dp(4), 0, 0);
        box.addView(partialView);
        transcriptScroll.addView(box);
        // Scrolling up reads back; scrolling to the end resumes following.
        transcriptScroll.setOnScrollChangeListener((v, x, y, ox, oy) -> {
            View child = transcriptScroll.getChildAt(0);
            int gap = child.getHeight() - (transcriptScroll.getHeight() + y);
            followTranscript = gap < dp(60);
        });
        return transcriptScroll;
    }

    private View buildCards() {
        FrameLayout frame = new FrameLayout(this);
        ScrollView sv = new ScrollView(this);
        cardsBox = new LinearLayout(this);
        cardsBox.setOrientation(LinearLayout.VERTICAL);
        cardsBox.setPadding(dp(12), dp(10), dp(12), dp(16));
        sv.addView(cardsBox);
        frame.addView(sv, new FrameLayout.LayoutParams(-1, -1));
        emptyCards = Ui.text(this, "Insight cards appear here while the lecture is running: concepts, definitions and likely exam points.", 13, Ui.SUB, false);
        emptyCards.setGravity(Gravity.CENTER);
        emptyCards.setPadding(dp(28), dp(28), dp(28), dp(28));
        frame.addView(emptyCards, new FrameLayout.LayoutParams(-1, -1));
        return frame;
    }

    private View buildSummary() {
        ScrollView sv = new ScrollView(this);
        summaryView = Ui.body(this, "", Ui.TEXT);
        summaryView.setLineSpacing(0, 1.18f);
        summaryView.setTextIsSelectable(true);
        summaryView.setPadding(dp(16), dp(12), dp(16), dp(16));
        sv.addView(summaryView);
        return sv;
    }

    private void selectTab(int i) {
        tab = i;
        for (int k = 0; k < 3; k++) {
            panes[k].setVisibility(k == i ? View.VISIBLE : View.GONE);
            tabs[k].setTextColor(k == i ? Color.WHITE : Ui.SUB);
            tabs[k].setBackground(k == i ? Ui.round(this, Ui.BLUE, 10) : Ui.round(this, Color.TRANSPARENT, 10));
        }
    }

    // ── session ─────────────────────────────────────────────────────────────────────────────────

    private void begin() {
        if (Build.VERSION.SDK_INT >= 23 && checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO}, MIC_REQ);
            return;
        }
        if (!engine.sentences().isEmpty() && !listening) {
            Sheets.confirm(this, "Start a new session?", "The notes on screen will be cleared. Export or save them first if you want to keep them.", "Start new", true, () -> { clearAll(); begin(); });
            return;
        }
        AgentBackend backend;
        try { backend = Backends.create(store); } catch (Exception e) { notice("Connect to a Hermes server first"); return; }
        if (!(backend instanceof HermesApi)) { notice("Live notes needs a Hermes server profile"); return; }
        llm = new LectureLlm((HermesApi) backend, store.lectureModel());
        engine.context(store.lectureContext());
        startedAt = System.currentTimeMillis();
        if (startedWhen.isEmpty()) startedWhen = java.text.DateFormat.getDateTimeInstance(java.text.DateFormat.MEDIUM, java.text.DateFormat.SHORT).format(new java.util.Date());
        listening = true;
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        LectureService.start(this);
        stt = new LectureStt(store, new LectureStt.Events() {
            @Override public void onPartial(String text) { ui.post(() -> partialView.setText(text)); }
            @Override public void onFinal(String text) { engine.addSentence(text); ui.post(() -> appendTranscript(text)); }
            @Override public void onLevel(float level) { }
            @Override public void onProblem(String message) { ui.post(() -> notice(message)); }
        });
        stt.start();
        final InsightEngine.Llm model = llm;
        tickPool = Executors.newSingleThreadScheduledExecutor();
        tickPool.scheduleWithFixedDelay(() -> runTick(model), store.lectureTick(), store.lectureTick(), TimeUnit.SECONDS);
        summaryPool = Executors.newSingleThreadScheduledExecutor();
        summaryPool.scheduleWithFixedDelay(() -> runSummary(model), SUMMARY_EVERY_S, SUMMARY_EVERY_S, TimeUnit.SECONDS);
        tickPool.scheduleWithFixedDelay(this::save, SAVE_EVERY_S, SAVE_EVERY_S, TimeUnit.SECONDS);
        ui.post(clock);
        refreshButtons();
    }

    private void runTick(InsightEngine.Llm model) {
        try {
            List<InsightEngine.Card> made = engine.tick(model, System.currentTimeMillis());
            if (made != null && !made.isEmpty()) ui.post(this::showCards);
            ui.post(() -> hideNoticeIf("Cards are late"));
        } catch (Throwable t) {
            ui.post(() -> notice("Cards are late: " + (t.getMessage() == null ? "model call failed" : t.getMessage())));
        }
    }

    private void runSummary(InsightEngine.Llm model) {
        try { if (engine.summarize(model)) ui.post(this::showSummary); }
        catch (Throwable ignored) { /* the next round retries; cards keep working off the older outline */ }
    }

    private void stopListening() {
        if (!listening) return;
        listening = false;
        elapsedBefore += System.currentTimeMillis() - startedAt;
        getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        if (stt != null) stt.stop();
        if (tickPool != null) tickPool.shutdownNow();
        if (summaryPool != null) summaryPool.shutdownNow();
        LectureService.stop(this);
        partialView.setText("");
        statusText.setText("Finishing up…");
        final LectureLlm closing = llm; llm = null;
        io.submit(() -> {
            // One last outline and a last round of cards from whatever was said at the very end.
            if (closing != null) {
                try { engine.tick(closing, System.currentTimeMillis()); } catch (Throwable ignored) { }
                try { engine.summarize(closing); } catch (Throwable ignored) { }
                closing.close();
            }
            save();
            ui.post(() -> { showCards(); showSummary(); refreshButtons(); });
        });
        refreshButtons();
    }

    private final Runnable clock = new Runnable() {
        @Override public void run() {
            if (!listening) return;
            timerText.setText(formatTime(elapsedBefore + System.currentTimeMillis() - startedAt));
            ui.postDelayed(this, 1000);
        }
    };

    static String formatTime(long ms) {
        long s = ms / 1000;
        return s >= 3600 ? String.format(Locale.US, "%d:%02d:%02d", s / 3600, (s / 60) % 60, s % 60) : String.format(Locale.US, "%d:%02d", s / 60, s % 60);
    }

    private long totalSeconds() { return (elapsedBefore + (listening ? System.currentTimeMillis() - startedAt : 0)) / 1000; }

    // ── rendering ───────────────────────────────────────────────────────────────────────────────

    private void appendTranscript(String text) {
        CharSequence cur = transcriptView.getText();
        transcriptView.setText(cur.length() == 0 ? text : cur + " " + text);
        partialView.setText("");
        if (followTranscript) transcriptScroll.post(() -> transcriptScroll.fullScroll(View.FOCUS_DOWN));
    }

    private void showCards() {
        List<InsightEngine.Card> all = engine.cards();
        emptyCards.setVisibility(all.isEmpty() ? View.VISIBLE : View.GONE);
        for (int i = shownCards; i < all.size(); i++) {
            View card = card(all.get(i));
            cardsBox.addView(card, 0);
            if (Ui.motionEnabled()) Ui.riseIn(card);
        }
        shownCards = all.size();
        tabs[1].setText(shownCards == 0 ? "Insights" : "Insights · " + shownCards);
    }

    private View card(InsightEngine.Card c) {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(14), dp(11), dp(14), dp(12));
        int accent = "exam_point".equals(c.type) ? Ui.AMBER : "question".equals(c.type) || "recap".equals(c.type) ? Ui.BLUE : "example".equals(c.type) ? Ui.FAINT : Ui.SUB;
        box.setBackground(Ui.stroke(this, Ui.SURFACE, "exam_point".equals(c.type) ? 0x66e7b24c : Ui.LINE, 12));
        TextView chip = Ui.label(this, InsightEngine.label(c.type), accent);
        box.addView(chip);
        TextView t = Ui.body(this, c.text, Ui.TEXT);
        t.setPadding(0, dp(4), 0, 0);
        t.setLineSpacing(0, 1.12f);
        t.setTextIsSelectable(true);
        box.addView(t);
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(-1, -2);
        p.setMargins(0, 0, 0, dp(8));
        box.setLayoutParams(p);
        return box;
    }

    private void showSummary() {
        String s = engine.summary();
        shownSummary = !s.isEmpty();
        summaryView.setText(s.isEmpty() ? "The running outline appears here after the first couple of minutes." : s);
        summaryView.setTextColor(s.isEmpty() ? Ui.SUB : Ui.TEXT);
    }

    private void refreshButtons() {
        helpers.setVisibility(engine.sentences().isEmpty() ? View.GONE : View.VISIBLE);
        startButton.setText(listening ? "Stop" : engine.sentences().isEmpty() ? "Start listening" : "Start a new session");
        startButton.setBackground(listening ? Ui.round(this, Ui.RED, 12) : Ui.round(this, Ui.BLUE, 12));
        statusText.setText(listening ? "Listening · cards every " + store.lectureTick() + " s" : engine.sentences().isEmpty() ? "Ready" : "Stopped · " + engine.sentences().size() + " sentences");
        timerText.setText(listening ? formatTime(elapsedBefore + System.currentTimeMillis() - startedAt) : elapsedBefore > 0 ? formatTime(elapsedBefore) : "");
        if (!shownSummary) showSummary();
    }

    private void notice(String message) { notice.setText(message); notice.setVisibility(View.VISIBLE); }
    private void hideNoticeIf(String prefix) { if (notice.getText().toString().startsWith(prefix)) notice.setVisibility(View.GONE); }


    // ── ask, recap, flashcards ──────────────────────────────────────────────────────────────────

    /** One model call outside the tick loop, on its own throwaway session. */
    private void oneOff(String system, String user, java.util.function.Consumer<String> done) {
        AgentBackend backend;
        try { backend = Backends.create(store); } catch (Exception e) { notice("Connect to a Hermes server first"); return; }
        if (!(backend instanceof HermesApi)) { notice("Live notes needs a Hermes server profile"); return; }
        final LectureLlm helper = new LectureLlm((HermesApi) backend, store.lectureModel());
        notice("Thinking…");
        io.submit(() -> {
            try {
                String answer = helper.ask(system, user, true);
                ui.post(() -> { hideNoticeIf("Thinking"); done.accept(answer); });
            } catch (Throwable t) {
                ui.post(() -> notice("Couldn't ask: " + (t.getMessage() == null ? "model call failed" : t.getMessage())));
            } finally { helper.close(); }
        });
    }

    private void missedThat() {
        if (engine.sentences().isEmpty()) return;
        oneOff(InsightEngine.RECAP_SYSTEM, engine.recapUser(), answer -> {
            if (answer.isEmpty()) return;
            engine.addCard(new InsightEngine.Card("recap", answer, System.currentTimeMillis()));
            showCards();
            selectTabIfNarrow(1);
        });
    }

    private void askLecture() {
        if (engine.sentences().isEmpty()) return;
        Sheets.input(this, "Ask about the lecture", "Answers use what has been said so far", "", "e.g. what is an eigenvalue?", question ->
                oneOff(InsightEngine.ASK_SYSTEM, engine.askUser(question), answer ->
                        Sheets.info(this, question, null, new String[][]{{"Answer", answer.isEmpty() ? "No answer came back." : answer}})), "Ask");
    }

    private void flashcards() {
        if (engine.sentences().isEmpty()) { Toast.makeText(this, "Nothing recorded yet", Toast.LENGTH_SHORT).show(); return; }
        oneOff(InsightEngine.FLASH_SYSTEM, engine.flashUser(), answer -> {
            java.util.List<String[]> cards = InsightEngine.parseFlashcards(answer);
            if (cards.isEmpty()) { notice("No flashcards came back, try again"); return; }
            Intent send = new Intent(Intent.ACTION_SEND).setType("text/plain")
                    .putExtra(Intent.EXTRA_SUBJECT, "Flashcards (" + cards.size() + ")").putExtra(Intent.EXTRA_TEXT, InsightEngine.anki(cards));
            startActivity(Intent.createChooser(send, "Flashcards for Anki"));
        });
    }

    private void selectTabIfNarrow(int i) { if (tabs[i].getVisibility() == View.VISIBLE) selectTab(i); }

    // ── menu, export ────────────────────────────────────────────────────────────────────────────

    private void openMenu() {
        Sheets.actions(this, "Live notes", title.isEmpty() ? null : title,
                Sheets.Item.of(R.drawable.ic_edit, Ui.SUB, "About this class", "Course, level, language for the cards", () -> editContext()),
                Sheets.Item.of(R.drawable.ic_clock, Ui.SUB, "Seconds between cards", "Now " + store.lectureTick() + " s", () -> pickTick()),
                Sheets.Item.of(R.drawable.ic_share, Ui.SUB, "Share as Markdown", "Transcript, cards and summary", () -> share()),
                Sheets.Item.of(R.drawable.ic_copy, Ui.SUB, "Flashcards for Anki", "Study cards from this lecture, tab-separated", () -> flashcards()),
                Sheets.Item.of(R.drawable.ic_download, Ui.SUB, "Save to Essentials", "File it as a note in your inbox", () -> saveToEssentials()),
                Sheets.Item.of(R.drawable.ic_delete, Ui.RED, "Clear everything", "Start from an empty screen", () -> Sheets.confirm(this, "Clear these notes?", "The transcript, cards and summary are deleted from this phone.", "Clear", true, this::clearAll)));
    }

    private void editContext() {
        Sheets.input(this, "About this class", "Course, today's topic, level, language for cards. Example: Linear algebra, eigenvalues, first year, cards in English",
                store.lectureContext(), "Optional", v -> { store.lectureContext(v); engine.context(v); }, "Save", true);
    }

    private void pickTick() {
        Sheets.actions(this, "Seconds between cards", "Shorter is livelier and uses more model calls",
                Sheets.Item.of(R.drawable.ic_clock, Ui.SUB, "5 seconds", "", () -> setTick(5)), Sheets.Item.of(R.drawable.ic_clock, Ui.SUB, "7 seconds", "Default", () -> setTick(7)),
                Sheets.Item.of(R.drawable.ic_clock, Ui.SUB, "10 seconds", "", () -> setTick(10)), Sheets.Item.of(R.drawable.ic_clock, Ui.SUB, "15 seconds", "", () -> setTick(15)));
    }

    private void setTick(int s) {
        store.lectureTick(s);
        Toast.makeText(this, listening ? "Applies from the next session" : "Saved", Toast.LENGTH_SHORT).show();
        refreshButtons();
    }

    private String markdown() {
        String heading = title.isEmpty() ? (store.lectureContext().isEmpty() ? "Live notes" : store.lectureContext()) : title;
        return engine.markdown(heading, startedWhen.isEmpty() ? "Live notes" : startedWhen, totalSeconds());
    }

    private void share() {
        if (engine.sentences().isEmpty()) { Toast.makeText(this, "Nothing recorded yet", Toast.LENGTH_SHORT).show(); return; }
        Intent send = new Intent(Intent.ACTION_SEND).setType("text/plain")
                .putExtra(Intent.EXTRA_SUBJECT, "Live notes").putExtra(Intent.EXTRA_TEXT, markdown());
        startActivity(Intent.createChooser(send, "Share notes"));
    }

    private void saveToEssentials() {
        if (engine.sentences().isEmpty()) { Toast.makeText(this, "Nothing recorded yet", Toast.LENGTH_SHORT).show(); return; }
        String base = store.essentialsBase(), token = store.essentialsToken();
        if (base == null || base.isEmpty() || token == null || token.isEmpty()) { notice("Set up Essentials in Connection settings first"); return; }
        final String note = markdown();
        final EssentialsApi api = new EssentialsApi(base, token);
        Toast.makeText(this, "Saving…", Toast.LENGTH_SHORT).show();
        io.submit(() -> {
            try {
                api.upload("live-" + UUID.randomUUID(), "text", note, null, null, null);
                ui.post(() -> Toast.makeText(this, "Saved to Essentials", Toast.LENGTH_SHORT).show());
            } catch (Exception e) {
                ui.post(() -> notice("Not saved: " + (e.getMessage() == null ? "Essentials unreachable" : e.getMessage())));
            }
        });
    }

    private void clearAll() {
        if (listening) stopListening();
        engine.restore(new ArrayList<>(), new ArrayList<>(), "", 0, 0);
        transcriptView.setText(""); partialView.setText(""); cardsBox.removeAllViews();
        shownCards = 0; shownSummary = false; elapsedBefore = 0; startedWhen = ""; title = "";
        emptyCards.setVisibility(View.VISIBLE);
        tabs[1].setText("Insights");
        new File(getFilesDir(), SAVE_FILE).delete();
        showSummary(); refreshButtons();
    }

    // ── autosave ────────────────────────────────────────────────────────────────────────────────

    private synchronized void save() {
        try {
            JSONObject o = new JSONObject();
            o.put("title", title).put("when", startedWhen).put("elapsed", totalSeconds() * 1000).put("summary", engine.summary())
                    .put("tick", engine.consumedTick()).put("sum", engine.consumedSummary());
            o.put("sentences", new JSONArray(engine.sentences()));
            JSONArray cards = new JSONArray();
            for (InsightEngine.Card c : engine.cards()) cards.put(new JSONObject().put("t", c.type).put("x", c.text).put("a", c.at));
            o.put("cards", cards);
            java.nio.file.Files.write(new File(getFilesDir(), SAVE_FILE).toPath(), o.toString().getBytes(StandardCharsets.UTF_8));
        } catch (Throwable ignored) { /* best effort */ }
    }

    private void loadSaved() {
        File f = new File(getFilesDir(), SAVE_FILE);
        if (!f.exists()) { showSummary(); return; }
        try {
            JSONObject o = new JSONObject(new String(java.nio.file.Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8));
            List<String> sents = new ArrayList<>(); JSONArray sa = o.optJSONArray("sentences");
            if (sa != null) for (int i = 0; i < sa.length(); i++) sents.add(sa.getString(i));
            List<InsightEngine.Card> cards = new ArrayList<>(); JSONArray ca = o.optJSONArray("cards");
            if (ca != null) for (int i = 0; i < ca.length(); i++) { JSONObject c = ca.getJSONObject(i); cards.add(new InsightEngine.Card(c.optString("t", "concept"), c.optString("x", ""), c.optLong("a"))); }
            engine.restore(sents, cards, o.optString("summary", ""), o.optInt("tick", 0), o.optInt("sum", 0));
            title = o.optString("title", ""); startedWhen = o.optString("when", ""); elapsedBefore = o.optLong("elapsed", 0);
            StringBuilder t = new StringBuilder();
            for (String s : sents) t.append(t.length() == 0 ? "" : " ").append(s);
            transcriptView.setText(t);
            showCards(); showSummary();
            if (!sents.isEmpty()) notice("Restored your last session. Share or save it, or start a new one.");
        } catch (Throwable ignored) { /* a damaged save is just an empty screen */ }
    }

    @Override public void onRequestPermissionsResult(int code, String[] perms, int[] results) {
        if (code == MIC_REQ && results.length > 0 && results[0] == PackageManager.PERMISSION_GRANTED) begin();
        else if (code == MIC_REQ) notice("Live notes needs the microphone permission");
    }

    @Override public void onBackPressed() {
        if (!listening && Nav.leaveFromTab(this, Layout.Tab.LIVE)) return;
        if (listening) {
            Sheets.confirm(this, "Stop listening?", "Cancel keeps it listening in the background while you use other apps.", "Stop and leave", true, () -> { stopListening(); finish(); }, () -> moveTaskToBack(true));
            return;
        }
        super.onBackPressed();
    }

    @Override protected void onDestroy() {
        super.onDestroy();
        if (listening) stopListening();
        io.shutdown();
    }
}
