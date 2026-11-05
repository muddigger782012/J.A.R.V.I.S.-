package com.assistant.core.services

import org.junit.Assert.*
import org.junit.Test

class SpeechPlaybackStallPolicyTest {
    @Test fun continuingLongReadAloudNeverTripsAbsoluteTimer() {
        val start = 1000L
        assertFalse(SpeechPlaybackStallPolicy.stalled(start + 300_000L, start + 299_000L))
        assertFalse(SpeechPlaybackStallPolicy.stalled(start + 600_000L, start + 580_000L))
    }

    @Test fun stalledOutputEventuallyReleasesLiveChat() {
        assertFalse(SpeechPlaybackStallPolicy.stalled(100_000L, 1_000L))
        assertTrue(SpeechPlaybackStallPolicy.stalled(151_000L, 1_000L))
        assertFalse(SpeechPlaybackStallPolicy.stalled(100_000L, 0L))
    }
}
