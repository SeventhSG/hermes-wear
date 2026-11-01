package cloud.stormlabs.hermescompanion;

import org.junit.Test;
import static org.junit.Assert.*;
import java.util.Arrays;
import java.util.Collections;

public class HubCredentialsTest {
    private HubCredentials.Profile p(String type, String endpoint, String hub, String key) {
        return new HubCredentials.Profile(type, endpoint, hub, key);
    }

    @Test public void codexUsesHermesRootOnTheSameHostInsteadOfBridgeKey() {
        HubCredentials.Profile codex = p("Codex", "http://100.1.2.3:8795", "", "bridge-key");
        HubCredentials.Profile root = p("Hermes", "http://100.1.2.3:8889", "https://hub.example/hub", "hub-key");
        HubCredentials.Profile result = HubCredentials.select(codex, Arrays.asList(codex, root));
        assertEquals("hub-key", result.key);
        assertEquals("https://hub.example/hub", result.hubUrl());
    }

    @Test public void cannotBorrowAnUnrelatedServersKeyOrGatewayProfileKey() {
        HubCredentials.Profile codex = p("Codex", "http://100.1.2.3:8795", "", "bridge-key");
        assertNull(HubCredentials.select(codex, Arrays.asList(
                p("Hermes", "http://100.9.8.7:8889", "", "other-key"),
                p("Hermes", "http://100.1.2.3:8889/p/worker", "", "worker-key"))));
        assertNull(HubCredentials.select(codex, Collections.singletonList(codex)));
    }

    @Test public void explicitSharedHubMatchesAcrossApiAddressAliases() {
        HubCredentials.Profile bridge = p("Claude Code", "http://100.1.2.3:8792", "https://hub.example/hub", "bridge-key");
        HubCredentials.Profile root = p("Hermes", "http://192.168.1.3:8889", "https://hub.example/hub", "hub-key");
        assertEquals("hub-key", HubCredentials.select(bridge, Arrays.asList(bridge, root)).key);
    }

    @Test public void ambiguousRootsFailClosed() {
        assertNull(HubCredentials.select(p("Codex", "http://host:8795", "", "bridge-key"), Arrays.asList(
                p("Hermes", "http://host:8889", "", "first-key"), p("Hermes", "http://host:9000", "", "second-key"))));
    }

    @Test public void hermesWorkerStillResolvesItsOriginalRootAndUnknownBackendsCannotBorrowKeys() {
        HubCredentials.Profile root = p("Hermes", "http://host:8889", "", "root-key");
        assertSame(root, HubCredentials.select(p("Hermes", "http://host:8889/p/worker", "", "worker-key"), Collections.singletonList(root)));
        assertNull(HubCredentials.select(p("unknown", "http://host:9000", "", ""), Collections.singletonList(root)));
    }
}
