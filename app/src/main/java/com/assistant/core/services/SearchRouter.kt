package com.assistant.core.services

/** Provider-independent routing; an unavailable or empty provider never blocks fallback. */
class SearchRouter(private val providers: List<Provider>) {
    data class Result(val title: String, val description: String, val url: String)
    data class Provider(val name: String, val search: (String) -> List<Result>)
    data class Response(val provider: String?, val items: List<Result>)

    fun search(query: String): Response {
        require(query.isNotBlank() && query.length <= 300) { "Invalid search query" }
        for (provider in providers) {
            val items = try {
                provider.search(query).filter { it.title.isNotBlank() && ChatSearchResults.safeUrl(it.url) }
                    .distinctBy { it.url }.take(5)
            } catch (_: Exception) { continue }
            if (items.isNotEmpty()) return Response(provider.name, items)
        }
        return Response(null, emptyList())
    }

    companion object {
        fun configured(tavilyKey: String, braveKey: String): SearchRouter {
            val providers = mutableListOf<Provider>()
            fun tavily(name: String, key: String) = Provider(name) { query ->
                TavilySearchProvider(key).search(query).map { Result(it.title, it.description, it.url) }
            }
            if (tavilyKey.isNotBlank()) providers.add(tavily("Tavily", tavilyKey))
            providers.add(tavily("Tavily keyless", ""))
            if (braveKey.isNotBlank()) providers.add(Provider("Brave") { query ->
                BraveSearchProvider(braveKey).search(query).map { Result(it.title, it.description, it.url) }
            })
            return SearchRouter(providers)
        }
    }
}
