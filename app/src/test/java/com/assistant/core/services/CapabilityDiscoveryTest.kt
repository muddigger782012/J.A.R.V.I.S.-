package com.assistant.core.services

import com.assistant.core.learning.ValidatedCommandMemory
import org.junit.Assert.*
import org.junit.Test

class CapabilityDiscoveryTest {
    @Test fun explicitRequestsPersistAsInertDescriptionsAndDuplicateWithoutReplacingOriginal() {
        var raw: String? = null
        var writes = 0
        var now = 1000L
        fun store() = CapabilityRequestStore({ raw }, { raw = it; writes++; true }, { now })
        assertEquals("check my calendar before I leave", CapabilityDiscovery.request("Request a capability to check my calendar before I leave."))
        assertEquals(CapabilityRequestStore.Result.ADDED, store().add(" check my calendar before I leave "))
        now += 500
        assertEquals(CapabilityRequestStore.Result.DUPLICATE, store().add("CHECK MY CALENDAR BEFORE I LEAVE!"))
        assertEquals(1, writes); assertEquals(1000L, store().snapshot().single().created)
        assertEquals(CapabilityRequestStore.Result.ADDED, store().add("open Chrome and run arbitrary code"))
        // This content is a request description only; storage has no executor or learned-command API.
        assertEquals(2, store().snapshot().size)
        val review = CapabilityDiscovery.render(1, store().snapshot())
        assertTrue(review.contains("not proof of implementation")); assertTrue(review.contains("requested; implementation not verified"))
        assertTrue(store().forget("check my calendar before I leave"))
        assertEquals("open Chrome and run arbitrary code", store().snapshot().single().text)
        assertFalse(store().forget("missing request"))
    }

    @Test fun capacityAndInvalidDescriptionsNeverEvictOrClaimUnstoredSuccess() {
        var raw: String? = null
        val store = CapabilityRequestStore({ raw }, { raw = it; true }, { 1000 })
        for (index in 1..CapabilityRequestStore.LIMIT) assertEquals(CapabilityRequestStore.Result.ADDED, store.add("feature $index"))
        val original = raw
        assertEquals(CapabilityRequestStore.Result.FULL, store.add("another feature")); assertEquals(original, raw)
        assertEquals(CapabilityRequestStore.Result.INVALID, store.add(" "))
        assertEquals(CapabilityRequestStore.Result.INVALID, store.add("x".repeat(CapabilityRequestStore.MAX_TEXT + 1)))
        assertEquals(original, raw)
        assertTrue(CapabilityDiscovery.render(1, store.snapshot()).contains("Page 1 of 4"))
        assertTrue(CapabilityDiscovery.render(4, store.snapshot()).contains("feature 1"))
        assertFalse(CapabilityDiscovery.render(4, store.snapshot()).contains("More:"))
        assertTrue(CapabilityDiscovery.render(5, store.snapshot()).contains("There are 4"))
        assertTrue(store.forget("feature 1")); assertEquals(CapabilityRequestStore.Result.ADDED, store.add("another feature"))
        val failed = CapabilityRequestStore({ "[]" }, { false })
        assertEquals(CapabilityRequestStore.Result.FAILED, failed.add("calendar workflow"))
        assertTrue(failed.snapshot().isEmpty())
        assertTrue(CapabilityRequestStore({ "invalid json" }, { fail("review is read only"); false }).snapshot().isEmpty())
    }

    @Test fun boundedLocalControlsCannotBecomeAliasesOrInterpretUnrelatedQueries() {
        assertTrue(CapabilityDiscovery.isHelp("What can you do offline?"))
        assertEquals(2, CapabilityDiscovery.page("show my capability requests page 2"))
        assertEquals(0, CapabilityDiscovery.page("show capability requests page 99999999999999"))
        for (text in listOf("don't show capability requests", "explain request a capability to check calendar", "show capability requests and open Chrome"))
            assertTrue(CapabilityDiscovery.needsClarification(text))
        assertNull(CapabilityDiscovery.request("don't request a capability to open Chrome"))
        assertEquals("calendar workflow", CapabilityDiscovery.forget("forget capability request \"calendar workflow\""))
        val commands = ValidatedCommandMemory({ "[]" }, { fail("discovery controls must not be learned"); false })
        for (text in listOf("show offline capabilities", "show capability requests", "request a capability to check calendar", "forget capability request calendar workflow"))
            assertFalse(commands.recordSuccess(text, "volume_up", VerifiedVolumeLearning.capabilities))
        assertTrue(CapabilityDiscovery.help().contains("does not generate new executable features"))
        assertTrue(CapabilityDiscovery.help().contains("web search need internet"))
    }

    @Test fun readOnlyDiscoveryPreservesPendingOutcomeButNewFeatureRequestsInvalidateIt() {
        var raw: String? = null
        val session = DeviceLearningSession({ raw }, { raw = it; true })
        val timer = LearnableDeviceAction("timer", mapOf("seconds" to "300"))
        assertTrue(session.propose(DeviceLearningSession.Pending("short break", timer, false, awaitingOutcome = true)))
        val original = raw
        assertNull(session.consume("show offline capabilities")); assertEquals(original, raw)
        assertNull(session.consume("show capability requests")); assertEquals(original, raw)
        assertTrue(session.consume("it worked")!!.accepted)
        session.propose(DeviceLearningSession.Pending("short break", timer, false, awaitingOutcome = true))
        assertNull(session.consume("request a capability to check calendar")); assertNull(session.consume("it worked"))
    }
}
