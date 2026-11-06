package cloud.stormlabs.hermescompanion;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;

import java.util.Arrays;
import org.junit.Test;

public class MediaCacheTest {
    @Test public void keyIsSha1Hex() {
        assertEquals("a9993e364706816aba3e25717850c26c9cd0d89d", MediaCache.key("abc"));
        assertNotEquals(MediaCache.key("/a.png"), MediaCache.key("/b.png"));
    }

    @Test public void sampleForWidthKeepsAtLeastTarget() {
        assertEquals(1, MediaCache.sampleForWidth(1000, 1080));
        assertEquals(1, MediaCache.sampleForWidth(1500, 1080));
        assertEquals(2, MediaCache.sampleForWidth(2160, 1080));
        assertEquals(4, MediaCache.sampleForWidth(4000, 1000));
        assertEquals(1, MediaCache.sampleForWidth(0, 1080));
    }

    @Test public void sampleForMaxFitsBothSides() {
        assertEquals(1, MediaCache.sampleForMax(1600, 900, 1600));
        assertEquals(4, MediaCache.sampleForMax(4000, 3000, 1600));
        assertEquals(2, MediaCache.sampleForMax(1000, 3000, 1600));
    }

    @Test public void victimsDropOldestUntilUnderLimit() {
        long[] sizes = {100, 100, 100, 100};
        long[] times = {40, 10, 30, 20};
        assertEquals(Arrays.asList(1, 3), MediaCache.victims(sizes, times, 200));
        assertEquals(Arrays.asList(), MediaCache.victims(sizes, times, 400));
    }

    @Test public void extFromDataUriAndPath() {
        assertEquals("jpg", MediaCache.extFor("data:image/jpeg;base64,AAAA"));
        assertEquals("png", MediaCache.extFor("data:image/png;base64,AAAA"));
        assertEquals("pdf", MediaCache.extFor("/files/Report.PDF?x=1"));
        assertEquals("", MediaCache.extFor("/media/noext"));
    }
}
