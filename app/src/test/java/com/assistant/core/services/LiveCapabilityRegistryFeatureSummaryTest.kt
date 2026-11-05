package com.assistant.core.services

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LiveCapabilityRegistryFeatureSummaryTest {
    private val buildNotes = listOf(
        "With a compatible Developer, Personal Jarvis can review verified APK handoff and installation requests.",
        "Activity-aware speech-capture watchdogs and manual finish/cancel controls.",
        "Weather location recovers promptly when permission is revoked.",
        "Actionable AI failures stay attributed to each attempted provider."
    )
    private fun snapshot(brief: Boolean = true) = LiveCapabilityRegistry.Snapshot(
        version = null,
        personal = true,
        accessibility = null,
        microphone = true,
        contacts = null,
        calls = null,
        notifications = null,
        geminiConfigured = false,
        gatewayConfigured = false,
        selectedModel = "none",
        provider = IntelligenceRouting.Provider.GEMINI,
        fallback = false,
        localVoice = null,
        foregroundVoice = null,
        buildNotes = buildNotes,
        briefFeatureNotes = brief
    )

    @Test fun normalFeatureQuestionGetsCliffNotesInsteadOfEveryDiagnostic() {
        assertEquals(LiveCapabilityRegistry.Topic.FEATURES,
            LiveCapabilityRegistry.topic("What new features do you have to test?"))
        val text = LiveCapabilityRegistry.render(LiveCapabilityRegistry.Topic.FEATURES, snapshot())
        assertTrue(text, text.contains("Highlights:"))
        assertTrue(text, text.contains("Developer"))
        assertTrue(text, text.contains("speech"))
        assertFalse(text, text.contains("Included build changes:"))
        assertFalse(text, text.contains("Microphone permission:"))
        assertFalse(text, text.contains("Gateway:"))
        assertTrue(text.length < 620)
    }

    @Test fun explicitFullDetailsStillReturnsVerifiableInstalledNotes() {
        for (input in listOf("full feature details", "show all new features",
            "What new features do you have in detail?", "give me the full changelog")) {
            assertEquals(input, LiveCapabilityRegistry.Topic.FEATURES_FULL, LiveCapabilityRegistry.topic(input))
        }
        val full = LiveCapabilityRegistry.render(LiveCapabilityRegistry.Topic.FEATURES_FULL, snapshot())
        assertTrue(full, full.contains(buildNotes.last()))
        assertTrue(full, full.contains("Microphone permission:"))
    }

    @Test fun savedFullPreferenceCanExpandOrdinaryFeatureQuestions() {
        val full = LiveCapabilityRegistry.render(LiveCapabilityRegistry.Topic.FEATURES, snapshot(brief = false))
        assertTrue(full.contains(buildNotes.first()))
        assertTrue(full.contains(buildNotes.last()))
    }
}
