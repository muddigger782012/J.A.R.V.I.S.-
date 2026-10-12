package com.assistant.core.services

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class SearchRecoveryTest {
    private val source = SearchRouter.Result("Official source", "", "https://example.org/source")
    private val image = SearchRouter.Result("Diesel truck", "Author · CC BY", "https://commons.wikimedia.org/wiki/File:Truck.jpg", "image", "https://upload.wikimedia.org/truck.jpg")
    private fun ai(query: String) = ChatSearchResults.Message(query, listOf(source), "Current information[1]", "<div>Google suggestions</div>")

    @Test fun groundedAiIsFirstAndTavilyIsNotCalledWhenWebSearchSucceeds() {
        val calls = mutableListOf<String>()
        val fallback = SearchRouter(listOf(SearchRouter.Provider("Tavily") { calls.add("Tavily"); listOf(source) }))
        val coordinator = SearchCoordinator({ q, _ -> calls.add("AI"); ai(q) }, fallback)
        val result = coordinator.search("diesel trucks", "web")
        assertEquals(listOf("AI"), calls)
        assertEquals("Current information[1]", result.answer)
        assertEquals("<div>Google suggestions</div>", result.searchSuggestions)
    }

    @Test fun primaryFailureOrUngroundedTextFallsBackWithoutPublishingAnInventedAnswer() {
        val calls = mutableListOf<String>()
        val fallback = SearchRouter(listOf(SearchRouter.Provider("Tavily") { calls.add("Tavily"); listOf(source) }))
        val failed = SearchCoordinator({ _, _ -> calls.add("AI"); throw SearchProviderFailure(SearchProviderFailure.Kind.RATE_LIMIT) }, fallback)
        assertEquals(listOf(source), failed.search("trucks", "web").items)
        assertEquals(listOf("AI", "Tavily"), calls)
        val ungrounded = SearchCoordinator({ q, _ -> ChatSearchResults.Message(q, emptyList(), "A made up live answer") }, fallback)
        assertNull(ungrounded.search("trucks", "web").answer)
    }

    @Test fun groundedPrimaryKeepsItsAnswerWhileMediaFallsBack() {
        val calls = mutableListOf<String>()
        val coordinator = SearchCoordinator({ q, _ -> calls.add("AI"); ai(q) },
            SearchRouter(listOf(SearchRouter.Provider("Tavily") { calls.add("Tavily"); listOf(image) })))
        val result = coordinator.search("diesel truck", "image")
        assertEquals(listOf("AI", "Tavily"), calls)
        assertEquals(listOf(source, image), result.items)
        assertEquals("Current information[1]", result.answer)
    }

    @Test fun providerLimitsAreNotReportedAsAnEmptySuccessfulSearchAndBodiesStayPrivate() {
        val router = SearchRouter(listOf(SearchRouter.Provider("limited") { throw SearchProviderFailure.fromHttp(429) }))
        val result = router.search("diesel truck")
        assertTrue(router.failureMessage(result, "image").contains("rate-limited"))
        try {
            TavilySearchProvider.parseRich("""{"error":{"code":"KEYLESS_RATE_LIMIT","message":"secret-token internal instructions"}}""")
            fail("Expected rate limit failure")
        } catch (error: SearchProviderFailure) {
            assertEquals(SearchProviderFailure.Kind.RATE_LIMIT, error.kind)
            assertFalse(error.message.orEmpty().contains("secret-token"))
        }
    }

    @Test fun commonsParsesRealImageMetadataWithSourceAndCreditAndRejectsUnsafeLinks() {
        val raw = """{"query":{"pages":[{"title":"File:Diesel_truck.jpg","index":1,"imageinfo":[{"mime":"image/jpeg","descriptionurl":"https://commons.wikimedia.org/wiki/File:Diesel_truck.jpg","thumburl":"https://upload.wikimedia.org/truck.jpg","extmetadata":{"Artist":{"value":"<a>Photographer</a>"},"LicenseShortName":{"value":"CC BY-SA"}}}]},{"title":"File:Bad.jpg","imageinfo":[{"mime":"image/jpeg","descriptionurl":"https://evil.example/file","thumburl":"https://upload.wikimedia.org/bad.jpg"}]}]}}"""
        val images = CommonsImageSearchProvider.parse(raw)
        assertEquals(1, images.size)
        assertEquals("Diesel truck.jpg", images.single().title)
        assertEquals("Photographer · CC BY-SA", images.single().description)
        assertEquals("image", images.single().kind)
        assertTrue(CommonsImageSearchProvider.requestUrl("truck & boat").contains("truck+%26+boat"))
    }

    @Test fun consentRedirectStaysEmbeddedButSourcesCredentialsAndUnsafeSchemesDoNot() {
        assertTrue(SearchBrowserNavigation.canStart("https://www.google.com/search?q=trucks"))
        assertTrue(SearchBrowserNavigation.staysEmbedded("https://consent.google.com/m?continue=search"))
        assertTrue(SearchBrowserNavigation.staysEmbedded("https://www.google.com/search?udm=2&q=trucks"))
        for (url in listOf("https://example.org/source", "https://www.google.com.evil.example/search", "http://www.google.com/search", "https://user:pass@www.google.com/search", "javascript:alert(1)")) {
            assertFalse(SearchBrowserNavigation.canStart(url))
            assertFalse(SearchBrowserNavigation.staysEmbedded(url))
        }
    }

    @Test fun catalogueAvailabilityIsSearchedButPrivatePreferencesAndGenericConversationAreNot() {
        assertNotNull(WebSearchCommand.currentInfoQuery("Is season 20 of Heartland available on Amazon Prime yet?"))
        assertNotNull(WebSearchCommand.currentInfoQuery("Is this movie streaming on Netflix now?"))
        assertNull(WebSearchCommand.currentInfoQuery("What is my preferred browser?"))
        assertNull(WebSearchCommand.currentInfoQuery("Explain what a movie is"))
        assertNull(WebSearchCommand.currentInfoQuery("Don't search for streaming movies"))
    }

    @Test fun groundingSourcesAndCitationsMustComeFromMetadataAndSurviveSavedHistory() {
        val raw = JSONObject("""{"candidates":[{"content":{"parts":[{"text":"Live answer"}]},"groundingMetadata":{"groundingChunks":[{"web":{"title":"Source","uri":"https://example.org/source"}}],"groundingSupports":[{"segment":{"text":"Live answer","endIndex":11},"groundingChunkIndices":[0]}],"searchEntryPoint":{"renderedContent":"<div>Google suggestions</div>"}}}]}""")
        val grounded = GeminiGroundedSearch.parse("query", raw)!!
        assertEquals("Live answer[1]", grounded.answer)
        val saved = ChatSearchResults.decode(ChatSearchResults.encode(grounded.query, grounded.items, grounded.answer, grounded.searchSuggestions))!!
        assertEquals(grounded, saved)
        raw.getJSONArray("candidates").getJSONObject(0).remove("groundingMetadata")
        assertNull(GeminiGroundedSearch.parse("query", raw))
        assertTrue(GeminiGroundedSearch.body("query", "web").getJSONArray("tools").getJSONObject(0).has("google_search"))
    }
}
