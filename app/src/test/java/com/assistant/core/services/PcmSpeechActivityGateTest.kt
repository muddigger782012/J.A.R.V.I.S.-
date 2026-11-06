package com.assistant.core.services

import org.junit.Assert.*
import org.junit.Test

class PcmSpeechActivityGateTest {
    private val quiet = ShortArray(320) { 100 }
    private val voice = ShortArray(320) { if (it % 2 == 0) 4200 else -4200 }

    @Test fun ignoresAmbientNoiseThenStartsAfterConfirmedSpeech() {
        val gate = PcmSpeechActivityGate()
        repeat(50) { assertEquals(PcmSpeechActivityGate.Event.NONE, gate.accept(quiet)) }
        repeat(4) { assertEquals(PcmSpeechActivityGate.Event.NONE, gate.accept(voice)) }
        assertEquals(PcmSpeechActivityGate.Event.STARTED, gate.accept(voice))
        assertTrue(gate.inSpeech)
    }

    @Test fun toleratesNaturalPausesAndEndsAfterBoundedSilence() {
        val gate = PcmSpeechActivityGate()
        repeat(5) { gate.accept(voice) }
        repeat(100) { assertEquals(PcmSpeechActivityGate.Event.NONE, gate.accept(quiet)) }
        assertTrue(gate.inSpeech)
        gate.accept(voice)
        repeat(159) { assertEquals(PcmSpeechActivityGate.Event.NONE, gate.accept(quiet)) }
        assertEquals(PcmSpeechActivityGate.Event.ENDED, gate.accept(quiet))
        assertFalse(gate.inSpeech)
    }

    @Test fun resetDiscardsPriorSessionAndNoisyFrameCannotStartTurn() {
        val gate = PcmSpeechActivityGate()
        repeat(5) { gate.accept(voice) }
        gate.reset()
        assertFalse(gate.inSpeech)
        assertEquals(PcmSpeechActivityGate.Event.NONE, gate.accept(voice))
        assertEquals(PcmSpeechActivityGate.Event.NONE, gate.accept(ShortArray(0)))
        assertEquals(PcmSpeechActivityGate.Event.NONE, gate.accept(quiet, count = -1))
    }
}
