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
        assertEquals(AmbientParticipationPolicy.Decision.OFFER_HELP, policy.decide("And what about the other side?", settings))
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
        assertEquals(AmbientParticipationPolicy.Decision.OFFER_HELP,
            policy.decide("I can't figure out why the pressure drops", active))
        assertEquals(AmbientParticipationPolicy.Decision.OBSERVE,
            policy.decide("I can't figure out why the pressure drops", active, AmbientParticipationPolicy.Speaker.OWNER)) // Duplicate provider turn must not trigger a second response.
        assertEquals(AmbientParticipationPolicy.Decision.OBSERVE,
            policy.decide("Jarvis, help", active, AmbientParticipationPolicy.Speaker.OTHER))
        time += 90_000L
        assertEquals(AmbientParticipationPolicy.Decision.OFFER_HELP,
            policy.decide("I wonder what makes it surge", active.copy(replyToOtherSpeakers = true),
                AmbientParticipationPolicy.Speaker.OTHER))
    }

    @Test fun activeHighInitiativeRespondsToUsefulUnaddressedTalkButNotEverySentence() {
        var clock = 1_000L
        val policy = AmbientParticipationPolicy { clock }
        val active = LiveChatCompanionSettings(style = "active", initiative = 100)
        assertEquals(AmbientParticipationPolicy.Decision.OFFER_HELP,
            policy.decide("This truck is surging at idle again", active))
        assertEquals(AmbientParticipationPolicy.Decision.OBSERVE,
            policy.decide("Pass me the thirteen millimeter wrench", active))
        assertEquals(AmbientParticipationPolicy.Decision.OBSERVE,
            policy.decide("I wonder why it keeps stalling", active))
        clock += 45_000L
        assertEquals(AmbientParticipationPolicy.Decision.OFFER_HELP,
            policy.decide("I wonder why it keeps stalling", active))
        assertEquals(AmbientParticipationPolicy.Decision.OBSERVE,
            policy.decide("I wonder why it keeps stalling", active, AmbientParticipationPolicy.Speaker.OTHER))
    }

    @Test fun companionInitiativeActuallyAffectsUnaddressedParticipation() {
        val prompt = "What should we do about the fuel pressure?"
        assertEquals(AmbientParticipationPolicy.Decision.OFFER_HELP,
            AmbientParticipationPolicy { 5L }.decide(prompt, LiveChatCompanionSettings(style = "companion", initiative = 55)))
        assertEquals(AmbientParticipationPolicy.Decision.OFFER_HELP,
            AmbientParticipationPolicy { 5L }.decide(prompt, LiveChatCompanionSettings(style = "companion", initiative = 100)))
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
            policy.decide("Can you find the problem", active)) // Same-moment follow-up is duplicate-suppressed.
        assertEquals(AmbientParticipationPolicy.Decision.OBSERVE,
            policy.decide("I wonder what makes it surge", active)) // Separate spontaneous offer on cooldown.
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

    @Test fun rapidAmbientTurnsAreDebouncedButRealFollowUpsRemainAvailable() {
        var clock = 1_000L
        val policy = AmbientParticipationPolicy { clock }
        val active = LiveChatCompanionSettings(style = "active", initiative = 100)
        assertEquals(AmbientParticipationPolicy.Decision.OFFER_HELP,
            policy.decide("The microphone keeps cutting out", active))
        clock += 200L
        assertEquals(AmbientParticipationPolicy.Decision.OBSERVE,
            policy.decide("What about noise suppression?", active))
        clock += 800L
        assertEquals(AmbientParticipationPolicy.Decision.OFFER_HELP,
            policy.decide("What about noise suppression?", active))
        policy.reset()
        assertEquals(AmbientParticipationPolicy.Decision.OBSERVE,
            policy.decide("What about noise suppression?", active.copy(initiative = 0)))
    }

    @Test fun companionAtApproved55AnswersClearQuestionsAndAdvisoryRequests() {
        val policy = AmbientParticipationPolicy { 1_000L }
        assertEquals(AmbientParticipationPolicy.Decision.OFFER_HELP,
            policy.decide("Why does the engine surge", LiveChatCompanionSettings(style = "companion", initiative = 55)))
        policy.reset()
        assertEquals(AmbientParticipationPolicy.Decision.OBSERVE,
            policy.decide("What about that other one", LiveChatCompanionSettings(style = "companion", initiative = 55)))
        assertEquals(AmbientParticipationPolicy.Decision.OFFER_HELP,
            policy.decide("I wonder why it is surging", LiveChatCompanionSettings(style = "companion", initiative = 55)))
        policy.reset()
        assertEquals(AmbientParticipationPolicy.Decision.OFFER_HELP,
            policy.decide("Why does the engine surge", LiveChatCompanionSettings(style = "companion", initiative = 100)))
        policy.reset()
        assertEquals(AmbientParticipationPolicy.Decision.OBSERVE,
            policy.decide("Why does the engine surge", LiveChatCompanionSettings(style = "quiet", initiative = 100)))
        assertEquals(AmbientParticipationPolicy.Decision.RESPOND,
            policy.decide("Jarvis, why does it surge", LiveChatCompanionSettings(style = "quiet")))
    }

    @Test fun activeMaximumCanCommentOnProblemStatementsWithoutWakeWord() {
        val policy = AmbientParticipationPolicy { 100L }
        val settings = LiveChatCompanionSettings(style = "active", initiative = 100)
        assertEquals(AmbientParticipationPolicy.Decision.OFFER_HELP,
            policy.decide("The microphone keeps cutting out", settings))
        policy.reset()
        assertEquals(AmbientParticipationPolicy.Decision.OBSERVE,
            policy.decide("The microphone keeps cutting out", settings.copy(initiative = 70)))
        assertEquals(AmbientParticipationPolicy.Decision.OBSERVE,
            policy.decide("Hand me the wrench", settings))
    }

    @Test fun naturalFollowUpsContinueAsTextOnlyWithNoReawakening() {
        var millis = 1_000L
        val policy = AmbientParticipationPolicy { millis }
        val settings = LiveChatCompanionSettings(style = "companion", initiative = 55)
        assertEquals(AmbientParticipationPolicy.Decision.RESPOND,
            policy.decide("Jarvis, help me troubleshoot the microphone", settings))
        millis += 1_000
        assertEquals(AmbientParticipationPolicy.Decision.OFFER_HELP,
            policy.decide("I tried that and it still cuts out", settings))
        millis += 1_000
        assertEquals(AmbientParticipationPolicy.Decision.OFFER_HELP,
            policy.decide("That's exactly what I meant", settings))
        millis += 1_000
        assertEquals(AmbientParticipationPolicy.Decision.OFFER_HELP,
            policy.decide("Go on", settings))

        // An unaddressed conversational reply never gets action authority.
        assertEquals(AmbientParticipationPolicy.Decision.OBSERVE,
            policy.decide("Can you please delete my messages", settings))
        assertEquals(AmbientParticipationPolicy.Decision.OBSERVE,
            policy.decide("Please open settings", settings))
        assertEquals(AmbientParticipationPolicy.Decision.OBSERVE,
            policy.decide("Will you send a text", settings))
        assertEquals(AmbientParticipationPolicy.Decision.OBSERVE,
            policy.decide("Hand me the wrench", settings))

        millis += 45_001
        assertEquals(AmbientParticipationPolicy.Decision.OBSERVE,
            policy.decide("That still didn't work", settings))
    }

    @Test fun contextualReplyNeedsContextAndDoesNotOverrideQuietMode() {
        val policy = AmbientParticipationPolicy { 1_000L }
        val default = LiveChatCompanionSettings()
        policy.decide("Jarvis, explain the problem", default)
        assertEquals(AmbientParticipationPolicy.Decision.OBSERVE,
            policy.decide("Exactly", default.copy(contextEnabled = false)))
        assertEquals(AmbientParticipationPolicy.Decision.OBSERVE,
            policy.decide("Exactly", default.copy(style = "quiet")))
        assertEquals(AmbientParticipationPolicy.Decision.OBSERVE,
            policy.decide("Exactly", default, AmbientParticipationPolicy.Speaker.OTHER))
        policy.reset()
        assertEquals(AmbientParticipationPolicy.Decision.OBSERVE,
            policy.decide("Exactly", default))
    }

    @Test fun anAmbientAnswerCanStartANaturalTextOnlyThread() {
        var millis = 1_000L
        val policy = AmbientParticipationPolicy { millis }
        val active = LiveChatCompanionSettings(style = "active", initiative = 100)
        assertEquals(AmbientParticipationPolicy.Decision.OFFER_HELP,
            policy.decide("The microphone keeps cutting out", active))
        millis += 2_000
        assertEquals(AmbientParticipationPolicy.Decision.OFFER_HELP,
            policy.decide("Yes, that's what I was saying", active))
        millis += 1_000
        assertEquals(AmbientParticipationPolicy.Decision.OFFER_HELP,
            policy.decide("What about the noise suppression?", active))
    }

    @Test fun defaultCompanionDoesNotIgnoreClearQuestions() {
        val settings = LiveChatCompanionSettings()
        for (spoken in listOf("Can you hear me?", "Are you there?", "How do I test the microphone?",
            "Why did Jarvis stop talking?", "Tell me why the engine is surging", "Hello")) {
            assertEquals(spoken, AmbientParticipationPolicy.Decision.OFFER_HELP,
                AmbientParticipationPolicy { 1_000L }.decide(spoken, settings))
        }
        for (spoken in listOf("Pass me the wrench", "Please delete my messages",
            "Can you please install that app", "Jarvis has several problems")) {
            assertEquals(spoken, AmbientParticipationPolicy.Decision.OBSERVE,
                AmbientParticipationPolicy { 1_000L }.decide(spoken, settings))
        }
        assertEquals(AmbientParticipationPolicy.Decision.OBSERVE,
            AmbientParticipationPolicy { 1_000L }.decide("Why is it stalling?", settings.copy(style = "quiet")))
        assertEquals(AmbientParticipationPolicy.Decision.OBSERVE,
            AmbientParticipationPolicy { 1_000L }.decide("Why is it stalling?", settings,
                AmbientParticipationPolicy.Speaker.OTHER))
    }

    @Test fun directPersonalAddressHandlesNaturalOpeningWithoutAComma() {
        val settings = LiveChatCompanionSettings(style = "quiet")
        for (spoken in listOf("Hey Jarvis I need your help", "Jarvis are you there",
            "Hello Jarvis we have a problem", "Jarvis, how are you?")) {
            assertEquals(spoken, AmbientParticipationPolicy.Decision.RESPOND,
                AmbientParticipationPolicy { 1_000L }.decide(spoken, settings))
        }
        assertEquals(AmbientParticipationPolicy.Decision.OBSERVE,
            AmbientParticipationPolicy { 1_000L }.decide("Jarvis has been buggy", settings))
    }

    @Test fun vagueCheckOrLookupRequestsNeedConversationContext() {
        var now = 1_000L
        val settings = LiveChatCompanionSettings()
        val policy = AmbientParticipationPolicy { now }
        assertEquals(AmbientParticipationPolicy.Decision.OBSERVE,
            policy.decide("Can you check the timing?", settings))
        assertEquals(AmbientParticipationPolicy.Decision.OBSERVE,
            policy.decide("Can you look that up?", settings))
        assertEquals(AmbientParticipationPolicy.Decision.RESPOND,
            policy.decide("Jarvis, I'm reviewing this truck", settings))
        now += 2_000L
        assertEquals(AmbientParticipationPolicy.Decision.OFFER_HELP,
            policy.decide("Can you look that up?", settings))
        policy.reset()
        assertEquals(AmbientParticipationPolicy.Decision.OBSERVE,
            policy.decide("Can you look that up?", settings))
    }

    @Test fun ordinaryFollowUpQuestionsBypassProactiveCooldownOnlyInActiveThread() {
        var now = 1_000L
        val policy = AmbientParticipationPolicy { now }
        val settings = LiveChatCompanionSettings(style = "companion", initiative = 55)
        assertEquals(AmbientParticipationPolicy.Decision.OFFER_HELP,
            policy.decide("Can you hear me?", settings))
        now += 1_200L
        assertEquals(AmbientParticipationPolicy.Decision.OFFER_HELP,
            policy.decide("When can we try the next test?", settings))
        now += 1_200L
        assertEquals(AmbientParticipationPolicy.Decision.OFFER_HELP,
            policy.decide("Is the microphone still active?", settings))
        assertEquals(AmbientParticipationPolicy.Decision.OBSERVE,
            policy.decide("Open my messages", settings))
        now += 46_000L
        assertEquals(AmbientParticipationPolicy.Decision.OBSERVE,
            policy.decide("What about that other one?", settings))
        policy.reset()
        assertEquals(AmbientParticipationPolicy.Decision.OBSERVE,
            policy.decide("When can we try it?", settings.copy(initiative = 0)))
    }

    @Test fun preferencesNormalizeUnknownValuesAndPreserveApprovedDefaults() {
        val defaults = LiveChatCompanionSettings.fromJson(org.json.JSONObject())
        assertEquals("companion", defaults.style)
        assertEquals(55, defaults.initiative)
        assertEquals(true, defaults.interruptionsEnabled)
        assertEquals(true, defaults.contextEnabled)
        assertEquals(false, defaults.replyToOtherSpeakers)
        assertEquals(true, defaults.keepListeningUntilStopped)
        assertEquals(true, defaults.avoidRepeatedChimes)
        assertEquals(75, defaults.speechDetectionSensitivity)
        assertEquals(100, LiveChatCompanionSettings.fromJson(
            LiveChatCompanionSettings(speechDetectionSensitivity = 100).toJson()).speechDetectionSensitivity)
        assertEquals(0, LiveChatCompanionSettings(speechDetectionSensitivity = -10).normalized().speechDetectionSensitivity)
        assertEquals(false, LiveChatCompanionSettings.fromJson(
            LiveChatCompanionSettings(keepListeningUntilStopped = false).toJson()).keepListeningUntilStopped)
        val invalid = LiveChatCompanionSettings(style = "other", initiative = -20).normalized()
        assertEquals("companion", invalid.style)
        assertEquals(0, invalid.initiative)
    }

    @Test fun approved55CompanionRecognizesSpecificHelpRequestsWithoutRespondingToChatter() {
        val settings = LiveChatCompanionSettings(style = "companion", initiative = 55)
        for (spoken in listOf(
            "I need help with the hydraulic pressure",
            "I'm having trouble with this microphone",
            "Any suggestions for the fuel-pressure test",
            "I cannot get the engine to start",
            "I'm struggling to understand these fault codes"
        )) {
            assertEquals(spoken, AmbientParticipationPolicy.Decision.OFFER_HELP,
                AmbientParticipationPolicy { 1_000L }.decide(spoken, settings))
        }
        for (spoken in listOf(
            "I need to grab a wrench",
            "I like the way that engine sounds",
            "Hand him a wrench",
            "Open settings",
            "Please delete the file"
        )) {
            assertEquals(spoken, AmbientParticipationPolicy.Decision.OBSERVE,
                AmbientParticipationPolicy { 1_000L }.decide(spoken, settings))
        }
        assertEquals(AmbientParticipationPolicy.Decision.OBSERVE,
            AmbientParticipationPolicy { 1_000L }.decide(
                "I need help with the hydraulic pressure", settings.copy(style = "quiet")))
        assertEquals(AmbientParticipationPolicy.Decision.OBSERVE,
            AmbientParticipationPolicy { 1_000L }.decide(
                "I need help with the hydraulic pressure", settings,
                AmbientParticipationPolicy.Speaker.OTHER))
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
