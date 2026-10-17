package com.jarvis.developer

import java.security.MessageDigest
import java.util.Collections

/** Pure release-candidate policy. The result is a review/request, never a publication capability. */
object CandidateEvaluation {
    const val CONTROLLER_BRANCH = "feature/jarvis-developer"
    const val PACKAGE_NAME = "com.jarvis.developer"
    const val REVIEW_DURATION_MILLIS = 5 * 60 * 1_000L
    private val sha = Regex("[a-f0-9]{40}")
    private val hash = Regex("[a-f0-9]{64}")
    private val packageName = Regex("[A-Za-z][A-Za-z0-9_]*(?:\\.[A-Za-z][A-Za-z0-9_]*)+")
    private val reasonCodes = setOf("BASELINE_MISSING", "BASELINE_UNVERIFIED", "BACKUP_MISSING", "BACKUP_UNVERIFIED",
        "RECOVERY_REBUILD_MISSING", "RECOVERY_REBUILD_UNVERIFIED", "CANDIDATE_UNVERIFIED", "TESTS_INCOMPLETE")

    data class Expected(val candidateCommitSha: String, val candidateBranch: String,
        val controllerCommitSha: String, val runId: Long, val runAttempt: Int)
    data class CandidateFacts(val commitSha: String, val branch: String, val controllerCommitSha: String,
        val controllerBranch: String, val runId: Long, val runAttempt: Int, val packageName: String,
        val versionCode: Long, val apkSha256: String, val apkBytes: Long, val signerSha256: String)
    data class ArtifactFacts(val sourceCommitSha: String, val packageName: String, val versionCode: Long,
        val apkSha256: String, val apkBytes: Long, val signerSha256: String)
    data class VerifiedArtifact(val facts: ArtifactFacts, val verified: Boolean)
    data class TestFacts(val passed: Boolean, val tests: Int, val failures: Int, val errors: Int,
        val skipped: Int, val lintErrors: Int, val lintWarnings: Int)
    class SourceFacts internal constructor(val baseCommitSha: String, changedPaths: List<String>,
        val scopeVerified: Boolean, val controlsUnchanged: Boolean, val branchIsolated: Boolean) {
        val changedPaths: List<String> = Collections.unmodifiableList(ArrayList(changedPaths))
    }

    class Evidence private constructor(val status: String, val candidate: CandidateFacts, val tests: TestFacts, val source: SourceFacts,
        val baseline: ArtifactFacts?, val backup: VerifiedArtifact?, val rebuild: VerifiedArtifact?,
        val missingReasons: List<String>, val fingerprint: String) {
        companion object {
            // Trusted module factory. Only the fixed-workflow reader establishes the observation's origin.
            internal fun create(status: String, candidate: CandidateFacts, tests: TestFacts, source: SourceFacts, baseline: ArtifactFacts?,
                backup: VerifiedArtifact?, rebuild: VerifiedArtifact?, reasons: List<String>): Evidence {
                val frozen = Collections.unmodifiableList(ArrayList(reasons))
                val fields = listOf("jarvis-candidate-evidence-v1", status, candidate.toString(), tests.toString(),
                    baseline.toString(), backup.toString(), rebuild.toString(), source.baseCommitSha,
                    source.scopeVerified.toString(), source.controlsUnchanged.toString(), source.branchIsolated.toString(),
                    source.changedPaths.size.toString()) + source.changedPaths + frozen.sorted()
                return Evidence(status, candidate, tests, source, baseline, backup, rebuild, frozen, digest(fields))
            }
        }
    }

