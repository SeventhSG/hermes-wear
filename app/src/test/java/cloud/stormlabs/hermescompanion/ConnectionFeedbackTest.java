package cloud.stormlabs.hermescompanion;

import org.junit.Test;
import static org.junit.Assert.*;

public class ConnectionFeedbackTest {
    @Test public void rejectedKeysPointToTheHarnessCredential() {
        for (String status : new String[]{"HTTP 401: Invalid gateway API key", "HTTP 403: Forbidden", "http 401: denied"}) {
            assertTrue(ConnectionFeedback.keyRejected(status));
            assertEquals("The server rejected this key. Copy API_SERVER_KEY again.", ConnectionFeedback.advice(status,"https://example.com","Hermes"));
            assertTrue(ConnectionFeedback.advice(status,"https://example.com","Claude Code").contains("Claude Code bridge token"));
            assertTrue(ConnectionFeedback.advice(status,"https://example.com","Codex").contains("Codex bridge token"));
        }
        assertFalse(ConnectionFeedback.keyRejected("HTTP 500: job 401 failed"));
        assertFalse(ConnectionFeedback.keyRejected("Connection refused at port 403"));
        assertFalse(ConnectionFeedback.keyRejected(null));
    }

    @Test public void unreachableServersPointToTheAddressAndNetwork() {
        for (String error : new String[]{"Connection refused", "Can't connect to the server — is Hermes running and reachable?", "The server took too long to respond", "SocketTimeoutException: timed out", "Can't resolve the server host", "No route to the server"}) {
            String advice = ConnectionFeedback.advice(error,"http://server:8795","Codex");
            assertTrue(advice, advice.startsWith("Nothing answered at server:8795."));
            assertTrue(advice, advice.contains("Codex bridge running"));
            assertTrue(advice, advice.contains("same network or tailnet"));
        }
        assertTrue(ConnectionFeedback.advice("timeout","https://server","Hermes").contains("server:443"));
        assertTrue(ConnectionFeedback.advice("timeout","http://server","Hermes").contains("server:80"));
        assertTrue(ConnectionFeedback.advice("timeout","http://[fd00::1]:8795","Hermes").contains("[fd00::1]:8795"));
        assertTrue(ConnectionFeedback.advice("timeout","http://[invalid","Hermes").contains("the server address"));
    }

    @Test public void otherErrorsKeepTheSpecificCause() {
        assertEquals("Connection failed. Secure connection failed (TLS error)", ConnectionFeedback.advice("Secure connection failed (TLS error)","https://server","Hermes"));
        assertEquals("Connection failed. HTTP 404: Not found", ConnectionFeedback.advice("HTTP 404: Not found","https://server","Hermes"));
    }
}
