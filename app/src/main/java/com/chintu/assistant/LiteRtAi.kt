package com.chintu.assistant

import android.app.ActivityManager
import android.content.Context
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.Contents
import com.google.ai.edge.litertlm.ConversationConfig
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.EngineConfig
import com.google.ai.edge.litertlm.Message
import com.google.ai.edge.litertlm.SamplerConfig
import java.io.File

// Runs a .litertlm model on the phone itself (CPU). The model file stays in the app's private storage.
class LiteRtLocalAi(private val ctx: Context) : LocalAi {
    private val prefs = ctx.getSharedPreferences("chintu_local_ai", Context.MODE_PRIVATE)
    private val dir = File(ctx.filesDir, "models")
    private var engine: Engine? = null
    private var enginePath: String? = null

    @Volatile
    var lastError: String? = null

    fun modelFile(): File? {
        val n = prefs.getString("model_name", null) ?: return null
        val f = File(dir, n)
        return if (f.isFile && f.length() > 0L) f else null
    }

    override val available: Boolean
        get() = modelFile() != null && lastError == null

    override fun status(): String {
        val f = modelFile() ?: return "no local AI model is set up yet."
        val err = lastError
        return if (err != null) "model ${f.name} is imported, but the last attempt to run it failed: $err"
        else "model ${f.name} is ready."
    }

    @Synchronized
    private fun engineFor(f: File): Engine {
        val cur = engine
        if (cur != null && enginePath == f.absolutePath) return cur
        closeEngine()
        val e = Engine(EngineConfig(modelPath = f.absolutePath, backend = Backend.CPU(), cacheDir = ctx.cacheDir.path))
        e.initialize()
        engine = e
        enginePath = f.absolutePath
        return e
    }

    @Synchronized
    private fun closeEngine() {
        try {
            engine?.close()
        } catch (e: Exception) {
        }
        engine = null
        enginePath = null
    }

    // Frees the model from RAM without blocking the screen.
    fun releaseAsync() {
        Thread { closeEngine() }.start()
    }

    // The online prompt is long; this small model has a short memory window, so keep only what matters.
    private fun compactSystem(system: String): String {
        val head = system.lines().take(4).joinToString("\n")
        val i = system.indexOf("Things the user asked you to remember")
        val mem = if (i >= 0) "\n" + system.substring(i).take(900) else ""
        return head +
            "\nYou are a small offline model. You cannot open apps, set timers or alarms, save notes or memories, search the web or control the phone, and you must never claim you did. Keep answers very short." +
            mem
    }

    @Synchronized
    override fun generate(system: String, turns: List<Pair<String, String>>): String {
        val f = modelFile() ?: throw RouteException("Offline AI is unavailable: no local AI model is set up yet.")
        val last = turns.lastOrNull()
        if (last == null || last.first != "user") throw RouteException("There is no question to answer.")
        try {
            val eng = engineFor(f)
            val history = turns.dropLast(1).takeLast(6).map {
                if (it.first == "user") Message.user(it.second) else Message.model(it.second)
            }
            val cfg = ConversationConfig(
                systemInstruction = Contents.of(compactSystem(system)),
                initialMessages = history,
                samplerConfig = SamplerConfig(topK = 10, topP = 0.95, temperature = 0.8)
            )
            val result = eng.createConversation(cfg).use { conv ->
                val raw = conv.sendMessage(last.second, extraContext = mapOf("enable_thinking" to false)).toString()
                raw.replace(Regex("(?s)<think>.*?</think>"), "").replace("<think>", "").replace("</think>", "").trim()
            }
            if (result.isEmpty()) throw RouteException("The offline AI returned an empty reply.")
            lastError = null
            return result
        } catch (e: RouteException) {
            throw e
        } catch (e: Exception) {
            val msg = "${e.javaClass.simpleName}: ${e.message}"
            lastError = msg
            closeEngine()
            throw RouteException("The offline AI failed.\nDetails: $msg")
        }
    }

    // Returns null on success, otherwise an honest error message.
    fun importModel(uri: Uri, onProgress: (Long, Long) -> Unit): String? {
        val cr = ctx.contentResolver
        var name = "model.litertlm"
        var size = -1L
        cr.query(uri, null, null, null, null)?.use { c ->
            val ni = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            val si = c.getColumnIndex(OpenableColumns.SIZE)
            if (c.moveToFirst()) {
                if (ni >= 0) name = c.getString(ni) ?: name
                if (si >= 0 && !c.isNull(si)) size = c.getLong(si)
            }
        }
        if (!name.endsWith(".litertlm")) return "That file is not a .litertlm model ($name)."
        name = name.replace(Regex("[^A-Za-z0-9._-]"), "_")
        dir.mkdirs()
        if (size > 0 && dir.usableSpace < size + 50L * 1024 * 1024) return "Not enough free storage to copy the model."
        val tmp = File(dir, "$name.part")
        val dest = File(dir, name)
        try {
            val input = cr.openInputStream(uri) ?: return "I couldn't open that file."
            var copied = 0L
            input.use { ins ->
                tmp.outputStream().use { outs ->
                    val buf = ByteArray(1024 * 1024)
                    while (true) {
                        val n = ins.read(buf)
                        if (n < 0) break
                        outs.write(buf, 0, n)
                        copied += n
                        onProgress(copied, size)
                    }
                }
            }
            if (copied == 0L || (size > 0 && copied != size)) {
                tmp.delete()
                return "The copy was incomplete, so I did not use it."
            }
            closeEngine()
            val old = modelFile()
            if (old != null && old.name != name) old.delete()
            dest.delete()
            if (!tmp.renameTo(dest)) {
                tmp.delete()
                return "I couldn't save the model file."
            }
            if (!prefs.edit().putString("model_name", name).commit()) return "I couldn't save the model setting."
            lastError = null
            return null
        } catch (e: Exception) {
            tmp.delete()
            return "Copy failed: ${e.javaClass.simpleName}: ${e.message}"
        }
    }

