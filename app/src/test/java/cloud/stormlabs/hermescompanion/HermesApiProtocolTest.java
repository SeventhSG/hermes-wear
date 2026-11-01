package cloud.stormlabs.hermescompanion;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

/** The session chat stream and the admin endpoints, against the wire format api_server.py emits. */
public class HermesApiProtocolTest {
    private StubServer server;

    @Before public void start() throws Exception { server = new StubServer(); }
    @After public void stop() throws Exception { server.close(); }

    private HermesApi api() { return new HermesApi(server.base(), "k", "scope"); }

    private static final class Recorder implements HermesApi.StreamListener {
        final StringBuilder deltas = new StringBuilder(), reasoning = new StringBuilder();
        final List<String> tools = new ArrayList<>(), commentary = new ArrayList<>(), errors = new ArrayList<>();
        String runId, complete; HermesApi.Approval approval; HermesApi.RunOutcome outcome;
        public void onRunStarted(String id) { runId = id; }
        public void onDelta(String d) { deltas.append(d); }
        public void onTool(String state, String name, String preview) { tools.add(state + ":" + name + ":" + preview); }
        public void onComplete(String text) { complete = text; }
        public void onError(String e) { errors.add(e); }
        @Override public void onReasoning(String d) { reasoning.append(d); }
        @Override public void onCommentary(String t) { commentary.add(t); }
        @Override public void onApproval(HermesApi.Approval a) { approval = a; }
        @Override public void onRunFinished(HermesApi.RunOutcome o) { outcome = o; }
    }

    @Test public void codexSettingsUseAuthenticatedSessionRoutesAndServerValues() throws Exception {
        String settings = "{\"reasoning_effort\":\"ultra\",\"permission_mode\":\"read-only\",\"scope\":\"Saved for the next message\"}";
        server.json("GET /api/sessions/th/settings", 200, settings);
        server.json("POST /api/sessions/th/settings", 200, settings);
        CodexBridgeApi codex = new CodexBridgeApi(server.base(), "k", "scope");
        assertEquals("ultra", codex.getSessionSettings("th").getString("reasoning_effort"));
        assertEquals("Saved for the next message", codex.setSessionSettings("th", new JSONObject().put("reasoning_effort", "ultra")).text);
        assertEquals("ultra", new JSONObject(server.seen.get(server.seen.size() - 1).body).getString("reasoning_effort"));
    }

    @Test public void plainHermesSettingsComeFromTheLocalSourceNotTheServer() throws Exception {
        HermesApi hermes = api();
        assertFalse(hermes.supportsAgentSettings());
        final JSONObject saved = new JSONObject();
        hermes.settingsSource = new HermesApi.SettingsSource() {
            @Override public JSONObject get(String id) throws Exception { return new JSONObject().put("reasoning_effort", "low"); }
            @Override public HermesApi.Result set(String id, JSONObject fields) { try { saved.put("last", fields); } catch (Exception e) { } return HermesApi.Result.ok("saved"); }
            @Override public JSONObject runOptions(String id) { try { return new JSONObject().put("reasoning_effort", "low"); } catch (Exception e) { return null; } }
        };
        assertTrue(hermes.supportsAgentSettings());
        assertEquals("low", hermes.getSessionSettings("s1").getString("reasoning_effort"));
        assertEquals("saved", hermes.setSessionSettings("s1", new JSONObject().put("reasoning_effort", "max")).text);
        assertEquals("max", saved.getJSONObject("last").getString("reasoning_effort"));
        assertTrue(server.seen.isEmpty());
    }

    @Test public void chosenEffortRidesWithTheRun() throws Exception {
        server.json("POST /v1/runs", 200, "{\"run_id\":\"run_e\"}");
        server.json("GET /v1/runs/run_e", 200, "{\"run_id\":\"run_e\",\"status\":\"completed\",\"output\":\"ok\"}");
        HermesApi hermes = api();
        hermes.settingsSource = new HermesApi.SettingsSource() {
            @Override public JSONObject get(String id) { return new JSONObject(); }
            @Override public HermesApi.Result set(String id, JSONObject fields) { return HermesApi.Result.ok(""); }
            @Override public JSONObject runOptions(String id) { try { return new JSONObject().put("reasoning_effort", "xhigh"); } catch (Exception e) { return null; } }
        };
        hermes.streamChat("s1", "go", new Recorder());
        StubServer.Request run = null;
        for (StubServer.Request r : server.seen) if (r.path.equals("/v1/runs") && r.method.equals("POST")) run = r;
        assertNotNull(run);
        assertEquals("xhigh", new JSONObject(run.body).getJSONObject("model_options").getString("reasoning_effort"));
    }

