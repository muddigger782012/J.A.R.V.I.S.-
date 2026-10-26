package com.jarvis.developer

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.InputStream

/** GET-only protocol fixtures keep candidate-controlled build output outside the trust boundary. */
class CandidateFeedbackTest {
    private val repository = "muddigger782012/J.A.R.V.I.S.-"
    private val api = "https://api.github.com/repos/$repository"
    private val branch = "feature/jarvis-developer-task/self-012345abcdef"
    private val sha = "a".repeat(40)
    private val controller = "c".repeat(40)
    private val token = "memory-only-test-token"
    private val storage = "https://productionresults1.blob.core.windows.net/actions-results/job.log?sig=transient-only"

    private data class Request(val url: String, val token: String?, val timeoutMillis: Long)
    private class Fixture : CandidateFeedback.Transport {
        val requests = ArrayList<Request>()
        val replies = LinkedHashMap<String, () -> CandidateFeedback.Response>()
        var closed = 0
        override fun get(url: String, token: String?, timeoutMillis: Long): CandidateFeedback.Response {
            requests += Request(url, token, timeoutMillis)
            return (replies[url] ?: error("Unexpected fixture request"))()
        }
        fun reply(url: String, text: String = "", status: Int = 200, location: String? = null,
                  length: Long = text.toByteArray(Charsets.UTF_8).size.toLong(), finalUrl: String = url) {
            replies[url] = {
                CandidateFeedback.Response(status, length, finalUrl,
                    ByteArrayInputStream(text.toByteArray(Charsets.UTF_8)), location) { closed++ }
            }
        }
        fun json(url: String, value: JSONObject) = reply(url, value.toString())
    }

    private fun runUrl() = "$api/actions/runs/17"
    private fun jobsUrl() = "$api/actions/runs/17/attempts/2/jobs?per_page=10"
    private fun logUrl(id: Long = 91) = "$api/actions/jobs/$id/logs"
    private fun run(status: String = "completed", result: String? = "success") = JSONObject()
        .put("id", 17).put("run_attempt", 2).put("head_sha", controller)
        .put("head_branch", "feature/jarvis-developer").put("event", "workflow_dispatch")
        .put("path", ".github/workflows/evaluate-jarvis-developer.yml")
        .put("repository", JSONObject().put("full_name", repository))
        .put("head_repository", JSONObject().put("full_name", repository))
        .put("html_url", "https://github.com/$repository/actions/runs/17")
        .put("status", status).put("conclusion", result ?: JSONObject.NULL)
    private fun job(id: Long = 91, name: String = "Record candidate evaluation", status: String = "completed",
                    result: String? = "success") = JSONObject()
        .put("id", id).put("run_id", 17).put("run_attempt", 2).put("head_sha", controller)
        .put("run_url", runUrl()).put("name", name).put("status", status)
        .put("conclusion", result ?: JSONObject.NULL)
    private fun jobs(items: List<JSONObject> = listOf(job()), total: Int = items.size) = JSONObject()
        .put("total_count", total).put("jobs", JSONArray(items))
    private fun evidence(): JSONObject = JSONObject().put("schemaVersion", 1).put("status", "BLOCKED")
        .put("source", JSONObject().put("baseCommitSha", controller)
            .put("changedPaths", JSONArray(listOf("developer/src/main/java/com/jarvis/developer/TaskFileSummary.kt")))
            .put("scopeVerified", true).put("controlsUnchanged", true).put("branchIsolated", true))
        .put("candidate", JSONObject().put("commitSha", sha).put("branch", branch)
            .put("controllerCommitSha", controller).put("controllerBranch", "feature/jarvis-developer")
            .put("runId", 17).put("runAttempt", 2).put("packageName", "com.jarvis.developer")
            .put("versionCode", 10).put("apkSha256", "1".repeat(64)).put("apkBytes", 123)
            .put("signerSha256", "2".repeat(64)))
        .put("tests", JSONObject().put("passed", true).put("tests", 1).put("failures", 0)
            .put("errors", 0).put("skipped", 0).put("lintErrors", 0).put("lintWarnings", 0))
        .put("recovery", JSONObject().put("baseline", JSONObject.NULL).put("backup", JSONObject.NULL)
            .put("rebuild", JSONObject.NULL).put("missingReasons", JSONArray(listOf(
                "BASELINE_MISSING", "BACKUP_MISSING", "RECOVERY_REBUILD_MISSING"))))
    private fun marker(value: JSONObject = evidence()) = "JARVIS_CANDIDATE_EVIDENCE=$value\n"
    private fun fixture() = Fixture().apply {
        json(runUrl(), run())
        json(jobsUrl(), jobs())
        reply(logUrl(), marker())
    }
    private fun read(fake: Fixture) = CandidateFeedback.Client(fake).read(token, branch, sha, 17, controller)
    private fun rejected(block: () -> Unit): IllegalStateException {
        try { block(); fail("Expected candidate feedback rejection") }
        catch (failure: IllegalStateException) { return failure }
        error("Unreachable")
    }

