package com.jarvis.developer

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.net.URLDecoder
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.nio.file.SimpleFileVisitor
import java.nio.file.attribute.BasicFileAttributes
import java.security.MessageDigest
import java.util.Base64

/**
 * Actual immutable source -> production analysis/self-proposal/diff gates. All remote commit,
 * package/signing, workflow, release and recovery records below are explicitly MODELED; neither
 * the proposal nor a release is applied. Actual package verification runs in the separate CI job.
 */
class SelfDevelopmentAcceptanceTest {
    @Test fun scopedSelfProposalNeedsFreshReviewAndVerifiedRecoveryBeforeMetadataOnlyPromotion() {
        val root = Paths.get(requireNotNull(System.getProperty("jarvis.repositoryRoot"))).toRealPath()
        val checkoutSha = System.getProperty("jarvis.repositoryCommit").orEmpty()
        assertTrue("Execution needs the full immutable checkout SHA", Regex("[a-f0-9]{40}").matches(checkoutSha))
        val before = snapshot(root)
        val sourceBytes = listOf(SOURCE, TEST).associateWith { path ->
            val file = root.resolve(path)
            assertTrue("Analysis uses regular immutable checkout files", Files.isRegularFile(file) && !Files.isSymbolicLink(file))
            Files.readAllBytes(file)
        }
        try {
            val reader = CheckoutFixture(root, checkoutSha, sourceBytes)
            val (inventory, analysis) = RepositoryIntelligence.Reader(reader).analyzeSnapshot("", TASK)
            assertEquals(checkoutSha, inventory.commitSha)
            assertEquals(setOf(SOURCE, TEST), analysis.files.map { it.path }.toSet())
            assertEquals(checkoutSha, analysis.commitSha)
            analysis.files.forEach { file ->
                assertEquals(gitBlob(sourceBytes.getValue(file.path)), file.source.sha)
                assertTrue(file.citations.isNotEmpty())
                assertTrue(file.citations.all { it.path == file.path && it.commitSha == checkoutSha && it.blobSha == file.source.sha })
            }
            assertTrue(analysis.files.single { it.path == TEST }.dependencies.any { SOURCE in it.resolvedPaths && it.status == "resolved" })
            val self = SelfDevelopment.propose("phase5-summary-clarity", analysis, listOf(SOURCE))
            SelfDevelopment.validate(self)
            assertEquals(checkoutSha, self.sourceCommitSha)
            assertEquals(BASE_BRANCH, self.sourceBranch)
            assertEquals(listOf(SOURCE), self.scope)
            assertEquals(analysis.analysisId, self.analysisId)
            assertEquals(listOf(gitBlob(sourceBytes.getValue(SOURCE))), self.suggestions.map { it.blobSha })
            assertFalse("Self proposals contain metadata, never full source", SelfDevelopment.render(self).contains(sourceBytes.getValue(SOURCE).toString(Charsets.UTF_8)))
            expectFailure { SelfDevelopment.propose("phase5-control-edit", analysis, listOf("developer/src/main/java/com/jarvis/developer/PatchEngine.kt")) }
            expectFailure { SelfDevelopment.propose("phase5-uninspected", analysis, listOf("developer/src/main/java/com/jarvis/developer/Uninspected.kt")) }
            expectFailure { SelfDevelopment.propose("phase5-baseline-tests", analysis, listOf(TEST)) }

            val beforeSource = sourceBytes.getValue(SOURCE).toString(Charsets.UTF_8)
            val afterSource = clarifiedSummary(beforeSource)
            assertNotEquals(beforeSource, afterSource)
            val request = PatchEngine.Request(self.id, self.task, self.sourceCommitSha, self.scope,
                listOf(PatchEngine.SourceFile(SOURCE, gitBlob(sourceBytes.getValue(SOURCE)), beforeSource)),
                "Fixture", "deterministic-self-development-v1", self.sourceBranch)
            val proposal = PatchEngine.parse(request, response(afterSource))
            assertEquals(listOf(SOURCE), proposal.edits.map { it.path })
            assertTrue("Full reviewed diff must identify the actual source", proposal.diff.contains("diff --git a/$SOURCE b/$SOURCE"))
            assertTrue(proposal.diff.contains("sortedDistinctPaths"))
            expectFailure { PatchEngine.approve(proposal, self.fingerprint) }
            val changed = PatchEngine.parse(request, response(afterSource + "\n// A different in-memory proposal needs a different approval.\n"))
            assertNotEquals(proposal.id, changed.id)
            expectFailure { PatchEngine.approve(changed, proposal.id) }
            expectFailure { PatchEngine.parse(request.copy(sources = request.sources.map { it.copy(sha = "0".repeat(40)) }), response(afterSource)) }
            val approved = PatchEngine.approve(proposal, proposal.id)
            PatchEngine.validateApproval(approved)
            val commit = modeledCommit(approved.proposal)
            assertEquals(checkoutSha, commit.baseCommitSha)
            assertEquals(BASE_BRANCH, commit.baseBranch)
            assertEquals(proposal.id, commit.proposalId)
            assertEquals(PatchEngine.branchName(proposal), commit.branch)
            assertTrue(commit.branch.startsWith("feature/jarvis-developer-task/"))
            assertNotEquals(BASE_BRANCH, commit.branch)

            // The baseline and candidate CI envelopes are modeled, rather than assertions that
            // the in-memory proposed source was compiled or committed remotely.
            val baseline = report(checkoutSha, BASE_BRANCH, 51001L, CASES)
            val candidate = report(commit.commitSha, commit.branch, 51002L, CASES)
            val packageBytes = "MODELED candidate APK bytes; not an Android package".toByteArray(Charsets.UTF_8)
            val previousBytes = "MODELED previous APK bytes; not an Android package".toByteArray(Charsets.UTF_8)
            val backupBytes = previousBytes.copyOf()
            assertTrue("Fixed modeled backup bytes must actually match their fixture baseline", previousBytes.contentEquals(backupBytes))
            assertEquals(digest("SHA-256", previousBytes), digest("SHA-256", backupBytes))
            val rebuiltBytes = "MODELED rebuilt APK bytes; not an Android package".toByteArray(Charsets.UTF_8)
            val modeledEvidence = readyEvidence(checkoutSha, commit, packageBytes, previousBytes, backupBytes, rebuiltBytes)
            val feedbackReads = mutableListOf<CandidateFixture>()
            fun readEvidence(json: JSONObject): CandidateEvaluation.Evidence {
                val fixture = CandidateFixture(checkoutSha, json)
                feedbackReads += fixture
                val value = CandidateFeedback.Client(fixture).read("", commit.branch, commit.commitSha, EVALUATION_RUN, checkoutSha)
                assertEquals(fixture.requests.size, fixture.closedResponses)
                return value
            }
            var now = 1_810_000_000_000L
            fun tick(): Long { now += 1; return now }
            val missing = JSONObject(modeledEvidence.toString()).put("status", "BLOCKED")
            missing.getJSONObject("recovery").put("backup", JSONObject.NULL)
                .put("missingReasons", JSONArray().put("BACKUP_MISSING"))
            val blocked = CandidateEvaluation.assess(baseline, candidate, readEvidence(missing), tick())
            assertFalse("An absent previous-release backup must prevent promotion review", blocked.eligible)
            expectFailure { CandidateEvaluation.confirm(blocked, blocked.fingerprint, true, tick()) }

            val ready = CandidateEvaluation.assess(baseline, candidate, readEvidence(modeledEvidence), tick())
            assertTrue("Complete matching MODELED evidence exercises the production eligibility gate: ${ready.blockers}", ready.eligible)
            val staleReview = CandidateEvaluation.assess(baseline, candidate, readEvidence(modeledEvidence), tick())
            expectFailure { CandidateEvaluation.confirm(staleReview, "0".repeat(64), true, tick()) }
            expectFailure { CandidateEvaluation.confirm(staleReview, staleReview.fingerprint, true, tick()) }
            val unconfirmedReview = CandidateEvaluation.assess(baseline, candidate, readEvidence(modeledEvidence), tick())
            expectFailure { CandidateEvaluation.confirm(unconfirmedReview, unconfirmedReview.fingerprint, false, tick()) }
            val promotion = CandidateEvaluation.confirm(ready, ready.fingerprint, true, tick())
            expectFailure { CandidateEvaluation.confirm(ready, ready.fingerprint, true, tick()) }
            val audit = CandidateEvaluation.audit(promotion)
            assertFalse("Metadata promotion must carry no full source/diff", audit.contains(proposal.diff) || audit.contains(beforeSource) || audit.contains(afterSource))
            assertTrue("Review identifies the immutable candidate", audit.contains(commit.commitSha))

            val rejected = mutableListOf("missing-previous-backup", "stale-diff-approval", "changed-preimage", "uninspected-source", "protected-control-source", "existing-baseline-test-edit", "reused-promotion-review")
            fun blockedVariant(name: String, changedEvidence: JSONObject) {
                val review = CandidateEvaluation.assess(baseline, candidate, readEvidence(changedEvidence), tick())
                assertFalse("$name must block promotion", review.eligible)
                expectFailure { CandidateEvaluation.confirm(review, review.fingerprint, true, tick()) }
                rejected += name
            }
            blockedVariant("substituted-backup-hash", JSONObject(modeledEvidence.toString()).apply {
                getJSONObject("recovery").getJSONObject("backup").put("apkSha256", "0".repeat(64))
            })
            blockedVariant("substituted-backup-source", JSONObject(modeledEvidence.toString()).apply {
                getJSONObject("recovery").getJSONObject("backup").put("sourceCommitSha", "0".repeat(40))
            })
            blockedVariant("substituted-backup-signer", JSONObject(modeledEvidence.toString()).apply {
                getJSONObject("recovery").getJSONObject("backup").put("signerSha256", "0".repeat(64))
            })
            blockedVariant("candidate-signer-changed", JSONObject(modeledEvidence.toString()).apply {
                getJSONObject("candidate").put("signerSha256", "0".repeat(64))
            })
            blockedVariant("missing-backup-byte-verification", JSONObject(modeledEvidence.toString()).apply {
                getJSONObject("recovery").getJSONObject("backup").put("verified", false)
            })
            blockedVariant("candidate-version-not-higher", JSONObject(modeledEvidence.toString()).apply {
                getJSONObject("candidate").put("versionCode", 7)
            })
            blockedVariant("changed-controls", JSONObject(modeledEvidence.toString()).apply {
                getJSONObject("source").put("controlsUnchanged", false)
            })
            blockedVariant("existing-baseline-test-changed", JSONObject(modeledEvidence.toString()).apply {
                // New test files may be added. The trusted controller compares baseline
                // blobs and rejects edits to this existing regression test before it
                // marks the source scope verified.
                getJSONObject("source").put("changedPaths", JSONArray().put(TEST)).put("scopeVerified", false)
            })
            val lostBaseline = report(commit.commitSha, commit.branch, 51002L, CASES.dropLast(1))
            val missingCaseReview = CandidateEvaluation.assess(baseline, lostBaseline, readEvidence(modeledEvidence), tick())
            assertFalse(missingCaseReview.eligible)
            expectFailure { CandidateEvaluation.confirm(missingCaseReview, missingCaseReview.fingerprint, true, tick()) }
            rejected += "lost-baseline-case"
            val wrongSha = JSONObject(modeledEvidence.toString()).apply { getJSONObject("candidate").put("commitSha", "0".repeat(40)) }
            expectFailure { readEvidence(wrongSha) }
            rejected += "candidate-evidence-wrong-source"
            val wrongReport = report("0".repeat(40), commit.branch, 51002L, CASES)
            val wrongSourceReview = CandidateEvaluation.assess(baseline, wrongReport, readEvidence(modeledEvidence), tick())
            assertFalse(wrongSourceReview.eligible)
            rejected += "candidate-ci-wrong-source"
            assertEquals("Read-only analysis must close every response", reader.requests.size, reader.closedResponses)
            assertEquals("All modeled candidate responses close on success and rejection", feedbackReads.sumOf { it.requests.size }, feedbackReads.sumOf { it.closedResponses })
            assertEquals(before, snapshot(root))
            emitEvidence(root, checkoutSha, sourceBytes, analysis, self, proposal, commit, baseline, candidate,
                modeledEvidence, ready, audit, rejected, reader, feedbackReads, before.size,
                packageBytes, previousBytes, backupBytes, rebuiltBytes)
        } finally {
            assertEquals("Analysis, self-proposal and promotion review must leave every analyzed path/byte unchanged", before, snapshot(root))
        }
    }

