package com.assistant.core.services

import org.junit.Assert.*
import org.junit.Test

class LiveChatInputRecoveryTest {
    @Test fun singleSystemProbeAfterTwoSilentOnDeviceTurns() {
        val recovery = LiveChatInputRecovery()
        assertFalse(recovery.shouldProbeSystem(true, 1, false, true))
        assertTrue(recovery.shouldProbeSystem(true, 2, false, true))
        assertFalse(recovery.shouldProbeSystem(true, 20, false, true))
    }

    @Test fun neverStealsPcmOrProbesUnavailableOrSystemProviders() {
        val recovery = LiveChatInputRecovery()
        assertFalse(recovery.shouldProbeSystem(false, 8, false, true))
        assertFalse(recovery.shouldProbeSystem(true, 8, true, true))
        assertFalse(recovery.shouldProbeSystem(true, 8, false, false))
        assertTrue(recovery.shouldProbeSystem(true, 2, false, true))
    }

    @Test fun manualRecoveryOrRealTranscriptResetsSystemProbeBudget() {
        val recovery = LiveChatInputRecovery()
        assertTrue(recovery.shouldProbeSystem(true, 2, false, true))
        assertFalse(recovery.shouldProbeSystem(true, 2, false, true))
        recovery.reset()
        assertTrue(recovery.shouldProbeSystem(true, 2, false, true))
    }
}
