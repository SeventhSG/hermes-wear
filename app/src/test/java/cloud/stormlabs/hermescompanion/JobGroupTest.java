package cloud.stormlabs.hermescompanion;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.Arrays;
import java.util.List;
import org.json.JSONObject;
import org.junit.Test;

public class JobGroupTest {
    private static HermesApi.SessionInfo s(String id, String title, String source) throws Exception {
        return new HermesApi.SessionInfo(new JSONObject().put("id", id).put("title", title).put("source", source).put("last_active", 1000));
    }

    @Test public void jobNameStripsDateSuffix() {
        assertEquals("oxd-change-scout", ConversationList.jobName("oxd-change-scout · Oct 01 08:09"));
        assertEquals("a · b", ConversationList.jobName("a · b · Oct 01 08:09"));
        assertEquals("plain", ConversationList.jobName(" plain "));
        assertEquals("", ConversationList.jobName(null));
    }

    @Test public void jobIdFromCronSessionId() {
        assertEquals("c3f9ee61b3fd", ConversationList.jobId("cron_c3f9ee61b3fd_20261001_141042"));
        assertEquals("", ConversationList.jobId("20261001_110215_92e261"));
        assertEquals("", ConversationList.jobId(null));
    }

    @Test public void groupsOnlyScheduledRunsPerJob() throws Exception {
        List<HermesApi.SessionInfo> all = Arrays.asList(
                s("cron_aa_20261001_1", "scout · Oct 01 14:12", "cron"),
                s("api_1", "Trip", "api_server"),
                s("cron_bb_20261001_2", "other · Oct 01 10:00", "cron"),
                s("cron_aa_20260930_3", "scout · Sep 30 14:12", "cron"));
        List<ConversationList.JobGroup> g = ConversationList.groupJobs(all);
        assertEquals(2, g.size());
        assertEquals("scout", g.get(0).name);
        assertEquals(2, g.get(0).runs.size());
        assertEquals("cron_aa_20261001_1", g.get(0).latest().id);
        assertEquals(1, g.get(1).runs.size());
    }

    @Test public void titleNeededDecision() {
        assertTrue(AutoTitle.needsTitle("", "hi"));
        assertTrue(AutoTitle.needsTitle("New conversation", "hi"));
        assertTrue(AutoTitle.needsTitle("hi, plan a 3 day trip", "hi, plan a 3 day trip"));
        assertTrue(AutoTitle.needsTitle("/goal Continue work on co…", "/goal Continue work on coverage tests"));
        assertFalse(AutoTitle.needsTitle("Sofia trip", "hi, plan a 3 day trip to Sofia"));
        assertFalse(AutoTitle.needsTitle("Anything", ""));
    }

    @Test public void fallbackTitle() {
        assertEquals("Plan a trip", AutoTitle.fallback("Plan a trip", "ok"));
        assertEquals("Here is the status", AutoTitle.fallback("/goal x", "## Here is the status\nmore"));
        assertEquals("", AutoTitle.fallback("/goal x", ""));
    }
}
