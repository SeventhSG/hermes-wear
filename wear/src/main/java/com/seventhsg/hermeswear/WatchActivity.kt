package com.seventhsg.hermeswear

import android.app.Activity
import android.content.Intent
import android.os.Bundle
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.wear.compose.foundation.lazy.ScalingLazyColumn
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

class WatchActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Typing a long address on a watch is miserable, so adb can set it:
        // adb shell am start -n com.seventhsg.hermeswear/.WatchActivity --es url http://host:8642 --es key KEY
        val store = Store(this)
        intent.getStringExtra("url")?.let { store.url = it }
        intent.getStringExtra("key")?.let { store.key = it }
        setContent { WatchApp(store) }
    }
}

private enum class Screen { Chat, Settings }

@Composable
private fun WatchApp(store: Store) {
    val scope = rememberCoroutineScope()
    var screen by remember { mutableStateOf(if (store.configured) Screen.Chat else Screen.Settings) }
    var client by remember { mutableStateOf(HermesClient(store.url, store.key)) }
    var prompt by remember { mutableStateOf("") }
    var reply by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    var job by remember { mutableStateOf<Job?>(null) }
    val busy = job?.isActive == true

    fun ask(text: String) {
        prompt = text; reply = ""; error = null
        job = scope.launch {
            try {
                client.ask(text) { d -> reply += d }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                error = e.message ?: "Request failed"
            }
        }
    }

    val dictate = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
        val said = r.data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)?.firstOrNull()
        if (r.resultCode == Activity.RESULT_OK && !said.isNullOrBlank()) ask(said)
    }

    val colors = Colors(primary = Accent, background = Ink, onBackground = Color(0xFFE8EAED), onSurface = Color(0xFFE8EAED))
    MaterialTheme(colors = colors) {
        Box(Modifier.fillMaxSize().background(Ink)) {
            if (screen == Screen.Settings) {
                SettingsScreen(store) { saved ->
                    if (saved) client = HermesClient(store.url, store.key)
                    if (store.configured) screen = Screen.Chat
                }
            } else {
                ScalingLazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    state = rememberScalingLazyListState(),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    item {
                        ActionChip(if (busy) "Stop" else "Ask Hermes", !busy) {
                            if (busy) job?.cancel()
                            else dictate.launch(
                                Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
                                    .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM),
                            )
                        }
                    }
                    if (prompt.isNotEmpty()) item { Text(prompt, color = Muted, textAlign = TextAlign.Center) }
                    if (busy && reply.isEmpty()) item { Text("Thinking", color = Muted) }
                    if (reply.isNotEmpty()) item { Text(reply, textAlign = TextAlign.Start) }
                    error?.let { msg -> item { Text(msg, color = Muted, textAlign = TextAlign.Center) } }
                    if (!busy && (prompt.isNotEmpty())) item {
                        ActionChip("New chat", false) { client.reset(); prompt = ""; reply = ""; error = null }
                    }
                    if (!busy) item { ActionChip("Server", false) { screen = Screen.Settings } }
                }
                TimeText()
            }
        }
    }
}

@Composable
private fun SettingsScreen(store: Store, done: (saved: Boolean) -> Unit) {
    var url by remember { mutableStateOf(store.url) }
    var key by remember { mutableStateOf(store.key) }
    ScalingLazyColumn(
        modifier = Modifier.fillMaxSize(),
        state = rememberScalingLazyListState(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        item { Text("Hermes server", color = Muted) }
        item { TextBox(url, "http://host:8642", VisualTransformation.None) { url = it } }
        item { TextBox(key, "API key", PasswordVisualTransformation()) { key = it } }
        item {
            ActionChip("Save", true) {
                store.url = url; store.key = key
                done(true)
            }
        }
        if (store.configured) item { ActionChip("Cancel", false) { done(false) } }
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
