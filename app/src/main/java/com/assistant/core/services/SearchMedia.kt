package com.assistant.core.services

import java.net.URI
import java.net.URLDecoder

/** Only recognized video URLs receive a player or a derived thumbnail. */
object SearchMedia {
    fun youtubeId(url: String): String? = runCatching {
        if (!ChatSearchResults.safeUrl(url)) return null
        val uri = URI(url)
        val host = uri.host.lowercase()
        val id = when (host) {
            "youtu.be" -> uri.path.removePrefix("/").substringBefore('/')
            "youtube.com", "www.youtube.com", "m.youtube.com" -> {
                if (uri.path == "/watch") uri.rawQuery.orEmpty().split('&')
                    .firstOrNull { it.startsWith("v=") }?.substringAfter("v=")?.let { URLDecoder.decode(it, "UTF-8") }
                else if (uri.path.startsWith("/shorts/") || uri.path.startsWith("/embed/")) uri.path.split('/').getOrNull(2)
                else null
            }
            else -> null
        }
        id?.takeIf { Regex("[A-Za-z0-9_-]{11}").matches(it) }
    }.getOrNull()

    fun webResult(title: String, description: String, url: String): SearchRouter.Result {
        val id = youtubeId(url)
        return SearchRouter.Result(title, description, url, if (id == null) "web" else "video",
            id?.let { "https://i.ytimg.com/vi/$it/hqdefault.jpg" })
    }
}
