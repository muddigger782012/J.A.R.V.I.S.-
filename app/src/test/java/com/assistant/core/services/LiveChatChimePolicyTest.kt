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

    @Test fun explicitPersistentSessionCannotSilentlyPauseAfterNormalSilence() {
        for (count in listOf(1, 3, 5, 30)) {
            assertEquals(LiveChatChimePolicy.Action.RETRY,
                LiveChatChimePolicy.afterSilence(false, count, persistentSession = true))
        }
        assertEquals(LiveChatChimePolicy.Action.PAUSE,
            LiveChatChimePolicy.afterSilence(false, 3, persistentSession = false))
        assertEquals(45_000L, LiveChatChimePolicy.silenceRetryDelayMillis(false, 30, persistentSession = true))
        assertTrue(LiveChatChimePolicy.silenceRetryDelayMillis(false, 3, persistentSession = true) > 4_000L)
        assertEquals(4_000L, LiveChatChimePolicy.silenceRetryDelayMillis(false, 3, persistentSession = false))
    }

    @Test fun quietMicrophoneKeepsOnDeviceRecognitionAvailableWhilePausingSystemProvider() {
        for (silent in 1..30) {
            assertEquals(LiveChatChimePolicy.Action.RETRY,
                LiveChatChimePolicy.afterSilence(
                    onDevice = true, consecutiveSilentTurns = silent,
                    persistentSession = true, avoidRepeatedChimes = true))
        }
        assertEquals(LiveChatChimePolicy.Action.RETRY,
            LiveChatChimePolicy.afterSilence(false, 1, persistentSession = true, avoidRepeatedChimes = true))
        for (silent in 2..30) {
            assertEquals(LiveChatChimePolicy.Action.PAUSE,
                LiveChatChimePolicy.afterSilence(false, silent, persistentSession = true, avoidRepeatedChimes = true))
        }
        // When the owner disables beep protection, system-provider retries resume.
        assertEquals(LiveChatChimePolicy.Action.RETRY,
            LiveChatChimePolicy.afterSilence(false, 5, persistentSession = true, avoidRepeatedChimes = false))
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
