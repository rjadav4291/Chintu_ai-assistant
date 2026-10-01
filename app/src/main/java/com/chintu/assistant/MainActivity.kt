package com.chintu.assistant

import android.Manifest
import android.content.Context
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel

val Ink = Color(0xFF070B14)
val Glass = Color(0x1AFFFFFF)
val Cyan = Color(0xFF4FD1FF)
val Violet = Color(0xFF8B7CFF)
val Red = Color(0xFFFF5D73)
val Dim = Color(0xFF9AA6BD)

private fun loadCfg(p: SharedPreferences): VoiceCfg = VoiceCfg(
    lang = try { Lang.valueOf(p.getString("lang", "AUTO") ?: "AUTO") } catch (e: Exception) { Lang.AUTO },
    voiceOn = p.getBoolean("voice_on", true),
    rate = p.getFloat("rate", 1f),
    pitch = p.getFloat("pitch", 1f),
    voiceName = p.getString("voice_name", "") ?: ""
)

private fun saveCfg(p: SharedPreferences, c: VoiceCfg) {
    p.edit()
        .putString("lang", c.lang.name)
        .putBoolean("voice_on", c.voiceOn)
        .putFloat("rate", c.rate)
        .putFloat("pitch", c.pitch)
        .putString("voice_name", c.voiceName)
        .apply()
}

class MainActivity : ComponentActivity() {
    private lateinit var voice: VoiceManager

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        voice = VoiceManager(applicationContext)
        val prefs = getSharedPreferences("chintu", Context.MODE_PRIVATE)
        setContent {
            MaterialTheme(colorScheme = darkColorScheme(background = Ink, surface = Ink, primary = Cyan)) {
                var name by remember { mutableStateOf(prefs.getString("name", "Chintu") ?: "Chintu") }
                var apiKey by remember { mutableStateOf(prefs.getString("openai_key", "") ?: "") }
                var model by remember { mutableStateOf(prefs.getString("openai_model", "") ?: "") }
                var baseUrl by remember { mutableStateOf(prefs.getString("base_url", DEFAULT_BASE_URL) ?: DEFAULT_BASE_URL) }
                var cfg by remember { mutableStateOf(loadCfg(prefs)) }
                var showSettings by remember { mutableStateOf(false) }
                Box(Modifier.fillMaxSize().background(Ink).systemBarsPadding()) {
                    if (showSettings) {
                        SettingsScreen(
                            name = name,
                            apiKey = apiKey,
                            keySaved = apiKey.isNotBlank(),
                            model = model,
                            baseUrl = baseUrl,
                            voice = voice,
                            cfg = cfg,
                            onName = { n ->
                                val clean = n.trim().ifEmpty { "Chintu" }
                                prefs.edit().putString("name", clean).apply()
                                name = clean
                            },
                            onSaveKey = { k ->
                                prefs.edit().putString("openai_key", k.trim()).apply()
                                apiKey = k.trim()
                            },
                            onClearKey = {
                                prefs.edit().remove("openai_key").apply()
                                apiKey = ""
                            },
                            onModel = { m ->
                                prefs.edit().putString("openai_model", m.trim()).apply()
                                model = m.trim()
                            },
                            onBaseUrl = { u ->
                                val clean = u.trim().ifEmpty { DEFAULT_BASE_URL }
                                prefs.edit().putString("base_url", clean).apply()
                                baseUrl = clean
                            },
                            onCfg = { c ->
                                cfg = c
                                saveCfg(prefs, c)
                            },
                            back = { showSettings = false }
                        )
                    } else {
                        MainScreen(
                            name = name,
                            baseUrl = baseUrl,
                            apiKey = apiKey,
                            model = model,
                            voice = voice,
                            cfg = cfg,
                            openSettings = { showSettings = true }
                        )
                    }
                }
            }
        }
    }

    override fun onDestroy() {
        voice.release()
        super.onDestroy()
    }
}

@Composable
fun Orb(state: AiState) {
    val t = rememberInfiniteTransition(label = "orb")
    val pulse by t.animateFloat(
        initialValue = 0.94f,
        targetValue = 1.06f,
        animationSpec = infiniteRepeatable(
            tween(if (state == AiState.THINKING || state == AiState.LISTENING) 700 else 2400, easing = FastOutSlowInEasing),
            RepeatMode.Reverse
        ),
        label = "p"
    )
    val core = if (state == AiState.ERROR) Red else Cyan
    Canvas(Modifier.size(200.dp)) {
        val c = Offset(size.width / 2, size.height / 2)
        val r = size.minDimension / 2 * pulse
        drawCircle(Brush.radialGradient(listOf(core.copy(alpha = .35f), Color.Transparent), c, r), r, c)
        drawCircle(Brush.radialGradient(listOf(Color.White.copy(.9f), core, Violet, Color.Transparent), c, r * .62f), r * .62f, c)
    }
}

