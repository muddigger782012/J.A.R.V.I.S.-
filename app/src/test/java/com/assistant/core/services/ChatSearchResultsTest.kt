package com.assistant.core.services

import org.junit.Assert.*
import org.junit.Test

class ChatSearchResultsTest {
    @Test fun speechReadsTheAnswerWithoutSerializingSourceCardsAndKeepsOrdinaryVoiceText() {
        val encoded = ChatSearchResults.encode("solar panels", listOf(SearchRouter.Result("Guide", "Excerpt", "https://example.com/guide")),
            "**Source excerpt:** Panel output depends on sunlight. See https://example.com/guide")
        val spoken = ChatSearchResults.forSpeech(encoded)
        assertTrue(spoken.contains("Panel output depends on sunlight"))
        assertFalse(spoken.contains("search-results-v1")); assertFalse(spoken.contains("https://"))
        assertEquals("Hello, how can I help?", ChatSearchResults.forSpeech("Hello, how can I help?"))
        assertFalse(ChatSearchResults.forSpeech("J.A.R.V.I.S.: [search-results-v1]{broken").contains("{broken"))
    }
    @Test fun savedChatRestoresSourceFieldsWithNewlines() {
        val item = SearchRouter.Result("A title", "Two\nparagraphs", "https://example.org/a?q=1")
        val message = ChatSearchResults.encode("diesel repair", listOf(item))
        assertEquals(message, ConversationMessages.chatMessage(message))
        val restored = ChatSearchResults.decode(message)!!
        assertEquals("diesel repair", restored.query)
        assertEquals(item, restored.items.single())
    }
    @Test fun rejectsMalformedAndCredentialLinks() {
        listOf("https://", "https:///bad", "https://user:pass@example.org", "javascript:alert(1)", "http://example.org").forEach {
            assertFalse(ChatSearchResults.safeUrl(it))
        }
        assertNull(ChatSearchResults.decode("J.A.R.V.I.S.: [search-results-v1]{"))
        assertNull(ChatSearchResults.decode("J.A.R.V.I.S.: Ordinary response"))
    }
}
