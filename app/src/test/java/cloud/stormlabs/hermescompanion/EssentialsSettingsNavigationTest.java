package cloud.stormlabs.hermescompanion;

import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import static org.junit.Assert.assertTrue;

/** Keeps Essentials configuration reachable after first-run setup. */
public class EssentialsSettingsNavigationTest {
    private static String source(String name) throws Exception {
        for (String root : new String[]{"src/main/java/cloud/stormlabs/hermescompanion/", "app/src/main/java/cloud/stormlabs/hermescompanion/"}) {
            Path path = Paths.get(root + name);
            if (Files.isRegularFile(path)) return new String(Files.readAllBytes(path), StandardCharsets.UTF_8);
        }
        throw new IllegalStateException("source not found: " + name);
    }

    @Test public void settingsListsEssentialsConnectionSeparately() throws Exception {
        String settings = source("SettingsActivity.java");
        assertTrue("Settings must expose Essentials after onboarding", settings.contains("\"Essentials capture\""));
    }

    @Test public void disconnectedEssentialsScreenOpensConnectionSettings() throws Exception {
        String essentials = source("EssentialsActivity.java");
        assertTrue("Not-connected action must open ConnectionActivity instead of retrying unchanged settings",
                essentials.contains("this::openConnectionSettings"));
    }
}
