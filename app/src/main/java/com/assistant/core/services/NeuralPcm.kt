package com.assistant.core.services

import kotlin.math.roundToInt

object NeuralPcm {
    fun pcm16(samples: FloatArray): ShortArray {
        require(samples.isNotEmpty() && samples.all { it.isFinite() })
        return ShortArray(samples.size) { index ->
            val value = samples[index].coerceIn(-1f, 1f)
            (value * if (value < 0) 32768f else 32767f).roundToInt().coerceIn(-32768, 32767).toShort()
        }
    }
}
