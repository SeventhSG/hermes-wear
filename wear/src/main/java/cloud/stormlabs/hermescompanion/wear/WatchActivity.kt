package cloud.stormlabs.hermescompanion.wear

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.speech.RecognizerIntent
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.wear.compose.foundation.lazy.ScalingLazyColumn
import androidx.wear.compose.foundation.lazy.rememberScalingLazyListState
import androidx.wear.compose.material.Chip
import androidx.wear.compose.material.ChipDefaults
import androidx.wear.compose.material.Colors
import androidx.wear.compose.material.MaterialTheme
import androidx.wear.compose.material.Text
import androidx.wear.compose.material.TimeText
import kotlinx.coroutines.launch

// One accent, locked across the app, on an off-black background. No red: nothing here is a
// destructive state, and a failure is a plain muted line.
private val Accent = Color(0xFF4F8FF7)
private val Ink = Color(0xFF0E1013)
private val Muted = Color(0xFF9AA0A8)

class WatchActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { WatchApp() }
    }
}

private data class UiState(
    val prompt: String = "",
    val reply: String = "",
    val busy: Boolean = false,
    val error: String? = null,
    val approval: Relay.Event.Approval? = null,
)

@Composable
private fun WatchApp() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var ui by remember { mutableStateOf(UiState()) }
    var phoneUp by remember { mutableStateOf<Boolean?>(null) }

    val relay = remember {
        Relay(context) { e ->
            ui = when (e) {
                is Relay.Event.Delta -> ui.copy(reply = ui.reply + e.text)
                is Relay.Event.Done -> ui.copy(reply = e.text, busy = false, approval = null)
                is Relay.Event.Failed -> ui.copy(error = e.message, busy = false, approval = null)
                is Relay.Event.Approval -> ui.copy(approval = e)
            }
        }
    }
    LaunchedEffect(Unit) { relay.start(); phoneUp = relay.phoneReachable() }
    DisposableEffect(Unit) { onDispose { relay.stop() } }

    val dictate = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
        val said = r.data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)?.firstOrNull()
        if (r.resultCode == Activity.RESULT_OK && !said.isNullOrBlank()) {
            ui = UiState(prompt = said, busy = true)
            scope.launch { relay.ask(said) }
        }
    }

    val colors = Colors(primary = Accent, background = Ink, onBackground = Color(0xFFE8EAED), onSurface = Color(0xFFE8EAED))
    MaterialTheme(colors = colors) {
        val list = rememberScalingLazyListState()
        ScalingLazyColumn(
            modifier = Modifier.fillMaxSize(),
            state = list,
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            val a = ui.approval
            if (a != null) {
                item { Text("Hermes wants to run", color = Muted, textAlign = TextAlign.Center) }
                item { Text(a.command.take(160), textAlign = TextAlign.Center) }
                item { ActionChip("Allow once", true) { scope.launch { relay.approve(a, "once") }; ui = ui.copy(approval = null) } }
                item { ActionChip("Deny", false) { scope.launch { relay.approve(a, "deny") }; ui = ui.copy(approval = null) } }
            } else {
                item {
                    ActionChip(if (ui.busy) "Stop" else "Ask Hermes", !ui.busy) {
                        if (ui.busy) {
                            scope.launch { relay.stopRun() }
                            ui = ui.copy(busy = false)
                        } else {
                            dictate.launch(
                                Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
                                    .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM),
                            )
                        }
                    }
                }
                if (phoneUp == false) item { Text("No phone connected. Open the Hermes app on your phone.", color = Muted, textAlign = TextAlign.Center) }
                if (ui.prompt.isNotEmpty()) item { Text(ui.prompt, color = Muted, textAlign = TextAlign.Center) }
                if (ui.busy && ui.reply.isEmpty()) item { Text("Thinking", color = Muted) }
                if (ui.reply.isNotEmpty()) item { Text(ui.reply, textAlign = TextAlign.Start) }
                ui.error?.let { msg -> item { Text(msg, color = Muted, textAlign = TextAlign.Center) } }
            }
        }
        TimeText()
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
