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
        assertEquals("Caterpillar C7 injectors", WebSearchCommand.query("Find information about Caterpillar C7 injectors."))
        assertEquals("Ford 6.0 Power Stroke", WebSearchCommand.query("Find me info on Ford 6.0 Power Stroke."))
        assertEquals("Volvo D11 fault codes", WebSearchCommand.query("Look up Volvo D11 fault codes."))
    }

    @Test fun imageRequests() {
        assertEquals("Megan Fox", WebSearchCommand.imageQuery("Show me a picture of Megan Fox"))
        assertEquals("Carmen Electra", WebSearchCommand.imageQuery("find photos of Carmen Electra"))
        assertEquals("diesel engines", WebSearchCommand.imageQuery("search the web for diesel engines images"))
        assertNull(WebSearchCommand.imageQuery("Teach Jarvis to show me pictures"))
    }

    @Test fun conversationalImageRequests() {
        assertEquals("Ford F-550", WebSearchCommand.imageQueryNatural("Can you show me some pictures of a Ford F-550?"))
        assertEquals("service trucks", WebSearchCommand.imageQueryNatural("Could you find images of service trucks"))
        assertEquals("Megan Fox", WebSearchCommand.imageQueryNatural("Show me a picture of Megan Fox"))
        assertNull(WebSearchCommand.imageQueryNatural("Can you explain how image search works?"))
    }

    @Test fun ordinaryChatAndIncompleteSearchAreNotBrowserActions() {
        assertNull(WebSearchCommand.query("Teach Jarvis to search the web for content"))
        assertNull(WebSearchCommand.query("search the web for"))
        assertNull(WebSearchCommand.query("What's the weather today?"))
    }

    @Test fun downloadLinkRequestsUseRetrievalWithoutClaimingAnInstallation() {
        assertEquals("downloadable voice models replicating jarvis from iron man",
            WebSearchCommand.query("Find me a downloadable voice models replicating jarvis from iron man"))
        assertEquals("download links for offline British male voices",
            WebSearchCommand.query("Could you find me download links for offline British male voices?"))
        assertEquals("downloads for Piper voices", WebSearchCommand.query("Show me downloads for Piper voices."))
        assertEquals("links to download an offline voice", WebSearchCommand.query("Please get me links to download an offline voice"))
        for (text in listOf("Download and install this voice model", "Find my downloads folder", "Don't find downloadable models",
            "Explain how to find downloadable voices", "Find me a downloadable", "Find me download links for", "find me a downloadable " + "x".repeat(301)))
            assertNull(WebSearchCommand.query(text))
    }

    @Test fun downloadFollowupUsesOnlyAnExplicitImmediatelyPreviousTopic() {
        val previous = "Find me a downloadable voice models replicating jarvis from iron man"
        assertEquals(WebSearchCommand.query(previous), WebSearchCommand.downloadFollowupQuery(
            "You can't search for downloadable links available online?", previous))
        assertEquals("Piper voice downloads", WebSearchCommand.downloadFollowupQuery("Can you find download links?", "Look up Piper voice downloads"))
        assertNull(WebSearchCommand.downloadFollowupQuery("Can you find download links?", null))
        assertNull(WebSearchCommand.downloadFollowupQuery("Can you find download links?", "Open Chrome"))
        assertNull(WebSearchCommand.downloadFollowupQuery("Don't search for downloadable links", previous))
        assertNull(WebSearchCommand.downloadFollowupQuery("Can you install it?", previous))
    }

    @Test fun naturalMusicVideoRequestsRouteToSearch() {
        assertEquals("Saving Abel music video",
            WebSearchCommand.videoQuery("Find me the music video by Saving Abel"))
        assertEquals("Saving Abel music video",
            WebSearchCommand.videoQuery("Could you show me a music video from Saving Abel?"))
        assertEquals("Saving Abel music video",
            WebSearchCommand.videoQuery("Search for music videos by Saving Abel"))
        assertNull(WebSearchCommand.videoQuery("Tell me about the music video by Saving Abel"))
        assertNull(WebSearchCommand.videoQuery("Find me the music video by"))
    }

    @Test fun videoTitlesAndLinkFollowupsUseOnlyThePreviousExplicitTarget() {
        val previous = "Play diesel repair video"
        assertEquals("diesel repair", WebSearchCommand.videoQuery(previous))
        assertEquals("diesel repair", WebSearchCommand.videoQuery("Find me diesel repair videos"))
        assertEquals("diesel repair", WebSearchCommand.videoQuery("Find me a link to diesel repair video"))
        assertEquals("diesel repair", WebSearchCommand.videoFollowupQuery("Find me a link to the video instead", previous))
        assertEquals("welding", WebSearchCommand.videoFollowupQuery("Could you get a link to that video?", "Show me a video about welding"))
        assertNull(WebSearchCommand.videoQuery("Play Jarvis"))
        assertNull(WebSearchCommand.videoQuery("Play the video"))
        assertNull(WebSearchCommand.videoQuery("Find me a link to the video instead"))
        assertNull(WebSearchCommand.videoFollowupQuery("Find me a link to the video instead", "Open Chrome"))
        assertNull(WebSearchCommand.videoFollowupQuery("Find me a link to the video instead", null))
        assertNull(WebSearchCommand.videoFollowupQuery("Don't find me a link to the video", previous))
        assertNull(WebSearchCommand.videoFollowupQuery("Find me a link to the video and open Chrome", previous))
        assertNull(WebSearchCommand.videoFollowupQuery("Install it instead", previous))
        assertNull(WebSearchCommand.videoQuery("Play " + "x".repeat(301) + " video"))
    }
    @Test fun explicitMusicLookupRoutesToSearchWithoutCapturingContacts() {
        assertEquals("Saving Abel Addicted song", WebSearchCommand.musicQuery("Find me the song Saving Abel Addicted"))
        assertEquals("Saving Abel song", WebSearchCommand.musicQuery("Find music by Saving Abel"))
        assertNull(WebSearchCommand.musicQuery("Find me Saving Abel Addicted"))
        assertNull(WebSearchCommand.musicQuery("Call Freddie"))
        assertNull(WebSearchCommand.musicQuery("Find me the song"))
    }

}
