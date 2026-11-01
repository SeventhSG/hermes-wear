package cloud.stormlabs.hermescompanion;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

public class ScheduledPreviewTest {
    @Test public void stripsOnlyLeadingImportantBlock() {
        assertEquals("Run complete", ChatActivity.scheduledPreview("  [IMPORTANT: You are running as a scheduled job\nFollow the instructions] \n Run complete "));
        assertEquals("[IMPORTANT: second] Result", ChatActivity.scheduledPreview("[IMPORTANT: first] [IMPORTANT: second] Result"));
        assertEquals("Before [IMPORTANT: keep]", ChatActivity.scheduledPreview("Before [IMPORTANT: keep]"));
    }

    @Test public void usesFallbackWhenPreambleIsAllThereIs() {
        assertEquals("Scheduled run", ChatActivity.scheduledPreview("[IMPORTANT: scheduled job]   \n"));
        assertEquals("Scheduled run", ChatActivity.scheduledPreview("[IMPORTANT: You are running as a scheduled cron job. Deliver"));
    }
}
