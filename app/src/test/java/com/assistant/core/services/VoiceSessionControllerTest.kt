package com.assistant.core.services

import org.junit.Assert.assertEquals
import org.junit.Test

class VoiceSessionControllerTest {
    @Test fun lateTranscriptAfterStopIsIgnored() {
        val transcripts = mutableListOf<String>()
        val controller = VoiceSessionController({}, { transcripts.add(it) }, {})
        controller.beginCapture(VoiceSessionController.Trigger.PUSH_TO_TALK)
        controller.stop()
        controller.transcriptReady("late callback")
        assertEquals(emptyList<String>(), transcripts)
        assertEquals(VoiceSessionController.Mode.STOPPED, controller.snapshot().mode)
    }

    @Test fun duplicateFinalTranscriptIsIgnored() {
        val transcripts = mutableListOf<String>()
        val controller = VoiceSessionController({}, { transcripts.add(it) }, {})
        controller.beginCapture(VoiceSessionController.Trigger.WAKE_WORD)
        controller.transcriptReady("first result")
        controller.transcriptReady("duplicate result")
        assertEquals(listOf("first result"), transcripts)
        assertEquals(VoiceSessionController.Mode.PROCESSING, controller.snapshot().mode)
    }

    @Test fun nextCaptureAcceptsNewTranscript() {
        val transcripts = mutableListOf<String>()
        val controller = VoiceSessionController({}, { transcripts.add(it) }, {})
        controller.beginCapture(VoiceSessionController.Trigger.PUSH_TO_TALK)
        controller.transcriptReady("first")
        controller.beginCapture(VoiceSessionController.Trigger.PUSH_TO_TALK)
        controller.transcriptReady("second")
        assertEquals(listOf("first", "second"), transcripts)
    }
}
