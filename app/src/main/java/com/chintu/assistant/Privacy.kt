package com.chintu.assistant

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import android.widget.Toast
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
import androidx.lifecycle.viewmodel.compose.viewModel
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import org.json.JSONArray

// Counts the online requests (AI and web tools) this app started since it was opened. Not saved anywhere.
object PrivacyLog {
    @Volatile var count = 0
    @Volatile var lastHost = ""
    @Volatile var lastTime = 0L

    fun record(host: String) {
        count++
        lastHost = host
        lastTime = System.currentTimeMillis()
    }

    fun summary(): String {
        if (count == 0) return "No online requests since the app was opened."
        val t = SimpleDateFormat("h:mm:ss a", Locale.ENGLISH).format(Date(lastTime))
        return "Online requests started since the app was opened (AI and web tools): $count. Last one: $t, sent to $lastHost."
    }
}

object DataControls {
    private fun p(ctx: Context, name: String) = ctx.getSharedPreferences(name, Context.MODE_PRIVATE)

    fun noteCount(ctx: Context): Int = try {
        JSONArray(p(ctx, "chintu_tools").getString("notes", "[]") ?: "[]").length()
    } catch (e: Exception) {
        0
    }

    // True only if the notes are really gone afterwards.
    fun deleteNotes(ctx: Context): Boolean {
        p(ctx, "chintu_tools").edit().remove("notes").commit()
        return noteCount(ctx) == 0
    }

    fun deleteAll(ctx: Context, ai: LiteRtLocalAi, vm: ChatVm): String {
        val problems = mutableListOf<String>()
        if (!ai.deleteModel()) problems.add("offline model")
        val modelDir = File(ctx.filesDir, "models")
        modelDir.deleteRecursively()
        if (modelDir.exists()) problems.add("model files")
        for (name in listOf("chintu_tools", "chintu_memory", "chintu_local_ai", "chintu")) {
            val pr = p(ctx, name)
            pr.edit().clear().commit()
            if (pr.all.isNotEmpty()) problems.add(name)
        }
        ctx.cacheDir.listFiles()?.forEach { it.deleteRecursively() }
        vm.messages.clear()
        vm.state = AiState.READY
        return if (problems.isEmpty()) "All local data deleted."
        else "I could not delete everything: " + problems.joinToString(", ") + "."
    }
}

@Composable
private fun Confirmable(label: String, warning: String, onYes: () -> Unit) {
    var ask by remember { mutableStateOf(false) }
    if (ask) {
        Text(warning, color = Red)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = {
                ask = false
                onYes()
            }) { Text("Yes, delete") }
            OutlinedButton(onClick = { ask = false }) { Text("Cancel") }
        }
    } else {
        OutlinedButton(onClick = { ask = true }) { Text(label) }
    }
}

