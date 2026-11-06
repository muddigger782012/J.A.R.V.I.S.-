package com.assistant.core.services

/**
 * A live conversation shouldn't go dead after one normal quiet moment.
 * Android's *system* recognizer may chime on every restart, whereas an
 * on-device engine can be rearmed quietly. Use bounded system retries to
 * avoid turning idle conversation into a notification-tone loop.
 */
internal object LiveChatChimePolicy {
    enum class Action { RETRY, PAUSE }

    fun afterSilence(onDevice: Boolean, consecutiveSilentTurns: Int): Action = when {
        onDevice -> Action.RETRY
        consecutiveSilentTurns <= 2 -> Action.RETRY
        else -> Action.PAUSE
    }

    fun silenceRetryDelayMillis(onDevice: Boolean, consecutiveSilentTurns: Int): Long =
        if (onDevice) (1_250L + (consecutiveSilentTurns - 1).coerceAtLeast(0) * 500L)
            .coerceAtMost(5_000L)
        else 4_000L

    fun afterProviderError(consecutiveErrors: Int): Action =
        if (consecutiveErrors <= 1) Action.RETRY else Action.PAUSE
}
