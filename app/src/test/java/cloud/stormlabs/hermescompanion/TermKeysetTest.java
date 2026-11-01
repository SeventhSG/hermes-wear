package cloud.stormlabs.hermescompanion;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.util.Collections;

import org.junit.Test;

public class TermKeysetTest {
    @Test public void everyPresetKeyExists() {
        for (String name : TermKeyset.PRESET_NAMES) {
            assertTrue(name, !TermKeyset.preset(name).isEmpty());
            for (String id : TermKeyset.preset(name)) assertNotNull(name + ": " + id, TermKeyset.find(id));
        }
    }

    @Test public void catalogHasTheKeysAPhoneKeyboardLacks() {
        for (String id : new String[]{"esc", "tab", "ctrl", "alt", "up", "home", "end", "pgup", "pgdn", "ins", "del", "f1", "f12", "^C", "^Z", "t:|", "t:~", "p:{}"})
            assertNotNull(id, TermKeyset.find(id));
        assertEquals("\u0003", TermKeyset.find("^C").data);
    }

    @Test public void customKeysResolveAndSequencesParse() {
        TermKeyset.Key k = TermKeyset.resolve("c:tmux new", Collections.singletonList(new TermStore.Entry("tmux new", "^b c")));
        assertEquals(TermKeyset.CUSTOM, k.type);
        assertEquals("\u0002 c", TermKeyset.parseSequence(k.data));
        assertNull(TermKeyset.resolve("c:gone", Collections.emptyList()));
        assertEquals("\u001b[A\r\t\\", TermKeyset.parseSequence("\\e[A\\r\\t\\\\"));
        assertEquals("\u001bx", TermKeyset.parseSequence("^[x"));
        assertEquals("a^1", TermKeyset.parseSequence("a^1"));
    }
}
