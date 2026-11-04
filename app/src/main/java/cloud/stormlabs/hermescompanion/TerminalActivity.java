package cloud.stormlabs.hermescompanion;

import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.HorizontalScrollView;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/**
 * Terminal module: tabs of real shells on the hub, or ssh sessions started from it, with saved hosts, snippets, an
 * extra-keys row (sticky Ctrl/Alt, arrows with repeat, F-keys), pinch zoom, themes, selection and paste. The hub only
 * serves it to your own Tailscale devices, so this works over Tailscale and not through the public address.
 */
public final class TerminalActivity extends Activity implements TermView.Host, TermSession.Listener {
    private final Handler ui = new Handler(Looper.getMainLooper());
    private TermStore store;
    private HubApi hub;
    private TermView view;
    private LinearLayout tabRow, keysBox;
    private HorizontalScrollView tabScroll;
    private TextView status, ctrlKey, altKey;
    private TermSession current;
    private boolean ctrl, alt, ctrlLock, altLock;

    @Override public void onCreate(Bundle b) {
        super.onCreate(b);
        try { build(); } catch (Throwable t) { CrashReporter.show(this, t); finish(); }
    }

    private void build() {
        SettingsStore s = new SettingsStore(this);
        if (s.hubUrl().isEmpty()) { Toast.makeText(this, "Connect the companion hub first", Toast.LENGTH_LONG).show(); finish(); return; }
        s.moduleOn(Modules.TERMINAL, true); // opening the terminal is switching it on
        store = new TermStore(this);
        String tail = Reach.tailnetBase(Reach.hubCandidates(s.hubUrl()));
        if (tail == null) {
            Sheets.confirm(this, "Terminal needs Tailscale", "The hub only serves the terminal over Tailscale, and the app has no Tailscale address for it. Add the hub's Tailscale address (100.x.x.x) under Settings › Backup addresses.",
                    "Open Settings", false, () -> startActivity(new Intent(this, SettingsActivity.class)), this::finish);
            return;
        }
        hub = new HubApi(tail, s.hubKey()).pinned();
        Ui.legacyWindowInsets(this);
        getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
        TermView.Theme theme = TermView.THEMES[store.theme()];
        getWindow().setStatusBarColor(Ui.BG); getWindow().setNavigationBarColor(theme.bg);
        if (store.keepAwake()) getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(theme.bg);
        root.addView(header());
        tabScroll = new HorizontalScrollView(this);
        tabScroll.setHorizontalScrollBarEnabled(false);
        tabRow = new LinearLayout(this);
        tabRow.setPadding(Ui.dp(this, 8), 0, Ui.dp(this, 8), Ui.dp(this, 4));
        tabScroll.addView(tabRow);
        root.addView(tabScroll, new LinearLayout.LayoutParams(-1, -2));
        view = new TermView(this);
        view.theme(theme);
        view.setTextSize(store.fontSp());
        root.addView(view, new LinearLayout.LayoutParams(-1, 0, 1));
        status = Ui.text(this, "", 12, Ui.SUB, false);
        status.setPadding(Ui.dp(this, 12), Ui.dp(this, 2), Ui.dp(this, 12), Ui.dp(this, 2));
        status.setVisibility(View.GONE);
        root.addView(status);
        root.addView(keys());
        setContentView(root);
        Ui.fitInsets(root, root);
        restore();
    }

    /* ---- chrome ---- */
    private View header() {
        LinearLayout bar = new LinearLayout(this);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setPadding(Ui.dp(this, 2), Ui.dp(this, 2), Ui.dp(this, 4), 0);
        ImageButton back = Ui.icon(this, R.drawable.ic_back, "Back");
        back.setOnClickListener(v -> finish());
        bar.addView(back, new LinearLayout.LayoutParams(Ui.dp(this, 44), Ui.dp(this, 48)));
        bar.addView(Ui.text(this, "Terminal", 18, Ui.TEXT, true), new LinearLayout.LayoutParams(0, -2, 1));
        ImageButton add = Ui.icon(this, R.drawable.ic_new, "New session");
        add.setOnClickListener(v -> picker());
        bar.addView(add, new LinearLayout.LayoutParams(Ui.dp(this, 44), Ui.dp(this, 48)));
        ImageButton more = Ui.icon(this, R.drawable.ic_more, "More");
        more.setOnClickListener(v -> menu());
        bar.addView(more, new LinearLayout.LayoutParams(Ui.dp(this, 44), Ui.dp(this, 48)));
        return bar;
    }

