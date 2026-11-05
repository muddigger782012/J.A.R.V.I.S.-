package com.assistant.core.services

import org.junit.Assert.assertTrue
import org.junit.Test

class WakeAcknowledgementTest {
    @Test fun cueIsBriefAndCaptureBeginsAfterCue() {
        assertTrue(WakeAcknowledgement.TONE_MILLIS in 75L..160L)
        assertTrue(WakeAcknowledgement.CAPTURE_DELAY_MILLIS > WakeAcknowledgement.TONE_MILLIS)
        assertTrue(WakeAcknowledgement.CAPTURE_DELAY_MILLIS < 300L)
    }
}
