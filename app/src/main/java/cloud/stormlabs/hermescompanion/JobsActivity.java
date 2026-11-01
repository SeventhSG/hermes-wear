package cloud.stormlabs.hermescompanion;

import android.app.Activity;
import android.app.Dialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.Typeface;
import android.os.Bundle;
import android.text.InputType;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.text.DateFormat;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Hermes' scheduled cron jobs: list, run, pause/resume, edit and create. */
public final class JobsActivity extends Activity {
    public static final String EXTRA_SKILLS = "skills", EXTRA_PROMPT = "prompt";
    /** Deep-linked from a hub job event. */
    public static final String EXTRA_JOB_ID = "job_id";

    private final ExecutorService network = Executors.newSingleThreadExecutor();
    private SettingsStore store;
    private AgentBackend api;
    private HubApi hub;

    private FrameLayout content;
    private List<HermesApi.Job> jobs = new ArrayList<>();
    private boolean loading = true;
    private String loadError;
    private boolean cronUnavailable;

    // Editor state, live only while the editor is showing.
    private boolean editing;
    private boolean saving;
    private TextView saveButton;
    private HermesApi.Job editingJob;
    private EditText nameField, scheduleField, promptField, deliverField, skillsField, repeatField;
    private String[] editorStart;

    private int dp(int n) { return Ui.dp(this, n); }
    private LinearLayout.LayoutParams match(int h) { return new LinearLayout.LayoutParams(-1, h < 0 ? h : dp(h)); }

    @Override public void onCreate(Bundle b) {
        super.onCreate(b);
        Ui.legacyWindowInsets(this);
        store = new SettingsStore(this);
        api = Backends.create(store);
        hub = new HubApi(store.hubUrl(), store.hubKey());
        getWindow().setStatusBarColor(Ui.BAR);
        getWindow().setNavigationBarColor(Ui.BG);
        buildFrame();
        String prefillPrompt = getIntent().getStringExtra(EXTRA_PROMPT);
        if (prefillPrompt != null && !prefillPrompt.trim().isEmpty()) {
            openEditor(null);
            promptField.setText(prefillPrompt.trim());
            String prefillSkills = getIntent().getStringExtra(EXTRA_SKILLS);
            if (prefillSkills != null && !prefillSkills.trim().isEmpty()) skillsField.setText(prefillSkills.trim());
            editorStart = editorState();
        } else {
            renderList();
            load();
        }
    }

    @Override protected void onDestroy() { super.onDestroy(); network.shutdownNow(); }

