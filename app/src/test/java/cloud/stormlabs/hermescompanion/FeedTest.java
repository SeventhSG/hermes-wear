package cloud.stormlabs.hermescompanion;

import static org.junit.Assert.assertEquals;

import java.util.Arrays;
import java.util.List;
import org.json.JSONObject;
import org.junit.Test;

public class FeedTest {
    private static HubApi.Event ev(long id, String type, String title) throws Exception {
        return new HubApi.Event(new JSONObject().put("id", id).put("type", type).put("title", title));
    }

    @Test public void collapsesConsecutiveTitlesOnly() throws Exception {
        List<Feed.Row> rows = Feed.collapse(Arrays.asList(ev(5, "job", "A"), ev(4, "job", "A"), ev(3, "job", "B"), ev(2, "job", "A")));
        assertEquals(3, rows.size());
        assertEquals(2, rows.get(0).count);
        assertEquals(5, rows.get(0).e.id);
        assertEquals(1, rows.get(2).count);
    }

    @Test public void filtersByKind() throws Exception {
        List<Feed.Row> rows = Feed.collapse(Arrays.asList(ev(3, "alert", "x"), ev(2, "recovered", "y"), ev(1, "job", "z")));
        assertEquals(3, Feed.filter(rows, "all").size());
        assertEquals(1, Feed.filter(rows, "alert").size());
        assertEquals(1, Feed.filter(rows, "info").size());
        assertEquals(1, Feed.filter(rows, "job").size());
    }

    @Test public void agoLabels() {
        long now = 1_000_000_000_000L;
        assertEquals("now", Feed.ago(now / 1000.0 - 20, now));
        assertEquals("4 min ago", Feed.ago(now / 1000.0 - 240, now));
        assertEquals("3 h ago", Feed.ago(now / 1000.0 - 3 * 3600, now));
        assertEquals("2 d ago", Feed.ago(now / 1000.0 - 2 * 86400, now));
        assertEquals("", Feed.ago(0, now));
    }
}
