package com.chintu.assistant

import android.content.Context
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.util.Locale
import org.json.JSONArray

// Memory the user explicitly asks for. Stored only on this phone.
// Nothing is saved unless Memory is ON, and "saved" is only reported after reading it back.
class MemoryStore(ctx: Context) {
    private val prefs = ctx.getSharedPreferences("chintu_memory", Context.MODE_PRIVATE)
    private var pending: (() -> String)? = null
    private val yes = setOf("yes", "y", "ok", "okay", "confirm", "sure", "haan", "ha", "han", "હા", "हाँ", "हां")
    private val no = setOf("no", "n", "cancel", "nahi", "na", "ના", "नहीं")
    private val maxItems = 100
    private val maxLen = 300

    val enabled: Boolean
        get() = prefs.getBoolean("enabled", false)

    fun setEnabled(on: Boolean): Boolean =
        prefs.edit().putBoolean("enabled", on).commit() && enabled == on

    fun all(): List<String> {
        val out = mutableListOf<String>()
        try {
            val arr = JSONArray(prefs.getString("items", "[]") ?: "[]")
            for (i in 0 until arr.length()) out.add(arr.getString(i))
        } catch (e: Exception) {
        }
        return out
    }

    private fun write(list: List<String>): Boolean {
        val arr = JSONArray()
        for (m in list) arr.put(m)
        return prefs.edit().putString("items", arr.toString()).commit() && all() == list
    }

    fun clearAll(): Boolean = write(emptyList())

    // Text added to the AI's instructions. Empty when memory is off or nothing is saved.
    fun promptSection(): String {
        if (!enabled) return ""
        val list = all()
        if (list.isEmpty()) return ""
        return "Things the user asked you to remember (use them naturally, mention them only if asked):\n" +
            list.mapIndexed { i, m -> "${i + 1}. $m" }.joinToString("\n")
    }

    fun handle(raw: String): String? {
        val text = raw.trim()
        val low = text.lowercase(Locale.ROOT)
        val act = pending
        if (act != null) {
            pending = null
            val word = low.trim('.', '!', '?', ' ')
            if (word in yes) return act()
            if (word in no) return "Okay, I cancelled that."
        }
        return switchCmd(low) ?: listCmd(low) ?: forgetCmd(text, low) ?: changeCmd(text, low) ?: saveCmd(text, low)
    }

    // ---------- memory on / off ----------
    private val onPhrases = listOf(
        "turn memory on", "turn on memory", "turn on the memory", "memory on", "enable memory",
        "switch memory on", "start remembering", "memory chalu", "મેમરી ચાલુ", "मेमोरी चालू"
    )
    private val offPhrases = listOf(
        "turn memory off", "turn off memory", "turn off the memory", "memory off", "disable memory",
        "switch memory off", "stop remembering", "memory band", "મેમરી બંધ", "मेमोरी बंद"
    )

    private fun switchCmd(low: String): String? {
        if (low.split(" ").size > 6) return null
        val on = onPhrases.any { low.contains(it) }
        val off = offPhrases.any { low.contains(it) }
        if (on == off) return null
        return if (on) {
            if (setEnabled(true)) "Memory is now ON. I'll save things only when you ask me to remember them. Saved memories are sent with your chat messages to the online AI so it can use them."
            else "I couldn't turn memory on."
        } else {
            if (setEnabled(false)) "Memory is now OFF. I won't save anything new or use saved memories in chat. Existing memories stay on this phone until you delete them."
            else "I couldn't turn memory off."
        }
    }

    // ---------- show memories ----------
    private val listPhrases = listOf(
        "what do you remember", "what do you know about me", "what have you remembered",
        "show my memories", "show memories", "show memory", "my memories", "list memories",
        "list my memories", "view memories", "what's in your memory", "whats in your memory",
        "tumhe kya yaad hai", "tujhe kya yaad hai", "तुम्हें क्या याद है", "तुझे क्या याद है",
        "मेरे बारे में क्या जानते हो", "તને શું યાદ છે", "તને મારા વિશે શું યાદ છે",
        "તું મારા વિશે શું જાણે છે", "મારી યાદો"
    )

    private fun listCmd(low: String): String? {
        if (low.split(" ").size > 10) return null
        if (listPhrases.none { low.contains(it) }) return null
        val list = all()
        if (list.isEmpty()) {
            return "I don't have anything saved about you." + (if (enabled) "" else " Memory is currently off.")
        }
        val lines = list.mapIndexed { i, m -> "${i + 1}. $m" }.joinToString("\n")
        return "Here's what I've saved:\n" + lines + (if (enabled) "" else "\n(Memory is off, so I'm not using these in chat.)")
    }