    private TextView selKey, mouseKey;
    private boolean lastMouse;

    private View keys() {
        keysBox = new LinearLayout(this);
        keysBox.setOrientation(LinearLayout.VERTICAL);
        keysBox.setBackgroundColor(Ui.BAR);
        rebuildKeys();
        return keysBox;
    }

    /** Draws the key row(s) from the saved layout (Customize keys). */
    private void rebuildKeys() {
        keysBox.removeAllViews();
        ctrlKey = altKey = selKey = mouseKey = null;
        List<TermStore.Entry> custom = store.customKeys();
        List<TermKeyset.Key> all = new ArrayList<>();
        for (String id : store.layout()) { TermKeyset.Key k = TermKeyset.resolve(id, custom); if (k != null) all.add(k); }
        int rows = Math.min(store.keyRows(), Math.max(1, all.size())), per = (all.size() + rows - 1) / rows;
        for (int r = 0; r < rows; r++) {
            HorizontalScrollView scroll = new HorizontalScrollView(this);
            scroll.setHorizontalScrollBarEnabled(false);
            LinearLayout row = new LinearLayout(this);
            row.setPadding(Ui.dp(this, 4), Ui.dp(this, 3), Ui.dp(this, 4), Ui.dp(this, 3));
            for (int i = r * per; i < Math.min(all.size(), (r + 1) * per); i++) row.addView(keyFor(all.get(i)));
            scroll.addView(row);
            keysBox.addView(scroll);
        }
        paintMods();
    }

    private View keyFor(TermKeyset.Key k) {
        switch (k.type) {
            case TermKeyset.MOD: {
                boolean isCtrl = k.id.equals("ctrl");
                TextView t = keyButton(k.label, v -> toggle(isCtrl), v -> { lock(isCtrl); return true; });
                if (isCtrl) ctrlKey = t; else altKey = t;
                return t;
            }
            case TermKeyset.KEY: {
                int code = k.code;
                TextView t = keyButton(k.label, null, null);
                boolean repeats = code >= KeyEvent.KEYCODE_DPAD_UP && code <= KeyEvent.KEYCODE_DPAD_RIGHT || code == KeyEvent.KEYCODE_PAGE_UP || code == KeyEvent.KEYCODE_PAGE_DOWN
                        || code == KeyEvent.KEYCODE_FORWARD_DEL || code == KeyEvent.KEYCODE_DEL;
                t.setOnTouchListener(new View.OnTouchListener() {
                    private final Runnable repeat = new Runnable() { @Override public void run() { fire(code); ui.postDelayed(this, 60); } };
                    @Override public boolean onTouch(View v, MotionEvent e) {
                        switch (e.getActionMasked()) {
                            case MotionEvent.ACTION_DOWN: v.setPressed(true); fire(code); if (repeats) ui.postDelayed(repeat, 380); return true;
                            case MotionEvent.ACTION_UP: case MotionEvent.ACTION_CANCEL: v.setPressed(false); ui.removeCallbacks(repeat); return true;
                            default: return true;
                        }
                    }
                });
                return t;
            }
            case TermKeyset.TEXT: return keyButton(k.label, v -> { input(TermKeys.text(k.data, alt, ctrl)); consume(); }, null);
            case TermKeyset.PAIR: return keyButton(k.label, v -> { input(k.data); input(TermKeys.ESC + "[D"); consume(); }, null);
            case TermKeyset.SEQ: return keyButton(k.label, v -> { input(k.data); consume(); }, null);
            case TermKeyset.CUSTOM: return keyButton(k.label, v -> { input(TermKeyset.parseSequence(k.data)); consume(); }, null);
            default: return action(k);
        }
    }

