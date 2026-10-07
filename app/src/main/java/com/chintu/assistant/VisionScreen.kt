package com.chintu.assistant

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
import java.io.IOException

@Composable
fun VisionScreen(
    baseUrl: String,
    apiKey: String,
    model: String,
    mode: AiMode,
    privateOn: Boolean,
    back: () -> Unit
) {
    val ctx = LocalContext.current
    val host = hostOf(baseUrl)
    val images = remember { mutableStateListOf<VisionImage>() }
    var question by remember { mutableStateOf("") }
    var answer by remember { mutableStateOf("") }
    var msg by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    val blocked = privateOn || mode == AiMode.OFFLINE
    val notReady = apiKey.isBlank() || model.isBlank()

    fun load(uris: List<Uri>) {
        busy = true
        msg = "Preparing the picture(s)..."
        Thread {
            val out = mutableListOf<VisionImage>()
            var err: String? = null
            try {
                for (u in uris) out.add(VisionCore.loadImage(ctx, u))
            } catch (e: VisionException) {
                err = e.message
            } catch (e: OutOfMemoryError) {
                err = "That picture is too big for this phone's memory."
            } catch (e: Exception) {
                err = "I couldn't read that picture: ${e.javaClass.simpleName}"
            }
            Handler(Looper.getMainLooper()).post {
                busy = false
                val room = 3 - images.size
                images.addAll(out.take(room))
                msg = err ?: (if (out.size > room) "Only 3 pictures can be sent at once." else "")
            }
        }.start()
    }

    fun ask() {
        if (images.isEmpty()) {
            msg = "Choose a picture first."
            return
        }
        busy = true
        answer = ""
        msg = "● ONLINE · sending to $host ..."
        PrivacyLog.record(host)
        val q = question.trim().ifEmpty { "Describe this picture." }
        val imgs = images.toList()
        Thread {
            var res: String? = null
            var err: String? = null
            try {
                res = VisionCore.ask(baseUrl, apiKey, model, q, imgs)
            } catch (e: VisionException) {
                err = e.message
            } catch (e: IOException) {
                err = "Internet is required for this task. I couldn't connect, so I did not look at the picture.\nDetails: ${e.javaClass.simpleName}: ${e.message}"
            } catch (e: Exception) {
                err = "Something went wrong, so I did not look at the picture: ${e.javaClass.simpleName}: ${e.message}"
            }
            val r = res
            val er = err
            Handler(Looper.getMainLooper()).post {
                busy = false
                if (r != null) {
                    answer = r
                    msg = "Answer from the online AI, via $host. This app did not store the picture."
                } else {
                    msg = er ?: "Unknown error"
                }
            }
        }.start()
    }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.PickMultipleVisualMedia(3)) { uris ->
        if (uris.isNotEmpty()) load(uris)
    }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        TextButton(onClick = back) { Text("Back") }
        Text("Vision", fontSize = 22.sp, color = Color.White)
        if (blocked) {
            Text(
                "Vision is blocked while ${if (privateOn) "Private Mode" else "OFFLINE mode"} is on. This phone is too small to understand pictures by itself, so the picture would have to be sent to $host. Change the mode, then come back.",
                color = Red
            )
        } else if (notReady) {
            Text("Vision needs an online AI. Add your API key and model in Settings first.", color = Red)
        } else {
            Text(
                "I can look at photos, screenshots and app screens, and read the text in them. The picture is understood by the online AI, not by this phone. It is sent only when you press the Send button below.",
                color = Dim
            )
            Button(onClick = {
                picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
            }, enabled = !busy && images.size < 3) { Text("Choose pictures (up to 3)") }
            images.forEachIndexed { i, im ->
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Image(
                        bitmap = im.thumb.asImageBitmap(),
                        contentDescription = "Chosen picture ${i + 1}",
                        modifier = Modifier.height(90.dp).widthIn(max = 150.dp),
                        contentScale = ContentScale.Fit
                    )
                    Column {
                        Text("Picture ${i + 1}: ${im.width} x ${im.height}, ${im.kb} KB", color = Dim)
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
                    question = "Read all the text in this picture exactly. Tell me which part is unreadable."
                }) { Text("Read text", fontSize = 12.sp) }
                TextButton(onClick = {
                    question = "This is a screenshot of an app screen. Explain what is on it and what I can do on it."
                }) { Text("Screenshot", fontSize = 12.sp) }
            }
            Button(onClick = { ask() }, enabled = !busy && images.isNotEmpty()) { Text("Send to $host") }
            Text(
                "Pressing Send sends the picture(s) and your question to $host, and it may pass them on to the model provider. Location data inside photos is removed first. Nothing is kept on this phone after you leave this screen.",
                color = Dim
            )
            if (images.isNotEmpty()) {
                OutlinedButton(onClick = {
                    images.clear()
                    answer = ""
                    msg = ""
                }, enabled = !busy) { Text("Clear pictures") }
            }
            if (msg.isNotEmpty()) Text(msg, color = if (answer.isEmpty() && !busy) Red else Amber)
            if (answer.isNotEmpty()) {
                Text("Answer", fontSize = 18.sp, color = Color.White)
                SelectionContainer { Text(answer, color = Color.White) }
                Text("Press and hold the answer to copy it. The AI can be wrong, and it has been told to say when something can't be seen.", color = Dim)
            }
        }
    }
}

// END OF FILE
