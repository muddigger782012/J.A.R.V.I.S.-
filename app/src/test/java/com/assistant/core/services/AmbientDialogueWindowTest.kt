package com.assistant.core.services

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AmbientDialogueWindowTest {
    @Test fun answersAreRememberedOnlyForNinetySeconds() {
        var now = 1_000L
        val window = AmbientDialogueWindow { now }
        window.record("The microphone keeps cutting out", "Try checking the recorder state.")
        assertTrue(window.snapshot().contains("microphone keeps cutting out"))
        assertTrue(window.snapshot().contains("Try checking the recorder"))
        now += 90_001L
        assertEquals("", window.snapshot())
    }

    @Test fun onlyThreeBoundedExchangesSurvive() {
        val window = AmbientDialogueWindow { 1_000L }
        repeat(4) { i -> window.record("Question $i", "Answer $i") }
        val context = window.snapshot()
        assertFalse(context.contains("Question 0"))
        assertTrue(context.contains("Question 1"))
        assertTrue(context.contains("Question 3"))
        window.clear()
        assertEquals("", window.snapshot())
    }

    @Test fun memoryNeverStoresEmptyAnswersAndClipsProviderPayload() {
        val window = AmbientDialogueWindow { 1_000L }
        window.record("Unanswered speech", "  ")
        assertEquals("", window.snapshot())
        window.record("u".repeat(700), "a".repeat(900))
        val context = window.snapshot()
        assertTrue(context.contains("u".repeat(250)))
        assertFalse(context.contains("u".repeat(251)))
        assertTrue(context.contains("a".repeat(650)))
        assertFalse(context.contains("a".repeat(651)))
    }

    @Test fun backwardClockDropsPreviousConversation() {
        var now = 10_000L
        val window = AmbientDialogueWindow { now }
        window.record("First session", "Reply")
        now = 0L
        assertEquals("", window.snapshot())
    }
}
