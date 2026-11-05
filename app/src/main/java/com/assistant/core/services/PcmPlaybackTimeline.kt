package com.assistant.core.services

/**
 * Accounting for one continuous output track: all decoded voice chunks use
 * the same sample rate and share one monotonically advancing frame counter.
 */
internal class PcmPlaybackTimeline {
    var sampleRate: Int = 0
        private set
    var framesQueued: Long = 0L
        private set

    fun accept(rate: Int, writtenFrames: Int) {
        require(rate in 8_000..48_000) { "Unsupported PCM sample rate" }
        require(writtenFrames > 0) { "PCM write must advance" }
        if (sampleRate == 0) sampleRate = rate
        require(sampleRate == rate) { "Sample rate changed between voice chunks" }
        framesQueued += writtenFrames
    }

    fun isDrained(headPositionFrames: Long): Boolean = headPositionFrames >= framesQueued
}