    private void buildFrame() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Ui.BG);
        content = new FrameLayout(this);
        root.addView(content, new LinearLayout.LayoutParams(-1, 0, 1));
        Nav.tabBar(this, root, Layout.Tab.AGENTS);
        setContentView(root);
        Ui.fitInsets(root, root);
    }

    // ---------------------------------------------------------------- list

    private void renderList() {
        editing = false;
        content.removeAllViews();
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);

        LinearLayout bar = new LinearLayout(this);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setPadding(dp(2), 0, dp(8), 0);
        bar.setBackgroundColor(Ui.BAR);
        ImageButton back = Ui.icon(this, R.drawable.ic_back, "Back");
        back.setOnClickListener(v -> onBackPressed());
        bar.addView(back, new LinearLayout.LayoutParams(dp(52), dp(56)));
        bar.addView(Ui.text(this, "Agents", 19, Ui.TEXT, true), new LinearLayout.LayoutParams(0, -2, 1));
        ImageButton refresh = Ui.icon(this, R.drawable.ic_refresh, "Refresh");
        refresh.setOnClickListener(v -> load());
        bar.addView(refresh, new LinearLayout.LayoutParams(dp(48), dp(56)));
        ImageButton add = Ui.icon(this, R.drawable.ic_new, "New job");
        add.setOnClickListener(v -> openEditor(null));
        bar.addView(add, new LinearLayout.LayoutParams(dp(48), dp(56)));
        root.addView(bar, match(56));

        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        LinearLayout body = new LinearLayout(this);
        body.setOrientation(LinearLayout.VERTICAL);
        body.setPadding(dp(12), dp(12), dp(12), dp(28));
        scroll.addView(body);
        root.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));
        content.addView(root, new FrameLayout.LayoutParams(-1, -1));

        if (loading) {
            body.addView(Ui.skeletonRows(this, 4), match(-2));
            return;
        }
        if (cronUnavailable) {
            body.addView(centered(message("Cron jobs aren't available on this server.",
                    "The Hermes cron module isn't enabled here, so scheduled jobs can't be managed from this device.", null)), match(-2));
            return;
        }
        if (loadError != null) {
            body.addView(centered(message("Couldn't load jobs", loadError, this::load)), match(-2));
            return;
        }
        if (jobs.isEmpty()) {
            body.addView(centered(message("No jobs yet", "Tap + to schedule Hermes to run something on a recurring basis.", null)), match(-2));
            return;
        }
        JobSort.Mode mode = JobSort.Mode.of(store().jobSort());
        TextView sort = Ui.text(this, "Sorted by " + mode.label.toLowerCase(Locale.ROOT) + "  ▾", 13, Ui.BLUE, true);
        sort.setPadding(dp(4), dp(2), dp(4), dp(10));
        sort.setContentDescription("Sort jobs. Now: " + mode.label);
        sort.setOnClickListener(v -> pickSort());
        body.addView(sort);
        int lastBucket = -1;
        for (HermesApi.Job job : JobSort.sort(jobs, mode)) {
            if (JobSort.grouped(mode)) {
                int bucket = JobSort.bucket(job);
                if (bucket != lastBucket) {
                    lastBucket = bucket;
                    int count = 0;
                    for (HermesApi.Job other : jobs) if (JobSort.bucket(other) == bucket) count++;
                    TextView head = Ui.label(this, JobSort.HEADERS[bucket] + "  ·  " + count, bucket == JobSort.FAILING ? Ui.RED : Ui.FAINT);
                    head.setPadding(dp(4), lastBucket == 0 ? 0 : dp(14), 0, dp(6));
                    body.addView(head);
                }
            }
            body.addView(jobCard(job), match(-2));
        }
        Ui.stagger(body);
    }

    private SettingsStore store() { return new SettingsStore(this); }

    private void pickSort() {
        JobSort.Mode current = JobSort.Mode.of(store().jobSort());
        List<Sheets.Item> items = new ArrayList<>();
        String[] notes = {"Failed jobs first, then upcoming by time, paused at the end", "Whatever fires soonest", "Most recently run", "Alphabetical"};
        for (JobSort.Mode m : JobSort.Mode.values()) {
            items.add(Sheets.Item.of(R.drawable.ic_clock, m == current ? Ui.BLUE : Ui.SUB, m.label + (m == current ? "  ✓" : ""), notes[m.ordinal()],
                    () -> { store().jobSort(m.name()); renderList(); }));
        }
        Sheets.actions(this, "Sort jobs", null, items.toArray(new Sheets.Item[0]));
    }

    private View jobCard(HermesApi.Job job) {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setBackground(Ui.ripple(this, Ui.SURFACE, 16));
        card.setPadding(dp(16), dp(14), dp(16), dp(14));
        card.setOnClickListener(v -> showJobActions(job));

        LinearLayout titleRow = new LinearLayout(this);
        titleRow.setGravity(Gravity.CENTER_VERTICAL);
        TextView name = Ui.text(this, job.title(), 16, Ui.TEXT, true);
        titleRow.addView(name, new LinearLayout.LayoutParams(0, -2, 1));
        titleRow.addView(stateChip(job), new LinearLayout.LayoutParams(-2, -2));
        card.addView(titleRow, match(-2));

        TextView schedule = Ui.text(this, job.schedule.isEmpty() ? "No schedule" : humanSchedule(job.schedule), 13, Ui.SUB, false);
        schedule.setTypeface(Typeface.MONOSPACE);
        schedule.setPadding(0, dp(6), 0, 0);
        card.addView(schedule, match(-2));

        StringBuilder runInfo = new StringBuilder();
        // A paused job keeps its stale next_run_at; "next 4mo ago" would only confuse.
        if (!job.nextRunAt.isEmpty() && !job.paused()) runInfo.append("Next ").append(TimeFmt.relative(job.nextRunAt));
        if (!job.lastRunAt.isEmpty()) {
            if (runInfo.length() > 0) runInfo.append("  ·  ");
            runInfo.append("Last run ").append(TimeFmt.relativePast(job.lastRunAt).replaceAll("(\\d+)(m|h|d|w|mo) ago$", "$1 $2 ago"));
        }
        if (runInfo.length() > 0) {
            TextView runs = Ui.text(this, runInfo.toString(), 12, Ui.FAINT, false);
            runs.setPadding(0, dp(4), 0, 0);
            card.addView(runs, match(-2));
        }

        if (!job.lastError.isEmpty()) {
            TextView err = Ui.text(this, "Last run failed: " + shortError(job.lastError), 12, Ui.RED, false);
            err.setMaxLines(2);
            err.setEllipsize(TextUtils.TruncateAt.END);
            err.setMinHeight(dp(48));
            err.setContentDescription(err.getText() + ". Long press to copy full error");
            err.setOnLongClickListener(v -> {
                ((ClipboardManager) getSystemService(CLIPBOARD_SERVICE)).setPrimaryClip(
                        ClipData.newPlainText("Job error", job.lastError));
                Toast.makeText(this, "Full error copied", Toast.LENGTH_SHORT).show();
                return true;
            });
            err.setPadding(0, dp(6), 0, 0);
            card.addView(err, match(-2));
        }

        TextView deliver = Ui.text(this, "Deliver: " + (job.deliver.isEmpty() ? "local" : job.deliver), 11, Ui.FAINT, false);
        deliver.setPadding(0, dp(6), 0, 0);
        card.addView(deliver, match(-2));

        LinearLayout wrapper = new LinearLayout(this);
        LinearLayout.LayoutParams cp = match(-2);
        cp.setMargins(0, 0, 0, dp(10));
        wrapper.addView(card, match(-2));
        LinearLayout outer = new LinearLayout(this);
        outer.addView(wrapper, cp);
        return outer;
    }

    static String shortError(String raw) {
        if (raw == null) return "";
        String message = null;
        for (char quote : new char[]{'\'', '"'}) {
            String key = quote + "message" + quote;
            int start = raw.indexOf(key);
            if (start < 0) continue;
            start = raw.indexOf(':', start + key.length());
            if (start < 0) continue;
            start++;
            while (start < raw.length() && Character.isWhitespace(raw.charAt(start))) start++;
            if (start >= raw.length() || (raw.charAt(start) != '\'' && raw.charAt(start) != '"')) continue;
            int end = raw.indexOf(raw.charAt(start), start + 1);
            if (end > start) { message = raw.substring(start + 1, end); break; }
        }
        if (message == null) {
            int colon = raw.lastIndexOf(": ");
            message = colon < 0 ? raw : raw.substring(colon + 2);
        }
        message = message.trim().replace('\n', ' ').replace('\r', ' ');
        return message.length() > 80 ? message.substring(0, 79).trim() + "…" : message;
    }

    static String humanSchedule(String schedule) {
        if (schedule == null) return null;
        if (schedule.matches("every \\d+m")) {
            int minutes;
            try { minutes = Integer.parseInt(schedule.substring(6, schedule.length() - 1)); }
            catch (NumberFormatException e) { return schedule; }
            if (minutes <= 0) return schedule;
            if (minutes == 1440) return "Every day";
            if (minutes % 60 == 0) return "Every " + minutes / 60 + " h";
            return "Every " + minutes + " min";
        }
        String[] fields = schedule.split("\\s+");
        if (fields.length == 5 && "*".equals(fields[2]) && "*".equals(fields[3]) && "*".equals(fields[4])
                && fields[0].matches("\\d{1,2}") && fields[1].matches("\\d{1,2}(,\\d{1,2})*")) {
            int minute = Integer.parseInt(fields[0]);
            if (minute > 59) return schedule;
            StringBuilder times = new StringBuilder("Daily at ");
            for (String hour : fields[1].split(",")) {
                int h = Integer.parseInt(hour);
                if (h > 23) return schedule;
                if (times.length() > 9) times.append(", ");
                times.append(String.format(Locale.ROOT, "%02d:%02d", h, minute));
            }
            return times.toString();
        }
        return schedule;
    }

    private View stateChip(HermesApi.Job job) {
        String s = job.state.toLowerCase(Locale.ROOT);
        int color; String label;
        if (s.contains("run")) { color = Ui.AMBER; label = "RUNNING"; }
        else if (s.contains("error") || s.contains("fail")) { color = Ui.RED; label = "ERROR"; }
        else if (s.contains("complete") || s.contains("done")) { color = Ui.GREEN; label = "COMPLETED"; }
        else if (s.contains("pause") || job.paused()) { color = Ui.FAINT; label = "PAUSED"; }
        else if (!s.isEmpty()) { color = Ui.BLUE; label = s.toUpperCase(Locale.ROOT); }
        else { color = Ui.BLUE; label = "SCHEDULED"; }
        TextView chip = Ui.text(this, label, 10, color, true);
        chip.setLetterSpacing(.05f);
        chip.setPadding(dp(8), dp(3), dp(8), dp(3));
        chip.setBackground(Ui.stroke(this, Color.TRANSPARENT, color, 20));
        return chip;
    }

    private void showJobActions(HermesApi.Job job) {
        List<Sheets.Item> items = new ArrayList<>();
        items.add(Sheets.Item.of(R.drawable.ic_clock, Ui.BLUE, "Run history", "Recent job outputs", () -> showRunHistory(job.id, job.title())));
        items.add(Sheets.Item.of(R.drawable.ic_bolt, Ui.BLUE, "Run now", "Trigger this job immediately", () -> runAction(() -> api.runJob(job.id), "run " + job.title())));
        if (job.paused()) items.add(Sheets.Item.of(R.drawable.ic_next, Ui.GREEN, "Resume", "Let it fire on schedule again", () -> runAction(() -> api.resumeJob(job.id), "resume " + job.title())));
        else items.add(Sheets.Item.of(R.drawable.ic_stop, Ui.AMBER, "Pause", "Stop it from firing on schedule", () -> runAction(() -> api.pauseJob(job.id), "pause " + job.title())));
        items.add(Sheets.Item.of(R.drawable.ic_edit, Ui.BLUE, "Edit", "Change name, schedule or prompt", () -> openEditor(job)));
        items.add(Sheets.Item.danger(R.drawable.ic_delete, "Delete", "This cannot be undone", () ->
                Sheets.confirm(this, "Delete " + job.title() + "?", "This removes the job and its schedule permanently.", "Delete", true,
                        () -> runAction(() -> api.deleteJob(job.id), "delete " + job.title()))));
        Sheets.actions(this, job.title(), job.schedule, items.toArray(new Sheets.Item[0]));
    }

    private void showRunHistory(String id, String title) {
        ScrollView scroll = new ScrollView(this);
        LinearLayout rows = new LinearLayout(this);
        rows.setOrientation(LinearLayout.VERTICAL);
        rows.setPadding(dp(16), dp(8), dp(16), dp(16));
        rows.addView(Ui.text(this, "Loading run history…", 14, Ui.SUB, false));
        scroll.addView(rows);
        Dialog sheet = Sheets.tall(this, title, "Run history", scroll);
        network.submit(() -> {
            try {
                List<HubApi.JobOutput> outputs = hub.jobOutputs(id, 100);
                outputs.sort((a, b) -> Double.compare(b.ts, a.ts));
                runOnUiThread(() -> {
                    if (!sheet.isShowing()) return;
                    rows.removeAllViews();
                    if (outputs.isEmpty()) rows.addView(Ui.text(this, "No runs recorded yet", 14, Ui.SUB, false));
                    for (HubApi.JobOutput output : outputs) {
                        LinearLayout card = new LinearLayout(this);
                        card.setOrientation(LinearLayout.VERTICAL);
                        card.setPadding(dp(14), dp(12), dp(14), dp(14));
                        card.setBackground(Ui.round(this, Ui.SURFACE, 12));
                        String when = DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT)
                                .format(new Date((long) (output.ts * 1000)));
                        TextView heading = Ui.text(this, when + (output.name.isEmpty() ? "" : "  ·  " + output.name), 12, Ui.SUB, true);
                        heading.setPadding(0, 0, 0, dp(8));
                        card.addView(heading);
                        card.addView(MarkdownView.render(this, output.text, Ui.TEXT, 14, null, null));
                        LinearLayout.LayoutParams p = match(-2);
                        p.bottomMargin = dp(10);
                        rows.addView(card, p);
                    }
                });
            } catch (Exception e) {
                runOnUiThread(() -> {
                    if (!sheet.isShowing()) return;
                    rows.removeAllViews();
                    rows.addView(Ui.text(this, "Couldn't load run history\n" + HermesApi.friendly(e), 14, Ui.RED, false));
                });
            }
        });
    }

    private interface JobResultCall { HermesApi.Result run(); }

    private void runAction(JobResultCall call, String what) {
        toast("Working…");
        network.submit(() -> {
            HermesApi.Result r = call.run();
            runOnUiThread(() -> { toast(r.ok ? r.text : "Could not " + what + ": " + r.text); load(); });
        });
    }

    private void load() {
        loading = true; loadError = null; cronUnavailable = false;
        if (!editing) renderList();
        network.submit(() -> {
            try {
                List<HermesApi.Job> loaded = api.jobs();
                runOnUiThread(() -> {
                    jobs = loaded; loading = false;
                    if (!editing) renderList();
                    String target = getIntent().getStringExtra(EXTRA_JOB_ID);
                    if (target != null && !target.isEmpty() && !editing) {
                        getIntent().removeExtra(EXTRA_JOB_ID);
                        for (HermesApi.Job job : jobs) if (target.equals(job.id)) { showRunHistory(job.id, job.title()); return; }
                        showRunHistory(target, target);
                    }
                });
            } catch (Exception e) {
                String friendly = HermesApi.friendly(e);
                boolean unavailable = friendly != null && (friendly.contains("HTTP 501") || friendly.toLowerCase(Locale.ROOT).contains("cron module"));
                runOnUiThread(() -> { loading = false; if (unavailable) cronUnavailable = true; else loadError = friendly; if (!editing) renderList(); });
            }
        });
    }

    // ---------------------------------------------------------------- editor

    private void openEditor(HermesApi.Job job) {
        editing = true;
        saving = false;
        editingJob = job;
        content.removeAllViews();
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);

        LinearLayout bar = new LinearLayout(this);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setPadding(dp(2), 0, dp(14), 0);
        bar.setBackgroundColor(Ui.BAR);
        ImageButton close = Ui.icon(this, R.drawable.ic_back, "Cancel");
        close.setOnClickListener(v -> onBackPressed());
        bar.addView(close, new LinearLayout.LayoutParams(dp(52), dp(56)));
        bar.addView(Ui.text(this, job == null ? "New job" : "Edit job", 19, Ui.TEXT, true), new LinearLayout.LayoutParams(0, -2, 1));
        TextView save = Ui.text(this, "SAVE", 13, Ui.BLUE, true);
        saveButton = save;
        save.setLetterSpacing(.06f);
        save.setPadding(dp(10), dp(8), dp(4), dp(8));
        save.setMinHeight(dp(48)); save.setGravity(Gravity.CENTER);
        save.setOnClickListener(v -> saveJob());
        bar.addView(save, new LinearLayout.LayoutParams(-2, -2));
        root.addView(bar, match(56));

        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        LinearLayout body = new LinearLayout(this);
        body.setOrientation(LinearLayout.VERTICAL);
        body.setPadding(dp(16), dp(16), dp(16), dp(28));
        scroll.addView(body);
        root.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));
        content.addView(root, new FrameLayout.LayoutParams(-1, -1));

        nameField = field(body, "Name", job == null ? "" : job.name, "Morning briefing", false);
        scheduleField = field(body, "Schedule", job == null ? "" : job.schedule, "every 30m, weekdays 9am, or 0 8 * * *", false);
        body.addView(scheduleChips(), match(-2));
        promptField = field(body, "Prompt", job == null ? "" : job.prompt, "What should Hermes do when this runs?", true);
        deliverField = field(body, "Deliver", job == null ? "local" : (job.deliver.isEmpty() ? "local" : job.deliver), "local, origin, telegram…", false);
        skillsField = field(body, "Skills (optional)", job == null ? "" : String.join(", ", job.skills), "comma separated skill names", false);
        repeatField = field(body, "Repeat count (optional)", job != null && job.repeatTimes > 0 ? String.valueOf(job.repeatTimes) : "", "leave blank to repeat forever", false);
        repeatField.setInputType(InputType.TYPE_CLASS_NUMBER);
        editorStart = editorState();
    }

    private String[] editorState() {
        return new String[]{nameField.getText().toString(), scheduleField.getText().toString(),
                promptField.getText().toString(), deliverField.getText().toString(),
                skillsField.getText().toString(), repeatField.getText().toString()};
    }

    @Override public void onBackPressed() {
        if (!editing) { if (!Nav.leaveFromTab(this, Layout.Tab.AGENTS)) Ui.back(this); return; }
        if (saving) { toast("Saving…"); return; }
        Runnable close = () -> { renderList(); if (jobs.isEmpty() && !loading) load(); };
        if (java.util.Arrays.equals(editorStart, editorState())) close.run();
        else Sheets.confirm(this, "Discard changes?", "Your job edits have not been saved.",
                "Discard", true, close);
    }

    private EditText field(LinearLayout parent, String label, String value, String hint, boolean multiline) {
        TextView l = Ui.text(this, label.toUpperCase(Locale.ROOT), 11, Ui.BLUE, true);
        l.setLetterSpacing(.08f);
        LinearLayout.LayoutParams lp = match(-2);
        lp.setMargins(dp(2), dp(16), 0, dp(6));
        parent.addView(l, lp);
        EditText edit = new EditText(this);
        edit.setText(value);
        edit.setHint(hint);
        edit.setTextColor(Ui.TEXT);
        edit.setHintTextColor(Ui.FAINT);
        edit.setTextSize(15);
        edit.setBackground(Ui.stroke(this, Ui.SURFACE, Ui.LINE, 12));
        if (multiline) {
            edit.setPadding(dp(14), dp(12), dp(14), dp(12));
            edit.setMinLines(4);
            edit.setGravity(Gravity.TOP | Gravity.START);
            edit.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
            parent.addView(edit, match(120));
        } else {
            edit.setPadding(dp(14), 0, dp(14), 0);
            edit.setSingleLine(true);
            parent.addView(edit, match(50));
        }
        return edit;
    }

    private View scheduleChips() {
        HorizontalScrollView scroller = new HorizontalScrollView(this);
        scroller.setHorizontalScrollBarEnabled(false);
        LinearLayout row = new LinearLayout(this);
        row.setPadding(0, dp(8), 0, 0);
        String[][] presets = {
                {"Every morning 8am", "0 8 * * *"},
                {"Every hour", "every 1h"},
                {"Weekdays 9am", "0 9 * * 1-5"},
                {"Every Monday 9am", "0 9 * * 1"},
        };
        for (String[] preset : presets) {
            TextView chip = Ui.text(this, preset[0], 12, Ui.TEXT, false);
            chip.setPadding(dp(12), dp(7), dp(12), dp(7));
            chip.setBackground(Ui.stroke(this, Ui.RAISED, Ui.LINE, 20));
            chip.setOnClickListener(v -> { scheduleField.setText(preset[1]); scheduleField.setSelection(scheduleField.length()); });
            LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(-2, -2);
            p.setMargins(0, 0, dp(8), 0);
            row.addView(chip, p);
        }
        scroller.addView(row);
        return scroller;
    }

    private void saveJob() {
        if (saving) return;
        String name = nameField.getText().toString().trim();
        String schedule = scheduleField.getText().toString().trim();
        String prompt = promptField.getText().toString().trim();
        String deliver = deliverField.getText().toString().trim();
        String skillsCsv = skillsField.getText().toString().trim();
        String repeatText = repeatField.getText().toString().trim();
        if (name.isEmpty()) { toast("Name is required"); return; }
        if (schedule.isEmpty()) { toast("Schedule is required"); return; }
        if (prompt.isEmpty()) { toast("Prompt is required"); return; }
        Integer repeat = null;
        if (!repeatText.isEmpty()) {
            try { repeat = Integer.valueOf(repeatText); } catch (NumberFormatException e) { toast("Repeat count must be a number"); return; }
        }
        List<String> skills = new ArrayList<>();
        for (String part : skillsCsv.split(",")) { String v = part.trim(); if (!v.isEmpty()) skills.add(v); }

        saving = true;
        saveButton.setText("SAVING…");
        HermesApi.Job editedJob = editingJob;
        Integer finalRepeat = repeat;
        network.submit(() -> {
            try {
                if (editedJob == null) {
                    api.createJob(name, schedule, prompt, deliver, skills, finalRepeat);
                } else {
                    JSONObject fields = new JSONObject();
                    fields.put("name", name);
                    fields.put("schedule", schedule);
                    fields.put("prompt", prompt);
                    fields.put("deliver", deliver);
                    fields.put("skills", new JSONArray(skills));
                    fields.put("repeat", finalRepeat == null ? JSONObject.NULL : finalRepeat);
                    api.updateJob(editedJob.id, fields);
                }
                runOnUiThread(() -> { saving = false; toast(editedJob == null ? "Job created" : "Job updated"); renderList(); load(); });
            } catch (Exception e) {
                String message = HermesApi.friendly(e);
                runOnUiThread(() -> { saving = false; saveButton.setText("SAVE"); toast(message); });
            }
        });
    }

    // ---------------------------------------------------------------- small view helpers

    private ProgressBar progress() {
        ProgressBar p = new ProgressBar(this);
        p.setIndeterminateTintList(ColorStateList.valueOf(Ui.BLUE));
        return p;
    }

    private View message(String title, String subtitle, Runnable onTapRetry) {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setGravity(Gravity.CENTER_HORIZONTAL);
        box.setPadding(dp(24), dp(40), dp(24), dp(24));
        TextView t = Ui.text(this, title, 16, Ui.TEXT, true);
        t.setGravity(Gravity.CENTER);
        box.addView(t, match(-2));
        TextView s = Ui.text(this, subtitle == null ? "" : subtitle, 13, Ui.SUB, false);
        s.setGravity(Gravity.CENTER);
        s.setLineSpacing(0, 1.2f);
        s.setPadding(0, dp(8), 0, 0);
        box.addView(s, match(-2));
        if (onTapRetry != null) {
            box.setOnClickListener(v -> onTapRetry.run());
            TextView retry = Ui.text(this, "TAP TO RETRY", 11, Ui.BLUE, true);
            retry.setLetterSpacing(.06f);
            retry.setGravity(Gravity.CENTER);
            retry.setPadding(0, dp(10), 0, 0);
            box.addView(retry, match(-2));
        }
        return box;
    }

    private View centered(View v) {
        FrameLayout f = new FrameLayout(this);
        FrameLayout.LayoutParams p = new FrameLayout.LayoutParams(-2, -2);
        p.gravity = Gravity.CENTER_HORIZONTAL;
        p.topMargin = dp(40);
        f.addView(v, p);
        return f;
    }

    private void toast(String s) { Toast.makeText(this, s == null || s.isEmpty() ? "Done" : s, Toast.LENGTH_SHORT).show(); }
}