    class Review private constructor(val evidence: Evidence, val eligible: Boolean, val blockers: List<String>,
        val fingerprint: String, val evaluatedAtMillis: Long, val deadlineAtMillis: Long,
        val baselineReportFingerprint: String, val candidateReportFingerprint: String) {
        private var consumed = false
        @Synchronized internal fun claim(exactFingerprint: String, explicitlyApproved: Boolean, nowMillis: Long) {
            check(!consumed) { "Candidate review was already confirmed; evaluate again" }
            consumed = true
            require(eligible && blockers.isEmpty()) { "Candidate promotion is blocked" }
            require(nowMillis >= evaluatedAtMillis && nowMillis < deadlineAtMillis) { "Candidate review expired or its clock changed" }
            require(explicitlyApproved && exactFingerprint == fingerprint) { "Fresh explicit confirmation must match the complete review fingerprint" }
        }
        companion object {
            internal fun create(evidence: Evidence, blockers: List<String>, now: Long, baseline: String, candidate: String): Review {
                val frozen = Collections.unmodifiableList(ArrayList(blockers))
                val deadline = now + REVIEW_DURATION_MILLIS
                val fingerprint = digest(listOf("jarvis-candidate-review-v1", evidence.fingerprint, baseline, candidate,
                    now.toString(), deadline.toString()) + frozen)
                return Review(evidence, frozen.isEmpty(), frozen, fingerprint, now, deadline, baseline, candidate)
            }
        }
    }

    /** Metadata for a separately authorized future promotion. It exposes no executable approval/write object. */
    class PromotionRequest private constructor(val reviewFingerprint: String, val evidenceFingerprint: String,
        val candidate: CandidateFacts, val source: SourceFacts, val baseline: ArtifactFacts, val recovery: ArtifactFacts,
        val baselineReportFingerprint: String, val candidateReportFingerprint: String, val confirmedAtMillis: Long) {
        companion object {
            internal fun create(review: Review, now: Long) = PromotionRequest(review.fingerprint, review.evidence.fingerprint,
                review.evidence.candidate, review.evidence.source, requireNotNull(review.evidence.baseline), requireNotNull(review.evidence.rebuild).facts,
                review.baselineReportFingerprint, review.candidateReportFingerprint, now)
        }
    }

    /** Input is untrusted JSON. Identity must also match the independently retrieved controller run. */
    fun parseEvidence(json: String, expected: Expected): Evidence = try {
        require(json.length in 1..65_536 && json.toByteArray(Charsets.UTF_8).size <= 65_536)
        require(sha.matches(expected.candidateCommitSha) && sha.matches(expected.controllerCommitSha) &&
            BuildDiagnostics.validBranch(expected.candidateBranch) && expected.runId > 0 && expected.runAttempt in 1..100)
        val root = StrictJson(json).parse().obj()
        keys(root, "schemaVersion", "status", "candidate", "tests", "source", "recovery")
        require(root["schemaVersion"] == 1L)
        val status = root.text("status").also { require(it == "READY" || it == "BLOCKED") }
        val c = root["candidate"].obj()
        keys(c, "commitSha", "branch", "controllerCommitSha", "controllerBranch", "runId", "runAttempt",
            "packageName", "versionCode", "apkSha256", "apkBytes", "signerSha256")
        val candidate = CandidateFacts(c.text("commitSha"), c.text("branch"), c.text("controllerCommitSha"),
            c.text("controllerBranch"), c.number("runId", 1, Long.MAX_VALUE), c.number("runAttempt", 1, 100).toInt(),
            c.text("packageName"), c.number("versionCode", 1, Int.MAX_VALUE.toLong()), c.text("apkSha256"),
            c.number("apkBytes", 1, 536_870_912), c.text("signerSha256"))
        require(candidate.commitSha == expected.candidateCommitSha && candidate.branch == expected.candidateBranch &&
            candidate.controllerCommitSha == expected.controllerCommitSha && candidate.controllerBranch == CONTROLLER_BRANCH &&
            candidate.runId == expected.runId && candidate.runAttempt == expected.runAttempt)
        validateArtifact(ArtifactFacts(candidate.commitSha, candidate.packageName, candidate.versionCode,
            candidate.apkSha256, candidate.apkBytes, candidate.signerSha256))
        val t = root["tests"].obj()
        keys(t, "passed", "tests", "failures", "errors", "skipped", "lintErrors", "lintWarnings")
        val tests = TestFacts(t.boolean("passed"), t.count("tests"), t.count("failures"), t.count("errors"),
            t.count("skipped"), t.count("lintErrors"), t.count("lintWarnings"))
        require(tests.failures.toLong() + tests.errors + tests.skipped <= tests.tests)
        val s = root["source"].obj()
        keys(s, "baseCommitSha", "changedPaths", "scopeVerified", "controlsUnchanged", "branchIsolated")
        val sourceBase = s.text("baseCommitSha").also { require(sha.matches(it)) }
        val paths = (s["changedPaths"] as? List<*>)?.map { item ->
            (item as? String)?.also { safeText(it) } ?: error("Invalid changed path")
        } ?: error("Invalid changed paths")
        require(paths.size <= 64)
        val source = SourceFacts(sourceBase, paths, s.boolean("scopeVerified"), s.boolean("controlsUnchanged"), s.boolean("branchIsolated"))
        val r = root["recovery"].obj()
        keys(r, "baseline", "backup", "rebuild", "missingReasons")
        val baseline = r["baseline"]?.let { artifact(it.obj(), false).first }
        val backup = r["backup"]?.let { val parsed = artifact(it.obj(), true); VerifiedArtifact(parsed.first, parsed.second) }
        val rebuild = r["rebuild"]?.let { val parsed = artifact(it.obj(), true); VerifiedArtifact(parsed.first, parsed.second) }
        val reasons = (r["missingReasons"] as? List<*>)?.map { it as? String ?: error("Invalid reason") }
            ?: error("Invalid reasons")
        require(reasons.size <= reasonCodes.size && reasons.distinct().size == reasons.size && reasons.all { it in reasonCodes })
        Evidence.create(status, candidate, tests, source, baseline, backup, rebuild, reasons)
    } catch (_: Exception) { throw IllegalArgumentException("Candidate evidence is malformed, unsupported, or does not match the requested source and controller run") }

