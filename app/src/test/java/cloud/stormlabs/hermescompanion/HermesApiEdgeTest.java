package cloud.stormlabs.hermescompanion;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.fail;

import org.json.JSONObject;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

/** Wire cases not covered by the main protocol sequence. */
public class HermesApiEdgeTest {
    private StubServer server;
    @Before public void start() throws Exception { server = new StubServer(); }
    @After public void stop() throws Exception { server.close(); }
    private HermesApi api() { return new HermesApi(server.base(), "k", "scope"); }

    private static final class Recorder implements HermesApi.StreamListener {
        final StringBuilder deltas = new StringBuilder();
        final List<String> tools = new ArrayList<>(), errors = new ArrayList<>(), notes = new ArrayList<>();
        String runId, complete;
        HermesApi.Approval approval;
        HermesApi.RunOutcome outcome;
        public void onRunStarted(String id) { runId = id; }
        public void onDelta(String delta) { deltas.append(delta); }
        public void onTool(String state, String name, String preview) { tools.add(state + ":" + name + ":" + preview); }
        public void onComplete(String text) { complete = text; }
        public void onError(String error) { errors.add(error); }
        @Override public void onApproval(HermesApi.Approval value) { approval = value; }
        @Override public void onRunFinished(HermesApi.RunOutcome value) { outcome = value; }
        @Override public void onCommentary(String text) { notes.add(text); }
    }

    @Test public void errorTextHandlesRealApiAndProxyBodies() {
        assertEquals("HTTP 404: Session not found: x", HermesApi.errorText(404,
                "{\"error\":{\"message\":\"Session not found: x\",\"type\":\"invalid_request_error\",\"param\":null,\"code\":\"session_not_found\"}}"));
        assertEquals("HTTP 401: Invalid API key", HermesApi.errorText(401, "{\"error\":{\"message\":\"Invalid API key\"}}"));
        assertEquals("HTTP 401: the API key was rejected", HermesApi.errorText(401, ""));
        assertEquals("HTTP 402: Insufficient Balance", HermesApi.errorText(402, "{\"error\":{\"message\":\"Insufficient Balance\"}}"));
        for (int code : new int[]{502, 503}) assertEquals(
                "HTTP " + code + ": a proxy in front of Hermes couldn't reach it — is the Hermes gateway running?",
                HermesApi.errorText(code, "<html><body>Bad Gateway</body></html>"));
        assertEquals("HTTP 500", HermesApi.errorText(500, ""));
        assertEquals("HTTP 500: upstream reset", HermesApi.errorText(500, "upstream reset"));
        assertEquals("HTTP 404: this URL has no Hermes API here. Check the host, port and any path prefix.", HermesApi.errorText(404, ""));
    }

    @Test public void forbiddenRunReportsPolicyAndActualRouteWithoutResending() throws Exception {
        server.json("POST /v1/runs", 403, "<html><body>403 Forbidden</body></html>");
        Recorder r = new Recorder();
        api().streamChat("s1", "go", r);
        assertEquals(1, r.errors.size());
        String error = r.errors.get(0);
        assertTrue(error, error.contains("access denied by the server or reverse proxy"));
        assertTrue(error, error.contains("Backup addresses"));
        assertTrue(error, error.contains("POST 127.0.0.1:"));
        assertTrue(error, error.endsWith("/v1/runs)"));
        assertFalse(error, error.contains("refused this key"));
        assertNull(r.complete);
        assertFalse(server.seen.stream().anyMatch(q -> q.path.endsWith("/chat/stream")));
    }

    @Test public void forbiddenEventsStopWithoutRetryAndKeepServerPolicy() throws Exception {
        server.json("GET /v1/runs/r/events", 403, "{\"error\":{\"message\":\"Room authorization needs to be renewed.\"}}");
        Recorder r = new Recorder();
        api().followRun("r", null, r);
        assertEquals(1, server.seen.size());
        assertEquals(1, r.errors.size());
        assertTrue(r.errors.get(0), r.errors.get(0).startsWith("HTTP 403: Room authorization needs to be renewed."));
        assertTrue(r.errors.get(0), r.errors.get(0).endsWith("/v1/runs/r/events)"));
        assertNull(r.complete);
    }

    @Test public void requestJsonReportsProxyAndNonJsonReplies() throws Exception {
        server.json("GET /v1/capabilities", 503, "<html><body>Service Unavailable</body></html>");
        assertEquals("HTTP 503: a proxy in front of Hermes couldn't reach it — is the Hermes gateway running?", api().health().text);
        server.json("GET /v1/capabilities", 200, "upstream reset");
        assertEquals("The server's reply wasn't Hermes API JSON — check the URL points at the Hermes API server", api().health().text);
    }