    @Test fun exactControllerRunAndAttemptBindIndependentCandidateSourceBeforeReadingEvidence() {
        val fake = fixture()
        assertNotNull(read(fake))
        assertEquals(listOf(runUrl(), jobsUrl(), logUrl()), fake.requests.map { it.url })
        assertTrue(fake.requests.all { it.token == token && it.timeoutMillis in 1..CandidateFeedback.MAX_MILLIS })
        assertEquals(3, fake.closed)
    }

    @Test fun wrongControllerRepositoryWorkflowBranchRunAttemptAndEventsRejectBeforeJobs() {
        for (change in listOf<(JSONObject) -> Unit>(
            { it.put("id", 18) }, { it.put("head_sha", sha) }, { it.put("head_branch", branch) },
            { it.put("path", ".github/workflows/release-jarvis-developer.yml") },
            { it.put("repository", JSONObject().put("full_name", "someone/else")) },
            { it.put("head_repository", JSONObject().put("full_name", "someone/else")) },
            { it.put("html_url", "https://example.invalid/run") }, { it.put("run_attempt", 0) },
            { it.put("run_attempt", "2") }, { it.put("run_attempt", 2.5) },
            { it.put("event", "pull_request") }, { it.put("status", "unknown") },
            { it.put("conclusion", "unknown") }
        )) {
            val fake = fixture()
            fake.json(runUrl(), run().also(change))
            val failure = rejected { read(fake) }
            assertEquals("Candidate feedback could not verify exact-source evidence", failure.message)
            assertEquals(1, fake.requests.size)
            assertEquals(1, fake.closed)
        }
    }

    @Test fun queuedRunsAndUnfinishedJobsNeverSupplyPartialLogs() {
        for (status in listOf("queued", "requested", "waiting", "pending", "in_progress")) {
            val fake = fixture()
            fake.json(runUrl(), run(status, null))
            rejected { read(fake) }
            assertEquals(listOf(runUrl()), fake.requests.map { it.url })
        }
        val fake = fixture()
        fake.json(jobsUrl(), jobs(listOf(job(), job(92, "Build unsigned candidate and recovery", "in_progress", null))))
        rejected { read(fake) }
        assertEquals(2, fake.requests.size)
        assertEquals(2, fake.closed)
    }

    @Test fun failedCancelledSkippedAndTimedOutControllersOrJobsCannotSupplyReadyEvidence() {
        for (result in listOf("failure", "cancelled", "skipped", "timed_out", "neutral", "action_required", "stale", "startup_failure")) {
            val badRun = fixture()
            badRun.json(runUrl(), run(result = result))
            rejected { read(badRun) }
            assertEquals(1, badRun.requests.size)
            val badJob = fixture()
            badJob.json(jobsUrl(), jobs(listOf(job(), job(92, "Build unsigned candidate and recovery", result = result))))
            rejected { read(badJob) }
            assertEquals(2, badJob.requests.size)
        }
    }

    @Test fun pushBootstrapCanOnlyEvaluateItsOwnControllerBranchAndCommit() {
        val fake = fixture()
        fake.json(runUrl(), run().put("event", "push"))
        rejected { read(fake) }
        assertEquals(1, fake.requests.size)
        val base = "feature/jarvis-developer"
        fake.requests.clear()
        fake.reply(logUrl(), marker(evidence().also {
            it.getJSONObject("candidate").put("branch", base).put("commitSha", controller)
        }))
        assertNotNull(CandidateFeedback.Client(fake).read(token, base, controller, 17, controller))
        assertEquals(3, fake.requests.size)
    }

