package cloud.stormlabs.hermescompanion;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.json.JSONObject;
import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

public class SystemHealthTest {
    private static HubApi.Service service(String name, String state) throws Exception {
        return new HubApi.Service(new JSONObject().put("name", name).put("state", state).put("kind", "systemd"));
    }

    private static HubApi.Services ops(double diskUsed, HubApi.Service... services) throws Exception {
        JSONObject host = new JSONObject().put("hostname", "homeserver").put("cpu_pct", 10).put("mem_used_mb", 1000).put("mem_total_mb", 8000)
                .put("disk_used_gb", diskUsed).put("disk_total_gb", 100);
        return new HubApi.Services(new ArrayList<>(Arrays.asList(services)), new HubApi.Host(host));
    }

    private static final String DEGRADED_DISK = "{\"gateway_state\":\"running\",\"readiness\":{\"checks\":{"
            + "\"disk\":{\"status\":\"degraded\",\"used_percent\":92.9,\"free_bytes\":2396303360}}}}";

    @Test public void allGoodWhenNothingReportsAProblem() throws Exception {
        List<SystemHealth.Reason> r = SystemHealth.reasons(new JSONObject("{\"gateway_state\":\"running\",\"status\":\"ok\"}"), null,
                ops(40, service("hermes-gateway", "active")), null, 0);
        assertTrue(r.isEmpty());
        assertEquals("All good", SystemHealth.label(SystemHealth.overall(r)));
    }

    @Test public void unreachableHermesIsDownAndFirst() throws Exception {
        List<SystemHealth.Reason> r = SystemHealth.reasons(null, "Connection refused", ops(96), null, 0);
        assertEquals(SystemHealth.DOWN, SystemHealth.overall(r));
        assertEquals("Down", SystemHealth.label(SystemHealth.overall(r)));
        assertEquals("Agent unreachable: Connection refused", r.get(0).text);
        assertEquals("Disk 96% full on homeserver", r.get(1).text);
        assertEquals("host", r.get(1).section);
    }

    @Test public void failedServiceNeedsAttentionAndCarriesTheService() throws Exception {
        HubApi.Service failed = service("hermes-gateway", "failed");
        List<SystemHealth.Reason> r = SystemHealth.reasons(null, null, ops(10, service("ntfy", "active"), failed), null, 0);
        assertEquals(1, r.size());
        assertEquals("Needs attention", SystemHealth.label(SystemHealth.overall(r)));
        assertEquals("hermes-gateway failed", r.get(0).text);
        assertEquals(failed, r.get(0).service);
    }

    @Test public void readinessDiskIsDroppedWhenHostMetricExists() throws Exception {
        JSONObject health = new JSONObject(DEGRADED_DISK);
        assertTrue(SystemHealth.reasons(health, null, ops(50), null, 0).isEmpty());
        List<SystemHealth.Reason> noHub = SystemHealth.reasons(health, null, null, null, 0);
        assertEquals(1, noHub.size());
        assertTrue(noHub.get(0).text.endsWith("(Hermes server)"));
    }

    @Test public void budgetAndGatewayAndPlatformReasons() throws Exception {
        JSONObject health = new JSONObject("{\"gateway_state\":\"degraded\",\"metrics_today\":{\"tokens\":1500},"
                + "\"platforms\":{\"telegram\":{\"state\":\"connected\"},\"discord\":\"disconnected\"}}");
        List<SystemHealth.Reason> r = SystemHealth.reasons(health, null, null, null, 1000);
        assertEquals(3, r.size());
        assertEquals("Gateway degraded", r.get(0).text);
        assertTrue(r.get(1).text.startsWith("Daily token budget exceeded"));
        assertEquals("platforms", r.get(2).section);
        assertEquals(SystemHealth.WARN, r.get(2).level);
    }

