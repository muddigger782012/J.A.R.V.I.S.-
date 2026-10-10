package com.assistant.core.services

import org.junit.Assert.*
import org.junit.Test

class CommandPhrasesTest {
    @Test fun primeMusicAliasesRouteLocally() {
        assertTrue(CommandPhrases.isAmazonMusicRequest("Play Prime music"))
        assertTrue(CommandPhrases.isAmazonMusicRequest("Please play Amazon Prime Music for me."))
        assertTrue(CommandPhrases.isAmazonMusicRequest("Open Amazon music"))
        assertFalse(CommandPhrases.isAmazonMusicRequest("What is Prime music?"))
        assertFalse(CommandPhrases.isAmazonMusicRequest("Play Spotify"))
    }
    @Test fun missedCallQuestionsAreLocalAndSpecific() {
        assertTrue(CommandPhrases.isLastMissedCall("Who was my last missed call?"))
        assertTrue(CommandPhrases.isLastMissedCall("Show my most recent missed call"))
        assertFalse(CommandPhrases.isLastMissedCall("Text John about my last missed call"))
        assertFalse(CommandPhrases.isLastMissedCall("What is a missed call"))
    }
    @Test fun mapsDirectionsExtractOnlyTheDestination() {
        val address = "140 Patrick Drive Hertford North Carolina"
        assertEquals(address, CommandPhrases.navigationDestination("Open maps to $address"))
        assertEquals(address, CommandPhrases.navigationDestination("Open maps and give me directions to $address"))
        assertEquals(address, CommandPhrases.navigationDestination("Please open Google maps and navigate to $address"))
        assertNull(CommandPhrases.navigationDestination("Open maps"))
    }
    @Test fun appRequestsRemoveCourtesyPhrases() {
        assertEquals("maps", CommandPhrases.appName("open maps for me"))
        assertEquals("maps", CommandPhrases.appName("please open maps for me please"))
        assertEquals("maps", CommandPhrases.appName("open maps"))
        assertNull(CommandPhrases.appName("open settings"))
    }
}
