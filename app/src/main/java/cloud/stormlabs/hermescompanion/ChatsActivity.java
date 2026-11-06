package cloud.stormlabs.hermescompanion;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Full-screen conversation list: the Tabs layout's Chats tab and home. */
public final class ChatsActivity extends Activity {
    private final ExecutorService network = Executors.newCachedThreadPool();
    private SettingsStore store;
    private AgentBackend api;
    // createSession() is a bare async call with no dedupe: a few fast taps on "+ New"
    // before the first one navigates away each created their own real, persisted session.
    private boolean creatingNewChat;
    private ConversationList conversations;
    private View profileLogo; private float logoDownX; private boolean logoSwiping;

    @Override public void onCreate(Bundle b) {
        super.onCreate(b);
        CrashReporter.install(this);
        Ui.legacyWindowInsets(this);
        store = new SettingsStore(this);
        if (store.apiKey().isEmpty()) { startActivity(new Intent(this, ConnectionActivity.class)); finish(); return; }
        api = Backends.create(store);
        getWindow().setStatusBarColor(Ui.BAR); getWindow().setNavigationBarColor(Ui.BG);
        LinearLayout root = new LinearLayout(this); root.setOrientation(LinearLayout.VERTICAL); Ui.ambient(root);
        LinearLayout bar = new LinearLayout(this); bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setPadding(Ui.dp(this, 16), 0, Ui.dp(this, 4), 0); bar.setBackgroundColor(Ui.BAR); bar.setMinimumHeight(Ui.dp(this, 56));
        profileLogo = Ui.hermesLogo(this, 36);
        profileLogo.setBackground(SwitchOverlay.ring(SwitchOverlay.accent(store.profileId()), Ui.dp(this, 2)));
        profileLogo.setContentDescription("Switch profile, current " + store.profileName());
        profileLogo.setOnClickListener(v -> switchProfileRelative(1));
        profileLogo.setOnLongClickListener(v -> { Profiles.show(this); return true; });
        profileLogo.setOnTouchListener((v, e) -> {
            if (e.getAction() == MotionEvent.ACTION_DOWN) { logoDownX = e.getX(); logoSwiping = false; return false; }
            if (e.getAction() == MotionEvent.ACTION_MOVE && Math.abs(e.getX() - logoDownX) > Ui.dp(this, 20)) logoSwiping = true;
            if (e.getAction() == MotionEvent.ACTION_UP || e.getAction() == MotionEvent.ACTION_CANCEL) {
                float dx = e.getX() - logoDownX;
                if (logoSwiping && Math.abs(dx) > Ui.dp(this, 42)) { switchProfileRelative(dx < 0 ? 1 : -1); return true; }
            }
            return false;
        });
        LinearLayout.LayoutParams logoParams = new LinearLayout.LayoutParams(Ui.dp(this, 36), Ui.dp(this, 36));
        logoParams.setMargins(0, 0, Ui.dp(this, 10), 0);
        bar.addView(profileLogo, logoParams);
        Ui.touchTarget(profileLogo, bar);
        LinearLayout names = new LinearLayout(this); names.setOrientation(LinearLayout.VERTICAL);
        names.addView(Ui.text(this, "Chats", 19, Ui.TEXT, true));
        names.addView(Ui.text(this, store.profileName() + " · tap to switch", 12, Ui.SUB, false));
        names.setContentDescription("Profile " + store.profileName() + ", tap to switch");
        names.setOnClickListener(v -> Profiles.show(this));
        bar.addView(names, new LinearLayout.LayoutParams(0, -2, 1));
        ImageButton settings = Ui.icon(this, R.drawable.ic_settings, "Settings");
        settings.setOnClickListener(v -> startActivity(new Intent(this, SettingsActivity.class)));
        bar.addView(settings, new LinearLayout.LayoutParams(Ui.dp(this, 48), Ui.dp(this, 48)));
        TextView add = Ui.text(this, "+ New", 14, Ui.BLUE, true);
        add.setGravity(Gravity.CENTER); add.setPadding(Ui.dp(this, 12), 0, Ui.dp(this, 12), 0);
        add.setBackground(Ui.stroke(this, Ui.RAISED, Ui.LINE, 99)); add.setContentDescription("New conversation");
        add.setOnClickListener(v -> newConversation());
        LinearLayout.LayoutParams ap = new LinearLayout.LayoutParams(-2, Ui.dp(this, 48)); ap.setMargins(Ui.dp(this, 4), 0, Ui.dp(this, 8), 0);
        bar.addView(add, ap);
        root.addView(bar, new LinearLayout.LayoutParams(-1, -2));
        conversations = new ConversationList(this, api, store.profileId(), network, new ConversationList.Host() {
            public String currentSessionId() { return store.sessionId(); }
            public void open(HermesApi.SessionInfo si) { openChat(si.id, si.displayTitle(), si.model); }
            public void openMessage(String id, String title) { openChat(id, title, ""); }
            public void renamed(String id, String title) {}
            public void removed(String id) { if (id.equals(store.sessionId())) store.sessionId(""); }
        });
        conversations.addTo(root);
        Nav.tabBar(this, root, Layout.Tab.CHATS);
        setContentView(root);
        Ui.fitInsets(root, root);
    }

    @Override protected void onResume() {
        super.onResume();
        if (conversations != null) conversations.load(); // unread flags, runs and titles change while away
    }

    private void openChat(String id, String title, String model) {
        store.sessionId(id);
        startActivity(Nav.chat(this, id, title, model));
        overridePendingTransition(0, 0);
    }

    /** Tap/swipe on the header logo: step to the next (delta 1) or previous (delta -1) saved profile. */
    private void switchProfileRelative(int delta) {
        List<SettingsStore.Profile> profiles = store.profilesForHarness();
        if (profiles.size() < 2) { Toast.makeText(this, "Hermes".equals(store.serverType()) ? "Add another profile in Settings › Profile" : "Profiles are Hermes-only: switch the harness to Hermes", Toast.LENGTH_SHORT).show(); return; }
        int current = 0;
        for (int i = 0; i < profiles.size(); i++) if (profiles.get(i).id.equals(store.profileId())) current = i;
        int next = Profiles.nextIndex(current, delta, profiles.size());
        Profiles.switchTo(this, profiles.get(next).id, profileLogo, delta);
    }

    private void newConversation() {
        if (creatingNewChat) return;
        creatingNewChat = true;
        network.submit(() -> {
            try { HermesApi.SessionInfo s = api.createSession("New conversation"); runOnUiThread(() -> { creatingNewChat = false; openChat(s.id, s.displayTitle(), s.model); }); }
            catch (Exception e) { runOnUiThread(() -> { creatingNewChat = false; Toast.makeText(this, HermesApi.friendly(e), Toast.LENGTH_SHORT).show(); }); }
        });
    }

    @Override protected void onDestroy() { super.onDestroy(); if (conversations != null) conversations.close(); network.shutdownNow(); }

    @Override public void onBackPressed() {
        if (Nav.leaveFromTab(this, Layout.Tab.CHATS)) return;
        Ui.back(this);
    }
}
