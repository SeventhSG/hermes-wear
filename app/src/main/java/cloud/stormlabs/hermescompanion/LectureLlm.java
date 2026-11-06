package cloud.stormlabs.hermescompanion;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * Model calls for Live notes over the normal Hermes run API, on throw-away sessions of their own so the
 * Assistant conversation is never touched. Fast ticks and the slower summary each get a session (they can
 * overlap), and a session is replaced every few calls so its history never grows into the prompt.
 */
final class LectureLlm implements InsightEngine.Llm {
    private static final int ROTATE_AFTER = 8;
    private static final long TIMEOUT_S = 45;

    private final HermesApi api;
    private final String model;
    private final String[] sid = new String[2];
    private final int[] uses = new int[2];
    private final List<String> made = new ArrayList<>();
    private volatile HermesApi.StreamHandle live;
    private volatile boolean closed;

    LectureLlm(HermesApi api, String configuredModel) { this.api = api; this.model = configuredModel; }

    @Override public String ask(String system, String user, boolean slow) throws Exception {
        if (closed) throw new IllegalStateException("closed");
        int slot = slow ? 1 : 0;
        String id = session(slot);
        final StringBuilder text = new StringBuilder();
        final String[] failure = {null};
        final CountDownLatch done = new CountDownLatch(1);
        HermesApi.StreamHandle handle = new HermesApi.StreamHandle();
        if (!slow) live = handle;
        api.streamChat(id, user, system, handle, new HermesApi.StreamListener() {
            @Override public void onRunStarted(String runId) { }
            @Override public void onDelta(String delta) { text.append(delta); }
            @Override public void onTool(String state, String name, String preview) { }
            @Override public void onComplete(String finalText) { if (finalText != null && !finalText.isEmpty()) { text.setLength(0); text.append(finalText); } done.countDown(); }
            @Override public void onError(String error) { failure[0] = error; done.countDown(); }
        });
        // streamChat blocks until the run ends, so the latch is already open; the wait only guards odd exits.
        if (!done.await(TIMEOUT_S, TimeUnit.SECONDS)) { handle.cancel(); throw new java.io.IOException("Hermes took too long"); }
        if (failure[0] != null && text.length() == 0) throw new java.io.IOException(failure[0]);
        return text.toString().trim();
    }

    private synchronized String session(int slot) throws Exception {
        if (sid[slot] != null && uses[slot] < ROTATE_AFTER) { uses[slot]++; return sid[slot]; }
        String old = sid[slot];
        String id = api.createSession("Live notes " + (slot == 0 ? "cards" : "summary")).id;
        try { PhoneAgent.pinModel(api, id, model); } catch (Exception ignored) { /* the gateway default still works, just slower */ }
        sid[slot] = id; uses[slot] = 1; made.add(id);
        if (old != null) drop(old);
        return id;
    }

    private void drop(String id) {
        Thread t = new Thread(() -> { try { api.deleteSession(id); } catch (Throwable ignored) { } }, "lecture-clean");
        t.setDaemon(true); t.start();
    }

    /** Cancels a call in flight and removes the temporary sessions. */
    void close() {
        closed = true;
        HermesApi.StreamHandle h = live; if (h != null) h.cancel();
        List<String> all;
        synchronized (this) { all = new ArrayList<>(made); made.clear(); sid[0] = sid[1] = null; }
        for (String id : all) drop(id);
    }
}
