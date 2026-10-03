package com.chintu.assistant

import android.content.Intent
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
                val i = Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, ctx.packageName)
                try {
                    ctx.startActivity(i)
                } catch (e: Exception) {
                    msg = "I couldn't open the notification settings."
                }
            }) { Text("Notification settings") }
            OutlinedButton(onClick = { rev++ }) { Text("Refresh") }
        }
        if (msg.isNotEmpty()) Text(msg, color = Red)
        if (list.isEmpty()) {
            Text("No reminders yet. Say: remind me at 7 PM to call mom. Or: every Sunday at 9 AM remind me to plan my week.", color = Dim)
        }
        list.forEachIndexed { i, r ->
            Spacer(Modifier.height(4.dp))
            Text("${i + 1}. ${r.text}", fontSize = 16.sp, color = Color.White)
            Text(Reminders.describe(r), color = Dim)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Switch(checked = r.enabled, onCheckedChange = { on ->
                    msg = Reminders.setEnabled(ctx, r.id, on) ?: ""
                    rev++
                })
                Spacer(Modifier.width(12.dp))
                Text(if (r.enabled) "ON" else "PAUSED", color = if (r.enabled) Cyan else Dim)
            }
            if (askId == r.id) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = {
                        msg = if (Reminders.remove(ctx, r.id)) "Reminder deleted." else "I couldn't delete that reminder."
                        askId = -1
                        rev++
                    }) { Text("Yes, delete") }
                    OutlinedButton(onClick = { askId = -1 }) { Text("Cancel") }
                }
            } else {
                OutlinedButton(onClick = { askId = r.id }) { Text("Delete") }
            }
        }
        Spacer(Modifier.height(8.dp))
        Text(
            "How it works: each reminder is registered with Android as an exact alarm, and a notification is shown when it is due. Android does not let me check that an alarm is still registered. Force-stopping the app removes its alarms until you open it again, and some phones' battery savers can delay or block them. After a restart, I put them back. Reminders are stored only on this phone.",
            color = Dim
        )
    }
}

// END OF FILE