    private fun clarifiedSummary(source: String): String {
        val line = "val files = paths.distinct().sorted()"
        require(source.contains(line)) { "Acceptance must be reviewed if the real summary implementation changes" }
        return source.replace("A deterministic review summary; it never reads or changes source files.",
            "A deterministic review summary of case-sensitive paths; extension classification ignores case. It never reads or changes source files.")
            .replace("val files =", "val sortedDistinctPaths =").replace("files.forEach", "sortedDistinctPaths.forEach")
            .replace("\${files.size}", "\${sortedDistinctPaths.size}").replace("files.size ==", "sortedDistinctPaths.size ==")
            .replace(" + files)", " + sortedDistinctPaths)")
    }

    private fun response(source: String): String = JSONObject().put("summary", "Clarify the sorted distinct path variable and case rules without changing summary behavior; retain all existing tests unchanged.")
        .put("edits", JSONArray().put(JSONObject().put("path", SOURCE).put("content", source))).toString()

    private fun modeledCommit(proposal: PatchEngine.Proposal): PatchPublisher.CommitResult = PatchPublisher.CommitResult(
        proposal.request.taskId, proposal.request.provider, proposal.request.model, proposal.id,
        proposal.edits.map { it.path }.sorted(), proposal.request.baseCommitSha,
        digest("SHA-1", ("MODELED sibling commit\n" + proposal.id).toByteArray(Charsets.UTF_8)),
        PatchEngine.branchName(proposal), proposal.diff, proposal.request.baseBranch)

