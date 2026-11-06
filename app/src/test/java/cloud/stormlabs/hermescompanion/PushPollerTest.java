package cloud.stormlabs.hermescompanion;

import org.json.JSONObject;
import org.junit.Test;

import java.util.Arrays;
import java.util.List;

import static org.junit.Assert.assertEquals;

public class PushPollerTest {
    @Test public void newEventsFiltersBlankAndOldBodiesAndSortsById() throws Exception {
        List<HubApi.Event> events = Arrays.asList(event(5, "newest"), event(2, "old"),
                event(4, "  \n "), event(3, "first"));
        List<HubApi.Event> fresh = PushPoller.newEvents(events, 2);
        assertEquals(2, fresh.size());
        assertEquals(3, fresh.get(0).id);
        assertEquals(5, fresh.get(1).id);
    }

    private static HubApi.Event event(long id, String body) throws Exception {
        return new HubApi.Event(new JSONObject().put("id", id).put("body", body));
    }
}
