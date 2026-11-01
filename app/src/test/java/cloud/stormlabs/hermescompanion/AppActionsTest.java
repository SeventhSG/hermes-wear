package cloud.stormlabs.hermescompanion;

import static org.junit.Assert.assertEquals;

import org.json.JSONObject;
import org.junit.Test;

public class AppActionsTest {
    @Test public void expandsAppActionsUrlTemplates() throws Exception {
        assertEquals("tg://search?query=Hermes%20bot", AppActions.expand("tg://search{?query}", new JSONObject().put("query", "Hermes bot")));
        assertEquals("tg://call?name=Ivan&phone=%2B359888", AppActions.expand("tg://call{?format,name,phone}", new JSONObject().put("name", "Ivan").put("phone", "+359888")));
        assertEquals("tg://search", AppActions.expand("tg://search{?query}", new JSONObject()));
        assertEquals("app://item/42", AppActions.expand("app://item/{id}", new JSONObject().put("id", "42")));
    }
}
