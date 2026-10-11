package com.assistant.core.services

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class WebSearchCommandTest {
    @Test fun explicitWebSearch() {
        assertEquals("Carmen Electra photos", WebSearchCommand.query("Search the web for Carmen Electra photos"))
        assertEquals("diesel wiring diagram", WebSearchCommand.query("please search web for diesel wiring diagram"))
        assertEquals("truck parts", WebSearchCommand.query("Google truck parts"))
        assertEquals("weather forecast", WebSearchCommand.query("look up weather forecast"))
    }

    @Test fun imageRequests() {
        assertEquals("Megan Fox", WebSearchCommand.imageQuery("Show me a picture of Megan Fox"))
        assertEquals("Carmen Electra", WebSearchCommand.imageQuery("find photos of Carmen Electra"))
        assertEquals("diesel engines", WebSearchCommand.imageQuery("search the web for diesel engines images"))
        assertNull(WebSearchCommand.imageQuery("Teach Jarvis to show me pictures"))
    }

    @Test fun ordinaryChatAndIncompleteSearchAreNotBrowserActions() {
        assertNull(WebSearchCommand.query("Teach Jarvis to search the web for content"))
        assertNull(WebSearchCommand.query("search the web for"))
        assertNull(WebSearchCommand.query("What's the weather today?"))
    }
}
