package com.assistant.core.services

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ContactNameMatcherTest {
    @Test fun spokenKeyDogMatchesSavedKeydawg() {
        assertEquals(ContactNameMatcher.normalize("Keydawg"), ContactNameMatcher.normalize("Key Dog"))
        assertEquals(ContactNameMatcher.normalize("Keydawg"), ContactNameMatcher.normalize("key dawg"))
    }

    @Test fun punctuationAndCaseAreNormalized() {
        assertEquals("mary jane", ContactNameMatcher.normalize("MARY-JANE"))
    }

    @Test fun refusesMultiplePhoneNumbersForSameContact() {
        assertNull(ContactNameMatcher.uniquePhoneFor("Key Dog", listOf(
            "Keydawg" to "555-0101",
            "Keydawg" to "555-0102"
        )))
    }

    @Test fun acceptsDuplicateRowsWithSameNumber() {
        assertEquals("555-0101", ContactNameMatcher.uniquePhoneFor("Key Dog", listOf(
            "Keydawg" to "555-0101",
            "Keydawg" to "(555) 0101"
        )))
    }

    @Test fun refusesAmbiguousPartialName() {
        assertNull(ContactNameMatcher.uniquePhoneFor("Alex", listOf(
            "Alex Smith" to "555-0101",
            "Alex Jones" to "555-0102"
        )))
    }
}
