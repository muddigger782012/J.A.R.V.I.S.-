package com.assistant.core.services

import org.junit.Assert.*
import org.junit.Test

class VoiceSessionDiagnosticsTest {
    @Test fun frequentPartialResultsCannotEraseTheLatencyBoundariesSharedAcrossProcesses() {
        var time = 0L
        val recorder = VoiceSessionDiagnostics.Recorder { time }
        val token = recorder.begin()
        time = 3000; recorder.record(VoiceSessionDiagnostics.Stage.CAPTURE_READY, token)
        time = 4000; recorder.record(VoiceSessionDiagnostics.Stage.SPEECH_STARTED, token)
        repeat(1000) { time += 100; recorder.record(VoiceSessionDiagnostics.Stage.PARTIAL, token) }
        time += 1000; recorder.record(VoiceSessionDiagnostics.Stage.TRANSCRIBING, token)
        time += 6000; recorder.record(VoiceSessionDiagnostics.Stage.PROCESSING, token)
        val shared = recorder.runtimeSnapshot()
        assertEquals(64, recorder.snapshot().size)
        assertTrue(shared.size <= 16)
        assertEquals(VoiceSessionDiagnostics.Stage.CAPTURE_REQUESTED, shared.first().stage)
        assertEquals(3000L, shared.single { it.stage == VoiceSessionDiagnostics.Stage.CAPTURE_READY }.sinceStartMillis)
        assertEquals(1, shared.count { it.stage == VoiceSessionDiagnostics.Stage.PARTIAL })
        assertEquals(VoiceSessionDiagnostics.Stage.PROCESSING, shared.last().stage)
        assertTrue(shared.zipWithNext().all { (first, next) -> first.sinceStartMillis <= next.sinceStartMillis })
        assertTrue(shared.all { it.sincePreviousMillis in 0..it.sinceStartMillis })
    }

    @Test fun repeatedReadinessAndPlaybackCallbacksKeepFirstStartupTimes() {
        var time = 0L
        val recorder = VoiceSessionDiagnostics.Recorder { time }
        recorder.begin()
        time = 300; recorder.record(VoiceSessionDiagnostics.Stage.CAPTURE_READY)
        time = 5000; recorder.record(VoiceSessionDiagnostics.Stage.CAPTURE_READY)
        time = 6000; recorder.record(VoiceSessionDiagnostics.Stage.SPEECH_REQUESTED)
        time = 9000; recorder.record(VoiceSessionDiagnostics.Stage.SPEAKING)
        time = 12_000; recorder.record(VoiceSessionDiagnostics.Stage.SPEAKING)
        val shared = recorder.runtimeSnapshot()
        assertEquals(300L, shared.single { it.stage == VoiceSessionDiagnostics.Stage.CAPTURE_READY }.sinceStartMillis)
        assertEquals(9000L, shared.single { it.stage == VoiceSessionDiagnostics.Stage.SPEAKING }.sinceStartMillis)
    }

    @Test fun replacementStartsANewClockAndRejectsAnOldCaptureInBothViews() {
        var time = 100L
        val recorder = VoiceSessionDiagnostics.Recorder { time }
        val old = recorder.begin()
        time = 1000; recorder.record(VoiceSessionDiagnostics.Stage.ERROR, old, 5)
        time = 2000; val current = recorder.begin()
        time = 2500; recorder.record(VoiceSessionDiagnostics.Stage.CAPTURE_READY, current)
        recorder.record(VoiceSessionDiagnostics.Stage.ERROR, old, 5)
        for (view in listOf(recorder.snapshot(), recorder.runtimeSnapshot())) {
            assertEquals(2, view.size)
            assertEquals(0L, view.first().sinceStartMillis)
            assertEquals(500L, view.last().sinceStartMillis)
            assertTrue(view.none { it.stage == VoiceSessionDiagnostics.Stage.ERROR })
        }
    }

    @Test fun everyTypedStageFitsTheBoundedRuntimePayloadWithoutArbitraryErrorValues() {
        var time = 0L
        val recorder = VoiceSessionDiagnostics.Recorder { time }
        recorder.begin()
        VoiceSessionDiagnostics.Stage.values().forEach {
            time += 100; recorder.record(it, errorCode = 1000)
        }
        val shared = recorder.runtimeSnapshot()
        assertTrue(shared.size <= 16)
        assertEquals(VoiceSessionDiagnostics.Stage.values().toSet(), shared.map { it.stage }.toSet())
        assertTrue(shared.all { it.errorCode == null })
    }
}
