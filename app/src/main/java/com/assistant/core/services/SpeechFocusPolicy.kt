package com.assistant.core.services

/** Android audio focus: transient interruptions must pause, not discard a reply. */
internal object SpeechFocusPolicy {
    enum class Action { CONTINUE, PAUSE, RESUME, STOP }
    fun action(change: Int) = when (change) {
        1 -> Action.RESUME       // AUDIOFOCUS_GAIN
        -1 -> Action.STOP       // AUDIOFOCUS_LOSS
        -2 -> Action.PAUSE      // AUDIOFOCUS_LOSS_TRANSIENT
        -3 -> Action.CONTINUE   // AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK
        else -> Action.CONTINUE
    }
}
