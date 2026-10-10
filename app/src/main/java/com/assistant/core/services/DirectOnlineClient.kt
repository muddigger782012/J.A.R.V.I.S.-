package com.assistant.core.services

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.time.OffsetDateTime
import java.time.Instant
import java.time.format.DateTimeFormatter
import java.util.Locale

object OnlineAnswers {
    fun wantsDeviceLocation(text: String): Boolean =
        Regex("\\b(use|enable|switch to)\\b", RegexOption.IGNORE_CASE).containsMatchIn(text) &&
            Regex("\\b(my|current|precise|phone|device)\\b", RegexOption.IGNORE_CASE).containsMatchIn(text) &&
            text.contains("location", ignoreCase = true) && isWeather(text)

    fun isWeather(text: String): Boolean {
        val words = text.trim().lowercase(Locale.ROOT)
        if (Regex("^(call|dial|text|message|remind|create|add|open|navigate|set|run)\\b").containsMatchIn(words)) return false
        return Regex("\\b(weather|forecast|rain|raining|snow|temperature)\\b").containsMatchIn(words)
    }
    fun weatherSummary(periods: JSONArray, question: String, location: String, now: Instant = Instant.now()): String {
        val upcoming = (0 until periods.length()).map { periods.getJSONObject(it) }.filter {
            OffsetDateTime.parse(it.getString("endTime")).toInstant().isAfter(now)
        }
        if (upcoming.isEmpty()) return "The weather service returned no upcoming forecast periods. Try again shortly."
        val wantsRain = Regex("\\b(rain|raining)\\b", RegexOption.IGNORE_CASE).containsMatchIn(question)
        if (wantsRain) {
            val wet = upcoming.firstOrNull { Regex("\\b(rain|showers|thunderstorms|drizzle)\\b", RegexOption.IGNORE_CASE).containsMatchIn(it.optString("shortForecast")) }
                ?: return "For $location, rain isn't mentioned in the available forecast through ${upcoming.last().getString("name")}. Forecasts can change. Source: National Weather Service."
            val day = OffsetDateTime.parse(wet.getString("startTime")).format(DateTimeFormatter.ofPattern("EEEE, MMMM d", Locale.getDefault()))
            val chance = wet.optJSONObject("probabilityOfPrecipitation")
            val probability = if (chance != null && !chance.isNull("value")) " Precipitation chance: ${chance.getInt("value") }%." else ""
            return "For $location, the next forecast period mentioning rain is ${wet.getString("name")}, $day: ${wet.getString("detailedForecast")}$probability Source: National Weather Service."
        }
        val tomorrow = question.lowercase(Locale.ROOT).contains("tomorrow")
        val selected = if (tomorrow) {
            val zone = OffsetDateTime.parse(upcoming.first().getString("startTime")).offset
            val target = now.atOffset(zone).toLocalDate().plusDays(1)
            upcoming.filter { OffsetDateTime.parse(it.getString("startTime")).toLocalDate() == target }
        } else upcoming.take(4)
        if (selected.isEmpty()) return "No forecast is available for that day yet."
        return "Forecast for $location: " + selected.joinToString(" ") { "${it.getString("name")}: ${it.getString("detailedForecast")}" } + " Source: National Weather Service."
    }
    fun geminiError(code: Int, json: JSONObject?): String {
        val error = json?.optJSONObject("error")
        val status = error?.optString("status").orEmpty().takeIf { Regex("[A-Z_]{1,80}").matches(it) }
        val details = error?.optJSONArray("details")
        val reasons = if (details == null) emptyList() else (0 until details.length()).mapNotNull {
            details.optJSONObject(it)?.optString("reason")?.takeIf { reason -> Regex("[A-Z_]{1,80}").matches(reason) }
        }
        val diagnostic = (listOf("HTTP $code") + listOfNotNull(status) + reasons).distinct().joinToString(", ")
        val advice = when {
            "API_KEY_INVALID" in reasons -> "Re-copy the complete API key from Google AI Studio and save it in Weather and AI settings."
            "SERVICE_DISABLED" in reasons || "API_KEY_SERVICE_BLOCKED" in reasons -> "Check the project's Gemini API access and key restrictions in Google AI Studio."
            code == 429 -> "Your Gemini quota or rate limit was reached. Check your quota or try again later."
            code == 404 -> "The configured model isn't available. Check the model in Weather and AI settings."
            code in listOf(400, 401, 403) -> "Check API key, model, project access, and regional availability in Google AI Studio."
            else -> "Try again later."
        }
        return "Gemini rejected the request ($diagnostic). $advice"
    }

    fun geminiText(json: JSONObject): String? {
        val candidate = json.optJSONArray("candidates")?.optJSONObject(0) ?: return null
        val parts = candidate.optJSONObject("content")?.optJSONArray("parts") ?: return null
        return (0 until parts.length()).mapNotNull { parts.optJSONObject(it)?.let { part ->
            if (part.optBoolean("thought", false)) null else part.optString("text").takeIf(String::isNotBlank)
        } }.joinToString("\n").trim().takeIf(String::isNotBlank)
    }
}

