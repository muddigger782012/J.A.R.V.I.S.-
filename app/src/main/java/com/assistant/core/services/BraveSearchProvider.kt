package com.assistant.core.services

import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/** Opt-in structured web search. Requires a user-supplied Brave Search API key. */
class BraveSearchProvider(private val apiKey: String) {
    data class Result(val title: String, val description: String, val url: String)

    fun search(query: String): List<Result> {
        require(query.isNotBlank() && query.length <= 300) { "Invalid search query" }
        require(apiKey.isNotBlank()) { "Brave Search API key not configured" }
        val encoded = URLEncoder.encode(query, "UTF-8")
        val connection = URL("https://api.search.brave.com/res/v1/web/search?q=$encoded&count=5")
            .openConnection() as HttpURLConnection
        return try {
            connection.requestMethod = "GET"
            connection.setRequestProperty("Accept", "application/json")
            connection.setRequestProperty("X-Subscription-Token", apiKey)
            connection.connectTimeout = 7000
            connection.readTimeout = 7000
            if (connection.responseCode != 200) {
                throw IllegalStateException("Search provider returned HTTP ${connection.responseCode}")
            }
            parse(connection.inputStream.bufferedReader().use { it.readText() })
        } finally {
            connection.disconnect()
        }
    }

    companion object {
        fun parse(json: String): List<Result> {
            val items = JSONObject(json).optJSONObject("web")?.optJSONArray("results") ?: return emptyList()
            return (0 until minOf(items.length(), 5)).mapNotNull { index ->
                val item = items.optJSONObject(index) ?: return@mapNotNull null
                val url = item.optString("url")
                if (!url.startsWith("https://")) return@mapNotNull null
                val title = item.optString("title").trim()
                if (title.isBlank()) return@mapNotNull null
                Result(title.take(250), item.optString("description").trim().take(700), url)
            }
        }
    }
}
