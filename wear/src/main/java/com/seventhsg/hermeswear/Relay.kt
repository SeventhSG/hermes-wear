package com.seventhsg.hermeswear

import android.content.Context
import com.google.android.gms.wearable.MessageClient
import com.google.android.gms.wearable.MessageEvent
import com.google.android.gms.wearable.Wearable
import kotlinx.coroutines.tasks.await
import org.json.JSONObject

data class Chat(val id: String, val title: String, val preview: String, val pinned: Boolean)
data class Turn(val role: String, val text: String)

/**
 * Talks to the Hermes Bridge phone app (WearRelayService) over the Wear Data Layer. The phone holds the
 * server, profile and chats, and can use a VPN the watch cannot join, so the watch simply mirrors it.
 */
class Relay(context: Context, private val onEvent: (Event) -> Unit) : MessageClient.OnMessageReceivedListener {
    sealed interface Event {
        data class Delta(val text: String) : Event
        data class Done(val text: String) : Event
        data class Failed(val message: String) : Event
        data class Approval(val runId: String, val requestId: String, val generation: Int, val command: String) : Event
        data class Session(val id: String) : Event
        data class Chats(val current: String, val chats: List<Chat>) : Event
        data class History(val id: String, val turns: List<Turn>) : Event
    }

    private val app = context.applicationContext
    private val messages = Wearable.getMessageClient(app)

    fun start() { messages.addListener(this) }
    fun stop() { messages.removeListener(this) }

    suspend fun phoneReachable(): Boolean =
        runCatching { Wearable.getNodeClient(app).connectedNodes.await().isNotEmpty() }.getOrDefault(false)

    /** [sessionId]: "" continues the phone's current chat, "new" starts one, otherwise that chat. */
    suspend fun ask(prompt: String, sessionId: String) = send(
        "/hermes/ask",
        JSONObject().put("text", prompt).put("sessionId", sessionId).put("watch", WatchInfo.describe(app)).toString(),
    )
    suspend fun stopRun() = send("/hermes/stop", "")
    suspend fun listChats() = send("/hermes/sessions", "")
    suspend fun history(id: String) = send("/hermes/history", id)
    suspend fun approve(a: Event.Approval, choice: String) = send(
        "/hermes/approve",
        JSONObject().put("runId", a.runId).put("requestId", a.requestId)
            .put("generation", a.generation).put("choice", choice).toString(),
    )

    private suspend fun send(path: String, body: String) {
        val nodes = runCatching { Wearable.getNodeClient(app).connectedNodes.await() }.getOrDefault(emptyList())
        if (nodes.isEmpty()) { onEvent(Event.Failed("No phone connected. Open the Hermes Bridge app on your phone.")); return }
        for (n in nodes) runCatching { messages.sendMessage(n.id, path, body.toByteArray()).await() }
            .onFailure { onEvent(Event.Failed("Could not reach the phone app. Is Hermes Bridge installed?")) }
    }

    override fun onMessageReceived(e: MessageEvent) {
        val body = String(e.data, Charsets.UTF_8)
        runCatching {
            when (e.path) {
                "/hermes/delta" -> onEvent(Event.Delta(body))
                "/hermes/done" -> onEvent(Event.Done(body))
                "/hermes/error" -> onEvent(Event.Failed(body))
                "/hermes/session" -> onEvent(Event.Session(JSONObject(body).optString("id")))
                "/hermes/approval" -> JSONObject(body).let {
                    onEvent(Event.Approval(it.optString("runId"), it.optString("requestId"), it.optInt("generation", -1), it.optString("command")))
                }
                "/hermes/sessions.result" -> JSONObject(body).let { o ->
                    val arr = o.optJSONArray("sessions")
                    val list = (0 until (arr?.length() ?: 0)).map { arr!!.getJSONObject(it) }.map {
                        Chat(it.optString("id"), it.optString("title").ifBlank { "Untitled" }, it.optString("preview"), it.optBoolean("pinned"))
                    }
                    onEvent(Event.Chats(o.optString("current"), list))
                }
                "/hermes/history.result" -> JSONObject(body).let { o ->
                    val arr = o.optJSONArray("messages")
                    val turns = (0 until (arr?.length() ?: 0)).map { arr!!.getJSONObject(it) }.map { Turn(it.optString("role"), it.optString("text")) }
                    onEvent(Event.History(o.optString("id"), turns))
                }
            }
        }
    }
}
