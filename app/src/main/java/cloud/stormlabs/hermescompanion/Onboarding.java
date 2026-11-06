package cloud.stormlabs.hermescompanion;

/** Pure state for the first-run tour (OnboardingActivity): routing and paging. */
final class Onboarding {
    /** Welcome, connect, connected. */
    static final int PAGES = 3;

    private Onboarding() { }

    /**
     * Hermes Bridge skips the welcome tour: its "Connect your server" page was followed by the connection
     * form, so a fresh install showed the same step twice. A missing key goes straight to the form instead
     * (MainActivity opens ConnectionActivity when there is no key).
     */
    static boolean shouldRun(String apiKey, boolean done) { return false; }

    /** Page after moving by delta, kept inside the tour. */
    static int move(int page, int delta) { return Math.max(0, Math.min(PAGES - 1, page + delta)); }

    static boolean isLast(int page) { return page >= PAGES - 1; }

    /** The setup action stays in the fixed footer, clear of scrolling content. */
    static String primaryLabel(boolean connected, boolean hubReady) {
        return !connected ? "Connect to my server" : hubReady ? "Start chatting" : "Set up my server";
    }
}
