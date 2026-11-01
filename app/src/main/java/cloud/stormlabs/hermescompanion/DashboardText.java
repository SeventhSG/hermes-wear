package cloud.stormlabs.hermescompanion;

/** Pure text for the Dashboard hero. */
final class DashboardText {
    private DashboardText() {}

    static String greeting(int hour) {
        return hour < 5 ? "Good night" : hour < 12 ? "Good morning" : hour < 18 ? "Good afternoon" : "Good evening";
    }

    /** One live status line. attention = open problems; guestsUp < 0 or NaN cpu mean unknown; known=false before any data. */
    static String status(boolean known, int attention, int guestsUp, String node, double cpu) {
        if (!known) return "Checking systems…";
        if (attention > 0) return attention == 1 ? "1 thing needs attention" : attention + " things need attention";
        StringBuilder s = new StringBuilder("All systems normal");
        if (guestsUp >= 0) s.append(" · ").append(guestsUp).append(guestsUp == 1 ? " guest running" : " guests running");
        if (node != null && !node.isEmpty() && !Double.isNaN(cpu)) s.append(" · ").append(node).append(' ').append(Math.round(cpu)).append("% CPU");
        return s.toString();
    }
}
