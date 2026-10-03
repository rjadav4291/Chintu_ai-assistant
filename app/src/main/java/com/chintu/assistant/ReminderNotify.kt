package com.chintu.assistant

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

object ReminderNotifier {
    private const val CHANNEL = "reminders"

    fun show(ctx: Context, r: Reminder, title: String) {
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
                ReminderNotifier.show(context, r, "Reminder")
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

// END OF FILE
