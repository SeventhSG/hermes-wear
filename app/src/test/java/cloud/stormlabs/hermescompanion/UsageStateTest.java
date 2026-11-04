package cloud.stormlabs.hermescompanion;

import org.json.JSONObject;
import org.junit.Test;
import static org.junit.Assert.*;

public class UsageStateTest {
    private JSONObject fixture() throws Exception {
        return new JSONObject("{\"as_of\":1762250000,\"context\":{\"used_tokens\":61234,\"window_tokens\":200000,\"percent\":30.6,\"auto_compact_at\":167000,\"source\":\"live\"},"
                + "\"session\":{\"input_tokens\":120000,\"output_tokens\":8000,\"cached_input_tokens\":90000,\"reasoning_tokens\":0,\"cost_usd\":1.42,\"cost_label\":\"API-equivalent\"},"
                + "\"limits\":[{\"id\":\"five_hour\",\"label\":\"5-hour limit\",\"used_percent\":42,\"resets_at\":1762260000,\"status\":\"warning\"}],\"plan\":\"max\",\"resets\":{\"available\":1,\"label\":\"Use a limit reset\"}}");
    }
    @Test public void readsTheWholeWireContract() throws Exception {
        UsageState s = UsageState.parse(fixture());
        assertEquals(1762250000, s.asOf); assertEquals("max", s.plan);
        assertEquals(Long.valueOf(61234), s.context.usedTokens); assertEquals(Long.valueOf(200000), s.context.windowTokens);
        assertEquals(30.6, s.context.percent, .001); assertEquals(Long.valueOf(167000), s.context.autoCompactAt); assertEquals("live", s.context.source);
        assertEquals(Long.valueOf(120000), s.session.inputTokens); assertEquals(Long.valueOf(8000), s.session.outputTokens);
        assertEquals(Long.valueOf(90000), s.session.cachedInputTokens); assertEquals(Long.valueOf(0), s.session.reasoningTokens);
        assertEquals(1.42, s.session.costUsd, .001); assertEquals("API-equivalent", s.session.costLabel);
        UsageState.Limit l = s.limits.get(0); assertEquals("five_hour", l.id); assertEquals("5-hour limit", l.label);
        assertEquals(42, l.usedPercent, .001); assertEquals(1762260000, l.resetsAt); assertEquals("warning", l.status);
        assertEquals(Long.valueOf(1), s.resets.available); assertEquals("Use a limit reset", s.resets.label);
    }
    @Test public void optionalFieldsAreAbsentAndPercentIsBounded() throws Exception {
        UsageState empty = UsageState.parse(new JSONObject());
        assertNull(empty.context); assertNull(empty.session); assertNull(empty.resets); assertTrue(empty.limits.isEmpty());
        UsageState s = UsageState.parse(new JSONObject("{\"context\":{\"percent\":200},\"limits\":[{\"used_percent\":-5}]}"));
        assertEquals(100, s.context.percent, 0); assertEquals(0, s.limits.get(0).usedPercent, 0);
        assertNull(s.context.usedTokens);
        assertEquals(50, UsageState.parse(new JSONObject("{\"context\":{\"used_tokens\":5,\"window_tokens\":10}}")).context.percent, 0);
        assertNull(UsageState.parse(new JSONObject("{\"context\":{\"window_tokens\":0}}")).context.percent);
    }
    @Test public void eventsReplaceOnlyPresentKeysAndSnapshotsAreIndependent() throws Exception {
        UsageState before = UsageState.parse(fixture());
        JSONObject event = new JSONObject("{\"event\":\"usage.updated\",\"limits\":[],\"context\":{\"percent\":88,\"source\":\"estimate\"}}");
        UsageState after = UsageState.merge(before, event);
        assertEquals(88, after.context.percent, 0); assertTrue(after.limits.isEmpty());
        assertEquals(before.session.costUsd, after.session.costUsd); assertEquals(before.plan, after.plan);
        assertEquals(30.6, before.context.percent, .001); assertEquals(1, before.limits.size());
        event.getJSONObject("context").put("percent", 1); assertEquals(88, after.context.percent, 0);
        assertNull(UsageState.merge(after, new JSONObject().put("context", JSONObject.NULL)).context);
    }
    @Test public void resetCountdownUsesSeconds() {
        assertEquals("2h 14m", UsageState.resetsIn(100, 8140));
        assertEquals("3h 57m", UsageState.resetsIn(100, 14320));
        assertEquals("1h", UsageState.resetsIn(100, 3700));
        assertEquals("now", UsageState.resetsIn(100, 99)); assertEquals("now", UsageState.resetsIn(100, 100));
        assertEquals("", UsageState.resetsIn(100, 0)); assertEquals("1m", UsageState.resetsIn(100, 101));
    }
    @Test public void usageEventIsForwardedAndNeverTerminal() throws Exception {
        final JSONObject[] received = {null};
        HermesApi.StreamListener listener = new HermesApi.StreamListener() {
            public void onRunStarted(String id) {} public void onDelta(String d) {} public void onTool(String a, String b, String c) {}
            public void onComplete(String text) { fail("Usage cannot finish a run"); } public void onError(String error) { fail(error); }
            @Override public void onUsage(JSONObject event) { received[0] = event; }
        };
        JSONObject event = new JSONObject().put("event", "usage.updated").put("context", new JSONObject().put("percent", 62));
        assertFalse(new HermesApi.RunEvents().handle("r", event, listener)); assertSame(event, received[0]);
    }
    @Test public void usageReachesListenersOnBothSsePaths() throws Exception {
        try (StubServer server = new StubServer()) {
            final int[] updates = {0};
            HermesApi.StreamListener listener = new HermesApi.StreamListener() {
                public void onRunStarted(String id) {} public void onDelta(String d) {} public void onTool(String a, String b, String c) {}
                public void onComplete(String text) {} public void onError(String error) { fail(error); }
                @Override public void onUsage(JSONObject event) { updates[0]++; }
            };
            server.sse("GET /v1/runs/r/events", "data: {\"event\":\"usage.updated\",\"context\":{\"percent\":42}}\n\ndata: {\"event\":\"run.completed\"}\n\n");
            server.sse("POST /api/sessions/s/chat/stream", StubServer.frame("usage.updated", "{\"context\":{\"percent\":62}}")
                    + StubServer.frame("run.completed", "{}") + StubServer.frame("done", "{}"));
            HermesApi api = new HermesApi(server.base(), "key", "scope");
            api.followRun("r", null, listener); assertEquals(1, updates[0]);
            api.streamChat("s", "hello", listener); assertEquals(2, updates[0]);
        }
    }

    @Test public void routesHermesStatusToTheServerOnlyWithHub() {
        assertTrue(ChatLogic.serverStatus("Hermes", true, "status"));
        assertFalse(ChatLogic.serverStatus("Hermes", false, "status"));
        assertFalse(ChatLogic.serverStatus("Claude Code", true, "status"));
        assertFalse(ChatLogic.serverStatus("Codex", true, "status"));
        assertFalse(ChatLogic.serverStatus("Hermes", true, "stop"));
    }
    @Test public void bridgeUsageAndResetUseSessionRoutes() throws Exception {
        try (StubServer server = new StubServer()) {
            server.json("GET /api/sessions/thread/usage", 200, fixture().toString());
            server.json("POST /api/sessions/thread/usage/reset", 200, "{\"ok\":true,\"message\":\"Reset used\"}");
            AgentBackend api = new CodexBridgeApi(server.base(), "key", "scope");
            assertEquals("max", api.usage("thread", true).getString("plan"));
            assertEquals("refresh=1", server.seen.get(0).query);
            assertTrue(api.resetUsage("thread").ok);
            assertEquals("{}", server.seen.get(1).body);
        }
    }
}
