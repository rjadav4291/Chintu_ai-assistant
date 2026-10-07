package com.chintu.assistant

import android.content.ContentResolver
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Matrix
import android.media.ExifInterface
import android.net.Uri
import android.util.Base64
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL
import org.json.JSONArray
import org.json.JSONObject

class VisionException(message: String) : Exception(message)

// One prepared picture: a small JPEG (location data removed) ready to send, plus a thumbnail for the screen.
class VisionImage(val label: String, val b64: String, val width: Int, val height: Int, val kb: Int, val thumb: Bitmap)

const val VISION_SYSTEM =
    "You look at pictures the user chose to send you. Answer using only what is actually visible. " +
        "If something cannot be determined from the picture (for example text that is too small or blurred, parts that are cut off, " +
        "who a person is, an exact place, or anything outside the picture), say so clearly instead of guessing. " +
        "Never identify real people from their faces. When you read text from a picture, copy it faithfully and say which part is unreadable. " +
        "Reply in the same language as the user's question. Keep answers short unless asked for detail. " +
        "You only describe the picture. You did not do anything else."

object VisionCore {
    private const val MAX_SIDE = 1280

    // Shrinks, flattens on white (so transparent images don't turn black) and re-saves as JPEG. This drops all metadata, including GPS.
    fun fromBitmap(src: Bitmap, label: String): VisionImage {
        val w = src.width
        val h = src.height
        val scale = minOf(1f, MAX_SIDE.toFloat() / maxOf(w, h))
        val nw = maxOf(1, (w * scale).toInt())
        val nh = maxOf(1, (h * scale).toInt())
        val scaled = if (scale < 1f) Bitmap.createScaledBitmap(src, nw, nh, true) else src
        val flat = Bitmap.createBitmap(nw, nh, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(flat)
        canvas.drawColor(android.graphics.Color.WHITE)
        canvas.drawBitmap(scaled, 0f, 0f, null)
        val out = ByteArrayOutputStream()
        flat.compress(Bitmap.CompressFormat.JPEG, 80, out)
        val bytes = out.toByteArray()
        val ts = minOf(1f, 240f / maxOf(nw, nh))
        val thumb = Bitmap.createScaledBitmap(flat, maxOf(1, (nw * ts).toInt()), maxOf(1, (nh * ts).toInt()), true)
        return VisionImage(label, Base64.encodeToString(bytes, Base64.NO_WRAP), nw, nh, bytes.size / 1024, thumb)
    }

    private fun orientation(cr: ContentResolver, uri: Uri): Int {
        return try {
            cr.openInputStream(uri)?.use {
                ExifInterface(it).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
            } ?: ExifInterface.ORIENTATION_NORMAL
        } catch (e: Exception) {
            ExifInterface.ORIENTATION_NORMAL
        }
    }

    private fun rotate(bmp: Bitmap, o: Int): Bitmap {
        val m = Matrix()
        when (o) {
            ExifInterface.ORIENTATION_ROTATE_90 -> m.postRotate(90f)
            ExifInterface.ORIENTATION_ROTATE_180 -> m.postRotate(180f)
            ExifInterface.ORIENTATION_ROTATE_270 -> m.postRotate(270f)
            else -> return bmp
        }
        return Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, m, true)
    }

    fun loadImage(ctx: Context, uri: Uri): VisionImage {
        val cr = ctx.contentResolver
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        val stream = cr.openInputStream(uri) ?: throw VisionException("I couldn't open that picture.")
        stream.use { BitmapFactory.decodeStream(it, null, bounds) }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) throw VisionException("That file isn't a picture I can read.")
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / sample > MAX_SIDE * 2) sample *= 2
        val opts = BitmapFactory.Options().apply { inSampleSize = sample }
        val stream2 = cr.openInputStream(uri) ?: throw VisionException("I couldn't open that picture.")
        val raw = stream2.use { BitmapFactory.decodeStream(it, null, opts) }
            ?: throw VisionException("I couldn't read that picture.")
        return fromBitmap(rotate(raw, orientation(cr, uri)), "Picture")
    }

    private fun post(url: String, apiKey: String, body: String): String {
        val conn = URL(url).openConnection() as HttpURLConnection
        try {
            conn.instanceFollowRedirects = false
            conn.requestMethod = "POST"
            conn.connectTimeout = 20000
            conn.readTimeout = 120000
            conn.doOutput = true
            conn.setRequestProperty("Content-Type", "application/json")
            conn.setRequestProperty("Authorization", "Bearer $apiKey")
            conn.outputStream.use { it.write(body.toByteArray()) }
            val code = conn.responseCode
            if (code in 300..399) {
                throw VisionException("The server tried to redirect me (HTTP $code). I did not follow it, to protect your key and picture.")
            }
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val text = stream?.bufferedReader()?.use { it.readText() } ?: ""
            if (code !in 200..299) {
                val detail = try { JSONObject(text).getJSONObject("error").getString("message") } catch (e: Exception) { text.take(200) }
                throw VisionException("HTTP $code $detail")
            }
            return text
        } finally {
            conn.disconnect()
        }
    }

    // Sends the picture(s) and the question to the online AI. Throws on any failure; never invents an answer.
    fun ask(baseUrl: String, apiKey: String, model: String, question: String, images: List<VisionImage>): String {
        val content = JSONArray()
        content.put(JSONObject().put("type", "text").put("text", question))
        for (im in images) {
            content.put(
                JSONObject().put("type", "image_url")
                    .put("image_url", JSONObject().put("url", "data:image/jpeg;base64," + im.b64))
            )
        }
        val msgs = JSONArray()
        msgs.put(JSONObject().put("role", "system").put("content", VISION_SYSTEM))
        msgs.put(JSONObject().put("role", "user").put("content", content))
        val body = JSONObject().put("model", model).put("max_completion_tokens", 1500).put("messages", msgs)
        val text = try {
            post(baseUrl.trim().trimEnd('/') + "/chat/completions", apiKey, body.toString())
        } catch (e: VisionException) {
            val m = e.message ?: ""
            if (m.startsWith("HTTP 4")) {
                throw VisionException(m + "\nThis model may not accept pictures. In Settings, choose a model that can see images.")
            }
            throw e
        }
        val msg = JSONObject(text).getJSONArray("choices").getJSONObject(0).getJSONObject("message")
        val c = msg.opt("content")
        val out = when (c) {
            is String -> c
            is JSONArray -> {
                val sb = StringBuilder()
                for (i in 0 until c.length()) {
                    val p = c.optJSONObject(i)
                    if (p != null && p.optString("type") == "text") sb.append(p.optString("text"))
                }
                sb.toString()
            }
            else -> ""
        }.trim()
        if (out.isEmpty()) throw VisionException("The AI returned an empty answer.")
        return out
    }
}

// END OF FILE
