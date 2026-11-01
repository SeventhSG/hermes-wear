package cloud.stormlabs.hermescompanion;

import org.json.JSONObject;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/** The hermes-hub wire protocol: push, events, job outputs, files, search and ops. */
public class HubApiTest {
    private StubServer server;

    @Before public void start() { Reach.configure("", 8889); }
    @After public void stop() throws Exception { if (server != null) server.close(); Reach.configure("", 8889); }

    private HubApi api() { return new HubApi(server.base(), "k"); }

    @Test public void healthReportsVersion() throws Exception {
        server = new StubServer();
        server.json("GET /health", 200, "{\"ok\":true,\"version\":\"1\"}");
        HermesApi.Result r = api().health();
        assertTrue(r.text, r.ok);
        assertTrue(r.text, r.text.contains("v1"));
        assertEquals("Bearer k", server.seen.get(0).headers.get("authorization"));
    }

    @Test public void registerPushSendsEndpoint() throws Exception {
        server = new StubServer();
        server.json("POST /push/register", 200, "{\"ok\":true}");
        api().registerPush("https://ntfy.sh/abc123");
        JSONObject body = new JSONObject(server.seen.get(0).body);
        assertEquals("https://ntfy.sh/abc123", body.getString("endpoint"));
    }

    @Test public void notifyPostsFieldsAndReturnsId() throws Exception {
        server = new StubServer();
        server.json("POST /notify", 200, "{\"ok\":true,\"id\":42}");
        long id = api().notify("Title", "Body", "job", "5", "sess1", "job1", "https://x");
        assertEquals(42, id);
        JSONObject body = new JSONObject(server.seen.get(0).body);
        assertEquals("Title", body.getString("title"));
        assertEquals("job", body.getString("type"));
        assertEquals("5", body.getString("priority"));
        assertEquals("sess1", body.getString("session_id"));
        assertEquals("job1", body.getString("job_id"));
        assertEquals("https://x", body.getString("url"));
    }

    @Test public void eventsParsesEveryField() throws Exception {
        server = new StubServer();
        server.json("GET /events", 200, "{\"events\":[{\"id\":7,\"ts\":123.5,\"type\":\"alert\",\"title\":\"t\",\"body\":\"b\",\"priority\":\"5\",\"session_id\":\"s\",\"job_id\":\"j\",\"url\":\"u\"}]}");
        List<HubApi.Event> events = api().events(3, 50);
        assertEquals(1, events.size());
        HubApi.Event e = events.get(0);
        assertEquals(7, e.id);
        assertEquals("alert", e.type);
        assertEquals("t", e.title);
        assertEquals("b", e.body);
        assertEquals("5", e.priority);
        assertEquals("s", e.sessionId);
        assertEquals("j", e.jobId);
        assertEquals("u", e.url);
        assertEquals("since=3&limit=50", server.seen.get(0).query);
    }

    @Test public void jobOutputsRoundTrip() throws Exception {
        server = new StubServer();
        server.json("GET /jobs/job1/outputs", 200, "{\"outputs\":[{\"ts\":1,\"name\":\"n\",\"text\":\"hello\"}]}");
        List<HubApi.JobOutput> out = api().jobOutputs("job1", 10);
        assertEquals(1, out.size());
        assertEquals("n", out.get(0).name);
        assertEquals("hello", out.get(0).text);
    }

    @Test public void uploadFileSendsHeadersAndBody() throws Exception {
        server = new StubServer();
        server.json("POST /files", 200, "{\"ok\":true,\"path\":\"/data/x.txt\"}");
        String path = api().uploadFile("x.txt", "text/plain", "hi".getBytes(StandardCharsets.UTF_8));
        assertEquals("/data/x.txt", path);
        StubServer.Request req = server.seen.get(0);
        assertEquals("x.txt", req.headers.get("x-filename"));
        assertEquals("text/plain", req.headers.get("content-type"));
        assertEquals("hi", req.body);
    }

    @Test public void searchParsesResultsAndEncodesQuery() throws Exception {
        server = new StubServer();
        server.json("GET /search", 200, "{\"results\":[{\"session_id\":\"s\",\"title\":\"t\",\"message_id\":\"m\",\"role\":\"user\",\"snippet\":\"hey there\",\"ts\":9}]}");
        List<HubApi.SearchResult> results = api().search("hey there", 30);
        assertEquals(1, results.size());
        assertEquals("hey there", results.get(0).snippet);
        assertTrue(server.seen.get(0).query, server.seen.get(0).query.contains("q=hey"));
    }

    @Test public void opsServicesParsesServicesAndHost() throws Exception {
        server = new StubServer();
        server.json("GET /ops/services", 200, "{\"services\":[{\"name\":\"gateway\",\"kind\":\"systemd\",\"state\":\"running\",\"sub\":\"running\",\"since\":\"1h\",\"description\":\"d\"}],"
                + "\"host\":{\"cpu_pct\":12.5,\"load1\":0.4,\"mem_used_mb\":100,\"mem_total_mb\":200,\"disk_used_gb\":1.5,\"disk_total_gb\":10,\"uptime_s\":3600}}");
        HubApi.Services s = api().opsServices();
        assertEquals(1, s.services.size());
        assertEquals("gateway", s.services.get(0).name);
        assertEquals("running", s.services.get(0).state);
        assertEquals(12.5, s.host.cpuPct, 0.001);
        assertEquals(200, s.host.memTotalMb);
    }

