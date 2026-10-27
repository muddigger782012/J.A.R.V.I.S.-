package com.jarvis.developer

import com.jarvis.integration.DevelopmentBridgeContract as Wire
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.security.MessageDigest
import java.util.UUID

class DevelopmentEngineeringTest {
    private fun installReview(job: String, mode: String = "supervised") = Wire.Request(UUID.randomUUID().toString(), Wire.ARTIFACT_INSTALL_REVIEW,
        mode = mode, jobId = job, expectedRevision = status(job).getLong("revision"))
    @Test fun installationHandoffCannotUseNavigationAuthorityAndNeverStartsASessionByItself() {
        val source = artifactSource(); assertTrue(inbox().recordArtifact(source, apk(source)))
        val navigation = inbox().handle(caller, true, review(source.job)).getJSONObject("artifactReview").getString("ticket")
        assertThrows(Wire.Rejected::class.java) { inbox().consumeArtifactReview(navigation, true, installation = true) }
        val request = installReview(source.job)
        val response = inbox().handle(caller, true, request)
        val ticket = response.getJSONObject("artifactReview").getString("ticket")
        assertEquals("installation", response.getJSONObject("artifactReview").getString("purpose"))
        assertEquals(ticket, inbox().handle(caller, true, request).getJSONObject("artifactReview").getString("ticket"))
        assertFalse(status(source.job).toString().contains(ticket))
        assertNull(inbox().installationRecord(source.job))
        assertThrows(Wire.Rejected::class.java) { inbox().consumeArtifactReview(ticket, true) }
        val delivered = inbox().consumeArtifactReview(ticket, true, installation = true)
        assertEquals("PASSED", delivered.getJSONObject("verification").getString("SIGNED_APK"))
        assertNull(inbox().installationRecord(source.job)); assertFalse(delivered.getBoolean("deviceVerified"))
        assertThrows(Wire.Rejected::class.java) { inbox().consumeArtifactReview(ticket, true, installation = true) }
        assertThrows(Wire.Rejected::class.java) { inbox().handle(caller, true, request) }
        assertLegacyStages()
    }
    @Test fun explicitInstallationCancellationRevokesTheHandoffWithoutCancellingSourceOrInventingInstallation() {
        val source = artifactSource(); assertTrue(inbox().recordArtifact(source, apk(source)))
        val ticket = inbox().handle(caller, true, installReview(source.job)).getJSONObject("artifactReview").getString("ticket")
        val cancel = Wire.Request(UUID.randomUUID().toString(), Wire.ARTIFACT_INSTALL_CANCEL,
            jobId = source.job, expectedRevision = status(source.job).getLong("revision"))
        val cancelled = inbox().handle(caller, true, cancel)
        assertTrue(cancelled.getJSONObject("artifactReview").getBoolean("cancelled"))
        assertFalse(cancelled.getJSONObject("artifactReview").getBoolean("consumed"))
        assertEquals("READY_FOR_REVIEW", cancelled.getString("state"))
        assertEquals("PASSED", cancelled.getJSONObject("verification").getString("SIGNED_APK"))
        assertNull(inbox().installationRecord(source.job))
        assertEquals(cancelled.getLong("revision"), inbox().handle(caller, true, cancel).getLong("revision"))
        assertThrows(Wire.Rejected::class.java) { inbox().consumeArtifactReview(ticket, true, installation = true) }
    }
    @Test fun voiceCancellationAlsoInvalidatesAnOwnedPendingSessionAndItsLateCallback() {
        val source = artifactSource(); val record = apk(source); assertTrue(inbox().recordArtifact(source, record))
        val initial = inbox().beginInstallation(source.job, status(source.job).getLong("revision"), record.apkSha256, true)
        val session = inbox().bindInstallation(initial, 73)
        inbox().handle(caller, true, Wire.Request(UUID.randomUUID().toString(), Wire.ARTIFACT_INSTALL_CANCEL,
            jobId = source.job, expectedRevision = status(source.job).getLong("revision")))
        assertFalse(inbox().installationStatus(session, "PACKAGE_PRESENT", 0))
        assertEquals("CANCELLED", status(source.job).getJSONObject("artifact").getString("installation"))
        assertEquals("BLOCKED", status(source.job).getJSONObject("verification").getString("DEVICE_INSTALLED"))
        assertTrue(inbox().pendingInstallations().isEmpty())
    }
    @Test fun personalInstallationConsentCannotBePreparedOrConsumedByCommercialParticipants() {
        val source = artifactSource(); assertTrue(inbox().recordArtifact(source, apk(source)))
        val request = installReview(source.job)
        for (receiver in listOf(false, true)) {
            assertEquals("POLICY_REQUIRED", assertThrows(Wire.Rejected::class.java) { inbox().handle("com.assistant.core.beta", receiver, request) }.code)
        }
        assertEquals("POLICY_REQUIRED", assertThrows(Wire.Rejected::class.java) { inbox().handle(caller, false, request) }.code)
        val ticket = inbox().handle(caller, true, request).getJSONObject("artifactReview").getString("ticket")
        assertEquals("POLICY_REQUIRED", assertThrows(Wire.Rejected::class.java) { inbox().consumeArtifactReview(ticket, false, installation = true) }.code)
        assertTrue(inbox().consumeArtifactReview(ticket, true, installation = true).getJSONObject("artifactReview").getBoolean("consumed"))
    }
    @Test fun installationConsentExpiresFailsOnClockRollbackAndCannotSurviveChangedBuildEvidence() {
        val source = artifactSource(); assertTrue(inbox().recordArtifact(source, apk(source)))
        var time = 1000L
        val queue = DevelopmentJobInbox(directory.root) { time }
        val request = installReview(source.job)
        val ticket = queue.handle(caller, true, request).getJSONObject("artifactReview").getString("ticket")
        time = 999
        assertThrows(Wire.Rejected::class.java) { queue.consumeArtifactReview(ticket, true, installation = true) }
        time = 121000
        assertThrows(Wire.Rejected::class.java) { queue.consumeArtifactReview(ticket, true, installation = true) }
        time = 200000
        val newer = queue.handle(caller, true, installReview(source.job)).getJSONObject("artifactReview").getString("ticket")
        queue.refreshEngineeringEvidence(source.job, status(source.job).getLong("revision"), true)
        assertThrows(Wire.Rejected::class.java) { queue.consumeArtifactReview(newer, true, installation = true) }
        assertTrue(queue.pendingInstallations().isEmpty())
    }
    @Test fun anActiveInstallationPreventsAnotherProposalOrDeliveryEvenForAnotherVerifiedJob() {
        val first = artifactSource(); assertTrue(inbox().recordArtifact(first, apk(first)))
        val second = artifactSource(); assertTrue(inbox().recordArtifact(second, apk(second)))
        val ticket = inbox().handle(caller, true, installReview(second.job)).getJSONObject("artifactReview").getString("ticket")
        inbox().beginInstallation(first.job, status(first.job).getLong("revision"), apk(first).apkSha256, true)
        assertThrows(Wire.Rejected::class.java) { inbox().handle(caller, true, installReview(second.job)) }
        assertThrows(Wire.Rejected::class.java) { inbox().consumeArtifactReview(ticket, true, installation = true) }
        assertNull(inbox().installationRecord(second.job))
    }
    private fun review(job: String, mode: String = "supervised") = Wire.Request(UUID.randomUUID().toString(), Wire.ARTIFACT_REVIEW,
        mode = mode, jobId = job, expectedRevision = status(job).getLong("revision"))
    @Test fun apkReviewIsOneUseNavigationAcrossRestartAndNeverAnInstallationApproval() {
        val source = artifactSource(); assertTrue(inbox().recordArtifact(source, apk(source)))
        val request = review(source.job)
        val ack = inbox().handle(caller, true, request)
        val ticket = ack.getJSONObject("artifactReview").getString("ticket")
        assertEquals(ticket, inbox().handle(caller, true, request).getJSONObject("artifactReview").getString("ticket"))
        assertFalse(status(source.job).toString().contains(ticket))
        assertFalse(inbox().localSnapshot().single().toString().contains(ticket))
        val opened = inbox().consumeArtifactReview(ticket, true)
        assertEquals(source.job, opened.getString("id")); assertTrue(opened.getJSONObject("artifactReview").getBoolean("consumed"))
        assertEquals("PASSED", opened.getJSONObject("verification").getString("SIGNED_APK"))
        assertEquals("PENDING", opened.getJSONObject("verification").getString("DEVICE_INSTALLED"))
        assertFalse(opened.getBoolean("deviceVerified")); assertNull(inbox().installationRecord(source.job))
        assertThrows(Wire.Rejected::class.java) { inbox().consumeArtifactReview(ticket, true) }
        assertThrows(Wire.Rejected::class.java) { inbox().handle(caller, true, request) }
        assertLegacyStages()
    }
    @Test fun apkReviewRejectsWrongCallerModeRevisionAndUnverifiedSource() {
        val unready = analyzed()
        assertEquals("UNVERIFIED_ARTIFACT", assertThrows(Wire.Rejected::class.java) { inbox().handle(caller, true, review(unready)) }.code)
        val source = artifactSource(); assertTrue(inbox().recordArtifact(source, apk(source)))
        val request = review(source.job)
        assertEquals("NOT_FOUND", assertThrows(Wire.Rejected::class.java) { inbox().handle("com.assistant.core.beta", true, request) }.code)
        assertEquals("POLICY_REQUIRED", assertThrows(Wire.Rejected::class.java) { inbox().handle(caller, true, request.copy(mode = "authorized_autonomous")) }.code)
        assertEquals("CONFLICT", assertThrows(Wire.Rejected::class.java) { inbox().handle(caller, true, request.copy(expectedRevision = 0)) }.code)
        assertFalse(status(source.job).has("artifactReview"))
    }
    @Test fun cancelledOrChangedJobsCannotConsumeAnEarlierReviewTicket() {
        val source = artifactSource(); assertTrue(inbox().recordArtifact(source, apk(source)))
        val ticket = inbox().handle(caller, true, review(source.job)).getJSONObject("artifactReview").getString("ticket")
        control(source.job, "task.cancel.request")
        assertThrows(Wire.Rejected::class.java) { inbox().consumeArtifactReview(ticket, true) }
        val other = artifactSource(); assertTrue(inbox().recordArtifact(other, apk(other)))
        val stale = inbox().handle(caller, true, review(other.job)).getJSONObject("artifactReview").getString("ticket")
        inbox().refreshEngineeringEvidence(other.job, status(other.job).getLong("revision"), true)
        assertThrows(Wire.Rejected::class.java) { inbox().consumeArtifactReview(stale, true) }
        assertTrue(inbox().pendingInstallations().isEmpty())
    }
    @Test fun expirationClockRollbackAndSupersedingRequestNeverRenewATicket() {
        val source = artifactSource(); assertTrue(inbox().recordArtifact(source, apk(source)))
        var clock = 100_000L
        val queue = DevelopmentJobInbox(directory.root) { clock }
        val first = review(source.job)
        val ticket = queue.handle(caller, true, first).getJSONObject("artifactReview").getString("ticket")
        clock = 99_999L
        assertThrows(Wire.Rejected::class.java) { queue.consumeArtifactReview(ticket, true) }
        clock = 220_000L
        assertThrows(Wire.Rejected::class.java) { queue.handle(caller, true, first) }
        assertThrows(Wire.Rejected::class.java) { queue.consumeArtifactReview(ticket, true) }
        val next = queue.handle(caller, true, review(source.job)).getJSONObject("artifactReview").getString("ticket")
        assertNotEquals(ticket, next)
        assertThrows(Wire.Rejected::class.java) { queue.handle(caller, true, first) }
        assertTrue(queue.consumeArtifactReview(next, true).getJSONObject("artifactReview").getBoolean("consumed"))
    }
    @Test fun commercialReceiverCannotOpenAnOwnerDevelopmentReview() {
        val source = artifactSource("owner_development"); assertTrue(inbox().recordArtifact(source, apk(source)))
        val ticket = inbox().handle(caller, true, review(source.job, "owner_development")).getJSONObject("artifactReview").getString("ticket")
        assertEquals("POLICY_REQUIRED", assertThrows(Wire.Rejected::class.java) { inbox().consumeArtifactReview(ticket, false) }.code)
        assertThrows(Wire.Rejected::class.java) { inbox().consumeArtifactReview("not-a-ticket", true) }
        assertTrue(inbox().consumeArtifactReview(ticket, true).getJSONObject("artifactReview").getBoolean("consumed"))
    }
    @get:Rule val directory = TemporaryFolder()
    private val caller = "com.assistant.core"
    private val base = "a".repeat(40)
    private val committed = "d".repeat(40)
    private val path = "developer/src/main/java/com/jarvis/developer/ConversationTone.kt"
    private val text = "package com.jarvis.developer\nobject ConversationTone { val enabled = false }\n"
    private fun inbox() = DevelopmentJobInbox(directory.root)
    private fun status(job: String) = inbox().handle(caller, true, Wire.Request(UUID.randomUUID().toString(), "task.status.request", jobId = job))
    private fun control(job: String, kind: String) = inbox().handle(caller, true, Wire.Request(UUID.randomUUID().toString(), kind,
        jobId = job, expectedRevision = status(job).getLong("revision")))
    private fun analyzed(mode: String = "supervised"): String {
        val job = inbox().handle(caller, true, Wire.Request(UUID.randomUUID().toString(), "source.developer.analysis.request", "Improve conversation tone", mode)).getString("id")
        val bytes = text.toByteArray()
        val sha = MessageDigest.getInstance("SHA-1").digest("blob ${bytes.size}\u0000".toByteArray() + bytes).joinToString("") { "%02x".format(it.toInt() and 255) }
        val inventory = RepositoryAnalysis.Inventory(listOf(RepositoryAnalysis.SourceFile(path, sha, bytes.size.toLong())), false, base)
        val plan = RepositoryAnalysis.analyze("Improve conversation tone", inventory) { text }
        check(inbox().finishAnalysis(inbox().claimAnalysis(job, true)!!, plan))
        return job
    }
    private fun stage(job: String, personal: Boolean = true) = inbox().stageEngineering(job, status(job).getLong("revision"), listOf(path), personal)
    private fun approved(draft: DevelopmentEngineering.Draft): PatchEngine.ApprovedProposal {
        val bytes = text.toByteArray()
        val sha = MessageDigest.getInstance("SHA-1").digest("blob ${bytes.size}\u0000".toByteArray() + bytes).joinToString("") { "%02x".format(it.toInt() and 255) }
        val request = PatchEngine.Request(draft.taskId, draft.task, draft.base, draft.paths,
            listOf(PatchEngine.SourceFile(path, sha, text)), "OpenAI", "gpt-4.1-mini", draft.branch, draft.target)
        val proposal = PatchEngine.parse(request, JSONObject().put("summary", "Improve conversation tone")
            .put("edits", JSONArray().put(JSONObject().put("path", path).put("content", text.replace("false", "true")))).toString())
        return PatchEngine.approve(proposal, proposal.id)
    }
    private fun result(write: DevelopmentEngineering.Write) = write.receipt.let { r -> PatchPublisher.CommitResult(r.taskId,
        r.provider, r.model, r.proposalId, r.edits.map { it.path }.sorted(), r.base, committed, r.branch, "", r.baseBranch, r.target) }
    private fun report(write: DevelopmentEngineering.Write, summary: Boolean = true, state: String = "passed", attempt: Int = 1): BuildDiagnostics.Report {
        val counts = JSONObject().put("tests", 1).put("failures", if (state == "failure") 1 else 0).put("errors", 0).put("skipped", if (state == "skipped") 1 else 0)
        val privateTests = write.receipt.target == PatchEngine.Target.PERSONAL
        val suite = if (privateTests) "PersonalJUnitEvidence" else "com.jarvis.developer.ConversationToneTest"
        val record = JSONObject().put("sourceCommit", committed).put("totals", counts)
            .put("suites", JSONArray().put(JSONObject(counts.toString()).put("name", suite).put("sha256", "c".repeat(64))))
            .put("cases", JSONArray().put(JSONObject().put("suite", suite).put("name", if (privateTests) "b".repeat(64) else "conversation").put("result", state)))
        return BuildDiagnostics.parse(committed, write.receipt.branch, 42, attempt, "completed", if (state == "failure") "failure" else "success",
            if (summary) "JARVIS_JUNIT_SUMMARY=$record" else "BUILD SUCCESSFUL", write.receipt.target)
    }
    private fun artifactSource(mode: String = "supervised"): DevelopmentArtifact.Source {
        val job = analyzed(mode); val draft = stage(job); val write = inbox().claimEngineering(draft, approved(draft), true)
        assertTrue(inbox().finishEngineering(write, result(write)))
        assertFalse(inbox().recordEngineeringBuild(inbox().claimEngineeringRead(job, true)!!, report(write)))
        return inbox().beginArtifact(job, status(job).getLong("revision"), true)
    }
    private fun apk(source: DevelopmentArtifact.Source) = DevelopmentArtifact.Record(source.commit, 42, 1, 99,
        "a".repeat(64), "b".repeat(64), 100, "com.jarvis.developer", 17, "0.1.17", "c".repeat(64), "production")
    private fun legacyPayload(): JSONArray = JSONArray(JSONObject(PrivateCheckpointFile(directory.root,
        "development-inbox.bin", 32, 1_048_576).read()!!.toString(Charsets.UTF_8)).getString("payload"))
    private fun assertLegacyStages() {
        val rows = legacyPayload()
        for (i in 0 until rows.length()) for (stage in listOf("SIGNED_APK", "DEVICE_INSTALLED"))
            assertTrue("Previous Developer versions require compatible checkpoint stages", rows.getJSONObject(i)
                .getJSONObject("verification").getString(stage) in setOf("PENDING", "BLOCKED"))
    }
    @Test fun apkAndInstallerEvidenceRemainReadableByThePreviousCheckpointContract() {
        val source = artifactSource(); assertTrue(inbox().recordArtifact(source, apk(source)))
        assertLegacyStages()
        assertEquals("PASSED", status(source.job).getJSONObject("verification").getString("SIGNED_APK"))
        val request = inbox().beginInstallation(source.job, status(source.job).getLong("revision"), apk(source).apkSha256, true)
        val bound = inbox().bindInstallation(request, 17); assertTrue(inbox().submitInstallation(bound))
        assertLegacyStages()
        assertEquals("RUNNING", status(source.job).getJSONObject("verification").getString("DEVICE_INSTALLED"))
        assertTrue(inbox().installationStatus(bound, "FAILED", 7)); assertLegacyStages()
        assertEquals("FAILED", status(source.job).getJSONObject("verification").getString("DEVICE_INSTALLED"))
    }
    @Test fun anOlderBuildUpdatingItsOwnCiEvidenceCannotTurnAStaleApkIntoInstallationApproval() {
        val source = artifactSource(); assertTrue(inbox().recordArtifact(source, apk(source)))
        val payload = legacyPayload()
        // Older Developer retains unknown extension fields while updating its known build fields.
        payload.getJSONObject(0).getJSONObject("engineering").getJSONObject("build").put("fingerprint", "f".repeat(64))
        val raw = payload.toString()
        val digest = MessageDigest.getInstance("SHA-256").digest(raw.toByteArray()).joinToString("") { "%02x".format(it.toInt() and 255) }
        PrivateCheckpointFile(directory.root, "development-inbox.bin", 32, 1_048_576).write(JSONObject().put("version", 1)
            .put("digest", digest).put("payload", raw).toString().toByteArray())
        val snapshot = status(source.job)
        assertEquals("BLOCKED", snapshot.getJSONObject("verification").getString("SIGNED_APK"))
        assertTrue(snapshot.getJSONObject("artifact").getBoolean("stale"))
        assertThrows(IllegalArgumentException::class.java) {
            inbox().beginInstallation(source.job, snapshot.getLong("revision"), apk(source).apkSha256, true)
        }
        assertLegacyStages()
    }
    @Test fun verifiedArtifactIsBoundToTheJobButCannotRestoreAnInstallationApproval() {
        val source = artifactSource(); assertTrue(inbox().recordArtifact(source, apk(source)))
        val state = status(source.job)
        assertEquals("PASSED", state.getJSONObject("verification").getString("SIGNED_APK"))
        assertEquals("PENDING", state.getJSONObject("verification").getString("DEVICE_INSTALLED"))
        assertFalse(state.getBoolean("deviceVerified")); assertTrue(inbox().pendingInstallations().isEmpty())
        assertFalse(state.getJSONObject("artifact").has("signerSha256"))
        assertEquals(apk(source), inbox().artifactRecord(source.job))
        val install = inbox().beginInstallation(source.job, state.getLong("revision"), apk(source).apkSha256, true)
        val bound = inbox().bindInstallation(install, 17); assertTrue(inbox().submitInstallation(bound))
        assertEquals(listOf(bound), inbox().pendingInstallations())
        assertThrows(IllegalArgumentException::class.java) { inbox().beginInstallation(source.job, status(source.job).getLong("revision"), apk(source).apkSha256, true) }
        assertTrue(inbox().installationStatus(bound, "PACKAGE_PRESENT", 0))
        assertEquals("BLOCKED", status(source.job).getJSONObject("verification").getString("DEVICE_INSTALLED"))
        assertFalse(status(source.job).getBoolean("deviceVerified"))
        assertTrue(inbox().pendingInstallations().isEmpty())
        assertFalse(inbox().installationStatus(bound, "FAILED", 1))
    }
    @Test fun cancelledArtifactReadAndInstallationCannotAdmitLateSuccess() {
        val source = artifactSource(); control(source.job, "task.cancel.request")
        assertFalse(inbox().recordArtifact(source, apk(source)))
        val other = artifactSource(); assertTrue(inbox().recordArtifact(other, apk(other)))
        val install = inbox().beginInstallation(other.job, status(other.job).getLong("revision"), apk(other).apkSha256, true)
        val bound = inbox().bindInstallation(install, 18); assertTrue(inbox().submitInstallation(bound))
        control(other.job, "task.cancel.request")
        assertFalse(inbox().installationStatus(bound, "PACKAGE_PRESENT", 0))
        assertEquals("CANCELLED", status(other.job).getJSONObject("artifact").getString("installation"))
        assertEquals("BLOCKED", status(other.job).getJSONObject("verification").getString("DEVICE_INSTALLED"))
    }
    @Test fun changedBuildEvidenceAndWrongCallbackIdentityCannotReuseAnOldArtifactDecision() {
        val source = artifactSource(); assertTrue(inbox().recordArtifact(source, apk(source)))
        val install = inbox().beginInstallation(source.job, status(source.job).getLong("revision"), apk(source).apkSha256, true)
        val bound = inbox().bindInstallation(install, 18)
        assertFalse(inbox().installationStatus(bound.copy(nonce = UUID.randomUUID().toString()), "PACKAGE_PRESENT", 0))
        assertFalse(inbox().installationStatus(bound.copy(session = 19), "PACKAGE_PRESENT", 0))
        assertFalse(inbox().installationStatus(install, "PACKAGE_PRESENT", 0))
        assertThrows(IllegalArgumentException::class.java) { inbox().refreshEngineeringEvidence(source.job, status(source.job).getLong("revision"), true) }
        assertTrue(inbox().installationStatus(bound, "CANCELLED"))
        inbox().refreshEngineeringEvidence(source.job, status(source.job).getLong("revision"), true)
        assertNull(inbox().artifactRecord(source.job)); assertFalse(inbox().recordArtifact(source, apk(source)))
        assertEquals("PENDING", status(source.job).getJSONObject("verification").getString("SIGNED_APK"))
        assertFalse(inbox().installationStatus(bound, "PACKAGE_PRESENT", 0))
    }
    @Test fun ownerDevelopmentArtifactOperationsRemainRejectedForCommercialPolicyAndUngatedJobs() {
        val job = analyzed()
        assertThrows(IllegalArgumentException::class.java) { inbox().beginArtifact(job, status(job).getLong("revision"), true) }
        val source = artifactSource("owner_development")
        assertEquals("POLICY_REQUIRED", assertThrows(Wire.Rejected::class.java) {
            inbox().beginArtifact(source.job, status(source.job).getLong("revision"), false)
        }.code)
        assertTrue(inbox().recordArtifact(source, apk(source)))
        assertEquals("POLICY_REQUIRED", assertThrows(Wire.Rejected::class.java) {
            inbox().beginInstallation(source.job, status(source.job).getLong("revision"), apk(source).apkSha256, false)
        }.code)
        for (name in listOf("DevelopmentArtifactDownload", "DevelopmentArtifactInspection", "DevelopmentInstallReceiver", "JarvisPackageInstaller"))
            assertFalse(PatchEngine.allowedPath("developer/src/main/java/com/jarvis/developer/$name.kt"))
    }
    private fun claim(job: String): DevelopmentEngineering.Write {
        val draft = stage(job)
        return inbox().claimEngineering(draft, approved(draft), true)
    }
    @Test fun stagingCannotGrantApprovalAndRestagingInvalidatesTheOldExactDraft() {
        val job = analyzed(); val first = stage(job); val next = stage(job)
        assertEquals("AWAITING_APPROVAL", status(job).getString("state"))
        assertThrows(IllegalArgumentException::class.java) { inbox().claimEngineering(first, approved(first), true) }
        val write = inbox().claimEngineering(next, approved(next), true)
        assertThrows(IllegalArgumentException::class.java) { inbox().claimEngineering(next, approved(next), true) }
        assertEquals("COMMITTING", status(job).getString("state")); assertFalse(status(job).getBoolean("sourceVerified"))
        assertEquals("RUNNING", status(job).getJSONObject("verification").getString("SOURCE_COMMITTED"))
        assertTrue(inbox().finishEngineering(write, result(write)))
        assertTrue(status(job).getBoolean("sourceVerified"))
    }
    @Test fun theOwnerCanSpecifyAChangeWithoutReusingTheReadOnlyAnalysisInstructionsAsTheTask() {
        val job = analyzed()
        val draft = inbox().stageEngineering(job, status(job).getLong("revision"), listOf(path), true, "Improve tone for brief conversations")
        assertEquals("Improve tone for brief conversations", draft.task)
        assertEquals(draft.task, inbox().engineeringGoal(job))
        assertEquals(draft.paths, inbox().engineeringPaths(job))
        assertNotNull(inbox().claimEngineering(draft, approved(draft), true))
    }
    @Test fun aCommitAndCompleteTestsAdvanceOnlyTheirOwnVerificationStagesAcrossRestart() {
        val job = analyzed(); val write = claim(job)
        inbox().finishEngineering(write, result(write))
        val lease = inbox().claimEngineeringRead(job, true)!!
        assertFalse(inbox().recordEngineeringBuild(lease, report(write)))
        val state = status(job)
        assertEquals("READY_FOR_REVIEW", state.getString("state"))
        val gates = state.getJSONObject("verification")
        listOf("SOURCE_COMMITTED", "BUILD_SUCCESSFUL", "TESTS_PASSED").forEach { assertEquals("PASSED", gates.getString(it)) }
        listOf("SIGNED_APK", "DEVICE_INSTALLED").forEach { assertEquals("PENDING", gates.getString(it)) }
        assertFalse(state.getBoolean("deviceVerified"))
        assertEquals("https://github.com/muddigger782012/J.A.R.V.I.S.-/commit/$committed", inbox().engineeringReviewUrl(job))
        assertTrue(inbox().engineeringDiagnostics(job)!!.contains("JUnit: 1 tests"))
        assertTrue(inbox().finishEngineering(write, result(write)))
        assertEquals("READY_FOR_REVIEW", status(job).getString("state"))
        assertTrue(inbox().recoverableEngineeringJobs().isEmpty())
    }
    @Test fun personalJobsCanPassPrivateTestEvidenceWithoutPassingSigningOrInstallation() {
        val personalPath = "app/src/main/java/com/assistant/core/services/ConversationTone.kt"
        val source = "package com.assistant.core.services\nobject ConversationTone { val enabled = false }\n"
        val bytes = source.toByteArray()
        val sha = MessageDigest.getInstance("SHA-1").digest("blob ${bytes.size}\u0000".toByteArray() + bytes)
            .joinToString("") { "%02x".format(it.toInt() and 255) }
        val request = Wire.Request(UUID.randomUUID().toString(), "source.personal.analysis.request", "Improve conversation tone")
        val job = inbox().handle(caller, true, request).getString("id")
        val inventory = RepositoryAnalysis.Inventory(listOf(RepositoryAnalysis.SourceFile(personalPath, sha, bytes.size.toLong())), false, base)
        inbox().finishAnalysis(inbox().claimAnalysis(job, true)!!, RepositoryAnalysis.analyze(request.body, inventory) { source })
        val draft = inbox().stageEngineering(job, 2, listOf(personalPath), true)
        val patch = PatchEngine.parse(PatchEngine.Request(draft.taskId, draft.task, draft.base, draft.paths,
            listOf(PatchEngine.SourceFile(personalPath, sha, source)), "OpenAI", "gpt-4.1-mini", "main", PatchEngine.Target.PERSONAL),
            JSONObject().put("summary", "Improve conversation tone").put("edits", JSONArray().put(JSONObject()
                .put("path", personalPath).put("content", source.replace("false", "true")))).toString())
        val write = inbox().claimEngineering(draft, PatchEngine.approve(patch, patch.id), true)
        inbox().finishEngineering(write, result(write))
        inbox().recordEngineeringBuild(inbox().claimEngineeringRead(job, true)!!, report(write))
        val state = status(job)
        assertEquals("READY_FOR_REVIEW", state.getString("state"))
        assertEquals("PASSED", state.getJSONObject("verification").getString("TESTS_PASSED"))
        assertEquals("PENDING", state.getJSONObject("verification").getString("SIGNED_APK"))
        assertFalse(state.getBoolean("deviceVerified"))
    }
    @Test fun greenBuildWithoutCompleteTestsOrWithSkippedTestsCannotCompleteTheTestGate() {
        for ((summary, testState) in listOf(false to "passed", true to "skipped", true to "failure")) {
            val job = analyzed(); val write = claim(job); inbox().finishEngineering(write, result(write))
            inbox().recordEngineeringBuild(inbox().claimEngineeringRead(job, true)!!, report(write, summary, testState))
            assertEquals("REVIEW_REQUIRED", status(job).getString("state"))
            assertNotEquals("PASSED", status(job).getJSONObject("verification").getString("TESTS_PASSED"))
        }
    }
    @Test fun cancelDuringCommitPreservesTheTruthWithoutResumingFutureStepsOrOldApprovals() {
        val job = analyzed(); val write = claim(job)
        control(job, "task.cancel.request")
        assertTrue(inbox().finishEngineering(write, result(write)))
        assertEquals("CANCELLED", status(job).getString("state")); assertTrue(status(job).getBoolean("sourceVerified"))
        assertTrue(inbox().recoverableEngineeringJobs().isEmpty())
        assertEquals("VERIFYING", control(job, "task.retry.request").getString("state"))
        assertTrue(status(job).getBoolean("sourceVerified"))
        assertTrue(inbox().recoverableAnalysisJobs().isEmpty())
    }
    @Test fun interruptedWritesRecoverByReadingOnlyAndNeverInvokeAWriterAgain() {
        val job = analyzed(); val write = claim(job); inbox().uncertainEngineering(write)
        assertThrows(Wire.Rejected::class.java) { control(job, "task.retry.request") }
        var reads = 0
        assertFalse(DevelopmentEngineeringRead.run(inbox(), job, true, PatchPreviewCancellation(),
            inspect = { reads++; result(write) }, feedback = { _, _, _ -> report(write) }))
        assertEquals(1, reads); assertEquals("READY_FOR_REVIEW", status(job).getString("state"))
    }
    @Test fun anAbsentBranchIsNotRollbackEvidenceOrPermissionToRetryAWritingAttempt() {
        val job = analyzed(); claim(job)
        assertNull(inbox().engineeringReviewUrl(job))
        assertFalse(DevelopmentEngineeringRead.run(inbox(), job, true, PatchPreviewCancellation(), inspect = { null },
            feedback = { _, _, _ -> error("Must not read a build for an unverified commit") }))
        assertEquals("REVIEW_REQUIRED", status(job).getString("state")); assertFalse(status(job).getBoolean("sourceVerified"))
        assertThrows(Wire.Rejected::class.java) { control(job, "task.retry.request") }
    }
    @Test fun cancellationAndReadLeasesRejectLateBuildsAndAnEditionChangeBlocksOwnerJobs() {
        val job = analyzed(); val write = claim(job); inbox().finishEngineering(write, result(write))
        val first = inbox().claimEngineeringRead(job, true)!!; val second = inbox().claimEngineeringRead(job, true)!!
        assertFalse(inbox().recordEngineeringBuild(first, report(write)))
        control(job, "task.pause.request")
        assertFalse(inbox().recordEngineeringBuild(second, report(write)))
        assertEquals("PAUSED", status(job).getString("state"))
        val owner = analyzed("owner_development")
        assertThrows(Wire.Rejected::class.java) { stage(owner, false) }
    }
    @Test fun transportFailuresHaveABoundedPersistentReadBudgetAndNeverEraseTheCommittedSource() {
        val job = analyzed(); val write = claim(job); inbox().finishEngineering(write, result(write))
        repeat(20) {
            assertTrue(DevelopmentEngineeringRead.run(inbox(), job, true, PatchPreviewCancellation(),
                feedback = { _, _, _ -> throw IllegalStateException("fixture private failure") }))
        }
        assertFalse(DevelopmentEngineeringRead.run(inbox(), job, true, PatchPreviewCancellation()))
        assertEquals("REVIEW_REQUIRED", status(job).getString("state")); assertTrue(status(job).getBoolean("sourceVerified"))
        assertEquals("BLOCKED", status(job).getJSONObject("verification").getString("BUILD_SUCCESSFUL"))
        assertFalse(directory.root.resolve("development-inbox.bin").readText().contains("fixture private failure"))
        assertEquals("VERIFYING", control(job, "task.retry.request").getString("state"))
    }
    @Test fun approvedMetadataCannotMoveToAnotherJobOrBroaderScopeAndNoSourceOrCredentialsAreSerialized() {
        val first = analyzed(); val a = stage(first); val second = analyzed(); val b = stage(second)
        assertThrows(IllegalArgumentException::class.java) { inbox().claimEngineering(b, approved(a), true) }
        val write = inbox().claimEngineering(a, approved(a), true)
        assertThrows(IllegalArgumentException::class.java) { inbox().finishEngineering(write, result(write).copy(branch = "main")) }
        val envelope = JSONObject(directory.root.resolve("development-inbox.bin").readText())
        val rows = JSONArray(envelope.getString("payload"))
        val stored = (0 until rows.length()).map { rows.getJSONObject(it) }.single { it.getString("id") == first }
            .getJSONObject("engineering").toString()
        assertFalse(stored.contains("val enabled")); assertFalse(stored.contains("gpt-key")); assertFalse(stored.contains("Bearer"))
        assertFalse(status(first).getJSONObject("analysis").has("paths"))
    }
}