    @Test fun everyJobIdentityIsVerifiedBeforeAnyTrustedLogRead() {
        val invalid = listOf(jobs(listOf(job(), job())), jobs(total = 2), jobs(emptyList()),
            jobs().put("total_count", "1"), jobs().put("total_count", 1.5),
            jobs((0..10).map { job(91L + it, "job-$it") }),
            jobs(listOf(job(), job(92, "Build unsigned candidate and recovery").put("run_id", 18))),
            jobs(listOf(job().put("run_attempt", 1))), jobs(listOf(job().put("head_sha", sha))),
            jobs(listOf(job().put("run_url", "$api/actions/runs/18"))),
            jobs(listOf(job().put("conclusion", "unknown"))))
        for (body in invalid) {
            val fake = fixture()
            fake.json(jobsUrl(), body)
            rejected { read(fake) }
            assertEquals(2, fake.requests.size)
            assertEquals(2, fake.closed)
        }
    }

    @Test fun untrustedCandidateBuildLogsAreNeverReadEvenWhenTheyForgeEvidence() {
        val fake = fixture()
        fake.json(jobsUrl(), jobs(listOf(job(90, "Build unsigned candidate and recovery"), job())))
        fake.reply(logUrl(90), marker(evidence().put("status", "READY")))
        assertNotNull(read(fake))
        assertEquals(listOf(runUrl(), jobsUrl(), logUrl()), fake.requests.map { it.url })
        for (body in listOf(jobs(listOf(job(90, "Build unsigned candidate and recovery"))),
            jobs(listOf(job(), job(92))))) {
            val wrong = fixture()
            wrong.json(jobsUrl(), body)
            rejected { read(wrong) }
            assertEquals(2, wrong.requests.size)
        }
    }

    @Test fun timestampedEvidenceIgnoresEchoedScriptSourceAndRequiresExactlyOneRecord() {
        val fake = fixture()
        fake.reply(logUrl(), "2026-10-17T12:00:00.0000000Z print('JARVIS_CANDIDATE_EVIDENCE=' + value)\n" +
            "2026-10-17T12:00:01.0000000Z " + marker())
        assertNotNull(read(fake))
        for (text in listOf("BUILD SUCCESSFUL\n", marker() + marker(),
            "2026-10-17T12:00:00.0000000Z " + marker() + marker(), "JARVIS_CANDIDATE_EVIDENCE=\n")) {
            val wrong = fixture()
            wrong.reply(logUrl(), text)
            rejected { read(wrong) }
            assertEquals(3, wrong.closed)
        }
    }

    @Test fun structuredEvidenceMustMatchCandidateControllerRunAndAttempt() {
        for (change in listOf<(JSONObject) -> Unit>(
            { it.getJSONObject("candidate").put("commitSha", controller) },
            { it.getJSONObject("candidate").put("branch", "feature/jarvis-developer-task/other-012345abcdef") },
            { it.getJSONObject("candidate").put("controllerCommitSha", sha) },
            { it.getJSONObject("candidate").put("controllerBranch", "main") },
            { it.getJSONObject("candidate").put("runId", 18) },
            { it.getJSONObject("candidate").put("runAttempt", 1) },
            { it.put("schemaVersion", 2) }, { it.put("extra", "untrusted") }
        )) {
            val fake = fixture()
            fake.reply(logUrl(), marker(evidence().also(change)))
            rejected { read(fake) }
            assertEquals(3, fake.closed)
        }
    }

    @Test fun signedStorageReceivesNoTokenAndSecondHopNeverOccurs() {
        val fake = fixture()
        fake.reply(logUrl(), status = 302, location = storage)
        fake.reply(storage, marker())
        assertNotNull(read(fake))
        assertEquals(listOf(runUrl(), jobsUrl(), logUrl(), storage), fake.requests.map { it.url })
        assertTrue(fake.requests.dropLast(1).all { it.token == token })
        assertNull(fake.requests.last().token)
        assertEquals(4, fake.closed)
        fake.requests.clear()
        fake.reply(storage, status = 302, location = storage + "&next=1")
        rejected { read(fake) }
        assertEquals(4, fake.requests.size)
        assertEquals(8, fake.closed)
    }

