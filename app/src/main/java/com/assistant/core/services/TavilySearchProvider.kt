package com.assistant.core.services

import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/** Free keyless Tavily web search, with optional user-owned API key for higher limits. */
class TavilySearchProvider(private val apiKey: String = "") {
    data class Result(val title: String, val description: String, val url: String)

    fun search(query: String): List<Result> {
        require(query.isNotBlank() && query.length <= 300) { "Invalid search query" }
        val connection = URL("https://api.tavily.com/search").openConnection() as HttpURLConnection
        return try {
            connection.requestMethod = "POST"
            connection.doOutput = true
            connection.setRequestProperty("Content-Type", "application/json")
            if (apiKey.isBlank()) connection.setRequestProperty("X-Tavily-Access-Mode", "keyless")
            else connection.setRequestProperty("Authorization", "Bearer $apiKey")
            connection.connectTimeout = 7000
            connection.readTimeout = 10000
            val body = JSONObject().put("query", query).put("max_results", 5)
                .put("search_depth", "basic").put("include_answer", false).toString()
            connection.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            if (connection.responseCode != 200)
                throw IllegalStateException("Search unavailable (HTTP ${connection.responseCode})")
            parse(connection.inputStream.bufferedReader().use { it.readText() })
        } finally {
            connection.disconnect()
        }
    }

    companion object {
        fun parse(json: String): List<Result> {
            val items = JSONObject(json).optJSONArray("results") ?: return emptyList()
            return (0 until minOf(items.length(), 5)).mapNotNull { index ->
                val item = items.optJSONObject(index) ?: return@mapNotNull null
                val url = item.optString("url")
                val title = item.optString("title").trim()
                if (!url.startsWith("https://") || title.isBlank()) return@mapNotNull null
                Result(title.take(250), item.optString("content").trim().take(700), url)
            }
        }
    }
}
