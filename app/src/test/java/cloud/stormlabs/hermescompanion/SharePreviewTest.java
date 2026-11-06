package cloud.stormlabs.hermescompanion;

import org.junit.Test;
import java.util.Arrays;
import java.util.Collections;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class SharePreviewTest {
    @Test public void textIsTruncatedAt300Characters() {
        String preview = SharePreview.text(String.join("", Collections.nCopies(301, "x")),
                Collections.emptyList(), 0);
        assertEquals(301, preview.length());
        assertTrue(preview.endsWith("…"));
    }

    @Test public void showsFileNamesAndTotalCount() {
        assertEquals("Note\n\n5 files\nphoto.jpg\nclip.mp4\n…and 3 more",
                SharePreview.text("Note", Arrays.asList("photo.jpg", "clip.mp4"), 5));
        assertEquals("1 file\ndoc.pdf",
                SharePreview.text(null, Collections.singletonList("doc.pdf"), 1));
    }
}
