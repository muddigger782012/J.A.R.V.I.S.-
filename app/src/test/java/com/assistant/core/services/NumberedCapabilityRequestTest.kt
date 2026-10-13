package com.assistant.core.services

import org.junit.Assert.*
import org.junit.Test

class NumberedCapabilityRequestTest {
    @Test fun numberedLookupMatchesGlobalDisplayedOrderAndNeverWrites() {
        var raw: String? = null
        val writer = CapabilityRequestStore({ raw }, { raw = it; true }, { 1000 })
        for (n in 1..7) writer.add("feature $n")
        val before = raw
        val reader = CapabilityRequestStore({ raw }, { fail("lookup cannot write"); false })
        assertEquals("feature 7", reader.findReference("NUMBER 1!")!!.text)
        assertEquals("feature 2", reader.findReference("number 6")!!.text)
        assertTrue(CapabilityDiscovery.render(2, reader.snapshot()).contains("6. feature 2"))
        assertEquals(before, raw)
        assertEquals("feature 3", reader.findReference("FEATURE 3")!!.text)
        for (bad in listOf("number", "number 0", "number -1", "number 01", "number 8", "number 21", "number 999999999999999", "number zero", "number twenty one", "number one and open Chrome", "number won", "number 1 and open Chrome"))
            assertNull(bad, reader.findReference(bad))
    }

    @Test fun trialBindsTheResolvedEntryBeforeApprovalEvenIfListOrderChanges() {
        var backlog: String? = null
        val store = CapabilityRequestStore({ backlog }, { backlog = it; true }, { 1000 })
        store.add("start timer for 10 minutes")
        var raw: String? = null
        val session = DeviceLearningSession({ raw }, { raw = it; true })
        val entry = store.findReference("number one")!!
        val reply = CapabilityRequestTrial.prepare(entry, { null }, session::propose, { _, _ -> false })
        assertTrue(reply.contains("Saved request: start timer for 10 minutes"))
        assertTrue(reply.contains("600-second timer")); assertTrue(reply.contains("Nothing has run yet"))
        store.add("start timer for 1 minute")
        assertEquals("start timer for 1 minute", store.findReference("number 1")!!.text)
        val approval = session.consume("yes")!!
        assertEquals("600", approval.pending.action.parameters["seconds"])
        assertEquals("start a timer for 600 seconds", approval.pending.phrase)
        assertFalse(approval.pending.learnable); assertNull(session.consume("yes"))
    }

    @Test fun numberIsExplicitAndCannotSilentlyChangeExactDescriptionOrRemoval() {
        var raw: String? = null
        val store = CapabilityRequestStore({ raw }, { raw = it; true })
        store.add("1"); store.add("start timer for 10 minutes")
        assertEquals("1", store.findReference("1")!!.text)
        assertNull(store.findReference("start a timer for 10 minutes"))
        assertNull(store.find("number 1"))
        assertFalse(store.forget("number 1")); assertEquals(2, store.snapshot().size)
        assertEquals("number 1", CapabilityDiscovery.trial("try capability request number 1"))
        assertEquals("number 2", CapabilityDiscovery.review("review capability request number 2"))
        assertTrue(CapabilityDiscovery.isControl("try capability request number 1"))
        assertTrue(CapabilityDiscovery.needsClarification("Tri capability request number 1"))
    }

    @Test fun allSpokenNumbersMatchDigitsWithoutChangingSavedDataOrControlPrefix() {
        var raw: String? = null
        val writer = CapabilityRequestStore({ raw }, { raw = it; true }, { 1000 })
        for (n in 1..20) writer.add("feature $n")
        val before = raw
        val reader = CapabilityRequestStore({ raw }, { fail("spoken lookup must not write"); false })
        val words = listOf("one", "two", "three", "four", "five", "six", "seven", "eight", "nine", "ten",
            "eleven", "twelve", "thirteen", "fourteen", "fifteen", "sixteen", "seventeen", "eighteen", "nineteen", "twenty")
        words.forEachIndexed { index, word ->
            val reference = "number $word"
            assertEquals(reader.findReference("number ${index + 1}"), reader.findReference(reference))
            assertEquals(reference, CapabilityDiscovery.review("review capability request $reference"))
            assertEquals(reference, CapabilityDiscovery.trial("try capability request $reference"))
        }
        assertEquals("feature 20", reader.findReference(" NUMBER ONE! ")!!.text)
        assertEquals(before, raw)
        assertNull(reader.findReference("one"))
        assertTrue(CapabilityDiscovery.needsClarification("Troy capability request number one"))
        assertTrue(CapabilityDiscovery.needsClarification("Dry capability request number one"))
    }
}