    @Test public void stateLevels() {
        assertEquals(SystemHealth.OK, SystemHealth.stateLevel("running"));
        assertEquals(SystemHealth.OK, SystemHealth.stateLevel(""));
        assertEquals(SystemHealth.BAD, SystemHealth.stateLevel("not_ready"));
        assertEquals(SystemHealth.BAD, SystemHealth.stateLevel("unhealthy"));
        assertEquals(SystemHealth.DOWN, SystemHealth.stateLevel("stopped"));
        assertEquals(SystemHealth.BAD, SystemHealth.serviceLevel("inactive"));
        assertEquals(SystemHealth.WARN, SystemHealth.serviceLevel("activating"));
        assertEquals(SystemHealth.OK, SystemHealth.serviceLevel("running"));
    }

    @Test public void servicesSortFailedFirstThenByName() throws Exception {
        List<HubApi.Service> sorted = SystemHealth.sortServices(Arrays.asList(
                service("b", "active"), service("z", "failed"), service("a", "active"), service("m", "activating")));
        assertEquals("z", sorted.get(0).name);
        assertEquals("m", sorted.get(1).name);
        assertEquals("a", sorted.get(2).name);
        assertEquals("b", sorted.get(3).name);
    }

    @Test public void meterThresholdsAndFormatting() throws Exception {
        assertEquals(0, HostThresholds.percent(79.9));
        assertEquals(1, HostThresholds.percent(80));
        assertEquals(2, HostThresholds.percent(90));
        assertEquals("2d 3h", SystemHealth.uptime(2 * 86400 + 3 * 3600 + 59));
        assertEquals("45m", SystemHealth.uptime(45 * 60));
        assertTrue(Double.isNaN(SystemHealth.cost(new JSONObject())));
        assertEquals(1.25, SystemHealth.cost(new JSONObject().put("cost_usd", 1.25)), 1e-9);
        assertFalse(SystemHealth.reasons(null, null, ops(85), null, 0).isEmpty());
    }

    @Test public void dockerStatesAreClassified() {
        org.junit.Assert.assertEquals(SystemHealth.OK, SystemHealth.serviceLevel("Up 4 days"));
        org.junit.Assert.assertEquals(SystemHealth.OK, SystemHealth.serviceLevel("Up 4 days (healthy)"));
        org.junit.Assert.assertEquals(SystemHealth.BAD, SystemHealth.serviceLevel("Up 2 hours (unhealthy)"));
        org.junit.Assert.assertEquals(SystemHealth.WARN, SystemHealth.serviceLevel("Up 5 seconds (health: starting)"));
        org.junit.Assert.assertEquals(SystemHealth.WARN, SystemHealth.serviceLevel("Created"));
        org.junit.Assert.assertEquals(SystemHealth.WARN, SystemHealth.serviceLevel("Exited (0) 3 days ago"));
        org.junit.Assert.assertEquals(SystemHealth.BAD, SystemHealth.serviceLevel("Exited (137) 4 days ago"));
        org.junit.Assert.assertEquals(SystemHealth.OK, SystemHealth.serviceLevel("active"));
    }

    @Test public void backupAgeColours() {
        assertEquals(SystemHealth.OK, SystemHealth.backupLevel(1.9, "OK"));
        assertEquals(SystemHealth.WARN, SystemHealth.backupLevel(2, "OK"));
        assertEquals(SystemHealth.WARN, SystemHealth.backupLevel(6.9, "OK"));
        assertEquals(SystemHealth.BAD, SystemHealth.backupLevel(7, "OK"));
        assertEquals(SystemHealth.NONE, SystemHealth.backupLevel(-1, ""));
        assertEquals(SystemHealth.BAD, SystemHealth.backupLevel(0.5, "job errors"));
    }

    @Test public void latencyColours() {
        assertEquals(SystemHealth.OK, SystemHealth.latencyLevel(99));
        assertEquals(SystemHealth.WARN, SystemHealth.latencyLevel(100));
        assertEquals(SystemHealth.BAD, SystemHealth.latencyLevel(300));
        assertEquals(SystemHealth.BAD, SystemHealth.latencyLevel(-1));
    }

    @Test public void peersSortOnlineFirstThenName() throws Exception {
        HubApi.Network n = new HubApi.Network(new JSONObject("{\"peers\":[{\"name\":\"b\",\"online\":false},{\"name\":\"Z\",\"online\":true},{\"name\":\"a\",\"online\":true}]}"));
        assertEquals("a", n.peers.get(0).name);
        assertEquals("Z", n.peers.get(1).name);
        assertEquals("b", n.peers.get(2).name);
        assertEquals(2, n.online());
    }

