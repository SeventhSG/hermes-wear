package cloud.stormlabs.hermescompanion;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * A switchable navigation structure (Settings › Appearance › Layout), independent of the colour theme.
 * Pure data and routing rules so they can be unit-tested; Nav turns them into intents.
 */
public final class Layout {
    /** Where the app lands: the last conversation, the conversation list, or the dashboard. */
    public enum Home { CHAT, CHATS, DASHBOARD }

    /** Bottom-bar destinations of the Tabs layout, in bar order. */
    public enum Tab {
        CHATS("Chats"), ASSISTANT("Assistant"), CAPTURE("Capture"), AGENTS("Agents"), MEDIA("Media"), SYSTEM("System"),
        HOME("Home"), LIVE("Live notes");
        public final String label;
        Tab(String label) { this.label = label; }
    }

    /** What the bar shows until the user picks their own (five fit comfortably at a readable label size). */
    public static final List<Tab> DEFAULT_TABS = Collections.unmodifiableList(Arrays.asList(Tab.CHATS, Tab.ASSISTANT, Tab.CAPTURE, Tab.AGENTS, Tab.SYSTEM));
    public static final int MIN_TABS = 3, MAX_TABS = 5;

    /** Stored tab list → the bar: unknown names and repeats dropped, kept to 3-5 entries, default five when unusable. */
    public static List<Tab> parseTabs(String csv) {
        List<Tab> out = new ArrayList<>();
        if (csv != null) for (String part : csv.split(",")) {
            for (Tab t : Tab.values()) if (t.name().equalsIgnoreCase(part.trim()) && !out.contains(t)) out.add(t);
            if (out.size() == MAX_TABS) break;
        }
        return out.size() < MIN_TABS ? DEFAULT_TABS : out;
    }

    public static String tabsToString(List<Tab> tabs) {
        StringBuilder b = new StringBuilder();
        for (Tab t : tabs) b.append(b.length() == 0 ? "" : ",").append(t.name());
        return b.toString();
    }

    public final String id, name, blurb;
    public final Home home;

    private Layout(String id, String name, String blurb, Home home) {
        this.id = id; this.name = name; this.blurb = blurb; this.home = home;
    }

    public static final Layout CLASSIC = new Layout("classic", "Classic", "Chat first, conversations and tools in the side drawer", Home.CHAT);
    public static final Layout TABS = new Layout("tabs", "Tabs", "A bottom bar of the five places you use most; choose which in Settings", Home.CHATS);
    public static final Layout DASHBOARD = new Layout("dashboard", "Dashboard", "Home screen of cards; chats open full screen", Home.DASHBOARD);
    public static final Layout FOCUS = new Layout("focus", "Focus", "Just the conversation; everything else in a command palette", Home.CHAT);

    public static final Layout VOICE = new Layout("voice", "Voice", "Walkie-talkie chat: a big hold-to-talk button, type only when you want to", Home.CHAT);

    public static final Layout[] ALL = {CLASSIC, TABS, DASHBOARD, FOCUS, VOICE};

    /** Stored id → layout; anything unknown (or missing) is Classic. */
    public static Layout byId(String id) {
        for (Layout l : ALL) if (l.id.equals(id)) return l;
        return CLASSIC;
    }

    /** Chat header keeps the hamburger drawer only in Classic. */
    public boolean drawer() { return this == CLASSIC; }

    /** The pressed tab to open, or null when it is already the current one (no new screen, no restack). */
    public static Tab navigate(Tab current, Tab pressed) { return pressed == current ? null : pressed; }

    /** Back on a screen: true when it should leave the app (a Tabs root) rather than pop or cycle. */
    public static boolean backLeavesApp(Layout layout, Tab screen) { return layout == TABS && screen != null; }

    /** A conversation opened by a notification or link keeps the layout's home screen underneath (Dashboard). */
    public static boolean homeUnderChat(Layout layout) { return layout.home == Home.DASHBOARD; }
}
