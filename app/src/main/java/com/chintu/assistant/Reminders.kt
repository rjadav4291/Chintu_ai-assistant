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

    fun notify(ctx: Context, r: Reminder, titl
