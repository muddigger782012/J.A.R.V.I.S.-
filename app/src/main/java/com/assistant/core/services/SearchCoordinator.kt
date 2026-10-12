package com.assistant.core.services

/** AI is attempted first. Retrieval providers fill unavailable search results or media. */
class SearchCoordinator(private val primary: (String, String) -> ChatSearchResults.Message?, private val fallback: SearchRouter) {
    data class Response(val items: List<SearchRouter.Result>, val answer: String? = null,
        val searchSuggestions: String? = null, val failures: List<SearchProviderFailure.Kind> = emptyList())
    fun search(query: String, kind: String): Response {
        require(query.isNotBlank() && query.length <= 300 && kind in setOf("web", "image", "video"))
        val errors = mutableListOf<SearchProviderFailure.Kind>()
        val ai = try { primary(query, kind) } catch (error: SearchProviderFailure) { errors.add(error.kind); null }
            catch (_: Exception) { errors.add(SearchProviderFailure.Kind.UNAVAILABLE); null }
        val validAi = ai?.takeIf { it.query == query && !it.answer.isNullOrBlank() && it.items.isNotEmpty() &&
            it.items.all { item -> ChatSearchResults.safeUrl(item.url) && item.title.isNotBlank() } }
        if (validAi != null && (kind == "web" || validAi.items.any { it.kind == kind && (kind != "image" || ChatSearchResults.safeUrl(it.thumbnail.orEmpty())) }))
            return Response(validAi.items.take(11), validAi.answer, validAi.searchSuggestions)
        val retrieved = fallback.search(query)
        val media = if (kind == "web") retrieved.items else retrieved.items.filter { it.kind == kind }
        if (validAi == null) return Response(media, failures = (errors + retrieved.failures).distinct())
        // Grounded AI remains the primary answer; fallback supplies media it did not return.
        return Response((validAi.items.take(5) + media.take(6)).distinctBy { it.kind + it.url }, validAi.answer,
            validAi.searchSuggestions, (errors + retrieved.failures).distinct())
    }
}
