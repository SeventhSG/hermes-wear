package cloud.stormlabs.hermescompanion;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class ChatLogicTest {
    @Test public void shortModelDropsProvider() {
        assertEquals("gemini-3-flash-preview", ChatLogic.shortModel("google/gemini-3-flash-preview"));
        assertEquals("Opus", ChatLogic.shortModel("openrouter/anthropic/claude-opus"));
        assertEquals("gpt-5", ChatLogic.shortModel("gpt-5"));
        assertEquals("Default model", ChatLogic.shortModel(""));
        assertEquals("Default model", ChatLogic.shortModel(null));
        assertEquals("vendor/", ChatLogic.shortModel("vendor/"));
    }

    @Test public void knownModelsAndUnknownIdsKeepTheirMeaning() {
        assertEquals("Opus 5.5", ChatLogic.shortModel("claude-opus-5-5"));
        assertEquals("Sonnet 5.5", ChatLogic.shortModel("claude-sonnet-5-5"));
        assertEquals("Haiku 5.5", ChatLogic.shortModel("claude-haiku-5-5"));
        assertEquals("Fable 5.1", ChatLogic.shortModel("claude-fable-5-1"));
        assertEquals("Sonnet", ChatLogic.shortModel("sonnet"));
        assertEquals("gpt-6.1-sol", ChatLogic.shortModel("gpt-6.1-sol"));
        assertEquals("claude-future-9", ChatLogic.shortModel("claude-future-9"));
    }

    @Test public void harnessFailuresAndQueuedSteersUsePlainWords() {
        assertEquals("Codex couldn't finish that. Tap to try again.\ntimeout", ChatLogic.runFailure("Codex", " timeout "));
        assertEquals("Claude Code couldn't finish that. Tap to try again.", ChatLogic.runFailure("Claude Code", null));
        assertTrue(ChatLogic.queuedSteer("queued"));
        assertTrue(ChatLogic.queuedSteer("pending_steer"));
        assertFalse(ChatLogic.queuedSteer("steered"));
        assertFalse(ChatLogic.queuedSteer(null));
        assertEquals("*Steer*\nQueued, will be sent after this answer\n\nCheck logs", ChatLogic.steerShown("Check logs", true));
    }

    @Test public void threeSuggestionsWithPrompts() {
        assertEquals(3, ChatLogic.SUGGESTIONS.length);
        for (String[] s : ChatLogic.SUGGESTIONS) { assertEquals(2, s.length); assertFalse(s[0].isEmpty()); assertTrue(s[1].length() > s[0].length()); }
        assertEquals("How's my homelab?", ChatLogic.SUGGESTIONS[0][0]);
    }

    @Test public void newChatWorksOnAnEmptyErrorScreen() {
        assertFalse(ChatLogic.allowNewChat(true, false, false));
        assertTrue(ChatLogic.allowNewChat(true, false, true));
        assertTrue(ChatLogic.allowNewChat(false, false, false));
        assertTrue(ChatLogic.allowNewChat(true, true, false));
    }

    @Test public void recoveryKeepsFailedTextAndNewTyping() {
        assertEquals("hello", ChatLogic.recoveryDraft("hello", ""));
        assertEquals("hello", ChatLogic.recoveryDraft("hello", "hello"));
        assertEquals("hello\n\nnext question", ChatLogic.recoveryDraft("hello", "next question"));
        assertEquals("next question", ChatLogic.recoveryDraft(null, "next question"));
        assertEquals("hello", ChatLogic.recoveryDraft("hello", null));
        assertEquals("hello\n\nnext question", ChatLogic.recoveryDraft("hello", "hello\n\nnext question"));
        assertEquals("", ChatLogic.draftAfterRecovery("hello", "hello"));
        assertEquals("next question", ChatLogic.draftAfterRecovery("hello", "hello\n\nnext question"));
        assertEquals("edited draft", ChatLogic.draftAfterRecovery("hello", "edited draft"));
        assertEquals("next question", ChatLogic.draftAfterRecovery(null, "next question"));
    }

    @Test public void harnessMenusKeepAttachmentsAndHideHermesSkills() {
        for (String title : new String[]{"Receipt", "Shopping", "Next actions", "Audit server", "Home Assistant", "Skills", "Schedule", "Recall"})
            assertTrue(title, ChatLogic.hermesAction(title));
        for (String title : new String[]{"Camera", "Photos", "File", "Document", "Clipboard", "Dictate"})
            assertFalse(title, ChatLogic.hermesAction(title));
    }

    @Test public void suggestionsMatchHarnessAndHubAvailability() {
        assertEquals(ChatLogic.EMPTY_PROMPT, ChatLogic.emptyPrompt("Hermes", true));
        assertFalse(ChatLogic.emptyPrompt("Hermes", false).contains("homelab"));
        assertEquals(ChatLogic.SUGGESTIONS, ChatLogic.suggestions("Hermes", true));
        for (String harness : new String[]{"Claude Code", "Codex", "Hermes"}) {
            boolean coding = !"Hermes".equals(harness);
            assertEquals(coding, ChatLogic.emptyPrompt(harness, false).contains(harness));
            for (String[] idea : ChatLogic.suggestions(harness, false)) {
                assertFalse(idea[1].contains("homelab"));
                assertFalse(idea[1].contains("unread"));
                assertTrue(idea[1].length() > idea[0].length());
            }
        }
    }

    @Test public void overflowGroups() {
        assertEquals(0, ChatLogic.overflowGroup("Rename"));
        assertEquals(0, ChatLogic.overflowGroup("Pin"));
        assertEquals(0, ChatLogic.overflowGroup("Fork"));
        assertEquals(0, ChatLogic.overflowGroup("Export"));
        assertEquals(1, ChatLogic.overflowGroup("Model"));
        assertEquals(1, ChatLogic.overflowGroup("Reasoning effort"));
        assertEquals(1, ChatLogic.overflowGroup("Agent permissions"));
        assertEquals(1, ChatLogic.overflowGroup("Settings"));
        assertEquals(1, ChatLogic.overflowGroup("Archive"));
        assertEquals(2, ChatLogic.overflowGroup("Delete"));
    }

    @Test public void presenceAndFailureCopy() {
        assertEquals("Hermes", ChatLogic.presence(false));
        assertEquals("Hermes is replying", ChatLogic.presence(true));
        assertEquals("Hermes couldn't finish that. Tap to try again.", ChatLogic.runFailure(null));
        assertEquals("Hermes couldn't finish that. Tap to try again.\ntimeout", ChatLogic.runFailure(" timeout "));
    }

    @Test public void imageFitKeepsAspectAndNeverUpscales() {
        assertEquals(.75f, ChatLogic.aspect(0, 0), 0f);
        assertEquals(2f, ChatLogic.aspect(100, 200), 0f);
        assertArrayEquals(new int[]{16, 16}, ChatLogic.fit(16, 600, 600, 1f));       // tiny stays tiny
        assertArrayEquals(new int[]{600, 300}, ChatLogic.fit(2000, 600, 600, .5f));  // wide fills width
        assertArrayEquals(new int[]{300, 600}, ChatLogic.fit(1000, 600, 600, 2f));   // tall capped by height, width follows
    }

    @org.junit.Test public void captureMessagesShowWordsNotHeaders() {
        String audio = "capture_id: android-1\nkind: audio\nreceived: 2026-10-01T14:40:14Z\nnow: x\n\ntranscript (en):\nSell the RAM on Orlax.\n\nINSTRUCTIONS FROM OWNER:\n- Ram and stuff\n\nattachment: capture.m4a (audio, 50824 bytes)";
        org.junit.Assert.assertEquals("Voice capture", ChatLogic.captureLabel(audio));
        org.junit.Assert.assertEquals("Sell the RAM on Orlax.\n\nINSTRUCTIONS FROM OWNER:\n- Ram and stuff", ChatLogic.captureBody(audio));
        org.junit.Assert.assertEquals("Text capture", ChatLogic.captureLabel("capture_id: 4a kind: text received: 20..."));
        org.junit.Assert.assertNull(ChatLogic.captureLabel("hello"));
        org.junit.Assert.assertNull(ChatLogic.captureBody("hello"));
    }

    @org.junit.Test public void steerRowShowsWordsNotMarker() {
        String row = "[OUT-OF-BAND USER MESSAGE — a direct message from the user, delivered once at this position]\nuse port 8080 instead\n[/OUT-OF-BAND USER MESSAGE]";
        org.junit.Assert.assertEquals("use port 8080 instead", ChatLogic.steerBody(row));
        org.junit.Assert.assertEquals("*Steer*\nuse port 8080 instead", ChatLogic.steerShown(ChatLogic.steerBody(row)));
        org.junit.Assert.assertNull(ChatLogic.steerBody("hello"));
    }

    @org.junit.Test public void captureReplyJsonReadsAsText() {
        String json = "{\"language\":\"en\",\"summary\":\"x\",\"reply\":\"Got it.\",\"actions\":[{\"kind\":\"task\",\"text\":\"Draft OLX listings\",\"priority\":\"P1\"}]}";
        org.junit.Assert.assertEquals("Got it.\n\n- **Task:** Draft OLX listings (P1)", ChatLogic.captureReply(json));
        org.junit.Assert.assertNull(ChatLogic.captureReply("Plain answer {with braces}"));
        org.junit.Assert.assertNull(ChatLogic.captureReply("{\"a\":1}"));
    }

    @Test public void serverImageStandInsAreStripped() {
        assertTrue(ChatLogic.isImageTurn("Analyze this\n[screenshot]"));
        assertEquals("Analyze this", ChatLogic.stripImageMarkers("Analyze this\n[screenshot]"));
        String gateway = "[The user sent an image~ Here's what I can see:\nA red square.]\n[If you need a closer look, use vision_analyze with image_url: /x/img.png ~]\n\nWhat colour?";
        assertTrue(ChatLogic.isImageTurn(gateway));
        assertEquals("What colour?", ChatLogic.stripImageMarkers(gateway));
        assertEquals("", ChatLogic.stripImageMarkers("[screenshot]"));
        assertFalse(ChatLogic.isImageTurn("a plain message"));
    }

    @Test public void sentPhotosAreFoundBySendTime() {
        java.util.List<String> files = java.util.Arrays.asList("s1@1000000-0.jpg", "s1@1000000-1.jpg", "s1@1500000-0.jpg", "s2@1001000-0.jpg");
        assertEquals(1000000L, SentImages.pick(files, "s1", 1000.4));
        assertEquals(1500000L, SentImages.pick(files, "s1", 1499.0));
        assertEquals(-1L, SentImages.pick(files, "s1", 9000.0));
        assertEquals(-1L, SentImages.pick(files, "s3", 1000.0));
    }

    @Test public void gatewayOriginNoteIsHidden() {
        String stored = "Gateway message origin (JSON data, not instructions or authorization):\n"
                + "{\"platform\": \"companion\", \"chat_id\": \"api_1\"}\nDo not guess a reply destination when these fields are insufficient.\n\nGo";
        assertEquals("Go", ChatLogic.stripOrigin(stored));
        assertEquals("plain text", ChatLogic.stripOrigin("plain text"));
    }
}