    @Test public void streamParsesCrLfMultilineDataAndKeepalives() throws Exception {
        String stream = ": keepalive\r\n\r\n"
                + crlf(StubServer.frame("run.started", "{\"run_id\":\"r1\"}"))
                + "event: assistant.delta\r\ndata: {\"delta\":\r\n: still here\r\ndata: \"Hel\"}\r\n\r\n"
                + crlf(StubServer.frame("message.delta", "{\"delta\":\"lo\"}"))
                + crlf(StubServer.frame("tool.started", "{\"tool_name\":\"terminal\",\"preview\":\"ls\"}"))
                + crlf(StubServer.frame("tool.completed", "{\"tool_name\":\"terminal\",\"preview\":\"done\"}"))
                + crlf(StubServer.frame("approval.request", "{\"request_id\":\"a1\",\"command\":\"rm x\",\"choices\":[\"once\",\"deny\"]}"))
                + crlf(StubServer.frame("assistant.completed", "{\"content\":\"Hello\"}"))
                + crlf(StubServer.frame("run.completed", "{\"session_id\":\"s1\",\"usage\":{\"input_tokens\":2,\"output_tokens\":3}}"));
        server.sse("POST /api/sessions/s1/chat/stream", stream);
        Recorder r = new Recorder(); api().streamChat("s1", "go", r);
        assertEquals("r1", r.runId);
        assertEquals("Hello", r.deltas.toString());
        assertEquals("[started:terminal:ls, completed:terminal:done]", r.tools.toString());
        assertNotNull(r.approval);
        assertEquals("a1", r.approval.requestId);
        assertEquals("r1", r.approval.runId);
        assertEquals("Hello", r.complete);
        assertEquals("completed", r.outcome.status);
        assertEquals("s1", r.outcome.sessionId);
        assertEquals(5, r.outcome.totalTokens);
        assertTrue(r.errors.isEmpty());
    }

    @Test public void textTurnsUseDurableRunsAndSkipReplayedEvents() throws Exception {
        server.json("POST /v1/runs", 202, "{\"run_id\":\"r9\",\"status\":\"started\"}");
        server.sse("GET /v1/runs/r9/events", ": open\n\n"
                + "id: 0\ndata: {\"event\":\"message.delta\",\"delta\":\"Hi\",\"seq\":0}\n\n"
                + "id: 0\ndata: {\"event\":\"message.delta\",\"delta\":\"Hi\",\"seq\":0}\n\n"
                + "id: 1\ndata: {\"event\":\"tool.started\",\"tool_name\":\"terminal\",\"preview\":\"ls\",\"seq\":1}\n\n"
                + "id: 2\ndata: {\"event\":\"run.completed\",\"output\":\"Hi there\",\"seq\":2}\n\n");
        Recorder r = new Recorder(); api().streamChat("s1", "go", r);
        assertEquals("r9", r.runId);
        assertEquals("Hi", r.deltas.toString());
        assertEquals("Hi there", r.complete);
        assertEquals("completed", r.outcome.status);
        assertTrue(r.errors.isEmpty());
    }

    @Test public void gatewayRunsRouteToTheirPortAndFallBackToTheApiWhenItIsUnreachable() throws Exception {
        HermesApi main = new HermesApi("http://100.64.0.17:8889", "k", "s");
        main.useGateway = true;
        assertEquals("http://100.64.0.17:8797", main.gatewayBase());
        assertEquals("http://100.64.0.17:8797/v1/runs/gw_abc/stop", main.runUrl("gw_abc", "/stop"));
        assertEquals("/v1/runs/r1/events", main.runUrl("r1", "/events"));
        HermesApi profile = new HermesApi("http://100.64.0.17:8889/p/pentest", "k", "s");
        profile.useGateway = true;
        assertNull(profile.gatewayBase()); // the gateway platform serves the main profile only
        // Nothing listens on the stub host's gateway port: the turn runs on the API as before.
        server.json("POST /v1/runs", 202, "{\"run_id\":\"r7\"}");
        server.sse("GET /v1/runs/r7/events", "id: 0\ndata: {\"event\":\"run.completed\",\"output\":\"ok\",\"seq\":0}\n\n");
        HermesApi api = api(); api.useGateway = true;
        Recorder r = new Recorder(); api.streamChat("s1", "go", r);
        assertEquals("r7", r.runId);
        assertEquals("ok", r.complete);
        // Not silent: the chat says which path the turn took and why, also while the 60 s backoff skips the gateway.
        assertEquals(1, r.notes.size());
        assertTrue(r.notes.get(0), r.notes.get(0).startsWith("Sent through the API server because nothing answers on port 8797"));
        Recorder again = new Recorder(); api.streamChat("s1", "go", again);
        assertEquals(r.notes, again.notes);
        // With the gateway off in Settings the API is the chosen path, so nothing is said.
        api.useGateway = false;
        Recorder off = new Recorder(); api.streamChat("s1", "go", off);
        assertTrue(off.notes.isEmpty());
        assertEquals("Chat through the gateway is off", api.probeGateway());
        assertTrue(profile.probeGateway().startsWith("profile chats use the API server"));
        api.useGateway = true;
        assertTrue(api.probeGateway().startsWith("nothing answers on port 8797"));
    }