@Composable
fun PrivacyScreen(
    privateOn: Boolean,
    onPrivate: (Boolean) -> Unit,
    mode: AiMode,
    host: String,
    keySaved: Boolean,
    ai: LiteRtLocalAi,
    onClearKey: () -> Unit,
    back: () -> Unit,
    vm: ChatVm = viewModel()
) {
    val ctx = LocalContext.current
    val memory = remember { MemoryStore(ctx) }
    var rev by remember { mutableStateOf(0) }
    var msg by remember { mutableStateOf("") }
    val toolPrefs = remember { ctx.getSharedPreferences("chintu_tools", Context.MODE_PRIVATE) }
    val notes = remember(rev) { DataControls.noteCount(ctx) }
    val memories = remember(rev) { memory.all().size }
    val city = remember(rev) { toolPrefs.getString("weather_city", "") ?: "" }
    val searchKey = remember(rev) { KeyVault.load(toolPrefs, "tavily_key").isNotEmpty() }
    val micOk = remember(rev) { ctx.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED }
    val alarmOk = remember(rev) { ctx.checkSelfPermission("com.android.alarm.permission.SET_ALARM") == PackageManager.PERMISSION_GRANTED }
    val modelFile = remember(rev) { ai.modelFile() }

    val goesOnline = if (privateOn) {
        "Private Mode is ON: nothing is sent to any online AI, and weather, Wikipedia and web search are not used."
    } else {
        when (mode) {
            AiMode.OFFLINE -> "OFFLINE mode: nothing is sent to any online AI, and weather, Wikipedia and web search are not used."
            AiMode.AUTO, AiMode.ONLINE ->
                "When I use the online AI ($host), these are sent: your message, the recent conversation and my instructions. Saved memories are included only while Memory is ON. Your API key is sent only as your login to that service. Weather, Wikipedia and web search send only the city or words you asked about. Calculator, time, date, stopwatch, notes, memory commands and opening apps are handled on the phone and are not sent."
        }
    }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        TextButton(onClick = back) { Text("Back") }
        Text("Privacy & Security", fontSize = 22.sp, color = Color.White)

        Text("Private Mode", fontSize = 18.sp, color = Color.White)
        Row(verticalAlignment = Alignment.CenterVertically) {
            Switch(checked = privateOn, onCheckedChange = { onPrivate(it) })
            Spacer(Modifier.width(12.dp))
            Text(if (privateOn) "Private Mode ON" else "Private Mode OFF", color = Color.White)
        }
        Text(
            "While ON, AI answers come only from the on-device model and the AI mode setting is ignored. If the on-device model isn't ready, I tell you instead of going online. Listening to your voice also uses only on-device speech recognition; if this phone can't do that, I won't listen.",
            color = Dim
        )

        Text("Online use", fontSize = 18.sp, color = Color.White)
        Text(PrivacyLog.summary(), color = Dim)
        Text(goesOnline, color = Dim)

        OnlineToolsSection()

        Text("Permissions", fontSize = 18.sp, color = Color.White)
        Text("Microphone: " + if (micOk) "allowed" else "not allowed", color = if (micOk) Cyan else Dim)
        Text("Used only while you tap Mic, to listen to you. I ask only when you first tap Mic.", color = Dim)
        Text("Internet: allowed (Android doesn't ask for this one).", color = Cyan)
        Text("Used only to contact the online AI and the online tools when the mode allows it.", color = Dim)
        Text("Timers: " + if (alarmOk) "allowed" else "not allowed", color = if (alarmOk) Cyan else Dim)
        Text("Used only to send timers to your Clock app.", color = Dim)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = {
                val i = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + ctx.packageName))
                try {
                    ctx.startActivity(i)
                } catch (e: Exception) {
                    msg = "I couldn't open the phone's app settings."
                }
            }) { Text("Phone app settings") }
            OutlinedButton(onClick = { rev++ }) { Text("Refresh") }
        }

        Text("Stored on this phone", fontSize = 18.sp, color = Color.White)
        Text("Notes: $notes", color = Dim)
        Text("Memories: $memories (Memory is ${if (memory.enabled) "ON" else "OFF"})", color = Dim)
        Text("AI API key: " + if (keySaved) "saved" else "not set", color = Dim)
        Text("Web search key: " + if (searchKey) "saved" else "not set", color = Dim)
        Text("Default city: " + if (city.isEmpty()) "none" else city, color = Dim)
        Text(
            "Offline model: " + if (modelFile == null) "none" else "${modelFile.name} (${modelFile.length() / (1024 * 1024)} MB)",
            color = Dim
        )
        Text("Chat history: kept only in memory while the app is open (${vm.messages.size} messages now). It is never saved to storage.", color = Dim)
        Text(
            "API keys are stored encrypted. Notes, memories, the city and settings are in the app's private storage and are not encrypted. Phone backup of this app's data is turned off. Uninstalling the app deletes it all.",
            color = Dim
        )

        Text("Delete data", fontSize = 18.sp, color = Color.White)
        Confirmable("Clear this chat", "Clear the conversation on screen?") {
            vm.messages.clear()
            vm.state = AiState.READY
            msg = "Chat cleared."
            rev++
        }
        Confirmable("Delete all notes", "Delete all $notes notes?") {
            msg = if (DataControls.deleteNotes(ctx)) "All notes deleted." else "I couldn't delete the notes."
            rev++
        }
        Confirmable("Delete all memories", "Delete all $memories memories?") {
            msg = if (memory.clearAll()) "All memories deleted." else "I couldn't delete the memories."
            rev++
        }
        Confirmable("Remove AI API key", "Remove the saved AI API key?") {
            onClearKey()
            val gone = ctx.getSharedPreferences("chintu", Context.MODE_PRIVATE).getString("openai_key", null) == null
            msg = if (gone) "API key removed." else "I couldn't remove the key."
            rev++
        }
        Confirmable("Delete offline model", "Delete the offline model file? You would need to import it again.") {
            msg = if (ai.deleteModel()) "Offline model deleted." else "I couldn't delete the model."
            rev++
        }
        Confirmable(
            "Delete ALL local data",
            "Delete everything: notes, memories, keys, settings, the offline model and this chat? The app will restart."
        ) {
            val r = DataControls.deleteAll(ctx, ai, vm)
            Toast.makeText(ctx, r, Toast.LENGTH_LONG).show()
            (ctx as? Activity)?.recreate()
        }
        if (msg.isNotEmpty()) Text(msg, color = Dim)
    }
}
