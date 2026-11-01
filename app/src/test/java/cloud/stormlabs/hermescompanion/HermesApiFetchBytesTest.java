package cloud.stormlabs.hermescompanion;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import org.junit.Test;

import java.net.URL;
import java.nio.charset.StandardCharsets;

public class HermesApiFetchBytesTest {
    @Test public void authOnlyForSameServerWithoutUserInfo() throws Exception {
        String base = "https://api.example.com/v1";
        assertTrue(HermesApi.sameServer(base, new URL("https://api.example.com/image")));
        assertTrue(HermesApi.sameServer(base, new URL("https://API.EXAMPLE.COM:443/image")));
        assertFalse(HermesApi.sameServer(base, new URL("http://api.example.com/image")));
        assertFalse(HermesApi.sameServer(base, new URL("https://api.example.com.evil.test/image")));
        assertFalse(HermesApi.sameServer(base, new URL("https://api.example.com:8443/image")));
        assertFalse(HermesApi.sameServer(base, new URL("https://user:pass@api.example.com/image")));
        assertFalse(HermesApi.sameServer(base, new URL("https://api.example.com@evil.test/image")));
        assertFalse(HermesApi.sameServer("https://user@api.example.com", new URL("https://api.example.com/image")));
    }

    @Test public void authenticatedFetchDoesNotFollowRedirect() throws Exception {
        try (StubServer server = new StubServer()) {
            server.on("GET /image", (request, socket) -> socket.getOutputStream().write(
                    ("HTTP/1.1 302 Found\r\nLocation: " + server.base() + "/next\r\nContent-Length: 0\r\nConnection: close\r\n\r\n")
                            .getBytes(StandardCharsets.UTF_8)));
            server.json("GET /next", 200, "{} ");
            try { new HermesApi(server.base(), "secret", "scope").fetchBytes(server.base() + "/image", 100); fail("redirect followed"); }
            catch (IllegalStateException expected) { assertEquals("HTTP 302", expected.getMessage()); }
            assertEquals(1, server.seen.size());
            assertEquals("Bearer secret", server.seen.get(0).headers.get("authorization"));
        }
    }
}
