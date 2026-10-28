package com.assistant.core.services

object WakeWordTuning {
    fun thresholdFor(sensitivity: Float): Float {
        val value = if (sensitivity.isFinite()) sensitivity.coerceIn(0f, 1f) else 0.8f
        // Increase detection sensitivity in the usual 0.6–0.9 slider range,
        // while preserving the existing safe positive lower bound.
        return (0.07f - value * 0.065f).coerceIn(0.005f, 0.07f)
    }
}
