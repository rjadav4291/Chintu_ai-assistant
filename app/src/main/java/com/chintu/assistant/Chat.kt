package com.chintu.assistant

import android.os.Handler
import android.os.Looper
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import org.json.JSONArray
import org.json.JSONObject

const val DEFAULT_BASE_URL = "https://api.experientiallabs.ai/v1"

enum class AiState { READY, LISTENING, THINKING, SPEAKING, OFFLINE, ERROR }
data class Msg(val fromUser: Boolean, val text: String, val isError: Boolean = false)
class AiException(message: String) : Exception(message)

fun systemPrompt(name: String) = """
You are $name, a personal assistant inside an Android app.
Personality: friendly, calm, helpful, natural, slightly futuristic. Keep answers short, in short natural sentences. Explain step by step only when the question is complicated.
Reply in the language the user writes in (Gujarati, Hindi, English or Hinglish).
Your replies may be spoken aloud, so do not use emojis, markdown, bullet symbols or long lists.
Truth rules: the app itself handles math, time, date, timers, stopwatch and notes before a message reaches you. You cannot do those yourself. If the user asks for one of them and you are seeing the message, say honestly that you did not do it and suggest a clearer phrase, like "set a timer for 5 minutes" or "note: buy milk".
You have NO other tools yet. You cannot open apps, save memories, set reminders or alarms, search the web, check weather, or control the phone. Never claim you did any of these. If asked, say honestly that it is not available yet.
Do not imitate any fictional character.
""".trimIndent()

fun hostOf(baseUrl: String): String =
    try { URL(baseUrl).host } catch (e: Exception) { "online AI" }

// Follows redirects only to the SAME host over https, so the key never goes to another server.
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
    val body = JSONObject().put("model", model).put("max_completion_tokens", 2000).put("messages", msgs)
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

fun describe(e: Exception): String = when (e) {
    is AiException -> "The AI service returned an error: ${e.message}"
    is IOException -> "I couldn't connect to the AI service. No offline AI is available yet.\nDetails: ${e.javaClass.simpleName}: ${e.message}"
    else -> "Something went wrong: ${e.javaClass.simpleName}: ${e.message}"
}

class ChatVm : ViewModel() {
    var state by mutableStateOf(AiState.READY)
    var source by mutableStateOf("ONLINE AI")
    val messages = mutableStateListOf<Msg>()

    private fun fail(text: String) {
        messages.add(Msg(false, text, true))
        state = AiState.ERROR
    }

    fun notice(text: String, serious: Boolean) {
        messages.add(Msg(false, text, true))
        state = if (serious) AiState.ERROR else AiState.READY
    }

    // A reply produced by a local tool. No online AI was contacted.
    fun addLocal(user: String, reply: String) {
        messages.add(Msg(true, user))
        messages.add(Msg(false, reply))
        source = "LOCAL"
        state = AiState.READY
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
        source = "ONLINE AI"
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
