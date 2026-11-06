package cloud.stormlabs.hermescompanion;

import android.app.Activity;
import android.content.Intent;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Switching between saved connections: other Hermes profiles on the same gateway (served at
 * {@code /p/<profile>} when it multiplexes them), another Hermes server, or a Claude Code bridge.
 */
final class Profiles {
    private Profiles() {}

    private static final Pattern PROFILE_PATH = Pattern.compile("/p/([^/?#]+)/?$");

    /** "Worker" for …/p/worker; otherwise "Hermes" (the host is shown beside it anyway). */
    static String defaultName(String endpoint) {
        Matcher m = PROFILE_PATH.matcher(UrlUtils.normalize(endpoint));
        if (!m.find()) return "Hermes";
        String p = m.group(1);
        return p.substring(0, 1).toUpperCase(Locale.ROOT) + p.substring(1);
    }

    /** host:port/path without the scheme, for subtitles. */
    static String where(String endpoint) {
        String url = UrlUtils.normalize(endpoint);
        int s = url.indexOf("://");
        return s < 0 ? url : url.substring(s + 3);
    }

    /** The gateway root with any /p/<profile> suffix removed. */
    static String serverRoot(String endpoint) { return PROFILE_PATH.matcher(UrlUtils.normalize(endpoint)).replaceFirst(""); }
    static boolean needsProfileName(String endpoint) { return UrlUtils.normalize(endpoint).endsWith("/p"); }

    /** Wrap-around index for stepping {@code delta} positions (±1) through {@code size} profiles. */
    static int nextIndex(int current, int delta, int size) {
        if (size <= 0) return 0;
        return ((current + delta) % size + size) % size;
    }

    static void show(Activity a) { show(a, () -> {}); }
    static void show(Activity a, Runnable changed) {
        SettingsStore store = new SettingsStore(a);
        if (store.dedupeProfiles() > 0) changed.run();
        List<SettingsStore.Profile> all = store.profilesForHarness();
        boolean hermesHarness = "Hermes".equals(store.serverType());
        List<Sheets.Item> items = new ArrayList<>();
        String root = serverRoot(store.endpoint());
        boolean hasMain = false;
        for (SettingsStore.Profile p : all) if ("Hermes".equals(p.type) && root.equals(UrlUtils.normalize(p.endpoint))) hasMain = true;
        if (!hasMain && "Hermes".equals(store.serverType())) items.add(Sheets.Item.of(R.drawable.ic_new, Ui.AMBER, "Restore main profile",
                where(root) + " · fetches its key over Tailscale", () -> restoreMain(a, root, changed)));
        for (SettingsStore.Profile p : all) {
            boolean current = p.id.equals(store.profileId());
            String sub = ("Hermes".equals(p.type) ? "" : p.type + " · ") + where(p.endpoint);
            items.add(Sheets.Item.of(R.drawable.ic_session, 0, p.name, sub, () -> { if (!current) switchTo(a, p.id); })
                    .avatar(SwitchOverlay.accent(p.id)).checked(current)
                    .bind((chip, subView) -> FleetHealth.watch(a, p.id, st -> FleetHealth.paint(chip, subView, sub, st))));
        }
        if (!hermesHarness) {
            // Hermes profiles (/p/<name>) only exist under the Hermes harness.
            items.add(Sheets.Item.of(R.drawable.ic_assistant, Ui.BLUE_DARK, "Switch to Hermes", "Its profiles live there", () -> Harness.switchTo(a, "Hermes")));
            Sheets.actions(a, store.serverType() + " connections", "Profiles belong to Hermes; " + store.serverType() + " is its own harness", items.toArray(new Sheets.Item[0]));
            return;
        }
        items.add(Sheets.Item.of(R.drawable.ic_new, Ui.BLUE, "Add profile", "Another Hermes profile or server", () -> add(a, changed)));
        if (all.size() > 1) items.add(Sheets.Item.of(R.drawable.ic_settings, Ui.SUB, "Remove a profile", null, () -> remove(a, changed)));
        Sheets.actions(a, "Profiles", "Talk to another Hermes profile or server", items.toArray(new Sheets.Item[0]));
    }

