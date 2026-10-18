package com.assistant.core.services

import org.junit.Assert.*
import org.junit.Test

class SearchCardPresentationTest {
    @Test fun sourceDisclosureSurvivesAnswerEnrichmentButIsolatedAcrossChats() {
        val card = ChatSearchResults.Message("truck parts", emptyList())
        assertEquals(SearchCardPresentation.stateKey("chat1", card),
            SearchCardPresentation.stateKey("chat1", card.copy(answer = "Answer [1]", retrievedAt = 42)))
        assertNotEquals(SearchCardPresentation.stateKey("chat1", card), SearchCardPresentation.stateKey("chat2", card))
        assertNotEquals(SearchCardPresentation.stateKey("chat1", card), SearchCardPresentation.stateKey("chat1", card.copy(kind = "image")))
        assertNotEquals(SearchCardPresentation.stateKey("chat1", card), SearchCardPresentation.stateKey("chat1", card.copy(query = "boat parts")))
    }

    @Test fun cachedAndRestoredCardsKeepOriginalRetrievalTime() {
        val encoded = ChatSearchResults.encode("parts", emptyList(), retrievedAt = 60_000)
        val decoded = ChatSearchResults.decode(encoded)!!
        assertEquals(60_000L, decoded.retrievedAt)
        assertEquals("Retrieved just now", SearchCardPresentation.freshness(decoded.retrievedAt, 60_001))
        assertEquals("Retrieved 10 min ago", SearchCardPresentation.freshness(decoded.retrievedAt, 660_000))
        assertEquals("Retrieved 2 hr ago", SearchCardPresentation.freshness(decoded.retrievedAt, 7_260_000))
        assertEquals("Retrieved 2 days ago", SearchCardPresentation.freshness(decoded.retrievedAt, 172_860_000))
        assertNull(SearchCardPresentation.freshness(0, 10))
        assertNull(SearchCardPresentation.freshness(100, 10))
        val legacy = """J.A.R.V.I.S.: [search-results-v1]{"query":"parts","results":[]}"""
        assertEquals(0L, ChatSearchResults.decode(legacy)!!.retrievedAt)
    }
}
