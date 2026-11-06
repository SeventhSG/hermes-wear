package cloud.stormlabs.hermescompanion;

final class HostThresholds {
    private HostThresholds() {}
    static int percent(double value) { return value >= 90 ? 2 : value >= 80 ? 1 : 0; }
    static double ratio(double used, double total) { return total > 0 ? used * 100 / total : 0; }
    static int load(double value, int cores) { return cores > 0 && value > cores ? 1 : 0; }
}
