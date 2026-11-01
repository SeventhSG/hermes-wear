package cloud.stormlabs.hermescompanion;

import java.util.UUID;

public final class SessionIds {
    private SessionIds() {}
    public static String normalize(String raw) {
        String value = raw == null ? "" : raw.trim();
        if (value.isEmpty()) return "mobile-" + UUID.randomUUID();
        return value.replaceAll("[\r\n\u0000]", "").substring(0, Math.min(value.replaceAll("[\r\n\u0000]", "").length(), 256));
    }
}
