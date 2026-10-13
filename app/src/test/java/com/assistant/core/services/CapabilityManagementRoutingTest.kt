package com.assistant.core.services

import com.assistant.core.learning.ValidatedCommandMemory
import org.junit.Assert.*
import org.junit.Test

class CapabilityManagementRoutingTest {
    @Test fun misheardNegatedAndCompoundManagementTextCannotBecomeAnActionAlias() {
        val memory = ValidatedCommandMemory({ "[]" }, { fail("management wording must not be learned"); false })
        for (text in listOf("Troy capability request start a timer for 10 minutes", "Dry capability request start a timer for 10 minutes",
            "try capability-request open Chrome", "don't try capability request open Chrome", "explain capability request start a timer for 10 minutes",
            "show capability requests and open Chrome", "capability request volume up", "request a capability", "propose a new capability")) {
            assertTrue(text, CapabilityDiscovery.needsClarification(text))
            assertTrue(text, CapabilityDiscovery.isControl(text))
            assertFalse(text, memory.recordSuccess(text, "volume_up", VerifiedVolumeLearning.capabilities))
        }
        assertFalse(CapabilityDiscovery.needsClarification("start a timer for 10 minutes"))
        assertFalse(CapabilityDiscovery.isManagementText("open Chrome"))
        assertFalse(CapabilityDiscovery.isManagementText("what are your capabilities"))
    }

    @Test fun exactControlsRetainTheirExistingMeaningAndSavedLookupDoesNotInventMatches() {
        for (text in listOf("try capability request start timer for 10 minutes", "review capability request open Chrome",
            "show capability requests", "request a capability to start timer for 10 minutes", "forget capability request open Chrome")) {
            assertFalse(text, CapabilityDiscovery.needsClarification(text)); assertTrue(CapabilityDiscovery.isControl(text))
        }
        var raw: String? = null
        val store = CapabilityRequestStore({ raw }, { raw = it; true })
        store.add("start timer for 10 minutes")
        assertNotNull(store.find("start timer for 10 minutes"))
        assertNull(store.find("start a timer for 10 minutes"))
    }

    @Test fun malformedNewTurnClearsApprovalAndOutcomeWithoutReportingSuccess() {
        var raw: String? = null
        val session = DeviceLearningSession({ raw }, { raw = it; true })
        val timer = LearnableDeviceAction("timer", mapOf("seconds" to "600"))
        for (outcome in listOf(false, true)) {
            session.propose(DeviceLearningSession.Pending("timer", timer, false, awaitingOutcome = outcome))
            assertNull(session.consume("")); assertNull(session.consume("yes")); assertNull(session.consume("it worked"))
        }
        val volume = VolumeConfirmationStore({ raw }, { raw = it; true })
        volume.propose("volume", FallbackIntentLesson("DEVICE_SETTING", .95, mapOf("setting" to "media_volume", "percent" to "25")))
        assertNull(volume.consume("")); assertNull(volume.consume("yes"))
        assertTrue(CapabilityDiscovery.clarification().contains("Nothing was run or approved"))
    }
}
