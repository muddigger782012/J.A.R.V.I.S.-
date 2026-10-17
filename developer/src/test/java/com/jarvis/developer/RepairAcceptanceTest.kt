package com.jarvis.developer

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.nio.file.SimpleFileVisitor
import java.nio.file.attribute.BasicFileAttributes
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * Real fixed Java commands run only against a disposable fixture, never the analyzed checkout.
 * Their actual compiler/test outputs enter the production feedback parser and repair gates.
 * GitHub identities, commit results and CI/JUnit envelopes are explicitly modeled; the provider
 * is deterministic Fixture data, and this test performs no network call or repository write.
 */
class RepairAcceptanceTest {
    @Test fun seededCompilerAndTestFailuresAreCorrectedWithinTwoReviewedAttempts() {
        val root = Paths.get(requireNotNull(System.getProperty("jarvis.repositoryRoot"))).toRealPath()
        val checkoutSha = System.getProperty("jarvis.repositoryCommit").orEmpty()
        assertTrue("Acceptance requires the actual full Actions checkout SHA", Regex("[a-f0-9]{40}").matches(checkoutSha))
        val before = snapshot(root)
        val temporary = Files.createTempDirectory("jarvis-phase4-fixed-fixture-")
        try {
            val source = temporary.resolve(SOURCE)
            val harness = temporary.resolve(HARNESS)
            Files.createDirectories(source.parent)
            Files.createDirectories(harness.parent)
            write(source, counter("2"))
            write(harness, checkSource())
            val harnessBlob = gitBlob(Files.readAllBytes(harness))
            val commands = mutableListOf<CommandResult>()
            val reportReads = mutableListOf<ModeledFeedback>()
            var clock = 1_800_000_000_000L
            fun tick(): Long { clock += 1; return clock }
            fun feedback(commit: String, branch: String, run: Long, success: Boolean, output: String,
                checked: Boolean): BuildDiagnostics.Report {
                val fixture = ModeledFeedback(commit, branch, run, success, output, checked)
                val report = BuildFeedback.Client(fixture).read("", branch, commit, run)
                assertEquals(3, fixture.requests.size)
                assertEquals(fixture.requests.size, fixture.closed)
                BuildDiagnostics.validateReport(report)
                assertEquals(commit, report.commitSha)
                assertEquals(branch, report.branch)
                assertEquals(run, report.runId)
                assertEquals(1, report.runAttempt)
                reportReads += fixture
                return report
            }

            // Establish the regression baseline with a real successful compiler and Java check.
            val baselineCompile = compile(temporary, "baseline").also { commands += it }
            assertEquals("Fixed known-good Java fixture must compile", 0, baselineCompile.exitCode)
            val baselineCheck = check(temporary, "baseline").also { commands += it }
            assertEquals("Fixed known-good regression check must pass", 0, baselineCheck.exitCode)
            assertTrue(baselineCheck.output.contains("PASS expectedTwo"))
            val baselineSha = modeledSha("baseline", counter("2"))
            val baseline = feedback(baselineSha, BASE_BRANCH, 94001, true,
                baselineCompile.output + baselineCheck.output, true)
            assertTrue(baseline.evidenceComplete)
            assertEquals(mapOf(CASE to BuildDiagnostics.TestState.PASSED), baseline.tests)
            var session = RepairEngine.start("phase4-seeded-counter", listOf(SOURCE), baseline, 2, tick())
            assertEquals(2, session.maxAttempts)
            assertEquals(30 * 60 * 1000L, session.deadlineAtMillis - session.startedAtMillis)

            // Seed a real unresolved Java symbol. No test result is invented for an unbuilt class.
            write(source, counter("missingSymbol"))
            val seededBlob = gitBlob(Files.readAllBytes(source))
            val compilerFailure = compile(temporary, "compiler-failure").also { commands += it }
            assertNotEquals(0, compilerFailure.exitCode)
            assertTrue(compilerFailure.output.contains("cannot find symbol"))
            val failedSha = modeledSha("seeded-compiler-failure", counter("missingSymbol"))
            assertNotEquals("Modeled fixture source is separate from the actual checkout", checkoutSha, failedSha)
            var failedReport = feedback(failedSha, BASE_BRANCH, 94002, false, compilerFailure.output, false)
            assertTrue(failedReport.diagnostics.any { it.kind == BuildDiagnostics.Kind.COMPILER && it.path == SOURCE })
            assertTrue(failedReport.diagnostics.filter { it.kind == BuildDiagnostics.Kind.COMPILER }.all { it.path == SOURCE })
            assertTrue(failedReport.tests.isEmpty())

            val proposalIds = mutableListOf<String>()
            val modeledCommits = mutableListOf<PatchPublisher.CommitResult>()
            val preimages = mutableListOf<String>()
            val observedReports = mutableListOf(failedReport)
            var completed: RepairEngine.Assessment? = null
            for (attempt in 1..2) {
                val prepared = RepairEngine.prepare(session, failedReport,
                    "Correct the fixed RepairCounter value without changing its public API or the read-only expectedTwo regression check.", tick())
                assertEquals(attempt, prepared.ticket.number)
                assertEquals(listOf(SOURCE), prepared.ticket.scope)
                assertEquals(failedReport.commitSha, prepared.ticket.baseCommitSha)
                assertEquals(failedReport.branch, prepared.ticket.baseBranch)
                assertTrue(prepared.ticket.task.contains(failedReport.commitSha))
                assertTrue(prepared.ticket.task.contains(failedReport.fingerprint))
                assertTrue(prepared.ticket.task.contains("untrusted diagnostic data"))
                prepared.ticket.claimPreview(tick())
                val bytes = Files.readAllBytes(source)
                val preimage = gitBlob(bytes)
                preimages += preimage
                assertEquals(if (attempt == 1) seededBlob else gitBlob(counter("1").toByteArray(Charsets.UTF_8)), preimage)
                val request = PatchEngine.Request(prepared.ticket.taskId, prepared.ticket.task,
                    prepared.ticket.baseCommitSha, prepared.ticket.scope,
                    listOf(PatchEngine.SourceFile(SOURCE, preimage, bytes.toString(Charsets.UTF_8))),
                    "Fixture", "deterministic-seeded-repair-v1", prepared.ticket.baseBranch)
                // Deliberately fix only compilation first, exposing a different real test failure.
                val replacement = counter(if (attempt == 1) "1" else "2")
                val response = JSONObject().put("summary", "Deterministic Fixture repair $attempt; exact Java replacement for review, no live model.")
                    .put("edits", JSONArray().put(JSONObject().put("path", SOURCE).put("content", replacement)))
                val proposal = PatchEngine.parse(request, response.toString())
                PatchEngine.revalidate(proposal)
                assertEquals(listOf(SOURCE), proposal.edits.map { it.path })
                assertEquals(preimage, proposal.request.sources.single().sha)
                assertTrue(proposal.diff.contains("diff --git a/$SOURCE b/$SOURCE"))
                assertTrue(proposal.diff.contains("+        return ${if (attempt == 1) "1" else "2"};"))
                expectFailure { PatchEngine.approve(proposal, "not-this-proposal") }
                val approval = PatchEngine.approve(proposal, proposal.id)
                prepared.ticket.claimCommit(approval, tick())
                expectFailure { prepared.ticket.claimCommit(approval, tick()) }
                proposalIds += proposal.id

                // This is a local fixture application and an explicitly modeled commit result.
                // Production GitHub publisher behavior has independent protocol coverage.
                assertEquals(preimage, gitBlob(Files.readAllBytes(source)))
                write(source, proposal.edits.single().content)
                assertEquals(gitBlob(replacement.toByteArray(Charsets.UTF_8)), gitBlob(Files.readAllBytes(source)))
                val result = modeledCommit(proposal)
                modeledCommits += result
                session = RepairEngine.recordCommit(prepared.session, prepared.ticket, result, tick())
                assertEquals("AWAITING_CI", session.attempts.last().outcome)
                expectFailure { prepared.ticket.claimPreview(tick()) }
                val correctedCompile = compile(temporary, "repair-$attempt").also { commands += it }
                assertEquals("Each reviewed replacement must actually compile", 0, correctedCompile.exitCode)
                val correctedCheck = check(temporary, "repair-$attempt").also { commands += it }
                if (attempt == 1) {
                    assertNotEquals("First compiler correction intentionally exposes a real failing check", 0, correctedCheck.exitCode)
                    assertTrue(correctedCheck.output.contains("AssertionError: Expected 2 but got 1"))
                } else {
                    assertEquals("Second scoped correction must pass the same real baseline check", 0, correctedCheck.exitCode)
                    assertTrue(correctedCheck.output.contains("PASS expectedTwo"))
                }
                failedReport = feedback(result.commitSha, result.branch, 94002L + attempt,
                    correctedCheck.exitCode == 0, correctedCompile.output + correctedCheck.output, true)
                observedReports += failedReport
                val assessment = RepairEngine.assess(session, failedReport, tick())
                session = assessment.session
                assertEquals(attempt == 2, assessment.passed)
                assertEquals(attempt == 1, assessment.canRetry)
                if (attempt == 1) {
                    assertTrue(failedReport.diagnostics.any { it.kind == BuildDiagnostics.Kind.TEST })
                    assertEquals(mapOf(CASE to BuildDiagnostics.TestState.FAILED), failedReport.tests)
                    assertNotEquals(BuildDiagnostics.failureSignature(observedReports[0]), BuildDiagnostics.failureSignature(failedReport))
                } else completed = assessment
                assertEquals("Read-only test harness must never enter the edit scope", harnessBlob, gitBlob(Files.readAllBytes(harness)))
            }
            val finalAssessment = requireNotNull(completed)
            assertTrue(finalAssessment.passed)
            assertFalse(finalAssessment.canRetry)
            assertEquals(2, session.attempts.size)
            assertEquals(listOf("CI_FAILED", "PASSED"), session.attempts.map { it.outcome })
            assertEquals(mapOf(CASE to BuildDiagnostics.TestState.PASSED), failedReport.tests)
            assertTrue(failedReport.evidenceComplete)
            assertEquals(1, failedReport.totals?.tests)
            assertEquals(0, failedReport.totals?.failures)
            assertTrue(session.baselineTests.all { failedReport.tests[it] == BuildDiagnostics.TestState.PASSED })
            expectFailure { RepairEngine.prepare(session, failedReport, "An unapproved third attempt", tick()) }
            assertEquals(2, session.attempts.size)
            assertEquals(7, commands.size)
            assertEquals(listOf(0, 0, 1, 0, 1, 0, 0), commands.map { it.exitCode })
            assertEquals(before, snapshot(root))
            emitEvidence(root, checkoutSha, session, commands, reportReads, observedReports,
                proposalIds, preimages, modeledCommits, seededBlob, harnessBlob)
        } finally {
            try {
                assertEquals("Compiler, model fixtures and repair gates must leave the analyzed checkout unchanged", before, snapshot(root))
            } finally {
                assertTrue("All executable fixture source/classes should be removable", temporary.toFile().deleteRecursively())
            }
        }
    }