class DirectOnlineClient(context: Context) {
    private val prefs = context.getSharedPreferences("jarvis_cloud_ai", Context.MODE_PRIVATE)
    private val location = WeatherLocation(context)
    private val keys = ApiKeyStore(context)
    fun hasGeminiKey(): Boolean = keys.read().isNotBlank()
    private fun request(url: String, body: JSONObject? = null, apiKey: String? = null): Pair<Int, JSONObject?> {
        val connection = URL(url).openConnection() as HttpURLConnection
        try {
            connection.connectTimeout = 10_000
            connection.readTimeout = 25_000
            connection.instanceFollowRedirects = false
            connection.setRequestProperty("User-Agent", "JARVIS/1.0 (https://github.com/muddigger782012/J.A.R.V.I.S.-)")
            connection.setRequestProperty("Accept", "application/json")
            apiKey?.let { connection.setRequestProperty("x-goog-api-key", it) }
            if (body != null) {
                connection.requestMethod = "POST"
                connection.doOutput = true
                connection.setRequestProperty("Content-Type", "application/json")
                connection.outputStream.writer(Charsets.UTF_8).use { it.write(body.toString()) }
            }
            val code = connection.responseCode
            if (code !in 200..299) {
                val error = runCatching { connection.errorStream?.reader(Charsets.UTF_8)?.use { JSONObject(it.readText()) } }.getOrNull()
                return code to error
            }
            return code to JSONObject(connection.inputStream.reader(Charsets.UTF_8).use { it.readText() })
        } finally { connection.disconnect() }
    }
    fun weather(question: String): String {
        if (OnlineAnswers.wantsDeviceLocation(question)) prefs.edit().putBoolean("weather_device_location", true).apply()
        val deviceMode = prefs.getBoolean("weather_device_location", false)
        val coordinates = if (deviceMode) {
            if (!location.hasPrecisePermission()) return "Allow Precise location for J.A.R.V.I.S. while using the app, then ask for weather again."
            val fix = location.current() ?: return "I couldn't get a fresh precise location. Turn on Location, keep J.A.R.V.I.S. open, and try again. GPS may need a clearer view of the sky."
            fix.latitude to fix.longitude
        } else null
        val lat = coordinates?.first ?: prefs.getString("weather_latitude", "").orEmpty().toDoubleOrNull()
        val lon = coordinates?.second ?: prefs.getString("weather_longitude", "").orEmpty().toDoubleOrNull()
        if (lat == null || lon == null || !lat.isFinite() || !lon.isFinite() || lat !in -90.0..90.0 || lon !in -180.0..180.0)
            return "Set your weather location in Voice Settings → Weather and AI. No gateway or API key is needed for weather."
        return try {
            val point = request(String.format(Locale.ROOT, "https://api.weather.gov/points/%.4f,%.4f", lat, lon))
            if (point.first == 404) return "National Weather Service forecasts aren't available for this location. This weather feature covers US locations."
            val props = point.second?.getJSONObject("properties") ?: return "Live weather is unavailable right now. Try again shortly."
            val forecastUrl = props.getString("forecast")
            val url = URL(forecastUrl)
            require(url.protocol == "https" && url.host == "api.weather.gov" && url.port == -1)
            val data = request(forecastUrl).second ?: return "Live weather is unavailable right now. Try again shortly."
            val place = props.getJSONObject("relativeLocation").getJSONObject("properties")
            OnlineAnswers.weatherSummary(data.getJSONObject("properties").getJSONArray("periods"), question, "${place.getString("city")}, ${place.getString("state")}")
        } catch (_: Exception) { "I couldn't retrieve live weather. Check your connection and try again." }
    }
    fun gemini(question: String): String {
        val apiKey = keys.read()
        if (apiKey.isBlank()) return "Gemini fallback needs your API key. Enter it in Voice Settings → Weather and AI."
        val model = prefs.getString("gemini_model", "gemini-3.5-flash-lite").orEmpty()
        if (!Regex("[a-zA-Z0-9._-]+").matches(model)) return "The Gemini model name is invalid. Check Weather and AI settings."
        return try {
            val body = JSONObject().put("systemInstruction", JSONObject().put("parts", JSONArray().put(JSONObject().put("text", "You are J.A.R.V.I.S., a concise helpful assistant. Answer questions only. You cannot execute device actions. Never claim to place calls or change settings. Do not invent current weather or other live facts; explain when live data is needed."))))
                .put("contents", JSONArray().put(JSONObject().put("role", "user").put("parts", JSONArray().put(JSONObject().put("text", question)))))
            val (code, json) = request("https://generativelanguage.googleapis.com/v1beta/models/$model:generateContent", body, apiKey)
            if (code in 200..299) {
                json?.let(OnlineAnswers::geminiText) ?: "Gemini returned no usable text answer. Try rephrasing the question."
            } else OnlineAnswers.geminiError(code, json)
        } catch (_: Exception) { "I couldn't connect to Gemini. Check your internet connection and try again." }
    }
}
