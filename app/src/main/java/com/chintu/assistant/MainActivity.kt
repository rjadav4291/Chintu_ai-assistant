package com.chintu.assistant

import android.Manifest
import android.content.Context
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewmodel.compose.viewModel
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import org.json.JSONArray
import org.json.JSONObject

private val Ink = Color(0xFF070B14)
private val Glass = Color(0x1AFFFFFF)
private val Cyan = Color(0xFF4FD1FF)
private val Violet = Color(0xFF8B7CFF)
private val Red = Color(0xFFFF5D73)
private val Dim = Color(0xFF9AA6BD)

const val DEFAULT_BASE_URL = "https://api.experientiallabs.ai/v1"

enum class AiState { READY, LISTENING, THINKING, SPEAKING, OFFLINE, ERROR }
data class Msg(val fromUser: Boolean, val text: String, val isError: Boolean = false)
class AiException(message: String) : Exception(message)

fun systemPrompt(name: String) = """
You are $name, a personal assistant inside an Android app.
Personality: friendly, calm, helpful, natural, slightly futuristic. Keep answers short, in short natural sentences. Explain step by step only when the question is complicated.
Reply in the language the user writes in (Gujarati, Hindi, English or Hinglish).
Your replies may be spoken aloud, so do not use emojis, markdown, bullet symbols or long lists.
Truth rules: in this version you can only chat. You have NO tools yet. You cannot open apps, save notes or memories, set timers or reminders, search the web, check weather, or control the phone. Never claim you did any of these. If asked, say honestly that it is not available yet.
Do not imitate any fictional character.
""".trimIndent()

fun hostOf(baseUrl: String): String =
    try { URL(baseUrl).host } catch (e: Exception) { "online AI" }

// Sends one request. Follows redirects only to the SAME host over https,
// so the API key is never sent to a different server.
private fun request(url: String, method: String, apiKey: String, body: String?): String {
    var current = URL(url.trim())
    val originalHost = current.host
    var hops = 0
    while (true) {
        val conn = current.openConnection() as HttpURLConnection
        try {
            conn.instanceFollowRedirects = false
            conn.requestMethod = method
            conn.connectTimeout = 15000
            conn.readTimeout = 90000
            conn.setRequestProperty("Authorization", "Bearer $apiKey")
            if (body != null) {
                conn.doOutput = true
                conn.setRequestProperty("Content-Type", "application/json")
                conn.outputStream.use { it.write(body.toByteArray()) }
            }
            val code = conn.responseCode
            if (code == 301 || code == 302 || code == 307 || code == 308) {
                val loc = conn.getHeaderField("Location")
                    ?: throw AiException("HTTP $code The server asked to redirect but gave no address.")
                val next = try { URL(current, loc) } catch (e: Exception) {
                    throw AiException("HTTP $code The server sent a redirect address I could not read: $loc")
                }
                if (next.protocol != "https" || next.host != originalHost) {
                    throw AiException("HTTP $code The server tried to redirect to ${next.protocol}://${next.host}. I did not follow it, to protect your key.")
                }
                hops++
                if (hops > 3) throw AiException("Too many redirects.")
                current = next
                continue
            }
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val text = stream?.bufferedReader()?.use { it.readText() } ?: ""
            if (code !in 200..299) {
                val detail = try { JSONObject(text).getJSONObject("error").getString("message") } catch (e: Exception) { text.take(200) }
                throw AiException("HTTP $code $detail")
            }
            return text
        } finally {
            conn.disconnect()
        }
    }
}

fun callAi(baseUrl: String, apiKey: String, model: String, system: String, turns: List<Pair<String, String>>): String {
    val msgs = JSONArray()
    msgs.put(JSONObject().put("role", "system").put("content", system))
    for ((role, text) in turns) msgs.put(JSONObject().put("role", role).put("content", text))
    val body = JSONObject()
        .put("model", model)
        .put("max_completion_tokens", 2000)
        .put("messages", msgs)
    val text = request(baseUrl.trim().trimEnd('/') + "/chat/completions", "POST", apiKey, body.toString())
    val choices = JSONObject(text).getJSONArray("choices")
    val out = choices.getJSONObject(0).getJSONObject("message").optString("content", "").trim()
    if (out.isEmpty()) throw AiException("The AI returned an empty reply.")
    return out
}

fun listModels(baseUrl: String, apiKey: String): List<String> {
    val text = request(baseUrl.trim().trimEnd('/') + "/models", "GET", apiKey, null)
    val data = JSONObject(text).getJSONArray("data")
    val ids = mutableListOf<String>()
    for (i in 0 until data.length()) {
        val id = data.getJSONObject(i).optString("id", "")
        if (id.isNotEmpty()) ids.add(id)
    }
    return ids
}