    private View action(TermKeyset.Key k) {
        switch (k.id) {
            case "kbd": return keyButton(k.label, v -> { view.requestFocus(); view.showKeyboard(); }, null);
            case "snip": return keyButton(k.label, v -> snippets(), null);
            case "paste": return keyButton(k.label, v -> paste(), null);
            case "copy": return keyButton(k.label, v -> { copy(view.allText()); toast("Copied"); }, null);
            case "sel": selKey = keyButton(k.label, v -> { view.localSelect = !view.localSelect; view.clearSelection(); paintMods(); toast(view.localSelect ? "Touch selects text" : "Touch goes to the program again"); }, null); return selKey;
            default: mouseKey = keyButton(k.label, v -> { view.mouseOff = !view.mouseOff; paintMods(); toast(view.mouseOff ? "Mouse off: touch never goes to the program" : "Mouse on when a program asks for it"); }, null); return mouseKey;
        }
    }

    private TextView keyButton(String label, View.OnClickListener click, View.OnLongClickListener lc) {
        TextView t = Ui.text(this, label, 13, Ui.TEXT, false);
        t.setGravity(Gravity.CENTER);
        t.setMinWidth(Ui.dp(this, 44));
        t.setPadding(Ui.dp(this, 10), 0, Ui.dp(this, 10), 0);
        t.setBackground(Ui.press(this, Ui.SURFACE, Ui.LINE, 8));
        t.setContentDescription(label);
        if (click != null) t.setOnClickListener(click);
        if (lc != null) t.setOnLongClickListener(lc);
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(-2, Ui.dp(this, 38));
        p.setMargins(Ui.dp(this, 2), 0, Ui.dp(this, 2), 0);
        t.setLayoutParams(p);
        return t;
    }

    private void fire(int code) {
        String s = TermKeys.special(code, false, alt, ctrl, current != null && current.screen.appCursor);
        if (s != null) input(s);
        consume(); view.toBottom();
    }

    private void toggle(boolean isCtrl) {
        if (isCtrl) { ctrl = !ctrl; ctrlLock = false; } else { alt = !alt; altLock = false; }
        paintMods();
    }

    private void lock(boolean isCtrl) {
        if (isCtrl) { ctrl = ctrlLock = true; } else { alt = altLock = true; }
        paintMods();
    }

    private void paintMods() {
        if (ctrlKey != null) { ctrlKey.setBackground(Ui.press(this, ctrl ? Ui.BLUE : Ui.SURFACE, Ui.LINE, 8)); ctrlKey.setTextColor(ctrl ? 0xffffffff : Ui.TEXT); ctrlKey.setText(ctrlLock ? "CTRL🔒" : "Ctrl"); }
        if (altKey != null) { altKey.setBackground(Ui.press(this, alt ? Ui.BLUE : Ui.SURFACE, Ui.LINE, 8)); altKey.setTextColor(alt ? 0xffffffff : Ui.TEXT); altKey.setText(altLock ? "ALT🔒" : "Alt"); }
        if (selKey != null) { selKey.setBackground(Ui.press(this, view.localSelect ? Ui.BLUE : Ui.SURFACE, Ui.LINE, 8)); selKey.setTextColor(view.localSelect ? 0xffffffff : Ui.TEXT); }
        if (mouseKey != null) {
            boolean on = view.mouseActive();
            mouseKey.setBackground(Ui.press(this, on ? Ui.BLUE : Ui.SURFACE, Ui.LINE, 8)); mouseKey.setTextColor(on ? 0xffffffff : view.mouseOff ? Ui.FAINT : Ui.TEXT);
            mouseKey.setText(view.mouseOff ? "Mouse off" : on ? "Mouse ●" : "Mouse");
        }
        lastMouse = view.mouseActive();
    }

    /* ---- TermView.Host ---- */
    @Override public void input(String s) { if (current != null) current.send(s); }
    @Override public void resized(int rows, int cols) { if (current != null) current.resize(rows, cols); }
    @Override public void zoomed(float sp) { store.fontSp(sp); }
    @Override public void copied(String text) { copy(text); Toast.makeText(this, "Copied", Toast.LENGTH_SHORT).show(); }
    @Override public boolean ctrl() { return ctrl; }
    @Override public boolean alt() { return alt; }
    @Override public void consume() { boolean changed = false; if (ctrl && !ctrlLock) { ctrl = false; changed = true; } if (alt && !altLock) { alt = false; changed = true; } if (changed) ui.post(this::paintMods); }