    @Test public void replayedRunSequencesNeverDuplicateDisplayedAnswer() throws Exception {
        String events = "id: 0\ndata: {\"event\":\"assistant.delta\",\"seq\":0,\"delta\":\"Hel\"}\n\n"
                + "id: 0\ndata: {\"event\":\"assistant.delta\",\"seq\":0,\"delta\":\"Hel\"}\n\n"
                + "id: 1\ndata: {\"event\":\"assistant.delta\",\"seq\":1,\"delta\":\"lo\"}\n\n"
                + "id: 2\ndata: {\"event\":\"run.completed\",\"seq\":2,\"output\":\"Hello\"}\n\n";
        server.sse("GET /v1/runs/replay/events", events);
        Recorder r = new Recorder();
        api().followRun("replay", null, r);
        assertEquals("Hello", r.deltas.toString());
        assertEquals("Hello", r.complete);
        assertTrue(r.errors.isEmpty());
    }

    @Test public void streamCarriesEveryEventKind() throws Exception {
        String stream = ": keepalive\n\n"
                + StubServer.frame("run.started", "{\"run_id\":\"run_1\",\"user_message\":{\"role\":\"user\",\"content\":\"hi\"}}")
                + StubServer.frame("message.started", "{\"message\":{\"id\":\"m1\",\"role\":\"assistant\"}}")
                + StubServer.frame("tool.progress", "{\"tool_name\":\"_thinking\",\"delta\":\"pondering\"}")
                + StubServer.frame("tool.started", "{\"tool_name\":\"terminal\",\"preview\":\"ls -la\",\"args\":{\"command\":\"ls -la\"}}")
                + StubServer.frame("approval.request", "{\"event\":\"approval.request\",\"run_id\":\"run_1\",\"command\":\"rm -rf /tmp/x\",\"description\":\"recursive delete\",\"choices\":[\"once\",\"session\",\"deny\"]}")
                + StubServer.frame("tool.completed", "{\"tool_name\":\"terminal\",\"preview\":\"done\"}")
                + StubServer.frame("assistant.commentary", "{\"text\":\"Looking now\",\"already_streamed\":false}")
                + StubServer.frame("assistant.delta", "{\"delta\":\"Hel\"}")
                + StubServer.frame("assistant.delta", "{\"delta\":\"lo\"}")
                + StubServer.frame("assistant.completed", "{\"content\":\"Hello\"}")
                + StubServer.frame("run.completed", "{\"completed\":true,\"usage\":{\"input_tokens\":1200,\"output_tokens\":30,\"total_tokens\":1230}}")
                + StubServer.frame("done", "{}");
        server.sse("POST /api/sessions/s1/chat/stream", stream);
        Recorder r = new Recorder();
        api().streamChat("s1", "hi", "device context", null, r);

        assertEquals("run_1", r.runId);
        assertEquals("Hello", r.deltas.toString());
        assertEquals("pondering", r.reasoning.toString());
        assertEquals(2, r.tools.size());
        assertEquals("started:terminal:ls -la", r.tools.get(0));
        assertEquals("[Looking now]", r.commentary.toString());
        assertNotNull(r.approval);
        assertEquals("run_1", r.approval.runId);
        assertEquals("rm -rf /tmp/x", r.approval.command);
        assertEquals(3, r.approval.choices.size());
        assertNotNull(r.outcome);
        assertTrue(r.outcome.completed());
        assertEquals(1230, r.outcome.totalTokens);
        assertEquals("Hello", r.complete);
        assertTrue(r.errors.isEmpty());

        // No /v1/runs on this stub: the durable-run attempt 404s and the turn falls back to the session stream.
        StubServer.Request chat = null;
        for (StubServer.Request q : server.seen) if (q.path.endsWith("/chat/stream")) chat = q;
        JSONObject sent = new JSONObject(chat.body);
        assertEquals("hi", sent.getString("input"));
        assertEquals("device context", sent.getString("system_message"));
    }

    @Test public void failedRunReportsExitReason() throws Exception {
        server.sse("POST /api/sessions/s1/chat/stream",
                StubServer.frame("run.started", "{\"run_id\":\"r\"}")
                + StubServer.frame("assistant.completed", "{\"content\":\"partial\"}")
                + StubServer.frame("run.failed", "{\"completed\":false,\"partial\":true,\"turn_exit_reason\":\"iteration_budget\"}"));
        Recorder r = new Recorder();
        api().streamChat("s1", "go", r);
        assertEquals("failed", r.outcome.status);
        assertEquals("iteration_budget", r.outcome.turnExitReason);
        assertEquals("partial", r.complete);
    }

