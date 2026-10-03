package com.chintu.assistant

import android.Manifest
import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
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
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import org.json.JSONArray
import org.json.JSONObject

// type: "once", "daily" or "weekly". dow uses Calendar.SUNDAY..SATURDAY (1..7). at is for "once" only.
data class Reminder(
    val id: Int,
    val text: String,
    val type: String,
    val hour: Int,
    val minute: Int,
    val dow: Int,
    val at: Long,
    val enabled: Boolean
)

object Reminders {
    const val ACTION = "com.chintu.assistant.REMINDER"
    private const val CHANNEL = "reminders"
    private val dayNames = listOf("", "Sunday", "Monday", "Tuesday", "Wednesday", "Thursday", "Friday", "Saturday")

    private fun prefs(ctx: Context) = ctx.getSharedPreferences("chintu_tools", Context.MODE_PRIVATE)

    fun load(ctx: Context): MutableList<Reminder> {
        val out = mutableListOf<Reminder>()
        try {
            val arr = JSONArray(prefs(ctx).getString("reminders", "[]") ?: "[]")
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                out.add(
                    Reminder(
                        o.getInt("id"), o.getString("text"), o.getString("type"), o.getInt("hour"),
                        o.getInt("minute"), o.getInt("dow"), o.getLong("at"), o.getBoolean("enabled")
                    )
                )
            }
        } catch (e: Exception) {
        }
        return out
    }

    // True only if the list was written AND read back equal.
    fun save(ctx: Context, list: List<Reminder>): Boolean {
        val arr = JSONArray()
        for (r in list) {
            arr.put(
                JSONObject().put("id", r.id).put("text", r.text).put("type", r.type).put("hour", r.hour)
                    .put("minute", r.minute).put("dow", r.dow).put("at", r.at).put("enabled", r.enabled)
            )
        }
        return prefs(ctx).edit().putString("reminders", arr.toString()).commit() && load(ctx) == list
    }

    fun notifOk(ctx: Context): Boolean =
        Build.VERSION.SDK_INT < 33 || ctx.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

    fun exactOk(ctx: Context): Boolean {
        if (Build.VERSION.SDK_INT < 31) return true
        val am = ctx.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        return am.canScheduleExactAlarms()
    }

    private fun clock(h: Int, m: Int): String {
        val c = Calendar.getInstance()
        c.set(Calendar.HOUR_OF_DAY, h)
        c.set(Calendar.MINUTE, m)
        return SimpleDateFormat("h:mm a", Locale.ENGLISH).format(c.time)
    }

    fun describe(r: Reminder): String = when (r.type) {
        "once" -> "once, " + SimpleDateFormat("EEE d MMM, h:mm a", Locale.ENGLISH).format(Date(r.at))
        "daily" -> "every day at " + clock(r.hour, r.minute)
        else -> "every " + dayNames[r.dow.coerceIn(1, 7)] + " at " + clock(r.hour, r.minute)
    }

    fun nextTrigger(r: Reminder, from: Long): Long? {
        if (r.type == "once") return if (r.at > from) r.at else null
        val c = Calendar.getInstance()
        c.timeInMillis = from
        c.set(Calendar.HOUR_OF_DAY, r.hour)
        c.set(Calendar.MINUTE, r.minute)
        c.set(Calendar.SECOND, 0)
        c.set(Calendar.MILLISECOND, 0)
        if (r.type == "weekly") {
            c.set(Calendar.DAY_OF_WEEK, r.dow)
            if (c.timeInMillis <= from) c.add(Calendar.WEEK_OF_YEAR, 1)
        } else {
            if (c.timeInMillis <= from) c.add(Calendar.DAY_OF_YEAR, 1)
        }
        return c.timeInMillis
    }

    private fun pending(ctx: Context, id: Int): PendingIntent {
        val i = Intent(ctx, ReminderReceiver::class.java).setAction(ACTION).putExtra("id", id)
        return PendingIntent.getBroadcast(ctx, id, i, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    }

    // Returns null on success, otherwise the reason it could not be scheduled.
    fun schedule(ctx: Context, r: Reminder): String? {
        val t = nextTrigger(r, System.currentTimeMillis()) ?: return "That time has already passed."
        if (!exactOk(ctx)) return "Android is not allowing exact alarms for this app."
        val am = ctx.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        return try {
            am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, t, pending(ctx, r.id))
            null
        } catch (e: SecurityException) {
            "Android refused the alarm permission."
        }
    }

    fun cancel(ctx: Context, id: Int) {
        val am = ctx.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        am.cancel(pending(ctx, id))
    }

    // Saves a new reminder and schedules it. Returns null only if both worked.
    fun add(ctx: Context, r: Reminder): String? {
        val list = load(ctx)
        if (list.size >= 30) return "You already have 30 reminders. Please delete some first."
        val full = r.copy(id = (list.maxOfOrNull { it.id } ?: 1000) + 1)
        list.add(full)
        if (!save(ctx, list)) return "I couldn't save it."
        val err = schedule(ctx, full)
        if (err != null) {
            list.removeAll { it.id == full.id }
            save(ctx, list)
            return err
        }
        return null
    }

    // True only if the reminder is really gone afterwards.
    fun remove(ctx: Context, id: Int): Boolean {
        cancel(ctx, id)
        val list = load(ctx)
        list.removeAll { it.id == id }
        return save(ctx, list) && load(ctx).none { it.id == id }
    }

    fun removeAll(ctx: Context): Boolean {
        for (r in load(ctx)) cancel(ctx, r.id)
        return save(ctx, emptyList()) && load(ctx).isEmpty()
    }

    fun setEnabled(ctx: Context, id: Int, on: Boolean): String? {
        val list = load(ctx)
        val i = list.indexOfFirst { it.id == id }
        if (i < 0) return "That reminder no longer exists."
        val updated = list[i].copy(enabled = on)
        if (on) {
            val err = schedule(ctx, updated)
            if (err != null) return err
        } else {
            cancel(ctx, id)
        }
        list[i] = updated
        return if (save(ctx, list)) null else "I couldn't save the change."
    }

    // Called when the app starts and after a restart. One-time reminders that were missed are shown once as "missed".
    fun rescheduleAll(ctx: Context) {
        val list = load(ctx)
        var changed = false
        val now = System.currentTimeMillis()
        val keep = mutableListOf<Reminder>()
        for (r in list) {
            if (!r.enabled) {
                keep.add(r)
                continue
            }
            if (r.type == "once" && r.at <= now) {
                notify(ctx, r, "Missed reminder")
                changed = true
                continue
            }
            schedule(ctx, r)
            keep.add(r)
        }
        if (changed) save(ctx, keep)
    }

    fun notify(ctx: Context, r: Reminder, title: String = "Reminder") {
        val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (nm.getNotificationChannel(CHANNEL) == null) {
            val ch = NotificationChannel(CHANNEL, "Reminders", NotificationManager.IMPORTANCE_HIGH)
            ch.description = "Reminders you asked Chintu to set"
            nm.createNotificationChannel(ch)
        }
        val open = PendingIntent.getActivity(
            ctx, 0,
            Intent(ctx, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val n = Notification.Builder(ctx, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_lock_idle_alarm)
            .setContentTitle(title)
            .setContentText(r.text)
            .setStyle(Notification.BigTextStyle().bigText(r.text))
            .setAutoCancel(true)
            .setContentIntent(open)
            .build()
        nm.notify(r.id, n)
    }
}

class ReminderReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            Reminders.ACTION -> {
                val id = intent.getIntExtra("id", -1)
                val list = Reminders.load(context)
                val r = list.firstOrNull { it.id == id } ?: return
                if (!r.enabled) return
                Reminders.notify(context, r)
                if (r.type == "once") {
                    list.removeAll { it.id == id }
                    Reminders.save(context, list)
                } else {
                    Reminders.schedule(context, r)
                }
            }
            Intent.ACTION_BOOT_COMPLETED, Intent.ACTION_MY_PACKAGE_REPLACED -> Reminders.rescheduleAll(context)
        }
    }
}

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
                if (dayWord == "tomorrow") c.add(Calendar.DAY_OF_YEAR, 1)
                else if (c.timeInMillis <= now) {
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

@Composable
fun RemindersScreen(back: () -> Unit) {
    val ctx = LocalContext.current
    var rev by remember { mutableStateOf(0) }
    var msg by remember { mutableStateOf("") }
    var askId by remember { mutableStateOf(-1) }
    val list = remember(rev) { Reminders.load(ctx) }
    val notif = remember(rev) { Reminders.notifOk(ctx) }
    val exact = remember(rev) { Reminders.exactOk(ctx) }
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        TextButton(onClick = back) { Text("Back") }
        Text("Reminders", fontSize = 22.sp, color = Color.White)
        Text("Notifications: " + if (notif) "allowed" else "NOT allowed", color = if (notif) Cyan else Red)
        Text("Exact alarms: " + if (exact) "allowed" else "NOT allowed", color = if (exact) Cyan else Red)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = {
                val i = Intent(Settings.ACTION_APP_NOTIFICATION_SETT
