package cloud.stormlabs.hermescompanion;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class ChatChromeTest {
    @Test public void edgeAlphaRampsAndClamps() {
        assertEquals(0f, ChatChrome.edgeAlpha(0, 24), 0f);
        assertEquals(0f, ChatChrome.edgeAlpha(-5, 24), 0f);
        assertEquals(.5f, ChatChrome.edgeAlpha(12, 24), 1e-6f);
        assertEquals(1f, ChatChrome.edgeAlpha(100, 24), 0f);
        assertEquals(1f, ChatChrome.edgeAlpha(1, 0), 0f);
    }

    @Test public void toolSummaryPluralisesAndCountsFailures() {
        assertEquals("Used 1 tool", ChatChrome.toolSummary(1, 0));
        assertEquals("Used 3 tools", ChatChrome.toolSummary(3, 0));
        assertEquals("Used 3 tools · 1 failed", ChatChrome.toolSummary(3, 1));
    }

    @Test public void jumpLabelShowsCount() {
        assertEquals("Latest", ChatChrome.jumpLabel(0));
        assertEquals("3 new", ChatChrome.jumpLabel(3));
        assertEquals("9+ new", ChatChrome.jumpLabel(25));
    }

    @Test public void streamFollowsOnlyAtTailWithoutDrag() {
        assertTrue(ChatChrome.followTail(true, false));
        assertFalse(ChatChrome.followTail(true, true));
        assertFalse(ChatChrome.followTail(false, false));
    }

    @Test public void switchAvatarInitial() {
        assertEquals("W", SwitchOverlay.initial("worker"));
        assertEquals("H", SwitchOverlay.initial("  "));
        assertEquals("H", SwitchOverlay.initial(null));
    }

    @Test public void mixBlendsPerChannel() {
        assertEquals(0xff808080, SwitchOverlay.mix(0xff000000, 0xffffffff, .5f) & 0xffffffff, 1);
        assertEquals(0xff112233, SwitchOverlay.mix(0xff112233, 0xffffffff, 0f));
    }
}
