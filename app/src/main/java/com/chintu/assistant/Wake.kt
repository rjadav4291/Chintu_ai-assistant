package com.chintu.assistant

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import java.util.Locale

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

// END OF FILE
