package com.assistant.core.services

/**
 * Android's SpeechRecognizer can play its own listening tone on every new
 * capture session. Repeatedly rearming after silence makes that tone loop.
 * A live conversation stays open, but idle/error rearming is bounded; a
 * deliberate microphone tap starts a fresh capture instead.
 */
internal object LiveChatChimePolicy {
    enum class Action { RETRY, PAUSE }
    fun afterSilence(): Action = Action.PAUSE
    fun afterProviderError(consecutiveErrors: Int): Action =
        if (consecutiveErrors <= 1) Action.RETRY else Action.PAUSE
}
