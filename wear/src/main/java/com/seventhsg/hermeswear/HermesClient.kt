package com.seventhsg.hermeswear

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.isActive
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import kotlin.coroutines.coroutineContext

/**
 * Minimal client for a Hermes API server's OpenAI-compatible endpoint:
 * POST {url}/v1/chat/completions with stream=true, read as server-sent events.
 */
class HermesClient(private val baseUrl: String, private val key: String) {
    /** Past turns, newest last. Kept short so a watch request stays small. */
    private val history = ArrayList<Pair<String, String>>()

    fun reset() = history.clear()

    /** Streams one reply. [onDelta] runs on the IO thread. Returns the full text, or throws with a readable message. */
    suspend fun ask(prompt: String, onDelta: (String) -> Unit): String = withContext(Dispatchers.IO) {
        history.add("user" to prompt)
        val messages = JSONArray()
        for ((role, text) in history.takeLast(12)) messages.put(JSONObject().put("role", role).put("content", text))
        val body = JSONObject().put("model", "hermes-agent").put("messages", messages).put("stream", true)

        val conn = (URL("$baseUrl/v1/chat/completions").openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 10_000
            readTimeout = 120_000
            doOutput = true
            setRequestProperty("Content-Type", "application/json")
            setRequestProperty("Accept", "text/event-stream")
            setRequestProperty("X-Hermes-Session-Key", "watch")
            if (key.isNotEmpty()) setRequestProperty("Authorization", "Bearer $key")
        }
        try {
            conn.outputStream.use { it.write(body.toString().toByteArray()) }
            val code = conn.responseCode
            if (code !in 200..299) {
                history.removeLast()
                throw IllegalStateException(describe(code))
            }
            val out = StringBuilder()
            conn.inputStream.bufferedReader().use { reader ->
                while (coroutineContext.isActive) {
                    val line = reader.readLine() ?: break
                    if (!line.startsWith("data:")) continue
                    val data = line.substring(5).trim()
                    if (data == "[DONE]") break
                    val delta = runCatching {
                        JSONObject(data).getJSONArray("choices").getJSONObject(0).optJSONObject("delta")?.optString("content", "")
                    }.getOrNull().orEmpty()
                    if (delta.isNotEmpty()) { out.append(delta); onDelta(delta) }
                }
            }
            val text = out.toString().trim()
            if (text.isEmpty()) { history.removeLast(); throw IllegalStateException("Hermes returned an empty reply") }
            history.add("assistant" to text)
            text
        } catch (e: java.io.IOException) {
            history.lastOrNull()?.takeIf { it.first == "user" }?.let { history.removeLast() }
            throw IllegalStateException("Cannot reach the server. Check the address and that the watch has a network.")
        } finally {
            conn.disconnect()
        }
    }

    private fun describe(code: Int) = when (code) {
        401, 403 -> "The server rejected the key (HTTP $code)"
        404 -> "No Hermes API at that address (HTTP 404)"
        else -> "Server error (HTTP $code)"
    }
}
