package cloud.stormlabs.hermescompanion;

import org.junit.Test;
import static org.junit.Assert.*;

public class UrlUtilsTest {
    @Test public void blankInputReturnsEmpty() {
        assertEquals("", UrlUtils.normalize("  "));
        assertEquals("", UrlUtils.normalize(null));
        assertEquals("", UrlUtils.normalize(""));
    }
    @Test public void hostOnlyInputGetsHttpScheme() {
        assertEquals("http://hermes.example.com", UrlUtils.normalize("hermes.example.com"));
        assertEquals("http://192.168.1.50:8889", UrlUtils.normalize("192.168.1.50:8889///"));
    }
    @Test public void explicitHttpIsPreserved() {
        assertEquals("http://192.168.1.50:8889", UrlUtils.normalize("http://192.168.1.50:8889/"));
    }
    @Test public void explicitHttpsIsPreserved() {
        assertEquals("https://hermes.example.com", UrlUtils.normalize("https://hermes.example.com/"));
        assertEquals("https://hermes.example.com/v1", UrlUtils.normalize("https://hermes.example.com/v1/"));
    }
    @Test public void blankBaseYieldsBlankEndpoint() {
        assertEquals("", UrlUtils.endpoint("  ", "v1/chat/completions"));
        assertEquals("", UrlUtils.endpoint(null, "health"));
    }
    @Test public void buildsEndpoint() {
        assertEquals("https://example.test/v1/chat/completions", UrlUtils.endpoint("https://example.test/", "v1/chat/completions"));
        assertEquals("https://example.test/v1/chat/completions", UrlUtils.endpoint("https://example.test", "/v1/chat/completions"));
    }
    @Test public void acceptsUsableEndpointsAndRejectsOthers() {
        assertTrue(UrlUtils.isUsableEndpoint("http://192.168.1.50:8889"));
        assertTrue(UrlUtils.isUsableEndpoint("https://hermes.example.com"));
        assertTrue(UrlUtils.isUsableEndpoint("hermes.example.com"));
        assertFalse(UrlUtils.isUsableEndpoint(""));
        assertFalse(UrlUtils.isUsableEndpoint("https://"));
        assertFalse(UrlUtils.isUsableEndpoint("ftp://example.test"));
    }
}