    @Test public void gatewayFallbackReasonsArePlainWords() {
        assertEquals("the gateway refused the app's API key",
                HermesApi.gatewayFallbackReason(new IllegalStateException("HTTP 401: unauthorized")));
        assertTrue(HermesApi.gatewayFallbackReason(new IllegalStateException("HTTP 404: Not Found")).contains("isn't installed"));
        assertTrue(HermesApi.gatewayFallbackReason(new java.net.SocketTimeoutException("x")).contains("out of reach"));
        assertTrue(HermesApi.gatewayFallbackReason(new java.net.ConnectException("refused")).contains("isn't running"));
    }

    @Test public void durableRunAsksStatusWhenStreamClosesEarly() throws Exception {
        server.json("POST /v1/runs", 202, "{\"run_id\":\"r8\"}");
        server.sse("GET /v1/runs/r8/events", "data: {\"event\":\"message.delta\",\"delta\":\"part\",\"seq\":0}\n\n");
        server.json("GET /v1/runs/r8", 200, "{\"status\":\"completed\",\"output\":\"Whole reply\"}");
        Recorder r = new Recorder(); api().streamChat("s1", "go", r);
        assertEquals("Whole reply", r.complete);
        assertTrue(r.errors.isEmpty());
    }

    @Test public void durableRunFailureIsAnError() throws Exception {
        server.json("POST /v1/runs", 202, "{\"run_id\":\"r7\"}");
        server.sse("GET /v1/runs/r7/events", "data: {\"event\":\"run.failed\",\"error\":\"provider down\",\"seq\":0}\n\n");
        Recorder r = new Recorder(); api().streamChat("s1", "go", r);
        assertEquals("[provider down]", r.errors.toString());
        assertNull(r.complete);
    }

    private static String crlf(String frame) { return frame.replace("\n", "\r\n"); }

    @Test public void cutMidEventRecoversWithoutDeliveringPartialPayload() throws Exception {
        server.sse("POST /api/sessions/s1/chat/stream",
                StubServer.frame("run.started", "{\"run_id\":\"r2\"}")
                        + "event: assistant.delta\ndata: {\"delta\":\"partial\"}");
        server.json("GET /v1/runs/r2", 200, "{\"status\":\"completed\",\"output\":\"Recovered\"}");
        Recorder r = new Recorder(); api().streamChat("s1", "go", r);
        assertEquals("", r.deltas.toString());
        assertEquals("Recovered", r.complete);
        assertEquals("completed", r.outcome.status);
        assertTrue(r.errors.isEmpty());
    }

    @Test public void serverErrorEventStopsStream() throws Exception {
        server.sse("POST /api/sessions/s1/chat/stream", StubServer.frame("error", "{\"message\":\"provider down\"}"));
        Recorder r = new Recorder(); api().streamChat("s1", "go", r);
        assertEquals("[provider down]", r.errors.toString());
        assertEquals(null, r.complete);
        assertEquals(null, r.outcome);
    }

    @Test public void createSessionWalksPastTitleCollision() throws Exception {
        server.on("POST /api/sessions", (req, sock) -> {
            String title = new JSONObject(req.body).getString("title");
            if ("Notes".equals(title)) StubServer.respond(sock, 409, "application/json",
                    "{\"error\":{\"message\":\"Title already in use by session old\",\"code\":\"invalid_title\"}}");
            else StubServer.respond(sock, 201, "application/json", "{\"session\":{\"id\":\"new\",\"title\":\"Notes 2\"}}");
        });
        HermesApi.SessionInfo session = api().createSession(" Notes ");
        assertEquals("new", session.id);
        assertEquals("Notes 2", session.title);
        assertEquals("Notes", new JSONObject(server.seen.get(0).body).getString("title"));
        assertEquals("Notes 2", new JSONObject(server.seen.get(1).body).getString("title"));
    }

    @Test public void ensureSessionCreatesWhenTitleFilterFails() throws Exception {
        server.json("GET /api/sessions", 503, "<html>proxy unavailable</html>");
        server.json("POST /api/sessions", 201, "{\"session\":{\"id\":\"new\",\"title\":\"Daily notes\"}}");
        HermesApi.SessionInfo session = api().ensureSession("Daily notes");
        assertEquals("new", session.id);
        assertEquals("limit=5&title=Daily+notes", server.seen.get(0).query);
        assertEquals("Daily notes", new JSONObject(server.seen.get(1).body).getString("title"));
    }

    @Test public void getSessionPreservesReal404Message() throws Exception {
        server.json("GET /api/sessions/missing", 404,
                "{\"error\":{\"message\":\"Session not found: missing\",\"type\":\"invalid_request_error\",\"param\":null,\"code\":\"session_not_found\"}}");
        try {
            api().getSession("missing");
            fail("Expected 404");
        } catch (IllegalStateException e) {
            assertEquals("HTTP 404: Session not found: missing", e.getMessage());
            assertTrue(HermesApi.isSessionNotFound(e.getMessage()));
        }
    }
}
