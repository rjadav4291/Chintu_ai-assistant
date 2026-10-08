package com.chintu.assistant

import android.Manifest
import android.content.pm.PackageManager
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import java.util.Locale
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay

// True while the app is on screen. The wake word listens only while this is true.
object AppFlags {
    var foreground by mutableStateOf(true)
}

object Wake {
    private val greetings = setOf("hey", "hay", "hi", "hello", "okay", "ok")

    private fun distance(a: String, b: String): Int {
        val dp = IntArray(b.length + 1) { it }
        for (i in 1..a.length) {
            var prev = dp[0]
            dp[0] = i
            for (j in 1..b.length) {
                val tmp = dp[j]
                dp[j] = minOf(dp[j] + 1, dp[j - 1] + 1, prev + (if (a[i - 1] == b[j - 1]) 0 else 1))
                prev = tmp
            }
        }
        return dp[b.length]
    }

    // Returns the words said after the wake phrase ("" if none), or null if "Hey <name>" was not heard.
    // The match is a little tolerant, because speech recognition often spells an unusual name differently.
    fun match(text: String, name: String): String? {
        val target = name.lowercase(Locale.ROOT).filter { it.isLetterOrDigit() }
        if (target.length < 3) return null
        val words = text.lowercase(Locale.ROOT).split(Regex("[^\\p{L}\\p{N}]+")).filter { it.isNotEmpty() }
        val allow = maxOf(1, (target.length + 1) / 3)
        for (i in 0 until words.size - 1) {
            if (words[i] !in greetings) continue
            for (n in 1..2) {
                if (i + n >= words.size) break
                val cand = words.subList(i + 1, i + 1 + n).joinToString("")
                if (distance(cand, target) <= allow) {
                    return words.drop(i + 1 + n).joinToString(" ")
                }
            }
        }
        return null
    }
}

// While the app is on screen and idle, listens on the device for "Hey <name>". Nothing is sent online.
@Composable
fun WakeEffect(
    wakeOn: Boolean,
    voice: VoiceManager,
    vm: ChatVm,
    name: String,
    onWakeOff: () -> Unit,
    onCommand: (String) -> Unit,
    onWakeOnly: () -> Unit
) {
    val ctx = LocalContext.current
    var tick by remember { mutableStateOf(0) }
    var fails by remember { mutableStateOf(0) }
    val command by rememberUpdatedState(onCommand)
    val wakeOnly by rememberUpdatedState(onWakeOnly)
    val turnOff by rememberUpdatedState(onWakeOff)
    LaunchedEffect(wakeOn, vm.state, AppFlags.foreground, tick, name) {
        if (!wakeOn || !AppFlags.foreground || vm.state != AiState.READY) return@LaunchedEffect
        if (ctx.checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) return@LaunchedEffect
        if (!voice.onDeviceAvailable()) {
            turnOff()
            vm.notice("The wake word was turned off, because this phone can't recognise speech on the device itself.", true)
            return@LaunchedEffect
        }
        delay(400)
        voice.startListening(
            Lang.AUTO,
            partial = { },
            done = { text ->
                fails = 0
                val rest = Wake.match(text, name)
                if (rest == null) {
                    tick++
                } else if (rest.isNotBlank()) {
                    command(rest)
                } else {
                    wakeOnly()
                }
            },
            fail = { msg, serious ->
                if (serious) {
                    fails++
                    if (fails >= 3) {
                        turnOff()
                        vm.notice("The wake word was turned off after repeated problems: $msg", true)
                    } else {
                        tick++
                    }
                } else {
                    tick++
                }
            },
            onDeviceOnly = true
        )
        try {
            awaitCancellation()
        } finally {
            if (vm.state != AiState.LISTENING) voice.cancelListening()
        }
    }
}

// END OF FILE
