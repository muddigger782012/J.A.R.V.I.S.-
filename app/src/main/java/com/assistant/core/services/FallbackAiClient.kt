package com.assistant.core.services

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

data class FallbackAiResult(val reply: String, val lessonJson: String?, val providerFailed: Boolean = false) {
    companion object {
        fun fromAssistantJson(raw: String): FallbackAiResult? = runCatching {
            val json = JSONObject(raw)
            val reply = (json.opt("reply") as? String)?.trim()?.takeIf { it.isNotBlank() } ?: return null
            FallbackAiResult(reply, json.optJSONObject("lesson")?.toString())
        }.getOrNull()
    }
}

/** Bounded interpretation-only fallback. No tool executes until the host reviews the result. */
object InterpretationFallback {
    fun ask(primary: () -> FallbackAiResult, alternative: (() -> FallbackAiResult?)? = null): FallbackAiResult {
        val first = runCatching(primary).getOrNull()
            ?: FallbackAiResult("The primary AI connection failed.", null, providerFailed = true)
        if (!first.providerFailed) return first
        val second = alternative?.let { runCatching(it).getOrNull() }?.takeIf { !it.providerFailed }
        return second ?: first.copy(lessonJson = null)
    }
}

/** Request construction is shared by normal chat and a location-free diagnostic check. */
object FallbackAiRequest {
    fun build(userText: String, learnIntent: Boolean, capabilityContext: String, latitude: Double?, longitude: Double?, includeLocation: Boolean): JSONObject {
        val request = JSONObject().put("session_id", "android").put("learn_intent", learnIntent)
            .put("capability_context", JSONObject(capabilityContext))
            .put("messages", JSONArray().put(JSONObject().put("role", "user").put("content", userText)))
        if (includeLocation && latitude != null && longitude != null) {
            request.put("latitude", latitude).put("longitude", longitude)
        }
        return request
    }
}

class FallbackAiClient(private val context: Context) {
    private val prefs = context.getSharedPreferences("jarvis_cloud_ai", Context.MODE_PRIVATE)

    fun isConfigured(): Boolean =
        prefs.getString("endpoint", "").orEmpty().startsWith("https://") &&
            prefs.getString("gateway_token", "").orEmpty().isNotBlank()

    fun ask(userText: String, learnIntent: Boolean = true, capabilityContext: String = "{}", includeLocation: Boolean = true, conversation: List<Pair<String, String>> = emptyList()): FallbackAiResult? {
        val endpoint = prefs.getString("endpoint", "").orEmpty()
        val token = prefs.getString("gateway_token", "").orEmpty()
        if (!endpoint.startsWith("https://") || token.isBlank()) return null

        val request = FallbackAiRequest.build(userText, learnIntent, capabilityContext,
            prefs.getString("weather_latitude", "").orEmpty().toDoubleOrNull(),
            prefs.getString("weather_longitude", "").orEmpty().toDoubleOrNull(), includeLocation)
        request.put("behavior_profile", AssistantPersonalityPreferences.behaviorProfile(context))
        val messages = JSONArray()
        conversation.takeLast(8).forEach { (user, assistant) ->
            messages.put(JSONObject().put("role", "user").put("content", user.take(12000)))
            messages.put(JSONObject().put("role", "assistant").put("content", assistant.take(12000)))
        }
        messages.put(JSONObject().put("role", "user").put("content", userText))
        request.put("messages", messages)
        val body = request.toString()

        var connection: HttpURLConnection? = null
        return try {
            connection = URL(endpoint).openConnection() as HttpURLConnection
            connection.requestMethod = "POST"
            connection.connectTimeout = 12_000
            connection.readTimeout = 25_000
            connection.doOutput = true
            connection.instanceFollowRedirects = false
            connection.setRequestProperty("Content-Type", "application/json")
            connection.setRequestProperty("Authorization", "Bearer $token")
            connection.outputStream.bufferedWriter().use { it.write(body) }
            if (connection.responseCode !in 200..299) return null
            val response = connection.inputStream.bufferedReader().use { it.readText() }
            val json = JSONObject(response)
            val reply = json.optString("reply").trim()
            if (reply.isBlank()) return null
            val lesson = if (learnIntent) json.optJSONObject("lesson")?.toString() else null
            FallbackAiResult(reply, lesson)
        } catch (_: Exception) {
            null
        } finally {
            connection?.disconnect()
        }
    }
}
