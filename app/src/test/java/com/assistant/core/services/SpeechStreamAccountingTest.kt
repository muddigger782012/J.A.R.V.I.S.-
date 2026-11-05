package com.assistant.core.services

import org.junit.Assert.*
import org.junit.Test

class SpeechStreamAccountingTest {
    @Test fun callbackSegmentsAndFinalAudioArePlayedOnce() {
        val a = SpeechStreamAccounting()
        a.receivedCallback(800)
        a.receivedCallback(1200)
        assertEquals(2000, a.undeliveredStart(2000))
        assertEquals(2000, a.deliveredFrames)
    }

    @Test fun partialOrMissingCallbacksAreRecoveredFromFinalSamples() {
        val partial = SpeechStreamAccounting()
        partial.receivedCallback(800)
        assertEquals(800, partial.undeliveredStart(2200))
        assertEquals(0, SpeechStreamAccounting().undeliveredStart(2200))
    }

    @Test fun invalidCumulativeCallbackCannotProduceRepeatedAudio() {
        val a = SpeechStreamAccounting()
        a.receivedCallback(1000)
        a.receivedCallback(1100)
        assertThrows(IllegalArgumentException::class.java) { a.undeliveredStart(1500) }
    }
}
