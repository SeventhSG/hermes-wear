package cloud.stormlabs.hermescompanion;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Serial task work whose delayed submissions become harmless after cancellation. */
final class TaskWorker implements AutoCloseable {
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private volatile boolean closed;

    synchronized boolean execute(Runnable task) {
        if (closed) return false;
        executor.execute(() -> { if (!closed) task.run(); });
        return true;
    }

    @Override public synchronized void close() {
        closed = true;
        executor.shutdownNow();
    }
}
