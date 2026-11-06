package cloud.stormlabs.hermescompanion;

import android.app.Activity;
import android.content.ClipData;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.util.ArrayList;

/**
 * "Ask Hermes" from anywhere: text selection (ACTION_PROCESS_TEXT), and the share sheet for text
 * or images (ACTION_SEND / ACTION_SEND_MULTIPLE).
 *
 * There is no chat here — this is a fast, translucent picker over whatever app invoked it. Every
 * path ends the same way: hand the prompt (and any images) to MainActivity, which opens the chat and
 * sends it at once. A bare selection goes as-is, so Hermes works out what to do with it.
 */
public final class AskActivity extends Activity {
    private String selectedText = "";
    private boolean readOnly = true;
    private final ArrayList<Uri> images = new ArrayList<>();
    private EditText question;

    @Override public void onCreate(Bundle b) {
        super.onCreate(b);
        Ui.apply(this);
        try {
            if (new SettingsStore(this).apiKey().trim().isEmpty()) {
                startActivity(new Intent(this, ConnectionActivity.class));
                finish();
                return;
            }
            readExtras(getIntent());
            if (selectedText.isEmpty() && images.isEmpty()) { finish(); return; }
            buildUi();
        } catch (Throwable t) {
            CrashReporter.show(this, t);
        }
    }

    private void readExtras(Intent intent) {
        if (intent == null) return;
        String action = intent.getAction();
        if (Intent.ACTION_PROCESS_TEXT.equals(action)) {
            CharSequence t = intent.getCharSequenceExtra(Intent.EXTRA_PROCESS_TEXT);
            selectedText = t == null ? "" : t.toString().trim();
            readOnly = intent.getBooleanExtra(Intent.EXTRA_PROCESS_TEXT_READONLY, true);
        } else if (Intent.ACTION_SEND.equals(action)) {
            String t = intent.getStringExtra(Intent.EXTRA_TEXT);
            selectedText = t == null ? "" : t.trim();
            Uri one = intent.getParcelableExtra(Intent.EXTRA_STREAM);
            if (one != null) images.add(one);
        } else if (Intent.ACTION_SEND_MULTIPLE.equals(action)) {
            ArrayList<Uri> many = intent.getParcelableArrayListExtra(Intent.EXTRA_STREAM);
            if (many != null) images.addAll(many);
        }
    }

    // ── UI ──────────────────────────────────────────────────────────────────────────────────────

    private void buildUi() {
        FrameLayout root = new FrameLayout(this);
        root.setOnClickListener(v -> onBackPressed());

        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setBackground(Ui.round(this, Ui.BAR, 20));
        card.setPadding(0, 0, 0, dp(10));
        card.setOnClickListener(v -> { }); // swallow taps so they don't fall through to root's finish()

        View handle = new View(this);
        handle.setBackground(Ui.round(this, Ui.FAINT, 99));
        LinearLayout.LayoutParams hp = new LinearLayout.LayoutParams(dp(36), dp(4));
        hp.gravity = Gravity.CENTER_HORIZONTAL; hp.setMargins(0, dp(10), 0, dp(10));
        card.addView(handle, hp);

        TextView title = Ui.text(this, "Ask Hermes", 19, Ui.TEXT, true);
        title.setPadding(dp(18), 0, dp(18), dp(8));
        card.addView(title);

        if (!selectedText.isEmpty()) {
            TextView preview = Ui.text(this, selectedText, 13, Ui.SUB, false);
            preview.setMaxLines(5);
            preview.setEllipsize(TextUtils.TruncateAt.END);
            preview.setLineSpacing(0, 1.15f);
            preview.setBackground(Ui.stroke(this, Ui.SURFACE, Ui.LINE, 12));
            preview.setPadding(dp(12), dp(10), dp(12), dp(10));
            LinearLayout.LayoutParams pp = new LinearLayout.LayoutParams(-1, -2);
            pp.setMargins(dp(16), 0, dp(16), dp(10));
            ScrollView scroll = new ScrollView(this);
            scroll.addView(preview);
            card.addView(scroll, pp);
        }
        if (!images.isEmpty()) {
            TextView imgLine = Ui.text(this, images.size() == 1 ? "1 image attached" : images.size() + " images attached", 12, Ui.BLUE, true);
            imgLine.setPadding(dp(18), 0, dp(18), dp(10));
            card.addView(imgLine);
        }

        question = new EditText(this);
        question.setHint(selectedText.isEmpty() ? "Ask Hermes about this image…" : "Ask about this… (optional)");
        question.setTextColor(Ui.TEXT); question.setHintTextColor(Ui.FAINT); question.setTextSize(15);
        question.setMinLines(1); question.setMaxLines(4);
        question.setBackground(Ui.stroke(this, Ui.SURFACE, Ui.BLUE, 12));
        question.setPadding(dp(14), dp(10), dp(14), dp(10));
        LinearLayout.LayoutParams qp = new LinearLayout.LayoutParams(-1, -2);
        qp.setMargins(dp(16), 0, dp(16), dp(12));
        card.addView(question, qp);

        card.addView(chipsRow(), new LinearLayout.LayoutParams(-1, -2));

        TextView ask = Ui.text(this, "ASK HERMES", 14, Color.WHITE, true);
        ask.setGravity(Gravity.CENTER);
        ask.setLetterSpacing(.04f);
        ask.setBackground(Ui.gradient(this, 0xff4a7ae8, 0xff3b61c2, 14));
        ask.setOnClickListener(v -> send(freeformPrompt()));
        LinearLayout.LayoutParams ap = new LinearLayout.LayoutParams(-1, dp(50));
        ap.setMargins(dp(16), dp(4), dp(16), dp(4));
        card.addView(ask, ap);

        root.addView(card, new FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM));
        setContentView(root);
        Ui.liftAboveSystemBars(card);

