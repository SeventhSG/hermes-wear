package cloud.stormlabs.hermescompanion;

import android.app.Activity;
import android.content.Intent;

import java.util.ArrayList;
import java.util.List;

/**
 * Switching the agent harness (Hermes, Claude Code, Codex) from the chat in one tap, instead of Settings › Advanced ›
 * Server type. Each harness keeps its own address, key and last conversation. A harness this profile has no address for
 * but another saved profile does (Codex paired over Tailscale is its own profile) switches to that profile.
 */
final class Harness {
    private Harness() {}

    static final String[] ALL = {"Hermes", "Claude Code", "Codex"};

    static String what(String type) {
        switch (type) {
            case "Codex": return "Your Codex sessions on the server, live";
            case "Claude Code": return "Claude Code through the bridge";
            default: return "Your Hermes agent";
        }
    }

    static void picker(Activity a) {
        SettingsStore store = new SettingsStore(a);
        List<Sheets.Item> items = new ArrayList<>();
        for (String type : ALL) {
            boolean current = type.equals(store.serverType());
            SettingsStore.Profile elsewhere = elsewhere(store, type);
            boolean ready = store.harnessReady(type) || elsewhere != null;
            String sub = current ? "In use" : ready ? what(type) : "Not set up yet: tap to connect";
            items.add(Sheets.Item.of(type.equals("Hermes") ? R.drawable.ic_assistant : R.drawable.ic_session, Ui.BLUE_DARK, Brand.name(store.brandName(), type), sub,
                    () -> { if (!current) switchTo(a, type); }).checked(current));
        }
        Sheets.actions(a, "Harness", "Which agent this chat talks to", items.toArray(new Sheets.Item[0]));
    }

    /** A saved profile (not the current one) whose harness is {@code type}, or null. */
    private static SettingsStore.Profile elsewhere(SettingsStore store, String type) {
        for (SettingsStore.Profile p : store.profiles()) if (type.equals(p.type) && !p.id.equals(store.profileId())) return p;
        return null;
    }

    static void switchTo(Activity a, String type) {
        SettingsStore store = new SettingsStore(a);
        if (!store.harnessReady(type)) {
            SettingsStore.Profile p = elsewhere(store, type);
            if (p != null) { Profiles.switchTo(a, p.id); return; }
            store.serverType(type);  // not set up: connect it, then the chat opens on it
            a.startActivity(new Intent(a, ConnectionActivity.class).putExtra(ConnectionActivity.EXTRA_OPEN_NEW_CHAT, true));
            return;
        }
        store.serverType(type);
        Reach.configure(store);
        a.startActivity(new Intent(a, MainActivity.class).putExtra(MainActivity.EXTRA_OPEN_CHAT, true)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK | Intent.FLAG_ACTIVITY_NO_ANIMATION));
        a.finish();
        a.overridePendingTransition(0, 0);
    }
}