    private data class CommandResult(val name: String, val stage: String, val exitCode: Int, val output: String)

    /** Fixed JDK binaries/flags only; fixture paths contain no provider, repository or user command. */
    private fun compile(root: Path, stage: String): CommandResult {
        val classes = root.resolve("classes-$stage")
        Files.createDirectories(classes)
        return execute(root, "javac", stage, listOf("-J-Duser.language=en", "-J-Duser.country=US", "-encoding", "UTF-8",
            "-proc:none", "-classpath", classes.toString(), "-d", classes.toString(),
            root.resolve(SOURCE).toString(), root.resolve(HARNESS).toString()))
    }

    private fun check(root: Path, stage: String): CommandResult = execute(root, "java", stage,
        listOf("-cp", root.resolve("classes-$stage").toString(), "com.jarvis.developer.RepairCounterCheck"))

    private fun execute(root: Path, command: String, stage: String, arguments: List<String>): CommandResult {
        require(command == "java" || command == "javac")
        val binary = Paths.get(System.getProperty("java.home"), "bin", command).toRealPath()
        require(Files.isRegularFile(binary) && Files.isExecutable(binary)) { "Acceptance needs the CI JDK binary" }
        val builder = ProcessBuilder(listOf(binary.toString()) + arguments)
            .directory(root.toFile()).redirectErrorStream(true)
        listOf("JAVA_TOOL_OPTIONS", "JDK_JAVA_OPTIONS", "JDK_JAVAC_OPTIONS", "_JAVA_OPTIONS")
            .forEach { builder.environment().remove(it) }
        val process = builder.start()
        val output = ByteArrayOutputStream()
        val readFailure = AtomicReference<Throwable?>()
        val reader = Thread {
            try {
                process.inputStream.use { input ->
                    val buffer = ByteArray(4096)
                    while (true) {
                        val size = input.read(buffer)
                        if (size < 0) break
                        require(size > 0 && output.size() + size <= 65_536) { "Fixed fixture output exceeded 64 KiB" }
                        output.write(buffer, 0, size)
                    }
                }
            } catch (failure: Throwable) {
                readFailure.set(failure)
                process.destroyForcibly()
            }
        }
        reader.isDaemon = true
        reader.start()
        try {
            assertTrue("Fixed $command fixture must finish within 20 seconds", process.waitFor(20, TimeUnit.SECONDS))
            reader.join(2_000)
            assertFalse("Fixed fixture output reader must terminate", reader.isAlive)
            readFailure.get()?.let { throw AssertionError("Fixed fixture output could not be bounded/read", it) }
            return CommandResult(command, stage, process.exitValue(), output.toByteArray().toString(Charsets.UTF_8))
        } finally {
            process.destroyForcibly()
            process.waitFor(2, TimeUnit.SECONDS)
            process.inputStream.close()
            reader.join(2_000)
        }
    }

