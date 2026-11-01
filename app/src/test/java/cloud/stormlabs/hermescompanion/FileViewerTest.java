package cloud.stormlabs.hermescompanion;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class FileViewerTest {
    @Test public void csvBecomesMarkdownTable() {
        String md = FileViewerActivity.csvTable("host,ram\n\"web, 1\",4\nb|c,\"8 \"\"GB\"\"\"\n", ',');
        assertEquals("| host | ram |\n| --- | --- |\n| web, 1 | 4 |\n| b\\|c | 8 \"GB\" |\n", md);
        assertEquals(2, Markdown.tableRows(Markdown.parse(md).get(0).text).size() - 1);
    }

    @Test public void viewerHandlesCommonTypes() {
        for (String e : new String[]{"pdf", "txt", "csv", "md", "png", "mp4", "mp3", "wav"}) assertTrue(e, FileViewerActivity.handles(e));
        assertTrue(!FileViewerActivity.handles("docx"));
    }
}