    private fun report(commit: String, branch: String, run: Long, names: List<String>): BuildDiagnostics.Report {
        fun counts() = JSONObject().put("tests", names.size).put("failures", 0).put("errors", 0).put("skipped", 0)
        val summary = JSONObject().put("sourceCommit", commit).put("totals", counts())
            .put("suites", JSONArray().put(counts().put("name", SUITE)
                .put("sha256", digest("SHA-256", ("MODELED JUnit $commit" + names.joinToString()).toByteArray(Charsets.UTF_8)))))
            .put("cases", JSONArray(names.map { JSONObject().put("suite", SUITE).put("name", it).put("result", "passed") }))
        return BuildDiagnostics.parse(commit, branch, run, 1, "completed", "success", "MODELED CI/JUnit envelope; proposed source was not executed.\nJARVIS_JUNIT_SUMMARY=$summary")
    }

    private fun readyEvidence(controllerSha: String, commit: PatchPublisher.CommitResult, candidate: ByteArray,
        previous: ByteArray, backup: ByteArray, rebuilt: ByteArray): JSONObject {
        fun artifact(source: String, version: Int, bytes: ByteArray) = JSONObject().put("sourceCommitSha", source)
            .put("packageName", "com.jarvis.developer").put("versionCode", version).put("apkSha256", digest("SHA-256", bytes))
            .put("apkBytes", bytes.size).put("signerSha256", SIGNER)
        val baseline = artifact(controllerSha, 7, previous)
        return JSONObject().put("schemaVersion", 1).put("status", "READY")
            .put("candidate", JSONObject().put("commitSha", commit.commitSha).put("branch", commit.branch)
                .put("controllerCommitSha", controllerSha).put("controllerBranch", BASE_BRANCH)
                .put("runId", EVALUATION_RUN).put("runAttempt", 1).put("packageName", "com.jarvis.developer")
                .put("versionCode", 10).put("apkSha256", digest("SHA-256", candidate)).put("apkBytes", candidate.size).put("signerSha256", SIGNER))
            .put("source", JSONObject().put("baseCommitSha", controllerSha).put("changedPaths", JSONArray().put(SOURCE))
                .put("scopeVerified", true).put("controlsUnchanged", true).put("branchIsolated", true))
            .put("tests", JSONObject().put("passed", true).put("tests", CASES.size).put("failures", 0).put("errors", 0)
                .put("skipped", 0).put("lintErrors", 0).put("lintWarnings", 185))
            .put("recovery", JSONObject().put("baseline", baseline).put("backup", artifact(controllerSha, 7, backup).put("verified", true))
                .put("rebuild", artifact(controllerSha, 11, rebuilt).put("verified", true)).put("missingReasons", JSONArray()))
    }

