package com.chintu.assistant

import android.Manifest
import android.content.Context
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.os.Build
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
val Amber = Color(0xFFFFB84D)
val Mint = Color(0xFF6DFFB0)

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

private fun loadMode(p: SharedPreferences): AiMode =
    try { AiMode.valueOf(p.getString("ai_mode", "AUTO") ?: "AUTO") } catch (e: Exception) { AiMode.AUTO }

class MainActivity : ComponentActivity() {
    private lateinit var voice: VoiceManager
    private val liteRt by lazy { LiteRtLocalAi(applicationContext) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        voice = VoiceManager(applicationContext)
        // Put saved reminders back with Android (alarms are lost after a restart or a force-stop).
        try {
            Reminders.rescheduleAll(applicationContext)
        } catch (e: Exception) {
        }
        val prefs = getSharedPreferences("chintu", Context.MODE_PRIVATE)
        setContent {
            MaterialTheme(colorScheme = darkColorScheme(background = Ink, surface = Ink, primary = Cyan)) {
                var name by remember { mutableStateOf(prefs.getString("name", "Chintu") ?: "Chintu") }
                var apiKey by remember { mutableStateOf(KeyVault.load(prefs)) }
                var model by remember { mutableStateOf(prefs.getString("openai_model", "") ?: "") }
                var baseUrl by remember { mutableStateOf(prefs.getString("base_url", DEFAULT_BASE_URL) ?: DEFAULT_BASE_URL) }
                var cfg by remember { mutableStateOf(loadCfg(prefs)) }
                var mode by remember { mutableStateOf(loadMode(prefs)) }
                var privateOn by remember { mutableStateOf(prefs.getBoolean("private_mode", false)) }
                var showSettings by remember { mutableStateOf(false) }
                var showAi by remember { mutableStateOf(false) }
                var showModel by remember { mutableStateOf(false) }
                var showPrivacy by remember { mutableStateOf(false) }
                var showSkills by remember { mutableStateOf(false) }
                var showReminders by remember { mutableStateOf(false) }
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
                                if (KeyVault.save(prefs, k)) apiKey = k.trim()
                            },
                            onClearKey = {
                                KeyVault.clear(prefs)
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
                    } else if (showAi) {
                        AiScreen(
                            mode = mode,
                            onMode = { m ->
                                if (prefs.edit().putString("ai_mode", m.name).commit()) mode = m
                            },
                            local = liteRt,
                            host = hostOf(baseUrl),
                            keySaved = apiKey.isNotBlank(),
                            back = { showAi = false }
                        )
                    } else if (showModel) {
                        ModelScreen(ai = liteRt, back = { showModel = false })
                    } else if (showSkills) {
                        SkillsScreen(back = { showSkills = false })
                    } else if (showReminders) {
                        RemindersScreen(back = { showReminders = false })
                    } else if (showPrivacy) {
                        PrivacyScreen(
                            privateOn = privateOn,
                            onPrivate = { on ->
                                if (prefs.edit().putBoolean("private_mode", on).commit()) privateOn = on
                            },
                            mode = mode,
                            host = hostOf(baseUrl),
                            keySaved = apiKey.isNotBlank(),
                            ai = liteRt,
                            onClearKey = {
                                KeyVault.clear(prefs)
                                apiKey = ""
                            },
                            back = { showPrivacy = false }
                        )
                    } else {
                        MainScreen(
                            name = name,
                            baseUrl = baseUrl,
                            apiKey = apiKey,
                            model = model,
                            voice = voice,
                            cfg = cfg,
                            mode = mode,
                            privateOn = privateOn,
                            local = liteRt,
                            openSettings = { showSettings = true },
                            openAi = { showAi = true },
                            openModel = { showModel = true },
                            openPrivacy = { showPrivacy = true },
                            openSkills = { showSkills = true },
                            openReminders = { showReminders = true }
                        )
                    }
                }
            }
        }
    }

    override fun onStop() {
        liteRt.releaseAsync()
        super.onStop()
    }

    override fun onDestroy() {
        voice.release()
        liteRt.releaseAsync()
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
    mode: AiMode,
    privateOn: Boolean,
    local: LocalAi,
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
        Spacer(Modifier.height(12.dp))
        LazyColumn(Modifier.weight(1f).fillMaxWidth(), state = listSt
