package com.assistant.core.services

import org.junit.Assert.assertEquals
import org.junit.Test

class ContactNameMatcherTest {
    @Test fun spokenKeyDogMatchesSavedKeydawg() {
        assertEquals(ContactNameMatcher.normalize("Keydawg"), ContactNameMatcher.normalize("Key Dog"))
        assertEquals(ContactNameMatcher.normalize("Keydawg"), ContactNameMatcher.normalize("key dawg"))
    }

    @Test fun punctuationAndCaseAreNormalized() {
        assertEquals("mary jane", ContactNameMatcher.normalize("MARY-JANE"))
    }
}
