package cloud.stormlabs.hermescompanion;

import com.google.android.gms.wearable.MessageEvent;
import com.google.android.gms.wearable.Wearable;
import com.google.android.gms.wearable.WearableListenerService;

import org.json.JSONArray;
import org.json.JSONObject;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Relay for the Wear OS app. The watch never holds the server URL or key: everything runs on this
 * phone's own connection and profile, so the watch sees the same chats and server as the phone app.
 *
 * Watch to phone:
 *   /hermes/ask       JSON {text, sessionId, watch}. sessionId "" means the phone's current chat,
 *                     "new" starts a fresh chat, anything else continues that chat.
 *   /hermes/approve   JSON {runId, requestId, generation, choice}
 *   /hermes/stop
 *   /hermes/sessions  list the chats
 *   /hermes/history   body is a session id, returns its latest messages
 * Phone to watch: /hermes/delta, /hermes/done, /hermes/error, /hermes/approval (JSON),
 *   /hermes/session (JSON {id}), /hermes/sessions.result (JSON), /hermes/history.result (JSON).
 */
public class WearRelayService extends WearableListenerService {
    static final String ASK = "/hermes/ask", APPROVE = "/hermes/approve", STOP = "/hermes/stop";
    static final String SESSIONS = "/hermes/sessions", HISTORY = "/hermes/history";
    static final String DELTA = "/hermes/delta", DONE = "/hermes/done", ERROR = "/hermes/error", APPROVAL = "/hermes/approval";
    static final String SESSION = "/hermes/session", SESSIONS_RESULT = "/hermes/sessions.result", HISTORY_RESULT = "/hermes/history.result";
    private static final String WATCH_SESSION_TITLE = "Watch";
    private static final int MAX_WATCH_INFO = 600;
    /** One in-flight turn per watch node. */
    private static final ConcurrentHashMap<String, HermesApi.StreamHandle> RUNNING = new ConcurrentHashMap<>();

    @Override public void onMessageReceived(MessageEvent event) {
        final String node = event.getSourceNodeId();
        final String path = event.getPath();
        final String body = new String(event.getData(), StandardCharsets.UTF_8);
        switch (path) {
            case ASK:
                if (body.trim().isEmpty()) return;
                new Thread(() -> runTurn(node, body.trim()), "wear-relay").start();
                break;
            case APPROVE:
                new Thread(() -> approve(node, body), "wear-approve").start();
                break;
            case STOP: {
                HermesApi.StreamHandle h = RUNNING.get(node);
                if (h != null) h.cancel();
                break;
            }
            case SESSIONS:
                new Thread(() -> sendSessions(node), "wear-sessions").start();
                break;
            case HISTORY:
                new Thread(() -> sendHistory(node, body.trim()), "wear-history").start();
                break;
            default:
                break;
        }
    }

    private AgentBackend backend() { return Backends.create(new SettingsStore(getApplicationContext())); }

