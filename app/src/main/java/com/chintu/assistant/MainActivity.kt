package com.chintu.assistant

import android.content.Context
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
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

const val DEFAULT_MODEL = "gpt-6-astra"

enum class AiState { READY, LISTENING, THINKING, SPEAKING, OFFLINE, ERROR }
data class Msg(val fromUser: Boolean, val text: String, val isError: Boolean = false)
class AiException(message: String) : Exception(message)

fun systemPrompt(name: String) = """
You are $name, a personal assistant inside an Android app.
Personality: friendly, calm, helpful, natural, slightly futuristic. Keep answers short, in short natural sentences. Explain step by step only when the question is complicated.
Reply in the language the user writes in (Gujarati, Hindi, English or Hinglish).
Truth rules: in this version you can only chat. You have NO tools yet. You cannot open apps, save notes or memories, set timers or reminders, search the web, check weather, or control the phone. Never claim you did any of these. If asked, say honestly that it is not available yet.
Do not imitate any fictional character.
""".trimIndent()

fun callAi(apiKey: String, model: String, system: String, turns: List<Pair<String, String>>): String {
    val msgs = JSONArray()
    msgs.put(JSONObject().put("role", "system").put("content", system))
    for ((role, text) in turns) msgs.put(JSONObject().put("role", role).put("content", text))
    val body = JSONObject()
        .put("model", model)
        .put("max_completion_tokens", 2000)
        .put("messages", msgs)
    val conn = URL("https://api.openai.com/v1/chat/completions").openConnection() as HttpURLConnection
    try {
        conn.requestMethod = "POST"
        conn.connectTimeout = 15000
        conn.readTimeout = 90000
        conn.doOutput = true
        conn.setRequestProperty("Content-Type", "application/json")
        conn.setRequestProperty("Authorization", "Bearer $apiKey")
        conn.outputStream.use { it.write(body.toString().toByteArray()) }
        val code = conn.responseCode
        val stream = if (code in 200..299) conn.inputStream else conn.errorStream
        val text = stream?.bufferedReader()?.use { it.readText() } ?: ""
        if (code !in 200..299) {
            val detail = try { JSONObject(text).getJSONObject("error").getString("message") } catch (e: Exception) { "" }
            throw AiException("HTTP $code $detail")
        }
        val choices = JSONObject(text).getJSONArray("choices")
        val out = choices.getJSONObject(0).getJSONObject("message").optString("content", "").trim()
        if (out.isEmpty()) throw AiException("The AI returned an empty reply.")
        return out
    } finally {
        conn.disconnect()
    }
}

private fun describe(e: Exception): String = when (e) {
    is AiException -> "The AI service returned an error: ${e.message}"
    is IOException -> "I couldn't connect to the AI service. No offline AI is available yet."
    else -> "Something went wrong: ${e.message}"
}

class ChatVm : ViewModel() {
    var state by mutableStateOf(AiState.READY)
    val messages = mutableStateListOf<Msg>()

    private fun fail(text: String) {
        messages.add(Msg(false, text, true))
        state = AiState.ERROR
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

    fun send(text: String, apiKey: String, model: String, name: String) {
        val t = text.trim()
        if (t.isEmpty() || state == AiState.THINKING) return
        messages.add(Msg(true, t))
        if (apiKey.isBlank()) {
            fail("No API key is set. Open Settings and add your key.")
            return
        }
        state = AiState.THINKING
        val turns = buildTurns()
        val sys = systemPrompt(name)
        Thread {
            var reply: String? = null
            var err: String? = null
            try {
                reply = callAi(apiKey, model, sys, turns)
            } catch (e: Exception) {
                err = describe(e)
            }
            val r = reply
            val er = err
            Handler(Looper.getMainLooper()).post {
                if (r != null) {
                    messages.add(Msg(false, r))
                    state = AiState.READY
                } else fail(er ?: "Unknown error")
            }
        }.start()
    }
}

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val prefs = getSharedPreferences("chintu", Context.MODE_PRIVATE)
        setContent {
            MaterialTheme(colorScheme = darkColorScheme(background = Ink, surface = Ink, primary = Cyan)) {
                var name by remember { mutableStateOf(prefs.getString("name", "Chintu") ?: "Chintu") }
                var apiKey by remember { mutableStateOf(prefs.getString("openai_key", "") ?: "") }
                var model by remember { mutableStateOf(prefs.getString("openai_model", DEFAULT_MODEL) ?: DEFAULT_MODEL) }
                var showSettings by remember { mutableStateOf(false) }
                Box(Modifier.fillMaxSize().background(Ink).systemBarsPadding()) {
                    if (showSettings) {
                        SettingsScreen(
                            name = name,
                            keySaved = apiKey.isNotBlank(),
                            model = model,
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
                                val clean = m.trim().ifEmpty { DEFAULT_MODEL }
                                prefs.edit().putString("openai_model", clean).apply()
                                model = clean
                            },
                            back = { showSettings = false }
                        )
                    } else {
                        MainScreen(name = name, apiKey = apiKey, model = model, openSettings = { showSettings = true })
                    }
                }
            }
        }
    }
}

