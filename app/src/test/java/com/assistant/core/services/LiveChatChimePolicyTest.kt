package com.assistant.core.services

import org.junit.Assert.*
import org.junit.Test

class LiveChatChimePolicyTest {
    @Test fun silenceLeavesConversationOpenInsteadOfStartingNewMicSession() {
        assertEquals(LiveChatChimePolicy.Action.PAUSE, LiveChatChimePolicy.afterSilence())
    }
    @Test fun providerErrorsAllowOneRecoveryButNoRepeatingChimes() {
        assertEquals(LiveChatChimePolicy.Action.RETRY, LiveChatChimePolicy.afterProviderError(1))
        assertEquals(LiveChatChimePolicy.Action.PAUSE, LiveChatChimePolicy.afterProviderError(2))
        assertEquals(LiveChatChimePolicy.Action.PAUSE, LiveChatChimePolicy.afterProviderError(20))
    }
}
