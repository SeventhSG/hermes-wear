package cloud.stormlabs.hermescompanion;

/**
 * Wakes a sleeping voice server before the first word is spoken: opening the chat, the voice sheet, the
 * wake word and the assistant all nudge the hub, which sends Wake-on-LAN only if the machine is down.
 */
final class KitWake {
    private KitWake() { }

    static final long THROTTLE_MS = 3 * 60_000L;
    private static long last;

    static boolean due(long now, long lastPoke) { return lastPoke == 0 || now - lastPoke >= THROTTLE_MS || now < lastPoke; }

    /** Fire and forget; a hub that is missing, too old, or unreachable is simply ignored. */
    static void poke(SettingsStore store) {
        final String url = store.hubUrl(), key = store.hubKey();
        if (url.isEmpty() || key.isEmpty() || !store.moduleOn(Modules.KIT_WAKE)) return;
        synchronized (KitWake.class) {
            long now = System.currentTimeMillis();
            if (!due(now, last)) return;
            last = now;
        }
        Thread t = new Thread(() -> { try { new HubApi(url, key).wake(); } catch (Exception ignored) { } }, "kit-wake");
        t.setDaemon(true);
        t.start();
    }
}
