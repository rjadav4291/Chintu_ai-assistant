package com.chintu.assistant

import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import java.util.Locale

enum class Lang(val label: String) {
    AUTO("Auto"), GUJARATI("Gujarati"), HINDI("Hindi"), ENGLISH("English"), HINGLISH("Hinglish")
}

data class VoiceCfg(
    val lang: Lang = Lang.AUTO,
    val voiceOn: Boolean = true,
    val rate: Float = 1f,
    val pitch: Float = 1f,
    val voiceName: String = ""
)

fun Lang.locale(): Locale? = when (this) {
    Lang.AUTO -> null
    Lang.GUJARATI -> Locale("gu", "IN")
    Lang.HINDI -> Locale("hi", "IN")
    Lang.ENGLISH -> Locale("en", "IN")
    Lang.HINGLISH -> Locale("en", "IN")
}

fun sampleText(lang: Lang): String = when (lang) {
    Lang.GUJARATI -> "નમસ્તે, આ મારો અવાજ છે."
    Lang.HINDI -> "नमस्ते, यह मेरी आवाज़ है।"
    Lang.HINGLISH -> "Namaste, ye meri awaaz hai."
    else -> "Hello, this is my voice."
}

class VoiceManager(private val ctx: Context) {
    private val main = Handler(Looper.getMainLooper())
    var onState: (AiState) -> Unit = {}
    private var tts: TextToSpeech? = null
    @Volatile private var ttsReady = false
    @Volatile private var speaking = false
    private var recognizer: SpeechRecognizer? = null

