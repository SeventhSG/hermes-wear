package cloud.stormlabs.hermescompanion;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.List;

public class MediaModulesTest {
    @Test public void onlyConfiguredModulesAndTheirFeatures() {
        List<MediaModules.Module> m = MediaModules.parse("{\"modules\":[{\"id\":\"sonarr\",\"name\":\"Sonarr\",\"kind\":\"tv\"},"
                + "{\"id\":\"plex\",\"name\":\"Plex\",\"kind\":\"server\",\"features\":[\"sessions\",\"control\"]}]}");
        assertTrue(MediaModules.has(m, "sonarr"));
        assertFalse(MediaModules.has(m, "radarr"));
        assertEquals(1, MediaModules.servers(m).size());
        MediaModules.Module plex = MediaModules.find(m, "plex");
        assertTrue(plex.can("control"));
        assertFalse(plex.can("resume"));
    }

    @Test public void anOlderHubOrBrokenAnswerMeansNothingSetUp() {
        assertTrue(MediaModules.parse("{}").isEmpty());
        assertTrue(MediaModules.parse("not json").isEmpty());
    }
}