    /** Offer this server's own profiles (keys come from the hub, main key only), else manual entry. */
    private static void add(Activity a, Runnable changed) {
        SettingsStore store = new SettingsStore(a);
        String root = serverRoot(store.endpoint());
        new Thread(() -> {
            List<String[]> offered = new ArrayList<>();
            try {
                java.util.Set<String> have = new java.util.HashSet<>();
                for (SettingsStore.Profile p : store.profiles()) have.add(UrlUtils.normalize(p.endpoint));
                for (String[] p : new HubApi(store.hubUrl(), store.hubKey()).serverProfiles())
                    if (!have.contains(root + "/p/" + p[0])) offered.add(p);
            } catch (Exception ignored) {
                // Older hub or unreachable: manual entry still works.
            }
            a.runOnUiThread(() -> {
                if (a.isFinishing() || a.isDestroyed()) return;
                if (offered.isEmpty()) { manual(a); return; }
                List<Sheets.Item> items = new ArrayList<>();
                for (String[] p : offered) {
                    String name = p[0].substring(0, 1).toUpperCase(Locale.ROOT) + p[0].substring(1);
                    items.add(Sheets.Item.of(R.drawable.ic_session, Ui.BLUE, name, "On this server · switch to it",
                            () -> new Thread(() -> {
                                try {
                                    store.addProfile(name, "Hermes", root + "/p/" + p[0], p[1]);
                                    a.runOnUiThread(() -> restart(a));
                                } catch (IllegalStateException e) {
                                    a.runOnUiThread(() -> { if (!a.isFinishing() && !a.isDestroyed()) CrashReporter.show(a, e); });
                                }
                            }, "profiles-save").start()));
                }
                if (offered.size() > 1) items.add(Sheets.Item.of(R.drawable.ic_new, Ui.GREEN, "Add all " + offered.size(), "Keep " + store.profileName() + " open",
                        () -> {
                            new Thread(() -> {
                                try {
                                    for (String[] p : offered) store.saveProfile(p[0].substring(0, 1).toUpperCase(Locale.ROOT) + p[0].substring(1), "Hermes", root + "/p/" + p[0], p[1]);
                                    a.runOnUiThread(() -> {
                                        if (a.isFinishing() || a.isDestroyed()) return;
                                        changed.run();
                                        android.widget.Toast.makeText(a, "Added " + offered.size() + " profiles", android.widget.Toast.LENGTH_SHORT).show();
                                        show(a, changed);
                                    });
                                } catch (IllegalStateException e) {
                                    a.runOnUiThread(() -> { if (!a.isFinishing() && !a.isDestroyed()) { changed.run(); CrashReporter.show(a, e); } });
                                }
                            }, "profiles-save").start();
                        }));
                items.add(Sheets.Item.of(R.drawable.ic_link, Ui.SUB, "Another server", "Enter its URL and key", () -> manual(a)));
                Sheets.actions(a, "Add profile", "Profiles on " + where(root), items.toArray(new Sheets.Item[0]));
            });
        }, "profiles").start();
    }

    /** Puts back the server's main (non-/p/) profile, e.g. after its settings were overwritten with a /p/ profile's. */
    private static void restoreMain(Activity a, String root, Runnable changed) {
        SettingsStore store = new SettingsStore(a);
        new Thread(() -> {
            String error = null;
            try { store.saveProfile("Hermes", "Hermes", root, Pairing.mainKey(store)); }
            catch (Exception e) { error = e.getMessage(); }
            final String err = error;
            a.runOnUiThread(() -> {
                if (a.isFinishing() || a.isDestroyed()) return;
                android.widget.Toast.makeText(a, err == null ? "Main profile restored" : err, android.widget.Toast.LENGTH_LONG).show();
                if (err == null) { changed.run(); show(a, changed); }
            });
        }, "profiles-restore").start();
    }

    private static void manual(Activity a) {
        a.startActivity(new Intent(a, ConnectionActivity.class).putExtra(ConnectionActivity.EXTRA_NEW_PROFILE, true));
    }

    private static void remove(Activity a, Runnable changed) {
        SettingsStore store = new SettingsStore(a);
        List<Sheets.Item> items = new ArrayList<>();
        for (SettingsStore.Profile p : store.profiles()) {
            if (p.id.equals(store.profileId())) continue;
            items.add(Sheets.Item.danger(R.drawable.ic_session, "Remove " + p.name, where(p.endpoint), () ->
                    Sheets.confirm(a, "Remove " + p.name + "?", "Its saved API key is deleted from this phone. Conversations stay on the server.",
                            "Remove", true, () -> { store.removeProfile(p.id); changed.run(); })));
        }
        Sheets.actions(a, "Remove a profile", "The current profile can't be removed", items.toArray(new Sheets.Item[0]));
    }

    static void switchTo(Activity a, String id) { switchTo(a, id, null, 1); }

    /** Flood overlay from {@code origin}, then switch and restart; a failed switch fades the overlay back out. */
    static void switchTo(Activity a, String id, android.view.View origin, int dir) {
        String name = "Hermes";
        for (SettingsStore.Profile p : new SettingsStore(a).profiles()) if (p.id.equals(id)) name = p.name;
        SwitchOverlay.play(a, origin, name, id, dir, overlay -> new Thread(() -> {
            if (new SettingsStore(a).switchProfile(id)) a.runOnUiThread(() -> restart(a, true));
            else a.runOnUiThread(() -> { overlay.dismiss(); android.widget.Toast.makeText(a, "Couldn't switch profile (removed or already active)", android.widget.Toast.LENGTH_SHORT).show(); });
        }, "profiles-switch").start());
    }

    /** Every screen caches its client and session, so re-enter through the router with a clean task. */
    static void restart(Activity a) { restart(a, false); }

    /** {@code switching}: MainActivity/ChatActivity repeat the switch overlay so the hand-off has no visible cut. */
    static void restart(Activity a, boolean switching) {
        a.startActivity(new Intent(a, MainActivity.class).putExtra(MainActivity.EXTRA_OPEN_CHAT, true).putExtra(MainActivity.EXTRA_SWITCHING, switching)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK | Intent.FLAG_ACTIVITY_NO_ANIMATION));
        a.finish();
        a.overridePendingTransition(0, 0);
    }
}
