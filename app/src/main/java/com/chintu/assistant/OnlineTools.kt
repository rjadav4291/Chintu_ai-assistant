package com.chintu.assistant

import android.content.Context
import android.net.Uri
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import org.json.JSONObject

class OnlineException(message: String) : Exception(message)

class Req(val kind: String, val arg: String)

class OnlineOut(val text: String, val ok: Boolean)

const val ONLINE_TOOLS_PROMPT =
    "The app itself (not you) fetches the weather, Wikipedia pages and web search results when the user says things like \"weather in Surat\", \"wikipedia Gandhi\" or \"search best time to visit Goa\". You cannot browse, search or know live data such as weather, news or prices. If the user asks for such a thing and you are seeing the message, say honestly that you did not look it up and suggest those phrases."

private fun now(): String = SimpleDateFormat("h:mm a", Locale.ENGLISH).format(Date())

private fun httpGet(url: String): String {
    val conn = URL(url).openConnection() as HttpURLConnection
    try {
        conn.connectTimeout = 15000
        conn.readTimeout = 20000
        conn.setRequestProperty("User-Agent", "ChintuAssistant/0.1 (Android app)")
        val code = conn.responseCode
        val stream = if (code in 200..299) conn.inputStream else conn.errorStream
        val text = stream?.bufferedReader()?.use { it.readText() } ?: ""
        if (code !in 200..299) throw OnlineException("HTTP $code ${text.take(150)}")
        return text
    } finally {
        conn.disconnect()
    }
}

private fun httpPostJson(url: String, bearer: String, body: String): String {
    val conn = URL(url).openConnection() as HttpURLConnection
    try {
        conn.requestMethod = "POST"
        conn.connectTimeout = 15000
        conn.readTimeout = 30000
        conn.doOutput = true
        conn.setRequestProperty("Content-Type", "application/json")
        conn.setRequestProperty("Authorization", "Bearer $bearer")
        conn.outputStream.use { it.write(body.toByteArray()) }
        val code = conn.responseCode
        val stream = if (code in 200..299) conn.inputStream else conn.errorStream
        val text = stream?.bufferedReader()?.use { it.readText() } ?: ""
        if (code !in 200..299) throw OnlineException("HTTP $code ${text.take(150)}")
        return text
    } finally {
        conn.disconnect()
    }
}

private fun describeCode(code: Int): String = when (code) {
    0 -> "clear sky"
    1 -> "mainly clear"
    2 -> "partly cloudy"
    3 -> "overcast"
    45, 48 -> "foggy"
    51, 53, 55 -> "drizzle"
    56, 57 -> "freezing drizzle"
    61 -> "light rain"
    63 -> "rain"
    65 -> "heavy rain"
    66, 67 -> "freezing rain"
    71, 73, 75, 77 -> "snow"
    80, 81, 82 -> "rain showers"
    85, 86 -> "snow showers"
    95 -> "thunderstorm"
    96, 99 -> "thunderstorm with hail"
    else -> "unknown conditions"
}

// Weather, Wikipedia and web search. Each answer comes straight from the service and names its source.
class OnlineTools(private val ctx: Context) {
    private val prefs = ctx.getSharedPreferences("chintu_tools", Context.MODE_PRIVATE)

    private val weatherWords = listOf(
        "weather", "mausam", "mosam", "temperature in", "temperature of",
        "હવામાન", "તાપમાન", "मौसम", "तापमान"
    )
    private val fillers = setOf(
        "what", "whats", "what's", "is", "the", "in", "at", "of", "for", "today", "now", "current", "tomorrow",
        "like", "how", "hows", "how's", "please", "tell", "me", "show", "get", "check", "will", "be", "it",
        "outside", "temperature", "aaj", "aaje", "kal", "ka", "ki", "ke", "kya", "hai", "hain", "kaisa", "kaise",
        "me", "mein", "nu", "ni", "no", "ma", "che", "chhe", "shu", "su", "kevu", "kevo",
        "आज", "का", "की", "में", "है", "क्या", "कैसा", "આજે", "આજનું", "નું", "ની", "માં", "છે", "શું", "કેવું"
    )
    private val wikiPrefixes = listOf(
        "search wikipedia for ", "search wikipedia ", "wikipedia ", "wiki ", "વિકિપીડિયા ", "विकिपीडिया "
    ).sortedByDescending { it.length }
    private val searchPrefixes = listOf(
        "search the web for ", "web search for ", "web search ", "search for ", "search ", "google ",
        "look up ", "સર્ચ ", "सर्च "
    ).sortedByDescending { it.length }

    fun savedCity(): String = prefs.getString("weather_city", "") ?: ""

    // Local command: remember a default city. No internet is used.
    fun setCity(raw: String): String? {
        val text = raw.trim()
        val low = text.lowercase(Locale.ROOT)
        for (p in listOf("set my city to ", "set my city as ", "my city is ", "set city to ", "set default city to ")) {
            if (low.startsWith(p)) {
                val city = text.drop(p.length).trim().trimEnd('.', '!')
                if (city.isEmpty() || city.length > 60) return "Which city?"
                return if (prefs.edit().putString("weather_city", city).commit() && savedCity() == city) {
                    "Okay, I'll use $city when you ask for the weather without a city."
                } else {
                    "I couldn't save the city."
                }
            }
        }
        return null
    }

