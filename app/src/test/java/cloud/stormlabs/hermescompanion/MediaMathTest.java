package cloud.stormlabs.hermescompanion;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class MediaMathTest {
    @Test public void indexAndCounter() {
        assertEquals(0, MediaMath.clampIndex(-3, 5));
        assertEquals(4, MediaMath.clampIndex(9, 5));
        assertEquals(0, MediaMath.clampIndex(2, 0));
        assertEquals("3 of 8", MediaMath.counter(2, 8));
        assertEquals("8 of 8", MediaMath.counter(40, 8));
    }

    @Test public void snapFollowsDistanceOrFlick() {
        assertEquals(2, MediaMath.snapTarget(2, -100, 0, 1000, 5));      // short drag stays
        assertEquals(3, MediaMath.snapTarget(2, -400, 0, 1000, 5));      // past 35% goes next
        assertEquals(1, MediaMath.snapTarget(2, 400, 0, 1000, 5));       // and back
        assertEquals(3, MediaMath.snapTarget(2, -50, -1500, 1000, 5));   // fast flick goes next
        assertEquals(2, MediaMath.snapTarget(2, -50, 1500, 1000, 5));    // flick against the drag stays
        assertEquals(4, MediaMath.snapTarget(4, -600, -2000, 1000, 5));  // never past the last
        assertEquals(0, MediaMath.snapTarget(0, 600, 2000, 1000, 5));
    }

    @Test public void edgesResist() {
        assertEquals(35f, MediaMath.edgeResistance(100, false, true), .001f);
        assertEquals(-35f, MediaMath.edgeResistance(-100, true, false), .001f);
        assertEquals(100f, MediaMath.edgeResistance(100, true, true), .001f);
    }

    @Test public void dismissThresholds() {
        assertEquals(0f, MediaMath.dismissProgress(0, 1000), 0);
        assertEquals(1f, MediaMath.dismissProgress(900, 1000), 0);
        assertFalse(MediaMath.shouldDismiss(100, 0, 1000));
        assertTrue(MediaMath.shouldDismiss(250, 0, 1000));
        assertTrue(MediaMath.shouldDismiss(50, 2000, 1000));
        assertFalse(MediaMath.shouldDismiss(50, -2000, 1000));
    }

    @Test public void offsetsNeverLeaveGaps() {
        assertEquals(50f, MediaMath.clampOffset(7, 100, 200), 0);       // smaller than view: centred
        assertEquals(0f, MediaMath.clampOffset(30, 400, 200), 0);       // left edge
        assertEquals(-200f, MediaMath.clampOffset(-999, 400, 200), 0);  // right edge
        assertEquals(-80f, MediaMath.clampOffset(-80, 400, 200), 0);
    }

    @Test public void zoomClampsToFiveAndKeepsFocus() {
        MediaMath.Zoom z = new MediaMath.Zoom();
        z.setSizes(1000, 2000, 1000, 500);
        assertEquals(750f, z.ty, 0);
        z.zoomAt(99, 500, 1000);
        assertEquals(5f, z.zoom, 0);
        z.zoomAt(0.1f, 500, 1000);
        assertEquals(1f, z.zoom, 0);
        assertFalse(z.zoomed());
        z.zoomAt(2.5f, 0, 0);
        assertTrue(z.zoomed());
        assertTrue(z.tx <= 0 && z.tx >= z.minTx());
        assertEquals(1f, z.toggleTarget(2.5f), 0);
    }

    @Test public void panReportsOverflow() {
        MediaMath.Zoom z = new MediaMath.Zoom();
        z.setSizes(1000, 1000, 1000, 1000);
        z.zoomAt(2, 500, 500);                 // content 2000 wide, offset -500
        assertEquals(0f, z.pan(100, 0), 0);
        assertEquals(-400f, z.tx, 0);
        assertEquals(300f, z.pan(700, 0), 0);  // hits the left edge, 300 left over for the pager
        assertEquals(0f, z.tx, 0);
        assertEquals(-1250f, z.pan(-2250, 0), 0);
        assertEquals(-1000f, z.tx, 0);
    }

    @Test public void fillZoomCoversView() {
        MediaMath.Zoom z = new MediaMath.Zoom();
        z.setSizes(2000, 1000, 1000, 1000);   // square video in a wide view: fit leaves bars, fill is 2x
        assertEquals(2f, z.fillZoom(), 0);
        z.setSizes(1000, 1000, 1000, 1000);   // already fills: fall back to the double-tap zoom
        assertEquals(MediaMath.DOUBLE_TAP_ZOOM, z.fillZoom(), 0);
    }

    @Test public void timeAndPercent() {
        assertEquals("0:00", MediaMath.time(-5));
        assertEquals("1:05", MediaMath.time(65_400));
        assertEquals("12:09", MediaMath.time(729_000));
        assertEquals("1:02:03", MediaMath.time(3_723_000));
        assertEquals(0, MediaMath.percent(3, 0));
        assertEquals(50, MediaMath.percent(7, 14));
        assertEquals(100, MediaMath.percent(99, 15));
    }

    @Test public void fitKeepsAspect() {
        float[] f = MediaMath.fit(4000, 2000, 1000, 1000);
        assertEquals(1000f, f[0], .01f);
        assertEquals(500f, f[1], .01f);
    }
}