    /** Modeled GitHub metadata wraps actual command output without any live network request. */
    private class ModeledFeedback(private val commit: String, private val branch: String,
        private val run: Long, private val success: Boolean, output: String, checked: Boolean) : BuildFeedback.Transport {
        val requests = mutableListOf<String>()
        var closed = 0
        private val conclusion = if (success) "success" else "failure"
        private val log = "MODELED CI metadata/JUnit envelope around actual fixed JDK command output; no GitHub run occurred.\n" +
            (if (checked && !success) "com.jarvis.developer.RepairCounterCheck > expectedTwo FAILED\n" else "") + output +
            (if (checked) "\nJARVIS_JUNIT_SUMMARY=" + summary(commit, success).toString() else "")

        override fun get(url: String, token: String?, timeoutMillis: Long): BuildFeedback.Response {
            assertEquals("No real write/read credential enters modeled feedback", "", token)
            assertTrue(timeoutMillis in 1..BuildFeedback.MAX_MILLIS)
            requests += url
            val text = when (url) {
                "$API/actions/runs/$run" -> JSONObject().put("id", run).put("run_attempt", 1)
                    .put("head_sha", commit).put("head_branch", branch).put("path", ".github/workflows/build-jarvis-developer.yml")
                    .put("repository", JSONObject().put("full_name", REPOSITORY)).put("head_repository", JSONObject().put("full_name", REPOSITORY))
                    .put("html_url", "https://github.com/$REPOSITORY/actions/runs/$run")
                    .put("status", "completed").put("conclusion", conclusion).toString()
                "$API/actions/runs/$run/attempts/1/jobs?per_page=10" -> JSONObject().put("total_count", 1)
                    .put("jobs", JSONArray().put(JSONObject().put("id", run + 100_000).put("run_id", run)
                        .put("run_attempt", 1).put("head_sha", commit).put("run_url", "$API/actions/runs/$run")
                        .put("status", "completed").put("conclusion", conclusion))).toString()
                "$API/actions/jobs/${run + 100_000}/logs" -> log
                else -> throw AssertionError("Feedback escaped the fixed modeled GET endpoints")
            }
            val bytes = text.toByteArray(Charsets.UTF_8)
            return BuildFeedback.Response(200, bytes.size.toLong(), url, ByteArrayInputStream(bytes)) { closed++ }
        }
    }

