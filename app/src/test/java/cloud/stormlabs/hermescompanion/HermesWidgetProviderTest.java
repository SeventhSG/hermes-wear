package cloud.stormlabs.hermescompanion;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

public class HermesWidgetProviderTest {
    @Test public void stripsMarkdownAndWhitespace() {
        assertEquals("Hello world code", HermesWidgetProvider.preview("## Hello\n\n**world**  `code`", 50));
    }
    @Test public void truncates() {
        assertEquals("abcd…", HermesWidgetProvider.preview("abcdefghij", 5));
    }
    @Test public void nullIsEmpty() { assertEquals("", HermesWidgetProvider.preview(null, 5)); }
}