    // ---------- forget ----------
    private val delAllPhrases = setOf(
        "forget everything", "forget all", "forget it all", "forget everything about me",
        "forget all about me", "clear memory", "clear my memory", "clear all memories",
        "delete all memories", "delete all my memories", "delete memory", "erase my memory",
        "erase all memories", "wipe memory", "બધું ભૂલી જા", "બધી મેમરી ડિલીટ કર",
        "सब भूल जा", "सब कुछ भूल जा", "सारी मेमोरी हटा"
    )
    private val forgetNumber = Regex("""^(?:forget\s+(?:memory\s+|number\s+|item\s+)?|(?:delete|remove)\s+(?:memory|memories)\s+(?:number\s+)?)#?(\d+)$""")
    private val forgetSuffixes = listOf("ભૂલી જાઓ", "ભૂલી જા", "भूल जाओ", "भूल जा", "bhool ja", "bhul ja")

    private fun forgetIndex(n: Int): String {
        val list = all()
        if (n < 1 || n > list.size) return "I don't have a memory number $n."
        val target = list[n - 1]
        pending = {
            val cur = all().toMutableList()
            if (!cur.remove(target)) "That memory is no longer there."
            else if (write(cur)) "Forgot: $target"
            else "I couldn't delete that memory."
        }
        return "Forget \"${target.take(80)}\"? Say yes to confirm."
    }

    private fun forgetCmd(text: String, low: String): String? {
        val w = low.trim('.', '!', '?', ' ')
        if (w in delAllPhrases) {
            val n = all().size
            if (n == 0) return "I have no memories to delete."
            pending = { if (clearAll()) "All memories deleted." else "I couldn't delete the memories." }
            return "Delete all $n memories? Say yes to confirm."
        }
        val num = forgetNumber.matchEntire(w)
        if (num != null) return forgetIndex(num.groupValues[1].toIntOrNull() ?: 0)

        var q: String? = null
        if (low.startsWith("forget") && !(low.getOrNull(6)?.isLetter() ?: false)) {
            q = low.drop(6)
        } else {
            val s = forgetSuffixes.firstOrNull { low.contains(it) }
            if (s != null) q = low.replace(s, " ")
        }
        val first = q ?: return null
        var query = first.trim(' ', ':', ',', '.', '-')
        for (p in listOf("that ", "this ", "about ", "કે ", "कि ")) {
            if (query.startsWith(p)) {
                query = query.drop(p.length)
                break
            }
        }
        query = query.trim()
        if (query.length < 3) return "What should I forget? Say \"show my memories\", then \"forget memory 2\"."
        val list = all()
        val hits = list.indices.filter {
            val m = list[it].lowercase(Locale.ROOT)
            m.contains(query) || query.contains(m)
        }
        if (hits.isEmpty()) return "I don't have a memory matching that."
        if (hits.size > 1) {
            return "Several memories match:\n" + hits.joinToString("\n") { "${it + 1}. ${list[it]}" } +
                "\nSay \"forget memory N\" with the number you mean."
        }
        return forgetIndex(hits[0] + 1)
    }

    // ---------- change ----------
    private val changeRegex = Regex(
        """^(?:please\s+)?(?:change|update|edit)\s+memory\s+(?:number\s+)?#?(\d+)\s*(?:to|as|:|-)\s*(.+)$""",
        RegexOption.IGNORE_CASE
    )

    private fun changeCmd(text: String, low: String): String? {
        val m = changeRegex.matchEntire(text.trim())
        if (m != null) {
            if (!enabled) return "Memory is off. Say \"turn memory on\" first."
            val n = m.groupValues[1].toIntOrNull() ?: 0
            val newText = m.groupValues[2].trim()
            val list = all().toMutableList()
            if (n < 1 || n > list.size) return "I don't have a memory number $n."
            if (looksSensitive(newText)) return sensitiveMsg
            if (newText.length > maxLen) return "That's too long to save as a memory (maximum $maxLen characters)."
            list[n - 1] = newText
            return if (write(list)) "Updated memory $n: $newText" else "I couldn't update that memory."
        }
        val vague = low.startsWith("change my") || low.startsWith("update my") || low.startsWith("edit my")
        if (vague && (low.contains("preference") || low.contains("memory") || low.contains("memories"))) {
            return "Which memory should I change? Say \"show my memories\", then \"change memory 2 to ...\"."
        }
        return null
    }

