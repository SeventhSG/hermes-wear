package cloud.stormlabs.hermescompanion;

import static org.junit.Assert.*;
import org.junit.Test;

public class SessionSearchTest {
    @Test public void everyWordMustAppearInAnyOrder() {
        assertTrue(SessionSearch.score("Speed up Whisper on GTX 1650", "", "whisper gtx") > 0);
        assertTrue(SessionSearch.score("Speed up Whisper on GTX 1650", "", "gtx whisper") > 0);
        assertEquals(SessionSearch.NO_MATCH, SessionSearch.score("Speed up Whisper on GTX 1650", "", "whisper rtx"));
    }

    @Test public void titleMatchesOutrankPreviewMatches() {
        int title = SessionSearch.score("Docker compose fix", "unrelated", "docker");
        int preview = SessionSearch.score("Something else", "we talked about docker", "docker");
        assertTrue(title > preview);
        assertEquals(SessionSearch.IN_PREVIEW, preview);
    }

    @Test public void startsWithBeatsContainsAndWordStartBeatsMiddle() {
        assertEquals(SessionSearch.TITLE_STARTS, SessionSearch.score("Docker compose fix", "", "docker comp"));
        assertEquals(SessionSearch.TITLE_WORDS_START, SessionSearch.score("Fix docker compose", "", "docker"));
        assertEquals(SessionSearch.IN_TITLE, SessionSearch.score("Redockerize things", "", "docker"));
    }

    @Test public void caseAndBlankInputAreHandled() {
        assertTrue(SessionSearch.score("DOCKER", null, "docker") > 0);
        assertEquals(SessionSearch.IN_TITLE, SessionSearch.score("anything", "", "   "));
        assertEquals(SessionSearch.NO_MATCH, SessionSearch.score(null, null, "x"));
        assertEquals(2, SessionSearch.tokens("  two   words ").size());
    }
}