    fun assess(baseline: BuildDiagnostics.Report, candidate: BuildDiagnostics.Report, evidence: Evidence, nowMillis: Long): Review {
        require(nowMillis >= 0 && nowMillis <= Long.MAX_VALUE - REVIEW_DURATION_MILLIS) { "Invalid candidate review time" }
        BuildDiagnostics.validateReport(baseline)
        BuildDiagnostics.validateReport(candidate)
        val blockers = linkedSetOf<String>()
        if (!passed(baseline)) blockers.add("Baseline CI must contain complete, nonempty passing test evidence")
        if (!passed(candidate)) blockers.add("Candidate CI must contain complete, nonempty passing test evidence")
        val c = evidence.candidate
        val source = evidence.source
        if (source.baseCommitSha != c.controllerCommitSha || baseline.commitSha != source.baseCommitSha || baseline.branch != CONTROLLER_BRANCH)
            blockers.add("Comparison baseline CI must identify the immutable controller source base")
        if (!source.branchIsolated || c.branch == CONTROLLER_BRANCH || c.commitSha == source.baseCommitSha)
            blockers.add("Self-development promotion requires a distinct isolated task branch and candidate commit")
        if (!source.scopeVerified || !source.controlsUnchanged || source.changedPaths.size !in 1..8 ||
            source.changedPaths.distinct().size != source.changedPaths.size || source.changedPaths.any { !SelfDevelopment.allowedCandidatePath(it) })
            blockers.add("Candidate source changes must be verified within eight safe Developer files with controller protections unchanged")
        if (candidate.commitSha != c.commitSha || candidate.branch != c.branch)
            blockers.add("Candidate CI does not identify the exact candidate commit and branch")
        val lost = baseline.tests.keys.count { candidate.tests[it] != BuildDiagnostics.TestState.PASSED }
        if (lost > 0) blockers.add("Candidate CI loses, skips or fails $lost baseline test case(s)")
        if (c.packageName != PACKAGE_NAME) blockers.add("Candidate package is outside the isolated Developer application")
        val tests = evidence.tests
        if (!tests.passed || tests.tests <= 0 || tests.failures != 0 || tests.errors != 0 || tests.skipped != 0 || tests.lintErrors != 0)
            blockers.add("Signed candidate test and lint gates have not passed completely")
        if (candidate.totals?.let { it.tests != tests.tests || it.failures != tests.failures || it.errors != tests.errors || it.skipped != tests.skipped } != false)
            blockers.add("Signed candidate counts do not match exact-source CI test evidence")
        val known = evidence.baseline
        if (known == null) blockers.add("Known-good signed baseline source and artifact are missing")
        else {
            if (known.packageName != PACKAGE_NAME)
                blockers.add("Known-good artifact package is outside the isolated Developer application")
            if (c.signerSha256 != known.signerSha256) blockers.add("Candidate signer does not match the known-good signing identity")
            if (c.versionCode <= known.versionCode) blockers.add("Candidate version must be higher than the known-good version")
            if (c.apkSha256 == known.apkSha256) blockers.add("A higher-version candidate cannot reuse the known-good APK bytes")
        }
        val backup = evidence.backup
        if (backup == null) blockers.add("Verified previous-release backup is missing; recovery cannot be deferred")
        else if (!backup.verified || known == null || backup.facts != known)
            blockers.add("Previous-release backup bytes, source, version, package or signer do not match the known-good artifact")
        val rebuild = evidence.rebuild
        if (rebuild == null) blockers.add("Actual signed recovery rebuild is missing")
        else if (!rebuild.verified || known == null || rebuild.facts.sourceCommitSha != known.sourceCommitSha ||
            rebuild.facts.packageName != PACKAGE_NAME || rebuild.facts.signerSha256 != known.signerSha256 ||
            rebuild.facts.versionCode <= c.versionCode || rebuild.facts.apkSha256 == known.apkSha256 || rebuild.facts.apkSha256 == c.apkSha256)
            blockers.add("Recovery rebuild must use the known-good source and signer with a version higher than the candidate")
        if (evidence.status != "READY") blockers.add("Trusted controller reports that the candidate is blocked")
        evidence.missingReasons.forEach { blockers.add("Controller recovery/test observation: $it") }
        return Review.create(evidence, blockers.toList(), nowMillis, baseline.fingerprint, candidate.fingerprint)
    }

