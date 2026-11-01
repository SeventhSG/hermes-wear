package cloud.stormlabs.hermescompanion;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.net.URL;
import java.util.Arrays;

public class ReachTest {
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
        assertTrue(Reach.tailnet("100.64.0.1")); assertTrue(Reach.tailnet("100.127.255.1")); assertTrue(Reach.tailnet("openclaw.taile537dc.ts.net"));
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
