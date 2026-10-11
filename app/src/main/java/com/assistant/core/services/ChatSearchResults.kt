package com.assistant.core.services

import org.json.JSONArray
import org.json.JSONObject
import java.net.URI

/** Versioned chat payload: saved history keeps source fields rather than flattened text. */
object ChatSearchResults {
    private const val PREFIX = "J.A.R.V.I.S.: [search-results-v1]"
    data class Message(val query: String, val items: List<TavilySearchProvider.Result>)

    fun safeUrl(url: String): Boolean = runCatching {
        val uri = URI(url)
        uri.scheme == "https" && !uri.host.isNullOrBlank() && uri.rawUserInfo == null
    }.getOrDefault(false)

    fun encode(query: String, items: List<TavilySearchProvider.Result>): String {
        val results = JSONArray()
        items.take(5).filter { safeUrl(it.url) }.forEach {
            results.put(JSONObject().put("title", it.title).put("content", it.description).put("url", it.url))
        }
        return PREFIX + JSONObject().put("query", query).put("results", results).toString()
    }

    fun decode(message: String): Message? {
        if (!message.startsWith(PREFIX)) return null
        return runCatching {
            val json = message.removePrefix(PREFIX)
            Message(JSONObject(json).getString("query"), TavilySearchProvider.parse(json))
        }.getOrNull()
    }
}
