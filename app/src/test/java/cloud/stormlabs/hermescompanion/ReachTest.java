package cloud.stormlabs.hermescompanion;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.net.URL;
import java.util.Arrays;

public class ReachTest {
    @org.junit.After public void resetRouting() {
        Reach.secureOnly = false;
        Reach.raceOnFirstUse = false;
        Reach.configure("", 8889);
    }

    private static void selectBackup(StubServer primary, StubServer backup) throws Exception {
        Reach.configure(backup.base(), new URL(primary.base()).getPort());
        assertTrue(Reach.failover(primary.base()));
        assertEquals(backup.base() + "/x", Reach.apply(primary.base() + "/x"));
    }

    @Test public void proxyDeniedPostRetriesOnceAndTheBlockedBackupCannotWinAgain() throws Exception {
        for (int code : new int[]{403, 421, 495, 496}) {
            try (StubServer primary = new StubServer(); StubServer backup = new StubServer()) {
                primary.json("POST /v1/runs", 200, "{\"run_id\":\"r\"}");
                primary.sse("GET /v1/runs/r/events", "data: {\"event\":\"run.completed\",\"output\":\"ok\"}\n\n");
                backup.on("POST /v1/runs", (r, s) -> StubServer.respond(s, code, "text/html", "<html>Certificate required</html>"));
                selectBackup(primary, backup);
                Recorder listener = new Recorder();
                new HermesApi(primary.base(), "key", "scope").streamChat("s", "go", listener);
                assertEquals("ok", listener.complete);
                assertNull(listener.error);
                assertEquals(1, backup.seen.stream().filter(r -> r.method.equals("POST")).count());
                assertEquals(1, primary.seen.stream().filter(r -> r.method.equals("POST")).count());
                StubServer.Request replay = primary.seen.stream().filter(r -> r.method.equals("POST")).findFirst().get();
                assertEquals("Bearer key", replay.headers.get("authorization"));
                assertEquals(backup.seen.stream().filter(r -> r.method.equals("POST")).findFirst().get().body, replay.body);
                assertNull(Reach.race(Arrays.asList(backup.base()), null));
                assertFalse(Reach.failover(primary.base()));
                assertEquals(primary.base() + "/x", Reach.apply(primary.base() + "/x"));
                Reach.configure(backup.base(), new URL(primary.base()).getPort());
                assertEquals(backup.base(), Reach.race(Arrays.asList(backup.base()), null));
            }
        }
    }

    @Test public void jsonAuthErrorsNeverRetryEvenOnBackupRoutes() throws Exception {
        for (int code : new int[]{401, 403}) {
            try (StubServer primary = new StubServer(); StubServer backup = new StubServer()) {
                backup.json("GET /v1/capabilities", code, "{\"error\":{\"message\":\"Policy denied\"}}");
                selectBackup(primary, backup);
                assertTrue(new HermesApi(primary.base(), "key", "scope").health().text.startsWith("HTTP " + code + ": Policy denied"));
                assertFalse(primary.seen.stream().anyMatch(r -> !r.method.isEmpty()));
                assertEquals(1, backup.seen.stream().filter(r -> r.method.equals("GET")).count());
                assertEquals(backup.base(), Reach.race(Arrays.asList(backup.base()), null));
            }
        }
    }

    @Test public void proxyErrorsAreScopedToKnownRoutesAndStructuredBodiesStayAuthErrors() throws Exception {
        Reach.configure("https://proxy.example", 8889);
        String configured = "http://lan.example:8797/v1/runs";
        URL actual = new URL("https://proxy.example/gateway/v1/runs");
        for (String json : Arrays.asList("{}", "[]", "\"Forbidden\"", "null", "403", "true"))
            assertFalse(Reach.rejectProxy(configured, actual, 403, json));
        assertFalse(Reach.rejectProxy(configured, actual, 401, "<html>Denied</html>"));
        assertFalse(Reach.rejectProxy(configured, new URL("https://unrelated.example/gateway/v1/runs"), 403, "Denied"));
        assertFalse(Reach.rejectProxy(configured, new URL(configured), 403, "Denied"));
        assertTrue(Reach.rejectProxy(configured, actual, 403, "Denied"));
        // The API root and Codex path on the same proxy remain distinct candidates.
        assertFalse(Reach.rejectProxy(configured, new URL("https://proxy.example/codex/v1/runs"), 403, "Denied"));
    }