        Window w = getWindow();
        w.setBackgroundDrawable(new ColorDrawable(0x99000000));
        w.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
        w.setGravity(Gravity.BOTTOM);
        w.addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);
        WindowManager.LayoutParams attrs = w.getAttributes();
        attrs.dimAmount = 0f; // the semi-transparent background above already dims
        w.setAttributes(attrs);
        // No keyboard until the field is tapped: it covered the chips and the Ask button.
        question.setImeOptions(android.view.inputmethod.EditorInfo.IME_ACTION_SEND);
        question.setOnEditorActionListener((v, a, e) -> { if (a == android.view.inputmethod.EditorInfo.IME_ACTION_SEND) { send(freeformPrompt()); return true; } return false; });
    }

    private View chipsRow() {
        HorizontalScrollView scroll = new HorizontalScrollView(this);
        scroll.setHorizontalScrollBarEnabled(false);
        LinearLayout row = new LinearLayout(this);
        row.setPadding(dp(16), 0, dp(8), dp(10));
        chip(row, "Explain", () -> send(withText("Explain this")));
        chip(row, "Summarise", () -> send(withText("Summarise this")));
        chip(row, "Translate to English", () -> send(withText("Translate this to English")));
        if (!selectedText.isEmpty()) chip(row, "Reply to this", () -> send(withText("Write a reply to this")));
        if (!selectedText.isEmpty()) chip(row, "Save for later", () -> send("Save this to my read-later list (append to /root/.hermes/read-later.md with date and a one-line summary; don't summarise it now): " + selectedText));
        chip(row, "Fix grammar", () -> send(withText("Fix the grammar and spelling in this, keeping the meaning")));
        // A real in-place PROCESS_TEXT replace only works for apps that consume our RESULT_OK
        // response, which most keyboards/editors do not for third-party assistants. Opening the
        // chat with a clear rewrite instruction is the reliable path, so it is offered the same way.
        if (!readOnly && !selectedText.isEmpty()) chip(row, "Rewrite & replace", () -> send(withText("Rewrite this, keeping the meaning but improving clarity. Reply with only the rewritten text so I can copy it back")));
        scroll.addView(row);
        return scroll;
    }

    private void chip(LinearLayout row, String label, Runnable onTap) {
        TextView v = Ui.text(this, label, 13, Ui.TEXT, false);
        v.setBackground(Ui.stroke(this, Ui.SURFACE, Ui.LINE, 99));
        v.setPadding(dp(14), dp(9), dp(14), dp(9));
        v.setOnClickListener(view -> onTap.run());
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, -2);
        lp.setMargins(0, 0, dp(8), 0);
        row.addView(v, lp);
    }

    // ── prompt composition ─────────────────────────────────────────────────────────────────────

    private String withText(String instruction) {
        String extra = question == null ? "" : question.getText().toString().trim();
        StringBuilder p = new StringBuilder(instruction);
        if (!extra.isEmpty()) p.append(" — ").append(extra);
        if (!selectedText.isEmpty()) p.append(":\n\n\"\"\"\n").append(selectedText).append("\n\"\"\"");
        return p.toString();
    }

    /** No chip tapped: whatever the user typed, plus the quoted selection if there is one. */
    private String freeformPrompt() {
        String extra = question == null ? "" : question.getText().toString().trim();
        if (extra.isEmpty()) return selectedText; // no instruction: Hermes infers what to do with it
        if (selectedText.isEmpty()) return extra;
        return extra + ":\n\n\"\"\"\n" + selectedText + "\n\"\"\"";
    }

    private void send(String prompt) {
        if (prompt == null || (prompt.trim().isEmpty() && images.isEmpty())) { finish(); return; }
        Intent i = new Intent(this, MainActivity.class)
                .putExtra(MainActivity.EXTRA_OPEN_CHAT, true)
                .putExtra(ChatActivity.EXTRA_PROMPT, prompt)
                .putExtra(ChatActivity.EXTRA_AUTOSEND, true)
                .putExtra(MainActivity.EXTRA_PROFILE_SECRET, new SettingsStore(this).intentSecret())
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        if (!images.isEmpty()) {
            i.putParcelableArrayListExtra(ChatActivity.EXTRA_IMAGES, images);
            i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            ClipData clip = ClipData.newRawUri("images", images.get(0));
            for (int k = 1; k < images.size(); k++) clip.addItem(new ClipData.Item(images.get(k)));
            i.setClipData(clip);
        }
        startActivity(i);
        finish();
    }

    @Override public void onBackPressed() {
        if (question != null && question.length() > 0) Sheets.confirm(this, "Discard changes?",
                "Your question has not been sent.", "Discard", true, this::finish);
        else finish();
    }
    private int dp(int v) { return Ui.dp(this, v); }
}
