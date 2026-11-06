package com.assistant.core.services

import org.junit.Assert.*
import org.junit.Test

class LiveRecognitionRecoveryTest {
    @Test fun completePartialSpeechIsRecoveredAfterProviderFailsToFinalize() {
        assertEquals("Could you check that for me", LiveRecognitionRecovery.usablePartial(
            true, "  Could you check that for me  "))
    }

    @Test fun backgroundNoiseAndUnconfirmedSpeechAreNeverSubmittedAsCommands() {
        assertNull(LiveRecognitionRecovery.usablePartial(false, "Could you check that for me"))
        assertNull(LiveRecognitionRecovery.usablePartial(true, "uh"))
        assertNull(LiveRecognitionRecovery.usablePartial(true, "static microphone"))
        assertNull(LiveRecognitionRecovery.usablePartial(true, "    "))
    }
}
