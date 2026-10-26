package com.jarvis.developer

import com.jarvis.integration.DevelopmentBridgeContract as Wire
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class DevelopmentJobInboxTest {
    @get:Rule val directory = TemporaryFolder()
    private val caller = "com.assistant.core"
    private fun request(id: String = "review") = Wire.Request(id, "diagnostics.review.request", "Unverified microphone observation")
    @Test fun replayAndProcessReconstructionCreateExactlyOneReviewWithoutInventingVerification() {
        val first = DevelopmentJobInbox(directory.root).handle(caller, true, request())
        val second = DevelopmentJobInbox(directory.root).handle(caller, true, request())
        assertEquals(first.getString("id"), second.getString("id")); assertTrue(second.getBoolean("duplicate"))
        assertEquals(1, DevelopmentJobInbox(directory.root).pendingReviews().size)
        for (stage in Wire.Stage.values()) assertEquals("PENDING", second.getJSONObject("verification").getString(stage.name))
        assertFalse(second.getBoolean("sourceVerified")); assertFalse(second.getBoolean("deviceVerified"))
    }
    @Test fun reusedIdsCannotChangeScopeAndCommercialModesCannotAdmitOwnerJobs() {
        val inbox = DevelopmentJobInbox(directory.root); inbox.handle(caller, true, request())
        assertThrows(Wire.Rejected::class.java) { inbox.handle(caller, true, request().copy(body = "Different objective")) }
        assertThrows(Wire.Rejected::class.java) { inbox.handle("com.assistant.core.beta", true, request("beta").copy(mode = "owner_development")) }
        assertThrows(Wire.Rejected::class.java) { inbox.handle(caller, false, request("commercial").copy(mode = "owner_development")) }
        assertEquals(1, inbox.pendingReviews().size)
    }
    @Test fun controlsRequireCurrentRevisionAndOldCancellationCannotCancelAResumedRetry() {
        val inbox = DevelopmentJobInbox(directory.root)
        val first = inbox.handle(caller, true, request()); val job = first.getString("id")
        val cancel = Wire.Request("cancel", "task.cancel.request", jobId = job, expectedRevision = 0)
        inbox.handle(caller, true, cancel)
        val retried = inbox.handle(caller, true, Wire.Request("retry", "task.retry.request", jobId = job, expectedRevision = 1))
        assertEquals("QUEUED", retried.getString("state"))
        val duplicate = inbox.handle(caller, true, cancel)
        assertTrue(duplicate.getBoolean("duplicate")); assertEquals("QUEUED", duplicate.getString("state"))
        assertThrows(Wire.Rejected::class.java) { inbox.handle(caller, true, cancel.copy(requestId = "stale")) }
    }
    @Test fun foreignCallersAndCorruptCheckpointsCannotReadOrResetAnExistingJob() {
        val inbox = DevelopmentJobInbox(directory.root); val job = inbox.handle(caller, true, request()).getString("id")
        assertThrows(Wire.Rejected::class.java) { inbox.handle("com.assistant.core.beta", true, Wire.Request("foreign", "task.status.request", jobId = job)) }
        directory.root.resolve("development-inbox.bin").writeText("damaged checkpoint")
        assertThrows(Exception::class.java) { inbox.handle(caller, true, request("new")) }
        assertEquals("damaged checkpoint", directory.root.resolve("development-inbox.bin").readText())
    }
    @Test fun changingToCommercialCannotResumeOrReplayAnOwnerModeJob() {
        val inbox = DevelopmentJobInbox(directory.root)
        val first = inbox.handle(caller, true, request().copy(mode = "owner_development"))
        val pause = Wire.Request("pause", "task.pause.request", jobId = first.getString("id"), expectedRevision = 0)
        inbox.handle(caller, true, pause)
        assertThrows(Wire.Rejected::class.java) { inbox.handle(caller, false, pause) }
        assertThrows(Wire.Rejected::class.java) { inbox.handle(caller, false,
            Wire.Request("resume", "task.resume.request", jobId = first.getString("id"), expectedRevision = 1)) }
    }
    @Test fun resumingCannotSilentlyUpgradeTheCurrentlySelectedMode() {
        val inbox = DevelopmentJobInbox(directory.root)
        val job = inbox.handle(caller, true, request().copy(mode = "owner_development")).getString("id")
        inbox.handle(caller, true, Wire.Request("pause", "task.pause.request", jobId = job, expectedRevision = 0))
        assertThrows(Wire.Rejected::class.java) { inbox.handle(caller, true,
            Wire.Request("resume-low", "task.resume.request", jobId = job, expectedRevision = 1)) }
        assertEquals("QUEUED", inbox.handle(caller, true,
            Wire.Request("resume-owner", "task.resume.request", mode = "owner_development", jobId = job, expectedRevision = 1)).getString("state"))
    }
}