    private void copy(String t) {
        ClipboardManager m = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
        if (m != null) m.setPrimaryClip(ClipData.newPlainText("terminal", t));
    }

    private void paste() {
        ClipboardManager m = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
        if (m == null || !m.hasPrimaryClip() || m.getPrimaryClip().getItemCount() == 0) { Toast.makeText(this, "Clipboard is empty", Toast.LENGTH_SHORT).show(); return; }
        CharSequence t = m.getPrimaryClip().getItemAt(0).coerceToText(this);
        if (t != null && current != null) input(TermKeys.paste(t.toString(), current.screen.bracketedPaste));
    }

    /* ---- sessions ---- */
    private void restore() {
        synchronized (TermSession.OPEN) {
            if (!TermSession.OPEN.isEmpty()) { for (TermSession s : TermSession.OPEN) s.listener = this; show(TermSession.OPEN.get(0)); return; }
        }
        List<TermStore.Entry> saved = store.tabs();
        if (saved.isEmpty()) { picker(); return; }
        new Thread(() -> {
            List<String[]> alive = new ArrayList<>();
            try {
                JSONArray a = hub.term("GET", "list", null).optJSONArray("sessions");
                for (int i = 0; a != null && i < a.length(); i++) {
                    JSONObject o = a.getJSONObject(i);
                    if (o.optBoolean("alive", true)) alive.add(new String[]{o.getString("id"), o.optString("target"), o.optString("name")});
                }
            } catch (Exception e) { ui.post(() -> { toast("Hub not reachable: " + HermesApi.friendly(e)); }); }
            // the hub restarted: its list is empty but the tmux shells are still there, so ask for the tabs we had by id
            if (alive.isEmpty() && store.keepShells()) for (TermStore.Entry e : saved) alive.add(new String[]{e.name, e.value, e.value});
            ui.post(() -> {
                for (String[] a : alive) { TermSession s = new TermSession(hub, a[1], a[2], a[0], store.keepShells()); s.listener = this; synchronized (TermSession.OPEN) { TermSession.OPEN.add(s); } }
                synchronized (TermSession.OPEN) { if (!TermSession.OPEN.isEmpty()) show(TermSession.OPEN.get(0)); else picker(); }
            });
        }, "term-restore").start();
    }

    private void toast(String m) { Toast.makeText(this, m, Toast.LENGTH_LONG).show(); }

    private void open(String target, String name) {
        if (!TermStore.validTarget(target)) { toast("Not a valid host: use name, user@host or user@host:port"); return; }
        TermSession s = new TermSession(hub, target, name, null, store.keepShells());
        s.listener = this;
        synchronized (TermSession.OPEN) { TermSession.OPEN.add(s); }
        show(s); persist();
    }

    private void show(TermSession s) {
        TermSession old = current;
        current = s;
        view.bind(s.screen, this);
        view.changed();
        tabs();
        s.resize(s.screen.rows, s.screen.cols);
        paintStatus(s);
        paintMods();
        view.requestFocus(); view.showKeyboard();
    }

    private void persist() {
        if (store == null) return;
        List<TermStore.Entry> out = new ArrayList<>();
        synchronized (TermSession.OPEN) { for (TermSession s : TermSession.OPEN) if (!s.id.isEmpty() && !s.ended) out.add(new TermStore.Entry(s.id, s.target)); }
        store.saveTabs(out);
    }

    private void tabs() {
        tabRow.removeAllViews();
        List<TermSession> all; synchronized (TermSession.OPEN) { all = new ArrayList<>(TermSession.OPEN); }
        for (TermSession s : all) {
            boolean on = s == current;
            TextView t = Ui.text(this, (s.ended ? "✕ " : "● ") + s.name, 13, on ? 0xffffffff : Ui.SUB, on);
            t.setPadding(Ui.dp(this, 12), 0, Ui.dp(this, 12), 0);
            t.setGravity(Gravity.CENTER);
            t.setBackground(Ui.ripple(this, on ? Ui.BLUE : Ui.SURFACE, 16));
            t.setOnClickListener(v -> { if (s != current) show(s); else tabMenu(s); });
            t.setOnLongClickListener(v -> { tabMenu(s); return true; });
            LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(-2, Ui.dp(this, 32));
            p.setMargins(Ui.dp(this, 2), Ui.dp(this, 2), Ui.dp(this, 2), 0);
            tabRow.addView(t, p);
        }
    }

