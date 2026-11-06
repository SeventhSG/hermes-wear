package cloud.stormlabs.hermescompanion;

public final class UrlUtils {
    private UrlUtils() {}
    public static String normalize(String raw) {
        String value = raw == null ? "" : raw.trim();
        if (value.isEmpty()) return "";
        if (!value.contains("://")) value = "http://" + value;
        int min = value.indexOf("//") + 3;
        while (value.length() > min && value.endsWith("/")) value = value.substring(0, value.length() - 1);
        return value;
    }
    public static String endpoint(String base, String path) {
        String normalized = normalize(base);
        if (normalized.isEmpty()) return "";
        String p = path == null ? "" : path;
        return normalized + (p.startsWith("/") ? p : "/" + p);
    }
    public static boolean isUsableEndpoint(String raw) {
        String normalized = normalize(raw);
        return !normalized.isEmpty() && normalized.matches("(?i)^https?://\\S+$");
    }
}
