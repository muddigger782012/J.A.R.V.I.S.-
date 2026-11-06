package com.assistant.core.services

/**
 * Recognition providers may ignore silence extras. When respected, Live Chat
 * must allow thinking pauses without slowing short push-to-talk commands.
 */
internal object ConversationCaptureTiming {
    const val LIVE_MINIMUM_PAUSE_MILLIS = 3_600L
    const val LIVE_MINIMUM_MAX_CAPTURE_MILLIS = 600_000L

    data class SilenceHints(val possiblyCompleteMillis: Long, val completeMillis: Long)

    fun silenceHints(configuredPauseMillis: Long, minimumPauseMillis: Long): SilenceHints {
        val possible = configuredPauseMillis.coerceAtLeast(minimumPauseMillis).coerceIn(1_000L, 10_000L)
        // Preserve the established one-shot endpoint; only Live Chat receives
        // a longer complete-silence window than its possibly-complete window.
        val complete = if (minimumPauseMillis > 0L) (possible + 750L).coerceAtMost(10_000L) else possible
        return SilenceHints(possible, complete)
    }

    fun captureMaximumMillis(configuredMaximumMillis: Long, minimumMaximumMillis: Long): Long =
        configuredMaximumMillis.coerceAtLeast(minimumMaximumMillis).coerceIn(60_000L, 600_000L)
}