    private void tabMenu(TermSession s) {
        Sheets.actions(this, s.name, s.target + " · " + s.state,
                Sheets.Item.of(R.drawable.ic_edit, Ui.BLUE, "Rename", null, () -> Sheets.input(this, "Rename tab", null, s.name, "Name", v -> { s.name = v; tabs(); }, "Save")),
                Sheets.Item.of(R.drawable.ic_copy, Ui.BLUE, "Copy everything on this tab", "Scrollback and screen", () -> { synchronized (s.screen) { copy(s.screen.text()); } toast("Copied"); }),
                Sheets.Item.of(R.drawable.ic_session, Ui.BLUE, "Leave running on the hub", "Close the tab, keep the shell", () -> closeTab(s, false)),
                Sheets.Item.danger(R.drawable.ic_delete, "End session", "Closes the shell", () -> closeTab(s, true)));
    }

    private void closeTab(TermSession s, boolean end) {
        if (end) s.close(); else { s.detach(); synchronized (TermSession.OPEN) { TermSession.OPEN.remove(s); } }
        TermSession next = null;
        synchronized (TermSession.OPEN) { if (!TermSession.OPEN.isEmpty()) next = TermSession.OPEN.get(TermSession.OPEN.size() - 1); }
        persist();
        if (s == current) { current = null; if (next != null) show(next); else { view.bind(new TermScreen(24, 80), this); tabs(); picker(); } } else tabs();
    }

    private void paintStatus(TermSession s) {
        boolean problem = s == current && !"Connected".equals(s.state);
        status.setVisibility(problem ? View.VISIBLE : View.GONE);
        status.setText(s.state);
        status.setTextColor("Connected".equals(s.state) ? Ui.SUB : Ui.RED);
    }

    @Override public void output(TermSession s) {
        if (s != current) return;
        view.changed();
        if (view.mouseActive() != lastMouse) ui.post(this::paintMods);
    }

    @Override public void status(TermSession s) { ui.post(() -> { tabs(); paintStatus(s); persist(); }); }

    /* ---- pickers and menus ---- */
    private void picker() {
        List<Sheets.Item> items = new ArrayList<>();
        items.add(Sheets.Item.of(R.drawable.ic_terminal, Ui.BLUE, "Hub shell", "A shell on the server running Hermes", () -> open("hub", "hub")));
        for (TermStore.Entry h : store.hosts()) items.add(Sheets.Item.of(R.drawable.ic_server, Ui.BLUE, h.name, h.value, () -> open(h.value, h.name)));
        items.add(Sheets.Item.of(R.drawable.ic_link, Ui.BLUE, "Connect to a host…", "ssh from the hub: name, user@host or user@host:port", () ->
                Sheets.input(this, "Connect to", "Reached from the hub, so any machine it can ssh to. Passwords are typed in the terminal.", "", "root@192.168.1.150", v -> {
                    if (!TermStore.validTarget(v)) { toast("Not a valid host"); return; }
                    boolean known = false; for (TermStore.Entry h : store.hosts()) if (h.value.equals(v)) known = true;
                    if (!known) store.saveHost(v, v);
                    open(v, v);
                }, "Connect")));
        if (!store.hosts().isEmpty()) items.add(Sheets.Item.of(R.drawable.ic_edit, Ui.SUB, "Manage saved hosts", null, this::hosts));
        Sheets.actions(this, "New session", "Shells live on the hub, so they keep running when you leave", items.toArray(new Sheets.Item[0]));
        // aliases the hub suggests (terminal-hosts.txt) join the list once they arrive
        new Thread(() -> {
            try {
                JSONArray t = hub.term("GET", "targets", null).optJSONArray("targets");
                List<String> extra = new ArrayList<>();
                for (int i = 0; t != null && i < t.length(); i++) if (!"hub".equals(t.getString(i))) extra.add(t.getString(i));
                if (!extra.isEmpty()) ui.post(() -> { for (String e : extra) { boolean known = false; for (TermStore.Entry h : store.hosts()) if (h.value.equals(e)) known = true; if (!known) store.saveHost(e, e); } });
            } catch (Exception ignored) { }
        }, "term-targets").start();
    }

