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
            if (code !in 200..299) return code to null
            return code to JSONObject(connection.inputStream.reader(Charsets.UTF_8).use { it.readText() })
        } finally { connection.disconnect() }
    }
    fun weather(question: String): String {
        val lat = prefs.getString("weather_latitude", "").orEmpty().toDoubleOrNull()
        val lon = prefs.getString("weather_longitude", "").orEmpty().toDoubleOrNull()
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
            when (code) {
                400, 401, 403 -> "Gemini rejected the request. Check your API key and API access in Google AI Studio."
                404 -> "That Gemini model isn't available for your API key. Change the model in Weather and AI settings."
                429 -> "Gemini's quota or rate limit was reached. Check your Google AI Studio quota or try again later."
                in 200..299 -> json?.let(OnlineAnswers::geminiText) ?: "Gemini returned no usable text answer. Try rephrasing the question."
                else -> "Gemini is unavailable right now (HTTP $code). Try again later."
            }
        } catch (_: Exception) { "I couldn't connect to Gemini. Check your internet connection and try again." }
    }
}
