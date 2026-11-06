package cloud.stormlabs.hermescompanion;

import android.content.SharedPreferences;
import java.lang.reflect.Proxy;
import java.util.HashMap;
import java.util.Map;
import org.junit.Test;
import static org.junit.Assert.*;

public class NotificationOriginTest {
    static SharedPreferences preferences() {
        Map<String, Object> values = new HashMap<>();
        return (SharedPreferences) Proxy.newProxyInstance(SharedPreferences.class.getClassLoader(),
                new Class<?>[]{SharedPreferences.class}, (proxy, method, args) -> {
            if (method.getName().equals("getAll")) return new HashMap<>(values);
            if (method.getName().equals("contains")) return values.containsKey(args[0]);
            if (method.getName().startsWith("get")) return values.getOrDefault(args[0], args[1]);
            if (method.getName().equals("edit")) {
                Map<String, Object> pending = new HashMap<>();
                return Proxy.newProxyInstance(SharedPreferences.Editor.class.getClassLoader(),
                        new Class<?>[]{SharedPreferences.Editor.class}, (editor, operation, input) -> {
                    if (operation.getName().startsWith("put")) { pending.put((String) input[0], input[1]); return editor; }
                    if (operation.getName().equals("remove")) { pending.put((String) input[0], null); return editor; }
                    if (operation.getName().equals("apply") || operation.getName().equals("commit")) {
                        pending.forEach((key, value) -> { if (value == null) values.remove(key); else values.put(key, value); });
                        return operation.getName().equals("commit") ? true : null;
                    }
                    throw new UnsupportedOperationException(operation.getName());
                });
            }
            throw new UnsupportedOperationException(method.getName());
        });
    }

    @Test public void twoHubsCanDeliverTheSameEventWhilePushAndPollDeduplicate() {
        SharedPreferences prefs = preferences();
        SettingsStore push = new SettingsStore(prefs), poll = new SettingsStore(prefs);
        assertTrue(push.markEventNotified("https://one.example", 42));
        assertTrue(poll.markEventNotified("https://two.example", 42));
        assertFalse(poll.markEventNotified("https://one.example/", 42));
        assertFalse(push.markEventNotified("https://two.example", 42));
    }

    @Test public void pushOriginSurvivesSelectionChangesAndCannotBeGuessed() {
        SharedPreferences prefs = preferences();
        SettingsStore store = new SettingsStore(prefs);
        String first = store.pushToken("https://one.example", "one");
        String second = store.pushToken("https://two.example", "two");
        prefs.edit().putString("profile_id", "two").putString("push_token", "legacy-token").apply();
        assertNotEquals(first, second);
        assertEquals(first, store.pushToken("https://one.example", "one"));
        assertEquals("one", store.pushProfile(first));
        assertEquals("https://one.example", store.pushHub(first));
        assertEquals("", store.pushHub("legacy-token"));
        assertEquals("", store.pushHub("unregistered-token"));
        assertEquals("", store.pushHub(null));
    }
}
