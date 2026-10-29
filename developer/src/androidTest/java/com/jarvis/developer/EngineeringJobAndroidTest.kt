package com.jarvis.developer

import androidx.test.platform.app.InstrumentationRegistry
import com.jarvis.integration.DevelopmentBridgeContract as Wire
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.security.MessageDigest
import java.util.UUID

/** Real Android checkpoint/cancellation checks. Remote source/CI results are explicit fixtures. */
class EngineeringJobAndroidTest {
    private val source = "package com.jarvis.developer\nobject ConversationTone { val enabled = false }\n"
    private val path = "developer/src/main/java/com/jarvis/developer/ConversationTone.kt"
    private val base = "a".repeat(40)
    private class Fixture(val directory: File, val job: String, val draft: DevelopmentEngineering.Draft,
        val approval: PatchEngine.ApprovedProposal) {
        fun inbox() = DevelopmentJobInbox(directory)
    }
    private fun fixture(): Fixture {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val directory = File(context.cacheDir.canonicalFile, "engineering-metadata-test-" + UUID.randomUUID())
        val inbox = DevelopmentJobInbox(directory)
        val request = Wire.Request(UUID.randomUUID().toString(), "source.developer.analysis.request", "Improve conversation tone")
        val job = inbox.handle("com.assistant.core", true, request).getString("id")
        val bytes = source.toByteArray()
        val sha = MessageDigest.getInstance("SHA-1").digest("blob ${bytes.size}\u0000".toByteArray() + bytes)
            .joinToString("") { "%02x".format(it.toInt() and 255) }
        val inventory = RepositoryAnalysis.Inventory(listOf(RepositoryAnalysis.SourceFile(path, sha, bytes.size.toLong())), false, base)
        check(inbox.finishAnalysis(inbox.claimAnalysis(job, true)!!, RepositoryAnalysis.analyze(request.body, inventory) { source }))
        val draft = inbox.stageEngineering(job, 2, listOf(path), true)
        val proposal = PatchEngine.parse(PatchEngine.Request(draft.taskId, draft.task, draft.base, draft.paths,
            listOf(PatchEngine.SourceFile(path, sha, source)), "OpenAI", "gpt-4.1-mini"),
            JSONObject().put("summary", "Adjust conversation tone").put("edits", JSONArray().put(JSONObject()
                .put("path", path).put("content", source.replace("false", "true")))).toString())
        return Fixture(directory, job, draft, PatchEngine.approve(proposal, proposal.id))
    }
    private fun simulatedCommit(write: DevelopmentEngineering.Write) = write.receipt.let { r ->
        PatchPublisher.CommitResult(r.taskId, r.provider, r.model, r.proposalId, r.edits.map { it.path }.sorted(),
            r.base, "d".repeat(40), r.branch, "", r.baseBranch, r.target)
    }
    @Test fun engineeringCredentialsAreEphemeralAndRejectControlCharacters() {
        val job = UUID.randomUUID().toString()
        try {
            assertEquals("", DevelopmentEngineeringCredentials.token(job))
            assertThrows(IllegalArgumentException::class.java) {
                DevelopmentEngineeringCredentials.remember("", "invalid-job")
            }
            assertThrows(IllegalArgumentException::class.java) {
                DevelopmentEngineeringCredentials.remember("bad\njob", "invalid-job")
            }
            DevelopmentEngineeringCredentials.remember(job, "temporary-github-token")
            assertEquals("temporary-github-token", DevelopmentEngineeringCredentials.token(job))
            DevelopmentEngineeringCredentials.rememberForRead(JSONObject().put("id", job).put("state", "PAUSED"), "not-for-a-paused-task")
            assertEquals("temporary-github-token", DevelopmentEngineeringCredentials.token(job))
            DevelopmentEngineeringCredentials.rememberForRead(JSONObject().put("id", job).put("state", "VERIFYING"), "resumed-read-token")
            assertEquals("resumed-read-token", DevelopmentEngineeringCredentials.token(job))
            DevelopmentEngineeringCredentials.remember(job, "temporary-github-token")
            assertThrows(IllegalArgumentException::class.java) {
                DevelopmentEngineeringCredentials.remember(job, "unsafe\ncredential")
            }
            assertEquals("temporary-github-token", DevelopmentEngineeringCredentials.token(job))
            assertThrows(IllegalArgumentException::class.java) {
                DevelopmentEngineeringCredentials.remember(job, "x".repeat(4097))
            }
            assertEquals("temporary-github-token", DevelopmentEngineeringCredentials.token(job))
            DevelopmentEngineeringCredentials.remember(job, "")
            assertEquals("", DevelopmentEngineeringCredentials.token(job))
        } finally {
            DevelopmentEngineeringCredentials.forget(job)
        }
    }
    @Test fun engineeringCredentialCacheHasAnAtomicBoundAcrossConcurrentAdmissions() {
        val prefix = UUID.randomUUID().toString()
        val jobs = (0 until 96).map { "$prefix-$it" }
        val start = java.util.concurrent.CountDownLatch(1)
        val done = java.util.concurrent.CountDownLatch(jobs.size)
        val accepted = java.util.concurrent.atomic.AtomicInteger()
        try {
            jobs.forEach { job ->
                Thread {
                    try {
                        start.await()
                        DevelopmentEngineeringCredentials.remember(job, "ephemeral")
                        accepted.incrementAndGet()
                    } catch (_: IllegalArgumentException) {
                        // Capacity is intentionally bounded.
                    } finally {
                        done.countDown()
                    }
                }.start()
            }
            start.countDown()
            assertTrue("Credential admission threads timed out", done.await(15, java.util.concurrent.TimeUnit.SECONDS))
            assertTrue(accepted.get() <= 64)
            assertEquals(accepted.get(), jobs.count { DevelopmentEngineeringCredentials.token(it) == "ephemeral" })
        } finally {
            jobs.forEach(DevelopmentEngineeringCredentials::forget)
        }
    }
    @Test fun reconstructedCheckpointsCannotReplayAWriteApprovalOrInferAnInstallation() {
        val f = fixture()
        try {
            val write = f.inbox().claimEngineering(f.draft, f.approval, true)
            assertThrows(IllegalArgumentException::class.java) { f.inbox().claimEngineering(f.draft, f.approval, true) }
            assertTrue(f.inbox().uncertainEngineering(write))
            val read = f.inbox().claimEngineeringRead(f.job, true)!!
            assertTrue(f.inbox().observedEngineering(read, simulatedCommit(write)))
            val state = f.inbox().localSnapshot().single()
            assertTrue(state.getBoolean("sourceVerified")); assertFalse(state.getBoolean("deviceVerified"))
            assertEquals("PENDING", state.getJSONObject("verification").getString("TESTS_PASSED"))
            assertEquals("PENDING", state.getJSONObject("verification").getString("SIGNED_APK"))
            assertTrue(f.inbox().recoverableAnalysisJobs().isEmpty())
        } finally { f.directory.deleteRecursively() }
    }
    @Test fun cancellationPreservesRecordedSourceAndRejectsLateEvidenceOnAndroid() {
        val f = fixture()
        try {
            val write = f.inbox().claimEngineering(f.draft, f.approval, true)
            assertTrue(f.inbox().finishEngineering(write, simulatedCommit(write)))
            val read = f.inbox().claimEngineeringRead(f.job, true)!!
            val state = f.inbox().localSnapshot().single()
            f.inbox().handle("com.assistant.core", true, Wire.Request(UUID.randomUUID().toString(), "task.cancel.request",
                jobId = f.job, expectedRevision = state.getLong("revision")))
            assertFalse(f.inbox().engineeringReadFailed(read))
            val restored = f.inbox().localSnapshot().single()
            assertEquals("CANCELLED", restored.getString("state")); assertTrue(restored.getBoolean("sourceVerified"))
            assertFalse(restored.getBoolean("deviceVerified")); assertTrue(f.inbox().recoverableEngineeringJobs().isEmpty())
        } finally { f.directory.deleteRecursively() }
    }
    @Test fun localPauseAndResumeCannotRestoreAnEarlierSourceApproval() {
        val f = fixture()
        try {
            val state = f.inbox().localSnapshot().single()
            val paused = f.inbox().controlLocal(f.job, state.getLong("revision"), "supervised", true,
                DevelopmentJobInbox.LocalControl.PAUSE)
            val resumed = f.inbox().controlLocal(f.job, paused.getLong("revision"), "supervised", true,
                DevelopmentJobInbox.LocalControl.RESUME)
            assertEquals("AWAITING_APPROVAL", resumed.getString("state"))
            assertThrows(IllegalArgumentException::class.java) { f.inbox().claimEngineering(f.draft, f.approval, true) }
            assertFalse(resumed.getBoolean("sourceVerified"))
            assertTrue(f.inbox().recoverableEngineeringJobs().isEmpty())
        } finally { f.directory.deleteRecursively() }
    }
}
