package com.chintu.assistant

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.provider.AlarmClock
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale
import org.json.JSONArray

// Local tools: they work without internet and never call an online AI.
// handle() returns a reply if a tool handled the message, or null to let the AI answer.
class Tools(private val ctx: Context) {
    private val prefs = ctx.getSharedPreferences("chintu_tools", Context.MODE_PRIVATE)
    private var pending: (() -> String)? = null
    private val yes = setOf("yes", "y", "ok", "okay", "confirm", "sure", "haan", "ha", "han", "હા", "हाँ", "हां")
    private val no = setOf("no", "n", "cancel", "nahi", "na", "ના", "नहीं")

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
        return timeDate(low) ?: stopwatch(low) ?: timer(low) ?: notes(text, low) ?: calc(low)
    }

    // ---------- time and date ----------
    private val timeWords = listOf(
        "what time is it", "what's the time", "whats the time", "what is the time", "current time",
        "time now", "tell me the time", "samay kya", "time kya", "kitne baje", "kitna baje", "time shu",
        "ketla vagya", "સમય શું", "ટાઈમ શું", "ટાઇમ શું", "કેટલા વાગ્યા", "समय क्या", "कितने बजे", "टाइम क्या"
    )
    private val dateWords = listOf(
        "what date is it", "today's date", "todays date", "what's the date", "whats the date",
        "what is the date", "current date", "date today", "what day is it", "what day is today",
        "which day is it", "which day is today", "tarikh", "taarikh", "તારીખ", "આજે કયો વાર",
        "आज की तारीख", "आज कौन सा दिन"
    )

    private fun timeDate(low: String): String? {
        if (low.split(" ").size > 8) return null
        val w = low.trim('.', '!', '?', ' ')
        val t = w == "time" || timeWords.any { low.contains(it) }
        val d = w == "date" || dateWords.any { low.contains(it) }
        if (!t && !d) return null
        val now = LocalDateTime.now()
        val parts = mutableListOf<String>()
        if (t) parts.add("It's " + now.format(DateTimeFormatter.ofPattern("h:mm a", Locale.ENGLISH)))
        if (d) parts.add("Today is " + now.format(DateTimeFormatter.ofPattern("EEEE, d MMMM yyyy", Locale.ENGLISH)))
        return parts.joinToString(". ") + " (from your phone's clock)."
    }

    // ---------- stopwatch (saved on the phone, survives closing the app) ----------
    private fun fmt(ms: Long): String {
        val s = ms / 1000
        return "%d:%02d:%02d".format(s / 3600, (s % 3600) / 60, s % 60)
    }

    private fun stopwatch(low: String): String? {
        val keys = listOf("stopwatch", "stop watch", "સ્ટોપવોચ", "स्टॉपवॉच")
        if (keys.none { low.contains(it) }) return null
        val r = keys.fold(low) { acc, k -> acc.replace(k, " ") }
        val startedAt = prefs.getLong("sw_start", 0L)
        val saved = prefs.getLong("sw_elapsed", 0L)
        val now = System.currentTimeMillis()
        val total = saved + (if (startedAt > 0L) now - startedAt else 0L)
        fun has(vararg w: String) = w.any { r.contains(it) }
        return when {
            has("reset", "clear", "zero", "રીસેટ", "रीसेट") -> {
                val ok = prefs.edit().putLong("sw_start", 0L).putLong("sw_elapsed", 0L).commit()
                if (ok) "Stopwatch reset to 0:00:00." else "I couldn't reset the stopwatch."
            }
            has("start", "begin", "resume", "chalu", "shuru", "શરૂ", "ચાલુ", "शुरू", "चालू") -> {
                if (startedAt > 0L) "The stopwatch is already running at ${fmt(total)}."
                else if (prefs.edit().putLong("sw_start", now).commit()) "Stopwatch started."
                else "I couldn't start the stopwatch."
            }
            has("stop", "pause", "band", "રોક", "બંધ", "रोक", "बंद") -> {
                if (startedAt <= 0L) "The stopwatch isn't running. It shows ${fmt(total)}."
                else if (prefs.edit().putLong("sw_start", 0L).putLong("sw_elapsed", total).commit()) "Stopwatch stopped at ${fmt(total)}."
                else "I couldn't stop the stopwatch."
            }
            else -> if (startedAt > 0L) "The stopwatch is running: ${fmt(total)}." else "The stopwatch is stopped at ${fmt(total)}."
        }
    }

    // ---------- timer (handed to the phone's Clock app) ----------
    private val timerUnit = Regex("""(\d+(?:\.\d+)?)\s*(hours?|hrs?|minutes?|mins?|seconds?|secs?|કલાક|મિનિટ|મીનીટ|સેકન્ડ|घंटे|घंटा|मिनट|सेकंड|सेकेंड)(?![a-zA-Z])""")

    private fun timer(low: String): String? {
        val keys = listOf("timer", "ટાઇમર", "ટાઈમર", "टाइमर")
        if (keys.none { low.contains(it) }) return null
        val r = keys.fold(low) { acc, k -> acc.replace(k, " ") }
        val found = timerUnit.findAll(r).toList()
        val cancelWords = listOf("cancel", "delete", "remove", "stop", "band", "બંધ", "रद्द", "बंद")
        val setWords = listOf("set", "start", "create", "make", "chalu", "laga", "shuru", "શરૂ", "ચાલુ", "सेट", "शुरू", "लगा")
        if (found.isEmpty() && cancelWords.any { r.contains(it) }) {
            return "I can't cancel timers from here. Please cancel it in your Clock app."
        }
        if (found.isEmpty() && setWords.none { r.contains(it) }) return null
        if (found.isEmpty()) return "How long should the timer be? For example: set a timer for 5 minutes."
        var seconds = 0.0
        for (m in found) {
            val n = m.groupValues[1].toDouble()
            val u = m.groupValues[2]
            seconds += n * when {
                u.startsWith("h") || u == "કલાક" || u.startsWith("घंट") -> 3600.0
                u.startsWith("m") || u == "મિનિટ" || u == "મીનીટ" || u.startsWith("मिन") -> 60.0
                else -> 1.0
            }
        }
        val total = Math.round(seconds).toInt()
        if (total < 1 || total > 86400) return "I can set timers from 1 second up to 24 hours."
        val intent = Intent(AlarmClock.ACTION_SET_TIMER)
            .putExtra(AlarmClock.EXTRA_LENGTH, total)
            .putExtra(AlarmClock.EXTRA_MESSAGE, "Chintu timer")
            .putExtra(AlarmClock.EXTRA_SKIP_UI, true)
        return try {
            ctx.startActivity(intent)
            "Sent a timer for ${duration(total)} to your Clock app. I can't confirm it started, so please check the Clock app."
        } catch (e: ActivityNotFoundException) {
            "I couldn't complete that action. No Clock app on this phone accepted the timer."
        } catch (e: SecurityException) {
            "I couldn't complete that action. Please check the required permission."
        }
    }

    private fun duration(total: Int): String {
        val h = total / 3600
        val m = (total % 3600) / 60
        val s = total % 60
        val p = mutableListOf<String>()
        if (h > 0) p.add(if (h == 1) "1 hour" else "$h hours")
        if (m > 0) p.add(if (m == 1) "1 minute" else "$m minutes")
        if (s > 0) p.add(if (s == 1) "1 second" else "$s seconds")
        return p.joinToString(" ")
    }

    // ---------- notes (saved only on this phone) ----------
    private fun loadNotes(): MutableList<String> {
        val out = mutableListOf<String>()
        try {
            val arr = JSONArray(prefs.getString("notes", "[]") ?: "[]")
            for (i in 0 until arr.length()) out.add(arr.getString(i))
        } catch (e: Exception) {
        }
        return out
    }

    // True only if the notes were written AND read back correctly.
    private fun saveNotes(list: List<String>): Boolean {
        val arr = JSONArray()
        for (n in list) arr.put(n)
        return prefs.edit().putString("notes", arr.toString()).commit() && loadNotes() == list
    }

    private val addPrefixes = listOf(
        "save a note", "save note", "add a note", "add note", "make a note", "take a note",
        "create a note", "note down", "note that", "note this", "note:", "note",
        "નોંધ કરો", "નોંધ કર", "નોંધ", "नोट करो", "नोट करें", "नोट कर", "नोट"
    ).sortedByDescending { it.length }
    private val delWords = listOf("delete", "remove", "clear", "erase", "કાઢી", "ડિલીટ", "હટાવ", "हटा", "डिलीट", "मिटा")
    private val allWords = setOf("all", "every", "badhi", "બધી", "બધા", "सभी", "सारे", "saari")
    private val listPhrases = listOf(
        "show my notes", "show notes", "list notes", "list my notes", "read notes", "read my notes",
        "all notes", "view notes", "notes list", "મારી નોંધ", "નોંધ બતાવ", "नोट्स दिखा", "मेरे नोट"
    )

    private fun notes(text: String, low: String): String? {
        val hasNote = Regex("""\bnotes?\b""").containsMatchIn(low) || low.contains("નોંધ") || low.contains("नोट")
        if (!hasNote) return null
        val w = low.trim('.', '!', '?', ' ')

        if (delWords.any { low.contains(it) }) {
            val list = loadNotes()
            if (list.isEmpty()) return "You have no saved notes to delete."
            val words = low.split(Regex("[\\s,.!?]+"))
            if (words.any { it in allWords }) {
                pending = { if (saveNotes(emptyList())) "All notes deleted." else "I couldn't delete the notes." }
                return "Delete all ${list.size} notes? Say yes to confirm."
            }
            val n = Regex("""\d+""").find(low)?.value?.toIntOrNull()
            if (n == null || n < 1 || n > list.size) {
                return "Which note? Say delete note 2, or delete all notes. You have ${list.size}."
            }
            pending = {
                val cur = loadNotes()
                if (n > cur.size) {
                    "That note is no longer there."
                } else {
                    cur.removeAt(n - 1)
                    if (saveNotes(cur)) "Deleted note $n." else "I couldn't delete the note."
                }
            }
            return "Delete note $n: \"${list[n - 1].take(60)}\"? Say yes to confirm."
        }

        if (w == "notes" || w == "my notes" || listPhrases.any { low.contains(it) }) {
            val list = loadNotes()
            if (list.isEmpty()) return "You have no saved notes."
            val shown = list.takeLast(10)
            val start = list.size - shown.size
            val lines = shown.mapIndexed { i, n -> "${start + i + 1}. $n" }.joinToString("\n")
            return "Your notes:\n" + lines + (if (start > 0) "\n(+$start older notes)" else "")
        }

        for (p in addPrefixes) {
            if (!low.startsWith(p)) continue
            val next = low.getOrNull(p.length)
            if (next != null && next.isLetter()) continue
            val content = text.drop(p.length).trimStart(' ', ':', '-', '–', ',', '.').trim()
            if (content.isEmpty()) return "What should I note down?"
            val list = loadNotes()
            list.add(content.take(1000))
            return if (saveNotes(list)) "Saved as note ${list.size}: ${content.take(80)}"
            else "I couldn't save the note."
        }
        return null
    }

    // ---------- calculator ----------
    private val exprChars = Regex("""[0-9+\-*/^().xX×÷\s]+""")
    private val percentOf = Regex("""(\d+(?:\.\d+)?)\s*(?:%|percent)\s*of\s*(\d+(?:\.\d+)?)""")
    private val fillers = listOf(
        "calculate", "what is", "what's", "whats", "how much is", "equals", "kitna hota hai",
        "kitna hai", "કેટલું થાય", "कितना होता है", "=", "?"
    )

    private fun calc(low: String): String? {
        if (low.length > 80) return null
        val pm = percentOf.find(low)
        if (pm != null) {
            val v = pm.groupValues[1].toDouble() / 100.0 * pm.groupValues[2].toDouble()
            return "${pm.groupValues[1]}% of ${pm.groupValues[2]} = ${Calc.format(v)}"
        }
        var s = low
        for (p in fillers) s = s.replace(p, " ")
        s = s.replace(" plus ", "+").replace(" minus ", "-").replace(" times ", "*")
            .replace(" multiplied by ", "*").replace(" divided by ", "/").replace(" into ", "*").trim()
        if (s.isEmpty() || !exprChars.matches(s)) return null
        if (!s.any { it.isDigit() } || !s.any { it in "+-*/^xX×÷" }) return null
        return try {
            "$s = ${Calc.format(Calc.eval(s))}"
        } catch (e: CalcException) {
            "I couldn't calculate that: ${e.message}."
        }
    }
}