@Composable
fun Orb(state: AiState) {
    val t = rememberInfiniteTransition(label = "orb")
    val pulse by t.animateFloat(
        initialValue = 0.94f,
        targetValue = 1.06f,
        animationSpec = infiniteRepeatable(
            tween(if (state == AiState.THINKING) 700 else 2400, easing = FastOutSlowInEasing),
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
fun MainScreen(name: String, apiKey: String, model: String, openSettings: () -> Unit, vm: ChatVm = viewModel()) {
    var input by remember { mutableStateOf("") }
    val listState = rememberLazyListState()
    LaunchedEffect(vm.messages.size) {
        if (vm.messages.isNotEmpty()) listState.animateScrollToItem(vm.messages.size - 1)
    }
    val status = if (vm.state == AiState.THINKING) "THINKING · contacting online AI (OpenAI)"
    else "${vm.state.name} · ONLINE AI"
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
            // Mic is disabled until voice is built in Phase 3 (no fake button).
            OutlinedButton(onClick = {}, enabled = false) { Text("Mic") }
            Spacer(Modifier.width(4.dp))
            Button(
                onClick = { vm.send(input, apiKey, model, name); input = "" },
                enabled = vm.state != AiState.THINKING
            ) { Text("Send") }
        }
    }
}

@Composable
fun SettingsScreen(
    name: String,
    keySaved: Boolean,
    model: String,
    onName: (String) -> Unit,
    onSaveKey: (String) -> Unit,
    onClearKey: () -> Unit,
    onModel: (String) -> Unit,
    back: () -> Unit
) {
    var draft by remember { mutableStateOf(name) }
    var keyDraft by remember { mutableStateOf("") }
    var modelDraft by remember { mutableStateOf(model) }
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        TextButton(onClick = back) { Text("Back") }
        Text("Assistant", fontSize = 22.sp, color = Color.White)
        OutlinedTextField(draft, { draft = it }, label = { Text("Assistant name") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        Button(onClick = { onName(draft) }) { Text("Save name") }
        Text("Online AI (OpenAI)", fontSize = 22.sp, color = Color.White)
        Text(if (keySaved) "API key: saved on this phone" else "API key: not set", color = if (keySaved) Cyan else Red)
        OutlinedTextField(
            keyDraft, { keyDraft = it }, label = { Text("Paste API key") }, singleLine = true,
            visualTransformation = PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth()
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { onSaveKey(keyDraft); keyDraft = "" }, enabled = keyDraft.isNotBlank()) { Text("Save key") }
            OutlinedButton(onClick = onClearKey, enabled = keySaved) { Text("Remove key") }
        }
        OutlinedTextField(modelDraft, { modelDraft = it }, label = { Text("Model") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        Button(onClick = { onModel(modelDraft) }) { Text("Save model") }
        Text(
            "Your messages are sent to OpenAI's servers to get replies. The key is stored in this app's private storage, not yet encrypted (planned for the Privacy phase).",
            color = Dim
        )
    }
}
