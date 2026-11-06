package com.assistant.core.services

import kotlin.math.max
import kotlin.math.sqrt

/**
 * Lightweight PCM turn gate for experimental microphone ownership.
 * This is an energy/activity detector, NOT speaker identity or a neural VAD.
 * A validated neural detector will be needed for heavy machinery and loud rooms.
 */
internal class PcmSpeechActivityGate(
    private val onsetFrames: Int = 5,
    private val endQuietFrames: Int = 160
) {
    enum class Event { NONE, STARTED, ENDED }
    private var baseline = 110.0
    private var onset = 0
    private var quiet = 0
    private var speaking = false
    val inSpeech get() = speaking

    fun reset() {
        baseline = 110.0
        onset = 0
        quiet = 0
        speaking = false
    }

    fun accept(buffer: ShortArray, count: Int = buffer.size): Event {
        if (count <= 0 || count > buffer.size) return Event.NONE
        var power = 0.0
        for (i in 0 until count) {
            val v = buffer[i].toDouble()
            power += v * v
        }
        val rms = sqrt(power / count)
        val energy = rms >= max(550.0, baseline * 2.8)
        if (!speaking) {
            // Slowly track ordinary room noise without chasing a sudden voice.
            if (!energy) baseline = (baseline * 0.985 + rms * 0.015).coerceIn(70.0, 3000.0)
            onset = if (energy) onset + 1 else 0
            if (onset >= onsetFrames) {
                speaking = true
                quiet = 0
                return Event.STARTED
            }
            return Event.NONE
        }
        quiet = if (energy) 0 else quiet + 1
        if (quiet >= endQuietFrames) {
            speaking = false
            onset = 0
            quiet = 0
            return Event.ENDED
        }
        return Event.NONE
    }
}