    private fun emitEvidence(root: Path, checkoutSha: String, bytes: Map<String, ByteArray>,
        analysis: RepositoryAnalysis.AnalysisPlan, self: SelfDevelopment.Proposal, proposal: PatchEngine.Proposal,
        commit: PatchPublisher.CommitResult, baseline: BuildDiagnostics.Report, candidate: BuildDiagnostics.Report,
        modeledEvidence: JSONObject, review: CandidateEvaluation.Review, promotionAudit: String, rejected: List<String>,
        reader: CheckoutFixture, feedback: List<CandidateFixture>, sourcePathCount: Int,
        candidateBytes: ByteArray, previousBytes: ByteArray, backupBytes: ByteArray, rebuiltBytes: ByteArray) {
        val json = JSONObject().put("schema", "jarvis-phase5-self-development-acceptance-v1")
            .put("actualExecutionCheckoutSha", checkoutSha).put("taskId", self.id).put("analysisId", analysis.analysisId)
            .put("sourceBlobs", JSONArray(bytes.map { (path, data) -> JSONObject().put("path", path).put("gitBlobSha", gitBlob(data)).put("bytes", data.size) }))
            .put("selfProposalFingerprint", self.fingerprint).put("reviewedPatchId", proposal.id).put("scope", JSONArray(self.scope))
            .put("provider", "Fixture").put("model", "deterministic-self-development-v1")
            .put("modeledSiblingCommit", JSONObject().put("baseCommitSha", commit.baseCommitSha).put("baseBranch", commit.baseBranch)
                .put("commitSha", commit.commitSha).put("branch", commit.branch).put("proposalId", commit.proposalId))
            .put("modeledBaselineReportFingerprint", baseline.fingerprint).put("modeledCandidateReportFingerprint", candidate.fingerprint)
            .put("modeledSignedCandidateAndRecovery", modeledEvidence).put("modeledPromotionReviewFingerprint", review.fingerprint)
            .put("metadataOnlyPromotionAudit", promotionAudit).put("rejections", JSONArray(rejected.distinct()))
            .put("checks", JSONObject().put("actualSourcePreimagesAndGitBlobsVerified", true).put("fullDiffExactlyApproved", true)
                .put("existingBaselineTestFileUnchanged", true).put("analyzedSourcePathCount", sourcePathCount)
                .put("analyzedRepositoryPathsAndBytesUnchanged", true).put("modeledMissingPreviousBackupBlocksPromotion", true)
                .put("modeledCandidateEligibleOnlyAfterCompleteMatchingRecovery", true).put("promotionReviewReused", false)
                .put("modeledAnalysisGetCount", reader.requests.size).put("closedAnalysisResponses", reader.closedResponses)
                .put("modeledCandidateGetCount", feedback.sumOf { it.requests.size }).put("closedCandidateResponses", feedback.sumOf { it.closedResponses }))
            .put("actualFixtureByteHashes", JSONArray(listOf("candidate" to candidateBytes, "previous" to previousBytes, "backup" to backupBytes, "rebuild" to rebuiltBytes)
                .map { (kind, data) -> JSONObject().put("fixture", kind).put("sha256", digest("SHA-256", data)).put("bytes", data.size) }))
            .put("evidenceBoundaries", JSONArray(listOf(
                "The immutable actual Actions checkout SHA, source bytes, Git blob SHA-1 preimages, production read-only analysis, self-proposal metadata and complete diff validation are actual.",
                "Only TaskFileSummary.kt is proposed for editing. Its original regression test file is immutable analysis context. Replacement bytes stay in memory and are not applied or compiled by this test.",
                "Provider responses, sibling Git commit, remote CI identities/conclusions/JUnit envelopes, Android package/signature/version evidence and recovery release records are MODELED fixtures; no live provider, GitHub write or network request occurs.",
                "Actual SHA-256 calculations compare fixed fixture byte arrays. Those arrays are not Android APKs and no real package signing or recovery rebuild is claimed here.",
                "Production CandidateFeedback validates modeled exact controller/run/job provenance and closes responses; CandidateEvaluation enforces missing backup, source/hash/signer/version/regression and fresh metadata-only review gates.",
                "The real previous-release backup and recovery verification remain unresolved; the modeled positive path cannot establish full Phase 5 operational acceptance or authorize release promotion.",
                "The separate trusted CI candidate pipeline performs real package/tool verification and emits its own evidence. This test performs no signing, publication, installation, workflow dispatch or device checks."
            )))
        val encoded = (json.toString(2) + "\n").toByteArray(Charsets.UTF_8)
        assertTrue("Acceptance evidence must fit the bounded CI record", encoded.size <= 65_536)
        val directory = root.resolve("developer/build/reports/phase5-acceptance")
        Files.createDirectories(directory)
        Files.write(directory.resolve("self-development-evidence.json"), encoded)
    }

