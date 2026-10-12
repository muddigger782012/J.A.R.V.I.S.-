package com.assistant.core.services

import com.assistant.core.learning.ApprovedRoutineRegistry
import com.assistant.core.learning.ValidatedCommandMemory
import org.junit.Assert.*
import org.junit.Test

class IntelligenceOverviewTest {
    @Test fun localInventorySeparatesStoredCommandsPausesExamplesAndRoutines() {
        val commands = listOf(
            ValidatedCommandMemory.SavedPhrase("quiet", "volume_down", emptyMap(), 3),
            ValidatedCommandMemory.SavedPhrase("loud", "volume_up", emptyMap(), 1, failures = 2)
        )
        val examples = listOf(IntentLearningStore.SavedExample("time", "GENERAL_QUESTION", "verified_local_time", 2, 0))
        val report = IntelligenceOverview.build(commands, examples, 4, true, true, false)
        assertEquals(2, report.commandEntries)
        assertEquals(1, report.pausedEntries)
        assertEquals(1, report.intentExamples)
        assertEquals(4, report.approvedRoutines)
        assertEquals("Gemini", report.primaryAi)
        assertFalse(report.automaticLearning)
        // Counting stored rows must not rewrite, approve or erase a paused command.
        assertEquals(2, commands[1].failures)
    }

    @Test fun primaryProviderMatchesRoutingButDoesNotClaimConnectionHealth() {
        fun report(gemini: Boolean, gateway: Boolean) = IntelligenceOverview.build(emptyList(), emptyList(), 0, gemini, gateway, true)
        assertEquals("Gemini", report(true, true).primaryAi)
        assertEquals("Gateway", report(false, true).primaryAi)
        assertEquals("Not configured", report(false, false).primaryAi)
        assertTrue(report(false, false).automaticLearning)
    }

    @Test fun connectionCheckRequiresExpectedReplyAndIsNotAnActionLesson() {
        assertTrue(IntelligenceConnectionCheck.accepted(" JARVIS_CONNECTION_OK\n"))
        for (reply in listOf(null, "", "Gemini rejected the request (HTTP 500)", "I will say JARVIS_CONNECTION_OK later", "yes"))
            assertFalse(IntelligenceConnectionCheck.accepted(reply))
        val request = FallbackAiRequest.build(IntelligenceConnectionCheck.PROMPT, false, "{}", 36.0, -76.0, false)
        assertFalse(request.has("latitude"))
        assertFalse(request.has("longitude"))
        assertFalse(request.getBoolean("learn_intent"))
        assertEquals(0, request.getJSONObject("capability_context").length())
        assertEquals(1, request.getJSONArray("messages").length())
        assertEquals(IntelligenceConnectionCheck.PROMPT, request.getJSONArray("messages").getJSONObject(0).getString("content"))
        val normal = FallbackAiRequest.build("weather", false, "{}", 36.0, -76.0, true)
        assertEquals(36.0, normal.getDouble("latitude"), 0.0)
        assertEquals(-76.0, normal.getDouble("longitude"), 0.0)
    }

    @Test fun availableRoutineInventoryExcludesConflictingOrUnsupportedStoredDefinitions() {
        val saved = """[{"phrase":"quiet time","actions":["volume_down"]},{"phrase":"quiet time","actions":["volume_up"]},{"phrase":"old routine","actions":["shell"]},{"phrase":"focus time","actions":["timer:300"]}]"""
        val registry = ApprovedRoutineRegistry({ saved }, { error("inventory must not write") })
        assertEquals(listOf(ApprovedRoutineRegistry.Routine("focus time", listOf("timer:300"))), registry.snapshot())
        assertEquals(4, registry.count())
    }
}
