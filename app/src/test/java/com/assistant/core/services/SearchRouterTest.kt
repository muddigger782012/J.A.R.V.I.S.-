package com.assistant.core.services

import org.junit.Assert.*
import org.junit.Test

class SearchRouterTest {
    private val result = SearchRouter.Result("Source", "Details", "https://example.org/source")

    @Test fun triesNextProviderAfterFailureOrEmptyResults() {
        val calls = mutableListOf<String>()
        val router = SearchRouter(listOf(
            SearchRouter.Provider("limited") { calls.add(it); throw IllegalStateException("HTTP 429") },
            SearchRouter.Provider("empty") { calls.add(it); emptyList() },
            SearchRouter.Provider("working") { calls.add(it); listOf(result) },
            SearchRouter.Provider("unused") { fail("Must stop after useful results"); emptyList() }
        ))
        val response = router.search("diesel repair")
        assertEquals(listOf("diesel repair", "diesel repair", "diesel repair"), calls)
        assertEquals("working", response.provider)
        assertEquals(listOf(result), response.items)
    }

    @Test fun rejectsUnsafeResultsAndContinuesToFallback() {
        val router = SearchRouter(listOf(
            SearchRouter.Provider("unsafe") { listOf(result.copy(url = "https://user:pass@example.org")) },
            SearchRouter.Provider("safe") { listOf(result, result) }
        ))
        assertEquals(SearchRouter.Response("safe", listOf(result)), router.search("repair"))
    }

    @Test fun exhaustedProvidersReturnBrowserFallbackWithoutInventingResults() {
        val router = SearchRouter(listOf(SearchRouter.Provider("offline") { throw IllegalStateException() }))
        assertEquals(SearchRouter.Response(null, emptyList()), router.search("repair"))
        assertEquals(SearchRouter.Response(null, emptyList()), SearchRouter(emptyList()).search("repair"))
    }

    @Test fun invalidQueriesNeverReachProviders() {
        val router = SearchRouter(listOf(SearchRouter.Provider("unused") { fail("Invalid query sent"); emptyList() }))
        for (query in listOf(" ", "x".repeat(301))) {
            try { router.search(query); fail("Expected validation failure") }
            catch (_: IllegalArgumentException) { }
        }
    }
}
