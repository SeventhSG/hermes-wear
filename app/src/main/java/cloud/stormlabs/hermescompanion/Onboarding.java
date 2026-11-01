package cloud.stormlabs.hermescompanion;

/** Pure state for the first-run tour (OnboardingActivity): routing, paging and permission-row states. */
final class Onboarding {
    /** Welcome, what you need, tour 1, tour 2, permissions, connect. */
    static final int PAGES = 6, PERMISSIONS_PAGE = 4;
    static final int GRANTED = 0, ASK = 1, BLOCKED = 2;

    private Onboarding() { }

    /** Only a fresh install (no key yet) that has not finished or skipped the tour sees it. */
    static boolean shouldRun(String apiKey, boolean done) { return !done && (apiKey == null || apiKey.isEmpty()); }

    /** Page after moving by delta, kept inside the tour. */
    static int move(int page, int delta) { return Math.max(0, Math.min(PAGES - 1, page + delta)); }

    static boolean isLast(int page) { return page >= PAGES - 1; }

    /** Granted wins; a request the system will no longer show sends the user to App info instead. */
    static int permState(boolean granted, boolean blocked) { return granted ? GRANTED : blocked ? BLOCKED : ASK; }

    static String actionLabel(int state) { return state == GRANTED ? "Granted ✓" : state == BLOCKED ? "App info" : "Allow"; }

    /** The last page's main button. */
    static String primaryLabel(boolean connected) { return connected ? "Done" : "Connect to my server"; }
}
