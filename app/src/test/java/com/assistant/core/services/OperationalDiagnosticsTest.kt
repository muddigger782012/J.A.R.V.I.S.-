package com.assistant.core.services

import org.junit.Assert.*
import org.junit.Test

class OperationalDiagnosticsTest {
    private fun snapshot() = LiveCapabilityRegistry.Snapshot(DeviceReport.App("installed-test", 519), true,
        DeviceReport.Accessibility.OFF, true, null, null, null, true, false, "gemini-owner-choice",
        IntelligenceRouting.Provider.GEMINI, true, null, null)

    @Test fun missingObservationsAreNotClaimedHealthyAndRuntimeEvidenceIsNeverSourceApproval() {
        val value = snapshot()
        assertTrue(OperationalDiagnostics.findings(value).isEmpty())
        val text = OperationalDiagnostics.render(value)
        assertTrue(text.contains("not a complete health check"))
        assertTrue(text.contains("no verified source revision"))
        assertTrue(text.contains("No write, merge, signing, publication or installation is authorized"))
    }

    @Test fun newestCaptureDelaysWakeFailureAndProviderFailureGiveSpecificEvidence() {
        fun event(stage: VoiceSessionDiagnostics.Stage, time: Long) = VoiceSessionDiagnostics.Event(stage, time, 0, null)
        val state = VoiceRuntimeObservation.Reading(VoiceAssistantService.WakeState.FAILED, VoiceInteractionState.IDLE,
            listOf(event(VoiceSessionDiagnostics.Stage.ERROR, 10), event(VoiceSessionDiagnostics.Stage.CAPTURE_REQUESTED, 0),
                event(VoiceSessionDiagnostics.Stage.CAPTURE_READY, 3000), event(VoiceSessionDiagnostics.Stage.TRANSCRIBING, 4000),
                event(VoiceSessionDiagnostics.Stage.PROCESSING, 12_000)))
        val value = snapshot().copy(foregroundVoice = state, geminiObservation = AiConnectionDiagnostics.Result(false,
            problem = AiConnectionDiagnostics.Problem.QUOTA))
        val findings = OperationalDiagnostics.findings(value)
        assertTrue(findings.any { it.title.contains("wake standby failed") })
        assertTrue(findings.any { it.evidence.contains("3000 ms") })
        assertTrue(findings.any { it.evidence.contains("8000 ms") })
        assertTrue(findings.any { it.evidence.contains("quota") })
        assertFalse(findings.any { it.title.contains("recognition failure") })
        assertEquals(1, findings.first().priority)
    }

    @Test fun replyAndSpeechStartupDelaysUseTheirOwnObservedBoundaries() {
        fun event(stage: VoiceSessionDiagnostics.Stage, time: Long) = VoiceSessionDiagnostics.Event(stage, time, 0, null)
        val value = snapshot().copy(localVoice = VoiceRuntimeObservation.Reading(
            VoiceAssistantService.WakeState.STOPPED, VoiceInteractionState.SPEAKING,
            listOf(event(VoiceSessionDiagnostics.Stage.CAPTURE_REQUESTED, 0),
                event(VoiceSessionDiagnostics.Stage.PROCESSING, 1000),
                event(VoiceSessionDiagnostics.Stage.SPEECH_REQUESTED, 7000),
                event(VoiceSessionDiagnostics.Stage.SPEAKING, 10_000))))
        val findings = OperationalDiagnostics.findings(value)
        assertTrue(findings.any { it.title.contains("reply delay") && it.evidence.contains("6000 ms") })
        assertTrue(findings.any { it.title.contains("speech startup delay") && it.evidence.contains("3000 ms") })
    }

    @Test fun aGenericSpeechOutputFailureIsNotLabeledAsAnAndroidRecognitionFailure() {
        val value = snapshot().copy(localVoice = VoiceRuntimeObservation.Reading(
            VoiceAssistantService.WakeState.STOPPED, VoiceInteractionState.IDLE,
            listOf(VoiceSessionDiagnostics.Event(VoiceSessionDiagnostics.Stage.CAPTURE_REQUESTED, 0, 0, null),
                VoiceSessionDiagnostics.Event(VoiceSessionDiagnostics.Stage.SPEECH_REQUESTED, 1000, 1000, null),
                VoiceSessionDiagnostics.Event(VoiceSessionDiagnostics.Stage.ERROR, 1100, 100, null))))
        assertFalse(OperationalDiagnostics.findings(value).any { it.title.contains("recognition failure") })
    }

    @Test fun speechFailureBeforeAnyCaptureIsReportedWithoutInventingARecognitionOrAudibilityResult() {
        val value = snapshot().copy(localVoice = VoiceRuntimeObservation.Reading(
            VoiceAssistantService.WakeState.STOPPED, VoiceInteractionState.IDLE,
            listOf(VoiceSessionDiagnostics.Event(VoiceSessionDiagnostics.Stage.SPEECH_REQUESTED, 0, 0, null),
                VoiceSessionDiagnostics.Event(VoiceSessionDiagnostics.Stage.SPEECH_FAILED, 500, 500, null),
                VoiceSessionDiagnostics.Event(VoiceSessionDiagnostics.Stage.SPEAKING, 1000, 500, null))))
        val findings = OperationalDiagnostics.findings(value)
        assertTrue(findings.any { it.title.contains("speech output failure") && it.evidence.contains("may have recovered") })
        assertFalse(findings.any { it.title.contains("recognition failure") })
    }

    @Test fun readinessWithoutARecordedCaptureRequestCannotEstablishCaptureStartupLatency() {
        val value = snapshot().copy(localVoice = VoiceRuntimeObservation.Reading(
            VoiceAssistantService.WakeState.STOPPED, VoiceInteractionState.LISTENING,
            listOf(VoiceSessionDiagnostics.Event(VoiceSessionDiagnostics.Stage.CAPTURE_READY, 3000, 0, null))))
        assertFalse(OperationalDiagnostics.findings(value).any { it.title.contains("capture startup delay") })
    }
}
