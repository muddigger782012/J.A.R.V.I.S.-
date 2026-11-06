package com.assistant.core.services

/**
 * Keeps the opt-in external-PCM capture from endlessly retrying a provider
 * that silently ignores its injected audio source.
 *
 * Only the explicitly enabled experimental path uses this policy. The legacy
 * microphone, wake word and commercial Beta are unaffected.
 */
internal class PcmTurnHealthPolicy(private val maxEmptyTurns: Int = 3) {
    init { require(maxEmptyTurns >= 1) }
    private var emptyTurns = 0

    fun reset() { emptyTurns = 0 }
    fun recordSuccessfulTranscript() = reset()

    /**
     * @return true if the session should release PCM ownership and restore the
     * normal recognizer. Speech observed locally but missing in the result
     * fails over immediately; persistent quiet times out after maxEmptyTurns.
     */
    fun recordNoTranscript(localSpeechObserved: Boolean): Boolean {
        if (localSpeechObserved) {
            reset()
            return true
        }
        emptyTurns = (emptyTurns + 1).coerceAtMost(maxEmptyTurns)
        return emptyTurns >= maxEmptyTurns
    }

    fun pendingEmptyTurns(): Int = emptyTurns
}
