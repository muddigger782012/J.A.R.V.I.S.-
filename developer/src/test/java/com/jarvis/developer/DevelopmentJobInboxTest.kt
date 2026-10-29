package com.jarvis.developer

import com.jarvis.integration.DevelopmentBridgeContract as Wire
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class DevelopmentJobInboxTest {
    @Test fun localTaskNamesAreReadOnlyAndNeverAddedToTheExternalStatusProtocol() {
        val inbox = DevelopmentJobInbox(directory.root)
        val request = Wire.Request("named", "diagnostics.review.request", "Inspect the application startup")
        val job = inbox.handle(caller, true, request)
        val before = directory.root.resolve("development-inbox.bin").readBytes()
        assertEquals(request.body, inbox.localSnapshot().single().getString("summary"))
        assertEquals(request.kind, inbox.localSnapshot().single().getString("kind"))
        val status = inbox.handle(caller, true, Wire.Request("status", "task.status.request", jobId = job.getString("id")))
        assertFalse(status.has("summary")); assertFalse(status.has("kind"))
        val listed = inbox.handle(caller, true, Wire.Request("list", "task.list.request")).getJSONArray("jobs").getJSONObject(0)
        assertFalse(listed.has("summary"))
        assertArrayEquals(before, directory.root.resolve("development-inbox.bin").readBytes())
    }
    @get:Rule val directory = TemporaryFolder()
    private val caller = "com.assistant.core"
    private fun request(id: String = "review") = Wire.Request(id, "diagnostics.review.request", "Unverified microphone observation")
    @Test fun ownerControlsPersistAcrossReconstructionAndRejectStaleClicks() {
        fun inbox() = DevelopmentJobInbox(directory.root)
        val id = inbox().handle(caller, true, request()).getString("id")
        assertEquals(listOf(DevelopmentJobInbox.LocalControl.PAUSE, DevelopmentJobInbox.LocalControl.CANCEL),
            inbox().localControls(id, 0, "supervised", true))
        val paused = inbox().controlLocal(id, 0, "supervised", true, DevelopmentJobInbox.LocalControl.PAUSE)
        assertEquals("PAUSED", paused.getString("state"))
        assertTrue(inbox().localControls(id, 0, "supervised", true).isEmpty())
        assertThrows(Wire.Rejected::class.java) { inbox().controlLocal(id, 0, "supervised", true, DevelopmentJobInbox.LocalControl.CANCEL) }
        val resumed = inbox().controlLocal(id, 1, "supervised", true, DevelopmentJobInbox.LocalControl.RESUME)
        assertEquals("QUEUED", resumed.getString("state"))
        assertEquals(1, resumed.getInt("attempts"))
        Wire.Stage.values().forEach { assertEquals("PENDING", resumed.getJSONObject("verification").getString(it.name)) }
        assertTrue(directory.root.resolve("development-inbox.bin").readText().contains("developer_ui"))
    }
    @Test fun ownerControlsRetainCommercialRestrictionsAndTheOriginalResumeMode() {
        val inbox = DevelopmentJobInbox(directory.root)
        val id = inbox.handle(caller, true, request().copy(mode = "owner_development")).getString("id")
        inbox.controlLocal(id, 0, "supervised", true, DevelopmentJobInbox.LocalControl.PAUSE)
        assertFalse(DevelopmentJobInbox.LocalControl.RESUME in inbox.localControls(id, 1, "supervised", true))
        assertThrows(Wire.Rejected::class.java) { inbox.controlLocal(id, 1, "supervised", true, DevelopmentJobInbox.LocalControl.RESUME) }
        assertThrows(Wire.Rejected::class.java) { inbox.controlLocal(id, 1, "owner_development", false, DevelopmentJobInbox.LocalControl.RESUME) }
        assertTrue(inbox.localControls(id, 1, "owner_development", false).isEmpty())
        assertEquals("QUEUED", inbox.controlLocal(id, 1, "owner_development", true,
            DevelopmentJobInbox.LocalControl.RESUME).getString("state"))
    }
    @Test fun localReadOnlyRetriesStopAtTheExistingAttemptLimit() {
        val inbox = DevelopmentJobInbox(directory.root)
        val id = inbox.handle(caller, true, request()).getString("id")
        var revision = 0L
        repeat(2) {
            inbox.controlLocal(id, revision++, "supervised", true, DevelopmentJobInbox.LocalControl.CANCEL)
            inbox.controlLocal(id, revision++, "supervised", true, DevelopmentJobInbox.LocalControl.RETRY)
        }
        inbox.controlLocal(id, revision++, "supervised", true, DevelopmentJobInbox.LocalControl.CANCEL)
        assertTrue(inbox.localControls(id, revision, "supervised", true).isEmpty())
        assertThrows(Wire.Rejected::class.java) { inbox.controlLocal(id, revision, "supervised", true, DevelopmentJobInbox.LocalControl.RETRY) }
        assertEquals(3, inbox.localSnapshot().single().getInt("attempts"))
        assertFalse(inbox.localSnapshot().single().getBoolean("sourceVerified"))
    }
    @Test fun concurrentOwnerControlsCannotApplyTheSameSnapshotTwice() {
        val inbox = DevelopmentJobInbox(directory.root)
        val id = inbox.handle(caller, true, request()).getString("id")
        val successes = java.util.concurrent.atomic.AtomicInteger()
        val failures = java.util.concurrent.atomic.AtomicInteger()
        val threads = listOf(DevelopmentJobInbox.LocalControl.PAUSE, DevelopmentJobInbox.LocalControl.CANCEL).map { action ->
            Thread {
                try { DevelopmentJobInbox(directory.root).controlLocal(id, 0, "supervised", true, action); successes.incrementAndGet() }
                catch (_: Wire.Rejected) { failures.incrementAndGet() }
            }.apply { start() }
        }
        threads.forEach { it.join(5_000); assertFalse(it.isAlive) }
        assertEquals(1, successes.get()); assertEquals(1, failures.get())
        assertEquals(1L, inbox.localSnapshot().single().getLong("revision"))
    }
    @Test fun resolvingTheFrameworkAliasDoesNotAllowLinksInsideTheInbox() {
        val data = directory.newFolder("data")
        val alias = directory.root.resolve("framework-storage")
        java.nio.file.Files.createSymbolicLink(alias.toPath(), data.toPath())
        val anchored = java.io.File(alias.canonicalFile, "development-inbox")
        DevelopmentJobInbox(anchored).handle(caller, true, request())
        assertEquals(1, DevelopmentJobInbox(anchored).pendingReviews().size)
        val untrusted = directory.root.resolve("linked-inbox")
        java.nio.file.Files.createSymbolicLink(untrusted.toPath(), anchored.toPath())
        assertThrows(Exception::class.java) { DevelopmentJobInbox(untrusted).handle(caller, true, request("blocked")) }
        assertEquals(1, DevelopmentJobInbox(anchored).pendingReviews().size)
    }
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
