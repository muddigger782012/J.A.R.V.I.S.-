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

    @Test fun unknownSpeakerNeverTriggersUnsolicitedHelpWithoutOptIn() {
        var time = 1_000L
        val policy = AmbientParticipationPolicy { time }
        val active = LiveChatCompanionSettings(style = "active", initiative = 100)
        assertEquals(AmbientParticipationPolicy.Decision.OBSERVE,
            policy.decide("I can't figure out why the pressure drops", active))
        assertEquals(AmbientParticipationPolicy.Decision.OFFER_HELP,
            policy.decide("I can't figure out why the pressure drops", active, AmbientParticipationPolicy.Speaker.OWNER))
        assertEquals(AmbientParticipationPolicy.Decision.OBSERVE,
            policy.decide("Jarvis, help", active, AmbientParticipationPolicy.Speaker.OTHER))
        time += 90_000L
        assertEquals(AmbientParticipationPolicy.Decision.OFFER_HELP,
            policy.decide("I wonder what makes it surge", active.copy(replyToOtherSpeakers = true),
                AmbientParticipationPolicy.Speaker.OTHER))
    }

    @Test fun preferencesNormalizeUnknownValuesAndPreserveApprovedDefaults() {
        val defaults = LiveChatCompanionSettings.fromJson(org.json.JSONObject())
        assertEquals("companion", defaults.style)
        assertEquals(55, defaults.initiative)
        assertEquals(true, defaults.interruptionsEnabled)
        assertEquals(true, defaults.contextEnabled)
        assertEquals(false, defaults.replyToOtherSpeakers)
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
