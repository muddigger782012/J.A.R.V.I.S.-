package com.assistant.core.services

/** Bounded, in-memory observations. Never accepts audio, transcripts, URLs or exception messages. */
object VoiceSessionDiagnostics {
    enum class Stage { CAPTURE_REQUESTED, CAPTURE_READY, SPEECH_STARTED, PARTIAL, TRANSCRIBING, PROCESSING,
        SPEECH_REQUESTED, SPEAKING, IDLE, CANCELLED, ERROR, WAKE_STARTING, WAKE_LISTENING, WAKE_STOPPED, WAKE_FAILED }
    data class Event(val stage: Stage, val sinceStartMillis: Long, val sincePreviousMillis: Long, val errorCode: Int?)
    private var generation = 0L
    private var started = 0L
    private var previous = 0L
    private val events = ArrayDeque<Event>()
    private fun now() = System.nanoTime() / 1_000_000
    @Synchronized fun begin(): Long {
        generation++; started = now(); previous = started
        record(Stage.CAPTURE_REQUESTED, generation)
        return generation
    }
    @Synchronized fun record(stage: Stage, token: Long = generation, errorCode: Int? = null) {
        if (token != generation) return
        val current = now()
        if (started == 0L) { started = current; previous = current }
        events.addLast(Event(stage, (current - started).coerceAtLeast(0), (current - previous).coerceAtLeast(0),
            errorCode?.takeIf { it in 1..100 }))
        previous = current
        while (events.size > 64) events.removeFirst()
    }
    @Synchronized fun snapshot(): List<Event> = events.toList()
    fun render(): String = "Observed voice callback timings (milliseconds; no audio or transcripts):\n" +
        snapshot().joinToString("\n") { "${it.stage}: ${it.sinceStartMillis} since request; ${it.sincePreviousMillis} since previous event" +
            (it.errorCode?.let { code -> "; Android error $code" } ?: "") } +
        "\nThese timings do not establish unobserved provider or microphone internals."
}
