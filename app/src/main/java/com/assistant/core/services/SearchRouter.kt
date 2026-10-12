package com.assistant.core.services

/** Provider-independent routing; an unavailable or empty provider never blocks fallback. */
class SearchRouter(private val providers: List<Provider>) {
    data class Result(val title: String, val description: String, val url: String,
        val kind: String = "web", val thumbnail: String? = null)
    data class Provider(val name: String, val search: (String) -> List<Result>)
    data class Response(val provider: String?, val items: List<Result>, val failures: List<SearchProviderFailure.Kind> = emptyList())

    fun search(query: String): Response {
        require(query.isNotBlank() && query.length <= 300) { "Invalid search query" }
        val failures = mutableListOf<SearchProviderFailure.Kind>()
        for (provider in providers) {
            val items = try {
                provider.search(query).filter { it.title.isNotBlank() && ChatSearchResults.safeUrl(it.url) }
                    .distinctBy { it.kind + it.url }.take(11)
            } catch (failure: SearchProviderFailure) { failures.add(failure.kind); continue }
            catch (_: Exception) { failures.add(SearchProviderFailure.Kind.UNAVAILABLE); continue }
            if (items.isNotEmpty()) return Response(provider.name, items)
        }
        return Response(null, emptyList(), failures.distinct())
    }

    fun failureMessage(response: Response, kind: String): String = when {
        SearchProviderFailure.Kind.RATE_LIMIT in response.failures -> "Search is temporarily rate-limited. Tap the search card to view results in J.A.R.V.I.S.'s embedded browser, or try again later."
        SearchProviderFailure.Kind.AUTHENTICATION in response.failures -> "A search provider rejected its credentials or access. Check search settings, or tap the search card to view results in the embedded browser."
        response.failures.isNotEmpty() -> "Search providers couldn't return results. Check your connection, or tap the search card to continue in the embedded browser."
        else -> "No $kind results were returned for this query. Tap the search card to broaden the search in the embedded browser."
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
            if (kind == "image") providers.add(Provider("Wikimedia Commons") { CommonsImageSearchProvider().search(it) })
            return SearchRouter(providers)
        }
    }
}
