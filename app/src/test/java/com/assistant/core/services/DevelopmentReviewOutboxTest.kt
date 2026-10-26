package com.assistant.core.services

import com.jarvis.integration.DevelopmentBridgeContract as Wire
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class DevelopmentReviewOutboxTest {
    @get:Rule val directory = TemporaryFolder()
    private fun outbox() = DevelopmentReviewOutbox(PersonalStateFile(directory.root, "developer-outbox.json"))
    @Test fun uncertainDeliverySurvivesReconstructionWithoutAllocatingAnotherRequestId() {
        val request = outbox().prepare("Review only", "supervised")
        outbox().record(request, null)
        assertEquals(request, outbox().prepare("Review only", "supervised"))
        assertEquals(listOf(request), outbox().pending("supervised"))
        assertTrue(outbox().pending("owner_development").isEmpty())
        outbox().record(request, JSONObject(Wire.response(request.requestId, "OK", JSONObject().put("id", "job_test"))))
        assertEquals("job_test", outbox().latestJob()); assertTrue(outbox().pending("supervised").isEmpty())
    }
    @Test fun forgedAcknowledgementsCannotBeAttachedToAnotherRequestOrUnknownJob() {
        val request = outbox().prepare("Review only", "supervised")
        assertThrows(Exception::class.java) { outbox().record(request,
            JSONObject(Wire.response("other", "OK", JSONObject().put("id", "job_test")))) }
        assertThrows(Exception::class.java) { outbox().record(request,
            JSONObject(Wire.response(request.requestId, "OK", JSONObject().put("id", "../outside")))) }
        assertThrows(Wire.Rejected::class.java) { outbox().latestJob() }
        assertEquals(listOf(request), outbox().pending("supervised"))
    }
    @Test fun malformedOrFullOutboxIsPreservedInsteadOfResetOrSilentlyEvicted() {
        repeat(16) { outbox().prepare("Review $it", "supervised") }
        assertThrows(Wire.Rejected::class.java) { outbox().prepare("Overflow", "supervised") }
        assertEquals(16, outbox().pending("supervised").size)
        directory.root.resolve("developer-outbox.json").writeText("corrupted")
        assertThrows(Exception::class.java) { outbox().prepare("Replacement", "supervised") }
        assertEquals("corrupted", directory.root.resolve("developer-outbox.json").readText())
    }
}