    private void runTurn(String node, String raw) {
        if (RUNNING.containsKey(node)) { send(node, ERROR, "Hermes is still answering."); return; }
        final HermesApi.StreamHandle handle = new HermesApi.StreamHandle();
        RUNNING.put(node, handle);
        try {
            String text = raw, wanted = "", watch = "";
            if (raw.startsWith("{")) {
                JSONObject o = new JSONObject(raw);
                text = o.optString("text", "").trim();
                wanted = o.optString("sessionId", "");
                watch = o.optString("watch", "");
            }
            if (text.isEmpty()) return;
            SettingsStore store = new SettingsStore(getApplicationContext());
            AgentBackend api = Backends.create(store);

            String sid;
            if ("new".equals(wanted)) sid = api.createSession(null).id;
            else if (!wanted.isEmpty()) sid = wanted;
            else {
                // Full sync: continue the chat the phone app has open, else a dedicated "Watch" chat.
                String current = store.sessionId();
                sid = current != null && !current.isEmpty() ? current : api.ensureSession(WATCH_SESSION_TITLE).id;
            }
            send(node, SESSION, new JSONObject().put("id", sid).toString());

            String system = null;
            if (!watch.trim().isEmpty()) {
                if (watch.length() > MAX_WATCH_INFO) watch = watch.substring(0, MAX_WATCH_INFO);
                system = "The user is talking to you from their smartwatch, so keep replies short and plain text. "
                        + "Watch status: " + watch.trim();
            }
            final StringBuilder out = new StringBuilder();
            api.streamChat(sid, text, system, handle, new HermesApi.StreamListener() {
                @Override public void onRunStarted(String runId) {}
                @Override public void onDelta(String delta) { out.append(delta); send(node, DELTA, delta); }
                @Override public void onTool(String state, String name, String preview) {}
                @Override public void onComplete(String finalText) {
                    send(node, DONE, finalText == null || finalText.isEmpty() ? out.toString() : finalText);
                }
                @Override public void onError(String error) { send(node, ERROR, error == null ? "Request failed" : error); }
                @Override public void onApproval(HermesApi.Approval a) {
                    try {
                        send(node, APPROVAL, new JSONObject().put("runId", a.runId).put("requestId", a.requestId).put("generation", a.generation)
                                .put("command", a.command == null || a.command.isEmpty() ? String.valueOf(a.description) : a.command).toString());
                    } catch (Exception ignored) { /* nothing to send */ }
                }
            });
        } catch (Exception e) {
            send(node, ERROR, HermesApi.friendly(e));
        } finally {
            RUNNING.remove(node);
        }
    }

    private void sendSessions(String node) {
        try {
            SettingsStore store = new SettingsStore(getApplicationContext());
            List<HermesApi.SessionInfo> list = Backends.create(store).listSessions(25, 0);
            JSONArray arr = new JSONArray();
            for (HermesApi.SessionInfo s : list) {
                if (s.archived) continue;
                arr.put(new JSONObject().put("id", s.id).put("title", s.displayTitle()).put("preview", s.preview == null ? "" : clip(s.preview, 120))
                        .put("pinned", s.pinned).put("lastActive", s.lastActive));
            }
            String current = store.sessionId();
            send(node, SESSIONS_RESULT, new JSONObject().put("current", current == null ? "" : current).put("sessions", arr).toString());
        } catch (Exception e) {
            send(node, ERROR, HermesApi.friendly(e));
        }
    }

    private void sendHistory(String node, String sessionId) {
        if (sessionId.isEmpty()) return;
        try {
            JSONArray arr = new JSONArray();
            for (HermesApi.Message m : backend().messages(sessionId, 30, 0)) {
                if (!"user".equals(m.role) && !"assistant".equals(m.role)) continue;
                if (m.content == null || m.content.trim().isEmpty()) continue;
                arr.put(new JSONObject().put("role", m.role).put("text", clip(m.content.trim(), 1200)));
            }
            send(node, HISTORY_RESULT, new JSONObject().put("id", sessionId).put("messages", arr).toString());
        } catch (Exception e) {
            send(node, ERROR, HermesApi.friendly(e));
        }
    }

    /** Wear messages are limited to about 100 KB, so long chats are trimmed per entry. */
    private static String clip(String s, int max) { return s.length() <= max ? s : s.substring(0, max) + "..."; }

    private void approve(String node, String json) {
        try {
            JSONObject o = new JSONObject(json);
            String choice = o.optString("choice", "deny");
            if (!choice.equals("once") && !choice.equals("session") && !choice.equals("deny")) choice = "deny";
            backend().approveRun(o.getString("runId"), choice, o.optString("requestId", null), o.optInt("generation", -1));
        } catch (Exception e) {
            send(node, ERROR, HermesApi.friendly(e));
        }
    }

    private void send(String node, String path, String text) {
        Wearable.getMessageClient(this).sendMessage(node, path, text.getBytes(StandardCharsets.UTF_8));
    }
}
