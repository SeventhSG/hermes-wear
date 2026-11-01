package cloud.stormlabs.hermescompanion;

import static org.junit.Assert.*;
import org.junit.Test;

public class SettingsStoreTest {
    @Test public void hermesKeepsLegacyKey() {
        assertEquals("endpoint", SettingsStore.keyFor("endpoint", "Hermes"));
        assertEquals("api_key", SettingsStore.keyFor("api_key", "Hermes"));
        assertEquals("session", SettingsStore.keyFor("session", "Hermes"));
    }

    @Test public void claudeCodeGetsSuffixedKey() {
        assertEquals("endpoint.claude_code", SettingsStore.keyFor("endpoint", "Claude Code"));
        assertEquals("api_key.claude_code", SettingsStore.keyFor("api_key", "Claude Code"));
        assertEquals("session.claude_code", SettingsStore.keyFor("session", "Claude Code"));
    }

    @Test public void unknownTypeCannotReadHermesCredentials() {
        assertEquals("endpoint.unsupported", SettingsStore.keyFor("endpoint", ""));
        assertEquals("api_key.unsupported", SettingsStore.keyFor("api_key", null));
    }

    @Test public void aHermesProfileNameDoesNotFollowYouIntoCodex() {
        android.content.SharedPreferences prefs = NotificationOriginTest.preferences();
        prefs.edit().putString("profile_name", "Pentest").putString("endpoint", "http://100.64.0.17:8642/p/pentest")
                .putString("endpoint.codex", "http://100.64.0.17:8795").putString("server_type", "Codex").apply();
        SettingsStore store = new SettingsStore(prefs);
        assertEquals("Codex", store.profileName());
        assertTrue(store.isHermesSubProfile());
        assertTrue(store.profilesForHarness().stream().allMatch(p -> "Codex".equals(p.type)));
        prefs.edit().putString("server_type", "Hermes").apply();
        assertEquals("Pentest", store.profileName());
    }

    @Test public void assistantSessionAndModelAreKeptPerHarness() {
        android.content.SharedPreferences prefs = NotificationOriginTest.preferences();
        prefs.edit().putString("server_type", "Hermes").apply();
        SettingsStore store = new SettingsStore(prefs);
        store.assistantSessionId("hermes-1"); store.agentModel("claude-haiku-5-5");
        store.serverType("Codex");
        assertEquals("", store.assistantSessionId()); assertEquals("", store.agentModel());
        store.assistantSessionId("codex-1");
        store.serverType("Hermes");
        assertEquals("hermes-1", store.assistantSessionId()); assertEquals("claude-haiku-5-5", store.agentModel());
    }

    @Test public void screenControlReminderNeedsPriorConnectionAndCurrentLoss() {
        assertFalse(SettingsStore.showScreenControlReminder(false, false, false));
        assertFalse(SettingsStore.showScreenControlReminder(false, true, false));
        assertFalse(SettingsStore.showScreenControlReminder(true, true, false));
        assertTrue(SettingsStore.showScreenControlReminder(true, false, false));
        assertFalse(SettingsStore.showScreenControlReminder(true, false, true));
    }
}
