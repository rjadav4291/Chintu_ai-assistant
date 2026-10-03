package com.chintu.assistant

import android.content.Context
import android.content.pm.PackageManager
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

// One entry per skill: what it is, what it uses, what it needs, and what happens when it fails.
class SkillInfo(
    val id: String,
    val name: String,
    val description: String,
    val examples: String,
    val tools: String,
    val permissions: String,
    val fails: String,
    val internet: Boolean = false,
    val available: Boolean = true,
    val note: String = ""
)

object SkillCatalog {
    val all: List<SkillInfo> = listOf(
        SkillInfo(
            "calc", "Calculator", "Does arithmetic and percentages.",
            "5+3, calculate 12 x 4, 20% of 150",
            "Built-in calculator on the phone", "None",
            "Says it couldn't calculate (for example, division by zero)."
        ),
        SkillInfo(
            "time", "Time & Date", "Tells the current time and date from your phone's clock.",
            "what time is it, what's the date",
            "Your phone's clock", "None",
            "Cannot fail in a way that gives a wrong answer."
        ),
        SkillInfo(
            "timer", "Timer", "Starts a countdown timer.",
            "set a timer for 5 minutes",
            "Hands the timer to your phone's Clock app", "Timers (allowed when the app is installed)",
            "Cannot confirm the timer started, and says so. If no Clock app accepts it, it tells you."
        ),
        SkillInfo(
            "stopwatch", "Stopwatch", "A stopwatch that keeps counting even if you close the app.",
            "start stopwatch, stopwatch status, stop stopwatch, reset stopwatch",
            "Saved on this phone", "None",
            "Says if it couldn't start, stop or reset it."
        ),
        SkillInfo(
            "notes", "Notes", "Saves, lists and deletes short notes.",
            "note: buy milk, show my notes, delete note 1",
            "Notes saved on this phone", "None",
            "Says \"saved\" only after reading the note back. Asks before deleting."
        ),
        SkillInfo(
            "memory", "Memory", "Remembers things you ask it to remember.",
            "remember that I prefer Gujarati, what do you remember, forget memory 1",
            "Memories saved on this phone", "None. It also needs the Memory switch to be ON (Privacy / Settings).",
            "Says it didn't save when Memory is off. Never saves passwords, PINs or card numbers."
        ),
        SkillInfo(
            "apps", "App Launcher", "Opens YouTube, Chrome, Instagram, WhatsApp, Camera, Calculator, Maps and Settings, and the Wi-Fi, Bluetooth, sound, display and location settings pages.",
            "open youtube, open camera, open wifi settings",
            "Android's app-opening feature", "None",
            "Says if the app isn't installed. Cannot close apps or turn Wi-Fi or Bluetooth on or off, and says so."
        ),
        SkillInfo(
            "weather", "Weather", "Gets the current weather and today/tomorrow forecast for a city.",
            "weather in Ahmedabad, set my city to Surat, weather",
            "open-meteo.com (no key needed)", "Internet",
            "Says internet is required and that it got nothing. Never makes up a forecast.",
            internet = true
        ),
        SkillInfo(
            "wiki", "Wikipedia", "Reads the short summary of a Wikipedia page.",
            "wikipedia Mahatma Gandhi",
            "wikipedia.org (no key needed)", "Internet",
            "Says if nothing was found or the internet is missing.",
            internet = true
        ),
        SkillInfo(
            "search", "Web Search", "Searches the web and shows the top results with their sources.",
            "search best time to visit Goa",
            "Tavily with your own free key, or Wikipedia if there is no key", "Internet",
            "Says internet is required, or shows the service's error. Never invents results.",
            internet = true
        ),
        SkillInfo(
            "reminders", "Reminders", "One-time and repeating reminders.",
            "remind me at 7 PM",
            "Not built yet", "Not decided yet",
            "Not available yet.",
            available = false, note = "Planned for Phase 11."
        )
    )

    fun nameOf(id: String): String = all.firstOrNull { it.id == id }?.name ?: id
}

// Skill on/off switches, saved with the app's settings.
class SkillSettings(ctx: Context) {
    private val prefs = ctx.getSharedPreferences("chintu", Context.MODE_PRIVATE)

    fun enabled(id: String): Boolean = prefs.getBoolean("skill_on_$id", true)

    // True only if the change was saved and read back.
    fun set(id: String, on: Boolean): Boolean =
        prefs.edit().putBoolean("skill_on_$id", on).commit() && enabled(id) == on
}

// Extra instructions for the AI about what it must not claim.
fun skillsPrompt(off: List<String>): String {
    val base = "You do not know the current time or date, because you have no clock."
    return if (off.isEmpty()) base
    else base + " The user has turned these app skills off: " + off.joinToString(", ") +
        ". If asked to use one, say that skill is turned off and can be turned on in Skills, and do not do it yourself."
}

@Composable
fun SkillsScreen(back: () -> Unit) {
    val ctx = LocalContext.current
    val settings = remember { SkillSettings(ctx) }
    var rev by remember { mutableStateOf(0) }
    var msg by remember { mutableStateOf("") }
    val states = remember(rev) { SkillCatalog.all.associate { it.id to settings.enabled(it.id) } }
    val alarmOk = remember(rev) { ctx.checkSelfPermission("com.android.alarm.permission.SET_ALARM") == PackageManager.PERMISSION_GRANTED }
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        TextButton(onClick = back) { Text("Back") }
        Text("Skills", fontSize = 22.sp, color = Color.White)
        Text(
            "Turn a skill OFF and I won't run it. For Weather, Wikipedia and Web Search I tell you it is off. For the other skills your message goes to the AI instead, which cannot run them and has been told they are off.",
            color = Dim
        )
        if (msg.isNotEmpty()) Text(msg, color = Red)
        for (s in SkillCatalog.all) {
            Spacer(Modifier.height(6.dp))
            Text(s.name, fontSize = 18.sp, color = Color.White)
            if (s.available) {
                val on = states[s.id] ?: true
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Switch(checked = on, onCheckedChange = { want ->
                        msg = if (settings.set(s.id, want)) "" else "I couldn't change that setting."
                        rev++
                    })
                    Spacer(Modifier.width(12.dp))
                    Text(if (on) "ON" else "OFF", color = if (on) Cyan else Dim)
                }
            } else {
                Text("Not available yet. ${s.note}", color = Red)
            }
            Text(s.description, color = Dim)
            Text("Try: ${s.examples}", color = Dim)
            Text("Uses: ${s.tools}", color = Dim)
            val timerNote = if (s.id == "timer") (if (alarmOk) " (currently allowed)" else " (currently NOT allowed)") else ""
            Text("Needs: ${s.permissions}$timerNote", color = Dim)
            if (s.internet) Text("Needs internet. Never runs in OFFLINE or Private Mode.", color = Dim)
            Text("If it fails: ${s.fails}", color = Dim)
        }
        Spacer(Modifier.height(6.dp))
        Text(
            "Planned, not built yet: Alarm, Translation, Study Assistant, Fitness Assistant, Daily Routine.",
            color = Dim
        )
    }
}