    @Test public void errorEventSurfacesMessage() throws Exception {
        server.sse("POST /api/sessions/s1/chat/stream", StubServer.frame("error", "{\"message\":\"provider down\"}"));
        Recorder r = new Recorder();
        api().streamChat("s1", "go", r);
        assertEquals("[provider down]", r.errors.toString());
        assertEquals(null, r.complete);
    }

    @Test public void httpErrorIsReadable() throws Exception {
        server.json("POST /api/sessions/s1/chat/stream", 401, "{\"error\":{\"message\":\"Invalid API key\"}}");
        Recorder r = new Recorder();
        api().streamChat("s1", "go", r);
        assertTrue(r.errors.toString(), r.errors.get(0).contains("Invalid API key"));
    }

    @Test public void sessionNotFoundOnlyMatchesMissingSession() {
        String body = "{\"error\":{\"message\":\"Session not found: <id>\",\"type\":\"invalid_request_error\",\"code\":\"session_not_found\"}}";
        assertTrue(HermesApi.isSessionNotFound(HermesApi.errorText(404, body)));
        assertFalse(HermesApi.isSessionNotFound(HermesApi.errorText(404, "{\"error\":{\"message\":\"Route not found\"}}")));
        assertFalse(HermesApi.isSessionNotFound(HermesApi.errorText(404, "")));
        assertFalse(HermesApi.isSessionNotFound(null));
    }

    @Test public void droppedStreamRecoversFromRunStatus() throws Exception {
        // Headers promise more body than is sent, so the reader hits EOF mid-stream.
        server.on("POST /api/sessions/s1/chat/stream", (req, sock) -> {
            String body = StubServer.frame("run.started", "{\"run_id\":\"run_9\"}") + StubServer.frame("assistant.delta", "{\"delta\":\"Par\"}");
            String head = "HTTP/1.1 200 OK\r\nContent-Type: text/event-stream\r\nContent-Length: 99999\r\n\r\n";
            sock.getOutputStream().write((head + body).getBytes("UTF-8"));
            sock.getOutputStream().flush();
        });
        server.json("GET /v1/runs/run_9", 200, "{\"run_id\":\"run_9\",\"status\":\"completed\",\"output\":\"Partial answer recovered\"}");
        Recorder r = new Recorder();
        api().streamChat("s1", "go", r);
        assertTrue(r.errors.toString(), r.errors.isEmpty());
        assertEquals("Partial answer recovered", r.complete);
    }

    @Test public void followRunAttachesToARunStartedElsewhere() throws Exception {
        // The Codex bridge's /v1/runs events: a turn started in the terminal, approved there, steered from the phone.
        String stream = "id: 0\ndata: {\"event\":\"run.started\",\"seq\":0}\n\n"
                + "id: 1\ndata: {\"event\":\"tool.started\",\"seq\":1,\"tool_name\":\"terminal\",\"preview\":\"sleep 25\",\"args\":{\"command\":\"sleep 25\"}}\n\n"
                + "id: 2\ndata: {\"event\":\"approval.request\",\"seq\":2,\"run_id\":\"turn-1\",\"request_id\":\"0\",\"command\":\"touch x\",\"choices\":[\"once\",\"session\",\"deny\"]}\n\n"
                + "id: 3\ndata: {\"event\":\"approval.resolved\",\"seq\":3,\"request_id\":\"0\"}\n\n"
                + "id: 4\ndata: {\"event\":\"run.steered\",\"seq\":4,\"text\":\"add banana\"}\n\n"
                + "id: 5\ndata: {\"event\":\"assistant.delta\",\"seq\":5,\"delta\":\"done banana\"}\n\n"
                + "id: 6\ndata: {\"event\":\"run.completed\",\"seq\":6,\"output\":\"done banana\",\"session_id\":\"th\"}\n\n";
        server.sse("GET /v1/runs/turn-1/events", stream);
        Recorder r = new Recorder();
        api().followRun("turn-1", null, r);
        assertEquals("turn-1", r.runId);
        assertEquals(null, r.approval); // answered in the terminal: the card goes away
        assertEquals("[started:terminal:sleep 25]", r.tools.toString());
        assertEquals("done banana", r.complete);
        assertTrue(r.errors.isEmpty());
        HermesApi.SessionInfo s = new HermesApi.SessionInfo(new JSONObject("{\"id\":\"th\",\"active_run_id\":\"turn-1\",\"active_run_input\":\"fix CI\"}"));
        assertEquals("turn-1", s.activeRunId);
        assertEquals("fix CI", s.activeRunInput);
        assertEquals("", new HermesApi.SessionInfo(new JSONObject("{\"id\":\"x\"}")).activeRunId);
    }