    private fun emitEvidence(root: Path, checkoutSha: String, session: RepairEngine.Session,
        commands: List<CommandResult>, reads: List<ModeledFeedback>, reports: List<BuildDiagnostics.Report>,
        proposals: List<String>, preimages: List<String>, commits: List<PatchPublisher.CommitResult>,
        seededBlob: String, harnessBlob: String) {
        val evidence = JSONObject().put("schema", "jarvis-phase4-seeded-repair-acceptance-v1")
            .put("actualExecutionCheckoutSha", checkoutSha).put("taskId", session.id)
            .put("provider", "Fixture").put("model", "deterministic-seeded-repair-v1")
            .put("editScope", JSONArray(session.scope)).put("attemptBudget", session.maxAttempts)
            .put("attemptCount", session.attempts.size).put("deadlineDurationMillis", session.deadlineAtMillis - session.startedAtMillis)
            .put("commands", JSONArray(commands.map { command -> JSONObject().put("binary", "java.home/bin/${command.name}")
                .put("stage", command.stage).put("exitCode", command.exitCode).put("timeoutSeconds", 20).put("outputBoundBytes", 65_536)
                .put("actualOutputBytes", command.output.toByteArray(Charsets.UTF_8).size)
                .put("actualOutputSha256", digest("SHA-256", command.output.toByteArray(Charsets.UTF_8))) }))
            .put("productionFeedbackLimits", JSONObject().put("maximumRequestsPerRead", BuildFeedback.MAX_REQUESTS)
                .put("maximumJobsPerRead", BuildFeedback.MAX_JOBS).put("maximumMillisPerRead", BuildFeedback.MAX_MILLIS)
                .put("maximumLogBytes", BuildFeedback.MAX_LOG_BYTES).put("maximumSessionBytes", BuildFeedback.MAX_SESSION_BYTES))
            .put("modeledBaseline", JSONObject().put("commitSha", session.baselineCommitSha).put("branch", session.baselineBranch)
                .put("runId", session.baselineRunId).put("reportFingerprint", session.baselineReportFingerprint)
                .put("actualCheckResult", "PASSED").put("testIds", JSONArray(session.baselineTests)))
            .put("seededFixtureGitBlobSha", seededBlob).put("readOnlyHarnessGitBlobSha", harnessBlob)
            .put("verifiedFixturePreimageGitBlobs", JSONArray(preimages)).put("reviewedProposalIds", JSONArray(proposals))
            .put("modeledCommitResults", JSONArray(commits.map { result -> JSONObject().put("commitSha", result.commitSha)
                .put("baseCommitSha", result.baseCommitSha).put("baseBranch", result.baseBranch).put("branch", result.branch)
                .put("proposalId", result.proposalId).put("paths", JSONArray(result.paths)) }))
            .put("diagnosedActualOutputs", JSONArray(reports.map { report -> JSONObject().put("modeledCommitSha", report.commitSha)
                .put("modeledRunId", report.runId).put("reportFingerprint", report.fingerprint).put("evidenceComplete", report.evidenceComplete)
                .put("kinds", JSONArray(report.diagnostics.map { it.kind.name }.distinct()))
                .put("paths", JSONArray(report.diagnostics.mapNotNull { it.path }.distinct()))
                .put("actualCheckResult", report.tests[CASE]?.name ?: "NOT_RUN_COMPILER_FAILED") }))
            .put("checks", JSONObject().put("baselineRegressionPassed", true).put("finalRegressionPassed", true)
                .put("readOnlyHarnessUnchanged", true).put("analyzedRepositoryPathsAndBytesUnchanged", true)
                .put("eachProposalExplicitlyApproved", true).put("thirdAttemptRejected", true)
                .put("modeledFeedbackGetCount", reads.map { it.requests.size }.sum()).put("closedResponses", reads.map { it.closed }.sum()))
            .put("evidenceBoundaries", JSONArray(listOf(
                "Actual immutable Actions checkout SHA identifies execution of this test; the Java fixture lives only in a temporary directory.",
                "java.home/bin/javac and java.home/bin/java execute fixed fixture source and a fixed expectedTwo check, with bounded output and deadlines; no shell or user command is executed.",
                "The compiler disables annotation processing, uses only its disposable output directory as classpath, and removes JDK option-injection environment variables.",
                "The seeded compiler failure, first correction's assertion failure, and final baseline regression pass are real JDK exit codes and output.",
                "GitHub source/branch/run/commit identities, CI conclusions and JUnit envelopes are MODELED metadata around the actual command results; no Git commit or GitHub workflow is created by this test.",
                "Production BuildFeedback, BuildDiagnostics, RepairEngine and PatchEngine validate exact-source feedback, two attempts, single-use tickets, verified preimages, full diffs and fresh exact approval.",
                "Deterministic Fixture provider responses are not live AI output. The production GitHub publisher has separate protocol tests; CommitResult objects here are MODELED.",
                "No actual analyzed repository source is edited; no signing, publication, installation, Android lint/instrumentation or device behavior is claimed."
            )))
        val directory = root.resolve("developer/build/reports/phase4-acceptance")
        Files.createDirectories(directory)
        Files.write(directory.resolve("seeded-repair-evidence.json"), (evidence.toString(2) + "\n").toByteArray(Charsets.UTF_8))
    }

