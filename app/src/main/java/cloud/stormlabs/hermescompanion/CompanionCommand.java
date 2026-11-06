package cloud.stormlabs.hermescompanion;

public final class CompanionCommand {
    public final String name;
    public final String args;
    private CompanionCommand(String name, String args) { this.name = name; this.args = args; }
    public static CompanionCommand parse(String raw) {
        String s = raw == null ? "" : raw.trim();
        if (!s.startsWith("/")) return null;
        int split = s.indexOf(' ');
        String name = (split < 0 ? s.substring(1) : s.substring(1, split)).trim().toLowerCase(java.util.Locale.ROOT);
        String args = split < 0 ? "" : s.substring(split + 1).trim();
        if (name.isEmpty()) return null;
        return new CompanionCommand(name, args);
    }
}
