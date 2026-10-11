package com.assistant.core.services

import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/** Free keyless Tavily web search, with optional user-owned API key for higher limits. */
class TavilySearchProvider(private val apiKey: String = "") {
    data class Result(val title: String, val description: String, val url: String)

    fun search(query: String): List<Result> = searchRich(query).filter { it.kind != "image" }
        .map { Result(it.title, it.description, it.url) }

    fun searchRich(query: String, kind: String = "web"): List<SearchRouter.Result> {
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
            val body = JSONObject().put("query", if (kind == "video") "$query site:youtube.com/watch" else query).put("max_results", 5)
                .put("search_depth", "basic").put("include_answer", false)
                .put("include_images", kind != "video").put("include_image_descriptions", kind != "video").toString()
            connection.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            if (connection.responseCode != 200)
                throw IllegalStateException("Search unavailable (HTTP ${connection.responseCode})")
            parseRich(connection.inputStream.bufferedReader().use { it.readText() })
        } finally {
            connection.disconnect()
        }
    }

    companion object {
        fun parseRich(json: String): List<SearchRouter.Result> {
            val web = parse(json).map { SearchMedia.webResult(it.title, it.description, it.url) }
            val images = JSONObject(json).optJSONArray("images")
            val media = if (images == null) emptyList() else (0 until minOf(images.length(), 6)).mapNotNull { index ->
                val image = images.optJSONObject(index)
                val url = image?.optString("url") ?: images.optString(index)
                if (!ChatSearchResults.safeUrl(url)) return@mapNotNull null
                val description = image?.optString("description").orEmpty().trim().take(300)
                SearchRouter.Result(description.ifBlank { "Image ${index + 1}" }, description, url, "image", url)
            }
            return web + media
        }

        fun parse(json: String): List<Result> {
            val items = JSONObject(json).optJSONArray("results") ?: return emptyList()
            return (0 until minOf(items.length(), 5)).mapNotNull { index ->
                val item = items.optJSONObject(index) ?: return@mapNotNull null
                val url = item.optString("url")
                val title = item.optString("title").trim()
                if (!ChatSearchResults.safeUrl(url) || title.isBlank()) return@mapNotNull null
                Result(title.take(250), item.optString("content").trim().take(700), url)
            }
        }
    }
}
