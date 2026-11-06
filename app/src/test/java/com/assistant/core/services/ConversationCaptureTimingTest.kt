package com.assistant.core.services

import org.junit.Assert.*
import org.junit.Test

class ConversationCaptureTimingTest {
    @Test fun originalOneShotCommandEndpointIsUnchanged() {
        assertEquals(ConversationCaptureTiming.SilenceHints(1700L, 1700L),
            ConversationCaptureTiming.silenceHints(1700L, 0L))
    }

    @Test fun liveConversationSurvivesThinkingPausesWithoutPrematureCompleteHint() {
        assertEquals(ConversationCaptureTiming.SilenceHints(3600L, 4350L),
            ConversationCaptureTiming.silenceHints(1700L, ConversationCaptureTiming.LIVE_MINIMUM_PAUSE_MILLIS))
        assertEquals(ConversationCaptureTiming.SilenceHints(10000L, 10000L),
            ConversationCaptureTiming.silenceHints(10000L, ConversationCaptureTiming.LIVE_MINIMUM_PAUSE_MILLIS))
    }

    @Test fun liveLongDictationDoesNotInheritThreeMinuteCommandCutoff() {
        assertEquals(600000L, ConversationCaptureTiming.captureMaximumMillis(180000L,
            ConversationCaptureTiming.LIVE_MINIMUM_MAX_CAPTURE_MILLIS))
        assertEquals(180000L, ConversationCaptureTiming.captureMaximumMillis(180000L, 0L))
    }
}