    fun confirm(review: Review, exactFingerprint: String, explicitlyApproved: Boolean, nowMillis: Long): PromotionRequest {
        review.claim(exactFingerprint, explicitlyApproved, nowMillis)
        return PromotionRequest.create(review, nowMillis)
    }

    fun render(review: Review): String = buildString {
        val c = review.evidence.candidate
        append("Candidate ${if (review.eligible) "eligible for a promotion request" else "BLOCKED"}\n")
        append("Source: ${c.branch} @ ${c.commitSha}\nController: ${c.controllerCommitSha}; run ${c.runId}/${c.runAttempt}\n")
        append("Package/version: ${c.packageName} / ${c.versionCode}\nAPK: ${c.apkSha256}; ${c.apkBytes} bytes\nSigner: ${c.signerSha256}\n")
        append("Comparison source: ${review.evidence.source.baseCommitSha}\nChanged paths:\n")
        review.evidence.source.changedPaths.forEach { append(it).append('\n') }
        append("Scope/controller/isolation verified: ${review.evidence.source.scopeVerified}/${review.evidence.source.controlsUnchanged}/${review.evidence.source.branchIsolated}\n")
        append("Tests: ${review.evidence.tests.tests}; failures/errors/skips ${review.evidence.tests.failures}/${review.evidence.tests.errors}/${review.evidence.tests.skipped}; lint ${review.evidence.tests.lintErrors} errors, ${review.evidence.tests.lintWarnings} warnings\n")
        append("Known-good artifact: ").append(review.evidence.baseline?.let(::artifactSummary) ?: "MISSING").append('\n')
        append("Previous backup: ").append(review.evidence.backup?.let { "verified=${it.verified}; ${artifactSummary(it.facts)}" } ?: "MISSING").append('\n')
        append("Recovery rebuild: ").append(review.evidence.rebuild?.let { "verified=${it.verified}; ${artifactSummary(it.facts)}" } ?: "MISSING").append('\n')
        append("Baseline CI: ${review.baselineReportFingerprint}\nCandidate CI: ${review.candidateReportFingerprint}\n")
        append("Review: ${review.fingerprint}\nExpires: ${review.deadlineAtMillis}\n")
        review.blockers.forEach { append("Blocked: $it\n") }
        append("This is source/CI evidence. Device installation, device verification and publication are separate approvals.")
    }

