package cloud.stormlabs.hermescompanion;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import org.junit.Test;

public class LocalCommandsTest {
    private static String p(String s) {
        DeviceActions.Action a = LocalCommands.parse(s);
        if (a == null) return null;
        java.util.TreeMap<String, Object> sorted = new java.util.TreeMap<>();
        for (java.util.Iterator<String> it = a.args.keys(); it.hasNext(); ) { String k = it.next(); sorted.put(k, a.args.opt(k)); }
        StringBuilder b = new StringBuilder(a.type).append(" {");
        for (java.util.Map.Entry<String, Object> e : sorted.entrySet()) {
            if (b.charAt(b.length() - 1) != '{') b.append(',');
            Object v = e.getValue();
            b.append(org.json.JSONObject.quote(e.getKey())).append(':').append(v instanceof String ? org.json.JSONObject.quote((String) v) : String.valueOf(v));
        }
        return b.append('}').toString();
    }

    @Test public void timers() {
        assertEquals("set_timer {\"seconds\":300}", p("Set a timer for 5 minutes"));
        assertEquals("set_timer {\"seconds\":300}", p("hey hermes, 5 minute timer"));
        assertEquals("set_timer {\"seconds\":1800}", p("timer for half an hour"));
        assertEquals("set_timer {\"seconds\":5400}", p("set timer for 1 hour and 30 minutes"));
        assertEquals("set_timer {\"label\":\"pasta\",\"seconds\":600}", p("Please set a timer for ten minutes for pasta."));
    }

    @Test public void alarms() {
        assertEquals("set_alarm {\"hour\":7,\"minute\":30}", p("Set an alarm for 7:30"));
        assertEquals("set_alarm {\"hour\":18,\"minute\":0}", p("wake me up at 6 pm"));
        assertEquals("set_alarm {\"hour\":0,\"minute\":15}", p("alarm at 12:15 am"));
    }

    @Test public void togglesAndMedia() {
        assertEquals("flashlight {\"on\":true}", p("turn on the flashlight"));
        assertEquals("flashlight {\"on\":false}", p("torch off"));
        assertEquals("wifi {\"on\":false}", p("turn wifi off"));
        assertEquals("dnd {\"mode\":\"on\"}", p("turn on do not disturb"));
        assertEquals("volume {\"direction\":\"up\"}", p("volume up"));
        assertEquals("volume {\"level\":40}", p("set volume to 40%"));
        assertEquals("media {\"command\":\"pause\"}", p("pause the music"));
        assertEquals("media {\"command\":\"next\"}", p("skip song"));
        assertEquals("dismiss {\"what\":\"timer\"}", p("stop the timer"));
        assertEquals("dismiss {\"what\":\"alarm\"}", p("Dismiss alarm"));
    }

    @Test public void communication() {
        assertEquals("call {\"contact\":\"Mom\"}", p("Call Mom"));
        assertEquals("call {\"number\":\"0888 123 456\"}", p("call 0888 123 456"));
        assertEquals("sms {\"body\":\"I'm running late\",\"contact\":\"Ivan\"}", p("Text Ivan saying I'm running late"));
        assertEquals("message {\"app\":\"whatsapp\",\"contact\":\"Maria\",\"text\":\"On my way\"}", p("Message Maria on WhatsApp saying On my way"));
        assertEquals("message {\"app\":\"telegram\",\"contact\":\"Alex\",\"text\":\"see you at 8\"}", p("telegram Alex saying see you at 8"));
        assertNull(p("call it a day"));
    }

    @Test public void navigationAppsMusic() {
        assertEquals("navigate {\"destination\":\"Sofia Airport\",\"mode\":\"driving\"}", p("Navigate to Sofia Airport"));
        assertEquals("navigate {\"destination\":\"the park\",\"mode\":\"walking\"}", p("walk to the park"));
        assertEquals("open_app {\"name\":\"Spotify\"}", p("open Spotify"));
        assertEquals("play_music {\"query\":\"Daft Punk\"}", p("play Daft Punk on spotify"));
    }

    @Test public void leavesEverythingElseToHermes() {
        assertNull(p("What's the weather tomorrow?"));
        assertNull(p("open the pod bay doors and tell me a joke"));
        assertNull(p("Summarise my emails from today and call me back if anything is urgent"));
        assertEquals("media {\"command\":\"play\"}", p("play"));
        assertNull(p(""));
    }

    @Test public void reminders() {
        String r = p("remind me to call the bank in 20 minutes");
        assertEquals("remind", r.split(" ")[0]);
        assertEquals(true, r.contains("\"text\":\"call the bank\""));
        String at = p("remind me at 5 pm to buy milk");
        assertEquals(true, at.contains("T17:00") && at.contains("buy milk"));
    }
}
