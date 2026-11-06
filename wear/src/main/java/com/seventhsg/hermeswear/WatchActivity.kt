package com.seventhsg.hermeswear

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognizerIntent
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.wear.compose.foundation.lazy.ScalingLazyColumn
import androidx.wear.compose.foundation.lazy.items
import androidx.wear.compose.foundation.lazy.rememberScalingLazyListState
import androidx.wear.compose.material.Chip
import androidx.wear.compose.material.ChipDefaults
import androidx.wear.compose.material.Colors
import androidx.wear.compose.material.MaterialTheme
import androidx.wear.compose.material.Text
import androidx.wear.compose.material.TimeText
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

// One accent, locked across the app, on an off-black background. No red: failures are a plain muted line.
private val Accent = Color(0xFF4F8FF7)
private val Ink = Color(0xFF0E1013)
private val Muted = Color(0xFF9AA0A8)
private val Field = Color(0xFF1A1D22)
private val main = Handler(Looper.getMainLooper())

class WatchActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Direct mode can be set from a PC, since typing an address on a watch is miserable:
        // adb shell am start -n com.seventhsg.hermeswear/.WatchActivity --es url http://host:8642 --es key KEY
        val store = Store(this)
        intent.getStringExtra("url")?.let { store.url = it }
        intent.getStringExtra("key")?.let { store.key = it }
        setContent { WatchApp(store) }
    }
}

private enum class Screen { Chat, Chats, Source }

@Composable
private fun WatchApp(store: Store) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var screen by remember { mutableStateOf(Screen.Chat) }
    var client by remember { mutableStateOf(HermesClient(store.url, store.key)) }
    var direct by remember { mutableStateOf(store.configured) }
    val turns = remember { mutableStateListOf<Turn>() }
    var sessionId by remember { mutableStateOf("") }          // "" = the phone's current chat
    var chats by remember { mutableStateOf<List<Chat>?>(null) }
    var phoneCurrent by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    var job by remember { mutableStateOf<Job?>(null) }
    var approval by remember { mutableStateOf<Relay.Event.Approval?>(null) }
    var phoneUp by remember { mutableStateOf<Boolean?>(null) }

    fun appendToReply(text: String) {
        val last = turns.lastOrNull()
        if (last != null && last.role == "assistant") turns[turns.lastIndex] = last.copy(text = last.text + text)
        else turns.add(Turn("assistant", text))
    }
    fun setReply(text: String) {
        val last = turns.lastOrNull()
        if (last != null && last.role == "assistant") turns[turns.lastIndex] = last.copy(text = text) else turns.add(Turn("assistant", text))
    }

    val relay = remember {
        Relay(context) { e ->
            when (e) {
                is Relay.Event.Delta -> appendToReply(e.text)
                is Relay.Event.Done -> { setReply(e.text); busy = false; approval = null }
                is Relay.Event.Failed -> { error = e.message; busy = false; approval = null }
                is Relay.Event.Approval -> approval = e
                is Relay.Event.Session -> sessionId = e.id
                is Relay.Event.Chats -> { phoneCurrent = e.current; chats = e.chats }
                is Relay.Event.History -> {
                    // Show a chat's saved messages only when no turn is in flight, so a late reply never wipes a live one.
                    if (!busy) { turns.clear(); turns.addAll(e.turns.takeLast(10)); sessionId = e.id }
                }
            }
        }
    }
    DisposableEffect(Unit) { relay.start(); onDispose { relay.stop() } }

    // Sync on open: learn the phone's current chat and show it.
    LaunchedEffect(direct) {
        if (direct) { phoneUp = null; return@LaunchedEffect }
        phoneUp = relay.phoneReachable()
        if (phoneUp == true) relay.listChats()
    }
    LaunchedEffect(chats) {
        // Open on the chat the phone app has open (else its newest), then keep talking in that same chat.
        if (!direct && turns.isEmpty() && sessionId.isEmpty()) {
            val id = phoneCurrent.ifEmpty { chats?.firstOrNull()?.id.orEmpty() }
            if (id.isNotEmpty()) relay.history(id)
        }
    }

    fun ask(text: String) {
        error = null; busy = true; approval = null
        turns.add(Turn("user", text))
        if (direct) {
            job = scope.launch {
                try {
                    client.ask(text) { d -> main.post { appendToReply(d) } }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    error = e.message ?: "Request failed"
                } finally {
                    busy = false
                }
            }
        } else {
            scope.launch { relay.ask(text, sessionId) }
        }
    }

    val dictate = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
        val said = r.data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)?.firstOrNull()
        if (r.resultCode == Activity.RESULT_OK && !said.isNullOrBlank()) ask(said)
    }

    val colors = Colors(primary = Accent, background = Ink, onBackground = Color(0xFFE8EAED), onSurface = Color(0xFFE8EAED))
    MaterialTheme(colors = colors) {
        Box(Modifier.fillMaxSize().background(Ink)) {
            when (screen) {
                Screen.Source -> SourceScreen(store) {
                    client = HermesClient(store.url, store.key)
                    direct = store.configured
                    turns.clear(); sessionId = ""; chats = null; error = null
                    screen = Screen.Chat
                }
                Screen.Chats -> ChatsScreen(
                    chats = chats,
                    onPick = { c ->
                        turns.clear(); sessionId = c.id; error = null
                        scope.launch { relay.history(c.id) }
                        screen = Screen.Chat
                    },
                    onBack = { screen = Screen.Chat },
                )
                Screen.Chat -> {
                    ScalingLazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        state = rememberScalingLazyListState(),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        val a = approval
                        if (a != null) {
                            item { Text("Hermes wants to run", color = Muted, textAlign = TextAlign.Center) }
                            item { Text(a.command.take(160), textAlign = TextAlign.Center) }
                            item { ActionChip("Allow once", true) { scope.launch { relay.approve(a, "once") }; approval = null } }
                            item { ActionChip("Deny", false) { scope.launch { relay.approve(a, "deny") }; approval = null } }
                        } else {
                            item {
                                ActionChip(if (busy) "Stop" else "Talk to Hermes", !busy) {
                                    if (busy) {
                                        if (direct) job?.cancel() else scope.launch { relay.stopRun() }
                                        busy = false
                                    } else {
                                        dictate.launch(
                                            Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
                                                .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM),
                                        )
                                    }
                                }
                            }
                            if (!direct && phoneUp == false) item {
                                Text("No phone connected. Open Hermes Bridge on your phone.", color = Muted, textAlign = TextAlign.Center)
                            }
                            if (busy && turns.lastOrNull()?.role != "assistant") item { Text("Thinking", color = Muted) }
                            // Newest first, so the latest reply is right under the button.
                            items(turns.asReversed()) { t ->
                                Text(
                                    t.text,
                                    color = if (t.role == "user") Muted else Color(0xFFE8EAED),
                                    textAlign = if (t.role == "user") TextAlign.End else TextAlign.Start,
                                    modifier = Modifier.fillMaxWidth(0.9f),
                                )
                            }
                            error?.let { msg -> item { Text(msg, color = Muted, textAlign = TextAlign.Center) } }
                            if (!busy) {
                                if (!direct) item { ActionChip("Chats", false) { screen = Screen.Chats; scope.launch { relay.listChats() } } }
                                item {
                                    ActionChip("New chat", false) {
                                        client.reset(); turns.clear(); error = null
                                        sessionId = if (direct) "" else "new"
                                    }
                                }
                                item { ActionChip(if (direct) "Using server" else "Using phone", false) { screen = Screen.Source } }
                            }
                        }
                    }
                    TimeText()
                }
            }
        }
    }
}

