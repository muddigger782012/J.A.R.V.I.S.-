package com.assistant.core.services

import java.util.ArrayDeque

/**
 * Small in-memory speech preroll (typically 25 × 20 ms).
 * A recognizer launched after voice onset gets the start of the utterance.
 * This buffer never persists audio and is cleared between turns.
 */
internal class PcmPreRollBuffer(private val maximumFrames: Int = 25) {
    init { require(maximumFrames in 1..250) }
    private val frames = ArrayDeque<ByteArray>()
    fun offer(frame: ByteArray) {
        if (frame.isEmpty()) return
        frames.addLast(frame.copyOf())
        while (frames.size > maximumFrames) frames.removeFirst()
    }
    fun drain(): List<ByteArray> {
        val out = frames.toList()
        frames.clear()
        return out
    }
    fun clear() = frames.clear()
    val size get() = frames.size
}
