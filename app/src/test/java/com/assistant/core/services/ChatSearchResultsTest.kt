package com.assistant.core.services

import org.junit.Assert.*
import org.junit.Test

class ChatSearchResultsTest {
    @Test fun savedChatRestoresSourceFieldsWithNewlines() {
        val item = TavilySearchProvider.Result("A title", "Two\nparagraphs", "https://example.org/a?q=1")
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