    @Test public void deniedEventStreamMovesToWorkingRouteWithoutReposting() throws Exception {
        try (StubServer primary = new StubServer(); StubServer backup = new StubServer()) {
            backup.json("GET /v1/runs/r/events", 403, "<html>Forbidden</html>");
            primary.sse("GET /v1/runs/r/events", "data: {\"event\":\"run.completed\",\"output\":\"ok\"}\n\n");
            selectBackup(primary, backup);
            Recorder listener = new Recorder();
            new HermesApi(primary.base(), "key", "scope").followRun("r", null, listener);
            assertEquals("ok", listener.complete);
            assertNull(listener.error);
            assertEquals(1, backup.seen.stream().filter(r -> r.method.equals("GET")).count());
            assertEquals(1, primary.seen.stream().filter(r -> r.method.equals("GET")).count());
            assertFalse(primary.seen.stream().anyMatch(r -> r.method.equals("POST")));
        }
    }

    @Test public void proxyDenialRetriesOnlyOnceWhenBothRoutesDenyThePost() throws Exception {
        try (StubServer primary = new StubServer(); StubServer backup = new StubServer()) {
            primary.json("POST /v1/runs", 403, "<html>Forbidden</html>");
            backup.json("POST /v1/runs", 403, "<html>Forbidden</html>");
            selectBackup(primary, backup);
            Recorder listener = new Recorder();
            new HermesApi(primary.base(), "key", "scope").streamChat("s", "go", listener);
            assertTrue(listener.error, listener.error.startsWith("HTTP 403"));
            assertNull(listener.complete);
            assertEquals(1, backup.seen.stream().filter(r -> r.method.equals("POST")).count());
            assertEquals(1, primary.seen.stream().filter(r -> r.method.equals("POST")).count());
        }
    }

    @Test public void aPostReadTimeoutNeverRetriesOnTheOtherRoute() throws Exception {
        java.util.concurrent.CountDownLatch release = new java.util.concurrent.CountDownLatch(1);
        try (StubServer primary = new StubServer(); StubServer backup = new StubServer()) {
            backup.on("POST /v1/runs", (r, s) -> release.await(5, java.util.concurrent.TimeUnit.SECONDS));
            selectBackup(primary, backup);
            HermesApi api = new HermesApi(primary.base(), "key", "scope");
            java.lang.reflect.Field timeout = HermesApi.class.getDeclaredField("readMs");
            timeout.setAccessible(true);
            timeout.setInt(api, 150);
            Recorder listener = new Recorder();
            api.streamChat("s", "go", listener);
            assertEquals("The server took too long to respond", listener.error);
            assertNull(listener.complete);
            assertEquals(1, backup.seen.stream().filter(r -> r.method.equals("POST")).count());
            assertFalse(primary.seen.stream().anyMatch(r -> r.method.equals("POST")));
        } finally { release.countDown(); }
    }

    @Test public void deniedLegacyStreamAndArtifactUploadRetryTheirOwnRequestOnce() throws Exception {
        try (StubServer primary = new StubServer(); StubServer backup = new StubServer()) {
            String route = "POST /api/sessions/s/chat/stream";
            backup.json(route, 403, "<html>Forbidden</html>");
            primary.sse(route, StubServer.frame("assistant.completed", "{\"content\":\"ok\"}") + StubServer.frame("done", "{}"));
            selectBackup(primary, backup);
            Recorder listener = new Recorder();
            // Non-text input selects the legacy session stream without creating a durable run.
            new HermesApi(primary.base(), "key", "scope").streamChat("s", new org.json.JSONArray(), listener);
            assertEquals("ok", listener.complete);
            assertNull(listener.error);
            assertEquals(1, backup.seen.stream().filter(r -> r.method.equals("POST")).count());
            assertEquals(1, primary.seen.stream().filter(r -> r.method.equals("POST")).count());

            backup.json("POST /v1/artifacts/upload", 496, "Client certificate required");
            primary.json("POST /v1/artifacts/upload", 200, "{\"artifact_id\":\"image\"}");
            selectBackup(primary, backup);
            assertEquals("image", new HermesApi(primary.base(), "key", "scope").uploadArtifact("image.txt", "text/plain", new byte[]{65}));
            StubServer.Request upload = primary.seen.stream().filter(r -> r.path.equals("/v1/artifacts/upload")).findFirst().get();
            assertEquals("A", upload.body);
            assertEquals("image.txt", upload.headers.get("x-artifact-filename"));
            assertEquals("text/plain", upload.headers.get("content-type"));
        }
    }

