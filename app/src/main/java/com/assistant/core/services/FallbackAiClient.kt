package com.assistant.core.services

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

data class FallbackAiResult(val reply: String, val lessonJson: String?)

class FallbackAiClient(private val context: Context) {
    private val prefs = context.getSharedPreferences("jarvis_cloud_ai", Context.MODE_PRIVATE)

    fun isConfigured(): Boolean =
        prefs.getString("endpoint", "").orEmpty().startsWith("https://") &&
            prefs.getString("gateway_token", "").orEmpty().isNotBlank()

    fun ask(userText: String): FallbackAiResult? {
        val endpoint = prefs.getString("endpoint", "").orEmpty()
        val token = prefs.getString("gateway_token", "").orEmpty()
        if (!endpoint.startsWith("https://") || token.isBlank()) return null

        val body = JSONObject()
            .put("session_id", "android")
            .put("learn_intent", true)
            .put("messages", JSONArray().put(JSONObject().put("role", "user").put("content", userText)))
            .toString()

        val connection = URL(endpoint).openConnection() as HttpURLConnection
        return try {
            connection.requestMethod = "POST"
            connection.connectTimeout = 12_000
            connection.readTimeout = 25_000
            connection.doOutput = true
            connection.setRequestProperty("Content-Type", "application/json")
            connection.setRequestProperty("Authorization", "Bearer $token")
            connection.outputStream.bufferedWriter().use { it.write(body) }
            if (connection.responseCode !in 200..299) return null
            val response = connection.inputStream.bufferedReader().use { it.readText() }
            val json = JSONObject(response)
            val reply = json.optString("reply").trim()
            if (reply.isBlank()) return null
            val lesson = json.optJSONObject("lesson")?.toString()
            FallbackAiResult(reply, lesson)
        } catch (_: Exception) {
            null
        } finally {
            connection.disconnect()
        }
    }
}
