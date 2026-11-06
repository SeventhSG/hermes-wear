package cloud.stormlabs.hermescompanion;

import com.google.android.gms.wearable.MessageEvent;
import com.google.android.gms.wearable.Wearable;
import com.google.android.gms.wearable.WearableListenerService;

import org.json.JSONObject;

import java.nio.charset.StandardCharsets;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Relay for the Wear OS app. The watch never holds the server URL or key: it sends a prompt here,
 * this service runs the turn against the phone's current connection and streams the reply back.
 *
 * Paths: /hermes/ask (watch to phone, prompt), /hermes/approve (JSON runId, requestId, choice),
 * /hermes/stop. Replies: /hermes/delta, /hermes/done, /hermes/error, /hermes/approval (JSON).
 */
public class WearRelayService extends WearableListenerService {
    static final String ASK = "/hermes/ask", APPROVE = "/hermes/approve", STOP = "/hermes/stop";
    static final String DELTA = "/hermes/delta", DONE = "/hermes/done", ERROR = "/hermes/error", APPROVAL = "/hermes/approval";
    private static final String WATCH_SESSION_TITLE = "Watch";
    /** One in-flight turn per watch node. */
    private static final ConcurrentHashMap<String, HermesApi.StreamHandle> RUNNING = new ConcurrentHashMap<>();

    @Override public void onMessageReceived(MessageEvent event) {
        final String node = event.getSourceNodeId();
        final String path = event.getPath();
        final String body = new String(event.getData(), StandardCharsets.UTF_8);
        if (ASK.equals(path)) {
            if (body.trim().isEmpty()) return;
            new Thread(() -> runTurn(node, body.trim()), "wear-relay").start();
        } else if (APPROVE.equals(path)) {
            new Thread(() -> approve(node, body), "wear-approve").start();
        } else if (STOP.equals(path)) {
            HermesApi.StreamHandle h = RUNNING.get(node);
            if (h != null) h.cancel();
        }
    }

    private void runTurn(String node, String prompt) {
        if (RUNNING.containsKey(node)) { send(node, ERROR, "Hermes is still answering."); return; }
        final HermesApi.StreamHandle handle = new HermesApi.StreamHandle();
        RUNNING.put(node, handle);
        try {
            SettingsStore store = new SettingsStore(getApplicationContext());
            AgentBackend api = Backends.create(store);
            HermesApi.SessionInfo session = api.ensureSession(WATCH_SESSION_TITLE);
            final StringBuilder text = new StringBuilder();
            api.streamChat(session.id, prompt, null, handle, new HermesApi.StreamListener() {
                @Override public void onRunStarted(String runId) {}
                @Override public void onDelta(String delta) { text.append(delta); send(node, DELTA, delta); }
                @Override public void onTool(String state, String name, String preview) {}
                @Override public void onComplete(String finalText) {
                    send(node, DONE, finalText == null || finalText.isEmpty() ? text.toString() : finalText);
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

    private void approve(String node, String json) {
        try {
            JSONObject o = new JSONObject(json);
            String choice = o.optString("choice", "deny");
            if (!choice.equals("once") && !choice.equals("session") && !choice.equals("deny")) choice = "deny";
            Backends.create(new SettingsStore(getApplicationContext()))
                    .approveRun(o.getString("runId"), choice, o.optString("requestId", null), o.optInt("generation", -1));
        } catch (Exception e) {
            send(node, ERROR, HermesApi.friendly(e));
        }
    }

    private void send(String node, String path, String text) {
        Wearable.getMessageClient(this).sendMessage(node, path, text.getBytes(StandardCharsets.UTF_8));
    }
}