    private static final class Recorder implements HermesApi.StreamListener {
        String complete, error;
        public void onRunStarted(String value) {}
        public void onDelta(String value) {}
        public void onTool(String state, String name, String preview) {}
        public void onComplete(String value) { complete = value; }
        public void onError(String value) { error = value; }
    }

    @Test public void completeHubRoutesDoNotDuplicateHubOrInheritPublicTlsPort() {
        Reach.configure("100.64.0.17, https://hermes.example.com, https://other.example.com/hub", 8889);
        assertEquals(Arrays.asList("https://hermes.example.com/hub", "http://100.64.0.17:8791", "https://other.example.com/hub"),
                Reach.hubCandidates("https://hermes.example.com/hub"));
        Reach.hubSucceeded("https://hermes.example.com/hub", "http://100.64.0.17:8791");
        assertEquals("http://100.64.0.17:8791", Reach.hubCandidates("https://hermes.example.com/hub").get(0));
        Reach.configure("", 8889);
    }

    @Test public void hostBackupsCoverEveryPortAndUrlBackupsMapTheHub() throws Exception {
        Reach.configure("100.64.0.17, https://hermes.example.com", 8889);
        assertEquals(Arrays.asList("http://192.168.1.114:8889", "http://100.64.0.17:8889", "https://hermes.example.com:443"),
                Reach.candidates(new URL("http://192.168.1.114:8889/v1/runs")));
        assertEquals(Arrays.asList("http://192.168.1.114:8791", "http://100.64.0.17:8791", "https://hermes.example.com:443/hub"),
                Reach.candidates(new URL("http://192.168.1.114:8791/ops/summary")));
        assertEquals(Arrays.asList("http://192.168.1.114:9000", "http://100.64.0.17:9000"),
                Reach.candidates(new URL("http://192.168.1.114:9000/x")));
        Reach.configure("", 8889);
    }

    @Test public void codexBridgeFailsOverToThePublicCodexPathNotTheHermesApi() throws Exception {
        android.content.SharedPreferences prefs = NotificationOriginTest.preferences();
        prefs.edit().putString("server_type", "Codex").putString("endpoint.codex", "http://100.64.0.17:8795")
                .putString("backup_hosts", "100.64.0.17, https://hermes.example.com").apply();
        Reach.configure(new SettingsStore(prefs));
        assertEquals(Arrays.asList("http://100.64.0.17:8795", "https://hermes.example.com:443/codex"), Reach.candidates(new URL("http://100.64.0.17:8795/api/sessions")));
        Reach.configure("", 8889);
    }

    @Test public void claudeBridgeFailsOverToThePublicClaudePath() throws Exception {
        Reach.configure("100.64.0.17, https://hermes.example.com", -1);
        assertEquals(Arrays.asList("http://192.168.1.114:8796", "http://100.64.0.17:8796", "https://hermes.example.com:443/claude"),
                Reach.candidates(new URL("http://192.168.1.114:8796/api/sessions")));
        Reach.configure("", 8889);
    }

    @Test public void gatewayFailsOverToItsOwnPublicPath() throws Exception {
        Reach.configure("100.64.0.17, https://hermes.example.com", 8642);
        assertEquals(Arrays.asList("http://192.168.1.114:8797", "http://100.64.0.17:8797", "https://hermes.example.com:443/gateway"),
                Reach.candidates(new URL("http://192.168.1.114:8797/v1/runs")));
        Reach.configure("", 8889);
    }

    @Test public void voiceSocketFailsOverToTheBackupThatAnswers() throws Exception {
        try (java.net.ServerSocket server = new java.net.ServerSocket(0, 5, java.net.InetAddress.getByName("127.0.0.1"))) {
            int port = server.getLocalPort();
            Reach.configure("127.0.0.1", -1);
            try (java.net.Socket s = Reach.socket("127.0.0.2", port, 2000)) {
                assertEquals("127.0.0.1", s.getInetAddress().getHostAddress());
            }
            assertEquals("http://127.0.0.1:" + port + "/x", Reach.apply("http://127.0.0.2:" + port + "/x"));
        } finally { Reach.configure("", 8889); }
    }

