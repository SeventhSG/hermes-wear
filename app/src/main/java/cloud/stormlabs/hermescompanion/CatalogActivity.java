package cloud.stormlabs.hermescompanion;

import android.app.Activity;
import android.content.Intent;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.View;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Browse installed skills (grouped by category) or connected toolsets (with their tools). */
public final class CatalogActivity extends Activity {
    public static final String EXTRA_SKILLS = "skills";

    private final ExecutorService network = Executors.newSingleThreadExecutor();
    private SettingsStore store;
    private AgentBackend api;
    private boolean skillsMode;

    private LinearLayout body;
    private EditText search;
    private boolean loading = true;
    private String loadError;
    private List<HermesApi.Skill> skills = new ArrayList<>();
    private List<HermesApi.Toolset> toolsets = new ArrayList<>();
    private final Set<String> expandedToolsets = new HashSet<>();

    private int dp(int n) { return Ui.dp(this, n); }
    private LinearLayout.LayoutParams match(int h) { return new LinearLayout.LayoutParams(-1, h < 0 ? h : dp(h)); }

    @Override public void onCreate(Bundle b) {
        super.onCreate(b);
        Ui.legacyWindowInsets(this);
        store = new SettingsStore(this);
        api = Backends.create(store);
        skillsMode = getIntent().getBooleanExtra(EXTRA_SKILLS, true);
        getWindow().setStatusBarColor(Ui.BAR);
        getWindow().setNavigationBarColor(Ui.BG);
        build();
        load();
    }

    @Override protected void onDestroy() { super.onDestroy(); network.shutdownNow(); }

