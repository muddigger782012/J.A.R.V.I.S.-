package com.assistant.core.services

import org.junit.Assert.*
import org.junit.Test

class SpeechCaptureRoutingTest {
    @Test fun offlinePreferenceWorksWithoutAConfiguredSystemRecognizer() {
        assertEquals(SpeechCaptureRouting.Engine.ON_DEVICE, SpeechCaptureRouting.choose(true, false, true, false))
        assertEquals(SpeechCaptureRouting.Engine.ON_DEVICE, SpeechCaptureRouting.choose(true, false, true, true))
        assertEquals(SpeechCaptureRouting.Engine.SYSTEM, SpeechCaptureRouting.choose(true, false, false, true))
        assertNull(SpeechCaptureRouting.choose(true, false, false, false))
    }

    @Test fun explicitPracticeModesNeverSubstituteAnotherEngine() {
        assertNull(SpeechCaptureRouting.choose(true, true, false, true))
        assertEquals(SpeechCaptureRouting.Engine.ON_DEVICE, SpeechCaptureRouting.choose(false, true, true, true))
        assertEquals(SpeechCaptureRouting.Engine.SYSTEM, SpeechCaptureRouting.choose(false, false, true, true))
        assertNull(SpeechCaptureRouting.choose(false, false, true, false))
        assertEquals(SpeechCaptureRouting.Engine.SYSTEM, SpeechCaptureRouting.choose(true, false, true, true, SpeechCaptureRouting.Engine.SYSTEM))
        assertNull(SpeechCaptureRouting.choose(true, false, true, false, SpeechCaptureRouting.Engine.SYSTEM))
        assertNull(SpeechCaptureRouting.choose(true, true, true, true, SpeechCaptureRouting.Engine.SYSTEM))
    }

    @Test fun skippingBlankTranscriptsKeepsTheOriginalConfidenceIndex() {
        val results = SpeechRecognitionCandidates.read(listOf(" ", "  cancel that  ", "confirm screen action"), floatArrayOf(.99f, .24f, .96f))
        assertEquals("cancel that", results.first().transcript)
        assertEquals(.24f, results.first().confidence, 0f)
        assertTrue(results.first().confidenceKnown)
        assertEquals("confirm screen action", results.last().transcript)
    }

    @Test fun missingAndInvalidScoresCannotBecomeHighConfidence() {
        for (score in listOf(null, -1f, Float.NaN, Float.POSITIVE_INFINITY, Float.NEGATIVE_INFINITY, 2f)) {
            val results = SpeechRecognitionCandidates.read(listOf("call Alex"), score?.let { floatArrayOf(it) })
            assertEquals(.5f, results.single().confidence, 0f)
            assertFalse(results.single().confidenceKnown)
        }
        for (score in listOf(0f, 1f)) assertEquals(score,
            SpeechRecognitionCandidates.read(listOf("cancel"), floatArrayOf(score)).single().confidence, 0f)
    }

    @Test fun alternativesNeverPromoteACommandOrMergeDuplicateConfidence() {
        val results = SpeechRecognitionCandidates.read(listOf("no", "no", "confirm screen action"), floatArrayOf(.1f, .99f, .98f))
        assertEquals(listOf("no", "confirm screen action"), results.map { it.transcript })
        assertEquals(.1f, results.first().confidence, 0f)
        assertTrue(SpeechRecognitionCandidates.read(listOf(" ", ""), null).isEmpty())
        assertEquals(5, SpeechRecognitionCandidates.read((1..20).map { "choice $it" }, null).size)
    }
}
