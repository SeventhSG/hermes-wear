package cloud.stormlabs.hermescompanion;

import org.junit.Test;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import static org.junit.Assert.*;

public class TaskWorkerTest {
    @Test public void delayedSubmissionAfterStopIsIgnored() {
        TaskWorker worker = new TaskWorker();
        worker.close();
        assertFalse(worker.execute(() -> fail("A stopped task must not act")));
        worker.close();
    }

    @Test public void stopDiscardsQueuedActionsAndInterruptsCurrentWork() throws Exception {
        TaskWorker worker = new TaskWorker();
        CountDownLatch started = new CountDownLatch(1), interrupted = new CountDownLatch(1);
        AtomicBoolean acted = new AtomicBoolean();
        try {
            worker.execute(() -> {
                started.countDown();
                try { new CountDownLatch(1).await(); }
                catch (InterruptedException expected) { interrupted.countDown(); }
            });
            assertTrue(started.await(2, TimeUnit.SECONDS));
            worker.execute(() -> acted.set(true));
            worker.close();
            assertTrue(interrupted.await(2, TimeUnit.SECONDS));
            assertFalse(acted.get());
        } finally { worker.close(); }
    }
}
