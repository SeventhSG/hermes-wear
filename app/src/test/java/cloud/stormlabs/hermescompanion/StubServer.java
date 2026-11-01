package cloud.stormlabs.hermescompanion;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/** Minimal JDK-only HTTP stub: route "METHOD /path" (query stripped) to a canned or computed reply. */
final class StubServer implements AutoCloseable {
    interface Handler { void handle(Request request, Socket socket) throws Exception; }

    static final class Request {
        final String method, path, query, body;
        final Map<String, String> headers;
        Request(String method, String path, String query, String body, Map<String, String> headers) {
            this.method = method; this.path = path; this.query = query; this.body = body; this.headers = headers;
        }
    }

    private final ServerSocket socket;
    private final Map<String, Handler> routes = new ConcurrentHashMap<>();
    final List<Request> seen = new CopyOnWriteArrayList<>();
    private volatile boolean running = true;

    StubServer() throws Exception {
        socket = new ServerSocket(0);
        Thread t = new Thread(this::serve, "stub-http");
        t.setDaemon(true);
        t.start();
    }

    String base() { return "http://127.0.0.1:" + socket.getLocalPort(); }

    StubServer json(String route, int code, String body) {
        routes.put(route, (r, s) -> respond(s, code, "application/json", body));
        return this;
    }

    StubServer sse(String route, String events) {
        routes.put(route, (r, s) -> respond(s, 200, "text/event-stream", events));
        return this;
    }

    StubServer on(String route, Handler h) { routes.put(route, h); return this; }

    private void serve() {
        while (running) {
            try {
                Socket c = socket.accept();
                new Thread(() -> {
                    try (Socket sock = c) {
                        Request r = read(sock.getInputStream());
                        seen.add(r);
                        Handler h = routes.get(r.method + " " + r.path);
                        if (h == null) respond(sock, 404, "application/json", "{\"error\":{\"message\":\"no route " + r.path + "\"}}");
                        else h.handle(r, sock);
                    } catch (Exception ignored) {}
                }).start();
            } catch (Exception e) {
                if (!running) return;
            }
        }
    }

    private static Request read(InputStream in) throws Exception {
        StringBuilder head = new StringBuilder();
        while (true) {
            int b = in.read();
            if (b < 0) break;
            head.append((char) b);
            if (head.length() >= 4 && head.substring(head.length() - 4).equals("\r\n\r\n")) break;
        }
        String[] lines = head.toString().split("\r\n");
        String[] first = lines[0].split(" ");
        Map<String, String> headers = new ConcurrentHashMap<>();
        int length = 0;
        for (int i = 1; i < lines.length; i++) {
            int colon = lines[i].indexOf(':');
            if (colon <= 0) continue;
            String k = lines[i].substring(0, colon).trim().toLowerCase();
            String v = lines[i].substring(colon + 1).trim();
            headers.put(k, v);
            if (k.equals("content-length")) length = Integer.parseInt(v);
        }
        ByteArrayOutputStream body = new ByteArrayOutputStream();
        for (int i = 0; i < length; i++) { int b = in.read(); if (b < 0) break; body.write(b); }
        String target = first.length > 1 ? first[1] : "/";
        int q = target.indexOf('?');
        return new Request(first[0], q < 0 ? target : target.substring(0, q), q < 0 ? "" : target.substring(q + 1),
                body.toString(StandardCharsets.UTF_8.name()), headers);
    }

    static void respond(Socket c, int code, String type, String body) throws Exception {
        byte[] out = body.getBytes(StandardCharsets.UTF_8);
        String head = "HTTP/1.1 " + code + " X\r\nContent-Type: " + type + "\r\nContent-Length: " + out.length
                + "\r\nConnection: close\r\n\r\n";
        OutputStream os = c.getOutputStream();
        os.write(head.getBytes(StandardCharsets.UTF_8));
        os.write(out);
        os.flush();
    }

    static String frame(String event, String json) { return "event: " + event + "\ndata: " + json + "\n\n"; }

    @Override public void close() throws Exception { running = false; socket.close(); }
}
