package cloud.stormlabs.hermescompanion;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import org.json.JSONObject;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Session titles are UNIQUE on the Hermes api_server ("Title already in use by
 * session X", code=invalid_title). createSession() must therefore walk forward
 * to a free suffix instead of failing the second "New conversation".
 *
 * The stub server is a raw ServerSocket (JDK-only, no com.sun.* modules so the
 * Android unit-test classpath stays happy).
 */
public class CreateSessionTitleTest {

    private ServerSocket socket;
    private Thread server;
    private volatile boolean running = true;
    private final Set<String> taken = new HashSet<>(Arrays.asList("Home"));
    private final List<String> seenTitles = new ArrayList<>();
    private volatile int unexpectedStatus = 0;

    @Before
    public void start() throws Exception {
        socket = new ServerSocket(0);
        server = new Thread(this::serve, "stub-api");
        server.setDaemon(true);
        server.start();
    }

    private void serve() {
        while (running) {
            try (Socket c = socket.accept()) {
                String request = readRequest(c.getInputStream());
                String title = "";
                int idx = request.indexOf('{');
                if (idx >= 0) {
                    try {
                        title = new JSONObject(request.substring(idx)).optString("title", "");
                    } catch (Exception ignored) {
                    }
                }
                seenTitles.add(title);
                if (unexpectedStatus != 0) {
                    respond(c, unexpectedStatus, "{\"error\":{\"message\":\"boom\",\"code\":\"internal\"}}");
                } else if (taken.contains(title)) {
                    respond(c, 400, "{\"error\":{\"message\":\"Title already in use by session api_1\",\"code\":\"invalid_title\"}}");
                } else {
                    taken.add(title);
                    respond(c, 201, "{\"object\":\"hermes.session\",\"session\":{\"id\":\"api_" + title.hashCode()
                            + "\",\"title\":\"" + title + "\",\"model\":\"\",\"preview\":\"\"}}");
                }
            } catch (Exception e) {
                if (running) {
                    // keep serving; the test asserts on responses, not on socket noise
                }
            }
        }
    }

    private static String readRequest(InputStream in) throws Exception {
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        int contentLength = 0;
        StringBuilder head = new StringBuilder();
        int prev = -1;
        while (true) {
            int b = in.read();
            if (b < 0) break;
            head.append((char) b);
            if (head.length() >= 4 && head.substring(head.length() - 4).equals("\r\n\r\n")) break;
            if (prev == -1 && b == '\n') prev = 0;
        }
        for (String line : head.toString().split("\r\n")) {
            if (line.toLowerCase().startsWith("content-length:")) {
                contentLength = Integer.parseInt(line.split(":")[1].trim());
            }
        }
        for (int i = 0; i < contentLength; i++) {
            int b = in.read();
            if (b < 0) break;
            buf.write(b);
        }
        return head + buf.toString(StandardCharsets.UTF_8);
    }

    private static void respond(Socket c, int code, String body) throws Exception {
        byte[] out = body.getBytes(StandardCharsets.UTF_8);
        String head = "HTTP/1.1 " + code + " X\r\nContent-Type: application/json\r\nContent-Length: " + out.length
                + "\r\nConnection: close\r\n\r\n";
        OutputStream os = c.getOutputStream();
        os.write(head.getBytes(StandardCharsets.UTF_8));
        os.write(out);
        os.flush();
    }

    @After
    public void stop() throws Exception {
        running = false;
        socket.close();
    }

    private HermesApi api() {
        return new HermesApi("http://127.0.0.1:" + socket.getLocalPort(), "test-key", "scope");
    }

    @Test
    public void placeholderTitleCreatesAnUntitledSessionSoHermesNamesIt() throws Exception {
        HermesApi.SessionInfo s = api().createSession("New conversation");
        assertEquals("", s.title);
        assertEquals("New conversation", s.displayTitle());
        assertTrue(seenTitles.toString(), seenTitles.contains(""));
    }

    @Test
    public void createSucceedsEvenWhenThePlainTitleIsTaken() throws Exception {
        HermesApi.SessionInfo s = api().createSession("Home");
        assertTrue(s.title, s.title.startsWith("Home"));
        assertTrue("must not reuse the taken title", !"Home".equals(s.title));
    }

    @Test
    public void consecutiveCreatesAlwaysGetAFreeTitle() throws Exception {
        HermesApi client = api();
        Set<String> titles = new HashSet<>();
        for (int i = 0; i < 3; i++) {
            String title = client.createSession("Home").title;
            assertTrue("duplicate title handed out: " + title, titles.add(title));
            assertTrue(title, title.startsWith("Home"));
        }
        assertEquals(3, titles.size());
    }

    @Test
    public void nonTitleErrorsStillSurface() {
        unexpectedStatus = 500;
        try {
            api().createSession("Home");
            fail("expected the server error to propagate");
        } catch (Exception e) {
            String msg = String.valueOf(e.getMessage());
            assertTrue(msg, msg.contains("boom") || msg.contains("500"));
        }
    }
}
