package com.assistant.core.services

/** Provider-independent routing; an unavailable or empty provider never blocks fallback. */
class SearchRouter(private val providers: List<Provider>) {
    data class Result(val title: String, val description: String, val url: String,
        val kind: String = "web", val thumbnail: String? = null)
    data class Provider(val name: String, val search: (String) -> List<Result>)
    data class Response(val provider: String?, val items: List<Result>)

    fun search(query: String): Response {
        require(query.isNotBlank() && query.length <= 300) { "Invalid search query" }
        for (provider in providers) {
            val items = try {
                provider.search(query).filter { it.title.isNotBlank() && ChatSearchResults.safeUrl(it.url) }
                    .distinctBy { it.kind + it.url }.take(11)
            } catch (_: Exception) { continue }
            if (items.isNotEmpty()) return Response(provider.name, items)
        }
        return Response(null, emptyList())
    }

    companion object {
        fun configured(tavilyKey: String, braveKey: String, kind: String = "web"): SearchRouter {
            val providers = mutableListOf<Provider>()
            fun tavily(name: String, key: String) = Provider(name) { query ->
                val items = TavilySearchProvider(key).searchRich(query, kind)
                if (kind == "image") items.filter { it.kind == "image" }
                else if (kind == "video") items.filter { it.kind == "video" }
                else items
            }
            if (tavilyKey.isNotBlank()) providers.add(tavily("Tavily", tavilyKey))
            providers.add(tavily("Tavily keyless", ""))
            if (braveKey.isNotBlank()) providers.add(Provider("Brave") { query ->
                val items = BraveSearchProvider(braveKey).search(if (kind == "video") "$query site:youtube.com/watch" else query)
                    .map { SearchMedia.webResult(it.title, it.description, it.url) }
                if (kind == "image") emptyList() else if (kind == "video") items.filter { it.kind == "video" } else items
            })
            return SearchRouter(providers)
        }
    }
}