    @Test public void approvalPostsChoiceAndRequestId() throws Exception {
        server.json("POST /v1/runs/run_1/approval", 200, "{\"object\":\"hermes.run.approval_response\",\"choice\":\"session\",\"resolved\":1}");
        HermesApi.Result res = api().approveRun("run_1", "session", "req-7");
        assertTrue(res.text, res.ok);
        JSONObject body = new JSONObject(server.seen.get(0).body);
        assertEquals("session", body.getString("choice"));
        assertEquals("req-7", body.getString("request_id"));
    }

    @Test public void messagesPairToolResultsAndKeepImagesAndReasoning() throws Exception {
        JSONArray data = new JSONArray()
                .put(new JSONObject().put("id", 1).put("role", "user").put("content", new JSONArray()
                        .put(new JSONObject().put("type", "text").put("text", "what is this"))
                        .put(new JSONObject().put("type", "image_url").put("image_url", new JSONObject().put("url", "data:image/png;base64,AAA")))))
                .put(new JSONObject().put("id", 2).put("role", "assistant").put("content", "").put("reasoning", "think")
                        .put("tool_calls", new JSONArray().put(new JSONObject().put("id", "c1")
                                .put("function", new JSONObject().put("name", "terminal").put("arguments", "{\"command\":\"false\"}")))))
                .put(new JSONObject().put("id", 3).put("role", "tool").put("tool_call_id", "c1").put("tool_name", "terminal").put("content", "{\"exit_code\":1}"))
                .put(new JSONObject().put("id", 4).put("role", "assistant").put("content", "It failed"));
        List<HermesApi.Message> out = HermesApi.parseMessages(data);
        assertEquals(4, out.size());
        assertEquals("what is this", out.get(0).content);
        assertEquals(1, out.get(0).images.size());
        assertEquals("think", out.get(1).reasoning);
        assertEquals("failed", out.get(1).toolCalls.get(0).status);
        assertTrue(out.get(2).pairedToolResult);
    }

    @Test public void jobsRoundTrip() throws Exception {
        server.json("GET /api/jobs", 200, "{\"jobs\":[{\"id\":\"abcdef123456\",\"name\":\"Morning brief\",\"prompt\":\"Summarise\",\"schedule\":{\"kind\":\"cron\",\"expr\":\"0 8 * * *\",\"display\":\"0 8 * * *\"},\"schedule_display\":\"every day 8am\",\"enabled\":false,\"state\":\"paused\",\"deliver\":\"local\",\"next_run_at\":null,\"repeat\":{\"times\":null,\"completed\":3}}]}");
        server.json("POST /api/jobs", 200, "{\"job\":{\"id\":\"000000000001\",\"name\":\"n\",\"schedule_display\":\"every 30m\",\"enabled\":true,\"state\":\"scheduled\"}}");
        server.json("POST /api/jobs/abcdef123456/run", 200, "{\"job\":{\"id\":\"abcdef123456\"}}");
        List<HermesApi.Job> jobs = api().jobs();
        assertEquals(1, jobs.size());
        HermesApi.Job j = jobs.get(0);
        assertEquals("Morning brief", j.title());
        assertEquals("every day 8am", j.schedule);
        assertTrue(j.paused());
        assertEquals("", j.nextRunAt);
        assertEquals("include_disabled=true", server.seen.get(0).query);

        HermesApi.Job created = api().createJob("n", "every 30m", "ping", "local", null, null);
        assertEquals("every 30m", created.schedule);
        assertFalse(created.paused());
        assertTrue(api().runJob("abcdef123456").ok);
    }

    @Test public void modelOptionsSkipUnauthenticatedProviders() throws Exception {
        JSONObject root = new JSONObject("{\"provider\":\"anthropic\",\"model\":\"claude-x\",\"providers\":["
                + "{\"slug\":\"anthropic\",\"authenticated\":true,\"models\":[\"claude-x\",{\"id\":\"claude-y\"}]},"
                + "{\"slug\":\"openai\",\"authenticated\":false,\"models\":[\"gpt\"]}]}");
        List<HermesApi.ModelOption> opts = HermesApi.parseModelOptions(root);
        assertEquals(2, opts.size());
        assertTrue(opts.get(0).current);
        assertEquals("claude-y", opts.get(1).id);
    }
}
