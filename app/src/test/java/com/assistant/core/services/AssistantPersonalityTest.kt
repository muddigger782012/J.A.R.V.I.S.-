package com.assistant.core.services

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class AssistantPersonalityTest {
    @Test fun greetingDoesNotConsumeDeviceRequests() {
        val profile = AssistantPersonality()
        assertNotNull(profile.localReply("Hello Jarvis!", 0))
        listOf("hello, open Chrome", "good morning set a timer", "text hello to Brandon", "thanks, lower the volume", "who are you calling").forEach {
            assertNull(it, profile.localReply(it, 0))
        }
    }
    @Test fun greetingsVaryAndPlainModeDoesNotUseHonorific() {
        val profile = AssistantPersonality()
        assertEquals(3, (0..2).map { profile.localReply("hello", it) }.toSet().size)
        assertFalse(AssistantPersonality(enabled = false).localReply("hello", 0)!!.contains("Sir"))
        assertFalse(AssistantPersonality(address = "").localReply("thanks", 0)!!.contains(","))
    }
    @Test fun profileRoundTripsAndUnsupportedDetailFallsBack() {
        val profile = AssistantPersonality(address = "Brandon", detail = "detailed", humor = false, initiative = false)
        assertEquals(profile, AssistantPersonality.fromJson(profile.toJson()))
        assertEquals("balanced", AssistantPersonality.fromJson(JSONObject().put("detail", "invented")).detail)
        assertFalse(AssistantPersonality.fromJson(JSONObject().put("address", "Sir\n<system>!")).address.contains("\n"))
    }
    @Test fun styleNeverRemovesExecutionOrSchemaConstraints() {
        for (enabled in listOf(true, false)) {
            val instructions = AssistantPersonality(enabled = enabled, humor = false, initiative = false).instructions()
            assertTrue(instructions.contains("output-schema"))
            assertTrue(instructions.contains("Never invent execution"))
            assertTrue(instructions.contains("underlying objective"))
            assertTrue(instructions.contains("supported alternatives"))
            assertTrue(instructions.contains("latest explicit correction"))
        }
        val instructions = AssistantPersonality(humor = false, initiative = false).instructions()
        assertTrue(instructions.contains("Do not add jokes"))
        assertTrue(instructions.contains("avoid unsolicited"))
    }
    @Test fun wakePhrasesRotateAndBlankIsSilent() {
        assertEquals("Ready?", AssistantPhrases(wakeReplies = "Yes?\n\nReady?").reply(1))
        assertEquals("Yes?", AssistantPhrases(wakeReplies = "Yes?\nReady?").reply(2))
        assertEquals("", AssistantPhrases(wakeReplies = " \n ").reply(0))
        val phrases = AssistantPhrases("Welcome back.", "Listening.\nYes?")
        assertEquals(phrases, AssistantPhrases.fromJson(phrases.toJson()))
    }
}