    @Test public void onlyFailedBackupsAreAlerts() throws Exception {
        HubApi.Backups b = new HubApi.Backups(new JSONObject("{\"guests\":[{\"vmid\":1,\"name\":\"x\",\"last_status\":\"\",\"age_days\":null},"
                + "{\"vmid\":2,\"name\":\"y\",\"last_status\":\"job errors\",\"age_days\":3}]}"));
        List<SystemHealth.Reason> r = SystemHealth.backupReasons(b);
        assertEquals(1, r.size());
        assertEquals(SystemHealth.WARN, r.get(0).level);
    }

    @Test public void updatesLineAndReasons() throws Exception {
        HubApi.Updates u = new HubApi.Updates(new JSONObject("{\"hosts\":[{\"name\":\"a\",\"packages\":12,\"security\":3,\"reboot_required\":true,\"sample\":[\"p 1 → 2\"]},"
                + "{\"name\":\"b\",\"packages\":0,\"security\":0}],\"hermes\":{\"current\":\"1.0\",\"latest\":null}}"));
        assertEquals("a · 12 updates · 3 security · reboot needed", u.hosts.get(0).line());
        assertEquals("b · up to date", u.hosts.get(1).line());
        assertEquals("1.0", u.hermes);
        assertEquals(2, SystemHealth.updateReasons(u).size());
        assertEquals(0, SystemHealth.updateReasons(null).size());
    }

    @org.junit.Test public void certificateLevelsAndText() {
        org.junit.Assert.assertEquals(SystemHealth.OK, SystemHealth.certLevel(35, ""));
        org.junit.Assert.assertEquals(SystemHealth.WARN, SystemHealth.certLevel(10, ""));
        org.junit.Assert.assertEquals(SystemHealth.BAD, SystemHealth.certLevel(3, ""));
        org.junit.Assert.assertEquals(SystemHealth.BAD, SystemHealth.certLevel(Integer.MIN_VALUE, "invalid: self-signed"));
        org.junit.Assert.assertEquals("35 days left", SystemHealth.certText(35, ""));
        org.junit.Assert.assertEquals("Expired", SystemHealth.certText(-1, ""));
        org.junit.Assert.assertEquals("Couldn\u2019t check", SystemHealth.certText(Integer.MIN_VALUE, "unreachable: TimeoutError"));
    }

    @org.junit.Test public void degradedNamesItsChecksAndSkipsDiskTheHubAlreadyShows() throws Exception {
        String disk = "{\"gateway_state\":\"running\",\"status\":\"degraded\",\"readiness\":{\"checks\":{\"disk\":{\"status\":\"degraded\"},\"model\":{\"status\":\"ok\"}}}}";
        for (SystemHealth.Reason r : SystemHealth.reasons(new JSONObject(disk), null, ops(50), null, 0))
            assertFalse(r.text, r.text.startsWith("Hermes reports"));
        String model = disk.replace("\"model\":{\"status\":\"ok\"}", "\"model\":{\"status\":\"error\"}").replace("\"disk\":{\"status\":\"degraded\"}", "\"disk\":{\"status\":\"ok\"}");
        boolean named = false;
        for (SystemHealth.Reason r : SystemHealth.reasons(new JSONObject(model), null, ops(50), null, 0)) named |= r.text.toLowerCase(java.util.Locale.ROOT).contains("model");
        assertTrue(named);
    }

    @org.junit.Test public void syncDeviceAge() {
        long day = 86_400_000L, now = 1_000 * day;
        assertEquals(SystemHealth.OK, SystemHealth.syncDeviceLevel(true, 0, now));
        assertEquals(SystemHealth.OK, SystemHealth.syncDeviceLevel(false, now - day / 2, now));
        assertEquals(SystemHealth.WARN, SystemHealth.syncDeviceLevel(false, now - 2 * day, now));
        assertEquals(SystemHealth.BAD, SystemHealth.syncDeviceLevel(false, now - 160 * day, now));
    }
}
