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
    openVision: () -> Unit,
    vm: ChatVm = viewModel()
) {
    var input by remember { mutableStateOf("") }
    var toolHost by remember { mutableStateOf("") }
    var menuOpen by remember { mutableStateOf(false) }
    var agentStatus by remember { mutableStateOf("") }
    var agentOnline by remember { mutableStateOf(false) }
    val ctx = LocalContext.current
    val tools = remember { Tools(ctx) }
    val memory = remember { MemoryStore(ctx) }
    val launcher = remember { AppLauncher(ctx) }
    val online = remember { OnlineTools(ctx) }
    val skills = remember { SkillSettings(ctx) }
    val reminders = remember { ReminderSkill(ctx) }
    val agent = remember { Agent() }
    val runner = remember { AgentRunner(ctx, reminders, memory, tools, launcher, online, skills) }
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

    // Runs an approved plan on a background thread and shows the checked result at the end.
    fun runPlan(plan: AgentPlan) {
        vm.state = AiState.THINKING
        agentStatus = "AGENT · starting"
        val toolsOk = effectiveMode != AiMode.OFFLINE
        Thread {
            val res = runner.run(plan, toolsOk) { i, n, s ->
                Handler(Looper.getMainLooper()).post {
                    agentOnline = s.skill == "weather" || s.skill == "wiki" || s.skill == "search"
                    agentStatus = "AGENT · step $i of $n: ${s.command.take(40)}"
                }
            }
            Handler(Looper.getMainLooper()).post {
                agentStatus = ""
                agentOnline = false
                vm.messages.add(Msg(false, res.text))
                vm.source = "AGENT"
                vm.state = AiState.READY
                speakIfOn(res.spoken)
            }
        }.start()
    }

    // The online AI only makes the plan. It never sees the results and never runs anything itself.
    fun planWithAi(goal: String) {
        vm.state = AiState.THINKING
        toolHost = host
        PrivacyLog.record(host)
        val toolsOk = effectiveMode != AiMode.OFFLINE
        Thread {
            var plan: AgentPlan? = null
            var err: String? = null
            try {
                plan = AgentPlanner.planWithAi(baseUrl, apiKey, model, goal, toolsOk) { id -> skills.enabled(id) }
            } catch (e: PlanException) {
                err = e.message
            } catch (e: Exception) {
                err = describe(e)
            }
            val p = plan
            val er = err
            Handler(Looper.getMainLooper()).post {
                toolHost = ""
                if (p != null) {
                    agent.pending = p
                    vm.messages.add(Msg(false, AgentRules.describe(p)))
                    vm.source = "AGENT PLAN"
                    vm.state = AiState.READY
                    speakIfOn("I made a plan with ${p.steps.size} steps. Say yes to run it.")
                } else {
                    vm.notice(er ?: "Unknown error", true)
                }
            }
        }.start()
    }

    fun doSend(text: String) {
        if (text.isBlank()) return
        if (vm.state == AiState.SPEAKING) voice.stopSpeaking()
        if (vm.state == AiState.THINKING) return
        // Several commands in one message (or a plan waiting for yes/no) go to the agent first.
        val act = try {
            agent.handle(text, effectiveMode != AiMode.OFFLINE, onlinePath) { id -> skills.enabled(id) }
        } catch (e: Exception) {
            null
        }
        if (act != null) {
            val reply = act.reply
            val run = act.run
            val goal = act.aiGoal
            if (reply != null) {
                vm.addLocal(text.trim(), reply)
                speakIfOn(reply.substringBefore("\n"))
            } else {
                vm.messages.add(Msg(true, text.trim()))
                if (run != null) runPlan(run) else if (goal != null) planWithAi(goal)
            }
            return
        }
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

    WakeEffect(
        wakeOn = wakeOn,
        voice = voice,
        vm = vm,
        name = name,
        onWakeOff = onWakeOff,
        onCommand = { doSend(it) },
        onWakeOnly = { startMic() }
    )

    val modeLabel = if (privateOn) "PRIVATE" else mode.label
    val hasReply = vm.messages.any { !it.fromUser && !it.isError }
    val last = if (hasReply) " · last: ${vm.source}" else ""
    val thinkingOnline = toolHost.isNotEmpty() || onlinePath || agentOnline
    val status = if (vm.state == AiState.THINKING) {
        if (agentStatus.isNotEmpty()) (if (agentOnline) "● ONLINE · " else "") + agentStatus
        else if (toolHost.isNotEmpty()) "● ONLINE · contacting $toolHost"
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
                    DropdownMenuItem(text = { Text("Vision") }, onClick = {
                        menuOpen = false
                        openVision()
                    })
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
