package com.assistant.core.services

/** Bounded, in-memory observations. Never accepts audio, transcripts, URLs or exception messages. */
object VoiceSessionDiagnostics {
    enum class Stage { CAPTURE_REQUESTED, CAPTURE_READY, SPEECH_STARTED, PARTIAL, TRANSCRIBING, PROCESSING,
        SPEECH_REQUESTED, SPEAKING, IDLE, CANCELLED, ERROR, WAKE_STARTING, WAKE_LISTENING, WAKE_STOPPED, WAKE_FAILED,
        SPEECH_FAILED }
    data class Event(val stage: Stage, val sinceStartMillis: Long, val sincePreviousMillis: Long, val errorCode: Int?)
    internal class Recorder(private val now: () -> Long = { System.nanoTime() / 1_000_000 }) {
        private var generation = 0L
        private var started: Long? = null
        private var previous = 0L
        private val events = ArrayDeque<Event>()
        // First readiness/playback callbacks define startup latency; partials cannot evict them.
        private val firstStages = setOf(Stage.CAPTURE_REQUESTED, Stage.CAPTURE_READY, Stage.SPEECH_STARTED,
            Stage.SPEECH_REQUESTED, Stage.SPEAKING)
        private val milestones = linkedMapOf<Stage, Event>()
        @Synchronized fun begin(): Long {
            generation++; started = now(); previous = started!!
            events.clear(); milestones.clear()
            record(Stage.CAPTURE_REQUESTED, generation)
            return generation
        }
        @Synchronized fun record(stage: Stage, token: Long? = null, errorCode: Int? = null) {
            if (token != null && token != generation) return
            val current = now()
            if (started == null) { started = current; previous = current }
            val event = Event(stage, (current - started!!).coerceAtLeast(0), (current - previous).coerceAtLeast(0),
                errorCode?.takeIf { it in 1..100 })
            events.addLast(event)
            previous = current
            while (events.size > 64) events.removeFirst()
            if (stage !in firstStages || stage !in milestones) {
                milestones.remove(stage)
                milestones[stage] = event
            }
        }
        @Synchronized fun snapshot(): List<Event> = events.toList()
        /** One milestone per typed stage, in callback order (at most 16, without content). */
        @Synchronized fun runtimeSnapshot(): List<Event> = milestones.values.toList()
    }
    private val recorder = Recorder()
    fun begin(): Long = recorder.begin()
    fun record(stage: Stage, token: Long? = null, errorCode: Int? = null) = recorder.record(stage, token, errorCode)
    fun snapshot(): List<Event> = recorder.snapshot()
    fun runtimeSnapshot(): List<Event> = recorder.runtimeSnapshot()
    fun render(): String = "Observed voice callback timings (milliseconds; no audio or transcripts):\n" +
        runtimeSnapshot().joinToString("\n") { "${it.stage}: ${it.sinceStartMillis} since voice trace start; ${it.sincePreviousMillis} since previous callback" +
            (it.errorCode?.let { code -> "; Android error $code" } ?: "") } +
        "\nThese timings do not establish unobserved provider or microphone internals."
}