    /** Only exact modeled GET endpoints wrap actual, independently hashed checkout file bytes. */
    private class CheckoutFixture(private val root: Path, private val commit: String,
        private val sourceBytes: Map<String, ByteArray>) : RepositoryIntelligence.GetTransport {
        private val tree = gitBlob(("MODELED scoped tree\n" + sourceBytes.entries.joinToString("\n") { (path, bytes) -> "$path:${gitBlob(bytes)}" }).toByteArray(Charsets.UTF_8))
        val requests = mutableListOf<String>()
        var closedResponses = 0
        override fun get(url: String, token: String): RepositoryIntelligence.GetResponse {
            assertEquals("", token)
            requests += url
            val json = when (url) {
                "$API/git/ref/heads/feature%2Fjarvis-developer" -> JSONObject().put("ref", "refs/heads/$BASE_BRANCH").put("object", JSONObject().put("type", "commit").put("sha", commit))
                "$API/git/commits/$commit" -> JSONObject().put("sha", commit).put("tree", JSONObject().put("sha", tree))
                "$API/git/trees/$tree?recursive=1" -> JSONObject().put("sha", tree).put("truncated", false).put("tree", JSONArray(sourceBytes.map { (path, bytes) ->
                    JSONObject().put("path", path).put("type", "blob").put("mode", "100644").put("sha", gitBlob(bytes)).put("size", bytes.size)
                }))
                else -> {
                    assertTrue(url.startsWith("$API/contents/") && url.endsWith("?ref=$commit"))
                    val path = url.removePrefix("$API/contents/").substringBefore('?').split('/').joinToString("/") { URLDecoder.decode(it, "UTF-8") }
                    assertTrue(path in sourceBytes && RepositoryIntelligence.allowedSource(path))
                    val file = root.resolve(path).normalize()
                    assertTrue(file.startsWith(root) && Files.isRegularFile(file) && !Files.isSymbolicLink(file))
                    val bytes = Files.readAllBytes(file)
                    assertTrue(bytes.contentEquals(sourceBytes.getValue(path)))
                    JSONObject().put("path", path).put("type", "file").put("size", bytes.size).put("sha", gitBlob(bytes))
                        .put("encoding", "base64").put("content", Base64.getEncoder().encodeToString(bytes))
                }
            }
            val bytes = json.toString().toByteArray(Charsets.UTF_8)
            return RepositoryIntelligence.GetResponse(200, bytes.size.toLong(), url, ByteArrayInputStream(bytes)) { closedResponses++ }
        }
    }

