package com.jarvis.developer

import com.jarvis.integration.DevelopmentBridgeContract as Wire
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.security.MessageDigest

class DevelopmentAnalysisLeaseTest {
    @get:Rule val directory = TemporaryFolder()
    private fun inbox() = DevelopmentJobInbox(directory.root)
    private val caller = "com.assistant.core"
    private val request = Wire.Request("source-review", "source.personal.analysis.request", "Inspect conversation reliability")
    private fun admit() = inbox().handle(caller, true, request).getString("id")
    private fun plan(): RepositoryAnalysis.AnalysisPlan {
        val source = "package example\nobject Conversation { fun reliability() = 1 }"
        val bytes = source.toByteArray()
        val hash = MessageDigest.getInstance("SHA-1").digest("blob ${bytes.size}\u0000".toByteArray() + bytes)
            .joinToString("") { "%02x".format(it.toInt() and 255) }
        return RepositoryAnalysis.analyze(request.body, RepositoryAnalysis.Inventory(listOf(RepositoryAnalysis.SourceFile(
            "app/src/main/java/example/Conversation.kt", hash, bytes.size.toLong())), false, "a".repeat(40))) { source }
    }
    @Test fun sourceAnalysisUsesDurableLeasesAndNeverMarksDevelopmentVerificationPassed() {
        val job = admit(); val first = inbox().claimAnalysis(job, true)!!
        val recovered = inbox().claimAnalysis(job, true)!!
        assertNotEquals(first.token, recovered.token)
        assertFalse(inbox().finishAnalysis(first, plan()))
        assertTrue(inbox().finishAnalysis(recovered, plan()))
        val state = inbox().handle(caller, true, Wire.Request("status", "task.status.request", jobId = job))
        assertEquals("REVIEW_READY", state.getString("state"))
        assertEquals("a".repeat(40), state.getJSONObject("analysis").getString("commit"))
        for (stage in Wire.Stage.values()) assertEquals("PENDING", state.getJSONObject("verification").getString(stage.name))
        assertFalse(state.getBoolean("sourceVerified")); assertFalse(state.getBoolean("deviceVerified"))
        assertTrue(inbox().localAnalysisReport(job)!!.contains("untrusted source data"))
        assertTrue(inbox().recoverableAnalysisJobs().isEmpty())
    }
    @Test fun cancellationAndPauseInvalidateLateAnalysisResultsAcrossProcessReconstruction() {
        val job = admit(); val lease = inbox().claimAnalysis(job, true)!!
        inbox().handle(caller, true, Wire.Request("pause", "task.pause.request", jobId = job, expectedRevision = 1))
        assertFalse(inbox().finishAnalysis(lease, plan())); assertFalse(inbox().failAnalysis(lease))
        assertTrue(inbox().recoverableAnalysisJobs().isEmpty())
        inbox().handle(caller, true, Wire.Request("resume", "task.resume.request", jobId = job, expectedRevision = 2))
        val next = inbox().claimAnalysis(job, true)!!
        assertNotEquals(lease.token, next.token)
        inbox().handle(caller, true, Wire.Request("cancel", "task.cancel.request", jobId = job, expectedRevision = 4))
        assertFalse(inbox().finishAnalysis(next, plan())); assertNull(inbox().localAnalysisReport(job))
    }
    @Test fun aFailedReadRecordsOnlyASanitizedProblemAndNeedsAnExplicitBoundedRetry() {
        val job = admit(); val lease = inbox().claimAnalysis(job, true)!!
        assertTrue(inbox().failAnalysis(lease))
        assertTrue(inbox().recoverableAnalysisJobs().isEmpty())
        val state = inbox().handle(caller, true, Wire.Request("status", "task.status.request", jobId = job))
        assertEquals("FAILED", state.getString("state")); assertEquals("SOURCE_READ_UNVERIFIED", state.getString("problem"))
        assertNull(inbox().localAnalysisReport(job))
        inbox().handle(caller, true, Wire.Request("retry", "task.retry.request", jobId = job, expectedRevision = 2))
        assertEquals(listOf(job), inbox().recoverableAnalysisJobs())
    }
    @Test fun queuedOwnerSourceAnalysisCannotRunAfterAnEditionChangeAndReviewsCannotBecomeWorkers() {
        val owner = inbox().handle(caller, true, request.copy(mode = "owner_development")).getString("id")
        assertThrows(Wire.Rejected::class.java) { inbox().claimAnalysis(owner, false) }
        val review = inbox().handle(caller, true, Wire.Request("review-only", "diagnostics.review.request", "Review only")).getString("id")
        assertNull(inbox().claimAnalysis(review, true))
    }
    @Test fun aSystemInterruptionQueuesOnlyTheCurrentLeaseAndNeverResumesAPausedTask() {
        val job = admit(); val first = inbox().claimAnalysis(job, true)!!
        assertTrue(inbox().interruptAnalysis(first))
        assertEquals(listOf(job), inbox().recoverableAnalysisJobs())
        val next = inbox().claimAnalysis(job, true)!!
        assertFalse(inbox().interruptAnalysis(first))
        inbox().handle(caller, true, Wire.Request("pause", "task.pause.request", jobId = job, expectedRevision = 3))
        assertFalse(inbox().interruptAnalysis(next)); assertTrue(inbox().recoverableAnalysisJobs().isEmpty())
    }
}
