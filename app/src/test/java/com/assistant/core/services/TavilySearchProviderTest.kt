package com.assistant.core.services

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TavilySearchProviderTest {
    @Test fun parsesStructuredResultsAndRejectsInsecureLinks() {
        val json = """{"results":[{"title":"Official documentation","content":"Useful details","url":"https://example.org/docs"},{"title":"Unsafe","content":"No","url":"http://example.org"},{"title":"","content":"No","url":"https://example.org/blank"}]}"""
        val results = TavilySearchProvider.parse(json)
        assertEquals(1, results.size)
        assertEquals("Official documentation", results[0].title)
        assertEquals("Useful details", results[0].description)
        assertEquals("https://example.org/docs", results[0].url)
    }

    @Test fun emptyAndMalformedResultsAreHandled() {
        assertTrue(TavilySearchProvider.parse("{}").isEmpty())
        assertTrue(TavilySearchProvider.parse("""{"results":[]}""").isEmpty())
    }

    @Test fun limitsResultsToFive() {
        val json = """{"results":[""" + (1..8).joinToString(",") {
            """{"title":"Result $it","content":"Description","url":"https://example.org/$it"}"""
        } + """]}"""
        assertEquals(5, TavilySearchProvider.parse(json).size)
    }
}
