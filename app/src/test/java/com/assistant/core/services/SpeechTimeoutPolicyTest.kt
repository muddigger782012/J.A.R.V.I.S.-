package com.assistant.core.services

import org.junit.Assert.assertEquals
import org.junit.Test

class SpeechTimeoutPolicyTest {
    @Test fun shortResponsesCannotBeCutOffAtFortySeconds() {
        assertEquals(120_000L, SpeechTimeoutPolicy.maximumReplyMillis(150))
    }

    @Test fun longerOfflineSpeechHasTimeToFinish() {
        assertEquals(260_000L, SpeechTimeoutPolicy.maximumReplyMillis(1000))
        assertEquals(900_000L, SpeechTimeoutPolicy.maximumReplyMillis(20000))
    }
}