    fun audit(request: PromotionRequest): String = buildString {
        append("promotion-request-v1 METADATA ONLY\nReview: ${request.reviewFingerprint}\nEvidence: ${request.evidenceFingerprint}\n")
        append("Candidate: ${request.candidate.branch} ${request.candidate.commitSha}\nController: ${request.candidate.controllerCommitSha} ${request.candidate.runId}/${request.candidate.runAttempt}\n")
        append("Build: https://github.com/muddigger782012/J.A.R.V.I.S.-/actions/runs/${request.candidate.runId}/attempts/${request.candidate.runAttempt}\n")
        append("Source base: ${request.source.baseCommitSha}\n")
        request.source.changedPaths.forEach { append("Path: ").append(it).append('\n') }
        append("APK: ${request.candidate.apkSha256} ${request.candidate.apkBytes}\nPackage/version: ${request.candidate.packageName} ${request.candidate.versionCode}\nSigner: ${request.candidate.signerSha256}\n")
        append("Known good: ${artifactSummary(request.baseline)}\nRecovery: ${artifactSummary(request.recovery)}\n")
        append("Baseline CI: ${request.baselineReportFingerprint}\nCandidate CI: ${request.candidateReportFingerprint}\nConfirmed: ${request.confirmedAtMillis}\n")
        append("No publication or installation was performed or authorized by this metadata object.")
    }

    private fun artifactSummary(facts: ArtifactFacts): String = "${facts.sourceCommitSha}; ${facts.packageName}/${facts.versionCode}; ${facts.apkSha256}/${facts.apkBytes} bytes; signer ${facts.signerSha256}"

    private fun passed(report: BuildDiagnostics.Report): Boolean = report.status == "completed" && report.conclusion == "success" &&
        report.evidenceComplete && report.tests.isNotEmpty() && report.tests.values.all { it == BuildDiagnostics.TestState.PASSED } &&
        report.totals?.let { it.tests == report.tests.size && it.failures == 0 && it.errors == 0 && it.skipped == 0 } == true
    private fun artifact(map: Map<String, Any?>, verified: Boolean): Pair<ArtifactFacts, Boolean> {
        val names = arrayOf("sourceCommitSha", "packageName", "versionCode", "apkSha256", "apkBytes", "signerSha256")
        keys(map, *(if (verified) names + "verified" else names))
        val artifact = ArtifactFacts(map.text("sourceCommitSha"), map.text("packageName"),
            map.number("versionCode", 1, Int.MAX_VALUE.toLong()), map.text("apkSha256"),
            map.number("apkBytes", 1, 536_870_912), map.text("signerSha256"))
        validateArtifact(artifact)
        return artifact to if (verified) map.boolean("verified") else false
    }
    private fun validateArtifact(value: ArtifactFacts) {
        require(sha.matches(value.sourceCommitSha) && packageName.matches(value.packageName) && value.packageName.length <= 160 &&
            hash.matches(value.apkSha256) && hash.matches(value.signerSha256))
    }
    private fun keys(map: Map<String, Any?>, vararg names: String) = require(map.keys == names.toSet())
    @Suppress("UNCHECKED_CAST") private fun Any?.obj(): Map<String, Any?> = this as? Map<String, Any?> ?: error("Expected object")
    private fun Map<String, Any?>.text(name: String): String = (this[name] as? String)?.also(::safeText) ?: error("Expected text")
    private fun safeText(it: String) {
        require(it.length in 1..512 && it.none(Char::isISOControl) && RepositoryRedaction.line(it) == it)
        var i = 0
        while (i < it.length) {
            val c = it[i++]
            if (Character.isHighSurrogate(c)) require(i < it.length && Character.isLowSurrogate(it[i++]))
            else require(!Character.isLowSurrogate(c))
        }
    }
    private fun Map<String, Any?>.number(name: String, min: Long, max: Long): Long =
        (this[name] as? Long)?.also { require(it in min..max) } ?: error("Expected integer")
    private fun Map<String, Any?>.count(name: String): Int = number(name, 0, 100_000).toInt()
    private fun Map<String, Any?>.boolean(name: String): Boolean = this[name] as? Boolean ?: error("Expected boolean")
    private fun digest(fields: List<String>): String {
        val digest = MessageDigest.getInstance("SHA-256")
        fields.forEach { val bytes = it.toByteArray(Charsets.UTF_8); digest.update("${bytes.size}:".toByteArray(Charsets.UTF_8)); digest.update(bytes) }
        return digest.digest().joinToString("") { "%02x".format(it.toInt() and 255) }
    }

