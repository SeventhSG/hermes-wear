package cloud.stormlabs.hermescompanion;

import org.json.JSONObject;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/** The hub's /jev/decisions wire protocol, and JevClient's "any failure returns null" contract. */
public class JevClientTest {
    private StubServer server;

    @Before public void start() throws Exception { server = new StubServer(); }
    @After public void stop() throws Exception { server.close(); }

    private JevClient client() { return new JevClient(server.base(), "k"); }

    @Test public void requestWrapsModelStateAndQuestions() throws Exception {
        JSONObject questions = new JSONObject().put("op", new JSONObject().put("type", "choice"));
        JSONObject req = JevClient.request("state text", questions);
        assertEquals("typesafe/jev-latest", req.getString("model"));
        assertEquals("state text", req.getString("state"));
        assertTrue(req.getJSONObject("questions").has("op"));
    }

    @Test public void decidePostsBodyAndAuthAndReturnsAnswers() throws Exception {
        server.json("POST /jev/decisions", 200,
                "{\"answers\":{\"op\":{\"type\":\"choice\",\"choice\":\"click\",\"confidence\":0.9}},\"usage\":{}}");
        JSONObject req = JevClient.request("state", new JSONObject().put("op", new JSONObject().put("type", "choice")));
        JSONObject answers = client().decide(req, "sess1");
        assertEquals("click", answers.getJSONObject("op").getString("choice"));
        StubServer.Request seen = server.seen.get(0);
        assertEquals("Bearer k", seen.headers.get("authorization"));
        JSONObject body = new JSONObject(seen.body);
        assertEquals("typesafe/jev-latest", body.getString("model"));
        assertEquals("sess1", body.getString("session_id"));
    }

    @Test public void decideOmitsSessionIdWhenEmpty() throws Exception {
        server.json("POST /jev/decisions", 200, "{\"answers\":{}}");
        client().decide(JevClient.request("s", new JSONObject()), "");
        JSONObject body = new JSONObject(server.seen.get(0).body);
        assertTrue(!body.has("session_id"));
    }

    @Test public void httpErrorReturnsNull() throws Exception {
        server.json("POST /jev/decisions", 401, "{\"error\":\"bad key\"}");
        JSONObject answers = client().decide(JevClient.request("s", new JSONObject()), null);
        assertNull(answers);
    }

    @Test public void malformedResponseReturnsNull() throws Exception {
        server.json("POST /jev/decisions", 200, "not json");
        JSONObject answers = client().decide(JevClient.request("s", new JSONObject()), null);
        assertNull(answers);
    }

    @Test public void unreachableHostReturnsNull() {
        // Stands in for a timeout/network failure: any failure returns null, never throws.
        JevClient unreachable = new JevClient("http://127.0.0.1:1", "k");
        assertNull(unreachable.decide(JevClient.request("s", new JSONObject()), null));
    }
}
