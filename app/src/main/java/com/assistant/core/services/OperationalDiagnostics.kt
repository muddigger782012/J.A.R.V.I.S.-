package com.assistant.core.services

/** Runtime observations stay distinct from imported source findings and exact-diff approvals. */
object OperationalDiagnostics {
    data class Finding(val priority: Int, val title: String, val evidence: String, val next: String)
    fun findings(value: LiveCapabilityRegistry.Snapshot): List<Finding> = buildList {
        if (!value.microphone) add(Finding(1, "Microphone access unavailable", "Android reports microphone permission is absent.",
            "Review microphone permission before investigating recognition quality. No permission is changed here."))
        for ((owner, state) in listOf("Local" to value.localVoice, "Foreground" to value.foregroundVoice)) {
            if (state == null) continue
            if (state.wake == VoiceAssistantService.WakeState.FAILED) add(Finding(1, "$owner wake standby failed",
                "The fresh service observation reports FAILED, rather than a requested enable flag.",
                "Inspect initialization and microphone ownership on the affected phone; explicitly retry wake standby."))
            val events = state.timings
            val start = events.indexOfLast { it.stage == VoiceSessionDiagnostics.Stage.CAPTURE_REQUESTED }
            if (start < 0) continue
            val capture = events.drop(start)
            capture.firstOrNull { it.stage == VoiceSessionDiagnostics.Stage.CAPTURE_READY && it.sinceStartMillis >= 2500 }?.let {
                add(Finding(2, "$owner capture startup delay", "The provider readiness callback arrived ${it.sinceStartMillis} ms after capture was requested.",
                    "Compare the selected speech provider and on-device model. This callback measurement does not isolate microphone hardware or provider internals."))
            }
            val ended = capture.lastOrNull { it.stage == VoiceSessionDiagnostics.Stage.TRANSCRIBING }
            val result = capture.lastOrNull { it.stage == VoiceSessionDiagnostics.Stage.PROCESSING }
            if (ended != null && result != null && result.sinceStartMillis - ended.sinceStartMillis >= 5000)
                add(Finding(2, "$owner final transcription delay", "Final-result processing followed the end-of-speech callback by ${result.sinceStartMillis - ended.sinceStartMillis} ms.",
                    "Compare provider behavior with natural pauses and long sentences on the physical phone. Partial results are not final transcripts."))
            capture.lastOrNull { it.stage == VoiceSessionDiagnostics.Stage.ERROR }?.let {
                add(Finding(2, "$owner recognition failure observed", "The retained capture callback reported Android error ${it.errorCode ?: "unavailable"}.",
                    "Retry capture and review provider and permission status. A retained failure does not prove the current attempt failed."))
            }
        }
        for ((provider, observation) in listOf("Gemini" to value.geminiObservation, "Gateway" to value.gatewayObservation)) {
            if (observation?.success == false) add(Finding(2, "$provider request failure observed",
                AiConnectionDiagnostics.describe(observation.problem), "Run the independent connection test for the current settings. Local commands remain available."))
        }
        if (value.version == null) add(Finding(2, "Installed-version evidence unavailable", "Package metadata could not be read.",
            "Inspect package metadata access before reporting an installed release version."))
        if (value.personal && value.accessibility == DeviceReport.Accessibility.CONFIGURED) add(Finding(2, "Screen bridge not connected",
            "Accessibility is configured, but a live bridge connection was not verified.", "Review the Accessibility service status. No protected screen is inspected or restriction bypassed."))
    }.sortedBy { it.priority }
    fun render(value: LiveCapabilityRegistry.Snapshot): String = buildString {
        appendLine("Read-only runtime review. No network tests, source edits or device actions were performed.")
        val findings = findings(value)
        if (findings.isEmpty()) appendLine("No issue was identified by these bounded observations. This is not a complete health check or physical-device acceptance.")
        findings.forEach { appendLine("P${it.priority}: ${it.title}\nEvidence: ${it.evidence}\nNext: ${it.next}\n") }
        appendLine("Missing or expired voice/provider observations remain unverified. Timing data excludes audio and transcripts.")
        append("This runtime review has no verified source revision or source-line evidence. Developer must inspect authorized source before preparing any repair proposal. No write, merge, signing, publication or installation is authorized.")
    }
}
