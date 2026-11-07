package com.assistant.core.services

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LiveChatManualVoiceTurnTest {
    private val observed = AmbientParticipationPolicy.Decision.OBSERVE
    private val help = AmbientParticipationPolicy.Decision.OFFER_HELP
    private val respond = AmbientParticipationPolicy.Decision.RESPOND

    @Test fun automaticAmbientSpeechStillNeedsTheParticipationGate() {
        val manual = LiveChatManualVoiceTurn()
        manual.started(10L)
        assertEquals(observed, manual.decide(10L, observed))
        assertEquals(observed, manual.decide(11L, observed))
    }

    @Test fun tappingMicToStartSpeechOverridesOnlyNextRecognizedTurn() {
        val manual = LiveChatManualVoiceTurn()
        manual.requestNext()
        manual.started(10L)
        assertEquals(help, manual.decide(10L, observed))
        assertEquals(observed, manual.decide(10L, observed))
        manual.started(11L)
        assertEquals(observed, manual.decide(11L, observed))
    }

    @Test fun finishingActiveSpeechPreservesCurrentTurnRatherThanNext() {
        val manual = LiveChatManualVoiceTurn()
        manual.started(100L)
        manual.requestCurrent(100L)
        assertEquals(help, manual.decide(100L, observed))
        manual.started(101L)
        assertEquals(observed, manual.decide(101L, observed))
    }

    @Test fun directAddressKeepsHostActionApprovalPolicy() {
        val manual = LiveChatManualVoiceTurn()
        manual.requestNext()
        manual.started(20L)
        assertEquals(respond, manual.decide(20L, respond))
        assertEquals(observed, manual.decide(20L, observed))
    }

    @Test fun interruptedAndOldCallbacksCannotStealNewMicRequest() {
        val manual = LiveChatManualVoiceTurn()
        manual.requestNext()
        manual.started(50L)
        manual.cancelled(50L)
        manual.requestNext()
        manual.started(60L)
        assertEquals(observed, manual.decide(50L, observed))
        assertEquals(help, manual.decide(60L, observed))
    }

    @Test fun abandonedTapNeverCarriesIntoNextLiveSession() {
        val manual = LiveChatManualVoiceTurn()
        manual.requestNext()
        manual.reset()
        manual.started(8L)
        assertEquals(observed, manual.decide(8L, observed))
    }

    @Test fun explicitMicCannotTurnUnaddressedDeviceCommandIntoPrivilegedAction() {
        val policy = AmbientParticipationPolicy { 500L }
        val quiet = LiveChatCompanionSettings(style = "quiet")
        val manual = LiveChatManualVoiceTurn()
        manual.requestNext()
        manual.started(3L)
        val command = policy.decide("Open my messages", quiet)
        assertEquals(observed, command)
        // It may produce conversational text, but OFFER_HELP never invokes
        // the host's privileged command router in VoiceForegroundService.
        assertEquals(help, manual.decide(3L, command))
        assertEquals(observed, manual.decide(3L, command))
    }
}
