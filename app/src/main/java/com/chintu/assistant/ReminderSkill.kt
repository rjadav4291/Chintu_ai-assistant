package com.chintu.assistant

import android.content.Context
import java.util.Calendar
import java.util.Locale

class RemResult(val reply: String, val needPermission: Boolean = false)

// Understands "remind me ..." sentences and the commands to list, delete, pause and resume reminders.
class ReminderSkill(private val ctx: Context) {
    private var pending: (() -> String)? = null
    private val yes = setOf("yes", "y", "ok", "okay", "confirm", "sure", "haan", "ha", "han", "હા", "हाँ", "हां")
    private val no = setOf("no", "n", "cancel", "nahi", "na", "ના", "नहीं")
    private val ic = RegexOption.IGNORE_CASE

    private val introRe = Regex("""\b(?:please\s+)?(?:remind\s+me|set\s+(?:a\s+)?reminder|create\s+(?:a\s+)?reminder|add\s+(?:a\s+)?reminder)\b""", ic)
    private val weeklyRe = Regex("""\b(?:every|each)\s+(sunday|monday|tuesday|wednesday|thursday|friday|saturday)s?\b""", ic)
    private val dailyRe = Regex("""\b(?:every\s*day|everyday|daily|every\s+(?:morning|afternoon|evening|night))\b""", ic)
    private val amPmRe = Regex("""\b(?:at\s+)?(\d{1,2})(?::(\d{2}))?\s*(a\.?m\.?|p\.?m\.?)(?![a-z])""", ic)
    private val h24Re = Regex("""\b(?:at\s+)?([01]?\d|2[0-3]):([0-5]\d)\b""")
    private val bareAtRe = Regex("""\bat\s+\d{1,2}\b""", ic)
    private val relRe = Regex("""\bin\s+(\d+(?:\.\d+)?)\s*(hours?|hrs?|minutes?|mins?)\b""", ic)
    private val dayRe = Regex("""\b(tomorrow|today|tonight)\b""", ic)

    private fun dayNumber(name: String): Int = when (name.lowercase(Locale.ROOT)) {
        "sunday" -> Calendar.SUNDAY
        "monday" -> Calendar.MONDAY
        "tuesday" -> Calendar.TUESDAY
        "wednesday" -> Calendar.WEDNESDAY
        "thursday" -> Calendar.THURSDAY
        "friday" -> Calendar.FRIDAY
        else -> Calendar.SATURDAY
    }

    fun handle(raw: String, notifOk: Boolean): RemResult? {
        val text = raw.trim()
        val low = text.lowercase(Locale.ROOT)
        val act = pending
        if (act != null) {
            pending = null
            val w = low.trim('.', '!', '?', ' ')
            if (w in yes) return RemResult(act())
            if (w in no) return RemResult("Okay, I cancelled that.")
        }
        if (low.startsWith("remember to ")) {
            return RemResult(
                "That sounds like a reminder. For a reminder, say for example: remind me at 7 PM to ${text.drop(12).trim()}. To save it as a memory instead, say: remember that ..."
            )
        }
        val managed = manage(low)
        if (managed != null) return RemResult(managed)
        if (introRe.containsMatchIn(text)) return create(text, notifOk)
        return null
    }

    private fun manage(low: String): String? {
        if (!Regex("""\breminders?\b""").containsMatchIn(low)) return null
        val w = low.trim('.', '!', '?', ' ')
        val list = Reminders.load(ctx)
        val n = Regex("""\d+""").find(low)?.value?.toIntOrNull()
        val hasAll = low.split(Regex("[\\s,.!?]+")).any { it == "all" || it == "every" }

        if (Regex("""\b(delete|remove|cancel)\b""").containsMatchIn(low)) {
            if (list.isEmpty()) return "You have no reminders to delete."
            if (hasAll) {
                pending = { if (Reminders.removeAll(ctx)) "All reminders deleted." else "I couldn't delete all the reminders." }
                return "Delete all ${list.size} reminders? Say yes to confirm."
            }
            if (n == null || n < 1 || n > list.size) return "Which reminder? Say delete reminder 2. You have ${list.size}."
            val target = list[n - 1]
            pending = { if (Reminders.remove(ctx, target.id)) "Deleted reminder $n." else "I couldn't delete that reminder." }
            return "Delete reminder $n: \"${target.text.take(60)}\"? Say yes to confirm."
        }
        if (Regex("""\b(pause|disable|stop|resume|enable)\b""").containsMatchIn(low)) {
            val on = Regex("""\b(resume|enable)\b""").containsMatchIn(low)
            if (n == null || n < 1 || n > list.size) return "Which reminder? Say ${if (on) "resume" else "pause"} reminder 2. You have ${list.size}."
            val err = Reminders.setEnabled(ctx, list[n - 1].id, on)
            return if (err != null) "I did not change reminder $n. $err"
            else if (on) "Reminder $n is on again." else "Reminder $n is paused."
        }
        val listing = w == "reminders" || w == "my reminders" ||
            listOf("show my reminders", "show reminders", "list reminders", "list my reminders", "what reminders", "view reminders")
                .any { low.contains(it) }
        if (listing) {
            if (list.isEmpty()) return "You have no reminders."
            return "Your reminders:\n" + list.mapIndexed { i, r ->
                "${i + 1}. ${r.text} (${Reminders.describe(r)})" + (if (r.enabled) "" else " [paused]")
            }.joinToString("\n")
        }
        return null
    }

