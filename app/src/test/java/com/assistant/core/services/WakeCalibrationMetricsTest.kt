package com.assistant.core.services

import org.junit.Assert.*
import org.junit.Test

class WakeCalibrationMetricsTest {
    @Test fun emptyPracticeHasNoMisleadingSuccessRate() {
        val metrics = WakeCalibrationMetrics()
        assertEquals(0, metrics.snapshot().attempts)
        assertNull(metrics.snapshot().detectionRatePercent)
    }

    @Test fun restoredCountsAreValidated() {
        val metrics = WakeCalibrationMetrics()
        metrics.restore(7, 3)
        assertEquals(10L, metrics.snapshot().attempts)
        assertEquals(70.0, metrics.snapshot().detectionRatePercent!!, 0.00001)
        assertThrows(IllegalArgumentException::class.java) { metrics.restore(-1, 0) }
        assertEquals(10L, metrics.snapshot().attempts)
    }

    @Test fun recordsOnlyExplicitAttemptsAndCanReset() {
        val metrics = WakeCalibrationMetrics()
        metrics.recordAttempt(true)
        metrics.recordAttempt(false)
        metrics.recordAttempt(true)
        val result = metrics.snapshot()
        assertEquals(3, result.attempts)
        assertEquals(2, result.detected)
        assertEquals(1, result.missed)
        assertEquals(200.0 / 3.0, result.detectionRatePercent!!, 0.00001)
        metrics.reset()
        assertEquals(0, metrics.snapshot().attempts)
        assertNull(metrics.snapshot().detectionRatePercent)
    }
}
