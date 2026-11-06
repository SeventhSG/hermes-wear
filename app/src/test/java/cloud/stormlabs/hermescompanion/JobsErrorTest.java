package cloud.stormlabs.hermescompanion;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class JobsErrorTest {
    @Test public void extractsQuotedApiMessage() {
        String error = "RuntimeError: Error code: 400 - [{'error': {'code': 400, "
                + "'message': 'API key not valid. Please pass a valid API key.'}}]";
        assertEquals("API key not valid. Please pass a valid API key.", JobsActivity.shortError(error));
        assertEquals("Access denied", JobsActivity.shortError("RuntimeError: Access denied"));
        assertEquals("Wrong key", JobsActivity.shortError("{\"message\": \"Wrong key\"}"));
        assertEquals("Wrong key", JobsActivity.shortError("{'message': \"Wrong key\"}"));
    }

    @Test public void clipsLongMessage() {
        String message = "A very long server error explaining that the API key cannot be used for this account right now";
        String shortMessage = JobsActivity.shortError(message);
        assertTrue(shortMessage.length() <= 80);
        assertTrue(shortMessage.endsWith("…"));
    }
}
