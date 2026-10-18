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
        if (wasDetected && detected < Int.MAX_VALUE) detected++
        else if (!wasDetected && missed < Int.MAX_VALUE) missed++
    }

    fun snapshot(): Snapshot = Snapshot(detected, missed)

    fun restore(savedDetected: Int, savedMissed: Int) {
        require(savedDetected >= 0 && savedMissed >= 0) { "Counts must be nonnegative" }
        detected = savedDetected
        missed = savedMissed
    }

    fun reset() {
        detected = 0
        missed = 0
    }

    data class Snapshot(val detected: Int, val missed: Int) {
        val attempts: Long get() = detected.toLong() + missed.toLong()
        val detectionRatePercent: Double?
            get() = if (attempts == 0) null else detected * 100.0 / attempts
    }
}