    // ---------- save ----------
    private val savePrefixes = listOf(
        "please remember that", "please remember this", "please remember", "remember that",
        "remember this", "remember:", "remember", "yaad rakhna", "yaad rakh",
        "યાદ રાખ કે", "યાદ રાખજે", "યાદ રાખો", "યાદ રાખ", "याद रखना", "याद रखो", "याद रख"
    ).sortedByDescending { it.length }
    private val sensitiveRegex = Regex(
        """\b(password|passcode|otp|cvv|pin|aadhaar|aadhar)\b|card number|bank account|પાસવર્ડ|पासवर्ड""",
        RegexOption.IGNORE_CASE
    )
    private val longDigits = Regex("""\d[\d\s-]{9,}\d""")
    private val sensitiveMsg = "That looks like sensitive information (a password, PIN, OTP or card number), so I didn't save it. I don't store those."

    private fun looksSensitive(s: String): Boolean = sensitiveRegex.containsMatchIn(s) || longDigits.containsMatchIn(s)

    private fun saveCmd(text: String, low: String): String? {
        for (p in savePrefixes) {
            if (!low.startsWith(p)) continue
            val next = low.getOrNull(p.length)
            if (next != null && next.isLetter()) continue
            var content = text.drop(p.length).trimStart(' ', ':', ',', '-', '–', '.').trim()
            for (lead in listOf("that ", "કે ", "कि ")) {
                if (content.lowercase(Locale.ROOT).startsWith(lead)) {
                    content = content.drop(lead.length).trim()
                    break
                }
            }
            if (!enabled) return "Memory is turned off, so I didn't save that. Say \"turn memory on\", then tell me again."
            if (content.isEmpty()) return "What should I remember?"
            if (content.lowercase(Locale.ROOT).startsWith("to ")) {
                return "That sounds like a reminder. Reminders aren't available yet, so I haven't set anything. To save it as a memory instead, say \"remember that ...\"."
            }
            if (looksSensitive(content)) return sensitiveMsg
            if (content.length > maxLen) return "That's too long to save as a memory (maximum $maxLen characters)."
            val list = all().toMutableList()
            if (list.any { it.equals(content, ignoreCase = true) }) return "I already remember that."
            if (list.size >= maxItems) return "My memory is full ($maxItems items). Please delete some first."
            list.add(content)
            return if (write(list)) "Okay, I'll remember: $content" else "I couldn't save that memory."
        }
        return null
    }
}

@Composable
fun MemorySection() {
    val ctx = LocalContext.current
    val store = remember { MemoryStore(ctx) }
    var on by remember { mutableStateOf(store.enabled) }
    var items by remember { mutableStateOf(store.all()) }
    var msg by remember { mutableStateOf("") }
    var confirmClear by remember { mutableStateOf(false) }
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Memory", fontSize = 22.sp, color = Color.White)
        Row(verticalAlignment = Alignment.CenterVertically) {
            Switch(
                checked = on,
                onCheckedChange = { want ->
                    if (store.setEnabled(want)) {
                        on = want
                        msg = ""
                    } else {
                        msg = "I couldn't change the memory setting."
                    }
                }
            )
            Spacer(Modifier.width(12.dp))
            Text(if (on) "Memory ON" else "Memory OFF", color = Color.White)
        }
        Text("Saved memories: ${items.size}", color = Dim)
        items.forEachIndexed { i, m -> Text("${i + 1}. $m", color = Color.White) }
        if (items.isNotEmpty()) {
            if (confirmClear) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = {
                        msg = if (store.clearAll()) "All memories deleted." else "I couldn't delete the memories."
                        items = store.all()
                        confirmClear = false
                    }) { Text("Yes, delete all") }
                    OutlinedButton(onClick = { confirmClear = false }) { Text("Cancel") }
                }
            } else {
                OutlinedButton(onClick = { confirmClear = true }) { Text("Delete all memories") }
            }
        }
        if (msg.isNotEmpty()) Text(msg, color = Dim)
        Text(
            "Memories are things you ask me to remember, like \"remember that I prefer Gujarati\". They are kept on this phone in the app's private storage (not yet encrypted). While Memory is ON, saved memories are sent along with your chat messages to the online AI so it can use them. While Memory is OFF, nothing new is saved and none are sent.",
            color = Dim
        )
    }
}
