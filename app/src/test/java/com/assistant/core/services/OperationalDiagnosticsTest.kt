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
}
