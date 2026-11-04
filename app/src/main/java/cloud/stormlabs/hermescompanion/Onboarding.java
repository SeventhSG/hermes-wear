package cloud.stormlabs.hermescompanion;

/** Pure state for the first-run tour (OnboardingActivity): routing and paging. */
final class Onboarding {
    /** Welcome, connect, connected. */
    static final int PAGES = 3;

    private Onboarding() { }

    /** Only a fresh install (no key yet) that has not finished or skipped the tour sees it. */
    static boolean shouldRun(String apiKey, boolean done) { return !done && (apiKey == null || apiKey.isEmpty()); }

    /** Page after moving by delta, kept inside the tour. */
    static int move(int page, int delta) { return Math.max(0, Math.min(PAGES - 1, page + delta)); }

    static boolean isLast(int page) { return page >= PAGES - 1; }

    /** The setup action stays in the fixed footer, clear of scrolling content. */
    static String primaryLabel(boolean connected, boolean hubReady) {
        return !connected ? "Connect to my server" : hubReady ? "Start chatting" : "Set up my server";
    }
}