    private class CandidateFixture(private val controller: String, private val evidence: JSONObject) : CandidateFeedback.Transport {
        val requests = mutableListOf<String>()
        var closedResponses = 0
        override fun get(url: String, token: String?, timeoutMillis: Long): CandidateFeedback.Response {
            assertEquals("", token)
            assertTrue(timeoutMillis in 1..CandidateFeedback.MAX_MILLIS)
            requests += url
            val json = when (url) {
                "$API/actions/runs/$EVALUATION_RUN" -> JSONObject().put("id", EVALUATION_RUN).put("run_attempt", 1)
                    .put("head_sha", controller).put("head_branch", BASE_BRANCH).put("path", ".github/workflows/evaluate-jarvis-developer.yml")
                    .put("event", "workflow_dispatch").put("repository", JSONObject().put("full_name", REPOSITORY))
                    .put("head_repository", JSONObject().put("full_name", REPOSITORY)).put("html_url", "https://github.com/$REPOSITORY/actions/runs/$EVALUATION_RUN")
                    .put("status", "completed").put("conclusion", "success").toString()
                "$API/actions/runs/$EVALUATION_RUN/attempts/1/jobs?per_page=10" -> JSONObject().put("total_count", 1)
                    .put("jobs", JSONArray().put(JSONObject().put("id", EVALUATION_JOB).put("run_id", EVALUATION_RUN)
                        .put("run_attempt", 1).put("head_sha", controller).put("run_url", "$API/actions/runs/$EVALUATION_RUN")
                        .put("name", "Record candidate evaluation").put("status", "completed").put("conclusion", "success"))).toString()
                "$API/actions/jobs/$EVALUATION_JOB/logs" -> "MODELED trusted evaluator output; no actual APK verification occurred in this fixture.\nJARVIS_CANDIDATE_EVIDENCE=$evidence\n"
                else -> throw AssertionError("Candidate feedback escaped fixed modeled GET endpoints")
            }
            val bytes = json.toByteArray(Charsets.UTF_8)
            return CandidateFeedback.Response(200, bytes.size.toLong(), url, ByteArrayInputStream(bytes), null) { closedResponses++ }
        }
    }

