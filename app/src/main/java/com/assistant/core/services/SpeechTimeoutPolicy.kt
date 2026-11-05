package com.assistant.core.services

/** 
 * Offline speech can synthesize slower than real time on mobile CPUs.
 * Never cut off the last sentences using a short character-count guess.
 * A manual stop command still interrupts a reply immediately.
 */
internal object SpeechTimeoutPolicy {
    fun maximumReplyMillis(visibleCharacters: Int): Long =
        (visibleCharacters.coerceAtLeast(0).toLong() * 200L + 60_000L)
            .coerceIn(120_000L, 900_000L)
}
