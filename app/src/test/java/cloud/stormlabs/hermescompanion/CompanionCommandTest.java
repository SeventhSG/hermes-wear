package cloud.stormlabs.hermescompanion;

import org.junit.Test;
import static org.junit.Assert.*;

public class CompanionCommandTest {
    @Test public void ignoresPlainMessages() { assertNull(CompanionCommand.parse("hello")); }
    @Test public void parsesCommandAndArguments() { CompanionCommand c=CompanionCommand.parse(" /rename  Mobile ops "); assertEquals("rename", c.name); assertEquals("Mobile ops", c.args); }
    @Test public void normalizesCase() { assertEquals("stop", CompanionCommand.parse("/STOP").name); }
}
