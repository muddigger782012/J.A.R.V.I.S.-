package com.assistant.core.services

import org.junit.Assert.*
import org.junit.Test

class WakeWordTuningTest {
    @Test fun existingSliderSettingBecomesMoreSensitive() {
        assertTrue(WakeWordTuning.thresholdFor(0.6f) < 0.05f)
        assertTrue(WakeWordTuning.thresholdFor(0.8f) < WakeWordTuning.thresholdFor(0.6f))
    }

    @Test fun thresholdsRemainPositiveAndBounded() {
        for (value in listOf(-1f, 0f, 0.6f, 1f, 2f, Float.NaN)) {
            assertTrue(WakeWordTuning.thresholdFor(value) in 0.005f..0.085f)
        }
    }
}
