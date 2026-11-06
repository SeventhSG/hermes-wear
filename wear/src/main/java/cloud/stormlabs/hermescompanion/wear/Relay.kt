package cloud.stormlabs.hermescompanion.wear

import android.content.Context
import com.google.android.gms.wearable.MessageClient
import com.google.android.gms.wearable.MessageEvent
import com.google.android.gms.wearable.Wearable
import kotlinx.coroutines.tasks.await
import org.json.JSONObject

/** Talks to the phone app's WearRelayService. The watch holds no server URL and no key. */
class Relay(context: Context, private val onEvent: (Event) -> Unit) : MessageClient.OnMessageReceivedListener {
    sealed interface Event {
        data class Delta(val text: String) : Event
        data class Done(val text: String) : Event
        data class Failed(val message: String) : Event
        data class Approval(val runId: String, val requestId: String, val generation: Int, val command: String) : Event
    }

    private val app = context.applicationContext
    private val messages = Wearable.getMessageClient(app)

    fun start() = messages.addListener(this)
    fun stop() = messages.removeListener(this)

    /** False when no phone is connected, so the UI can say so instead of hanging. */
    suspend fun phoneReachable(): Boolean =
        runCatching { Wearable.getNodeClient(app).connectedNodes.await().isNotEmpty() }.getOrDefault(false)

    suspend fun ask(prompt: String) = send("/hermes/ask", prompt)
    suspend fun stopRun() = send("/hermes/stop", "")
    suspend fun approve(a: Event.Approval, choice: String) = send(
        "/hermes/approve",
        JSONObject().put("runId", a.runId).put("requestId", a.requestId)
            .put("generation", a.generation).put("choice", choice).toString(),
    )

    private suspend fun send(path: String, body: String) {
        val nodes = Wearable.getNodeClient(app).connectedNodes.await()
        if (nodes.isEmpty()) { onEvent(Event.Failed("Phone not connected")); return }
        for (n in nodes) messages.sendMessage(n.id, path, body.toByteArray()).await()
    }

    override fun onMessageReceived(e: MessageEvent) {
        val body = String(e.data, Charsets.UTF_8)
        when (e.path) {
            "/hermes/delta" -> onEvent(Event.Delta(body))
            "/hermes/done" -> onEvent(Event.Done(body))
            "/hermes/error" -> onEvent(Event.Failed(body))
            "/hermes/approval" -> runCatching { JSONObject(body) }.getOrNull()?.let {
                onEvent(Event.Approval(it.optString("runId"), it.optString("requestId"), it.optInt("generation", -1), it.optString("command")))
            }
        }
    }
}