    // True only if the model is really gone afterwards.
    fun deleteModel(): Boolean {
        closeEngine()
        modelFile()?.delete()
        prefs.edit().remove("model_name").commit()
        lastError = null
        return modelFile() == null
    }

    fun ramInfo(): String {
        val am = ctx.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val mi = ActivityManager.MemoryInfo()
        am.getMemoryInfo(mi)
        val gb = 1024.0 * 1024.0 * 1024.0
        return "Phone RAM: %.1f GB total, %.1f GB free right now.".format(mi.totalMem / gb, mi.availMem / gb)
    }
}

@Composable
fun ModelScreen(ai: LiteRtLocalAi, back: () -> Unit) {
    var rev by remember { mutableStateOf(0) }
    var msg by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    val file = remember(rev) { ai.modelFile() }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            busy = true
            msg = "Copying the model file..."
            Thread {
                val handler = Handler(Looper.getMainLooper())
                val err = ai.importModel(uri) { done, total ->
                    handler.post {
                        val mb = done / (1024 * 1024)
                        msg = if (total > 0) "Copying... $mb MB of ${total / (1024 * 1024)} MB" else "Copying... $mb MB"
                    }
                }
                handler.post {
                    busy = false
                    rev++
                    msg = err ?: "Model imported. Tap \"Test model\" to check that it really runs."
                }
            }.start()
        }
    }
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        TextButton(onClick = back) { Text("Back") }
        Text("Offline AI model", fontSize = 22.sp, color = Color.White)
        Text(ai.ramInfo(), color = Dim)
        Text(
            if (file == null) "No model imported yet."
            else "Model: ${file.name} (${file.length() / (1024 * 1024)} MB)",
            color = if (file == null) Red else Cyan
        )
        val err = ai.lastError
        if (file != null && err != null) Text("Last problem: $err", color = Red)
        Button(onClick = { picker.launch(arrayOf("*/*")) }, enabled = !busy) { Text("Choose model file (.litertlm)") }
        OutlinedButton(
            onClick = {
                busy = true
                msg = "Loading the model and answering. This can take a while on a small phone..."
                Thread {
                    val t0 = System.currentTimeMillis()
                    val result = try {
                        val r = ai.generate(
                            "You are a helpful assistant. Answer in one short sentence.",
                            listOf("user" to "Say hello and tell me your name is Chintu.")
                        )
                        "It works. Reply after ${(System.currentTimeMillis() - t0) / 1000} s: $r"
                    } catch (e: Exception) {
                        e.message ?: "The test failed."
                    }
                    Handler(Looper.getMainLooper()).post {
                        busy = false
                        rev++
                        msg = result
                    }
                }.start()
            },
            enabled = file != null && !busy
        ) { Text("Test model") }
        if (file != null) {
            if (confirmDelete) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = {
                        msg = if (ai.deleteModel()) "Model deleted." else "I couldn't delete the model."
                        confirmDelete = false
                        rev++
                    }) { Text("Yes, delete") }
                    OutlinedButton(onClick = { confirmDelete = false }) { Text("Cancel") }
                }
            } else {
                OutlinedButton(onClick = { confirmDelete = true }, enabled = !busy) { Text("Delete model") }
            }
        }
        if (msg.isNotEmpty()) Text(msg, color = Dim)
        Text("How to get a model:", fontSize = 18.sp, color = Color.White)
        Text(
            "1. On Wi-Fi, open huggingface.co/litert-community/Qwen3-0.6B in Chrome.\n" +
                "2. Open Files and download qwen3_0_6b_mixed_int4.litertlm (about 475 MB).\n" +
                "3. Come back here, tap Choose model file, and pick it from Downloads.",
            color = Dim
        )
        Text(
            "Honest limits: this is a very small model. It is slow on phones with little RAM, it is weak in Gujarati, and it can make mistakes. If the app closes when you use it, the model is too heavy for this phone, so delete it. The model file stays on this phone, and in OFFLINE mode nothing is sent anywhere.",
            color = Dim
        )
    }
}
