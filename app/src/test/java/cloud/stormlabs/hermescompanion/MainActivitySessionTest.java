package cloud.stormlabs.hermescompanion;

import static org.junit.Assert.*;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;

public class MainActivitySessionTest {
    private static JSONObject session(String id, String source, boolean archived) throws Exception {
        return new JSONObject().put("id", id).put("source", source).put("archived", archived);
    }

    @Test public void findsAppChatBeyondTerminalAndArchivedPages() throws Exception {
        try (StubServer server = new StubServer()) {
            server.on("GET /api/sessions", (request, socket) -> {
                JSONArray data = new JSONArray();
                if (request.query.equals("limit=200&offset=0")) {
                    for (int i = 0; i < 200; i++) data.put(session("terminal-" + i, "cli", false));
                } else if (request.query.equals("limit=200&offset=200")) {
                    for (int i = 0; i < 200; i++) data.put(session("archived-" + i, "api_server", true));
                } else if (request.query.equals("limit=200&offset=400")) {
                    data.put(session("mobile", "api_server", false));
                } else fail("Unexpected page: " + request.query);
                StubServer.respond(socket, 200, "application/json", new JSONObject().put("data", data).toString());
            });
            assertEquals("mobile", MainActivity.findAppChat(new HermesApi(server.base(), "k", "scope"), "missing").id);
            assertEquals(3, server.seen.size());
        }
    }

    @Test public void wantedConversationWinsOverAnotherAppChat() throws Exception {
        try (StubServer server = new StubServer()) {
            JSONArray data = new JSONArray().put(session("other", "api_server", false))
                    .put(session("wanted", "cli", false));
            server.json("GET /api/sessions", 200, new JSONObject().put("data", data).toString());
            assertEquals("wanted", MainActivity.findAppChat(new HermesApi(server.base(), "k", "scope"), "wanted").id);
        }
    }

    @Test public void exhaustedListAllowsNewChatButNetworkFailureDoesNot() throws Exception {
        try (StubServer server = new StubServer()) {
            HermesApi api = new HermesApi(server.base(), "k", "scope");
            server.json("GET /api/sessions", 200, "{\"data\":[]}");
            assertNull(MainActivity.findAppChat(api, "missing"));
            server.json("GET /api/sessions", 503, "{\"error\":\"offline\"}");
            try {
                MainActivity.findAppChat(api, "missing");
                fail("A failed lookup must not allow a duplicate chat");
            } catch (Exception expected) { }
        }
    }

    @Test public void scanLimitFailsWithoutCreatingADuplicate() throws Exception {
        try (StubServer server = new StubServer()) {
            JSONArray data = new JSONArray();
            for (int i = 0; i < 200; i++) data.put(session("terminal-" + i, "cli", false));
            server.json("GET /api/sessions", 200, new JSONObject().put("data", data).toString());
            try {
                MainActivity.findAppChat(new HermesApi(server.base(), "k", "scope"), "missing");
                fail("An incomplete search must not allow a duplicate chat");
            } catch (Exception expected) {
                assertTrue(expected.getMessage().contains("10,000"));
            }
            assertEquals(50, server.seen.size());
            for (StubServer.Request request : server.seen) assertEquals("GET", request.method);
        }
    }
}
