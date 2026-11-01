package cloud.stormlabs.hermescompanion;

import static org.junit.Assert.*;
import org.junit.Test;

public class NotificationSpeechTest {
    private static NotificationSpeech.Situation ok() {
        NotificationSpeech.Situation s = new NotificationSpeech.Situation();
        s.enabled = true; s.appAllowed = true; s.screenOffOnly = true; s.headphonesOnly = true; s.headphones = true;
        return s;
    }

    @Test public void aMessageToEarbudsWithTheScreenOffIsRead() { assertNull(NotificationSpeech.skipReason(ok())); }

    @Test public void eachRuleBlocksOnItsOwn() {
        NotificationSpeech.Situation s = ok(); s.enabled = false; assertEquals("off", NotificationSpeech.skipReason(s));
        s = ok(); s.agentPaused = true; assertEquals("paused", NotificationSpeech.skipReason(s));
        s = ok(); s.ownApp = true; assertEquals("own app", NotificationSpeech.skipReason(s));
        s = ok(); s.appAllowed = false; assertEquals("app not chosen", NotificationSpeech.skipReason(s));
        s = ok(); s.ongoing = true; assertEquals("ongoing", NotificationSpeech.skipReason(s));
        s = ok(); s.groupSummary = true; assertEquals("group summary", NotificationSpeech.skipReason(s));
        s = ok(); s.silentChannel = true; assertEquals("silent channel", NotificationSpeech.skipReason(s));
        s = ok(); s.category = "Transport"; assertEquals("category Transport", NotificationSpeech.skipReason(s));
        s = ok(); s.screenOn = true; assertEquals("screen on", NotificationSpeech.skipReason(s));
        s = ok(); s.headphones = false; assertEquals("no headphones", NotificationSpeech.skipReason(s));
        s = ok(); s.inCall = true; assertEquals("in a call", NotificationSpeech.skipReason(s));
        s = ok(); s.quietNow = true; assertEquals("quiet hours", NotificationSpeech.skipReason(s));
    }

    @Test public void turningTheScreenAndHeadphoneRulesOffLetsEverythingElseThrough() {
        NotificationSpeech.Situation s = ok();
        s.screenOffOnly = false; s.screenOn = true; s.headphonesOnly = false; s.headphones = false;
        assertNull(NotificationSpeech.skipReason(s));
        s.category = "msg"; assertNull(NotificationSpeech.skipReason(s));
    }

    @Test public void linksEmojiAndSpacingAreTidiedAndLongTextIsCutAtAWord() {
        assertEquals("see link now", NotificationSpeech.clean("see https://example.com/a?b=1   now"));
        assertEquals("on my way", NotificationSpeech.clean("on my way 😀🚗"));
        String longText = new String(new char[60]).replace("\0", "word ");
        String cut = NotificationSpeech.clean(longText);
        assertTrue(cut, cut.endsWith("… and more"));
        assertTrue(cut.length() <= NotificationSpeech.MAX_CHARS + 12);
        assertEquals("", NotificationSpeech.clean(null));
    }

    @Test public void phrasesNameTheAppThenWhoThenWhat() {
        assertEquals("WhatsApp. Jane: running late", NotificationSpeech.phrase("WhatsApp", "Jane", "running late", true));
        assertEquals("WhatsApp, new message from Jane", NotificationSpeech.phrase("WhatsApp", "Jane", "running late", false));
        assertEquals("Gmail. Your invoice is ready", NotificationSpeech.phrase("Gmail", "Gmail", "Your invoice is ready", true));
        assertEquals("Signal, new notification", NotificationSpeech.phrase("Signal", "", "", true));
        assertEquals("Jane: hello", NotificationSpeech.phrase("", "Jane", "hello", true));
    }

    @Test public void theSameNotificationIsNotReadTwiceInsideTheWindow() {
        NotificationSpeech.Recent r = new NotificationSpeech.Recent(60_000);
        assertTrue(r.fresh("k", "hello", 0));
        assertFalse(r.fresh("k", "hello", 10_000));
        assertTrue(r.fresh("k", "hello again", 10_000));
        assertTrue(r.fresh("k", "hello", 70_000));
    }

    @Test public void overflowIsSummarised() {
        assertEquals("", NotificationSpeech.overflow(0));
        assertEquals("And one more notification.", NotificationSpeech.overflow(1));
        assertEquals("And 5 more notifications.", NotificationSpeech.overflow(5));
    }

    @Test public void storeDefaultsAreSafe() {
        SettingsStore s = new SettingsStore(NotificationOriginTest.preferences());
        assertFalse(s.readNotifications());
        assertTrue(s.readNotifHeadphonesOnly());
        assertTrue(s.readNotifScreenOffOnly());
        assertTrue(s.readNotifContent());
        assertTrue(s.readNotifApps().isEmpty());
    }
}