    /** Bounded JSON grammar rejects duplicate keys (including escaped aliases), coercion and nesting. */
    private class StrictJson(private val text: String) {
        private var index = 0
        private var nodes = 0
        fun parse(): Any? { val result = value(0); whitespace(); require(index == text.length); return result }
        private fun value(depth: Int): Any? {
            require(depth <= 8 && ++nodes <= 256); whitespace(); require(index < text.length)
            return when (text[index]) {
                '{' -> {
                    index++; whitespace(); val result = linkedMapOf<String, Any?>()
                    if (!take('}')) {
                        do { whitespace(); require(index < text.length && text[index] == '"'); val key = string(); whitespace();
                            require(take(':') && key !in result); result[key] = value(depth + 1); whitespace()
                        } while (take(','))
                        require(take('}'))
                    }; result
                }
                '[' -> {
                    index++; whitespace(); val result = mutableListOf<Any?>()
                    if (!take(']')) { do { result.add(value(depth + 1)); whitespace() } while (take(',')); require(take(']')) }; result
                }
                '"' -> string()
                't' -> literal("true", true)
                'f' -> literal("false", false)
                'n' -> literal("null", null)
                '-', in '0'..'9' -> {
                    val start = index
                    if (take('-')) require(index < text.length)
                    if (take('0')) require(index >= text.length || text[index] !in '0'..'9')
                    else { require(index < text.length && text[index] in '1'..'9'); while (index < text.length && text[index] in '0'..'9') index++ }
                    text.substring(start, index).toLong()
                }
                else -> error("Invalid JSON value")
            }
        }
        private fun literal(word: String, result: Any?): Any? { require(text.startsWith(word, index)); index += word.length; return result }
        private fun string(): String {
            require(take('"')); val out = StringBuilder()
            while (index < text.length) {
                val c = text[index++]
                if (c == '"') return out.toString()
                require(c >= ' ' && out.length <= 512)
                if (c != '\\') { out.append(c); continue }
                require(index < text.length)
                when (val escape = text[index++]) {
                    '"', '\\', '/' -> out.append(escape)
                    'b' -> out.append('\b'); 'f' -> out.append('\u000c'); 'n' -> out.append('\n'); 'r' -> out.append('\r'); 't' -> out.append('\t')
                    'u' -> { require(index + 4 <= text.length); val digits = text.substring(index, index + 4)
                        require(digits.all { it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F' }); out.append(digits.toInt(16).toChar()); index += 4 }
                    else -> error("Invalid JSON escape")
                }
            }; error("Unterminated string")
        }
        private fun whitespace() { while (index < text.length && text[index] in " \r\n\t") index++ }
        private fun take(c: Char): Boolean = if (index < text.length && text[index] == c) { index++; true } else false
    }
}
