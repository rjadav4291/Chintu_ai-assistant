package com.chintu.assistant

import android.content.Context
import android.content.SharedPreferences
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

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
                var wakeOn by remember { mutableStateOf(prefs.getBoolean("wake_on", false)) }
                var showSettings by remember { mutableStateOf(false) }
                var showAi by remember { mutableStateOf(false) }
                var showModel by remember { mutableStateOf(false) }
                var showPrivacy by remember { mutableStateOf(false) }
                var showSkills by remember { mutableStateOf(false) }
                var showReminders by remember { mutableStateOf(false) }
                var showVision by remember { mutableStateOf(false) }
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
                            wakeOn = wakeOn,
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
                            onWake = { on ->
                                if (prefs.edit().putBoolean("wake_on", on).commit()) wakeOn = on
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
                    } else if (showVision) {
                        VisionScreen(
                            baseUrl = baseUrl,
                            apiKey = apiKey,
                            model = model,
                            mode = mode,
                            privateOn = privateOn,
                            back = { showVision = false }
                        )
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
                            wakeOn = wakeOn,
                            local = liteRt,
                            onWakeOff = {
                                if (prefs.edit().putBoolean("wake_on", false).commit()) wakeOn = false
                            },
                            openSettings = { showSettings = true },
                            openAi = { showAi = true },
                            openModel = { showModel = true },
                            openPrivacy = { showPrivacy = true },
                            openSkills = { showSkills = true },
                            openReminders = { showReminders = true },
                            openVision = { showVision = true }
                        )
                    }
                }
            }
        }
    }

    override fun onStart() {
        super.onStart()
        AppFlags.foreground = true
    }

    override fun onStop() {
        AppFlags.foreground = false
        voice.cancelListening()
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

// END OF FILE
