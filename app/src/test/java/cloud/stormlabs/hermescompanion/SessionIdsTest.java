package cloud.stormlabs.hermescompanion;

import org.junit.Test;
import static org.junit.Assert.*;

public class SessionIdsTest {
    @Test public void createsStablePrefix() { assertTrue(SessionIds.normalize("").startsWith("mobile-")); }
    @Test public void stripsHeaderInjection() { assertEquals("abcdef", SessionIds.normalize("abc\r\ndef")); }
    @Test public void capsLength() { assertEquals(256, SessionIds.normalize("x".repeat(400)).length()); }
}
