package com.jarvis.developer

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class CandidateEvaluationTest {
    private val baselineSha = "a".repeat(40)
    private val candidateSha = "b".repeat(40)
    private val controllerSha = "c".repeat(40)
    private val branch = "feature/jarvis-developer-task/candidate-" + "d".repeat(12)
    private val cert = "e".repeat(64)
    private val expected get() = CandidateEvaluation.Expected(candidateSha, branch, controllerSha, 42, 2)

    private fun artifact(source: String = baselineSha, version: Int = 100, apk: String = "1".repeat(64)): JSONObject =
        JSONObject().put("sourceCommitSha", source).put("packageName", "com.jarvis.developer")
            .put("versionCode", version).put("apkSha256", apk).put("apkBytes", 2048).put("signerSha256", cert)
    private fun manifest(): JSONObject = JSONObject().put("schemaVersion", 1).put("status", "READY")
        .put("candidate", JSONObject().put("commitSha", candidateSha).put("branch", branch)
            .put("controllerCommitSha", controllerSha).put("controllerBranch", "feature/jarvis-developer")
            .put("runId", 42).put("runAttempt", 2).put("packageName", "com.jarvis.developer")
            .put("versionCode", 101).put("apkSha256", "2".repeat(64)).put("apkBytes", 2300).put("signerSha256", cert))
        .put("tests", JSONObject().put("passed", true).put("tests", 2).put("failures", 0).put("errors", 0)
            .put("skipped", 0).put("lintErrors", 0).put("lintWarnings", 185))
        .put("source", JSONObject().put("baseCommitSha", controllerSha)
            .put("changedPaths", JSONArray().put("developer/src/main/java/com/jarvis/developer/TaskFileSummary.kt"))
            .put("scopeVerified", true).put("controlsUnchanged", true).put("branchIsolated", true))
        .put("recovery", JSONObject().put("baseline", artifact()).put("backup", artifact().put("verified", true))
            .put("rebuild", artifact(version = 102, apk = "3".repeat(64)).put("verified", true))
            .put("missingReasons", JSONArray()))

    private fun report(source: String = candidateSha, sourceBranch: String = branch,
        states: List<String> = listOf("passed", "passed"), names: List<String> = states.indices.map { "case$it" },
        conclusion: String = "success", complete: Boolean = true): BuildDiagnostics.Report {
        val counts = JSONObject().put("tests", states.size).put("failures", states.count { it == "failure" })
            .put("errors", states.count { it == "error" }).put("skipped", states.count { it == "skipped" })
        val suite = "com.jarvis.developer.TaskFileSummaryTest"
        val cases = JSONArray()
        states.forEachIndexed { i, state -> cases.put(JSONObject().put("suite", suite).put("name", names[i]).put("result", state)) }
        val summary = JSONObject().put("sourceCommit", source).put("totals", counts)
            .put("suites", JSONArray().put(JSONObject(counts.toString()).put("name", suite).put("sha256", "4".repeat(64))))
            .put("cases", cases)
        return BuildDiagnostics.parse(source, sourceBranch, 77, 1, "completed", conclusion,
            if (complete) "JARVIS_JUNIT_SUMMARY=$summary" else "")
    }
    private fun baseline() = report(controllerSha, "feature/jarvis-developer")
    private fun evidence(json: JSONObject = manifest(), identity: CandidateEvaluation.Expected = expected) =
        CandidateEvaluation.parseEvidence(json.toString(), identity)
    private fun review(json: JSONObject = manifest(), candidate: BuildDiagnostics.Report = report(), baseline: BuildDiagnostics.Report = baseline()) =
        CandidateEvaluation.assess(baseline, candidate, evidence(json), 1_000)
    private fun blocked(json: JSONObject, substring: String) {
        val result = review(json)
        assertFalse(result.eligible)
        assertTrue(result.blockers.joinToString().contains(substring))
        rejected { CandidateEvaluation.confirm(result, result.fingerprint, true, 1_001) }
    }
    private fun rejected(block: () -> Unit) {
        try { block(); fail("Expected candidate gate to reject") }
        catch (_: IllegalArgumentException) { }
        catch (_: IllegalStateException) { }
    }

    @Test fun completeModeledEvidenceCreatesOnlyOneMetadataPromotionRequest() {
        val result = review()
        assertTrue(result.blockers.joinToString(), result.eligible)
        val request = CandidateEvaluation.confirm(result, result.fingerprint, true, 1_001)
        assertEquals(candidateSha, request.candidate.commitSha)
        assertEquals(baselineSha, request.recovery.sourceCommitSha)
        assertEquals(result.fingerprint, request.reviewFingerprint)
        assertTrue(CandidateEvaluation.audit(request).contains("METADATA ONLY"))
        assertTrue(CandidateEvaluation.render(result).contains("Device installation"))
        rejected { CandidateEvaluation.confirm(result, result.fingerprint, true, 1_002) }
    }

    @Test fun missingPreviousBackupAlwaysBlocksEvenWhenControllerClaimsReady() {
        blocked(manifest().apply { getJSONObject("recovery").put("backup", JSONObject.NULL) }, "backup is missing")
    }

    @Test fun missingBaselineAndMissingRecoveryRebuildCannotBeDeferred() {
        blocked(manifest().apply { getJSONObject("recovery").put("baseline", JSONObject.NULL) }, "baseline source and artifact are missing")
        blocked(manifest().apply { getJSONObject("recovery").put("rebuild", JSONObject.NULL) }, "recovery rebuild is missing")
    }

    @Test fun substitutedBackupBytesSourcePackageVersionOrSignerBlocks() {
        val mutations: List<Pair<String, Any>> = listOf("apkSha256" to "9".repeat(64), "apkBytes" to 2049,
            "sourceCommitSha" to "9".repeat(40), "packageName" to "com.jarvis.beta", "versionCode" to 99,
            "signerSha256" to "9".repeat(64), "verified" to false)
        mutations.forEach { (field, value) ->
            blocked(manifest().apply { getJSONObject("recovery").getJSONObject("backup").put(field, value) }, "backup bytes, source")
        }
    }

    @Test fun recoveryMustBeActualRebuildOfKnownGoodSourceSignerAndHigherVersion() {
        val mutations: List<Pair<String, Any>> = listOf("sourceCommitSha" to candidateSha, "packageName" to "com.jarvis.beta",
            "signerSha256" to "9".repeat(64), "versionCode" to 101, "verified" to false,
            "apkSha256" to "1".repeat(64), "apkSha256" to "2".repeat(64))
        mutations.forEach { (field, value) ->
            blocked(manifest().apply { getJSONObject("recovery").getJSONObject("rebuild").put(field, value) }, "Recovery rebuild must")
        }
    }

    @Test fun candidateMustRetainKnownGoodSignerPackageAndAdvanceVersion() {
        blocked(manifest().apply { getJSONObject("candidate").put("signerSha256", "9".repeat(64)) }, "Candidate signer")
        blocked(manifest().apply { getJSONObject("candidate").put("versionCode", 100) }, "Candidate version")
        blocked(manifest().apply { getJSONObject("candidate").put("packageName", "com.jarvis.beta") }, "Candidate package")
        blocked(manifest().apply { getJSONObject("candidate").put("apkSha256", "1".repeat(64)) }, "cannot reuse")
    }

    @Test fun expectedSourceBranchControllerRunAndAttemptCannotBeSubstituted() {
        val fields = listOf("commitSha" to "9".repeat(40), "branch" to "feature/jarvis-developer",
            "controllerCommitSha" to "9".repeat(40), "controllerBranch" to "main", "runId" to 43, "runAttempt" to 1)
        fields.forEach { (field, value) -> rejected { evidence(manifest().apply { getJSONObject("candidate").put(field, value) }) } }
        rejected { evidence(identity = expected.copy(controllerCommitSha = "9".repeat(40))) }
        rejected { evidence(identity = expected.copy(candidateBranch = "main")) }
    }

    @Test fun exactSourceCIAndBaselineArtifactCommitAreRequired() {
        assertFalse(review(candidate = report(source = "9".repeat(40))).eligible)
        assertFalse(review(candidate = report(sourceBranch = "feature/jarvis-developer")).eligible)
        assertFalse(review(baseline = report("9".repeat(40), "feature/jarvis-developer")).eligible)
    }

    @Test fun baseBranchBootstrapCanBeParsedButCannotRequestPromotion() {
        val json = manifest().apply {
            getJSONObject("candidate").put("branch", "feature/jarvis-developer").put("commitSha", controllerSha)
            getJSONObject("source").put("branchIsolated", false).put("changedPaths", JSONArray())
        }
        val expectedBase = expected.copy(candidateCommitSha = controllerSha, candidateBranch = "feature/jarvis-developer")
        val review = CandidateEvaluation.assess(baseline(), report(controllerSha, "feature/jarvis-developer"), evidence(json, expectedBase), 1_000)
        assertFalse(review.eligible)
        assertTrue(review.blockers.joinToString().contains("isolated task branch"))
    }

    @Test fun immutableControllerBaselineAndBoundedSafeScopeAreRequired() {
        blocked(manifest().apply { getJSONObject("source").put("baseCommitSha", baselineSha) }, "immutable controller")
        listOf("scopeVerified", "controlsUnchanged", "branchIsolated").forEach { field ->
            assertFalse(review(manifest().apply { getJSONObject("source").put(field, false) }).eligible)
        }
        val paths = listOf(JSONArray(), JSONArray().put("developer/src/main/java/com/jarvis/developer/PatchEngine.kt"),
            JSONArray().put("app/src/main/java/com/assistant/core/MainActivity.kt"),
            JSONArray().put("developer/src/main/java/com/jarvis/developer/TaskFileSummary.kt")
                .put("developer/src/main/java/com/jarvis/developer/TaskFileSummary.kt"),
            JSONArray((1..9).map { "developer/src/main/java/com/jarvis/developer/Safe$it.kt" }))
        paths.forEach { changed -> blocked(manifest().apply { getJSONObject("source").put("changedPaths", changed) }, "eight safe Developer files") }
    }

    @Test fun everyBaselineCaseMustPassAndNoNewCasesMayFailOrSkip() {
        val lost = report(states = listOf("passed", "passed"), names = listOf("case0", "replacement"))
        assertFalse(review(candidate = lost).eligible)
        listOf("failure", "error", "skipped").forEach { state ->
            assertFalse(review(candidate = report(states = listOf("passed", state))).eligible)
            assertFalse(review(candidate = report(states = listOf("passed", "passed", state))).eligible)
        }
    }

    @Test fun incompleteOrFailedBaselineAndCandidateReportsCannotPass() {
        assertFalse(review(candidate = report(complete = false)).eligible)
        assertFalse(review(baseline = report(baselineSha, "feature/jarvis-developer", complete = false)).eligible)
        assertFalse(review(candidate = report(conclusion = "failure")).eligible)
        assertFalse(review(baseline = report(baselineSha, "feature/jarvis-developer", conclusion = "failure")).eligible)
    }

    @Test fun signedCandidateMustHaveMatchingTestCountsAndZeroLintErrors() {
        blocked(manifest().apply { getJSONObject("tests").put("passed", false) }, "test and lint")
        blocked(manifest().apply { getJSONObject("tests").put("lintErrors", 1) }, "test and lint")
        blocked(manifest().apply { getJSONObject("tests").put("tests", 3) }, "counts do not match")
        blocked(manifest().apply { getJSONObject("tests").put("skipped", 1) }, "test and lint")
    }

    @Test fun blockedControllerAndAnyMissingReasonAlwaysBlock() {
        blocked(manifest().put("status", "BLOCKED"), "controller reports")
        blocked(manifest().apply { getJSONObject("recovery").put("missingReasons", JSONArray().put("BACKUP_MISSING")) }, "BACKUP_MISSING")
    }

    @Test fun confirmationRequiresExactFingerprintExplicitApprovalAndFreshClock() {
        var result = review()
        rejected { CandidateEvaluation.confirm(result, "9".repeat(64), true, 1_001) }
        rejected { CandidateEvaluation.confirm(result, result.fingerprint, true, 1_002) }
        result = review()
        rejected { CandidateEvaluation.confirm(result, result.fingerprint, false, 1_001) }
        result = review()
        rejected { CandidateEvaluation.confirm(result, result.fingerprint, true, 999) }
        result = review()
        rejected { CandidateEvaluation.confirm(result, result.fingerprint, true, result.deadlineAtMillis) }
        result = review()
        assertNotNull(CandidateEvaluation.confirm(result, result.fingerprint, true, result.deadlineAtMillis - 1))
    }

    @Test fun fingerprintsBindReportsArtifactFactsControllerAndReviewTime() {
        val original = review()
        assertNotEquals(original.fingerprint, review(manifest().apply { getJSONObject("candidate").put("apkBytes", 2301) }).fingerprint)
        val manifest2 = manifest().apply { getJSONObject("candidate").put("controllerCommitSha", "9".repeat(40)) }
        val changed = CandidateEvaluation.parseEvidence(manifest2.toString(), expected.copy(controllerCommitSha = "9".repeat(40)))
        assertNotEquals(original.fingerprint, CandidateEvaluation.assess(baseline(), report(), changed, 1_000).fingerprint)
        assertNotEquals(original.fingerprint, CandidateEvaluation.assess(baseline(), report(), evidence(), 1_001).fingerprint)
        assertEquals(original.fingerprint, review().fingerprint)
    }

    @Test fun strictJsonRejectsDuplicateEscapedKeysUnknownFieldsFloatsCoercionAndTrailingInput() {
        val json = manifest().toString()
        val invalid = listOf("{", json + "{}", json.replaceFirst("\"schemaVersion\":1", "\"schemaVersion\":1.0"),
            json.replaceFirst("\"schemaVersion\":1", "\"schemaVersion\":\"1\""),
            json.replaceFirst("\"schemaVersion\":1", "\"schemaVersion\":1,\"schema\\u0056ersion\":1"),
            json.dropLast(1) + ",\"unknown\":0}",
            json.replaceFirst("\"passed\":true", "\"passed\":\"true\""),
            json.replaceFirst("\"apkBytes\":2300", "\"apkBytes\":02300"))
        invalid.forEach { text -> rejected { CandidateEvaluation.parseEvidence(text, expected) } }
    }

    @Test fun strictArtifactAndCountBoundsRejectOverflowZeroHashesAndUnsupportedSchema() {
        val invalid = listOf(manifest().put("schemaVersion", 2), manifest().put("status", "deferred"),
            manifest().apply { getJSONObject("candidate").put("apkBytes", 0) },
            manifest().apply { getJSONObject("candidate").put("versionCode", Long.MAX_VALUE) },
            manifest().apply { getJSONObject("candidate").put("apkSha256", "short") },
            manifest().apply { getJSONObject("candidate").put("signerSha256", "E".repeat(64)) },
            manifest().apply { getJSONObject("tests").put("errors", 3) },
            manifest().apply { getJSONObject("recovery").put("missingReasons", JSONArray().put("DEFER_RECOVERY")) },
            manifest().apply { getJSONObject("recovery").put("missingReasons", JSONArray().put("BACKUP_MISSING").put("BACKUP_MISSING")) })
        invalid.forEach { rejected { evidence(it) } }
        rejected { CandidateEvaluation.assess(baseline(), report(), evidence(), Long.MAX_VALUE) }
    }

    @Test fun recognizedSecretsNeverEnterEvidenceOrExceptionMessages() {
        val secret = "github_pat_" + "A".repeat(40)
        val fields = listOf("branch", "packageName", "commitSha", "signerSha256")
        fields.forEach { field ->
            try { evidence(manifest().apply { getJSONObject("candidate").put(field, secret) }); fail("Secret accepted") }
            catch (e: IllegalArgumentException) { assertFalse(e.message.orEmpty().contains(secret)) }
        }
        val bad = manifest().apply { getJSONObject("candidate").put("packageName", "com.jarvis.\uD800") }
        rejected { evidence(bad) }
    }

    @Test fun evidenceAndReviewCollectionsAreImmutable() {
        val evidence = evidence()
        try { (evidence.missingReasons as MutableList<String>).add("BACKUP_MISSING"); fail("Mutable evidence") }
        catch (_: UnsupportedOperationException) { }
        val result = review(manifest().put("status", "BLOCKED"))
        try { (result.blockers as MutableList<String>).clear(); fail("Mutable review") }
        catch (_: UnsupportedOperationException) { }
    }
}
