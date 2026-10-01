package com.chintu.assistant

import android.os.Handler
import android.os.Looper
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

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

        Text("Local tools", fontSize = 22.sp, color = Color.White)
        Text(
            "Calculator, time, date, stopwatch and notes work without internet. Timers are handed to your phone's Clock app. Notes are saved only on this phone, in the app's private storage.",
            color = Dim
        )

        MemorySection()

        Text("Voice", fontSize = 22.sp, color = Color.White)
        Row(verticalAlignment = Alignment.CenterVertically) {
            Switch(checked = cfg.voiceOn, onCheckedChange = { onCfg(cfg.copy(voiceOn = it)) })
            Spacer(Modifier.width(12.dp))
            Text(if (cfg.voiceOn) "Voice ON (replies are spoken)" else "Voice OFF (text only)", color = Color.White)
        }
        OutlinedButton(onClick = {
            val all = Lang.values()
            onCfg(cfg.copy(lang = all[(cfg.lang.ordinal + 1) % all.size], voiceName = ""))
            voices = emptyList()
        }) { Text("Language: ${cfg.lang.label} (tap to change)") }
        Text(
            "Auto: I listen in your phone's default speech language and speak each reply in the language it is written in. Pick a specific language for better listening. Hinglish is heard as Hindi.",
            color = Dim
        )
        Text("Speed: ${"%.1f".format(rateDraft)}", color = Color.White)
        Slider(
            value = rateDraft,
            onValueChange = { rateDraft = it },
            onValueChangeFinished = { onCfg(cfg.copy(rate = rateDraft)) },
            valueRange = 0.5f..2f
        )
        Text("Pitch: ${"%.1f".format(pitchDraft)}", color = Color.White)
        Slider(
            value = pitchDraft,
            onValueChange = { pitchDraft = it },
            onValueChangeFinished = { onCfg(cfg.copy(pitch = pitchDraft)) },
            valueRange = 0.5f..2f
        )
        OutlinedButton(onClick = {
            val err = voice.speak(sampleText(cfg.lang), cfg)
            voiceMsg = err ?: "Speaking a test sentence..."
        }) { Text("Test voice") }
        if (voiceMsg.isNotEmpty()) Text(voiceMsg, color = Dim)
        Text("Saved voice: ${if (cfg.voiceName.isEmpty()) "phone default" else cfg.voiceName}", color = Dim)
        if (cfg.lang != Lang.AUTO) {
            OutlinedButton(onClick = {
                voices = voice.voiceNames(cfg.lang).take(15)
                voiceMsg = if (voices.isEmpty()) "No voices found for this language on this phone." else "Tap a voice to use it:"
            }) { Text("Show voices") }
            TextButton(onClick = { onCfg(cfg.copy(voiceName = "")) }) { Text("Use phone default voice") }
            voices.forEach { v ->
                TextButton(onClick = { onCfg(cfg.copy(voiceName = v)) }) { Text(v) }
            }
        } else {
            Text("Choose a specific language above to pick a voice.", color = Dim)
        }
        Text(
            "Listening is done by your phone's speech service (often Google's) and may use the internet. Speaking uses your phone's text-to-speech engine.",
            color = Dim
        )

        Text("Online AI provider", fontSize = 22.sp, color = Color.White)
        OutlinedTextField(urlDraft, { urlDraft = it }, label = { Text("Server address") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        Button(onClick = { onBaseUrl(urlDraft) }) { Text("Save address") }
        Text("Saved address: $baseUrl", color = Dim)
        Text(if (keySaved) "API key: saved on this phone" else "API key: not set", color = if (keySaved) Cyan else Red)
        OutlinedTextField(
            keyDraft, { keyDraft = it }, label = { Text("Paste API key") }, singleLine = true,
            visualTransformation = PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth()
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { onSaveKey(keyDraft); keyDraft = "" }, enabled = keyDraft.isNotBlank()) { Text("Save key") }
            OutlinedButton(onClick = onClearKey, enabled = keySaved) { Text("Remove key") }
        }
        OutlinedTextField(modelDraft, { modelDraft = it }, label = { Text("Model ID") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        Button(onClick = { onModel(modelDraft) }) { Text("Save model") }
        Text("Saved model: ${if (model.isBlank()) "none" else model}", color = Dim)
        OutlinedButton(
            onClick = {
                loading = true
                modelMsg = "Checking..."
                modelList = emptyList()
                val u = baseUrl
                val k = apiKey
                Thread {
                    var ids: List<String>? = null
                    var err: String? = null
                    try {
                        ids = listModels(u, k)
                    } catch (e: Exception) {
                        err = describe(e)
                    }
                    val i = ids
                    val er = err
                    Handler(Looper.getMainLooper()).post {
                        loading = false
                        if (i != null) {
                            modelList = i.take(40)
                            modelMsg = if (i.isEmpty()) "No models are available to this key."
                            else "Available to your key: ${i.size} (showing up to 40). Tap one to use it:"
                        } else modelMsg = er ?: "Unknown error"
                    }
                }.start()
            },
            enabled = keySaved && !loading
        ) { Text("Check available models") }
        if (modelMsg.isNotEmpty()) Text(modelMsg, color = Dim)
        modelList.forEach { id ->
            TextButton(onClick = { modelDraft = id; onModel(id) }) { Text(id) }
        }
        Text(
            "Your messages are sent to the server address above. A gateway service may forward them to the model provider. The key is stored in this app's private storage, not yet encrypted (planned for the Privacy phase).",
            color = Dim
        )
    }
}
