package com.chintu.assistant

import android.content.Context
import android.net.Uri
import android.os.Handler
import android.os.Looper
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import java.io.IOException

@Composable
fun VisionScreen(
    baseUrl: String,
    apiKey: String,
    model: String,
    mode: AiMode,
    privateOn: Boolean,
    back: () -> Unit,
    vm: ChatVm = viewModel()
) {
    val ctx = LocalContext.current
    val host = hostOf(baseUrl)
    val prefs = remember { ctx.getSharedPreferences("chintu", Context.MODE_PRIVATE) }
    val images = remember { mutableStateListOf<VisionImage>() }
    var question by remember { mutableStateOf("") }
    var lastQuestion by remember { mutableStateOf("") }
    var answer by remember { mutableStateOf("") }
    var msg by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var visionModel by remember { mutableStateOf(prefs.getString("vision_model", "") ?: "") }
    var modelDraft by remember { mutableStateOf(visionModel) }
    var cap by remember { mutableStateOf<Boolean?>(null) }
    var capDone by remember { mutableStateOf(false) }
    var found by remember { mutableStateOf<List<String>>(emptyList()) }
    var findMsg by remember { mutableStateOf("") }
    val blocked = privateOn || mode == AiMode.OFFLINE
    val notReady = apiKey.isBlank() || model.isBlank()
    val useModel = visionModel.ifBlank { model }

    // Asks the provider whether this model accepts pictures. This sends no picture, only the model list request.
    LaunchedEffect(useModel, baseUrl, apiKey, blocked, notReady) {
        cap = null
        capDone = false
        if (blocked || notReady) return@LaunchedEffect
        PrivacyLog.record(host)
        Thread {
            var r: Boolean? = null
            try {
                r = VisionExtras.acceptsImages(baseUrl, apiKey, useModel)
            } catch (e: Exception) {
            }
            val res = r
            Handler(Looper.getMainLooper()).post {
                cap = res
                capDone = true
            }
        }.start()
    }

    fun load(uris: List<Uri>) {
        busy = true
        msg = "Preparing..."
        Thread {
            val out = mutableListOf<VisionImage>()
            var note = ""
            var err: String? = null
            try {
                for (u in uris) {
                    val type = ctx.contentResolver.getType(u) ?: ""
                    if (type == "application/pdf") {
                        val r = VisionExtras.loadPdf(ctx, u)
                        out.addAll(r.images)
                        if (r.totalPages > r.images.size) {
                            note = "That PDF has ${r.totalPages} pages. Only the first ${r.images.size} are used."
                        }
                    } else {
                        out.add(VisionCore.loadImage(ctx, u))
                    }
                }
            } catch (e: VisionException) {
                err = e.message
            } catch (e: OutOfMemoryError) {
                err = "That file is too big for this phone's memory."
            } catch (e: Exception) {
                err = "I couldn't read that file: ${e.javaClass.simpleName}"
            }
            Handler(Looper.getMainLooper()).post {
                busy = false
                val room = 3 - images.size
                images.addAll(out.take(room))
                msg = err ?: (if (out.size > room) "Only 3 pictures or pages can be sent at once." else note)
            }
        }.start()
    }

    fun ask() {
        if (images.isEmpty()) {
            msg = "Choose a picture or document first."
            return
        }
        if (cap == false) {
            msg = "The provider lists $useModel as text-only, so I did not send anything. Choose a picture-capable model below."
            return
        }
        busy = true
        answer = ""
        msg = "● ONLINE · sending to $host ..."
        PrivacyLog.record(host)
        val q = question.trim().ifEmpty { "Describe this picture." }
        lastQuestion = q
        val imgs = images.toList()
        val m = useModel
        Thread {
            var res: String? = null
            var err: String? = null
            try {
                res = VisionCore.ask(baseUrl, apiKey, m, q, imgs)
            } catch (e: VisionException) {
                err = e.message
            } catch (e: IOException) {
                err = "Internet is required for this task. I couldn't connect, so I did not look at it.\nDetails: ${e.javaClass.simpleName}: ${e.message}"
            } catch (e: Exception) {
                err = "Something went wrong, so I did not look at it: ${e.javaClass.simpleName}: ${e.message}"
            }
            val r = res
            val er = err
            Handler(Looper.getMainLooper()).post {
                busy = false
                if (r != null) {
                    answer = r
                    msg = "Answer from the online AI ($m), via $host. This app did not store the picture."
                } else {
                    msg = er ?: "Unknown error"
                }
            }
        }.start()
    }

    fun find() {
        busy = true
        findMsg = "Looking..."
        PrivacyLog.record(host)
        Thread {
            var list: List<String> = emptyList()
            var err: String? = null
            try {
                list = VisionExtras.imageModels(baseUrl, apiKey)
            } catch (e: IOException) {
                err = "Internet is required for this."
            } catch (e: Exception) {
                err = "I couldn't get the list: ${e.message}"
            }
            val l = list
            val er = err
            Handler(Looper.getMainLooper()).post {
                busy = false
                found = l.take(30)
                findMsg = er ?: (if (l.isEmpty()) "This provider doesn't say which models accept pictures. You can type a model ID yourself." else "Picture-capable models (tap one):")
            }
        }.start()
    }

    fun saveModel(id: String) {
        val clean = id.trim()
        if (prefs.edit().putString("vision_model", clean).commit()) {
            visionModel = clean
            modelDraft = clean
            msg = ""
        } else {
            msg = "I couldn't save that."
        }
    }

    val photoPicker = rememberLauncherForActivityResult(ActivityResultContracts.PickMultipleVisualMedia(3)) { uris ->
        if (uris.isNotEmpty()) load(uris)
    }
    val docPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) load(listOf(uri))
    }

    val capText = when {
        !capDone -> "Checking whether $useModel accepts pictures..."
        cap == true -> "The provider says $useModel accepts pictures."
        cap == false -> "The provider lists $useModel as text-only. Choose a picture-capable model below."
        else -> "I can't tell whether $useModel accepts pictures. I'll try, and show the provider's answer."
    }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        TextButton(onClick = back) { Text("Back") }
        Text("Vision", fontSize = 22.sp, color = Color.White)
        if (blocked) {
            Text(
                "Vision is blocked while ${if (privateOn) "Private Mode" else "OFFLINE mode"} is on. This phone is too small to understand pictures by itself, so they would have to be sent to $host. Change the mode, then come back.",
                color = Red
            )
        } else if (notReady) {
            Text("Vision needs an online AI. Add your API key and model in Settings first.", color = Red)
        } else {
            Text(
                "I can look at photos, screenshots, app screens and PDF documents, and read the text in them. They are understood by the online AI, not by this phone, and sent only when you press the Send button.",
                color = Dim
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = {
                    photoPicker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                }, enabled = !busy && images.size < 3) { Text("Pictures") }
                OutlinedButton(onClick = {
                    docPicker.launch(arrayOf("application/pdf", "image/*"))
                }, enabled = !busy && images.size < 3) { Text("PDF / document") }
            }
            images.forEachIndexed { i, im ->
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Image(
                        bitmap = im.thumb.asImageBitmap(),
                        contentDescription = "Chosen item ${i + 1}",
                        modifier = Modifier.height(90.dp).widthIn(max = 150.dp),
                        contentScale = ContentScale.Fit
                    )
                    Column {
                        Text("${i + 1}. ${im.label}: ${im.width} x ${im.height}, ${im.kb} KB", color = Dim)
                        TextButton(onClick = { images.removeAt(i) }, enabled = !busy) { Text("Remove") }
                    }
                }
            }
            OutlinedTextField(
                question, { question = it },
                label = { Text("Your question (optional)") },
                modifier = Modifier.fillMaxWidth()
            )
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                TextButton(onClick = { question = "Describe this picture." }) { Text("Describe", fontSize = 12.sp) }
                TextButton(onClick = {
                    question = "Read all the text exactly. Tell me which part is unreadable."
                }) { Text("Read text", fontSize = 12.sp) }
                TextButton(onClick = {
                    question = "This is a screenshot of an app screen. Explain what is on it and what I can do on it."
                }) { Text("Screenshot", fontSize = 12.sp) }
                TextButton(onClick = {
                    question = "Summarise this document in a few short points."
                }) { Text("Summarise", fontSize = 12.sp) }
            }
            Text(capText, color = if (cap == false) Red else Dim)
            Button(onClick = { ask() }, enabled = !busy && images.isNotEmpty()) { Text("Send to $host") }
            Text(
                "Pressing Send sends the picture(s) or document pages and your question to $host, and it may pass them on to the model provider. Location data inside photos is removed first. Documents can contain private details, so send only what you are happy to share. Nothing is kept on this phone after you leave this screen.",
                color = Dim
            )
            if (images.isNotEmpty()) {
                OutlinedButton(onClick = {
                    images.clear()
                    answer = ""
                    msg = ""
                }, enabled = !busy) { Text("Clear all") }
            }
            if (msg.isNotEmpty()) Text(msg, color = if (answer.isEmpty() && !busy) Red else Amber)
            if (answer.isNotEmpty()) {
                Text("Answer", fontSize = 18.sp, color = Color.White)
                SelectionContainer { Text(answer, color = Color.White) }
                Text("Press and hold the answer to copy it. The AI can be wrong, and it has been told to say when something can't be seen.", color = Dim)
                OutlinedButton(onClick = {
                    vm.messages.add(Msg(true, "[Picture] $lastQuestion"))
                    vm.messages.add(Msg(false, answer))
                    vm.source = "VISION: $host"
                    msg = "Added to the chat as text. The picture itself is not saved."
                }) { Text("Add this answer to the chat") }
            }

            Text("Model for pictures", fontSize = 18.sp, color = Color.White)
            Text("Now using: $useModel" + (if (visionModel.isBlank()) " (same as the chat model)" else ""), color = Dim)
            OutlinedTextField(
                modelDraft, { modelDraft = it },
                label = { Text("Picture model ID (empty = chat model)") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { saveModel(modelDraft) }) { Text("Save") }
                OutlinedButton(onClick = { find() }, enabled = !busy) { Text("Find picture models") }
            }
            if (findMsg.isNotEmpty()) Text(findMsg, color = Dim)
            found.forEach { id ->
                TextButton(onClick = { saveModel(id) }) { Text(id) }
            }
        }
    }
}

// END OF FILE
