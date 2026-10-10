package com.assistant.core.services

/**
 * Single source of truth for J.A.R.V.I.S. voice interaction.
 *
 * Voice is transport only: every final transcript is handed to the same
 * assistant pipeline used by typed input. Wake-word, push-to-talk and Android
 * assistant invocation all drive this controller.
 */
class VoiceSessionController(
    private val onStateChanged: (VoiceInteractionState) -> Unit,
    private val onTranscript: (String) -> Unit,
    private val onError: (VoiceFailure) -> Unit
) {
    enum class Trigger { PUSH_TO_TALK, WAKE_WORD, ANDROID_ASSISTANT, BACKGROUND }
    enum class Mode { STOPPED, ARMED, CAPTURING, PROCESSING, SPEAKING }

    data class Snapshot(
        val mode: Mode = Mode.STOPPED,
        val trigger: Trigger? = null,
        val sessionId: Long = 0L
    )

    data class VoiceFailure(
        val stage: Stage,
        val message: String,
        val recoverable: Boolean = true
    ) {
        enum class Stage { MICROPHONE, RECOGNIZER, WAKE_WORD, TRANSCRIPTION, ASSISTANT, TTS }
    }

    @Volatile
    private var snapshot = Snapshot()

    fun snapshot(): Snapshot = snapshot

    @Synchronized
    fun arm() = transition(Mode.ARMED, snapshot.trigger)

    @Synchronized
    fun beginCapture(trigger: Trigger) {
        val nextId = snapshot.sessionId + 1L
        snapshot = Snapshot(Mode.CAPTURING, trigger, nextId)
        emitState()
    }

    @Synchronized
    fun transcriptReady(text: String) {
        val clean = text.trim()
        if (clean.isBlank()) {
            fail(VoiceFailure(VoiceFailure.Stage.TRANSCRIPTION, "I didn't hear anything."))
            return
        }
        snapshot = snapshot.copy(mode = Mode.PROCESSING)
        emitState()
        onTranscript(clean)
    }

    @Synchronized
    fun speaking() = transition(Mode.SPEAKING, snapshot.trigger)

    @Synchronized
    fun responseFinished(keepArmed: Boolean) {
        transition(if (keepArmed) Mode.ARMED else Mode.STOPPED, snapshot.trigger)
    }

    @Synchronized
    fun stop() = transition(Mode.STOPPED, null)

    @Synchronized
    fun fail(failure: VoiceFailure) {
        onError(failure)
        transition(if (failure.recoverable) Mode.ARMED else Mode.STOPPED, snapshot.trigger)
    }

    private fun transition(mode: Mode, trigger: Trigger?) {
        snapshot = snapshot.copy(mode = mode, trigger = trigger)
        emitState()
    }

    private fun emitState() {
        val state = when (snapshot.mode) {
            Mode.STOPPED -> VoiceInteractionState.IDLE
            Mode.ARMED -> VoiceInteractionState.WAKE_LISTENING
            Mode.CAPTURING -> VoiceInteractionState.LISTENING
            Mode.PROCESSING -> VoiceInteractionState.PROCESSING
            Mode.SPEAKING -> VoiceInteractionState.SPEAKING
        }
        onStateChanged(state)
    }
}