    private void hosts() {
        List<Sheets.Item> items = new ArrayList<>();
        for (TermStore.Entry h : store.hosts()) items.add(Sheets.Item.of(R.drawable.ic_server, Ui.BLUE, h.name, h.value, () ->
                Sheets.actions(this, h.name, h.value,
                        Sheets.Item.of(R.drawable.ic_play, Ui.BLUE, "Connect", null, () -> open(h.value, h.name)),
                        Sheets.Item.of(R.drawable.ic_edit, Ui.BLUE, "Rename", null, () -> Sheets.input(this, "Rename host", h.value, h.name, "Name", v -> { store.removeHost(h.name); store.saveHost(v, h.value); }, "Save")),
                        Sheets.Item.danger(R.drawable.ic_delete, "Remove", null, () -> store.removeHost(h.name)))));
        Sheets.actions(this, "Saved hosts", null, items.toArray(new Sheets.Item[0]));
    }

    private void snippets() {
        List<Sheets.Item> items = new ArrayList<>();
        for (TermStore.Entry sn : store.snippets()) items.add(Sheets.Item.of(R.drawable.ic_send, Ui.BLUE, sn.name, sn.value, () -> input(TermKeys.text(sn.value, false, false) + "\r")));
        items.add(Sheets.Item.of(R.drawable.ic_new, Ui.BLUE, "New snippet…", "A command you run often", () ->
                Sheets.input(this, "Snippet name", null, "", "e.g. Update", name -> Sheets.input(this, "Command", "Sent with Enter when tapped", "", "apt update && apt upgrade", cmd -> store.saveSnippet(name, cmd), "Save"), "Next")));
        if (!store.snippets().isEmpty()) items.add(Sheets.Item.of(R.drawable.ic_delete, Ui.SUB, "Remove a snippet", null, () -> {
            List<Sheets.Item> rm = new ArrayList<>();
            for (TermStore.Entry sn : store.snippets()) rm.add(Sheets.Item.danger(R.drawable.ic_delete, sn.name, sn.value, () -> store.removeSnippet(sn.name)));
            Sheets.actions(this, "Remove snippet", null, rm.toArray(new Sheets.Item[0]));
        }));
        Sheets.actions(this, "Snippets", "Tap to type it into the terminal", items.toArray(new Sheets.Item[0]));
    }