    private fun create(raw: String, notifOk: Boolean): RemResult {
        var s = raw
        var type = "once"
        var dow = 0
        val weekly = weeklyRe.find(s)
        if (weekly != null) {
            type = "weekly"
            dow = dayNumber(weekly.groupValues[1])
            s = s.removeRange(weekly.range)
        } else {
            val daily = dailyRe.find(s)
            if (daily != null) {
                type = "daily"
                s = s.removeRange(daily.range)
            }
        }
        var hour = -1
        var minute = 0
        var relMs = -1L
        val rel = if (type == "once") relRe.find(s) else null
        if (rel != null) {
            val amount = rel.groupValues[1].toDouble()
            val unit = rel.groupValues[2].lowercase(Locale.ROOT)
            relMs = (amount * (if (unit.startsWith("h")) 3_600_000.0 else 60_000.0)).toLong()
            s = s.removeRange(rel.range)
        } else {
            val ap = amPmRe.find(s)
            if (ap != null) {
                val h = ap.groupValues[1].toInt()
                val m = if (ap.groupValues[2].isEmpty()) 0 else ap.groupValues[2].toInt()
                if (h !in 1..12 || m > 59) return RemResult("I couldn't understand that time. Try something like 7:30 PM.")
                val pm = ap.groupValues[3].lowercase(Locale.ROOT).startsWith("p")
                hour = (h % 12) + (if (pm) 12 else 0)
                minute = m
                s = s.removeRange(ap.range)
            } else {
                val h24 = h24Re.find(s)
                if (h24 != null) {
                    hour = h24.groupValues[1].toInt()
                    minute = h24.groupValues[2].toInt()
                    s = s.removeRange(h24.range)
                } else if (bareAtRe.containsMatchIn(s)) {
                    return RemResult("Did you mean AM or PM? Please say it again with AM or PM, like \"at 7 PM\", or use 24-hour time like \"at 19:00\".")
                }
            }
        }
        val dayMatch = dayRe.find(s)
        val dayWord = dayMatch?.value?.lowercase(Locale.ROOT) ?: ""
        if (dayMatch != null) s = s.removeRange(dayMatch.range)
        s = introRe.replace(s, " ")
        var body = s.replace(Regex("""\s{2,}"""), " ").trim().trim(',', '.', ':', '-').trim()
        body = body.replaceFirst(Regex("""^(?:to|that|about)\s+""", ic), "").trim()
        body = body.replace(Regex("""\s+(?:at|on|in)$""", ic), "").trim()

        if (hour < 0 && relMs < 0) {
            return RemResult("What time? For example: remind me at 7 PM to call mom, or every Sunday at 9 AM remind me to plan my week.")
        }
        if (body.isEmpty()) return RemResult("What should I remind you about?")
        if (body.length > 200) return RemResult("That reminder is too long. Please keep it under 200 characters.")

        val now = System.currentTimeMillis()
        val reminder: Reminder
        if (type == "once") {
            val at: Long
            if (relMs >= 0) {
                at = now + relMs
            } else {
                val c = Calendar.getInstance()
                c.set(Calendar.HOUR_OF_DAY, hour)
                c.set(Calendar.MINUTE, minute)
                c.set(Calendar.SECOND, 0)
                c.set(Calendar.MILLISECOND, 0)
                if (dayWord == "tomorrow") {
                    c.add(Calendar.DAY_OF_YEAR, 1)
                } else if (c.timeInMillis <= now) {
                    if (dayWord == "today" || dayWord == "tonight") return RemResult("That time has already passed today.")
                    c.add(Calendar.DAY_OF_YEAR, 1)
                }
                at = c.timeInMillis
            }
            reminder = Reminder(0, body, "once", 0, 0, 0, at, true)
        } else {
            if (hour < 0) return RemResult("Repeating reminders need a clock time, like 7 AM. For example: every morning at 7 AM remind me to exercise.")
            reminder = Reminder(0, body, type, hour, minute, dow, 0L, true)
        }
        if (!notifOk) {
            return RemResult("To show reminders I need permission to show notifications. Please allow it, then say that again.", true)
        }
        val err = Reminders.add(ctx, reminder)
        if (err != null) return RemResult("I did not create the reminder. $err")
        return RemResult("Reminder set: \"${reminder.text}\", ${Reminders.describe(reminder)}. I'll show a notification.")
    }
}
