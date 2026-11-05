package com.assistant.core.services

/**
 * Sherpa's streaming callback supplies PCM sentence segments. The returned
 * GeneratedAudio may also contain the entire utterance. Never play that
 * combined result twice or silently discard an un-delivered final segment.
 */
internal class SpeechStreamAccounting {
    var deliveredFrames: Int = 0
        private set
    fun receivedCallback(frames: Int) {
        require(frames > 0)
        deliveredFrames = Math.addExact(deliveredFrames, frames)
    }
    fun undeliveredStart(totalFrames: Int): Int {
        require(totalFrames >= deliveredFrames) { "Native audio segments exceeded final generated PCM" }
        return deliveredFrames
    }
}
