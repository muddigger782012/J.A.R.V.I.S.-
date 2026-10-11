package com.assistant.core.services

object WakeWordTuning {
    fun thresholdFor(sensitivity: Float): Float {
        val value = if (sensitivity.isFinite()) sensitivity.coerceIn(0f, 1f) else 0.8f
        return (0.085f - value * 0.08f).coerceIn(0.005f, 0.085f)
    }
}
