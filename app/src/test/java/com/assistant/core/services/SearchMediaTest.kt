package com.assistant.core.services

import org.junit.Assert.*
import org.junit.Test

class SearchMediaTest {
    @Test fun onlyValidYoutubeUrlsProducePlayerIds() {
        for (url in listOf("https://www.youtube.com/watch?v=abcdefghijk&t=20", "https://youtu.be/abcdefghijk", "https://m.youtube.com/shorts/abcdefghijk")) {
            assertEquals("abcdefghijk", SearchMedia.youtubeId(url))
        }
        for (url in listOf("https://youtube.com.evil.org/watch?v=abcdefghijk", "https://example.org/abcdefghijk", "https://www.youtube.com/watch?v=bad", "https://user:pass@youtube.com/watch?v=abcdefghijk", "http://youtu.be/abcdefghijk")) {
            assertNull(SearchMedia.youtubeId(url))
        }
    }

    @Test fun parsesImagesAndVideoSourcesWithoutInventingMedia() {
        val json = """{"results":[{"title":"Repair video","content":"Instructions","url":"https://www.youtube.com/watch?v=abcdefghijk"}],"images":["https://example.org/a.jpg",{"url":"https://example.org/b.jpg","description":"Engine diagram"},{"url":"http://example.org/no.jpg"}]}"""
        val results = TavilySearchProvider.parseRich(json)
        assertEquals(3, results.size)
        assertEquals("video", results[0].kind)
        assertEquals("https://i.ytimg.com/vi/abcdefghijk/hqdefault.jpg", results[0].thumbnail)
        assertEquals("image", results[1].kind)
        assertEquals("Engine diagram", results[2].title)
        assertTrue(TavilySearchProvider.parseRich("{}").isEmpty())
    }

    @Test fun mediaSurvivesSavedConversationAndOldTextResultsStillLoad() {
        val items = listOf(SearchRouter.Result("Diagram", "Details", "https://example.org/a.jpg", "image", "https://example.org/a.jpg"),
            SearchMedia.webResult("Video", "Details", "https://youtu.be/abcdefghijk"))
        assertEquals(items, ChatSearchResults.decode(ChatSearchResults.encode("engine", items))!!.items)
        val old = """J.A.R.V.I.S.: [search-results-v1]{"query":"engine","results":[{"title":"Source","content":"Text","url":"https://example.org"}]}"""
        assertEquals("web", ChatSearchResults.decode(old)!!.items.single().kind)
    }

    @Test fun recognizesConversationalVideoRequests() {
        assertEquals("C7 injector replacement", WebSearchCommand.videoQuery("Show me videos of C7 injector replacement"))
        assertEquals("welding", WebSearchCommand.videoQuery("Can you find a video about welding?"))
        assertEquals("diesel repair", WebSearchCommand.videoQuery("Search the web for diesel repair videos"))
        assertNull(WebSearchCommand.videoQuery("Explain how video search works"))
    }
}
