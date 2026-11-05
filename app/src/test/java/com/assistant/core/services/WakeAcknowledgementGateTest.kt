package com.assistant.core.services

import org.junit.Assert.*
import org.junit.Test

class WakeAcknowledgementGateTest {
    @Test fun singleWakeGetsOneToneWithoutRepeatingForDecoderCallbacks() {
        val gate = WakeAcknowledgementGate(1500L)
        assertTrue(gate.shouldPlay(100))
        assertFalse(gate.shouldPlay(120))
        assertFalse(gate.shouldPlay(1499))
        assertTrue(gate.shouldPlay(1600))
    }

    @Test fun uptimeResetDoesNotDisableToneForever() {
        val gate = WakeAcknowledgementGate()
        assertTrue(gate.shouldPlay(1_000_000))
        assertTrue(gate.shouldPlay(50))
    }
}
