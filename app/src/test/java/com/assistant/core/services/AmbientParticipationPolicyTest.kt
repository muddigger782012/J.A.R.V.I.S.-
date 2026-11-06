package com.assistant.core.services

import org.junit.Assert.assertEquals
import org.junit.Test

class AmbientParticipationPolicyTest {
    @Test fun quietObservesRoomConversationButRespondsWhenAddressed() {
        val policy = AmbientParticipationPolicy { 1_000L }
        val quiet = LiveChatCompanionSettings(style = "quiet")
        assertEquals(AmbientParticipationPolicy.Decision.OBSERVE, policy.decide("Hand me that thirteen millimeter wrench", quiet))
        assertEquals(AmbientParticipationPolicy.Decision.OBSERVE, policy.decide("I wonder what the torque spec is", quiet))
        assertEquals(AmbientParticipationPolicy.Decision.OBSERVE, policy.decide("Jarvis has been running poorly lately", quiet))
        assertEquals(AmbientParticipationPolicy.Decision.OBSERVE, policy.decide("I mentioned Jarvis to the mechanic", quiet))
        assertEquals(AmbientParticipationPolicy.Decision.RESPOND, policy.decide("Jarvis, can you look that up?", quiet))
        assertEquals(AmbientParticipationPolicy.Decision.RESPOND, policy.decide("What is the torque, Jarvis?", quiet))
        assertEquals(AmbientParticipationPolicy.Decision.OBSERVE, policy.decide("What about the next bolt?", quiet))
    }

    @Test fun companionAllowsBoundedFollowUpsOnlyWhenContextIsEnabled() {
        var millis = 1_000L
        val policy = AmbientParticipationPolicy { millis }
        val settings = LiveChatCompanionSettings()
        assertEquals(AmbientParticipationPolicy.Decision.OBSERVE, policy.decide("What about the next one?", settings))
        assertEquals(AmbientParticipationPolicy.Decision.RESPOND, policy.decide("Hey Jarvis, can you check that?", settings))
        millis += 10_000
        assertEquals(AmbientParticipationPolicy.Decision.RESPOND, policy.decide("And what about the other side?", settings))
        assertEquals(AmbientParticipationPolicy.Decision.OBSERVE,
            policy.decide("Hand him the wrench", settings))
        assertEquals(AmbientParticipationPolicy.Decision.OBSERVE,
            policy.decide("Can you check the timing?", settings.copy(contextEnabled = false)))
        millis += 46_000
        assertEquals(AmbientParticipationPolicy.Decision.OBSERVE,
            policy.decide("Can you check the timing?", settings))
    }

    @Test fun activeMaxInitiativeAnswersUnaddressedQuestionsWithoutOwnerIdentity() {
        var time = 1_000L
        val policy = AmbientParticipationPolicy { time }
        val active = LiveChatCompanionSettings(style = "active", initiative = 100)
        assertEquals(AmbientParticipationPolicy.Decision.OFFER_HELP,
            policy.decide("I can't figure out why the pressure drops", active))
        time += 16_000L
        assertEquals(AmbientParticipationPolicy.Decision.OFFER_HELP,
            policy.decide("What should we try next", active))
        assertEquals(AmbientParticipationPolicy.Decision.OBSERVE,
            policy.decide("Can you find the problem", active)) // Cooldown prevents constant interruptions.
        time += 16_000L
        assertEquals(AmbientParticipationPolicy.Decision.OBSERVE,
            policy.decide("Hand me that thirteen millimeter wrench", active))
        assertEquals(AmbientParticipationPolicy.Decision.OBSERVE,
            policy.decide("Open my messages", active)) // No unaddressed device actions.
        assertEquals(AmbientParticipationPolicy.Decision.OBSERVE,
            policy.decide("I wonder what makes it surge", active, AmbientParticipationPolicy.Speaker.OTHER))
        assertEquals(AmbientParticipationPolicy.Decision.OFFER_HELP,
            policy.decide("I wonder what makes it surge", active.copy(replyToOtherSpeakers = true),
                AmbientParticipationPolicy.Speaker.OTHER))
    }

    @Test fun highCompanionInitiativeEnablesConversationButModerateAndQuietRemainSilent() {
        val policy = AmbientParticipationPolicy { 1_000L }
        assertEquals(AmbientParticipationPolicy.Decision.OBSERVE,
            policy.decide("Why does the engine surge", LiveChatCompanionSettings(style = "companion", initiative = 55)))
        assertEquals(AmbientParticipationPolicy.Decision.OFFER_HELP,
            policy.decide("Why does the engine surge", LiveChatCompanionSettings(style = "companion", initiative = 100)))
        policy.reset()
        assertEquals(AmbientParticipationPolicy.Decision.OBSERVE,
            policy.decide("Why does the engine surge", LiveChatCompanionSettings(style = "quiet", initiative = 100)))
        assertEquals(AmbientParticipationPolicy.Decision.RESPOND,
            policy.decide("Jarvis, why does it surge", LiveChatCompanionSettings(style = "quiet")))
    }

    @Test fun preferencesNormalizeUnknownValuesAndPreserveApprovedDefaults() {
        val defaults = LiveChatCompanionSettings.fromJson(org.json.JSONObject())
        assertEquals("companion", defaults.style)
        assertEquals(55, defaults.initiative)
        assertEquals(true, defaults.interruptionsEnabled)
        assertEquals(true, defaults.contextEnabled)
        assertEquals(false, defaults.replyToOtherSpeakers)
        assertEquals(true, defaults.keepListeningUntilStopped)
        assertEquals(false, LiveChatCompanionSettings.fromJson(
            LiveChatCompanionSettings(keepListeningUntilStopped = false).toJson()).keepListeningUntilStopped)
        val invalid = LiveChatCompanionSettings(style = "other", initiative = -20).normalized()
        assertEquals("companion", invalid.style)
        assertEquals(0, invalid.initiative)
    }

    @Test fun resetPreventsPreviousLiveSessionFromAnsweringFollowUps() {
        val policy = AmbientParticipationPolicy { 1000L }
        val settings = LiveChatCompanionSettings()
        policy.decide("Jarvis, start", settings)
        policy.reset()
        assertEquals(AmbientParticipationPolicy.Decision.OBSERVE,
            policy.decide("Can you look that up?", settings))
    }
}