    @Test fun unrecognizedRedirectsNeverLeaveApiOriginAndLocationsNeverEscape() {
        for (destination in listOf("http://productionresults1.blob.core.windows.net/a?sig=x",
            "https://evil.example/a?sig=x", "https://productionresults1.blob.core.windows.net.evil.example/a?sig=x",
            "https://token@productionresults1.blob.core.windows.net/a?sig=x",
            "https://productionresults1.blob.core.windows.net:443/a?sig=x",
            "https://productionresults1.blob.core.windows.net/a?sig=x#fragment",
            "https://productionresults1.blob.core.windows.net/a/../b?sig=x", logUrl())) {
            val fake = fixture()
            fake.reply(logUrl(), status = 302, location = destination)
            val failure = rejected { read(fake) }
            assertFalse(failure.message.orEmpty().contains(destination))
            assertFalse(failure.message.orEmpty().contains("sig="))
            assertEquals(3, fake.requests.size)
            assertEquals(3, fake.closed)
        }
    }

    @Test fun apiRedirectAndUnexpectedFinalUrlAreRejectedWithoutFollowing() {
        for (response in listOf<(Fixture) -> Unit>(
            { it.reply(runUrl(), status = 302, location = storage) },
            { it.reply(runUrl(), run().toString(), finalUrl = storage) }
        )) {
            val fake = fixture().also(response)
            rejected { read(fake) }
            assertEquals(1, fake.requests.size)
            assertEquals(1, fake.closed)
        }
    }

    @Test fun httpErrorBodiesAndTransportCausesNeverExposeSecrets() {
        val fake = fixture()
        var supplied = false
        fake.replies[runUrl()] = {
            CandidateFeedback.Response(403, 100, runUrl(), object : InputStream() {
                override fun read(): Int { supplied = true; error("Must not read credential-bearing body") }
            }) { fake.closed++ }
        }
        val failure = rejected { read(fake) }
        assertFalse(supplied)
        assertEquals("Candidate feedback could not verify exact-source evidence (HTTP 403)", failure.message)
        assertNull(failure.cause)
        assertEquals(1, fake.closed)
        fake.replies[runUrl()] = { error("$token $storage") }
        val raw = rejected { read(fake) }
        assertFalse(raw.message.orEmpty().contains(token))
        assertFalse(raw.message.orEmpty().contains("sig="))
        assertNull(raw.cause)
    }

    @Test fun advertisedOversizedLogsRejectBeforeReadingAndUnknownLengthsStopAtLimitPlusOne() {
        val fake = fixture()
        var supplied = 0
        fun endless(length: Long) = {
            CandidateFeedback.Response(200, length, logUrl(), object : InputStream() {
                override fun read(): Int { supplied++; return 'x'.code }
                override fun read(bytes: ByteArray, offset: Int, length: Int): Int {
                    bytes.fill('x'.code.toByte(), offset, offset + length)
                    supplied += length
                    return length
                }
            }) { fake.closed++ }
        }
        fake.replies[logUrl()] = endless(CandidateFeedback.MAX_LOG_BYTES + 1L)
        rejected { read(fake) }
        assertEquals(0, supplied)
        fake.replies[logUrl()] = endless(-1)
        rejected { read(fake) }
        assertEquals(CandidateFeedback.MAX_LOG_BYTES + 1, supplied)
        assertEquals(6, fake.closed)
    }

    @Test fun sessionByteBudgetIncludesBothMetadataResponsesAndTrustedLog() {
        val fake = fixture()
        fake.json(runUrl(), run().put("padding", "x".repeat(800_000)))
        fake.json(jobsUrl(), jobs().put("padding", "x".repeat(800_000)))
        fake.reply(logUrl(), "x".repeat(500_000) + "\n" + marker())
        rejected { read(fake) }
        assertEquals(3, fake.requests.size)
        assertEquals(3, fake.closed)
    }

