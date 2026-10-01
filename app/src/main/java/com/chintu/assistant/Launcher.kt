package com.chintu.assistant

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.MediaStore
import android.provider.Settings
import java.util.Locale

// Extra instructions for the online AI. The app itself (not the AI) opens apps.
const val LAUNCHER_PROMPT =
    "The app itself (not you) can open YouTube, Chrome, Instagram, WhatsApp, Camera, Calculator, Maps and Settings, and can open the Wi-Fi, Bluetooth, sound, display and location settings screens, when the user says something like \"open YouTube\". If you are seeing such a request, the app did not understand it: say honestly that you did not open anything and suggest the phrase \"open YouTube\". You cannot close apps and you cannot turn Wi-Fi or Bluetooth on or off."

private class Target(
    val label: String,
    val aliases: List<String>,
    val isSetting: Boolean,
    val intent: () -> Intent?
)

class AppLauncher(private val ctx: Context) {
    private fun pkg(name: String): Intent? = ctx.packageManager.getLaunchIntentForPackage(name)

    // Settings shortcuts come first so "wifi settings" does not match the Settings app.
    private val targets = listOf(
        Target("Wi-Fi settings", listOf("wifi", "wi-fi", "wi fi", "વાઇફાઇ", "વાઈફાઈ", "वाईफाई", "वाई-फाई"), true) { Intent(Settings.ACTION_WIFI_SETTINGS) },
        Target("Bluetooth settings", listOf("bluetooth", "બ્લૂટૂથ", "બ્લુટુથ", "ब्लूटूथ"), true) { Intent(Settings.ACTION_BLUETOOTH_SETTINGS) },
        Target("Sound settings", listOf("sound", "volume", "અવાજ", "आवाज़", "आवाज"), true) { Intent(Settings.ACTION_SOUND_SETTINGS) },
        Target("Display settings", listOf("display", "brightness", "ડિસ્પ્લે", "डिस्प्ले"), true) { Intent(Settings.ACTION_DISPLAY_SETTINGS) },
        Target("Location settings", listOf("location", "gps", "લોકેશન", "लोकेशन"), true) { Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS) },
        Target("YouTube", listOf("youtube", "you tube", "યુટ્યુબ", "યૂટ્યુબ", "यूट्यूब"), false) { pkg("com.google.android.youtube") },
        Target("Chrome", listOf("chrome", "ક્રોમ", "क्रोम"), false) { pkg("com.android.chrome") },
        Target("Instagram", listOf("instagram", "insta", "ઇન્સ્ટાગ્રામ", "ઇન્સ્ટા", "इंस्टाग्राम"), false) { pkg("com.instagram.android") },
        Target("WhatsApp", listOf("whatsapp", "whats app", "વોટ્સએપ", "વોટ્સઅપ", "व्हाट्सऐप", "व्हाट्सएप", "वॉट्सऐप"), false) { pkg("com.whatsapp") },
        Target("Camera", listOf("camera", "કેમેરા", "कैमरा"), false) { Intent(MediaStore.INTENT_ACTION_STILL_IMAGE_CAMERA) },
        Target("Calculator", listOf("calculator", "કેલ્ક્યુલેટર", "कैलकुलेटर"), false) { Intent.makeMainSelectorActivity(Intent.ACTION_MAIN, Intent.CATEGORY_APP_CALCULATOR) },
        Target("Maps", listOf("maps", "google maps", "map", "મેપ્સ", "નકશો", "मैप्स"), false) { pkg("com.google.android.apps.maps") ?: Intent(Intent.ACTION_VIEW, Uri.parse("geo:0,0")) },
        Target("Settings", listOf("settings", "setting", "સેટિંગ્સ", "સેટિંગ", "सेटिंग्स", "सेटिंग"), false) { Intent(Settings.ACTION_SETTINGS) }
    )

    private val openVerbs = listOf(
        "open", "launch", "start", "run", "khol", "kholo", "kholna", "kholdo", "chalu",
        "ખોલ", "ખોલો", "ખોલજે", "ખોલી", "શરૂ", "ચાલુ", "खोल", "खोलो", "खोलना", "चलाओ", "चालू", "लॉन्च"
    )
    private val toggleVerbs = listOf(
        "turn on", "turn off", "switch on", "switch off", "enable", "disable", "on kar", "off kar",
        "chalu kar", "band kar", "ચાલુ કર", "બંધ કર", "चालू कर", "बंद कर"
    )
    private val closeVerbs = listOf("close", "kill", "quit", "exit")

    private fun has(low: String, words: List<String>, term: String): Boolean {
        val ascii = term.all { it.code < 128 }
        return if (ascii) {
            if (term.contains(' ')) low.contains(term) else words.contains(term)
        } else {
            low.contains(term)
        }
    }

    fun handle(raw: String): String? {
        val low = raw.trim().lowercase(Locale.ROOT)
        val words = low.split(Regex("[\\s,.!?;:]+")).filter { it.isNotEmpty() }
        if (words.isEmpty() || words.size > 7) return null
        val opening = openVerbs.any { has(low, words, it) }
        val toggling = toggleVerbs.any { has(low, words, it) }
        val closing = closeVerbs.any { has(low, words, it) }
        val settingWord = has(low, words, "settings") || has(low, words, "setting") ||
            low.contains("સેટિંગ") || low.contains("सेटिंग")
        val t = targets.firstOrNull { tg -> tg.aliases.any { has(low, words, it) } }
        if (t == null) {
            val first = words[0]
            if (words.size <= 3 && (first == "open" || first == "launch")) {
                return "I can only open YouTube, Chrome, Instagram, WhatsApp, Camera, Calculator, Maps and Settings for now."
            }
            return null
        }
        if (closing && !t.isSetting) {
            return "I can't close apps, because Android doesn't allow that. You can close ${t.label} from your recent apps."
        }
        if (t.isSetting) {
            if (!(opening || toggling || settingWord)) return null
            val result = open(t)
            return if (toggling) {
                "I can't change that setting myself, because Android doesn't allow apps to do that. $result"
            } else {
                result
            }
        }
        if (!opening) return null
        return open(t)
    }

    private fun open(t: Target): String {
        val intent = try { t.intent() } catch (e: Exception) { null }
        if (intent == null) return "I couldn't find ${t.label} on this phone. It may not be installed."
        return try {
            ctx.startActivity(intent)
            "Opening ${t.label}."
        } catch (e: ActivityNotFoundException) {
            "I couldn't open ${t.label}. It doesn't seem to be available on this phone."
        } catch (e: SecurityException) {
            "I couldn't complete that action. Please check the required permission."
        }
    }
}
