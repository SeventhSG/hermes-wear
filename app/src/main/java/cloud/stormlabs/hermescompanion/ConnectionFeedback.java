package cloud.stormlabs.hermescompanion;

import java.net.URL;
import java.util.Locale;

/** Plain connection advice, shared by the form's inline result and field error. */
final class ConnectionFeedback {
    private ConnectionFeedback() { }

    static boolean keyRejected(String error) {
        return error != null && error.matches("(?is).*\\bHTTP(?:/\\S+)?\\s+(?:401|403)\\b.*");
    }

    static String advice(String error, String url, String harness) {
        if (keyRejected(error)) return "The server rejected this key. Copy "
                + ("Hermes".equals(harness) ? "API_SERVER_KEY" : "the " + harness + " bridge token") + " again.";
        String lower = error == null ? "" : error.toLowerCase(Locale.ROOT);
        if (lower.contains("connection refused") || lower.contains("can't connect") || lower.contains("failed to connect")
                || lower.contains("connectexception") || lower.contains("timed out") || lower.contains("too long")
                || lower.contains("timeout") || lower.contains("resolve") || lower.contains("no route")) {
            String address="the server address";
            try {
                URL endpoint=new URL(url);
                if(!endpoint.getHost().isEmpty())address=endpoint.getHost()+":"+(endpoint.getPort()<0?endpoint.getDefaultPort():endpoint.getPort());
            } catch(java.net.MalformedURLException ignored) { }
            return "Nothing answered at " + address + ". Is " + ("Hermes".equals(harness) ? "Hermes" : "the " + harness + " bridge")
                    + " running, and is the phone on the same network or tailnet?";
        }
        return "Connection failed. " + (error == null || error.isEmpty() ? "Check the server URL and try again." : error);
    }
}
