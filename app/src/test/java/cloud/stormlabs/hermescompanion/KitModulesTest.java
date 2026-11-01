package cloud.stormlabs.hermescompanion;

import static org.junit.Assert.*;
import org.junit.Test;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.Set;

public class KitModulesTest {
    private static KitModules kit() throws Exception {
        File f = new File("../server-kit/modules.json");
        if (!f.exists()) f = new File("server-kit/modules.json");
        return KitModules.parse(new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8));
    }

    private static Set<String> set(String... ids) { return new LinkedHashSet<>(Arrays.asList(ids)); }

    @Test public void theShippedCatalogueParsesAndKeepsTheHubRequired() throws Exception {
        KitModules k = kit();
        assertTrue(k.byId.get("hub").required);
        assertEquals(10, k.skills().size());
        assertEquals(10, k.byId.get("skills").children.size());
        assertTrue(k.presets.containsKey("recommended"));
        for (KitModules.Module m : k.byId.values()) for (String d : m.depends) assertTrue(m.id + " -> " + d, k.byId.containsKey(d));
    }

    @Test public void defaultsAreTheRecommendedSetAndNeverIncludeHeavyOrExperimentalModules() throws Exception {
        KitModules k = kit();
        Set<String> d = k.defaults();
        assertTrue(d.contains("hub")); assertTrue(d.contains("phone-mcp")); assertTrue(d.contains("skill-deploy"));
        assertFalse(d.contains("slash")); assertFalse(d.contains("voice-tts")); assertFalse(d.contains("claude-bridge"));
        assertEquals(d, k.preset("recommended"));
        assertEquals(set("hub"), k.preset("minimal"));
        assertFalse(k.preset("full").contains("claude-bridge"));
        assertTrue(k.preset("full").contains("voice-stt"));
    }

    @Test public void selectionAlwaysGainsTheHubAndTheDependenciesOfWhatIsChosen() throws Exception {
        KitModules k = kit();
        assertEquals(set("hub", "skill-travel"), k.normalize(set("skill-travel")));
        assertEquals(set("hub"), k.normalize(set("bogus")));
        assertEquals(set("hub", "slash"), k.normalize(set("slash")));
    }

    @Test public void argsCollapseAllSkillsAndListPartialOnes() throws Exception {
        KitModules k = kit();
        assertEquals("--modules hub", k.args(set("hub")));
        String all = k.args(k.defaults());
        assertTrue(all, all.startsWith("--modules hub"));
        assertTrue(all, all.contains("phone-mcp") && all.endsWith(",skills") && !all.contains("--skills"));
        assertEquals("--modules hub --skills deploy,travel", k.args(set("skill-deploy", "skill-travel")));
        assertEquals("--modules hub,slash,voice-tts", k.args(set("voice-tts", "slash")));
    }

    @Test public void groupsExpandAndNamesAreReadable() throws Exception {
        KitModules k = kit();
        assertEquals(10, k.expand(Arrays.asList("skills")).size());
        String names = k.names(k.defaults());
        assertTrue(names, names.startsWith("Hub, all 10 skills"));
        assertEquals("Hub, 2 of 10 skills", k.names(set("skill-deploy", "skill-travel")));
    }

    @Test public void csvRoundTripKeepsOrderAndDropsBlanks() {
        assertEquals(set("a", "b"), KitModules.fromCsv(" a, ,b "));
        assertEquals("a,b", KitModules.toCsv(set("a", "b")));
        assertTrue(KitModules.fromCsv(null).isEmpty());
    }

    @Test public void promptCarriesTheArgumentsTheTokenAndOnlyAsksAboutDownloadsForVoice() throws Exception {
        KitModules k = kit();
        String p = ServerSetup.prompt("tok123", 41234, k, k.defaults(), false);
        assertTrue(p.contains("tok123") && p.contains("41234"));
        assertTrue(p.contains("python3 install.py " + k.args(k.defaults()) + " --dry-run --yes"));
        assertTrue(p.contains("install the Hermes Companion server kit") || p.contains("install the"));
        assertFalse(p.contains("--download-models"));
        Set<String> voice = k.normalize(set("voice-stt", "voice-tts", "proxmox"));
        String v = ServerSetup.prompt("t", 40001, k, voice, true);
        assertTrue(v.contains("--download-models") && v.contains("--stt-device gpu"));
        assertTrue(v.contains("--pve-ssh"));
        assertTrue(v.startsWith("Please update"));
    }
}
