package com.chintu.assistant

import android.content.Context
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewmodel.compose.viewModel

private val Ink = Color(0xFF070B14)
private val Glass = Color(0x1AFFFFFF)
private val Cyan = Color(0xFF4FD1FF)
private val Violet = Color(0xFF8B7CFF)
private val Red = Color(0xFFFF5D73)
private val Dim = Color(0xFF9AA6BD)

enum class AiState { READY, LISTENING, THINKING, SPEAKING, OFFLINE, ERROR }
data class Msg(val fromUser: Boolean, val text: String)

class ChatVm : ViewModel() {
    var state by mutableStateOf(AiState.READY)
    var mode by mutableStateOf("AUTO")
    val messages = mutableStateListOf<Msg>()

    fun send(text: String) {
        if (text.isBlank()) return
        messages.add(Msg(true, text.trim()))
        // Phase 1: no AI engine exists yet. Say so instead of inventing a reply.
        messages.add(Msg(false, "I received your message, but my AI engine isn't connected yet (added in Phase 2)."))
        state = AiState.READY
    }
}

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val prefs = getSharedPreferences("chintu", Context.MODE_PRIVATE)
        setContent {
            MaterialTheme(colorScheme = darkColorScheme(background = Ink, surface = Ink, primary = Cyan)) {
                var name by remember { mutableStateOf(prefs.getString("name", "Chintu")!!) }
                var showSettings by remember { mutableStateOf(false) }
                Box(Modifier.fillMaxSize().background(Ink).systemBarsPadding()) {
                    if (showSettings) SettingsScreen(name, { n ->
                        val clean = n.trim().ifEmpty { "Chintu" }
                        prefs.edit().putString("name", clean).apply(); name = clean
                    }) { showSettings = false }
                    else MainScreen(name) { showSettings = true }
                }
            }
        }
    }
}

@Composable
fun Orb(state: AiState) {
    val t = rememberInfiniteTransition(label = "orb")
    val pulse by t.animateFloat(0.94f, 1.06f,
        infiniteRepeatable(tween(if (state == AiState.THINKING) 700 else 2400, easing = Easing { FastOutSlowInEasing.transform(it) }), RepeatMode.Reverse), label = "p")
    val core = if (state == AiState.ERROR) Red else Cyan
    Canvas(Modifier.size(200.dp)) {
        val c = Offset(size.width / 2, size.height / 2); val r = size.minDimension / 2 * pulse
        drawCircle(Brush.radialGradient(listOf(core.copy(alpha = .35f), Color.Transparent), c, r), r, c)
        drawCircle(Brush.radialGradient(listOf(Color.White.copy(.9f), core, Violet, Color.Transparent), c, r * .62f), r * .62f, c)
    }
}

@Composable
fun MainScreen(name: String, openSettings: () -> Unit, vm: ChatVm = viewModel()) {
    var input by remember { mutableStateOf("") }
    Column(Modifier.fillMaxSize().padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            TextButton(onClick = openSettings) { Text("Settings", color = Dim) }
        }
        Orb(vm.state)
        Text(name, fontSize = 30.sp, color = Color.White)
        Text("${vm.state.name} · ${vm.mode}", color = if (vm.state == AiState.ERROR) Red else Cyan, fontSize = 13.sp)
        Spacer(Modifier.height(12.dp))
        LazyColumn(Modifier.weight(1f).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(vm.messages) { m ->
                Box(Modifier.fillMaxWidth(), contentAlignment = if (m.fromUser) Alignment.CenterEnd else Alignment.CenterStart) {
                    Text(m.text, color = Color.White, modifier = Modifier.clip(RoundedCornerShape(16.dp))
                        .background(if (m.fromUser) Cyan.copy(.22f) else Glass).padding(12.dp))
                }
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(input, { input = it }, Modifier.weight(1f), placeholder = { Text("Message $name") }, singleLine = true)
            Spacer(Modifier.width(8.dp))
            // Mic is disabled until voice is built in Phase 3 (no fake button).
            OutlinedButton(onClick = {}, enabled = false) { Text("Mic") }
            Spacer(Modifier.width(4.dp))
            Button(onClick = { vm.send(input); input = "" }) { Text("Send") }
        }
    }
}

@Composable
fun SettingsScreen(name: String, onName: (String) -> Unit, back: () -> Unit) {
    var draft by remember { mutableStateOf(name) }
    Column(Modifier.fillMaxSize().padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        TextButton(onClick = back) { Text("Back") }
        Text("Assistant", fontSize = 22.sp, color = Color.White)
        OutlinedTextField(draft, { draft = it }, label = { Text("Assistant name") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        Button(onClick = { onName(draft) }) { Text("Save name") }
        Text("Wake word, voice, AI, memory, privacy, skills and automation settings arrive in later phases.", color = Dim)
    }
}