    private void menu() {
        Sheets.actions(this, "Terminal", current == null ? null : current.name + " · " + current.state,
                Sheets.Item.of(R.drawable.ic_copy, Ui.BLUE, "Copy all", "Scrollback and screen", () -> { copy(view.allText()); toast("Copied"); }),
                Sheets.Item.of(R.drawable.ic_download, Ui.BLUE, "Paste", null, this::paste),
                Sheets.Item.of(R.drawable.ic_refresh, Ui.BLUE, "Clear screen", "Ctrl+L", () -> input("\u000c")),
                Sheets.Item.of(R.drawable.ic_palette, Ui.BLUE, "Theme", TermView.THEMES[store.theme()].name, this::themes),
                Sheets.Item.of(R.drawable.ic_aspect, Ui.BLUE, "Text size", String.format(java.util.Locale.US, "%.0f sp · pinch the screen too", view.textSize()), this::sizes),
                Sheets.Item.of(R.drawable.ic_screen, Ui.BLUE, "Keep screen on", store.keepAwake() ? "On" : "Off", () -> {
                    store.keepAwake(!store.keepAwake());
                    if (store.keepAwake()) getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON); else getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
                }),
                Sheets.Item.of(R.drawable.ic_server, Ui.BLUE, "Keep shells alive", store.keepShells() ? "On · tmux on the hub, survives hub restarts (new tabs)" : "Off · plain shells, scroll on the phone (new tabs)", () -> store.keepShells(!store.keepShells())),
                Sheets.Item.of(R.drawable.ic_tools, Ui.BLUE, "Customize keys", store.layout().size() + " buttons · presets, your own keys", this::keyEditor),
                Sheets.Item.of(R.drawable.ic_server, Ui.BLUE, "Saved hosts", null, this::hosts));
    }

    /* ---- customizing the key row ---- */
    private void keyEditor() {
        Sheets.actions(this, "Customize keys", store.layout().size() + " buttons in " + store.keyRows() + (store.keyRows() == 1 ? " row" : " rows"),
                Sheets.Item.of(R.drawable.ic_skills, Ui.BLUE, "Presets", "Default, Minimal, Shell, Editor, tmux/herdr, Everything", this::presets),
                Sheets.Item.of(R.drawable.ic_new, Ui.BLUE, "Add a button", "Any key a phone keyboard lacks: Esc, Ctrl, F-keys, Ctrl+letters…", () -> addGroups()),
                Sheets.Item.of(R.drawable.ic_edit, Ui.BLUE, "Arrange or remove", "Change the order, take buttons off", () -> arrange(0)),
                Sheets.Item.of(R.drawable.ic_compose, Ui.BLUE, "Make your own button", "A label and keys to send, like ^b c for a tmux window", this::newCustomKey),
                Sheets.Item.of(R.drawable.ic_aspect, Ui.BLUE, "Rows of buttons", store.keyRows() + "", this::rowCount),
                Sheets.Item.of(R.drawable.ic_delete, Ui.SUB, "Delete one of my buttons", store.customKeys().size() + " made", this::deleteCustom),
                Sheets.Item.danger(R.drawable.ic_refresh, "Back to default", null, () -> { store.layout(TermKeyset.preset("Default")); rebuildKeys(); }));
    }

    private void deleteCustom() {
        List<Sheets.Item> items = new ArrayList<>();
        for (TermStore.Entry e : store.customKeys()) items.add(Sheets.Item.danger(R.drawable.ic_delete, e.name, e.value, () -> {
            store.removeCustomKey(e.name);
            List<String> now = store.layout(); now.remove("c:" + e.name); store.layout(now); rebuildKeys();
        }));
        if (items.isEmpty()) { toast("You have not made any buttons"); return; }
        Sheets.actions(this, "Delete a button", null, items.toArray(new Sheets.Item[0]));
    }

    private void presets() {
        Sheets.Item[] items = new Sheets.Item[TermKeyset.PRESET_NAMES.length];
        for (int i = 0; i < items.length; i++) {
            String name = TermKeyset.PRESET_NAMES[i];
            items[i] = Sheets.Item.of(R.drawable.ic_skills, Ui.BLUE, name, TermKeyset.preset(name).size() + " buttons", () -> { store.layout(TermKeyset.preset(name)); rebuildKeys(); toast(name + " keys"); });
        }
        Sheets.actions(this, "Key presets", "Replaces the current row. Your own buttons are kept.", items);
    }

    private void rowCount() {
        Sheets.Item[] items = new Sheets.Item[3];
        for (int i = 0; i < 3; i++) { int n = i + 1; items[i] = Sheets.Item.of(R.drawable.ic_aspect, Ui.BLUE, n + (n == 1 ? " row" : " rows"), n == 1 ? "One scrolling row" : "Buttons split over " + n + " rows", () -> { store.keyRows(n); rebuildKeys(); }).checked(store.keyRows() == n); }
        Sheets.actions(this, "Rows of buttons", null, items);
    }

    private void addGroups() {
        List<String> groups = new ArrayList<>(TermKeyset.groups());
        if (!store.customKeys().isEmpty()) groups.add("Mine");
        Sheets.Item[] items = new Sheets.Item[groups.size()];
        for (int i = 0; i < items.length; i++) { String g = groups.get(i); items[i] = Sheets.Item.of(R.drawable.ic_tools, Ui.BLUE, g, null, () -> addFrom(g)); }
        Sheets.actions(this, "Add a button", "Pick a group", items);
    }

    private void addFrom(String group) {
        List<String> layout = store.layout();
        List<Sheets.Item> items = new ArrayList<>();
        List<TermKeyset.Key> keys = new ArrayList<>();
        if (group.equals("Mine")) { for (TermStore.Entry e : store.customKeys()) keys.add(TermKeyset.resolve("c:" + e.name, store.customKeys())); }
        else for (TermKeyset.Key k : TermKeyset.catalog()) if (k.group.equals(group)) keys.add(k);
        for (TermKeyset.Key k : keys) {
            boolean on = layout.contains(k.id);
            items.add(Sheets.Item.of(R.drawable.ic_tools, Ui.BLUE, k.label, on ? "On the row · tap to remove" : "Tap to add", () -> {
                List<String> now = store.layout();
                if (now.contains(k.id)) now.remove(k.id); else now.add(k.id);
                store.layout(now); rebuildKeys(); addFrom(group);
            }).checked(on));
        }
        Sheets.actions(this, group, "Tap to add or remove", items.toArray(new Sheets.Item[0]));
    }

    private void arrange(int ignored) {
        List<String> layout = store.layout(); List<TermStore.Entry> custom = store.customKeys();
        List<Sheets.Item> items = new ArrayList<>();
        for (int i = 0; i < layout.size(); i++) {
            int at = i; String id = layout.get(i);
            TermKeyset.Key k = TermKeyset.resolve(id, custom);
            if (k == null) continue;
            items.add(Sheets.Item.of(R.drawable.ic_tools, Ui.BLUE, (i + 1) + ".  " + k.label, k.group, () -> Sheets.actions(this, k.label, null,
                    Sheets.Item.of(R.drawable.ic_back, Ui.BLUE, "Move earlier", null, () -> { move(at, at - 1); arrange(0); }),
                    Sheets.Item.of(R.drawable.ic_next, Ui.BLUE, "Move later", null, () -> { move(at, at + 1); arrange(0); }),
                    Sheets.Item.of(R.drawable.ic_pin, Ui.BLUE, "Move to the start", null, () -> { move(at, 0); arrange(0); }),
                    Sheets.Item.danger(R.drawable.ic_delete, "Remove from the row", null, () -> { List<String> now = store.layout(); if (at < now.size()) now.remove(at); store.layout(now); rebuildKeys(); arrange(0); }))));
        }
        Sheets.actions(this, "Arrange buttons", "Tap one to move or remove it", items.toArray(new Sheets.Item[0]));
    }

    private void move(int from, int to) {
        List<String> now = store.layout();
        if (from < 0 || from >= now.size() || to < 0 || to >= now.size()) return;
        now.add(to, now.remove(from));
        store.layout(now); rebuildKeys();
    }

    private void newCustomKey() {
        Sheets.input(this, "Button label", "What the button says", "", "e.g. tmux new", name -> Sheets.input(this, "Keys to send",
                "^b is Ctrl+B, \\e is Esc, \\r is Enter, \\t is Tab. Example: ^b c", "", "^b c", seq -> {
                    store.saveCustomKey(name, seq);
                    List<String> now = store.layout(); if (!now.contains("c:" + name)) now.add("c:" + name);
                    store.layout(now); rebuildKeys(); toast("Added " + name);
                }, "Add"), "Next");
    }

    private void themes() {
        TermView.Theme[] all = TermView.THEMES;
        Sheets.Item[] items = new Sheets.Item[all.length];
        for (int i = 0; i < all.length; i++) { int k = i; items[i] = Sheets.Item.of(R.drawable.ic_palette, all[i].cursor, all[i].name, null, () -> {
            store.theme(k); view.theme(all[k]); ((View) view.getParent()).setBackgroundColor(all[k].bg); getWindow().setNavigationBarColor(all[k].bg);
        }).checked(i == store.theme()); }
        Sheets.actions(this, "Theme", null, items);
    }

    private void sizes() {
        float[] sp = {9, 11, 13, 15, 18, 22};
        Sheets.Item[] items = new Sheets.Item[sp.length];
        for (int i = 0; i < sp.length; i++) { float v = sp[i]; items[i] = Sheets.Item.of(R.drawable.ic_aspect, Ui.BLUE, (int) v + " sp", null, () -> { view.setTextSize(v); store.fontSp(v); }).checked(Math.abs(view.textSize() - v) < 0.5f); }
        Sheets.actions(this, "Text size", null, items);
    }

    @Override protected void onPause() { if (store != null) persist(); super.onPause(); }

    @Override protected void onResume() {
        super.onResume();
        synchronized (TermSession.OPEN) { for (TermSession s : TermSession.OPEN) s.listener = this; }
        if (view != null) view.changed();
    }

    @Override protected void onDestroy() {
        synchronized (TermSession.OPEN) { for (TermSession s : TermSession.OPEN) if (s.listener == this) s.listener = null; }
        super.onDestroy();
    }
}
