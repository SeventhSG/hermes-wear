package cloud.stormlabs.hermescompanion;

import android.content.SharedPreferences;
import org.junit.Test;
import static org.junit.Assert.*;

public class ProfileConnectionTest {
    @Test public void inactiveProfileKeepsItsOwnConnectionAndHub() {
        SharedPreferences prefs = NotificationOriginTest.preferences();
        prefs.edit().putString("profile_id", "two").putString("server_type", "Hermes")
                .putString("endpoint", "https://two.example").putString("session", "two-session")
                .putString("hub_url", "https://two-hub.example")
                .putString("profiles", "[{\"id\":\"one\",\"type\":\"Hermes\",\"endpoint\":\"https://one.example\",\"session\":\"one-session\",\"hub_url\":\"https://one-hub.example\"}]").apply();
        SettingsStore store = new SettingsStore(prefs);
        SettingsStore.Connection one = store.connection("one"), two = store.connection("two");
        assertEquals("https://one.example", one.endpoint);
        assertEquals("one-session", one.session);
        assertEquals("https://two.example", two.endpoint);
        assertEquals("two-session", two.session);
        assertEquals("https://one-hub.example", store.hubConnection("one").endpoint);
        assertEquals("one", store.mainProfileId("one"));
        assertNull(store.connection("removed"));
        assertNull(store.hubConnection("removed"));
    }

    @Test public void workerProfileFindsItsOriginalRootAfterSwitch() {
        SharedPreferences prefs = NotificationOriginTest.preferences();
        prefs.edit().putString("profile_id", "two").putString("endpoint", "https://two.example")
                .putString("profiles", "[{\"id\":\"one\",\"endpoint\":\"https://one.example\"},{\"id\":\"worker\",\"endpoint\":\"https://one.example/p/worker\"}]").apply();
        SettingsStore store = new SettingsStore(prefs);
        assertEquals("one", store.mainProfileId("worker"));
        assertEquals("http://one.example:8791", store.hubConnection("worker").endpoint);
    }
}
