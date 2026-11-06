package cloud.stormlabs.hermescompanion;

import static org.junit.Assert.*;
import org.json.JSONObject;
import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

public class JobSortTest {
    private static HermesApi.Job job(String name, String state, String next, String last, String lastStatus, String error, boolean enabled) throws Exception {
        return new HermesApi.Job(new JSONObject().put("id", name).put("name", name).put("state", state).put("next_run_at", next)
                .put("last_run_at", last).put("last_status", lastStatus).put("last_error", error).put("enabled", enabled));
    }

    private static List<String> names(List<HermesApi.Job> jobs) {
        List<String> out = new ArrayList<>();
        for (HermesApi.Job j : jobs) out.add(j.name);
        return out;
    }

    private HermesApi.Job a, b, c, d, e, f;

    private List<HermesApi.Job> fixture() throws Exception {
        a = job("alpha", "scheduled", "2026-10-14T20:00:00Z", "2026-10-14T08:00:00Z", "ok", "", true);
        b = job("bravo", "scheduled", "2026-10-14T15:00:00Z", "2026-10-13T08:00:00Z", "ok", "", true);
        c = job("charlie", "scheduled", "2026-10-14T12:00:00Z", "2026-10-14T06:00:00Z", "error", "boom", true);
        d = job("delta", "paused", "2026-01-01T00:00:00Z", "2026-09-01T00:00:00Z", "ok", "", false);
        e = job("echo", "running", "", "", "", "", true);
        f = job("foxtrot", "completed", "", "2026-10-01T00:00:00Z", "ok", "", true);
        return Arrays.asList(a, b, c, d, e, f);
    }

    @Test public void buckets() throws Exception {
        fixture();
        assertEquals(JobSort.FAILING, JobSort.bucket(c));
        assertEquals(JobSort.RUNNING, JobSort.bucket(e));
        assertEquals(JobSort.SCHEDULED, JobSort.bucket(a));
        assertEquals(JobSort.PAUSED, JobSort.bucket(d));
        assertEquals(JobSort.FINISHED, JobSort.bucket(f));
    }

    @Test public void smartOrderPutsTroubleFirstThenUpcomingBySoonestAndPausedLast() throws Exception {
        assertEquals(Arrays.asList("charlie", "echo", "bravo", "alpha", "delta", "foxtrot"),
                names(JobSort.sort(fixture(), JobSort.Mode.SMART)));
    }

    @Test public void nextRunOrdersByTimeAndSinksPausedAndUnscheduled() throws Exception {
        List<String> n = names(JobSort.sort(fixture(), JobSort.Mode.NEXT));
        assertEquals(Arrays.asList("charlie", "bravo", "alpha"), n.subList(0, 3));
        assertTrue(n.indexOf("delta") > n.indexOf("alpha"));      // a stale next run of a paused job must not float up
    }

    @Test public void lastRunIsNewestFirstAndNeverRunLast() throws Exception {
        List<String> n = names(JobSort.sort(fixture(), JobSort.Mode.LAST));
        assertEquals("alpha", n.get(0));
        assertEquals("echo", n.get(n.size() - 1));
    }

    @Test public void nameOrderIgnoresCase() throws Exception {
        List<HermesApi.Job> jobs = Arrays.asList(job("beta", "scheduled", "", "", "", "", true), job("Alpha", "scheduled", "", "", "", "", true), job("gamma", "scheduled", "", "", "", "", true));
        assertEquals(Arrays.asList("Alpha", "beta", "gamma"), names(JobSort.sort(jobs, JobSort.Mode.NAME)));
    }

    @Test public void unreadableTimesDoNotCrashAndModeIdsRoundTrip() throws Exception {
        List<HermesApi.Job> jobs = Arrays.asList(job("x", "scheduled", "soon-ish", "", "", "", true), job("y", "scheduled", "2026-10-14T12:00:00Z", "", "", "", true));
        assertEquals("y", JobSort.sort(jobs, JobSort.Mode.NEXT).get(0).name);
        assertSame(JobSort.Mode.NEXT, JobSort.Mode.of("next"));
        assertSame(JobSort.Mode.SMART, JobSort.Mode.of("nonsense"));
        assertTrue(JobSort.grouped(JobSort.Mode.SMART));
        assertFalse(JobSort.grouped(JobSort.Mode.NAME));
    }
}
