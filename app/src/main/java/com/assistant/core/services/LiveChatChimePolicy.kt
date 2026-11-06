package com.assistant.core.services

/**
 * A live conversation shouldn't go dead after one normal quiet moment.
 * Android's *system* recognizer may chime on every restart, whereas an
 * on-device engine can be rearmed quietly. Use bounded system retries to
 * avoid turning idle conversation into a notification-tone loop.
 */
internal object LiveChatChimePolicy {
    enum class Action { RETRY, PAUSE }

    fun afterSilence(onDevice: Boolean, consecutiveSilentTurns: Int, persistentSession: Boolean = false,
                     avoidRepeatedChimes: Boolean = false): Action = when {
        // Android/vendor recognizer tones cannot be muted from this app.
        // Pause system-provider auto-rearming after two empty turns when
        // chime protection is on; keep Live Chat available for manual rearm.
        // Device-local capture can be quietly rearmed; never strand this
        // engine in a tap-to-resume state solely to silence system chimes.
        avoidRepeatedChimes && !onDevice && consecutiveSilentTurns >= 2 -> Action.PAUSE
        persistentSession -> Action.RETRY
        onDevice -> Action.RETRY
        consecutiveSilentTurns <= 2 -> Action.RETRY
        else -> Action.PAUSE
    }

    fun silenceRetryDelayMillis(onDevice: Boolean, consecutiveSilentTurns: Int, persistentSession: Boolean = false): Long =
        if (onDevice) (1_250L + (consecutiveSilentTurns - 1).coerceAtLeast(0) * 500L)
            .coerceAtMost(5_000L)
        else if (persistentSession) (4_000L + (consecutiveSilentTurns - 1).coerceAtLeast(0) * 8_000L)
            .coerceAtMost(45_000L)
        else 4_000L

    fun afterProviderError(consecutiveErrors: Int): Action =
        if (consecutiveErrors <= 1) Action.RETRY else Action.PAUSE
}
