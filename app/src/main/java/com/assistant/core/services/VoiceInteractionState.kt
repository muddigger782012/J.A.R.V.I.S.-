package com.assistant.core.services

/**
 * User-facing voice lifecycle. Engine-specific details stay behind this state so
 * the Assistant UI only needs to represent what J.A.R.V.I.S. is doing.
 */
enum class VoiceInteractionState {
    IDLE,
    WAKE_LISTENING,
    LISTENING,
    PROCESSING,
    SPEAKING
}

fun VoiceInteractionState.userLabel(): String = when (this) {
    VoiceInteractionState.IDLE -> "Talk to J.A.R.V.I.S."
    VoiceInteractionState.WAKE_LISTENING -> "Listening for wake phrase…"
    VoiceInteractionState.LISTENING -> "Listening…"
    VoiceInteractionState.PROCESSING -> "Thinking…"
    VoiceInteractionState.SPEAKING -> "Speaking…"
}
