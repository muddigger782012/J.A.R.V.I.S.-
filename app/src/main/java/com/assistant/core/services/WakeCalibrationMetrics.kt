package com.assistant.core.services

/**
 * Explicit, user-confirmed wake-word practice results.
 *
 * A missed wake phrase cannot be inferred from microphone silence. The user must
 * mark each attempt as detected or missed; no audio or transcript is stored.
 */
class WakeCalibrationMetrics {
    private var detected = 0
    private var missed = 0

    fun recordAttempt(wasDetected: Boolean) {
        if (wasDetected) detected++ else missed++
    }

    fun snapshot(): Snapshot = Snapshot(detected, missed)

    fun reset() {
        detected = 0
        missed = 0
    }

    data class Snapshot(val detected: Int, val missed: Int) {
        val attempts: Int get() = detected + missed
        val detectionRatePercent: Double?
            get() = if (attempts == 0) null else detected * 100.0 / attempts
    }
}
