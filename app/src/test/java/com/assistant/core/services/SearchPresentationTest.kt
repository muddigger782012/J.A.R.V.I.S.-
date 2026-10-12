package com.assistant.core.services

import org.junit.Assert.*
import org.junit.Test

class SearchPresentationTest {
    @Test fun readableAnswerKeepsCitationsWhileFormattingMarkdown() {
        val raw = "## Voice options\n\n* **Piper**: use `offline` models [1].\n* [Model page](https://example.org/model) [2]."
        val result = SearchReadableText.format(raw)
        assertEquals("Voice options\n\n• Piper: use offline models [1].\n• Model page [2].", result.text)
        assertEquals(listOf("Voice options", "Piper"), result.emphasis.map { result.text.substring(it.start, it.end) })
        assertFalse(result.text.contains("https://"))
        assertEquals("Title", SearchReadableText.excerpt("### **Title**"))
        assertTrue(SearchReadableText.excerpt("long ".repeat(200)).length <= 220)
        assertEquals("first\n\nlast", SearchReadableText.format("first\n\n\n\nlast").text)
    }

    @Test fun completedSearchHasOneCardAndOtherTurnsAreRetained() {
        val ready = "J.A.R.V.I.S.: Web search ready: voice models\nhttps://www.google.com/search?q=voice+models"
        val result = ChatSearchResults.encode("voice models", listOf(SearchRouter.Result("Source", "Short", "https://example.org")), "Answer [1].")
        val lines = listOf("You: Find voice models", ready, result)
        assertEquals(listOf(lines.first(), result), SearchConversationPresentation.visible(lines))
        assertEquals(listOf(ready), SearchConversationPresentation.visible(listOf(ready)))
        assertEquals(listOf(ready, "You: New topic", result), SearchConversationPresentation.visible(listOf(ready, "You: New topic", result)))
        val other = ChatSearchResults.encode("truck parts", emptyList())
        assertEquals(listOf(ready, other), SearchConversationPresentation.visible(listOf(ready, other)))
    }

    @Test fun explicitMediaKindSurvivesSavedChatWithoutTurningWebAnswersIntoImageGalleries() {
        val image = SearchRouter.Result("Photo", "Preview", "https://example.org/photo", "image", "https://example.org/thumb")
        val web = SearchRouter.Result("Model", "Description", "https://example.org/model")
        assertEquals("web", ChatSearchResults.decode(ChatSearchResults.encode("models", listOf(web, image)))!!.kind)
        assertEquals("image", ChatSearchResults.decode(ChatSearchResults.encode("engines", listOf(image), kind = "image"))!!.kind)
        assertEquals("video", ChatSearchResults.decode(ChatSearchResults.encode("repair", listOf(web), kind = "video"))!!.kind)
        val old = """J.A.R.V.I.S.: [search-results-v1]{"query":"engines","results":[{"title":"Photo","url":"https://example.org/photo","kind":"image"}]}"""
        assertEquals("image", ChatSearchResults.decode(old)!!.kind)
    }
}