    @Test public void opsLogsAndRestart() throws Exception {
        server = new StubServer();
        server.json("GET /ops/logs", 200, "{\"lines\":[\"a\",\"b\"]}");
        assertEquals(2, api().opsLogs("gateway", 200).size());
        server.json("GET /ops/logs", 200, "{\"ok\":true,\"lines\":[\"a\"],\"cursor\":\"s=1;i=2\"}");
        HubApi.LogPage pg = api().opsLogs("gateway", 200, "");
        assertEquals(1, pg.lines.size());
        assertEquals("s=1;i=2", pg.cursor);
        assertTrue(server.seen.get(server.seen.size() - 1).query.contains("after="));
        server.json("POST /ops/restart", 200, "{\"ok\":true,\"state\":\"running\"}");
        assertEquals("running", api().opsRestart("gateway"));
        JSONObject body = new JSONObject(server.seen.get(server.seen.size() - 1).body);
        assertEquals("gateway", body.getString("name"));
    }

    @Test public void httpErrorIsReadable() throws Exception {
        server = new StubServer();
        server.json("GET /health", 401, "{\"error\":{\"message\":\"Invalid API key\"}}");
        HermesApi.Result r = api().health();
        assertTrue(r.text, r.text.contains("Invalid API key"));
    }

    @Test public void defaultHubUrlDerivesHostAndFixedPort() {
        assertEquals("http://hermes.local:8791", HubApi.defaultFor("http://hermes.local:8420"));
        assertEquals("http://hermes.local:8791", HubApi.defaultFor("https://hermes.local:8420/p/worker"));
    }
    private static final class FakeConnection extends java.net.HttpURLConnection {
        final int status;
        final byte[] data;
        final long length;
        FakeConnection(java.net.URL url, int status, String data, long length) {
            super(url); this.status = status; this.data = data.getBytes(StandardCharsets.UTF_8); this.length = length;
        }
        @Override public int getResponseCode() { return status; }
        @Override public java.io.InputStream getInputStream() { return new java.io.ByteArrayInputStream(data); }
        @Override public java.io.InputStream getErrorStream() { return getInputStream(); }
        @Override public long getContentLengthLong() { return length; }
        @Override public void disconnect() { }
        @Override public boolean usingProxy() { return false; }
        @Override public void connect() { }
    }

    @Test public void latestRetriesFailedPrimaryAndRemembersBackup() throws Exception {
        Reach.configure("https://backup.example", 8889);
        java.util.List<FakeConnection> calls = new java.util.ArrayList<>();
        HubApi client = new HubApi("http://primary.example:8791", "hub-key", url -> {
            FakeConnection c = new FakeConnection(url, url.getHost().equals("primary.example") ? 503 : 200, "{\"version\":42}", -1);
            calls.add(c); return c;
        });
        assertEquals(42, client.appLatest().getInt("version"));
        assertEquals(2, calls.size());
        assertEquals("/hub/app/latest", calls.get(1).getURL().getPath());
        assertEquals("Bearer hub-key", calls.get(1).getRequestProperty("Authorization"));
        assertTrue(!calls.get(1).getInstanceFollowRedirects());
        assertEquals(2000, calls.get(1).getConnectTimeout());
        assertEquals(4000, calls.get(1).getReadTimeout());
        client.appLatest();
        assertEquals("backup.example", calls.get(2).getURL().getHost());
    }

    @Test public void latestRetriesConnectionIoFailure() throws Exception {
        Reach.configure("https://backup.example", 8889);
        java.util.List<java.net.URL> calls = new java.util.ArrayList<>();
        HubApi client = new HubApi("http://primary.example:8791", "hub-key", url -> {
            calls.add(url);
            if (url.getHost().equals("primary.example")) throw new java.net.ConnectException("offline");
            return new FakeConnection(url, 200, "{\"version\":43}", -1);
        });
        assertEquals(43, client.appLatest().getInt("version"));
        assertEquals(2, calls.size());
    }

    @Test public void latestDoesNotRetryAuthenticationOrRedirect() throws Exception {
        Reach.configure("https://backup.example", 8889);
        for (int status : new int[]{401, 403, 302}) {
            java.util.List<java.net.URL> calls = new java.util.ArrayList<>();
            HubApi client = new HubApi("http://primary.example:8791", "hub-key", url -> {
                calls.add(url); return new FakeConnection(url, status, "{\"error\":\"denied\"}", -1);
            });
            try { client.appLatest(); org.junit.Assert.fail("Expected HTTP failure"); }
            catch (IllegalStateException expected) { }
            assertEquals(1, calls.size());
        }
    }

    @Test public void apkRetriesTruncationWithFreshDigestFileAndProgress() throws Exception {
        Reach.configure("https://backup.example", 8889);
        java.util.List<Long> progress = new java.util.ArrayList<>();
        HubApi client = new HubApi("http://primary.example:8791", "hub-key", url ->
                new FakeConnection(url, 200, url.getHost().equals("primary.example") ? "partial" : "complete", 8));
        java.io.File file = java.io.File.createTempFile("hub-update", ".apk");
        try {
            String digest = client.appApk(file, progress::add);
            assertEquals("complete", new String(java.nio.file.Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8));
            assertEquals(Updater.hex(java.security.MessageDigest.getInstance("SHA-256").digest("complete".getBytes(StandardCharsets.UTF_8))), digest);
            assertEquals(java.util.Arrays.asList(0L, 7L, 0L, 8L), progress);
        } finally { file.delete(); }
    }

    @Test public void exhaustedApkTransferDeletesPartialFile() throws Exception {
        HubApi client = new HubApi("http://primary.example:8791", "hub-key", url -> new FakeConnection(url, 200, "partial", 100));
        java.io.File file = java.io.File.createTempFile("hub-update", ".apk");
        try { client.appApk(file, n -> { }); org.junit.Assert.fail("Expected truncated APK failure"); }
        catch (java.io.IOException expected) { }
        assertTrue(!file.exists());
    }

}
