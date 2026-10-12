package com.assistant.core.services

import org.junit.Assert.*
import org.junit.Test

class NeuralPcmTest {
    @Test fun conversionRetainsAudioPolarityAndSaturatesWithoutWrapping() {
        assertArrayEquals(shortArrayOf(-32768, -32768, -16384, 0, 16384, 32767, 32767),
            NeuralPcm.pcm16(floatArrayOf(-2f, -1f, -0.5f, 0f, 0.5f, 1f, 2f)))
    }
    @Test fun invalidSamplesCannotEnterAudioTrack() {
        assertTrue(runCatching { NeuralPcm.pcm16(floatArrayOf(Float.NaN)) }.isFailure)
        assertTrue(runCatching { NeuralPcm.pcm16(floatArrayOf(Float.POSITIVE_INFINITY)) }.isFailure)
        assertTrue(runCatching { NeuralPcm.pcm16(floatArrayOf()) }.isFailure)
    }
}