@Composable
fun MainScreen(
    name: String,
    baseUrl: String,
    apiKey: String,
    model: String,
    voice: VoiceManager,
    cfg: VoiceCfg,
    openSettings: () -> Unit,
    vm: ChatVm = viewModel()
) {
    var input by remember { mutableStateOf("") }
    val ctx = LocalContext.current
    val tools = remember { Tools(ctx) }
    val memory = remember { MemoryStore(ctx) }
    val listState = rememberLazyListState()

    SideEffect { voice.onState = { s -> vm.state = s } }

    LaunchedEffect(vm.messages.size) {
        if (vm.messages.isNotEmpty()) listState.animateScrollToItem(vm.messages.size - 1)
    }

    fun speakIfOn(text: String) {
        if (cfg.voiceOn) {
            val err = voice.speak(text, cfg)
            if (err != null) vm.notice(err, false)
        }
    }

    fun doSend(text: String) {
        if (vm.state == AiState.SPEAKING) voice.stopSpeaking()
        if (vm.state == AiState.THINKING) return
        val local = try {
            memory.handle(text) ?: tools.handle(text)
        } catch (e: Exception) {
            "A local tool failed: ${e.message}"
        }
        if (local != null) {
            vm.addLocal(text.trim(), local)
            speakIfOn(local)
            return
        }
        vm.send(text, baseUrl, apiKey, model, name, memory.promptSection()) { reply -> speakIfOn(reply) }
    }

    fun startMic() {
        vm.state = AiState.LISTENING
        voice.startListening(
            cfg.lang,
            partial = { input = it },
            done = { text ->
                input = ""
                vm.state = AiState.READY
                doSend(text)
            },
            fail = { msg, serious ->
                input = ""
                vm.notice(msg, serious)
            }
        )
    }

    val permLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) startMic()
        else vm.notice("Microphone permission was not allowed, so I can't listen. You can allow it in the phone's app settings.", true)
    }

    fun onMicClick() {
        when (vm.state) {
            AiState.LISTENING -> voice.stopListening()
            AiState.SPEAKING -> voice.stopSpeaking()
            AiState.THINKING -> {}
            else -> {
                if (ctx.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) startMic()
                else permLauncher.launch(Manifest.permission.RECORD_AUDIO)
            }
        }
    }

    val host = hostOf(baseUrl)
    val status = if (vm.state == AiState.THINKING) "THINKING · contacting $host"
    else "${vm.state.name} · ${vm.source}"
    val micLabel = when (vm.state) {
        AiState.LISTENING -> "Stop"
        AiState.SPEAKING -> "Quiet"
        else -> "Mic"
    }
    Column(Modifier.fillMaxSize().padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            TextButton(onClick = openSettings) { Text("Settings", color = Dim) }
        }
        Orb(vm.state)
        Text(name, fontSize = 30.sp, color = Color.White)
        Text(status, color = if (vm.state == AiState.ERROR) Red else Cyan, fontSize = 13.sp)
        Spacer(Modifier.height(12.dp))
        LazyColumn(Modifier.weight(1f).fillMaxWidth(), state = listState, verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(vm.messages) { m ->
                Box(Modifier.fillMaxWidth(), contentAlignment = if (m.fromUser) Alignment.CenterEnd else Alignment.CenterStart) {
                    Text(
                        m.text,
                        color = if (m.isError) Red else Color.White,
                        modifier = Modifier.clip(RoundedCornerShape(16.dp))
                            .background(if (m.fromUser) Cyan.copy(.22f) else Glass)
                            .padding(12.dp)
                    )
                }
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(input, { input = it }, Modifier.weight(1f), placeholder = { Text("Message $name") }, singleLine = true)
            Spacer(Modifier.width(8.dp))
            OutlinedButton(onClick = { onMicClick() }, enabled = vm.state != AiState.THINKING) { Text(micLabel) }
            Spacer(Modifier.width(4.dp))
            Button(
                onClick = {
                    val t = input
                    input = ""
                    doSend(t)
                },
                enabled = vm.state != AiState.THINKING && vm.state != AiState.LISTENING
            ) { Text("Send") }
        }
    }
}
