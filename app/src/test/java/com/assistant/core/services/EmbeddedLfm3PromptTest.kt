package com.assistant.core.services

import org.junit.Assert.*
import org.junit.Test

class EmbeddedLfm3PromptTest {
    @Test fun newestUserQuestionAlwaysSurvivesLargeConversationHistory() {
        val history = (0..20).map { i -> ("old user $i " + "x".repeat(3_000)) to ("old reply $i " + "z".repeat(3_000)) }
        val newest = "Please diagnose the new fuel-pressure issue. UNIQUE_LATEST_QUESTION"
        val prompt = EmbeddedLfm3Prompt.compose(newest, history)
        assertTrue(prompt.length <= EmbeddedLfm3Prompt.MAX_PROMPT_CHARS)
        assertTrue(prompt.endsWith("\nUser: $newest"))
        assertFalse(prompt.contains("old user 0 "))
        assertTrue(prompt.contains("old user 20"))
    }

    @Test fun recentHistoryIsInConversationOrderAndOldestTurnsAreDropped() {
        val history = (1..12).map { "question-$it" to "answer-$it" }
        val prompt = EmbeddedLfm3Prompt.compose("question-13", history)
        assertFalse(prompt.contains("question-1\n"))
        assertTrue(prompt.contains("question-12"))
        assertTrue(prompt.indexOf("question-11") < prompt.indexOf("question-12"))
        assertTrue(prompt.endsWith("\nUser: question-13"))
    }

    @Test fun aLongNewUserTurnHasPriorityOverPastConversation() {
        val question = "q".repeat(20_000)
        val prompt = EmbeddedLfm3Prompt.compose(question, listOf("past" to "answer"))
        assertEquals(EmbeddedLfm3Prompt.MAX_QUESTION_CHARS, prompt.substringAfterLast("\nUser: ").length)
        assertTrue(prompt.endsWith("q".repeat(EmbeddedLfm3Prompt.MAX_QUESTION_CHARS)))
        assertTrue(prompt.length <= EmbeddedLfm3Prompt.MAX_PROMPT_CHARS)
    }

    @Test fun supersededTurnCannotReturnAsCurrentConversationReply() {
        val turns = AssistantResponseTurns()
        val previous = turns.begin()
        turns.inTurn(previous) { assertTrue(turns.current()) }
        turns.cancel()
        val latest = turns.begin()
        assertFalse(turns.current(previous))
        assertTrue(turns.current(latest))
        turns.inTurn(previous) { assertFalse(turns.current()) }
    }
}