    // Decides whether a message is a weather / Wikipedia / web search request.
    fun parse(raw: String): Req? {
        val text = raw.trim()
        val low = text.lowercase(Locale.ROOT)
        if (text.isEmpty() || text.length > 150) return null
        for (p in wikiPrefixes) {
            if (low.startsWith(p)) return Req("wiki", text.drop(p.length).trim())
        }
        for (p in searchPrefixes) {
            if (low.startsWith(p)) return Req("search", text.drop(p.length).trim())
        }
        if (weatherWords.any { low.contains(it) }) {
            val words = low.split(Regex("[\\s,.!?]+")).filter { it.isNotEmpty() && it !in fillers && it !in weatherWords }
            if (words.size > 3) return null
            val city = words.joinToString(" ")
            return Req("weather", if (city.isEmpty()) savedCity() else city)
        }
        return null
    }

    fun hostFor(req: Req): String = when (req.kind) {
        "weather" -> "open-meteo.com"
        "wiki" -> "wikipedia.org"
        else -> if (KeyVault.load(prefs, "tavily_key").isNotEmpty()) "api.tavily.com" else "wikipedia.org"
    }

    // Runs on a background thread. Never invents a result: on any failure it says what failed.
    fun run(req: Req): OnlineOut {
        val what = when (req.kind) {
            "weather" -> "the weather"
            "wiki" -> "the Wikipedia result"
            else -> "the search results"
        }
        return try {
            val text = when (req.kind) {
                "weather" -> weather(req.arg)
                "wiki" -> wiki(req.arg)
                else -> search(req.arg)
            }
            OnlineOut(text, true)
        } catch (e: IOException) {
            OnlineOut("Internet is required for this task. I couldn't connect, so I did not get $what.\nDetails: ${e.javaClass.simpleName}: ${e.message}", false)
        } catch (e: OnlineException) {
            OnlineOut("The online service returned an error, so I did not get $what. ${e.message}", false)
        } catch (e: Exception) {
            OnlineOut("Something went wrong, so I did not get $what. ${e.javaClass.simpleName}: ${e.message}", false)
        }
    }

    private fun weather(city: String): String {
        val enc = URLEncoder.encode(city, "UTF-8")
        val geo = JSONObject(httpGet("https://geocoding-api.open-meteo.com/v1/search?name=$enc&count=1&language=en&format=json"))
        val results = geo.optJSONArray("results")
        if (results == null || results.length() == 0) {
            return "I couldn't find a place called \"$city\", so I have no weather for it."
        }
        val g = results.getJSONObject(0)
        val country = if (g.isNull("country")) "" else g.optString("country", "")
        val place = g.optString("name", city) + (if (country.isNotEmpty()) ", $country" else "")
        val lat = g.getDouble("latitude")
        val lon = g.getDouble("longitude")
        val url = "https://api.open-meteo.com/v1/forecast?latitude=$lat&longitude=$lon" +
            "&current=temperature_2m,relative_humidity_2m,apparent_temperature,weather_code,wind_speed_10m" +
            "&daily=temperature_2m_max,temperature_2m_min,precipitation_probability_max&forecast_days=2&timezone=auto"
        val w = JSONObject(httpGet(url))
        val cur = w.getJSONObject("current")
        val d = w.getJSONObject("daily")
        fun t(x: Double): Int = Math.round(x).toInt()
        val sb = StringBuilder()
        sb.append("Weather in $place: ${t(cur.getDouble("temperature_2m"))}°C, feels like ${t(cur.getDouble("apparent_temperature"))}°C, ${describeCode(cur.getInt("weather_code"))}. ")
        sb.append("Humidity ${cur.getInt("relative_humidity_2m")}%, wind ${t(cur.getDouble("wind_speed_10m"))} km/h. ")
        val labels = listOf("Today", "Tomorrow")
        val mins = d.getJSONArray("temperature_2m_min")
        val maxs = d.getJSONArray("temperature_2m_max")
        val rains = d.getJSONArray("precipitation_probability_max")
        for (i in 0 until minOf(2, mins.length())) {
            sb.append("${labels[i]}: ${t(mins.getDouble(i))} to ${t(maxs.getDouble(i))}°C")
            val rain = rains.optInt(i, -1)
            if (rain >= 0) sb.append(", rain chance $rain%")
            sb.append(". ")
        }
        sb.append("\n\nSource: open-meteo.com, fetched at ${now()}.")
        return sb.toString()
    }

