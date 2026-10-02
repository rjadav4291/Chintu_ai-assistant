package com.chintu.assistant

import android.os.Handler
import android.os.Looper
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.io.IOException

enum class AiMode(val label: String) { AUTO("AUTO"), OFFLINE("OFFLINE"), ONLINE("ONLINE") }

// An error whose message is already final and honest, to be shown to the user as is.
class RouteException(message: String) : Exception(message)

// Anything that can answer on the phone itself. Phase 7B plugs a real model in here.
interface LocalAi {
    val available: Boolean
    fun status(): String
    fun generate(system: String, turns: List<Pair<String, String>>): String
}

object NoLocalAi : LocalAi {
    override val available: Boolean = false
    override fun status(): String = "no local AI model is set up yet."
    override fun generate(system: String, turns: List<Pair<String, String>>): String =
        throw RouteException("Offline AI is unavailable: no local AI model is set up yet.")
}

private fun turnsOf(messages: List<Msg>): List<Pair<String, String>> {
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

private fun localReply(local: LocalAi, sys: String, turns: List<Pair<String, String>>): Pair<String, String> {
    if (!local.available) throw RouteException("Offline AI is unavailable: ${local.status()}")
    return local.generate(sys, turns) to "OFFLINE AI"
}

// Returns (reply, which AI actually answered). OFFLINE never touches the network.
private fun runAi(
    mode: AiMode,
    local: LocalAi,
    baseUrl: String,
    apiKey: String,
    model: String,
    sys: String,
    turns: List<Pair<String, String>>,
    onlineReady: Boolean
): Pair<String, String> {
    if (mode == AiMode.OFFLINE) return localReply(local, sys, turns)
    if (mode == AiMode.AUTO && !onlineReady) return localReply(local, sys, turns)
    try {
        return callAi(baseUrl, apiKey, model, sys, turns) to "ONLINE AI"
    } catch (e: IOException) {
        if (mode == AiMode.AUTO && local.available) return localReply(local, sys, turns)
        val why = "${e.javaClass.simpleName}: ${e.message}"
        if (mode == AiMode.ONLINE) {
            throw RouteException("Internet is required for this task. I couldn't connect to the AI service.\nDetails: $why")
        }
        throw RouteException("I couldn't connect to the AI service, and no offline AI is available.\nDetails: $why")
    }
}

fun ChatVm.sendRouted(
    text: String,
    mode: AiMode,
    local: LocalAi,
    baseUrl: String,
    apiKey: String,
    model: String,
    name: String,
    extra: String,
    onReply: (String) -> Unit
) {
    val t = text.trim()
    if (t.isEmpty() || state == AiState.THINKING) return
    messages.add(Msg(true, t))
    val onlineReady = apiKey.isNotBlank() && model.isNotBlank()
    if (mode == AiMode.OFFLINE && !local.available) {
        notice("Offline AI is unavailable: ${local.status()} I did not contact any online service.", true)
        return
    }
    if (mode == AiMode.ONLINE && !onlineReady) {
        notice("Online AI isn't set up: add your API key and model in Settings.", true)
        return
    }
    if (mode == AiMode.AUTO && !onlineReady && !local.available) {
        notice("No AI is available: online AI isn't set up (API key or model is missing) and there is no offline AI.", true)
        return
    }
    state = AiState.THINKING
    val turns = turnsOf(messages)
    val sys = systemPrompt(name, extra)
    Thread {
        var reply: Pair<String, String>? = null
        var err: String? = null
        try {
            reply = runAi(mode, local, baseUrl, apiKey, model, sys, turns, onlineReady)
        } catch (e: RouteException) {
            err = e.message
        } catch (e: Exception) {
            err = describe(e)
        }
        val r = reply
        val er = err
        Handler(Looper.getMainLooper()).post {
            if (r != null) {
                messages.add(Msg(false, r.first))
                source = r.second
                state = AiState.READY
                onReply(r.first)
            } else {
                notice(er ?: "Unknown error", true)
            }
        }
    }.start()
}

@Composable
fun AiScreen(
    mode: AiMode,
    onMode: (AiMode) -> Unit,
    local: LocalAi,
    host: String,
    keySaved: Boolean,
    back: () -> Unit
) {
    val about = when (mode) {
        AiMode.AUTO -> "AUTO: I use the online AI when I can reach it. If I can't connect, I use the offline AI if one is set up, otherwise I tell you what failed."
        AiMode.OFFLINE -> "OFFLINE: I never contact an online AI. Only the offline AI and the built-in tools are used."
        AiMode.ONLINE -> "ONLINE: I only use the online AI. If there is no internet, I tell you that internet is required."
    }
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        TextButton(onClick = back) { Text("Back") }
        Text("AI mode", fontSize = 22.sp, color = Color.White)
        for (m in AiMode.values()) {
            if (m == mode) {
                Button(onClick = { onMode(m) }, modifier = Modifier.fillMaxWidth()) { Text(m.label + " (selected)") }
            } else {
                OutlinedButton(onClick = { onMode(m) }, modifier = Modifier.fillMaxWidth()) { Text(m.label) }
            }
        }
        Text(about, color = Dim)
        Text("Online AI: $host, " + if (keySaved) "API key saved" else "API key not set", color = if (keySaved) Cyan else Red)
        Text(
            "Offline AI: " + if (local.available) "ready" else "unavailable, ${local.status()}",
            color = if (local.available) Cyan else Red
        )
        Text(
            "Always work without internet, in every mode: calculator, time, date, stopwatch, notes, memory and opening apps.",
            color = Dim
        )
        Text(
            "Note: listening to your voice is done by your phone's speech service, which may use the internet in any mode. Private Mode comes in a later phase.",
            color = Dim
        )
    }
}
