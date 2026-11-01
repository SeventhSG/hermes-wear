package cloud.stormlabs.hermescompanion;

import java.util.Locale;

/** Pure helpers for hub history series ([[epochSeconds, value], ...]). */
final class History {
    private History() {}

    /** {min, avg, max}; NaNs when empty. */
    static double[] stats(double[][] pts) {
        if (pts == null || pts.length == 0) return new double[]{Double.NaN, Double.NaN, Double.NaN};
        double min = Double.MAX_VALUE, max = -Double.MAX_VALUE, sum = 0;
        for (double[] p : pts) { min = Math.min(min, p[1]); max = Math.max(max, p[1]); sum += p[1]; }
        return new double[]{min, sum / pts.length, max};
    }

    /** "min 3 · avg 12 · max 40" plus unit, or "no data yet". */
    static String summary(double[][] pts, String unit) {
        if (pts == null || pts.length == 0) return "no data yet";
        double[] s = stats(pts);
        return String.format(Locale.US, "min %.0f%s · avg %.0f%s · max %.0f%s", s[0], unit, s[1], unit, s[2], unit);
    }

    /** Share of points that are up (value > 0.5), as a percentage; NaN when empty. */
    static double uptimePct(double[][] pts) {
        if (pts == null || pts.length == 0) return Double.NaN;
        int up = 0;
        for (double[] p : pts) if (p[1] > 0.5) up++;
        return 100.0 * up / pts.length;
    }
}
