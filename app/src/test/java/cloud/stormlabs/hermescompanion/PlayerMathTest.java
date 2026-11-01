package cloud.stormlabs.hermescompanion;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

public class PlayerMathTest {
    @Test public void seekClampsToClip() {
        assertEquals(0, PlayerMath.seekTarget(4000, -10000, 60000));
        assertEquals(60000, PlayerMath.seekTarget(55000, 10000, 60000));
        assertEquals(25000, PlayerMath.seekTarget(15000, 10000, 60000));
        assertEquals(0, PlayerMath.seekTarget(3000, -10000, 0));
        assertEquals(13000, PlayerMath.seekTarget(3000, 10000, 0));   // unknown duration: only floors at 0
    }

    @Test public void thirdsOfTheScreen() {
        assertEquals(PlayerMath.LEFT, PlayerMath.zone(50, 900));
        assertEquals(PlayerMath.CENTER, PlayerMath.zone(450, 900));
        assertEquals(PlayerMath.RIGHT, PlayerMath.zone(850, 900));
        assertEquals(PlayerMath.CENTER, PlayerMath.zone(10, 0));
    }

    @Test public void scrubberTimeMath() {
        assertEquals(30000, PlayerMath.fractionToMs(.5f, 60000));
        assertEquals(60000, PlayerMath.fractionToMs(3f, 60000));
        assertEquals(0, PlayerMath.fractionToMs(.5f, 0));
        assertEquals(.25f, PlayerMath.msToFraction(15000, 60000), 1e-6);
        assertEquals(1f, PlayerMath.msToFraction(99000, 60000), 1e-6);
        assertEquals(0f, PlayerMath.msToFraction(5, 0), 1e-6);
        assertEquals(.5f, PlayerMath.trackFraction(150, 50, 200), 1e-6);
        assertEquals(0f, PlayerMath.trackFraction(10, 50, 200), 1e-6);
        assertEquals(1f, PlayerMath.trackFraction(900, 50, 200), 1e-6);
        assertEquals("0:02 / 1:05", PlayerMath.timeLabel(2000, 65000));
    }

    @Test public void bubbleStaysOnScreen() {
        assertEquals(0f, PlayerMath.bubbleLeft(5, 60, 400), 1e-6);
        assertEquals(170f, PlayerMath.bubbleLeft(200, 60, 400), 1e-6);
        assertEquals(340f, PlayerMath.bubbleLeft(399, 60, 400), 1e-6);
        assertEquals(0f, PlayerMath.bubbleLeft(10, 60, 40), 1e-6);
    }

    @Test public void speedCyclesAndWraps() {
        assertEquals(1.25f, PlayerMath.nextSpeed(1f), 1e-6);
        assertEquals(.75f, PlayerMath.nextSpeed(.5f), 1e-6);
        assertEquals(.5f, PlayerMath.nextSpeed(2f), 1e-6);
        assertEquals(1.5f, PlayerMath.nextSpeed(1.3f), 1e-6);
        assertEquals("1×", PlayerMath.speedLabel(1f));
        assertEquals("0.5×", PlayerMath.speedLabel(.5f));
        assertEquals("1.25×", PlayerMath.speedLabel(1.25f));
        assertEquals("0.75×", PlayerMath.speedLabel(.75f));
    }

    @Test public void dragMapsToLevel() {
        assertEquals(.5f + .1f, PlayerMath.levelAfterDrag(.5f, 80, 1000), 1e-6);   // 80px of 800px span
        assertEquals(1f, PlayerMath.levelAfterDrag(.95f, 400, 1000), 1e-6);
        assertEquals(0f, PlayerMath.levelAfterDrag(.05f, -400, 1000), 1e-6);
        assertEquals(.3f, PlayerMath.levelAfterDrag(.3f, 50, 0), 1e-6);
        assertEquals(8, PlayerMath.levelToStep(.5f, 15));
        assertEquals(15, PlayerMath.levelToStep(2f, 15));
        assertEquals(0, PlayerMath.levelToStep(.5f, 0));
        assertEquals(.5f, PlayerMath.stepToLevel(5, 10), 1e-6);
        assertEquals(0f, PlayerMath.stepToLevel(5, 0), 1e-6);
    }

    @Test public void skipBubbleAndParallax() {
        assertEquals("−10s", PlayerMath.skipLabel(-10000));
        assertEquals("+20s", PlayerMath.skipLabel(20000));
        assertEquals(1f, PlayerMath.pageScale(0, 1000), 1e-6);
        assertEquals(.9f, PlayerMath.pageScale(-1000, 1000), 1e-6);
        assertEquals(.95f, PlayerMath.pageScale(500, 1000), 1e-6);
    }
}
