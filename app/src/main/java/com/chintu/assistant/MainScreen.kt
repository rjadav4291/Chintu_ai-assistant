package com.chintu.assistant

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay

@Composable
fun MainScreen(
    name: String,
    baseUrl: String,
    apiKey: String,
    model: String,
    voice: VoiceManager,
    cfg: VoiceCfg,
    mode: AiMode,
    privateOn: Boolean,
    wakeOn: Boolean,
    local: LocalAi,
    onWakeOff: () -> Unit,
    openSettings: () -> Unit,
    openAi: () -> Unit,
    openModel: () -> Unit,
    openPrivacy: () -> Unit,
    openSkills: () -> Unit,
    openReminders: () -> Unit,
    vm: ChatVm = viewModel()
) {
    var input by remember { mutableStateOf("") }
    var toolHost by remember { mutableStateOf("") }
    var menuOpen by remember { mutableStateOf(false) }
    var wakeTick by remember { mutableStateOf(0) }
    var wakeFails by remember { mutableStateOf(0) }
    val ctx = LocalContext.current
    val tools = remember { Tools(ctx) }
    val memory = remember { MemoryStore(ctx) }
    val launcher = remember { AppLauncher(ctx) }
    val online = remember { OnlineTools(ctx) }
    val skills = remember { SkillSettings(ctx) }
    val reminders = remember { ReminderSkill(ctx) }
    val listState = rememberLazyListState()

    // Private Mode forces OFFLINE: neither the online AI nor the online tools are used.
    val effectiveMode = if (privateOn) AiMode.OFFLINE else mode
    val onlineReady = apiKey.isNotBlank() && model.isNotBlank()
    val onlinePath = onlineReady && effectiveMode != AiMode.OFFLINE
    val host = hostOf(baseUrl)

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

    val notifLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) {
            vm.messages.add(Msg(false, "Notifications are allowed now. Please say your reminder again."))
            vm.state = AiState.READY
        } else {
            vm.notice("Notifications are not allowed, so I can't set reminders. You can allow them in the phone's app settings.", true)
        }
    }

    fun runOnlineTool(text: String, req: Req) {
        vm.messages.add(Msg(true, text.trim()))
        vm.state = AiState.THINKING
        val h = online.hostFor(req)
        toolHost = h
        PrivacyLog.record(h)
        Thread {
            val out = online.run(req)
            Handler(Looper.getMainLooper()).post {
                toolHost = ""
                if (out.ok) {
                    vm.messages.add(Msg(false, out.text))
                    vm.source = "WEB: $h"
                    vm.state = AiState.READY
                    speakIfOn(out.text.substringBefore("\n\n"))
                } else {
                    vm.notice(out.text, true)
                }
            }
        }.start()
    }

    fun doSend(text: String) {
        if (text.isBlank()) return
        if (vm.state == AiState.SPEAKING) voice.stopSpeaking()
        if (vm.state == AiState.THINKING) return
        // Reminders first, so that "remember to ..." and "remind me ..." are understood correctly.
        val rr = try {
            if (skills.enabled("reminders")) reminders.handle(text, Reminders.notifOk(ctx)) else null
        } catch (e: Exception) {
            RemResult("A reminder tool failed: ${e.message}")
        }
        if (rr != null) {
            vm.addLocal(text.trim(), rr.reply)
            speakIfOn(rr.reply)
            if (rr.needPermission && Build.VERSION.SDK_INT >= 33) {
                notifLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
            return
        }
        // Local skills run next and never contact any AI or website. A skill that is switched off is skipped.
        val localReply = try {
            (if (skills.enabled("memory")) memory.handle(text) else null)
                ?: tools.handle(text) { id -> skills.enabled(id) }
                ?: (if (skills.enabled("apps")) launcher.handle(text) else null)
                ?: (if (skills.enabled("weather")) online.setCity(text) else null)
        } catch (e: Exception) {
            "A local tool failed: ${e.message}"
        }
        if (localReply != null) {
            vm.addLocal(text.trim(), localReply)
            speakIfOn(localReply)
            return
        }
        // Weather, Wikipedia and web search.
        val req = try { online.parse(text) } catch (e: Exception) { null }
        if (req != null) {
            val skillId = when (req.kind) {
                "weather" -> "weather"
                "wiki" -> "wiki"
                else -> "search"
            }
            if (!skills.enabled(skillId)) {
                val off = "The ${SkillCatalog.nameOf(skillId)} skill is turned off, so I did not use it. You can turn it on in Skills."
                vm.addLocal(text.trim(), off)
                speakIfOn(off)
                return
            }
            if (req.kind == "weather" && req.arg.isBlank()) {
                val ask = "Which city? Say for example: weather in Ahmedabad. You can also say: set my city to Ahmedabad."
                vm.addLocal(text.trim(), ask)
                speakIfOn(ask)
                return
            }
            if (effectiveMode == AiMode.OFFLINE) {
                val refuse = "That needs the internet, but ${if (privateOn) "Private Mode" else "OFFLINE mode"} is on, so I did not go online. Change it in Mode or Privacy."
                vm.addLocal(text.trim(), refuse)
                speakIfOn(refuse)
                return
            }
            runOnlineTool(text, req)
            return
        }
        if (privateOn && !local.available) {
            vm.messages.add(Msg(true, text.trim()))
            vm.notice(
                "Private Mode is ON, so I only use the on-device AI, and it isn't ready (${local.status().trimEnd('.')}). Nothing was sent online. Turn Private Mode off in Privacy, or set up the offline model in Model.",
                true
            )
            return
        }
        if (onlinePath) PrivacyLog.record(host)
        // Extra context for the AI: what the app can do, which skills are off, plus saved memories (only if Memory is ON).
        val offNames = SkillCatalog.all.filter { it.available && !skills.enabled(it.id) }.map { it.name }
        val extra = listOf(LAUNCHER_PROMPT, ONLINE_TOOLS_PROMPT, skillsPrompt(offNames), memory.promptSection())
            .filter { it.isNotEmpty() }.joinToString("\n\n")
        vm.sendRouted(text, effectiveMode, local, baseUrl, apiKey, model, name, extra) { reply -> speakIfOn(reply) }
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
            },
            onDeviceOnly = privateOn
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

    // Wake word: while the app is on screen and idle, listen on-device for "Hey <name>". Nothing is sent online.
    LaunchedEffect(wakeOn, vm.state, AppFlags.foreground, wakeTick) {
        if (!wakeOn || !AppFlags.foreground || vm.state != AiState.READY) return@LaunchedEffect
        if (ctx.checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) return@LaunchedEffect
        if (!voice.onDeviceAvailable()) {
            onWakeOff()
            vm.notice("The wake word was turned off, because this phone can't recognise speech on the device itself.", true)
            return@LaunchedEffect
        }
        delay(400)
        voice.startListening(
            Lang.ENGLISH,
            partial = { },
            done = { text ->
                wakeFails = 0
                val rest = Wake.match(text, name)
                if (rest == null) {
                    wakeTick++
                } else if (rest.isNotBlank()) {
                    doSend(rest)
                } else {
                    startMic()
                }
            },
            fail = { msg, serious ->
                if (serious) {
                    wakeFails++
                    if (wakeFails >= 3) {
                        onWakeOff()
                        vm.notice("The wake word was turned off after repeated problems: $msg", true)
                    } else {
                        wakeTick++
                    }
                } else {
                    wakeTick++
                }
            },
            onDeviceOnly = true
        )
        try {
            awaitCancellation()
        } finally {
            if (vm.state != AiState.LISTENING) voice.cancelListening()
        }
    }

    val modeLabel = if (privateOn) "PRIVATE" else mode.label
    val hasReply = vm.messages.any { !it.fromUser && !it.isError }
    val last = if (hasReply) " · last: ${vm.source}" else ""
    val thinkingOnline = toolHost.isNotEmpty() || onlinePath
    val status = if (vm.state == AiState.THINKING) {
        if (toolHost.isNotEmpty()) "● ONLINE · contacting $toolHost"
        else if (onlinePath) "● ONLINE · contacting $host"
        else "THINKING · on-device"
    } else {
        "${vm.state.name} · $modeLabel$last"
    }
    val statusColor = when {
        vm.state == AiState.ERROR -> Red
        vm.state == AiState.THINKING && thinkingOnline -> Amber
        else -> Cyan
    }
    val micLabel = when (vm.state) {
        AiState.LISTENING -> "Stop"
        AiState.SPEAKING -> "Quiet"
        else -> "Mic"
    }
    val pad = PaddingValues(horizontal = 8.dp)
    Column(Modifier.fillMaxSize().padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            TextButton(onClick = openAi, contentPadding = pad) {
                Text("Mode: $modeLabel", color = if (privateOn) Mint else Cyan, fontSize = 13.sp)
            }
            TextButton(onClick = openSkills, contentPadding = pad) { Text("Skills", color = Cyan, fontSize = 13.sp) }
            TextButton(onClick = openPrivacy, contentPadding = pad) { Text("Privacy", color = Mint, fontSize = 13.sp) }
            Box {
                TextButton(onClick = { menuOpen = true }, contentPadding = pad) { Text("More", color = Dim, fontSize = 13.sp) }
                DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                    DropdownMenuItem(text = { Text("Reminders") }, onClick = {
                        menuOpen = false
                        openReminders()
                    })
                    DropdownMenuItem(text = { Text("Model") }, onClick = {
                        menuOpen = false
                        openModel()
                    })
                    DropdownMenuItem(text = { Text("Settings") }, onClick = {
                        menuOpen = false
                        openSettings()
                    })
                }
            }
        }
        Orb(vm.state)
        Text(name, fontSize = 30.sp, color = Color.White)
        Text(status, color = statusColor, fontSize = 13.sp)
        if (wakeOn) {
            Text("Wake word ON: say \"Hey $name\" (on-device, only while this app is open)", color = Mint, fontSize = 12.sp)
        }
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

// END OF FILE
