package cloud.stormlabs.hermescompanion;

import static org.junit.Assert.*;
import org.junit.Test;
import org.json.JSONObject;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public class LayoutTest {
    @Test public void storedIdsMapBackAndUnknownIsClassic() {
        assertSame(Layout.CLASSIC, Layout.byId(null));
        assertSame(Layout.CLASSIC, Layout.byId(""));
        assertSame(Layout.CLASSIC, Layout.byId("carousel"));
        Set<String> ids = new HashSet<>();
        for (Layout l : Layout.ALL) { assertSame(l, Layout.byId(l.id)); assertTrue(ids.add(l.id)); }
        // Persisted values must not change: ui_layout holds these strings.
        assertEquals(Arrays.asList("classic", "tabs", "dashboard", "focus", "voice"),
                Arrays.asList(Layout.CLASSIC.id, Layout.TABS.id, Layout.DASHBOARD.id, Layout.FOCUS.id, Layout.VOICE.id));
    }

    @Test public void eachLayoutHasItsHome() {
        assertEquals(Layout.Home.CHAT, Layout.CLASSIC.home);
        assertEquals(Layout.Home.CHATS, Layout.TABS.home);
        assertEquals(Layout.Home.DASHBOARD, Layout.DASHBOARD.home);
        assertEquals(Layout.Home.CHAT, Layout.FOCUS.home);
        assertTrue(Layout.CLASSIC.drawer());
        assertFalse(Layout.TABS.drawer()); assertFalse(Layout.DASHBOARD.drawer()); assertFalse(Layout.FOCUS.drawer());
    }

    @Test public void tabRouting() {
        assertNull(Layout.navigate(Layout.Tab.SYSTEM, Layout.Tab.SYSTEM));
        assertEquals(Layout.Tab.CHATS, Layout.navigate(Layout.Tab.SYSTEM, Layout.Tab.CHATS));
        assertEquals(Layout.Tab.ASSISTANT, Layout.navigate(null, Layout.Tab.ASSISTANT));
        assertEquals(8, Layout.Tab.values().length);
        assertEquals("Chats", Layout.Tab.values()[0].label);
    }

    @Test public void backLeavesOnlyFromTabsRoots() {
        for (Layout.Tab t : Layout.Tab.values()) assertTrue(Layout.backLeavesApp(Layout.TABS, t));
        assertFalse(Layout.backLeavesApp(Layout.TABS, null));
        assertFalse(Layout.backLeavesApp(Layout.CLASSIC, Layout.Tab.SYSTEM));
        assertFalse(Layout.backLeavesApp(Layout.DASHBOARD, Layout.Tab.ASSISTANT));
        assertTrue(Layout.homeUnderChat(Layout.DASHBOARD));
        assertFalse(Layout.homeUnderChat(Layout.CLASSIC));
        assertFalse(Layout.homeUnderChat(Layout.TABS));
        assertFalse(Layout.homeUnderChat(Layout.FOCUS));
    }

    @Test public void tabListIsValidatedAndCapped() {
        assertEquals(Layout.DEFAULT_TABS, Layout.parseTabs(null));
        assertEquals(Layout.DEFAULT_TABS, Layout.parseTabs(""));
        assertEquals(Layout.DEFAULT_TABS, Layout.parseTabs("CHATS,MEDIA"));            // too few
        assertEquals(Arrays.asList(Layout.Tab.HOME, Layout.Tab.CHATS, Layout.Tab.LIVE), Layout.parseTabs("home, chats ,bogus,LIVE,chats"));
        assertEquals(5, Layout.parseTabs("CHATS,ASSISTANT,CAPTURE,AGENTS,MEDIA,SYSTEM,HOME").size());
        assertEquals("CHATS,LIVE,HOME", Layout.tabsToString(Arrays.asList(Layout.Tab.CHATS, Layout.Tab.LIVE, Layout.Tab.HOME)));
        assertEquals(5, Layout.DEFAULT_TABS.size());
    }

    @Test public void voiceLayoutLandsInTheChatWithoutADrawer() {
        assertEquals(Layout.Home.CHAT, Layout.VOICE.home);
        assertFalse(Layout.VOICE.drawer());
        assertFalse(Layout.backLeavesApp(Layout.VOICE, Layout.Tab.CHATS));
    }

    private static HermesApi.SessionInfo session(String id, boolean unread) throws Exception {
        return new HermesApi.SessionInfo(new JSONObject().put("id", id).put("title", id).put("unread", unread));
    }

    @Test public void needsYouPicksUnreadApprovalsAndProposalsInOrder() throws Exception {
        List<HermesApi.SessionInfo> all = Arrays.asList(session("a", false), session("b", true), session("c", false), session("d", false), session("e", false));
        Set<String> proposals = new HashSet<>(Collections.singletonList("d"));
        List<HermesApi.SessionInfo> picked = ConversationList.needsYou(all, id -> id.equals("c"), proposals);
        assertEquals(3, picked.size());
        assertEquals("b", picked.get(0).id); assertEquals("c", picked.get(1).id); assertEquals("d", picked.get(2).id);
        assertTrue(ConversationList.needsYou(all, id -> false, Collections.emptySet()).stream().allMatch(s -> s.unread));
    }
}
