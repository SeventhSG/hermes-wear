package cloud.stormlabs.hermescompanion;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/** A take whose voice port is out of reach streams through the hub over https: audio up, partials back, final on stop. */
public class LiveWhisperTest {
    private StubServer hub;

    @Before public void start() { Reach.configure("", 8889); }
    @After public void stop() throws Exception { if (hub != null) hub.close(); Reach.configure("", 8889); }

    @Test public void unreachableVoicePortStreamsThroughTheHub() throws Exception {
        hub = new StubServer()
                .json("POST /voice/live", 200, "{\"ok\":true,\"id\":\"t1\"}")
                .json("POST /voice/live/t1/audio", 200, "{\"ok\":true,\"partial\":\"hel\",\"endpoint\":false}")
                .json("POST /voice/live/t1/stop", 200, "{\"ok\":true,\"text\":\"hello there\"}");
        List<String> partials = new CopyOnWriteArrayList<>();
        // Port 1 refuses at once, like the voice port seen from mobile data.
        LiveWhisper live = new LiveWhisper(WhisperClient.parse("127.0.0.1:1"), "en", partials::add, null, new HubApi(hub.base(), "k"), false);
        byte[] chunk = new byte[3200];  // 100 ms, what the recorder hands over
        for (int i = 0; i < 10; i++) { live.feed(chunk, chunk.length); Thread.sleep(20); }
        assertEquals("hello there", live.finish());
        assertEquals("Homelab server over https · live", live.route);
        assertTrue(partials.contains("hel"));
        int sent = 0;
        for (StubServer.Request r : hub.seen) if (r.path.startsWith("/voice/live/t1/")) sent += r.body.length();
        assertEquals("every recorded byte reaches the hub", 32000, sent);
        assertEquals("language=en", hub.seen.get(0).query);
    }

    @Test public void aUrlAsSpeechServerGoesThroughThatHubOverHttps() throws Exception {
        hub = new StubServer().json("POST /hub/voice/stt", 200, "{\"ok\":true,\"text\":\"\"}");
        SettingsStore store = new SettingsStore(NotificationOriginTest.preferences());
        store.whisperAddress(hub.base() + "/");
        assertTrue(SpeechRecognition.httpsOnly(store));
        assertEquals("Speech server answers over https (live words)", SpeechRecognition.test(store));
        assertEquals("/hub/voice/stt", hub.seen.get(0).path);
        store.whisperAddress("myserver:10300");
        assertTrue(!SpeechRecognition.httpsOnly(store));
    }
}
