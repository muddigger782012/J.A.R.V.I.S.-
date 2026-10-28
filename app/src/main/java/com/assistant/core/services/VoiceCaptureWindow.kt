package com.assistant.core.services

/** Activity watchdog, not a replacement for a provider's endpoint detector. */
class VoiceCaptureWindow(private val now: () -> Long, private val idleMillis: Long = 30_000,
    private val finalMillis: Long = 20_000, private val maximumMillis: Long = 180_000) {
    private val started = now()
    private var activity = started
    private var ended: Long? = null
    private var continuous = false
    fun speechStarted() { continuous = true; speechActivity() }
    fun speechActivity() { activity = now(); ended = null }
    fun endOfSpeech() { continuous = false; if (ended == null) ended = now() }
    fun expired(): Boolean {
        val current = now()
        return current - started >= maximumMillis ||
            (ended?.let { current - it >= finalMillis } ?: (!continuous && current - activity >= idleMillis))
    }
}
