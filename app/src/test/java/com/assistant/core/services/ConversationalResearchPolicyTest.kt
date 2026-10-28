package com.assistant.core.services

import org.junit.Assert.assertEquals
import org.junit.Test

class ConversationalResearchPolicyTest {
    @Test fun currentInformationRequestsTriggerResearch() {
        assertEquals(ConversationalResearchPolicy.Decision.RESEARCH,
            ConversationalResearchPolicy.decide("Look up the latest service bulletin", online = true))
    }

    @Test fun offlineRequestsNeverTriggerResearch() {
        assertEquals(ConversationalResearchPolicy.Decision.ANSWER_LOCALLY,
            ConversationalResearchPolicy.decide("Look up the latest service bulletin", online = false))
    }

    @Test fun deviceCommandsRemainLocal() {
        assertEquals(ConversationalResearchPolicy.Decision.ANSWER_LOCALLY,
            ConversationalResearchPolicy.decide("Open the current settings menu", online = true, deviceCommand = true))
        assertEquals(ConversationalResearchPolicy.Decision.ANSWER_LOCALLY,
            ConversationalResearchPolicy.decide("Set a timer for today", online = true))
    }

    @Test fun modelSpecificSpecsRequestEvidenceWithoutFabricatingIt() {
        assertEquals(ConversationalResearchPolicy.Decision.OFFER_RESEARCH,
            ConversationalResearchPolicy.decide("What is the fuel tank capacity for this machine?", online = true))
    }

    @Test fun manualRetrievalTriggersGroundedResearch() {
        assertEquals(ConversationalResearchPolicy.Decision.RESEARCH,
            ConversationalResearchPolicy.decide("Pull up the technical manual for me", online = true))
        assertEquals(ConversationalResearchPolicy.Decision.RESEARCH,
            ConversationalResearchPolicy.decide("Find the John Deere 450J service manual", online = true))
    }

    @Test fun manualRetrievalRespectsOfflineAndDeviceCommands() {
        assertEquals(ConversationalResearchPolicy.Decision.ANSWER_LOCALLY,
            ConversationalResearchPolicy.decide("Pull up the technical manual for me", online = false))
        assertEquals(ConversationalResearchPolicy.Decision.ANSWER_LOCALLY,
            ConversationalResearchPolicy.decide("Pull up the technical manual for me",
                online = true, deviceCommand = true))
    }

    @Test fun followUpUsesPreviousResearchSubject() {
        assertEquals(ConversationalResearchPolicy.Decision.RESEARCH,
            ConversationalResearchPolicy.decide("How much does it hold?", online = true,
                previousResearchQuery = "John Deere 450J fuel tank"))
    }

    @Test fun followUpWithoutSubjectDoesNotTriggerResearch() {
        assertEquals(ConversationalResearchPolicy.Decision.ANSWER_LOCALLY,
            ConversationalResearchPolicy.decide("How much does it hold?", online = true))
    }

    @Test fun deviceCommandWinsOverResearchHistory() {
        assertEquals(ConversationalResearchPolicy.Decision.ANSWER_LOCALLY,
            ConversationalResearchPolicy.decide("Open settings", online = true,
                previousResearchQuery = "John Deere 450J fuel tank"))
    }

    @Test fun ordinaryChatStaysLocal() {
        assertEquals(ConversationalResearchPolicy.Decision.ANSWER_LOCALLY,
            ConversationalResearchPolicy.decide("Explain why diesel engines use turbos", online = true))
    }
}