    private fun snapshot(root: Path): Map<String, String> {
        val files = sortedMapOf<String, String>()
        Files.walkFileTree(root, object : SimpleFileVisitor<Path>() {
            override fun preVisitDirectory(directory: Path, attributes: BasicFileAttributes): FileVisitResult =
                if (directory != root && directory.fileName.toString() in setOf(".git", ".gradle", ".kotlin", "build", "out", "node_modules"))
                    FileVisitResult.SKIP_SUBTREE else FileVisitResult.CONTINUE
            override fun visitFile(file: Path, attributes: BasicFileAttributes): FileVisitResult {
                if (file.fileName.toString() != ".git") {
                    val path = root.relativize(file).toString().replace('\\', '/')
                    files[path] = if (Files.isSymbolicLink(file)) "link:" + Files.readSymbolicLink(file).toString()
                        else "bytes:" + digest("SHA-256", Files.readAllBytes(file))
                }
                return FileVisitResult.CONTINUE
            }
        })
        return files
    }

    private fun expectFailure(block: () -> Unit) {
        try { block() } catch (_: IllegalArgumentException) { return } catch (_: IllegalStateException) { return }
        throw AssertionError("Expected the production gate to reject this operation")
    }

    companion object {
        private const val REPOSITORY = "muddigger782012/J.A.R.V.I.S.-"
        private const val API = "https://api.github.com/repos/$REPOSITORY"
        private const val BASE_BRANCH = "feature/jarvis-developer"
        private const val SOURCE = "developer/src/main/java/com/jarvis/developer/TaskFileSummary.kt"
        private const val TEST = "developer/src/test/java/com/jarvis/developer/TaskFileSummaryTest.kt"
        private const val TASK = "Clarify TaskFileSummary sorted distinct case-sensitive path naming and extension classification while preserving all existing TaskFileSummaryTest duplicate, empty, mixed-case and Gradle behavior."
        private const val SUITE = "com.jarvis.developer.TaskFileSummaryTest"
        private const val SIGNER = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
        private const val EVALUATION_RUN = 52001L
        private const val EVALUATION_JOB = 152001L
        private val CASES = listOf("summarizesDistinctPathsInStableOrder", "summarizesEmptyAndSingleFileScopes",
            "countsGradleScriptsBeforeKotlinAndHandlesMixedCase", "classifiesTheFilenameInsteadOfParentDirectory")
        private fun gitBlob(bytes: ByteArray): String {
            val digest = MessageDigest.getInstance("SHA-1")
            digest.update("blob ${bytes.size}\u0000".toByteArray(Charsets.UTF_8))
            return digest.digest(bytes).joinToString("") { "%02x".format(it.toInt() and 255) }
        }
        private fun digest(algorithm: String, bytes: ByteArray): String = MessageDigest.getInstance(algorithm).digest(bytes)
            .joinToString("") { "%02x".format(it.toInt() and 255) }
    }
}