    @Test public void connectTimeoutsFailOverForWritesButReadTimeoutsOnlyForGets() {
        assertTrue(Reach.isConnectFailure(new java.net.SocketTimeoutException("failed to connect to /192.168.0.5 (port 8642) after 4000ms"), "POST"));
        assertFalse(Reach.isConnectFailure(new java.net.SocketTimeoutException("Read timed out"), "POST"));
        assertTrue(Reach.isConnectFailure(new java.net.SocketTimeoutException("Read timed out"), "GET"));
    }

    @Test public void hubAddressesJoinTheListWithoutDroppingTheUsersOwn() {
        assertEquals("100.64.0.17, https://hermes.example.com, 192.168.1.114",
                Reach.merge("100.64.0.17,https://hermes.example.com", Arrays.asList("100.64.0.17", "192.168.1.114", "https://hermes.example.com")));
        assertEquals("192.168.1.114", Reach.merge("", Arrays.asList("192.168.1.114")));
    }

    @Test public void encryptedOnlyDropsPlainLanHttpButKeepsTailscaleHttpsAndLanVoice() throws Exception {
        Reach.configure("100.64.0.17, 192.168.1.114, https://hermes.example.com", 8889);
        Reach.secureOnly = true;
        try {
            assertEquals(Arrays.asList("http://100.64.0.17:8889", "https://hermes.example.com:443"),
                    Reach.candidates(new URL("http://192.168.1.114:8889/v1/runs")));
            assertEquals("http://100.64.0.17:8889/v1/runs", Reach.apply("http://192.168.1.114:8889/v1/runs"));
            assertEquals(Arrays.asList("http://100.64.0.17:8791", "https://hermes.example.com/hub"), Reach.hubCandidates("http://192.168.1.114:8791"));
            // Voice on the LAN stays a candidate: Reach.socket dials the server's mutual-TLS port for it
            assertEquals(Arrays.asList("http://192.168.1.114:10300", "http://100.64.0.17:10300"), Reach.candidates(new URL("http://192.168.1.114:10300")));
            assertEquals(10340, Reach.tlsPort(10300));
            try { Reach.checkSecure("http://192.168.1.114:8889/v1/runs"); org.junit.Assert.fail(); } catch (java.net.ConnectException expected) {}
            Reach.checkSecure("http://100.64.0.17:8889/v1/runs");
            Reach.checkSecure("https://hermes.example.com/v1/runs");
        } finally { Reach.secureOnly = false; Reach.configure("", 8889); }
    }

    @Test public void tailnetCoversCgnatRangeMagicDnsAndLoopbackOnly() {
        assertTrue(Reach.tailnet("100.64.0.1")); assertTrue(Reach.tailnet("100.127.255.1")); assertTrue(Reach.tailnet("homeserver.tail1234.ts.net"));
        assertTrue(Reach.tailnet("127.0.0.1"));
        assertFalse(Reach.tailnet("100.128.0.1")); assertFalse(Reach.tailnet("192.168.1.114")); assertFalse(Reach.tailnet("hermes.example.com"));
    }

    @Test public void gatewayProfileComesFromTheEndpointPath() {
        assertEquals("nsfw", HubApi.gatewayProfile("http://192.168.1.114:8889/p/nsfw"));
        assertEquals("nsfw", HubApi.gatewayProfile("http://192.168.1.114:8889/p/nsfw/"));
        assertEquals("", HubApi.gatewayProfile("http://192.168.1.114:8889"));
    }

    @Test public void terminalPicksTheTailscaleAddressOfTheHub() {
        assertEquals("http://100.64.0.17:8791", Reach.tailnetBase(java.util.Arrays.asList("https://hermes.example.org/hub", "http://192.168.1.114:8791", "http://100.64.0.17:8791")));
        assertNull(Reach.tailnetBase(java.util.Arrays.asList("https://hermes.example.org/hub", "http://192.168.1.114:8791")));
    }
}
