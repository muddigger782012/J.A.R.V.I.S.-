package com.assistant.core.services

import org.junit.Assert.*
import org.junit.Test

class PcmTurnHealthPolicyTest {
    @Test fun repeatedSilentTurnsRestoreStandardMicrophoneWithoutEndlessRetry() {
        val gate = PcmTurnHealthPolicy()
        assertFalse(gate.recordNoTranscript(false))
        assertFalse(gate.recordNoTranscript(false))
        assertEquals(2, gate.pendingEmptyTurns())
        assertTrue(gate.recordNoTranscript(false))
        assertEquals(3, gate.pendingEmptyTurns())
        assertTrue(gate.recordNoTranscript(false))
    }

    @Test fun missingLocallyObservedSpeechImmediatelyTriggersSafeFallback() {
        val gate = PcmTurnHealthPolicy()
        assertTrue(gate.recordNoTranscript(true))
        assertEquals(0, gate.pendingEmptyTurns())
    }

    @Test fun successfulTranscriptionResetsSilentFailureCount() {
        val gate = PcmTurnHealthPolicy()
        assertFalse(gate.recordNoTranscript(false))
        gate.recordSuccessfulTranscript()
        assertFalse(gate.recordNoTranscript(false))
        assertFalse(gate.recordNoTranscript(false))
        assertTrue(gate.recordNoTranscript(false))
    }

    @Test fun newSessionStartsWithACompleteRetryBudget() {
        val gate = PcmTurnHealthPolicy()
        assertFalse(gate.recordNoTranscript(false))
        gate.reset()
        assertEquals(0, gate.pendingEmptyTurns())
        assertFalse(gate.recordNoTranscript(false))
    }

    @Test fun fallbackThresholdIsBoundedAndTestable() {
        val gate = PcmTurnHealthPolicy(maxEmptyTurns = 2)
        assertFalse(gate.recordNoTranscript(false))
        assertTrue(gate.recordNoTranscript(false))
    }
}
