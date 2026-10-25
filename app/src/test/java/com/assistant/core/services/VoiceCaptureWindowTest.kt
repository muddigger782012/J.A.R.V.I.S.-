package com.assistant.core.services

import org.junit.Assert.*
import org.junit.Test

class VoiceCaptureWindowTest {
    @Test fun continuousSpeechAndNaturalPausesDoNotHitTheOldAbsoluteCaptureDeadlines() {
        var time = 0L
        val window = VoiceCaptureWindow({ time })
        repeat(30) {
            time += 2500; window.speechActivity()
            time += 2500; assertFalse(window.expired())
        }
    }
    @Test fun providerEndOfSpeechWaitsForFinalResultsAndMoreSpeechReopensTheCaptureWindow() {
        var time = 0L
        val window = VoiceCaptureWindow({ time })
        time = 19_000; window.endOfSpeech()
        time = 26_000; assertFalse(window.expired())
        window.speechActivity()
        time = 50_000; assertFalse(window.expired())
        time = 56_000; assertTrue(window.expired())
    }
    @Test fun providerReportedContinuousSpeechCanExceedIdleLimitWithoutPartialTranscripts() {
        var time = 0L
        val window = VoiceCaptureWindow({ time })
        window.speechStarted()
        time = 120_000; assertFalse(window.expired())
        window.endOfSpeech()
        time = 139_999; assertFalse(window.expired())
        time = 140_000; assertTrue(window.expired())
        time = 180_000; window.speechStarted(); assertTrue(window.expired())
    }
    @Test fun noSpeechAndUnresponsiveProvidersRemainBoundedWithoutInventingATranscript() {
        var time = 0L
        val silent = VoiceCaptureWindow({ time })
        time = 30_000; assertTrue(silent.expired())
        val maximum = VoiceCaptureWindow({ time })
        time += 180_000; maximum.speechActivity(); assertTrue(maximum.expired())
    }
    @Test fun diagnosticsRetainOnlyBoundedTimingEventsAndRejectAnOldCaptureToken() {
        val old = VoiceSessionDiagnostics.begin()
        val current = VoiceSessionDiagnostics.begin()
        repeat(100) { VoiceSessionDiagnostics.record(VoiceSessionDiagnostics.Stage.PARTIAL, current) }
        VoiceSessionDiagnostics.record(VoiceSessionDiagnostics.Stage.ERROR, old, 5)
        val events = VoiceSessionDiagnostics.snapshot()
        assertEquals(64, events.size)
        assertTrue(events.all { it.stage == VoiceSessionDiagnostics.Stage.PARTIAL && it.errorCode == null })
        assertTrue(events.all { it.sinceStartMillis >= 0 && it.sincePreviousMillis >= 0 })
    }
    @Test fun controllerRejectsTranscriptsAfterCancellationAndDoesNotInventWakeReadiness() {
        val heard = mutableListOf<String>()
        val states = mutableListOf<VoiceInteractionState>()
        val controller = VoiceSessionController(states::add, heard::add, {})
        controller.beginCapture(VoiceSessionController.Trigger.PUSH_TO_TALK)
        controller.transcribing(); assertEquals(VoiceInteractionState.TRANSCRIBING, states.last())
        controller.stop(); controller.transcriptReady("stale speech")
        assertTrue(heard.isEmpty())
        controller.responseFinished(true)
        assertEquals(VoiceInteractionState.IDLE, states.last())
        controller.wakeState(true)
        assertEquals(VoiceInteractionState.WAKE_LISTENING, states.last())
        controller.wakeState(false)
        assertEquals(VoiceInteractionState.IDLE, states.last())
    }
    @Test fun lateSpeechCompletionCannotEndANewerCaptureButCaptureFailureCanEndItsOwnTurn() {
        val states = mutableListOf<VoiceInteractionState>()
        val controller = VoiceSessionController(states::add, {}, {})
        controller.beginCapture(VoiceSessionController.Trigger.PUSH_TO_TALK)
        controller.responseFinished(true)
        assertEquals(VoiceSessionController.Mode.CAPTURING, controller.snapshot().mode)
        controller.transcribing(); controller.responseFinished(false)
        assertEquals(VoiceSessionController.Mode.TRANSCRIBING, controller.snapshot().mode)
        controller.captureFinished(true)
        assertEquals(VoiceInteractionState.IDLE, states.last())
        controller.wakeState(true)
        controller.beginCapture(VoiceSessionController.Trigger.PUSH_TO_TALK)
        controller.captureFinished(true)
        assertEquals(VoiceInteractionState.WAKE_LISTENING, states.last())
    }
}
