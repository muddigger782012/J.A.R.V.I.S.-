package com.assistant.core.services

import org.junit.Assert.*
import org.junit.Test

class ConversationPolicyTest {
    @Test fun missingCommandMatchIsExplicitStateIndependentOfWording() {
        assertTrue(HybridAssistantReply("Let's work through it.", needsInterpretation = true).needsInterpretation)
        assertFalse(HybridAssistantReply("I don't understand that locally yet.").needsInterpretation)
    }

    @Test fun offlineAlternativesAreSpecificAndDoNotClaimExecutionOrLearnUnknownRequests() {
        val call = ConversationPolicy.fallback("I need to phone someone", ConversationPolicy.Obstacle.OFFLINE)
        assertTrue(call.contains("dialer")); assertTrue(call.contains("review")); assertFalse(call.contains("called"))
        val timer = ConversationPolicy.fallback("Could we do a timer?", ConversationPolicy.Obstacle.OFFLINE)
        assertTrue(timer.contains("Clock")); assertTrue(timer.contains("duration or time you want"))
        val unfamiliar = ConversationPolicy.fallback("Help me prepare for tomorrow", ConversationPolicy.Obstacle.OFFLINE)
        assertTrue(unfamiliar.contains("arithmetic")); assertTrue(unfamiliar.contains("objective"))
        assertFalse(unfamiliar.contains("must execute successfully"))
        assertFalse(unfamiliar.contains("don't understand"))
    }

    @Test fun failureAndSetupAdvicePreserveProviderChoiceAndActionApprovals() {
        val setup = ConversationPolicy.fallback("help", ConversationPolicy.Obstacle.NOT_CONFIGURED)
        assertTrue(setup.contains("Gemini")); assertTrue(setup.contains("gateway"))
        val failure = ConversationPolicy.fallback("help", ConversationPolicy.Obstacle.PROVIDER_FAILED)
        assertTrue(failure.contains("alternative provider"))
        assertTrue(ConversationPolicy.fallback("yes", ConversationPolicy.Obstacle.OFFLINE).contains("No device action is awaiting approval"))
    }

    @Test fun technicalTimeAndCalendarConversationsDoNotEnterDeviceHandlers() {
        listOf("tell me about time travel", "what is the best time to plant", "explain calendar permissions",
            "how do I schedule an appointment", "create an event called workshop", "remind me to check the calendar").forEach {
            assertFalse(it, ConversationPolicy.isTimeQuery(it)); assertFalse(it, ConversationPolicy.isCalendarQuery(it))
        }
        assertFalse(ConversationPolicy.isScreenQuery("What does read my screen mean?"))
        assertTrue(ConversationPolicy.isScreenQuery("Can you read my screen?"))
        listOf("what time is it", "what's the time?", "Jarvis, tell me the time").forEach {
            assertTrue(it, ConversationPolicy.isTimeQuery(it))
        }
        listOf("show my calendar", "what's on my calendar tomorrow", "whats on my schedule today", "next event", "what do I have tomorrow").forEach {
            assertTrue(it, ConversationPolicy.isCalendarQuery(it))
        }
    }
}
