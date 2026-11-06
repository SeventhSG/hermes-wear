package cloud.stormlabs.hermescompanion;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.json.JSONObject;
import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

public class OpsTextTest {
    private static SystemHealth.Reason r(int level, String text) { return new SystemHealth.Reason(level, text, "host", null); }

    @Test public void oneSeverityModel() {
        assertEquals(OpsText.CALM, OpsText.severity(SystemHealth.OK));
        assertEquals(OpsText.WATCH, OpsText.severity(SystemHealth.WARN));
        assertEquals(OpsText.CRITICAL, OpsText.severity(SystemHealth.BAD));
        assertEquals(OpsText.CRITICAL, OpsText.severity(SystemHealth.DOWN));
        assertEquals("Checking", OpsText.word(OpsText.CALM, true));
        assertEquals("Healthy", OpsText.word(OpsText.CALM, false));
        assertEquals("Attention", OpsText.word(OpsText.WATCH, false));
        assertEquals("Critical", OpsText.word(OpsText.CRITICAL, false));
    }

    @Test public void sentenceNamesTheWorstAndCountsTheRest() {
        List<SystemHealth.Reason> rs = new ArrayList<>();
        assertEquals("Hermes and host are fine", OpsText.sentence(rs, false, "Hermes and host are fine"));
        assertEquals("Checking Hermes and the host", OpsText.sentence(rs, true, "x"));
        rs.add(r(SystemHealth.BAD, "Disk 98% on /vault"));
        assertEquals("Disk 98% on /vault", OpsText.sentence(rs, false, "x"));
        for (int i = 0; i < 5; i++) rs.add(r(SystemHealth.WARN, "w" + i));
        assertEquals("Disk 98% on /vault · 5 more", OpsText.sentence(rs, false, "x"));
    }

    @Test public void hermesHumanFirst() {
        assertEquals("Idle", OpsText.hermesHeadline("0", "false"));
        assertEquals("Idle", OpsText.hermesHeadline("", ""));
        assertEquals("Working on 1 task", OpsText.hermesHeadline("1", "false"));
        assertEquals("Working on 2 tasks", OpsText.hermesHeadline("2", "true"));
        assertEquals("Working", OpsText.hermesHeadline("0", "true"));
        assertEquals("Gateway running", OpsText.gatewayLine("Running"));
        assertEquals("", OpsText.gatewayLine(""));
    }

    @Test public void dockerStatusInPlainWords() {
        assertEquals("Stopped 5 days ago · exit 137", OpsText.dockerStatus("Exited (137) 5 days ago"));
        assertEquals("Stopped 2 hours ago", OpsText.dockerStatus("Exited (0) 2 hours ago"));
        assertEquals("Running 4 days · healthy", OpsText.dockerStatus("Up 4 days (healthy)"));
        assertEquals("Running 3 hours", OpsText.dockerStatus("Up 3 hours"));
        assertEquals("Restarting · exit 1 · 5 seconds ago", OpsText.dockerStatus("Restarting (1) 5 seconds ago"));
        assertEquals("Created, never started", OpsText.dockerStatus("Created"));
        assertEquals("active", OpsText.dockerStatus("active"));
        assertEquals("", OpsText.dockerStatus(null));
    }

    private static HubApi.PveHost.Storage st(String name, String status, double total, double used) throws Exception {
        JSONObject o = new JSONObject().put("name", name).put("type", "zfs").put("status", status).put("total", total).put("used", used);
        return new HubApi.PveHost.Storage(o);
    }

    @Test public void storageWorstFirstHealthyCollapsed() throws Exception {
        List<HubApi.PveHost.Storage> in = new ArrayList<>();
        in.add(st("/", "active", 100, 40));
        in.add(st("/vault", "active", 100, 98));
        in.add(st("/empty", "active", 0, 0));
        in.add(st("/backup", "unreachable", 0, 0));
        in.add(st("/tank", "active", 100, 85));
        List<HubApi.PveHost.Storage> out = OpsText.sortStorage(in);
        assertEquals(4, out.size());
        assertEquals("/backup", out.get(0).name);
        assertEquals("/vault", out.get(1).name);
        assertEquals("/tank", out.get(2).name);
        assertEquals("/", out.get(3).name);
        assertFalse(OpsText.storageHealthy(out.get(0)));
        assertFalse(OpsText.storageHealthy(out.get(2)));
        assertTrue(OpsText.storageHealthy(out.get(3)));
    }

    @Test public void worstMetricPicksTheMostPressured() {
        OpsText.Worst w = OpsText.worst(12, 40, 91, 55);
        assertEquals("Storage", w.label);
        assertEquals("91%", w.display);
        assertEquals("Temp", OpsText.worst(12, 40, 60, 88).label);
        assertEquals("88°", OpsText.worst(12, 40, 60, 88).display);
        assertEquals("CPU", OpsText.worst(30, Double.NaN, Double.NaN, Double.NaN).label);
        assertNull(OpsText.worst(Double.NaN, Double.NaN, Double.NaN, Double.NaN));
    }

    @Test public void agentFailureTitle() {
        assertEquals("morning-briefing couldn't run", OpsText.agentFailure("morning-briefing"));
        assertEquals("An agent couldn't run", OpsText.agentFailure(""));
    }

    @org.junit.Test public void systemdSinceReadsAsAnAge() throws Exception {
        long now = new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.ROOT).parse("2026-10-01 09:19:38").getTime();
        org.junit.Assert.assertEquals("Running 6 days", OpsText.systemdStatus("active", "running", "Fri 2026-09-25 09:19:38 EEST", now));
        org.junit.Assert.assertEquals("failed \u00b7 failed \u00b7 for 3 hours", OpsText.systemdStatus("failed", "failed", "Thu 2026-10-01 06:19:38 EEST", now));
        org.junit.Assert.assertEquals("inactive \u00b7 dead", OpsText.systemdStatus("inactive", "dead", "", now));
    }

    @org.junit.Test public void proxmoxTaskLabels() {
        java.util.Map<String, String> names = new java.util.HashMap<>(); names.put("108", "servarr");
        org.junit.Assert.assertEquals("Backup of servarr", OpsText.taskLabel("vzdump", "108", names));
        org.junit.Assert.assertEquals("Shut down 150", OpsText.taskLabel("vzshutdown", "150", names));
        org.junit.Assert.assertEquals("Update package lists", OpsText.taskLabel("aptupdate", "", names));
        org.junit.Assert.assertEquals("Stop all guests", OpsText.taskLabel("stopall", "", names));
        org.junit.Assert.assertEquals("hamigrate 5", OpsText.taskLabel("hamigrate", "5", null));
    }
}
