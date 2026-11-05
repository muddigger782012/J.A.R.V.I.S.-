package com.assistant.core.services

/** A running speech turn is only stalled when no synthesis or playback has advanced. */
internal class SpeechProgressGuard(private val now: () -> Long) {
    private var lastProgress = 0L
    private var active = false

    @Synchronized fun start() {
        lastProgress = now()
        active = true
    }
    @Synchronized fun advance() {
        if (active) lastProgress = now()
    }
    @Synchronized fun finish() { active = false }
    @Synchronized fun recent(staleMillis: Long): Boolean =
        active && now() - lastProgress < staleMillis
}
