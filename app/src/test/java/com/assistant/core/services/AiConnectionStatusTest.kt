package com.assistant.core.services

import com.assistant.core.learning.ValidatedCommandMemory
import org.junit.Assert.*
import org.junit.Test

class AiConnectionStatusTest {
    @Test fun screenshotQuestionsUseHostDiagnosticsWithNarrowVoiceVariants() {
        for (question in listOf("Are you connected to Gemini", "Are you connected to AI", "Are you connected to internet",
            "Hey Jarvis, are you connected to the internet?", "Jarvis please check AI connection!", "Are you online?",
            "Do you use Gemini?", "is Gemini working", "AI connection status")) {
            assertTrue(question, AiConnectionStatus.isQuestion(question))
        }
        for (question in listOf("Are you connected to Gemini and open Maps", "Explain how Gemini works", "Open Gemini",
            "Remind me to check internet connection", "What is AI", "Set a timer for 5 minutes", "yes", "it worked")) {
            assertFalse(question, AiConnectionStatus.isQuestion(question))
        }
    }

    @Test fun networkValidationNeverImpliesProviderHealthAndUnknownStaysUnknown() {
        for ((network, expected) in listOf(AiConnectionStatus.Network.NONE to "no active internet-capable network",
            AiConnectionStatus.Network.UNVALIDATED to "has not validated internet access",
            AiConnectionStatus.Network.VALIDATED to "validated internet access",
            AiConnectionStatus.Network.UNKNOWN to "couldn't read Android's current network status")) {
            val reply = AiConnectionStatus.render(AiConnectionStatus.Snapshot(network, true, false))
            assertTrue(reply.contains(expected))
            assertTrue(reply.contains("without contacting a provider"))
            assertTrue(reply.contains("not proof of a working connection"))
            assertFalse(reply.contains("operating normally"))
            assertFalse(reply.contains("independently of Gemini"))
        }
    }

    @Test fun configuredProvidersMatchExistingChatPriorityWithoutInventingGatewayModel() {
        fun render(gemini: Boolean, gateway: Boolean) = AiConnectionStatus.render(
            AiConnectionStatus.Snapshot(AiConnectionStatus.Network.VALIDATED, gemini, gateway))
        assertTrue(render(true, false).contains("Gemini is configured and is the selected provider"))
        assertTrue(render(true, true).contains("Existing chat routing uses Gemini"))
        assertTrue(render(false, true).contains("gateway is configured and is the selected provider"))
        assertTrue(render(false, true).contains("cannot identify the gateway's underlying model"))
        assertTrue(render(false, false).contains("No online AI provider is configured"))
    }

    @Test fun diagnosticQuestionsCannotBecomeLearnedActionsAndKeepPendingOutcome() {
        for (question in listOf("Are you connected to Gemini", "Are you connected to AI", "Are you connected to internet")) {
            val commands = ValidatedCommandMemory({ "[]" }, { fail("status cannot be learned"); false })
            assertTrue(LearnedCommandReviewCommands.isReserved(question))
            assertFalse(commands.recordSuccess(question, "volume_up", VerifiedVolumeLearning.capabilities))
            var raw: String? = null
            val session = DeviceLearningSession({ raw }, { raw = it; true })
            assertTrue(session.propose(DeviceLearningSession.Pending("timer", LearnableDeviceAction("timer", mapOf("seconds" to "300")), false, awaitingOutcome = true)))
            val before = raw
            assertNull(session.consume(question))
            assertEquals(before, raw)
            assertTrue(session.consume("it worked")!!.accepted)
        }
    }
}
