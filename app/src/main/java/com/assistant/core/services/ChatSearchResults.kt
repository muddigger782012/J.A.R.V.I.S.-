package com.assistant.core.services

import org.json.JSONArray
import org.json.JSONObject
import java.net.URI

/** Versioned chat payload: saved history keeps source fields rather than flattened text. */
object ChatSearchResults {
    private const val PREFIX = "J.A.R.V.I.S.: [search-results-v1]"
    data class Message(val query: String, val items: List<SearchRouter.Result>)

    fun safeUrl(url: String): Boolean = runCatching {
        val uri = URI(url)
        uri.scheme == "https" && !uri.host.isNullOrBlank() && uri.rawUserInfo == null
    }.getOrDefault(false)

    fun encode(query: String, items: List<SearchRouter.Result>): String {
        val results = JSONArray()
        items.take(11).filter { safeUrl(it.url) }.forEach {
            results.put(JSONObject().put("title", it.title).put("content", it.description).put("url", it.url)
                .put("kind", it.kind).put("thumbnail", it.thumbnail?.takeIf(::safeUrl)))
        }
        return PREFIX + JSONObject().put("query", query).put("results", results).toString()
    }

    fun decode(message: String): Message? {
        if (!message.startsWith(PREFIX)) return null
        return runCatching {
            val json = message.removePrefix(PREFIX)
            val document = JSONObject(json)
            val results = document.getJSONArray("results")
            val items = (0 until minOf(results.length(), 11)).mapNotNull { index ->
                val item = results.optJSONObject(index) ?: return@mapNotNull null
                val url = item.optString("url")
                val title = item.optString("title").trim().take(300)
                if (!safeUrl(url) || title.isBlank()) return@mapNotNull null
                val kind = item.optString("kind", "web").takeIf { it in setOf("web", "image", "video") } ?: "web"
                val thumbnail = item.optString("thumbnail").takeIf(::safeUrl)
                SearchRouter.Result(title, item.optString("content").take(700), url, kind, thumbnail)
            }
            Message(document.getString("query"), items)
        }.getOrNull()
    }
}
