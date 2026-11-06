package cloud.stormlabs.hermescompanion;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class HistoryTest {
    private static final double[][] P = {{1, 10}, {2, 20}, {3, 60}};

    @Test public void stats() {
        double[] s = History.stats(P);
        assertEquals(10, s[0], 0); assertEquals(30, s[1], 0); assertEquals(60, s[2], 0);
        assertTrue(Double.isNaN(History.stats(new double[0][])[1]));
    }

    @Test public void summaryAndEmpty() {
        assertEquals("min 10% · avg 30% · max 60%", History.summary(P, "%"));
        assertEquals("no data yet", History.summary(new double[0][], "%"));
    }

    @Test public void uptime() {
        assertEquals(50, History.uptimePct(new double[][]{{1, 1}, {2, 0}}), 0);
    }
}