@Composable
private fun ChatsScreen(chats: List<Chat>?, onPick: (Chat) -> Unit, onBack: () -> Unit) {
    ScalingLazyColumn(
        modifier = Modifier.fillMaxSize(),
        state = rememberScalingLazyListState(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        item { Text("Chats on your phone", color = Muted, textAlign = TextAlign.Center) }
        when {
            chats == null -> item { Text("Loading", color = Muted) }
            chats.isEmpty() -> item { Text("No chats yet", color = Muted) }
            else -> items(chats) { c ->
                Chip(
                    onClick = { onPick(c) },
                    label = { Text(c.title, maxLines = 1) },
                    secondaryLabel = if (c.preview.isNotBlank()) ({ Text(c.preview, maxLines = 1) }) else null,
                    colors = ChipDefaults.secondaryChipColors(),
                )
            }
        }
        item { ActionChip("Back", false, onBack) }
    }
}

@Composable
private fun SourceScreen(store: Store, done: () -> Unit) {
    var url by remember { mutableStateOf(store.url) }
    var key by remember { mutableStateOf(store.key) }
    ScalingLazyColumn(
        modifier = Modifier.fillMaxSize(),
        state = rememberScalingLazyListState(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        item { ActionChip("Use phone (Hermes Bridge)", store.url.isEmpty()) { store.url = ""; store.key = ""; done() } }
        item { Text("Or connect directly", color = Muted, textAlign = TextAlign.Center) }
        item { TextBox(url, "http://host:8642", VisualTransformation.None) { url = it } }
        item { TextBox(key, "API key", PasswordVisualTransformation()) { key = it } }
        item { ActionChip("Save server", store.url.isNotEmpty()) { store.url = url; store.key = key; done() } }
        item { ActionChip("Back", false) { done() } }
    }
}

@Composable
private fun TextBox(value: String, hint: String, transform: VisualTransformation, onChange: (String) -> Unit) {
    Box(
        Modifier.fillMaxWidth(0.85f).background(Field, RoundedCornerShape(20.dp)).padding(horizontal = 14.dp, vertical = 10.dp),
    ) {
        if (value.isEmpty()) Text(hint, color = Muted, fontSize = 13.sp)
        BasicTextField(
            value = value,
            onValueChange = onChange,
            singleLine = true,
            visualTransformation = transform,
            textStyle = TextStyle(color = Color(0xFFE8EAED), fontSize = 13.sp),
            cursorBrush = SolidColor(Accent),
        )
    }
}

@Composable
private fun ActionChip(label: String, primary: Boolean, onClick: () -> Unit) {
    Chip(
        onClick = onClick,
        label = { Text(label) },
        colors = if (primary) ChipDefaults.primaryChipColors() else ChipDefaults.secondaryChipColors(),
    )
}