    private fun snapshot(root: Path): Map<String, String> {
        val files = sortedMapOf<String, String>()
        Files.walkFileTree(root, object : SimpleFileVisitor<Path>() {
            override fun preVisitDirectory(directory: Path, attributes: BasicFileAttributes): FileVisitResult =
                if (directory != root && directory.fileName.toString() in setOf(".git", ".gradle", ".kotlin", "build", "out", "node_modules"))
                    FileVisitResult.SKIP_SUBTREE else FileVisitResult.CONTINUE
            override fun visitFile(file: Path, attributes: BasicFileAttributes): FileVisitResult {
                if (attributes.isRegularFile && !Files.isSymbolicLink(file) && file.fileName.toString() != ".git")
                    files[root.relativize(file).toString().replace('\\', '/')] = gitBlob(Files.readAllBytes(file))
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
        private const val SOURCE = "developer/src/main/java/com/jarvis/developer/RepairCounter.java"
        private const val HARNESS = "developer/src/test/java/com/jarvis/developer/RepairCounterCheck.java"
        private const val CASE = "com.jarvis.developer.RepairCounterCheck#expectedTwo"
        private const val BASE_BRANCH = "feature/jarvis-developer"
        private const val REPOSITORY = "muddigger782012/J.A.R.V.I.S.-"
        private const val API = "https://api.github.com/repos/$REPOSITORY"
        private fun write(path: Path, content: String) { Files.write(path, content.toByteArray(Charsets.UTF_8)) }
        private fun counter(expression: String): String = """
            package com.jarvis.developer;

            public final class RepairCounter {
                public static int value() {
                    return $expression;
                }
            }
        """.trimIndent() + "\n"
        private fun checkSource(): String = """
            package com.jarvis.developer;

            public final class RepairCounterCheck {
                public static void main(String[] args) {
                    int actual = RepairCounter.value();
                    if (actual != 2) throw new AssertionError("Expected 2 but got " + actual);
                    System.out.println("PASS expectedTwo");
                }
            }
        """.trimIndent() + "\n"
        private fun modeledSha(label: String, value: String): String = digest("SHA-1", ("MODELED fixture source $label\n$value").toByteArray(Charsets.UTF_8))
        private fun gitBlob(bytes: ByteArray): String {
            val hash = MessageDigest.getInstance("SHA-1")
            hash.update("blob ${bytes.size}\u0000".toByteArray(Charsets.UTF_8))
            return hash.digest(bytes).joinToString("") { "%02x".format(it.toInt() and 255) }
        }
        private fun digest(algorithm: String, bytes: ByteArray): String = MessageDigest.getInstance(algorithm).digest(bytes)
            .joinToString("") { "%02x".format(it.toInt() and 255) }
        private fun modeledCommit(proposal: PatchEngine.Proposal): PatchPublisher.CommitResult = PatchPublisher.CommitResult(
            proposal.request.taskId, proposal.request.provider, proposal.request.model, proposal.id,
            proposal.edits.map { it.path }.sorted(), proposal.request.baseCommitSha,
            modeledSha("reviewed-commit", proposal.request.baseCommitSha + proposal.id), PatchEngine.branchName(proposal),
            proposal.diff, proposal.request.baseBranch)
        private fun summary(commit: String, success: Boolean): JSONObject {
            val suite = "com.jarvis.developer.RepairCounterCheck"
            val failures = if (success) 0 else 1
            fun counts(): JSONObject = JSONObject().put("tests", 1).put("failures", failures).put("errors", 0).put("skipped", 0)
            val modeledXml = "MODELED JUnit envelope of actual fixed check exit: $suite#expectedTwo ${if (success) "passed" else "failure"}"
            return JSONObject().put("sourceCommit", commit).put("totals", counts())
                .put("suites", JSONArray().put(counts().put("name", suite)
                    .put("sha256", digest("SHA-256", modeledXml.toByteArray(Charsets.UTF_8)))))
                .put("cases", JSONArray().put(JSONObject().put("suite", suite).put("name", "expectedTwo")
                    .put("result", if (success) "passed" else "failure")))
        }
    }
}