    private void build() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Ui.BG);

        LinearLayout bar = new LinearLayout(this);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setPadding(dp(2), 0, dp(8), 0);
        bar.setBackgroundColor(Ui.BAR);
        ImageButton back = Ui.icon(this, R.drawable.ic_back, "Back");
        back.setOnClickListener(v -> onBackPressed());
        bar.addView(back, new LinearLayout.LayoutParams(dp(52), dp(56)));
        bar.addView(Ui.text(this, skillsMode ? "Skills" : "Toolsets", 19, Ui.TEXT, true), new LinearLayout.LayoutParams(0, -2, 1));
        ImageButton refresh = Ui.icon(this, R.drawable.ic_refresh, "Refresh");
        refresh.setOnClickListener(v -> load());
        bar.addView(refresh, new LinearLayout.LayoutParams(dp(48), dp(56)));
        root.addView(bar, match(56));

        LinearLayout searchRow = new LinearLayout(this);
        searchRow.setPadding(dp(12), dp(10), dp(12), dp(6));
        search = new EditText(this);
        search.setHint(skillsMode ? "Search skills" : "Search toolsets");
        search.setTextColor(Ui.TEXT);
        search.setHintTextColor(Ui.FAINT);
        search.setTextSize(14);
        search.setSingleLine(true);
        search.setPadding(dp(38), 0, dp(14), 0);
        search.setBackground(Ui.stroke(this, Ui.SURFACE, Ui.LINE, 12));
        search.setCompoundDrawablesWithIntrinsicBounds(R.drawable.ic_search, 0, 0, 0);
        search.setCompoundDrawablePadding(dp(8));
        search.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int st, int c, int a) {}
            @Override public void onTextChanged(CharSequence s, int st, int b, int c) { render(); }
            @Override public void afterTextChanged(Editable e) {}
        });
        searchRow.addView(search, match(48));
        root.addView(searchRow, match(-2));

        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        body = new LinearLayout(this);
        body.setOrientation(LinearLayout.VERTICAL);
        body.setPadding(dp(12), dp(4), dp(12), dp(28));
        scroll.addView(body);
        root.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));
        setContentView(root);
        Ui.fitInsets(root, root);
    }

    private void load() {
        loading = true; loadError = null;
        render();
        network.submit(() -> {
            try {
                if (skillsMode) {
                    List<HermesApi.Skill> loaded = api.skillList();
                    runOnUiThread(() -> { skills = loaded; loading = false; render(); });
                } else {
                    List<HermesApi.Toolset> loaded = api.toolsetList();
                    runOnUiThread(() -> { toolsets = loaded; loading = false; render(); });
                }
            } catch (Exception e) {
                String friendly = HermesApi.friendly(e);
                runOnUiThread(() -> { loading = false; loadError = friendly; render(); });
            }
        });
    }

    private void render() {
        body.removeAllViews();
        if (loading) { body.addView(centeredProgress(), match(160)); return; }
        if (loadError != null) { body.addView(errorBlock(loadError, this::load), match(-2)); return; }
        String q = search == null ? "" : search.getText().toString().trim().toLowerCase(Locale.ROOT);
        if (skillsMode) renderSkills(q); else renderToolsets(q);
    }

    // ---------------------------------------------------------------- skills

    private void renderSkills(String q) {
        List<HermesApi.Skill> filtered = new ArrayList<>();
        for (HermesApi.Skill s : skills) {
            if (q.isEmpty() || contains(s.name, q) || contains(s.description, q) || contains(s.category, q)) filtered.add(s);
        }
        if (skills.isEmpty()) { body.addView(emptyBlock("No skills installed", "This server hasn't reported any skills yet."), match(-2)); return; }
        if (filtered.isEmpty()) { body.addView(emptyBlock("No matches", "Nothing matches \"" + q + "\"."), match(-2)); return; }

        TreeMap<String, List<HermesApi.Skill>> byCategory = new TreeMap<>((a, b2) -> {
            if (a.equals("Other")) return b2.equals("Other") ? 0 : 1;
            if (b2.equals("Other")) return -1;
            return a.compareToIgnoreCase(b2);
        });
        for (HermesApi.Skill s : filtered) {
            String cat = s.category.isEmpty() ? "Other" : s.category;
            byCategory.computeIfAbsent(cat, k -> new ArrayList<>()).add(s);
        }
        for (String cat : byCategory.keySet()) {
            body.addView(categoryLabel(cat + " (" + byCategory.get(cat).size() + ")"), match(-2));
            LinearLayout card = new LinearLayout(this);
            card.setOrientation(LinearLayout.VERTICAL);
            card.setBackground(Ui.round(this, Ui.SURFACE, 16));
            List<HermesApi.Skill> group = byCategory.get(cat);
            for (int i = 0; i < group.size(); i++) {
                card.addView(skillRow(group.get(i)), match(-2));
                if (i < group.size() - 1) divider(card);
            }
            LinearLayout.LayoutParams cp = match(-2);
            cp.setMargins(0, 0, 0, dp(6));
            body.addView(card, cp);
        }
    }

    private View skillRow(HermesApi.Skill skill) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.VERTICAL);
        row.setPadding(dp(16), dp(12), dp(16), dp(12));
        row.setBackground(Ui.ripple(this, Color.TRANSPARENT, 0));
        row.setOnClickListener(v -> showSkill(skill));
        row.addView(Ui.text(this, skill.name.isEmpty() ? "Unnamed skill" : skill.name, 15, Ui.TEXT, true), match(-2));
        if (!skill.description.isEmpty()) {
            TextView d = Ui.text(this, skill.description, 13, Ui.SUB, false);
            d.setMaxLines(2);
            d.setEllipsize(android.text.TextUtils.TruncateAt.END);
            d.setLineSpacing(0, 1.15f);
            d.setPadding(0, dp(4), 0, 0);
            row.addView(d, match(-2));
        }
        return row;
    }

    private void showSkill(HermesApi.Skill skill) {
        String subtitle = (skill.category.isEmpty() || "Other".equals(skill.category) ? "" : skill.category + " · ")
                + (skill.description.isEmpty() ? "No description provided." : skill.description);
        Sheets.actions(this, skill.name.isEmpty() ? "Skill" : skill.name, subtitle,
                Sheets.Item.of(R.drawable.ic_send, Ui.BLUE, "Use in chat", "Prefill the composer with this skill", () -> useInChat(skill.name)));
    }

    private void useInChat(String name) {
        // ChatActivity auto-sends EXTRA_PROMPT unless EXTRA_AUTOSEND is set false, so this drops the
        // composer prefilled rather than firing the message immediately.
        startActivity(new Intent(this, MainActivity.class)
                .putExtra(MainActivity.EXTRA_OPEN_CHAT, true)
                .putExtra(ChatActivity.EXTRA_PROMPT, "Use the " + name + " skill: ")
                .putExtra(ChatActivity.EXTRA_AUTOSEND, false)
                .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP));
        finish();
    }

    // ---------------------------------------------------------------- toolsets

    private void renderToolsets(String q) {
        List<HermesApi.Toolset> filtered = new ArrayList<>();
        for (HermesApi.Toolset t : toolsets) {
            if (q.isEmpty() || contains(t.name, q) || contains(t.description, q)) filtered.add(t);
        }
        if (toolsets.isEmpty()) { body.addView(emptyBlock("No toolsets connected", "This server hasn't reported any toolsets yet."), match(-2)); return; }
        if (filtered.isEmpty()) { body.addView(emptyBlock("No matches", "Nothing matches \"" + q + "\"."), match(-2)); return; }

        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setBackground(Ui.round(this, Ui.SURFACE, 16));
        for (int i = 0; i < filtered.size(); i++) {
            card.addView(toolsetRow(filtered.get(i)), match(-2));
            if (i < filtered.size() - 1) divider(card);
        }
        body.addView(card, match(-2));
    }

    private View toolsetRow(HermesApi.Toolset toolset) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.VERTICAL);
        row.setPadding(dp(16), dp(12), dp(16), dp(12));
        row.setBackground(Ui.ripple(this, Color.TRANSPARENT, 0));

        LinearLayout head = new LinearLayout(this);
        head.setGravity(Gravity.CENTER_VERTICAL);
        head.addView(Ui.text(this, toolset.name.isEmpty() ? "Unnamed toolset" : toolset.name, 15, Ui.TEXT, true), new LinearLayout.LayoutParams(0, -2, 1));
        TextView chip = Ui.text(this, toolset.enabled ? "ON" : "OFF", 10, toolset.enabled ? Ui.GREEN : Ui.FAINT, true);
        chip.setLetterSpacing(.05f);
        chip.setPadding(dp(8), dp(3), dp(8), dp(3));
        chip.setBackground(Ui.stroke(this, Color.TRANSPARENT, toolset.enabled ? Ui.GREEN : Ui.FAINT, 20));
        head.addView(chip);
        row.addView(head, match(-2));

        if (!toolset.description.isEmpty()) {
            TextView d = Ui.text(this, toolset.description, 13, Ui.SUB, false);
            d.setLineSpacing(0, 1.15f);
            d.setPadding(0, dp(4), 0, 0);
            row.addView(d, match(-2));
        }

        boolean expanded = expandedToolsets.contains(toolset.name);
        TextView tools = Ui.text(this, expanded
                ? (toolset.tools.isEmpty() ? "No tools reported" : String.join(", ", toolset.tools))
                : toolset.tools.size() + (toolset.tools.size() == 1 ? " tool" : " tools") + (toolset.tools.isEmpty() ? "" : " · tap to view"), 12, Ui.FAINT, false);
        tools.setLineSpacing(0, 1.2f);
        tools.setPadding(0, dp(6), 0, 0);
        row.addView(tools, match(-2));

        if (!toolset.tools.isEmpty()) row.setOnClickListener(v -> {
            if (expanded) expandedToolsets.remove(toolset.name); else expandedToolsets.add(toolset.name);
            render();
        });
        return row;
    }

    // ---------------------------------------------------------------- shared view helpers

    private View categoryLabel(String text) {
        TextView label = Ui.text(this, text.toUpperCase(Locale.ROOT), 11, Ui.BLUE, true);
        label.setLetterSpacing(.09f);
        LinearLayout.LayoutParams lp = match(-2);
        lp.setMargins(dp(4), dp(16), 0, dp(8));
        label.setLayoutParams(lp);
        return label;
    }

    private void divider(LinearLayout card) {
        View line = new View(this);
        line.setBackgroundColor(Ui.LINE);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, dp(1));
        lp.setMargins(dp(16), 0, dp(16), 0);
        card.addView(line, lp);
    }

    private View centeredProgress() {
        LinearLayout box = new LinearLayout(this);
        box.setGravity(Gravity.CENTER);
        box.setPadding(0, dp(40), 0, 0);
        ProgressBar p = new ProgressBar(this);
        p.setIndeterminateTintList(ColorStateList.valueOf(Ui.BLUE));
        box.addView(p, new LinearLayout.LayoutParams(dp(28), dp(28)));
        return box;
    }

    private View errorBlock(String error, Runnable retry) {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setGravity(Gravity.CENTER_HORIZONTAL);
        box.setPadding(dp(24), dp(40), dp(24), dp(24));
        box.setOnClickListener(v -> retry.run());
        TextView t = Ui.text(this, "Couldn't load this catalog", 16, Ui.TEXT, true);
        t.setGravity(Gravity.CENTER);
        box.addView(t, match(-2));
        String why = error == null ? "" : error;
        if (why.contains("500") && why.toLowerCase(java.util.Locale.ROOT).contains("skills"))
            why += "\n\nThe Hermes server failed while listing skills. This usually means its install is half-updated "
                    + "(api_server.py and tools/skills_tool.py from different versions) — updating Hermes fixes it.";
        TextView s = Ui.text(this, why, 13, Ui.SUB, false);
        s.setGravity(Gravity.CENTER);
        s.setLineSpacing(0, 1.2f);
        s.setPadding(0, dp(8), 0, 0);
        box.addView(s, match(-2));
        TextView r = Ui.text(this, "TAP TO RETRY", 11, Ui.BLUE, true);
        r.setLetterSpacing(.06f);
        r.setGravity(Gravity.CENTER);
        r.setPadding(0, dp(10), 0, 0);
        box.addView(r, match(-2));
        return box;
    }

    private View emptyBlock(String title, String subtitle) {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setGravity(Gravity.CENTER_HORIZONTAL);
        box.setPadding(dp(24), dp(40), dp(24), dp(24));
        TextView t = Ui.text(this, title, 16, Ui.TEXT, true);
        t.setGravity(Gravity.CENTER);
        box.addView(t, match(-2));
        TextView s = Ui.text(this, subtitle, 13, Ui.SUB, false);
        s.setGravity(Gravity.CENTER);
        s.setLineSpacing(0, 1.2f);
        s.setPadding(0, dp(8), 0, 0);
        box.addView(s, match(-2));
        return box;
    }

    private static boolean contains(String haystack, String needle) {
        return haystack != null && haystack.toLowerCase(Locale.ROOT).contains(needle);
    }
    @Override public void onBackPressed() { Ui.back(this); }
}
