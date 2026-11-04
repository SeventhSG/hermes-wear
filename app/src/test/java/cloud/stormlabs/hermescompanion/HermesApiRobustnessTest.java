package cloud.stormlabs.hermescompanion;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.util.List;

/** Overnight robustness fixes in HermesApi that need a stubbed server. */
public class HermesApiRobustnessTest {
    private StubServer server;

    @Before public void start() throws Exception { server = new StubServer(); }
    @After public void stop() throws Exception { server.close(); }

    private HermesApi api() { return new HermesApi(server.base(), "k", "scope"); }

    @Test public void historyPageRemembersRawRowsSoPagingIgnoresDroppedRows() throws Exception {
        // 4 raw rows: two empty assistant rows are dropped by the parser, so only 2 messages remain.
        server.json("GET /api/sessions/s/messages", 200, "{\"data\":["
                + "{\"id\":1,\"role\":\"user\",\"content\":\"hi\"},"
                + "{\"id\":2,\"role\":\"assistant\",\"content\":\"\"},"
                + "{\"id\":3,\"role\":\"assistant\",\"content\":\"\"},"
                + "{\"id\":4,\"role\":\"assistant\",\"content\":\"yo\"}]}");
        List<HermesApi.Message> page = api().messages("s", 4, 0);
        assertEquals(2, page.size());
        assertEquals(4, HermesApi.rawRows(page));
        assertEquals(3, HermesApi.rawRows(new java.util.ArrayList<>(java.util.Arrays.asList(null, null, null))));
    }

    private static final class Rec implements HermesApi.StreamListener {
        final java.util.List<String> errors = new java.util.concurrent.CopyOnWriteArrayList<>();
        String complete;
        public void onRunStarted(String id) { }
        public void onDelta(String d) { }
        public void onTool(String state, String name, String preview) { }
        public void onComplete(String text) { complete = text; }
        public void onError(String e) { errors.add(e); }
    }

    private static boolean sawStop(StubServer s, String path) {
        for (StubServer.Request r : s.seen) if (r.method.equals("POST") && r.path.equals(path)) return true;
        return false;
    }

    @Test public void gatewayFallbackOnlyWhenTheRequestNeverReachedIt() {
        assertTrue(HermesApi.unreachable(new java.net.ConnectException("refused")));
        assertTrue(HermesApi.unreachable(new java.net.SocketTimeoutException("connect timed out")));
        assertTrue(HermesApi.unreachable(new IllegalStateException("HTTP 404 Not Found")));
        // The gateway may already have created the run: resending would execute the prompt twice.
        assertFalse(HermesApi.unreachable(new java.net.SocketTimeoutException("Read timed out")));
    }

    @Test public void stopWhileTheRunIsBeingCreatedStopsItOnTheServer() throws Exception {
        HermesApi.StreamHandle handle = new HermesApi.StreamHandle();
        server.on("POST /v1/runs", (req, sock) -> { handle.cancel(); StubServer.respond(sock, 200, "application/json", "{\"run_id\":\"run_x\"}"); });
        server.json("POST /v1/runs/run_x/stop", 200, "{}");
        Rec r = new Rec();
        assertTrue(api().streamRun("s", "go", null, handle, r));
        assertEquals("[Cancelled]", r.errors.toString());
        assertTrue(sawStop(server, "/v1/runs/run_x/stop"));
    }

    @Test public void forgottenRunWithNothingStreamedIsAnError() throws Exception {
        // events 404 and the status lookup 404s too (no route): the old code reported an empty successful reply.
        Rec r = new Rec();
        api().followRun("gone", null, r);
        assertEquals(1, r.errors.size());
        assertTrue(r.errors.get(0), r.errors.get(0).contains("no longer has this run"));
        assertEquals(null, r.complete);
    }

    @Test public void refusedKeyEndsTheRunInsteadOfRetryingForMinutes() throws Exception {
        server.json("GET /v1/runs/r1/events", 401, "{\"error\":{\"message\":\"bad key\"}}");
        Rec r = new Rec();
        long t0 = System.currentTimeMillis();
        api().followRun("r1", null, r);
        assertEquals(1, r.errors.size());
        assertTrue(System.currentTimeMillis() - t0 < 3000);
    }

    @Test public void stopCutsTheReconnectBackoffShort() throws Exception {
        server.sse("GET /v1/runs/r2/events", "");
        server.json("GET /v1/runs/r2", 200, "{\"run_id\":\"r2\",\"status\":\"running\"}");
        HermesApi.StreamHandle handle = new HermesApi.StreamHandle();
        Rec r = new Rec();
        new Thread(() -> { try { Thread.sleep(300); } catch (InterruptedException ignored) {} handle.cancel(); }).start();
        long t0 = System.currentTimeMillis();
        api().followRun("r2", handle, r);
        assertEquals("[Cancelled]", r.errors.toString());
        assertTrue("slept through the backoff", System.currentTimeMillis() - t0 < 900);
    }

    @Test public void failedRouteLookupIsNotCachedAsNoPinnedModel() throws Exception {
        java.util.concurrent.atomic.AtomicInteger calls = new java.util.concurrent.atomic.AtomicInteger();
        server.on("GET /api/sessions/pin", (req, sock) -> {
            if (calls.incrementAndGet() == 1) StubServer.respond(sock, 500, "application/json", "{\"error\":{\"message\":\"boom\"}}");
            else StubServer.respond(sock, 200, "application/json", "{\"session\":{\"model\":\"m1\",\"has_model_config\":true}}");
        });
        HermesApi api = api();
        assertEquals(null, api.sessionRoute("pin"));
        assertEquals("m1", api.sessionRoute("pin")[0]);
    }
}