    init {
        tts = TextToSpeech(ctx) { status ->
            ttsReady = status == TextToSpeech.SUCCESS
            if (ttsReady) {
                tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                    override fun onStart(utteranceId: String?) {
                        speaking = true
                        main.post { onState(AiState.SPEAKING) }
                    }
                    override fun onDone(utteranceId: String?) { finishSpeaking() }
                    override fun onError(utteranceId: String?) { finishSpeaking() }
                    override fun onStop(utteranceId: String?, interrupted: Boolean) { finishSpeaking() }
                })
            }
        }
    }

    private fun finishSpeaking() {
        if (speaking) {
            speaking = false
            main.post { onState(AiState.READY) }
        }
    }

    private fun detectLocale(text: String): Locale {
        var gu = 0
        var hi = 0
        for (c in text) {
            when (c.code) {
                in 0x0A80..0x0AFF -> gu++
                in 0x0900..0x097F -> hi++
            }
        }
        return if (gu > 0 && gu >= hi) Locale("gu", "IN")
        else if (hi > 0) Locale("hi", "IN")
        else Locale("en", "IN")
    }

    // Returns null if speaking started, or an honest error message.
    fun speak(text: String, cfg: VoiceCfg): String? {
        val t = tts
        if (t == null || !ttsReady) return "Text-to-speech is not ready or not installed on this phone."
        val clean = text.replace(Regex("[*#_`~>]|\\p{So}"), "").trim().take(3900)
        if (clean.isEmpty()) return null
        val loc = cfg.lang.locale() ?: detectLocale(clean)
        val r = t.setLanguage(loc)
        if (r == TextToSpeech.LANG_MISSING_DATA || r == TextToSpeech.LANG_NOT_SUPPORTED) {
            return "This phone has no ${loc.displayLanguage} voice installed, so I can't speak in it. You can install it in your phone's text-to-speech settings."
        }
        if (cfg.voiceName.isNotEmpty()) {
            try {
                val v = t.voices?.firstOrNull { it.name == cfg.voiceName && it.locale.language == loc.language }
                if (v != null) t.setVoice(v)
            } catch (e: Exception) {
            }
        }
        t.setSpeechRate(cfg.rate)
        t.setPitch(cfg.pitch)
        val res = t.speak(clean, TextToSpeech.QUEUE_FLUSH, null, "chintu")
        return if (res == TextToSpeech.SUCCESS) null else "I couldn't start speaking."
    }

    fun voiceNames(lang: Lang): List<String> {
        val t = tts ?: return emptyList()
        val language = (lang.locale() ?: Locale("en", "IN")).language
        return try {
            t.voices.filter { it.locale.language == language }.map { it.name }.sorted()
        } catch (e: Exception) {
            emptyList()
        }
    }

    private fun silence() {
        speaking = false
        tts?.stop()
    }

    fun stopSpeaking() {
        silence()
        onState(AiState.READY)
    }

    fun recognitionAvailable(): Boolean = SpeechRecognizer.isRecognitionAvailable(ctx)

    // True if this phone can recognise speech on the device itself (needs Android 13+ and the speech service's offline support).
    fun onDeviceAvailable(): Boolean =
        Build.VERSION.SDK_INT >= 33 && SpeechRecognizer.isOnDeviceRecognitionAvailable(ctx)

    private fun recogTag(lang: Lang): String? = when (lang) {
        Lang.AUTO -> null
        Lang.GUJARATI -> "gu-IN"
        Lang.HINDI -> "hi-IN"
        Lang.ENGLISH -> "en-IN"
        Lang.HINGLISH -> "hi-IN"
    }

    private fun errorText(code: Int): String = when (code) {
        SpeechRecognizer.ERROR_NETWORK_TIMEOUT, SpeechRecognizer.ERROR_NETWORK ->
            "Speech recognition couldn't reach the network. Your phone's speech service may need internet."
        SpeechRecognizer.ERROR_AUDIO -> "There was a problem with the microphone."
        SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "Microphone permission is not allowed."
        SpeechRecognizer.ERROR_NO_MATCH, SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "I didn't catch that. Please try again."
        SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "The speech service is busy. Please try again."
        12, 13 -> "This language isn't supported or downloaded for speech recognition on this phone."
        else -> "Speech recognition failed (code $code)."
    }

    // onDeviceOnly = true (Private Mode): listen only with the on-device recogniser, or refuse. Never falls back to online.
    fun startListening(
        lang: Lang,
        partial: (String) -> Unit,
        done: (String) -> Unit,
        fail: (String, Boolean) -> Unit,
        onDeviceOnly: Boolean = false
    ) {
        silence()
        if (onDeviceOnly) {
            if (!onDeviceAvailable()) {
                fail("Private Mode: this phone can't listen on-device, so I won't listen. Type your message, or turn Private Mode off.", false)
                return
            }
        } else if (!recognitionAvailable()) {
            fail("Speech recognition is not available on this phone.", true)
            return
        }
        recognizer?.destroy()
        val rec = if (onDeviceOnly) SpeechRecognizer.createOnDeviceSpeechRecognizer(ctx)
        else SpeechRecognizer.createSpeechRecognizer(ctx)
        recognizer = rec
        rec.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) {}
            override fun onBeginningOfSpeech() {}
            override fun onRmsChanged(rmsdB: Float) {}
            override fun onBufferReceived(buffer: ByteArray?) {}
            override fun onEndOfSpeech() {}
            override fun onEvent(eventType: Int, params: Bundle?) {}
            override fun onError(error: Int) {
                val serious = error != SpeechRecognizer.ERROR_NO_MATCH && error != SpeechRecognizer.ERROR_SPEECH_TIMEOUT
                val hint = if (onDeviceOnly && (error == 12 || error == 13))
                    " For Private Mode, download this language for offline use in your phone's speech settings."
                else ""
                fail(errorText(error) + hint, serious)
            }
            override fun onResults(results: Bundle?) {
                val text = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull() ?: ""
                if (text.isBlank()) fail("I didn't catch that. Please try again.", false) else done(text)
            }
            override fun onPartialResults(partialResults: Bundle?) {
                val text = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull() ?: ""
                if (text.isNotBlank()) partial(text)
            }
        })
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            val tag = recogTag(lang)
            if (tag != null) putExtra(RecognizerIntent.EXTRA_LANGUAGE, tag)
        }
        rec.startListening(intent)
    }

    fun stopListening() {
        recognizer?.stopListening()
    }

    fun release() {
        silence()
        tts?.shutdown()
        recognizer?.destroy()
        recognizer = null
    }
}