    @Test fun requestBudgetIncludesStorageAndClientCannotBeReusedToExceedIt() {
        val fake = fixture()
        fake.reply(logUrl(), status = 302, location = storage)
        fake.reply(storage, marker())
        val client = CandidateFeedback.Client(fake)
        repeat(5) { assertNotNull(client.read(token, branch, sha, 17, controller)) }
        rejected { client.read(token, branch, sha, 17, controller) }
        assertEquals(CandidateFeedback.MAX_REQUESTS, fake.requests.size)
        assertEquals(CandidateFeedback.MAX_REQUESTS, fake.closed)
    }

    @Test fun elapsedBudgetStopsBeforeAnotherReadAndPassesRemainingDeadline() {
        val fake = fixture()
        var now = 0L
        val client = CandidateFeedback.Client(fake) { now }
        fake.replies[runUrl()] = {
            now = CandidateFeedback.MAX_MILLIS
            CandidateFeedback.Response(200, -1, runUrl(), ByteArrayInputStream(run().toString().toByteArray())) { fake.closed++ }
        }
        rejected { client.read(token, branch, sha, 17, controller) }
        assertEquals(1, fake.requests.size)
        assertEquals(CandidateFeedback.MAX_MILLIS, fake.requests.single().timeoutMillis)
        assertEquals(1, fake.closed)
        rejected { client.read(token, branch, sha, 17, controller) }
        assertEquals(1, fake.requests.size)
    }

    @Test fun invalidUtf8NonProgressingStreamsAndOversizedEvidenceRecordsFailClosed() {
        val fake = fixture()
        fake.replies[logUrl()] = {
            CandidateFeedback.Response(200, 2, logUrl(), ByteArrayInputStream(byteArrayOf(0xc3.toByte(), 0x28))) { fake.closed++ }
        }
        rejected { read(fake) }
        fake.replies[logUrl()] = {
            CandidateFeedback.Response(200, -1, logUrl(), object : InputStream() {
                override fun read(): Int = 0
                override fun read(bytes: ByteArray, offset: Int, length: Int): Int = 0
            }) { fake.closed++ }
        }
        rejected { read(fake) }
        fake.reply(logUrl(), "JARVIS_CANDIDATE_EVIDENCE=" + "x".repeat(CandidateFeedback.MAX_EVIDENCE_BYTES + 1))
        rejected { read(fake) }
        assertEquals(9, fake.closed)
    }

    @Test fun sourceIdentityAndWriteForeignOrDispatchEndpointsRejectBeforeNetwork() {
        val fake = Fixture()
        for (badBranch in listOf("main", "feature/jarvis-developer/task/a", "feature/jarvis-developer-task/../bad-012345abcdef")) {
            try { CandidateFeedback.Client(fake).read(token, badBranch, sha, 17, controller); fail("Invalid branch") }
            catch (_: IllegalArgumentException) { }
        }
        for (badSha in listOf("main", "a".repeat(39), "A".repeat(40))) {
            try { CandidateFeedback.Client(fake).read(token, branch, badSha, 17, controller); fail("Invalid SHA") }
            catch (_: IllegalArgumentException) { }
            try { CandidateFeedback.Client(fake).read(token, branch, sha, 17, badSha); fail("Invalid controller") }
            catch (_: IllegalArgumentException) { }
        }
        for (badRun in listOf(0L, -1L)) {
            try { CandidateFeedback.Client(fake).read(token, branch, sha, badRun, controller); fail("Invalid run") }
            catch (_: IllegalArgumentException) { }
        }
        try { CandidateFeedback.Client(fake).read("bad\nheader", branch, sha, 17, controller); fail("Invalid token") }
        catch (_: IllegalArgumentException) { }
        for (url in listOf("$api/git/refs", "$api/actions/workflows/evaluate-jarvis-developer.yml/dispatches",
            "$api/actions/runs/17/cancel", "$api/releases", "$api/actions/runs/17?other=1",
            "https://api.github.com/repos/someone/else/actions/runs/17", "http://api.github.com/repos/$repository/actions/runs/17",
            "https://user@api.github.com/repos/$repository/actions/runs/17", "https://api.github.com:443/repos/$repository/actions/runs/17")) {
            try { CandidateFeedback.requireApiUrl(url); fail("Invalid endpoint") }
            catch (_: IllegalArgumentException) { }
        }
        assertTrue(fake.requests.isEmpty())
    }
}
