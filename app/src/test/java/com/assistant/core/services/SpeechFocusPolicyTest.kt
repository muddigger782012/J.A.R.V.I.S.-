package com.assistant.core.services

import org.junit.Assert.*
import org.junit.Test

class SpeechFocusPolicyTest {
    @Test fun transientAudioFocusLossPausesRatherThanTruncatingReply() {
        assertEquals(SpeechFocusPolicy.Action.PAUSE, SpeechFocusPolicy.action(-2))
        assertEquals(SpeechFocusPolicy.Action.RESUME, SpeechFocusPolicy.action(1))
        assertEquals(SpeechFocusPolicy.Action.STOP, SpeechFocusPolicy.action(-1))
        assertEquals(SpeechFocusPolicy.Action.CONTINUE, SpeechFocusPolicy.action(-3))
    }
}
