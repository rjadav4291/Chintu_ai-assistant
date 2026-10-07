package com.chintu.assistant

import android.content.Context
import android.graphics.Bitmap
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import org.json.JSONObject

class PdfResult(val images: List<VisionImage>, val totalPages: Int)

object VisionExtras {
    private const val MAX_PAGES = 3

    // Turns the first pages of a PDF into pictures. Nothing is saved.
    fun loadPdf(ctx: Context, uri: Uri): PdfResult {
        val pfd = ctx.contentResolver.openFileDescriptor(uri, "r")
            ?: throw VisionException("I couldn't open that PDF.")
        val result: PdfResult = try {
            pfd.use { fd ->
                PdfRenderer(fd).use { renderer ->
                    val total = renderer.pageCount
                    if (total <= 0) throw VisionException("That PDF has no pages.")
                    val out = mutableListOf<VisionImage>()
                    for (i in 0 until minOf(total, MAX_PAGES)) {
                        renderer.openPage(i).use { page ->
                            val s = minOf(2f, 2000f / maxOf(page.width, page.height))
                            val w = maxOf(1, (page.width * s).toInt())
                            val h = maxOf(1, (page.height * s).toInt())
                            val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
                            bmp.eraseColor(android.graphics.Color.WHITE)
                            page.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                            out.add(VisionCore.fromBitmap(bmp, "PDF page ${i + 1}"))
                        }
                    }
                    PdfResult(out, total)
                }
            }
        } catch (e: SecurityException) {
            throw VisionException("That PDF is password-protected, so I can't open it.")
        } catch (e: IOException) {
            throw VisionException("I couldn't read that PDF.")
        }
        return result
    }

    private fun get(url: String, apiKey: String): String {
        val conn = URL(url).openConnection() as HttpURLConnection
        try {
            conn.instanceFollowRedirects = false
            conn.connectTimeout = 15000
            conn.readTimeout = 30000
            conn.setRequestProperty("Authorization", "Bearer $apiKey")
            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val text = stream?.bufferedReader()?.use { it.readText() } ?: ""
            if (code !in 200..299) throw VisionException("HTTP $code ${text.take(150)}")
            return text
        } finally {
            conn.disconnect()
        }
    }

    // true = the provider says the model accepts pictures, false = it lists the model as text-only, null = it doesn't say.
    fun acceptsImages(baseUrl: String, apiKey: String, model: String): Boolean? {
        val data = JSONObject(get(baseUrl.trim().trimEnd('/') + "/models", apiKey)).optJSONArray("data") ?: return null
        for (i in 0 until data.length()) {
            val o = data.optJSONObject(i) ?: continue
            if (o.optString("id") != model) continue
            val mods = o.optJSONObject("architecture")?.optJSONArray("input_modalities") ?: return null
            for (j in 0 until mods.length()) {
                if (mods.optString(j) == "image") return true
            }
            return false
        }
        return null
    }

    // Models the provider says accept pictures (empty if the provider doesn't say).
    fun imageModels(baseUrl: String, apiKey: String): List<String> {
        val data = JSONObject(get(baseUrl.trim().trimEnd('/') + "/models", apiKey)).optJSONArray("data")
            ?: return emptyList()
        val out = mutableListOf<String>()
        for (i in 0 until data.length()) {
            val o = data.optJSONObject(i) ?: continue
            val id = o.optString("id")
            if (id.isEmpty() || id.endsWith(":batch")) continue
            val mods = o.optJSONObject("architecture")?.optJSONArray("input_modalities") ?: continue
            for (j in 0 until mods.length()) {
                if (mods.optString(j) == "image") {
                    out.add(id)
                    break
                }
            }
        }
        return out.sorted()
    }
}

// END OF FILE
