package com.assistant.core.services

import com.assistant.core.learning.ValidatedCommandMemory
import org.junit.Assert.*
import org.junit.Test

class CapabilityRequestReviewTest {
    private val resolve: (String) -> Pair<String, String>? = { label ->
        if (label.equals("Chrome", true)) "com.android.chrome" to "Chrome" else null
    }

    @Test fun definitionsUseExistingBoundedHandlersAndMaximumNeverBecomesOneStep() {
        val volume = CapabilityRequestReview.assess("raise media volume to 100%", resolve)
        assertEquals(CapabilityRequestReview.Status.EXISTING_HANDLER, volume.status)
        val volumeDefinition = volume.definition!!
        assertEquals(ExactVolumeLearning.CAPABILITY, volumeDefinition.capability)
        assertEquals(mapOf("percent" to "100"), volumeDefinition.parameters)
        assertEquals("set media volume to 100%", volumeDefinition.command)
        assertEquals("100", CapabilityRequestReview.assess("turn volume all the way up", resolve).definition!!.parameters["percent"])
        val timer = CapabilityRequestReview.assess("start a timer for 10 minutes", resolve).definition!!
        assertEquals("timer", timer.capability)
        assertEquals(mapOf("seconds" to "600"), timer.parameters)
        assertEquals("start a timer for 600 seconds", timer.command)
        val app = CapabilityRequestReview.assess("open Chrome", resolve).definition!!
        assertEquals("open_app", app.capability)
        assertEquals(mapOf("package" to "com.android.chrome", "label" to "Chrome"), app.parameters)
        assertEquals("open Chrome", app.command)
        assertEquals("volume_down", CapabilityRequestReview.assess("lower volume", resolve).definition!!.capability)
        val update = CapabilityRequestReview.assess("update app", resolve).definition!!
        assertEquals("app_update", update.capability)
        assertTrue(update.verification.contains("does not verify"))
    }

    @Test fun unknownMissingAmbiguousAndInvalidRequestsNeverReceiveExecutableDefinitions() {
        for (resolver in listOf<(String) -> Pair<String, String>?>({ null }, { "com.android.chrome" to "Wrong name" }, { throw IllegalStateException("Unavailable") })) {
            val result = CapabilityRequestReview.assess("open Chrome", resolver)
            assertEquals(CapabilityRequestReview.Status.APP_UNAVAILABLE, result.status); assertNull(result.definition)
        }
        for (text in listOf("start a timer", "start timer for 0 minutes", "start timer for 25 hours")) {
            val result = CapabilityRequestReview.assess(text, resolve)
            assertEquals(CapabilityRequestReview.Status.DETAILS_REQUIRED, result.status); assertNull(result.definition)
        }
        for (text in listOf("check my calendar before I leave", "don't open Chrome", "explain set media volume to 25%", "set media volume to 101%", "generate and run shell code")) {
            val result = CapabilityRequestReview.assess(text, resolve)
            assertEquals(CapabilityRequestReview.Status.REVIEW_REQUIRED, result.status); assertNull(result.definition)
        }
        assertNull(CapabilityRequestReview.assess("open Chrome and call Mom", resolve).definition)
        val unknown = CapabilityRequestReview.render(CapabilityRequestStore.Entry("check calendar", 1000),
            CapabilityRequestReview.Assessment(CapabilityRequestReview.Status.REVIEW_REQUIRED))
        assertTrue(unknown.contains("not proof")); assertTrue(unknown.contains("Nothing was run, approved, learned or installed"))
    }

    @Test fun exactReadOnlyLookupAndReviewDoNotWriteBackOrExposePackageInProductText() {
        var raw: String? = null
        val writer = CapabilityRequestStore({ raw }, { raw = it; true }, { 1000 })
        writer.add("open Chrome"); writer.add("start a timer for 10 minutes")
        val original = raw
        val reader = CapabilityRequestStore({ raw }, { fail("lookup/review must not write"); false })
        val entry = reader.find("  OPEN   CHROME! ")!!
        val result = CapabilityRequestReview.render(entry, CapabilityRequestReview.assess(entry.text, resolve))
        assertEquals(original, raw); assertEquals(2, reader.snapshot().size)
        assertTrue(result.contains("existing local capability matches")); assertTrue(result.contains("state a new request: open Chrome"))
        assertFalse(result.contains("com.android.chrome")); assertFalse(result.contains("installed successfully"))
        assertNull(reader.find("open")); assertNull(reader.find("")); assertNull(reader.find("x".repeat(241)))
    }

    @Test fun reviewIsReservedAndCanPreserveAnOutcomeButNeverBecomesApproval() {
        assertEquals("open Chrome", CapabilityDiscovery.review("review capability request \"open Chrome\""))
        assertEquals("", CapabilityDiscovery.review("review capability request"))
        assertNull(CapabilityDiscovery.review("don't review capability request open Chrome"))
        val memory = ValidatedCommandMemory({ "[]" }, { fail("control must not be learned"); false })
        assertFalse(memory.recordSuccess("review capability request open Chrome", "volume_up", VerifiedVolumeLearning.capabilities))
        var raw: String? = null
        val session = DeviceLearningSession({ raw }, { raw = it; true })
        val timer = LearnableDeviceAction("timer", mapOf("seconds" to "300"))
        session.propose(DeviceLearningSession.Pending("timer alias", timer, false, awaitingOutcome = true))
        val original = raw
        assertNull(session.consume("review capability request open Chrome")); assertEquals(original, raw)
        assertTrue(session.consume("yes")!!.reminder)
        assertTrue(session.consume("it worked")!!.accepted)
        session.propose(DeviceLearningSession.Pending("timer alias", timer, true))
        assertNull(session.consume("review capability request open Chrome")); assertNull(session.consume("yes"))
    }
}
