package com.assistant.core.services

import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/** Reads models offered to the user's Gemini API key. No credentials are logged or persisted. */
object GeminiModelDiscovery {
    fun list(apiKey: String): List<String> {
        require(apiKey.isNotBlank() && apiKey.length <= 1024)
        val available = linkedSetOf<String>()
        var pageToken = ""
        repeat(5) {
            val url = "https://generativelanguage.googleapis.com/v1beta/models" +
                if (pageToken.isBlank()) "" else "?pageToken=" +
                    java.net.URLEncoder.encode(pageToken, "UTF-8")
            val connection = URL(url).openConnection() as HttpURLConnection
            try {
                connection.requestMethod = "GET"
                connection.instanceFollowRedirects = false
                connection.connectTimeout = 15000
                connection.readTimeout = 20000
                connection.setRequestProperty("x-goog-api-key", apiKey)
                connection.setRequestProperty("Accept", "application/json")
                if (connection.responseCode !in 200..299) return available.toList()
                val response = connection.inputStream.bufferedReader().use { reader ->
                    val text = reader.readText()
                    require(text.length <= 256000)
                    JSONObject(text)
                }
                val models = response.optJSONArray("models") ?: JSONArray()
                for (i in 0 until models.length()) {
                    val model = models.getJSONObject(i)
                    val methods = model.optJSONArray("supportedGenerationMethods") ?: JSONArray()
                    if ((0 until methods.length()).any { methods.optString(it) == "generateContent" }) {
                        val id = model.optString("name").removePrefix("models/")
                        if (Regex("gemini-[a-zA-Z0-9._-]+").matches(id)) available.add(id)
                    }
                }
                pageToken = response.optString("nextPageToken")
                if (pageToken.isBlank() || pageToken.length > 1024) return available.toList()
            } finally {
                connection.disconnect()
            }
        }
        return available.toList()
    }
}
