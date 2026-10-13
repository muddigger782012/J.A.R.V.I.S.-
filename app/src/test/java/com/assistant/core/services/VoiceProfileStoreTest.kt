package com.assistant.core.services

import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class VoiceProfileStoreTest {
    @get:Rule val temporary = TemporaryFolder()
    @Test fun approvalPersistsVocabularyAcrossFreshReadersWithoutRewritingHeardText() {
        val store = VoiceProfileStore(temporary.root)
        assertTrue(store.approve("Troy capability request number one", "Try capability request number one", "en-US", "device", false))
        val other = VoiceProfileStore(temporary.root)
        assertEquals(listOf("Try capability request number one"), other.hints("en-US"))
        assertTrue(other.hints("fr-FR").isEmpty())
        assertEquals("Troy capability request number one", other.entries().single().heard)
        assertFalse(other.entries().single().matched)
        assertTrue(other.remove(0)); assertTrue(store.hints("en-US").isEmpty())
    }
    @Test fun scoresRemainOriginalEvenAfterApprovedCorrectionAndTrackEngineAndHints() {
        val store = VoiceProfileStore(temporary.root)
        store.approve("Open Chrome!", "open chrome", "en-US", "default", true)
        store.approve("word break", "work break", "en-US", "device", false)
        assertEquals(listOf(true, false), store.entries().map { it.matched })
        assertEquals(listOf("default", "device"), store.entries().map { it.mode })
        assertEquals(listOf(true, false), store.entries().map { it.hinted })
    }
    @Test fun boundsAndCorruptionCannotFeedUnvalidatedHints() {
        val store = VoiceProfileStore(temporary.root)
        assertFalse(store.approve("heard", "\n", "en-US", "device", false))
        assertFalse(store.approve("heard", "x".repeat(161), "en-US", "device", false))
        assertFalse(store.approve("heard", "hello", "invalid language", "device", false))
        assertFalse(store.approve("heard", "hello", "en-US", "unknown", false))
        repeat(220) { store.approve("phrase $it", "phrase $it", "en-US", "device", false) }
        assertEquals(200, store.entries().size); assertEquals(50, store.hints("en-US").size)
        assertEquals("phrase 219", store.hints("en-US").first())
        File(temporary.root, "my-voice-profile.json").writeText("broken")
        assertTrue(store.hints("en-US").isEmpty())
        assertTrue(store.clear()); assertTrue(store.entries().isEmpty())
    }
    @Test fun repeatedApprovedWordingProducesOneHintButRetainsBothPracticeScores() {
        val store = VoiceProfileStore(temporary.root)
        store.approve("word break", "work break", "en-US", "device", false)
        store.approve("work break", "Work break", "en-US", "device", true)
        assertEquals(1, store.hints("en-US").size)
        assertEquals(2, store.entries().size)
        assertTrue(store.clear()); assertTrue(VoiceProfileStore(temporary.root).hints("en-US").isEmpty())
    }
}