private fun describe(e: Exception): String = when (e) {
    is AiException -> "The AI service returned an error: ${e.message}"
    is IOException -> "I couldn't connect to the AI service. No offline AI is available yet.\nDetails: ${e.javaClass.simpleName}: ${e.message}"
    else -> "Something went wrong: ${e.javaClass.simpleName}: ${e.message}"
}

class ChatVm : ViewModel() {
    var state by mutableStateOf(AiState.READY)
    val messages = mutableStateListOf<Msg>()

    private fun fail(text: String) {
        messages.add(Msg(false, text, true))
        state = AiState.ERROR
    }

    fun notice(text: String, serious: Boolean) {
        messages.add(Msg(false, text, true))
        state = if (serious) AiState.ERROR else AiState.READY
    }

    private fun buildTurns(): List<Pair<String, String>> {
        val turns = mutableListOf<Pair<String, String>>()
        for (m in messages) {
            if (m.isError) continue
            val role = if (m.fromUser) "user" else "assistant"
            if (turns.isNotEmpty() && turns.last().first == role) {
                turns[turns.size - 1] = role to (turns.last().second + "\n" + m.text)
            } else turns.add(role to m.text)
        }
        var sub = turns.takeLast(20)
        if (sub.isNotEmpty() && sub.first().first != "user") sub = sub.drop(1)
        return sub
    }

    fun send(text: String, baseUrl: String, apiKey: String, model: String, name: String, onReply: (String) -> Unit) {
        val t = text.trim()
        if (t.isEmpty() || state == AiState.THINKING) return
        messages.add(Msg(true, t))
        if (apiKey.isBlank()) {
            fail("No API key is set. Open Settings and add your key.")
            return
        }
        if (model.isBlank()) {
            fail("No model is set. Open Settings and choose a model.")
            return
        }
        state = AiState.THINKING
        val turns = buildTurns()
        val sys = systemPrompt(name)
        Thread {
            var reply: String? = null
            var err: String? = null
            try {
                reply = callAi(baseUrl, apiKey, model, sys, turns)
            } catch (e: Exception) {
                err = describe(e)
            }
            val r = reply
            val er = err
            Handler(Looper.getMainLooper()).post {
                if (r != null) {
                    messages.add(Msg(false, r))
                    state = AiState.READY
                    onReply(r)
                } else fail(er ?: "Unknown error")
            }
        }.start()
    }
}

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
    val listState = rememberLazyListState()

    SideEffect { voice.onState = { s -> vm.state = s } }

    LaunchedEffect(vm.messages.size) {
        if (vm.messages.isNotEmpty()) listState.animateScrollToItem(vm.messages.size - 1)
    }

    fun doSend(text: String) {
        if (vm.state == AiState.SPEAKING) voice.stopSpeaking()
        vm.send(text, baseUrl, apiKey, model, name) { reply ->
            if (cfg.voiceOn) {
                val err = voice.speak(reply, cfg)
                if (err != null) vm.notice(err, false)
            }
        }
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
    else "${vm.state.name} · ONLINE AI"
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

@Composable
fun SettingsScreen(
    name: String,
    apiKey: String,
    keySaved: Boolean,
    model: String,
    baseUrl: String,
    voice: VoiceManager,
    cfg: VoiceCfg,
    onName: (String) -> Unit,
    onSaveKey: (String) -> Unit,
    onClearKey: () -> Unit,
    onModel: (String) -> Unit,
    onBaseUrl: (String) -> Unit,
    onCfg: (VoiceCfg) -> Unit,
    back: () -> Unit
) {
    var draft by remember { mutableStateOf(name) }
    var keyDraft by remember { mutableStateOf("") }
    var modelDraft by remember { mutableStateOf(model) }
    var urlDraft by remember { mutableStateOf(baseUrl) }
    var modelList by remember { mutableStateOf<List<String>>(emptyList()) }
    var modelMsg by remember { mutableStateOf("") }
    var loading by remember { mutableStateOf(false) }
    var rateDraft by remember { mutableStateOf(cfg.rate) }
    var pitchDraft by remember { mutableStateOf(cfg.pitch) }
    var voiceMsg by remember { mutableStateOf("") }
    var voices by remember { mutableStateOf<List<String>>(emptyList()) }
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        TextButton(onClick = back) { Text("Back") }
        Text("Assistant", fontSize = 22.sp, color = Color.White)
        OutlinedTextField(draft, { draft = it }, label = { Text("Assistant name") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        Button(onClick = { onName(draft) }) { Text("Save name") }

        Text("Voice", fontSize = 22.sp, color = Color.White)
        Row(verticalAlignment = Alignment.CenterVertically) {
            Switch(checked = cfg.voiceOn, onCheckedChange =
