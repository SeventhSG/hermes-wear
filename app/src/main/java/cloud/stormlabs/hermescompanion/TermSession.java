package cloud.stormlabs.hermescompanion;

import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.LinkedBlockingQueue;

/**
 * One terminal tab: a shell on the hub (or an ssh from the hub) read by long-polling and written in order. The shell
 * lives on the hub, so a tab survives the app being closed or the phone changing network (Wi-Fi, mobile data, Tailscale): the
 * open requests are aborted when the network changes and retried, resuming from the last byte read; reopening reads it back
 * from the start of the hub's buffer. Sessions are held here so rotating or leaving the screen keeps them.
 */
final class TermSession {
    interface Listener { void output(TermSession s); void status(TermSession s); }

    static final List<TermSession> OPEN = new ArrayList<>();

    final String target;
    String name;
    final TermScreen screen = new TermScreen(30, 80);
    volatile String id = "";
    volatile String state = "Connecting…";
    volatile boolean ended;
    volatile Listener listener;
    private final HubApi hub;
    private final LinkedBlockingQueue<String> outbox = new LinkedBlockingQueue<>();
    private long next;
    private final boolean keep;
    /** The id to ask the hub for again after it restarted (the tmux shell keeps running under it). */
    private String resume = "";
    private volatile boolean closed;

    TermSession(HubApi hub, String target, String name, String existingId, boolean keep) {
        this.hub = hub; this.keep = keep; this.target = target; this.name = name == null || name.isEmpty() ? target : name;
        if (existingId != null) id = existingId;
        screen.onReply(this::send);
        Thread t = new Thread(() -> { try { run(); } catch (Throwable e) { ended = true; state = "Error"; show("[terminal error: " + e + "]"); } }, "term-read"); t.setDaemon(true); t.start();
        Thread w = new Thread(() -> { try { write(); } catch (Throwable ignored) { } }, "term-write"); w.setDaemon(true); w.start();
    }

    void send(String s) { if (!s.isEmpty() && !ended) outbox.offer(s); }

    void resize(int rows, int cols) {
        if (id.isEmpty()) return;
        new Thread(() -> { try { hub.term("POST", "resize", new JSONObject().put("id", id).put("rows", rows).put("cols", cols)); } catch (Exception ignored) { } }, "term-size").start();
    }

    void close() {
        closed = true; outbox.offer("");
        synchronized (OPEN) { OPEN.remove(this); }
        String sid = id;
        if (!sid.isEmpty()) new Thread(() -> { try { hub.term("POST", "close", new JSONObject().put("id", sid)); } catch (Exception ignored) { } }, "term-close").start();
    }

    /** Leave the tab without ending the shell on the hub. */
    void detach() { closed = true; outbox.offer(""); }

    private void status(String s) { state = s; Listener l = listener; if (l != null) l.status(this); }

    private void show(String red) { synchronized (screen) { screen.feed("\u001b[31m" + red + "\u001b[0m\r\n"); } Listener l = listener; if (l != null) l.output(this); }

    private void run() {
        int failures = 0;
        while (!closed) {
            try {
                if (id.isEmpty()) {
                    JSONObject o = hub.term("POST", "open", new JSONObject().put("target", target).put("name", name).put("rows", screen.rows).put("cols", screen.cols)
                            .put("persist", keep).put("resume", resume));
                    String got = o.getString("id");
                    if (!resume.isEmpty() && !resume.equals(got)) show("[the shell could not be kept through the hub restart: new session]");
                    id = got; resume = ""; next = 0;
                    status("Connected");
                }
                JSONObject o = hub.term("GET", "read?id=" + id + "&since=" + next + "&wait=15", null);
                failures = 0;
                if (!"Connected".equals(state)) status("Connected");
                String data = o.optString("data");
                next = o.optLong("next", next);
                if (!data.isEmpty()) { synchronized (screen) { screen.feed(data); } Listener l = listener; if (l != null) l.output(this); }
                if (!o.optBoolean("alive", true)) { ended = true; show("[session ended]"); status("Ended"); return; }
            } catch (Exception e) {
                String m = String.valueOf(e.getMessage());
                if (m.contains("no such terminal")) { // the hub restarted (an update): ask for the same shell again (kept alive by tmux) or start a fresh one in this tab
                    resume = keep ? id : ""; id = ""; next = 0;
                    if (!keep) show("[the hub was restarted, so this shell ended: new session]");
                    status("Reconnecting…"); continue;
                }
                if (m.contains("Tailscale only") || m.contains("unauthorized") || m.contains("too many")) { ended = true; show(m); status("Blocked"); return; }
                failures++;
                status("Reconnecting…");
                try { Thread.sleep(Math.min(8000, 500L * failures)); } catch (InterruptedException ignored) { return; }
            }
        }
    }

    private void write() {
        while (!closed) {
            try {
                String first = outbox.take();
                if (first.isEmpty() || closed) continue;
                StringBuilder b = new StringBuilder(first);
                String more; while ((more = outbox.poll()) != null) b.append(more); // keystrokes typed while the last one was in flight go together
                while (id.isEmpty() && !closed) Thread.sleep(100);
                // a network change can take a few seconds to settle: keep the typed text (in order) for about a minute
                for (int attempt = 0; attempt < 30 && !closed; attempt++) {
                    try { hub.term("POST", "send", new JSONObject().put("id", id).put("data", b.toString())); break; }
                    catch (Exception e) {
                        String m = String.valueOf(e.getMessage());
                        if (m.contains("no such terminal") || ended) break;
                        Thread.sleep(Math.min(3000L, 400L * (attempt + 1)));
                    }
                }
            } catch (InterruptedException e) { return; }
        }
    }
}
