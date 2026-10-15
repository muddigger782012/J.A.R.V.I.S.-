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
