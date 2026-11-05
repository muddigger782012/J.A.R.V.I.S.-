package com.assistant.core.services

import org.junit.Assert.*
import org.junit.Test

class SpeechProgressGuardTest {
    @Test fun activeSpeechDoesNotExpireAtArbitraryTotalReplyLength() {
        var now = 100L
        val guard = SpeechProgressGuard { now }
        guard.start()
        repeat(40) {
            now += 20_000L
            guard.advance()
            assertTrue(guard.recent(90_000L))
        }
        now += 90_001L
        assertFalse(guard.recent(90_000L))
        guard.finish()
        assertFalse(guard.recent(90_000L))
    }
    @Test fun lackOfFirstAudioRemainsRecoverable() {
        var now = 1_000L
        val guard = SpeechProgressGuard { now }
        guard.start()
        now += 100_000L
        assertFalse(guard.recent(90_000L))
    }
}
