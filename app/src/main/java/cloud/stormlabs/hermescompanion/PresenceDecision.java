package cloud.stormlabs.hermescompanion;

/** Pure place transition and command cooldown decision. */
final class PresenceDecision {
    static final long COOLDOWN_MS = 10 * 60 * 1000L;

    /** Android reports the current state right after a proximity alert is (re)registered (boot, edit, enable). */
    static final long REGISTRATION_WINDOW_MS = 30_000L;

    static boolean shouldRun(boolean inside, int lastState, long lastRun, long registeredAt, long now) {
        if (registeredAt > 0 && now >= registeredAt && now - registeredAt < REGISTRATION_WINDOW_MS) return false;
        return lastState != (inside ? 1 : 0)
                && (lastRun <= 0 || now < lastRun || now - lastRun >= COOLDOWN_MS);
    }
}
