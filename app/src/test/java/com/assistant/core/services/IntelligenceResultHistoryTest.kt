package com.assistant.core.services

import org.junit.Assert.*
import org.junit.Test

class IntelligenceResultHistoryTest {
    @Test fun boundsHistoryAndRestoresNewestFirstWithoutWritingOnReview() {
        var raw: String? = null
        var writes = 0
        var clock = 100L
        val history = IntelligenceResultHistory({ raw }, { raw = it; writes++; true }, { clock++ })
        repeat(105) { assertTrue(history.record("phrase $it", "open Chrome", IntelligenceResultHistory.Outcome.USER_CONFIRMED, "No new phrase saved.")) }
        val before = writes
        val restored = IntelligenceResultHistory({ raw }, { error("Review must not write") }).snapshot()
        assertEquals(100, restored.size)
        assertEquals("phrase 104", restored.first().phrase)
        assertEquals("phrase 5", restored.last().phrase)
        assertEquals(before, writes)
        assertFalse(raw!!.contains("package"))
    }

    @Test fun unclearAndFailureNeverBecomeVerifiedSuccess() {
        var raw: String? = null
        val history = IntelligenceResultHistory({ raw }, { raw = it; true }, { 42 })
        history.record("quiet timer", "start a 300-second timer", IntelligenceResultHistory.Outcome.UNCLEAR, "Confirmation still pending; nothing learned.")
        history.record("quiet timer", "start a 300-second timer", IntelligenceResultHistory.Outcome.USER_FAILED, "Learned phrase paused.")
        assertEquals(listOf(IntelligenceResultHistory.Outcome.USER_FAILED, IntelligenceResultHistory.Outcome.UNCLEAR), history.snapshot().map { it.outcome })
        assertTrue(history.clear())
        assertTrue(history.snapshot().isEmpty())
    }

    @Test fun rejectsInvalidEntriesAndReportsStorageFailure() {
        var writes = 0
        val history = IntelligenceResultHistory({ "[{\"outcome\":\"UNKNOWN\"}]" }, { writes++; false }, { 1 })
        assertTrue(history.snapshot().isEmpty())
        assertFalse(history.record("", "open Chrome", IntelligenceResultHistory.Outcome.USER_CONFIRMED, "confirmed"))
        assertEquals(0, writes)
        assertFalse(history.record("open Chrome", "open Chrome", IntelligenceResultHistory.Outcome.USER_CONFIRMED, "confirmed"))
        assertEquals(1, writes)
        assertFalse(history.clear())
    }
}
