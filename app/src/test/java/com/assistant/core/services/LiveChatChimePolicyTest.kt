package com.assistant.core.services

import org.junit.Assert.*
import org.junit.Test

class LiveChatChimePolicyTest {
    @Test fun quietOnDeviceConversationRearmsInsteadOfDemandingMicTap() {
        for (silent in 1..12)
            assertEquals(LiveChatChimePolicy.Action.RETRY,
                LiveChatChimePolicy.afterSilence(onDevice = true, consecutiveSilentTurns = silent))
        assertTrue(LiveChatChimePolicy.silenceRetryDelayMillis(true, 12) <= 5_000L)
    }

    @Test fun systemRecognizerDoesNotLoopItsAudibleStartChimeForever() {
        assertEquals(LiveChatChimePolicy.Action.RETRY,
            LiveChatChimePolicy.afterSilence(false, 1))
        assertEquals(LiveChatChimePolicy.Action.RETRY,
            LiveChatChimePolicy.afterSilence(false, 2))
        assertEquals(LiveChatChimePolicy.Action.PAUSE,
            LiveChatChimePolicy.afterSilence(false, 3))
        assertEquals(4_000L, LiveChatChimePolicy.silenceRetryDelayMillis(false, 2))
    }

    @Test fun providerErrorsAllowOneRecoveryButNotAnUnboundedLoop() {
        assertEquals(LiveChatChimePolicy.Action.RETRY,
            LiveChatChimePolicy.afterProviderError(1))
        assertEquals(LiveChatChimePolicy.Action.PAUSE,
            LiveChatChimePolicy.afterProviderError(2))
        assertEquals(LiveChatChimePolicy.Action.PAUSE,
            LiveChatChimePolicy.afterProviderError(20))
    }
}