    private fun wiki(topic: String): String {
        if (topic.isBlank()) return "What should I look up on Wikipedia? For example: wikipedia Mahatma Gandhi."
        val lang = when {
            topic.any { it.code in 0x0A80..0x0AFF } -> "gu"
            topic.any { it.code in 0x0900..0x097F } -> "hi"
            else -> "en"
        }
        val enc = URLEncoder.encode(topic, "UTF-8")
        val url = "https://$lang.wikipedia.org/w/api.php?action=query&generator=search&gsrsearch=$enc&gsrlimit=1" +
            "&prop=extracts&exintro=1&explaintext=1&exchars=600&redirects=1&format=json"
        val r = JSONObject(httpGet(url))
        val pages = r.optJSONObject("query")?.optJSONObject("pages")
            ?: return "I found nothing on Wikipedia ($lang) for \"$topic\"."
        val keys = pages.keys()
        if (!keys.hasNext()) return "I found nothing on Wikipedia ($lang) for \"$topic\"."
        val p = pages.getJSONObject(keys.next())
        val title = p.optString("title", topic)
        val extract = if (p.isNull("extract")) "" else p.optString("extract", "").trim()
        if (extract.isEmpty()) return "Wikipedia ($lang) has a page \"$title\", but no summary text I could read."
        return "$extract\n\nSource: $lang.wikipedia.org, \"$title\". Fetched at ${now()}."
    }

    private fun search(q: String): String {
        if (q.isBlank()) return "What should I search for? For example: search best time to visit Goa."
        val key = KeyVault.load(prefs, "tavily_key")
        if (key.isEmpty()) {
            return "No web search key is set, so I searched Wikipedia instead. You can add a free Tavily key in Privacy, under Online tools.\n\n" + wiki(q)
        }
        val body = JSONObject()
            .put("query", q)
            .put("max_results", 3)
            .put("search_depth", "basic")
            .put("include_answer", true)
        val r = JSONObject(httpPostJson("https://api.tavily.com/search", key, body.toString()))
        val answer = if (r.isNull("answer")) "" else r.optString("answer", "").trim()
        val results = r.optJSONArray("results")
        val sb = StringBuilder()
        if (answer.isNotEmpty()) sb.append("Tavily's summary: ").append(answer)
        else sb.append("Top results for \"$q\":")
        sb.append("\n\n")
        if (results == null || results.length() == 0) {
            sb.append("No results were returned.")
        } else {
            for (i in 0 until results.length()) {
                val item = results.getJSONObject(i)
                val title = item.optString("title", "")
                val host = Uri.parse(item.optString("url", "")).host ?: ""
                val snippet = (if (item.isNull("content")) "" else item.optString("content", "")).trim().take(160)
                sb.append("${i + 1}. $title ($host)\n   $snippet\n")
            }
        }
        sb.append("\nSource: web search by Tavily, fetched at ${now()}.")
        return sb.toString()
    }
}

@Composable
fun OnlineToolsSection() {
    val ctx = LocalContext.current
    val prefs = remember { ctx.getSharedPreferences("chintu_tools", Context.MODE_PRIVATE) }
    var city by remember { mutableStateOf(prefs.getString("weather_city", "") ?: "") }
    var keySaved by remember { mutableStateOf(KeyVault.load(prefs, "tavily_key").isNotEmpty()) }
    var keyDraft by remember { mutableStateOf("") }
    var msg by remember { mutableStateOf("") }
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Online tools", fontSize = 18.sp, color = Color.White)
        Text(
            "Weather uses open-meteo.com and Wikipedia lookups use wikipedia.org. Neither needs a key. Web search uses Tavily and needs your own free key. These tools run only in AUTO and ONLINE modes, never in OFFLINE or Private Mode. What is sent: the city or search words you said, and your phone's internet address (as with any website). Your chat and memories are not sent.",
            color = Dim
        )
        Text("Default city: " + if (city.isEmpty()) "none" else city, color = Dim)
        if (city.isNotEmpty()) {
            OutlinedButton(onClick = {
                prefs.edit().remove("weather_city").commit()
                city = prefs.getString("weather_city", "") ?: ""
                msg = if (city.isEmpty()) "City removed." else "I couldn't remove the city."
            }) { Text("Remove city") }
        }
        Text(
            "Web search key (Tavily): " + if (keySaved) "saved, stored encrypted" else "not set",
            color = if (keySaved) Cyan else Dim
        )
        OutlinedTextField(
            keyDraft, { keyDraft = it }, label = { Text("Paste Tavily key") }, singleLine = true,
            visualTransformation = PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth()
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = {
                if (KeyVault.save(prefs, keyDraft, "tavily_key")) {
                    keySaved = true
                    msg = "Key saved."
                } else {
                    msg = "I couldn't save the key."
                }
                keyDraft = ""
            }, enabled = keyDraft.isNotBlank()) { Text("Save key") }
            OutlinedButton(onClick = {
                KeyVault.clear(prefs, "tavily_key")
                keySaved = KeyVault.load(prefs, "tavily_key").isNotEmpty()
                msg = if (keySaved) "I couldn't remove the key." else "Key removed."
            }, enabled = keySaved) { Text("Remove key") }
        }
        if (msg.isNotEmpty()) Text(msg, color = Dim)
    }
}
