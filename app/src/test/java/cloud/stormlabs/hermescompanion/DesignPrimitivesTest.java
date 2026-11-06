package cloud.stormlabs.hermescompanion;

import static org.junit.Assert.*;
import org.junit.Test;

public class DesignPrimitivesTest {
    @Test public void gaugeSweepIsClampedTo240() {
        assertEquals(0f, Gauge.sweep(0f), 0);
        assertEquals(120f, Gauge.sweep(.5f), 1e-4);
        assertEquals(240f, Gauge.sweep(1f), 0);
        assertEquals(240f, Gauge.sweep(3f), 0);
        assertEquals(0f, Gauge.sweep(-1f), 0);
        assertEquals(0f, Gauge.sweep(Float.NaN), 0);
    }

    @Test public void gaugeColourRunsLowMidHigh() {
        int low = 0xff0000ff, mid = 0xff00ff00, high = 0xffff0000;
        assertEquals(low, Gauge.colorAt(0f, low, mid, high));
        assertEquals(mid, Gauge.colorAt(.5f, low, mid, high));
        assertEquals(high, Gauge.colorAt(1f, low, mid, high));
        assertEquals(high, Gauge.colorAt(9f, low, mid, high));
        assertEquals(0xff008080, Gauge.colorAt(.25f, low, mid, high));
    }

    @Test public void digitRollOnlyMarksChangedColumns() {
        assertArrayEquals(new int[]{0, 1}, DigitRoll.columnDiff("41", "42"));
        assertArrayEquals(new int[]{0, -1}, DigitRoll.columnDiff("42", "41"));
        assertArrayEquals(new int[]{0, 0}, DigitRoll.columnDiff("42", "42"));
        assertArrayEquals(new int[]{1, -1, -1}, DigitRoll.columnDiff("99", "100"));
        assertArrayEquals(new int[]{0, 0, 0}, DigitRoll.columnDiff("1.5", "1.5"));
        assertArrayEquals(new int[]{1}, DigitRoll.columnDiff("", "7"));
        assertArrayEquals(new int[]{0, 1}, DigitRoll.columnDiff("100", "09"));
    }
}
