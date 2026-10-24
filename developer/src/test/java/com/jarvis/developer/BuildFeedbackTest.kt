package com.jarvis.developer

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.InputStream
import java.net.URLEncoder

/** The fixture can perform GET only and records the credential boundary on every hop. */
class BuildFeedbackTest {
    private val repository = "muddigger782012/J.A.R.V.I.S.-"
    private val api = "https://api.github.com/repos/$repository"
    private val branch = "feature/jarvis-developer-task/repair-012345abcdef"
    private val sha = "a".repeat(40)
    private val token = "memory-only-test-token"
    private val storage = "https://productionresults1.blob.core.windows.net/actions-results/job.log?sig=transient-only"

    private data class Request(val url: String, val token: String?, val timeoutMillis: Long)
    private class Fixture : BuildFeedback.Transport {
        val requests = ArrayList<Request>()
        val replies = LinkedHashMap<String, () -> BuildFeedback.Response>()
        var closed = 0
        override fun get(url: String, token: String?, timeoutMillis: Long): BuildFeedback.Response {
            requests += Request(url, token, timeoutMillis)
            return (replies[url] ?: error("Unexpected fixture request"))()
        }
        fun reply(url: String, text: String = "", status: Int = 200, location: String? = null,
                  length: Long = text.toByteArray(Charsets.UTF_8).size.toLong(), finalUrl: String = url) {
            replies[url] = {
                BuildFeedback.Response(status, length, finalUrl,
                    ByteArrayInputStream(text.toByteArray(Charsets.UTF_8)), location) { closed++ }
            }
        }
        fun json(url: String, body: JSONObject) = reply(url, body.toString())
    }

    private fun runUrl(id: Long = 17) = "$api/actions/runs/$id"
    private fun jobsUrl(id: Long = 17, attempt: Int = 2) = "$api/actions/runs/$id/attempts/$attempt/jobs?per_page=10"
    private fun logUrl(id: Long = 91) = "$api/actions/jobs/$id/logs"
    private fun listUrl() = "$api/actions/workflows/build-jarvis-developer.yml/runs?branch=${URLEncoder.encode(branch, "UTF-8")}&head_sha=$sha&per_page=20"
    private fun run(id: Long = 17, number: Long = 5, attempt: Int = 2, status: String = "completed", result: String? = "failure") = JSONObject()
        .put("id", id).put("run_number", number).put("run_attempt", attempt)
        .put("head_sha", sha).put("head_branch", branch).put("path", ".github/workflows/build-jarvis-developer.yml")
        .put("repository", JSONObject().put("full_name", repository))
        .put("head_repository", JSONObject().put("full_name", repository))
        .put("html_url", "https://github.com/$repository/actions/runs/$id")
        .put("status", status).put("conclusion", result ?: JSONObject.NULL)
    private fun job(id: Long = 91, status: String = "completed", result: String? = "failure") = JSONObject()
        .put("id", id).put("run_id", 17).put("run_attempt", 2).put("head_sha", sha)
        .put("run_url", runUrl()).put("status", status).put("conclusion", result ?: JSONObject.NULL)
    private fun jobs(items: List<JSONObject> = listOf(job()), total: Int = items.size) = JSONObject()
        .put("total_count", total).put("jobs", JSONArray(items))
    private fun fixture() = Fixture().apply {
        json(listUrl(), JSONObject().put("workflow_runs", JSONArray().put(run())))
        json(runUrl(), run())
        json(jobsUrl(), jobs())
        reply(logUrl(), "e: developer/src/main/java/com/jarvis/developer/TaskFileSummary.kt:8:1 Unresolved reference: result\nBUILD FAILED\n")
    }
    private fun rejected(block: () -> Unit): IllegalStateException {
        try { block(); fail("Expected feedback rejection") } catch (failure: IllegalStateException) { return failure }
        error("Unreachable")
    }

    private val personalBranch = "feature/jarvis-personal-repair/diagnostic-012345abcdef"
    private fun personalList() = "$api/actions/workflows/local-knowledge-checks.yml/runs?branch=${URLEncoder.encode(personalBranch, "UTF-8")}&head_sha=$sha&per_page=20"
    private fun personalRun() = run().put("head_branch", personalBranch).put("path", ".github/workflows/local-knowledge-checks.yml")
    private fun personalFixture() = fixture().apply {
        json(personalList(), JSONObject().put("workflow_runs", JSONArray().put(personalRun())))
        json(runUrl(), personalRun())
        reply(logUrl(), "e: app/src/main/java/com/assistant/core/services/Example.kt:8:1 Unresolved reference: objective\n")
    }

    @Test fun personalDiscoveryUsesOnlyItsOwnExactSourceWorkflowAndReturnsReadOnlyDiagnostics() {
        val fake = personalFixture()
        fake.json(personalList(), JSONObject().put("workflow_runs", JSONArray()
            .put(personalRun().put("id", 99).put("run_number", 99).put("path", ".github/workflows/build-jarvis-developer.yml"))
            .put(personalRun())))
        val report = BuildFeedback.Client(fake).read(token, personalBranch, sha, target = PatchEngine.Target.PERSONAL)
        assertEquals(PatchEngine.Target.PERSONAL, report.target)
        assertEquals(listOf(personalList(), runUrl(), jobsUrl(), logUrl()), fake.requests.map { it.url })
        assertEquals("app/src/main/java/com/assistant/core/services/Example.kt", report.diagnostics.single().path)
        assertFalse(report.evidenceComplete); assertNull(report.totals)
        assertEquals(4, fake.closed)
        assertTrue(BuildDiagnostics.render(report).contains("Personal feedback is read-only"))
    }

    @Test fun personalWrongWorkflowSourceRepositoryOrJobIsRejectedBeforeLogs() {
        for (mutate in listOf<(JSONObject) -> Unit>(
            { it.put("path", ".github/workflows/build-jarvis-developer.yml") },
            { it.put("path", ".github/workflows/release-personal.yml") },
            { it.put("head_sha", "b".repeat(40)) },
            { it.put("head_branch", branch) },
            { it.put("head_repository", JSONObject().put("full_name", "someone/else")) }
        )) {
            val fake = personalFixture()
            fake.json(runUrl(), personalRun().also(mutate))
            rejected { BuildFeedback.Client(fake).read(token, personalBranch, sha, 17, PatchEngine.Target.PERSONAL) }
            assertEquals(listOf(runUrl()), fake.requests.map { it.url })
        }
        val fake = personalFixture()
        fake.json(jobsUrl(), jobs(listOf(job().put("head_sha", "b".repeat(40)))))
        rejected { BuildFeedback.Client(fake).read(token, personalBranch, sha, 17, PatchEngine.Target.PERSONAL) }
        assertEquals(listOf(runUrl(), jobsUrl()), fake.requests.map { it.url })
    }

    @Test fun personalLogAccessFailureAndStorageRedirectNeverLeakCredentialsOrInventTests() {
        val fake = personalFixture()
        fake.reply(logUrl(), status = 302, location = storage)
        fake.reply(storage, status = 403)
        val report = BuildFeedback.Client(fake).read(token, personalBranch, sha, 17, PatchEngine.Target.PERSONAL)
        assertFalse(report.evidenceComplete); assertTrue(report.tests.isEmpty())
        assertEquals(PatchEngine.Target.PERSONAL, report.target)
        assertTrue(report.limitations.any { it.contains("HTTP 403") })
        assertNull(fake.requests.last().token)
        assertEquals(4, fake.requests.size)
        assertFalse(BuildDiagnostics.render(report).contains("transient-only"))
    }

    @Test fun productScopeIsRequiredBeforeNetworkAndCanonicalListEndpointsCannotCrossProducts() {
        val fake = personalFixture()
        try { BuildFeedback.Client(fake).read(token, personalBranch, sha); fail("Explicit Personal product required") }
        catch (_: IllegalArgumentException) { }
        assertTrue(fake.requests.isEmpty())
        BuildFeedback.requireApiUrl(personalList())
        BuildFeedback.requireApiUrl(listUrl())
        for (url in listOf(personalList().replace("local-knowledge-checks", "build-jarvis-developer"),
            listUrl().replace("build-jarvis-developer", "local-knowledge-checks"), personalList() + "&unexpected=true")) {
            try { BuildFeedback.requireApiUrl(url); fail("Cross-product or expanded query accepted") }
            catch (_: IllegalArgumentException) { }
        }
    }

    @Test fun exactRunAndAttemptAreVerifiedBeforeReadingCompletedJobLogs() {
        val fake = fixture()
        val report = BuildFeedback.Client(fake).read(token, branch, sha)
        assertEquals(sha, report.commitSha)
        assertEquals(branch, report.branch)
        assertEquals(17L, report.runId)
        assertEquals(2, report.runAttempt)
        assertEquals("completed", report.status)
        assertEquals(listOf(listUrl(), runUrl(), jobsUrl(), logUrl()), fake.requests.map { it.url })
        assertTrue(fake.requests.all { it.token == token && it.timeoutMillis in 1..BuildFeedback.MAX_MILLIS })
        assertEquals(fake.requests.size, fake.closed)
    }

    @Test fun requestedRunBypassesDiscoveryAndStillVerifiesAllIdentityFields() {
        val fake = fixture()
        BuildFeedback.Client(fake).read(token, branch, sha, 17)
        assertEquals(runUrl(), fake.requests.first().url)
        assertEquals(3, fake.closed)
        for (mutate in listOf<(JSONObject) -> Unit>(
            { it.put("id", 18) }, { it.put("head_sha", "b".repeat(40)) }, { it.put("head_branch", "main") },
            { it.put("path", ".github/workflows/release-jarvis-developer.yml") },
            { it.put("repository", JSONObject().put("full_name", "someone/else")) },
            { it.put("head_repository", JSONObject().put("full_name", "someone/else")) },
            { it.put("html_url", "https://example.invalid/build") }, { it.put("run_attempt", 0) }
        )) {
            val wrong = fixture()
            wrong.json(runUrl(), run().also(mutate))
            val failure = rejected { BuildFeedback.Client(wrong).read(token, branch, sha, 17) }
            assertEquals("Build feedback could not verify exact-source evidence", failure.message)
            assertEquals(1, wrong.requests.size)
            assertEquals(1, wrong.closed)
        }
    }

    @Test fun discoveryIgnoresForeignSourcesAndChoosesLatestRunThenAttempt() {
        val fake = fixture()
        fake.json(listUrl(), JSONObject().put("workflow_runs", JSONArray()
            .put(run(id = 20, number = 99).put("head_sha", "b".repeat(40)))
            .put(run(id = 21, number = 99).put("path", ".github/workflows/release-jarvis-developer.yml"))
            .put(run(id = 22, number = 99).put("head_branch", "main"))
            .put(run(id = 16, number = 5, attempt = 1)).put(run())))
        assertEquals(17L, BuildFeedback.Client(fake).read(token, branch, sha).runId)
        assertEquals(runUrl(), fake.requests[1].url)
        fake.requests.clear()
        fake.json(listUrl(), JSONObject().put("workflow_runs", JSONArray().put(run().put("head_sha", "b".repeat(40)))))
        val missing = BuildFeedback.Client(fake).read(token, branch, sha)
        assertEquals("missing", missing.status)
        assertEquals(0L, missing.runId)
        assertFalse(missing.evidenceComplete)
        assertEquals(1, fake.requests.size)
    }

    @Test fun discoveryAllowsABoundedPageOfFullRunMetadata() {
        val fake = fixture()
        // Real GitHub run objects repeat both repository descriptions. A normal 20-run
        // page can exceed 128 KiB even though every run and the page remain bounded.
        val page = JSONArray()
        repeat(19) { index ->
            page.put(run(id = 100L + index, number = 1).put("display_title", "x".repeat(10_000)))
        }
        page.put(run().put("display_title", "x".repeat(10_000)))
        val response = JSONObject().put("workflow_runs", page)
        assertTrue(response.toString().toByteArray(Charsets.UTF_8).size > 131_072)
        fake.json(listUrl(), response)
        assertEquals(17L, BuildFeedback.Client(fake).read(token, branch, sha).runId)
        assertEquals(listOf(listUrl(), runUrl(), jobsUrl(), logUrl()), fake.requests.map { it.url })
        assertEquals(4, fake.closed)
    }

    @Test fun unfinishedRunsNeverFetchJobsOrPartialLogs() {
        for (state in listOf("queued", "requested", "waiting", "pending", "in_progress")) {
            val fake = fixture()
            fake.json(runUrl(), run(status = state, result = null))
            val report = BuildFeedback.Client(fake).read(token, branch, sha, 17)
            assertEquals(state, report.status)
            assertFalse(report.evidenceComplete)
            assertEquals(listOf(runUrl()), fake.requests.map { it.url })
        }
        val fake = fixture()
        fake.json(jobsUrl(), jobs(listOf(job(status = "in_progress", result = null))))
        assertFalse(BuildFeedback.Client(fake).read(token, branch, sha, 17).evidenceComplete)
        assertEquals(2, fake.requests.size)
    }

    @Test fun duplicateMissingForeignOrOversizedJobInventoriesRejectBeforeAnyLogRead() {
        val invalid = listOf(jobs(listOf(job(), job())), jobs(total = 2), jobs(emptyList()),
            jobs().put("total_count", "1"), jobs().put("total_count", 1.5),
            jobs((0..10).map { job(91L + it) }), jobs(listOf(job().put("run_id", 18))),
            jobs(listOf(job().put("run_attempt", 1))), jobs(listOf(job().put("head_sha", "b".repeat(40)))),
            jobs(listOf(job().put("run_url", "$api/actions/runs/18"))))
        for (body in invalid) {
            val fake = fixture()
            fake.json(jobsUrl(), body)
            rejected { BuildFeedback.Client(fake).read(token, branch, sha, 17) }
            assertEquals(2, fake.requests.size)
            assertEquals(2, fake.closed)
        }
    }

    @Test fun signedLogRedirectHasExactlyOneHopAndNeverReceivesToken() {
        val fake = fixture()
        fake.reply(logUrl(), status = 302, location = storage)
        fake.reply(storage, "BUILD FAILED\n")
        BuildFeedback.Client(fake).read(token, branch, sha, 17)
        assertEquals(listOf(runUrl(), jobsUrl(), logUrl(), storage), fake.requests.map { it.url })
        assertTrue(fake.requests.dropLast(1).all { it.token == token })
        assertNull(fake.requests.last().token)
        assertEquals(4, fake.closed)
        // A second redirect is an incomplete report, never a third storage request.
        fake.requests.clear()
        fake.reply(storage, status = 302, location = storage + "&next=1")
        val report = BuildFeedback.Client(fake).read(token, branch, sha, 17)
        assertFalse(report.evidenceComplete)
        assertTrue(report.limitations.any { it.contains("incomplete") })
        assertEquals(4, fake.requests.size)
    }

    @Test fun unrecognizedRedirectsAndUrlCredentialsNeverLeaveApiOrigin() {
        for (destination in listOf("http://productionresults1.blob.core.windows.net/a?sig=x",
            "https://evil.example/a?sig=x", "https://productionresults1.blob.core.windows.net.evil.example/a?sig=x",
            "https://token@productionresults1.blob.core.windows.net/a?sig=x",
            "https://productionresults1.blob.core.windows.net:443/a?sig=x",
            "https://productionresults1.blob.core.windows.net/a?sig=x#fragment",
            "https://productionresults1.blob.core.windows.net/a/../b?sig=x", "$api/actions/jobs/91/logs")) {
            val fake = fixture()
            fake.reply(logUrl(), status = 302, location = destination)
            val report = BuildFeedback.Client(fake).read(token, branch, sha, 17)
            assertFalse(report.evidenceComplete)
            assertTrue(report.limitations.none { it.contains(destination) || it.contains("sig=") || it.contains(token) })
            assertEquals(3, fake.requests.size)
            assertEquals(3, fake.closed)
        }
    }

    @Test fun apiRedirectsAndUnexpectedFinalUrlsAreRejectedWithoutFollowing() {
        val fake = fixture()
        fake.reply(runUrl(), status = 302, location = storage)
        rejected { BuildFeedback.Client(fake).read(token, branch, sha, 17) }
        assertEquals(1, fake.requests.size)
        assertEquals(1, fake.closed)
        fake.reply(runUrl(), run().toString(), finalUrl = storage)
        rejected { BuildFeedback.Client(fake).read(token, branch, sha, 17) }
        assertEquals(2, fake.closed)
    }

    @Test fun errorBodiesAreClosedWithoutBeingReadAndFailuresContainNoCauseOrSecret() {
        val fake = fixture()
        var read = false
        fake.replies[runUrl()] = {
            BuildFeedback.Response(403, 100, runUrl(), object : InputStream() {
                override fun read(): Int { read = true; error("Credential-bearing body must remain unread") }
            }) { fake.closed++ }
        }
        val failure = rejected { BuildFeedback.Client(fake).read(token, branch, sha, 17) }
        assertFalse(read)
        assertTrue(failure.message.orEmpty().contains("HTTP 403"))
        assertNull(failure.cause)
        assertEquals(1, fake.closed)
        fake.replies[runUrl()] = { error("$token $storage") }
        val raw = rejected { BuildFeedback.Client(fake).read(token, branch, sha, 17) }
        assertFalse(raw.message.orEmpty().contains(token))
        assertFalse(raw.message.orEmpty().contains("sig="))
        assertNull(raw.cause)
    }

    @Test fun missingOrFailedLogMakesEvenSuccessfulMetadataIncomplete() {
        val fake = fixture()
        fake.json(runUrl(), run(result = "success"))
        fake.json(jobsUrl(), jobs(listOf(job(result = "success"))))
        fake.reply(logUrl(), status = 404)
        val report = BuildFeedback.Client(fake).read(token, branch, sha, 17)
        assertEquals("success", report.conclusion)
        assertFalse(report.evidenceComplete)
        assertTrue(report.limitations.any { it.contains("HTTP 404") })
        assertEquals(3, fake.closed)
    }

    @Test fun advertisedOversizedLogIsRejectedBeforeReadingAndUnknownLengthStopsAtLimitPlusOne() {
        val fake = fixture()
        var read = false
        fake.replies[logUrl()] = {
            BuildFeedback.Response(200, BuildFeedback.MAX_LOG_BYTES + 1L, logUrl(), object : InputStream() {
                override fun read(): Int { read = true; return 'x'.code }
            }) { fake.closed++ }
        }
        assertFalse(BuildFeedback.Client(fake).read(token, branch, sha, 17).evidenceComplete)
        assertFalse(read)
        var supplied = 0
        fake.replies[logUrl()] = {
            BuildFeedback.Response(200, -1, logUrl(), object : InputStream() {
                override fun read(): Int { supplied++; return 'x'.code }
                override fun read(bytes: ByteArray, offset: Int, length: Int): Int {
                    bytes.fill('x'.code.toByte(), offset, offset + length)
                    supplied += length
                    return length
                }
            }) { fake.closed++ }
        }
        assertFalse(BuildFeedback.Client(fake).read(token, branch, sha, 17).evidenceComplete)
        assertEquals(BuildFeedback.MAX_LOG_BYTES + 1, supplied)
        assertEquals(6, fake.closed)
    }

    @Test fun totalByteBudgetIncludesMetadataAndEveryJobLog() {
        val fake = fixture()
        fake.json(jobsUrl(), jobs(listOf(job(), job(92))))
        fake.reply(logUrl(), "x".repeat(BuildFeedback.MAX_LOG_BYTES))
        fake.reply(logUrl(92), "x".repeat(BuildFeedback.MAX_LOG_BYTES))
        val report = BuildFeedback.Client(fake).read(token, branch, sha, 17)
        assertFalse(report.evidenceComplete)
        assertEquals(4, fake.requests.size)
        assertEquals(4, fake.closed)
    }

    @Test fun requestBudgetCountsStorageRedirectsAndDoesNotRetry() {
        val fake = fixture()
        fake.json(jobsUrl(), jobs((0 until 10).map { job(91L + it) }))
        for (index in 0 until 10) {
            val destination = storage + "&job=$index"
            fake.reply(logUrl(91L + index), status = 302, location = destination)
            fake.reply(destination, "BUILD FAILED\n")
        }
        val report = BuildFeedback.Client(fake).read(token, branch, sha, 17)
        assertFalse(report.evidenceComplete)
        assertEquals(BuildFeedback.MAX_REQUESTS, fake.requests.size)
        assertEquals(BuildFeedback.MAX_REQUESTS, fake.closed)
    }

    @Test fun elapsedSessionBudgetStopsBeforeAnotherCallAndPassesRemainingDeadline() {
        val fake = fixture()
        var now = 0L
        val client = BuildFeedback.Client(fake) { now }
        fake.replies[runUrl()] = {
            now = BuildFeedback.MAX_MILLIS
            BuildFeedback.Response(200, -1, runUrl(), ByteArrayInputStream(run().toString().toByteArray())) { fake.closed++ }
        }
        rejected { client.read(token, branch, sha, 17) }
        assertEquals(1, fake.requests.size)
        assertEquals(BuildFeedback.MAX_MILLIS, fake.requests.single().timeoutMillis)
        assertEquals(1, fake.closed)
        rejected { client.read(token, branch, sha, 17) }
        assertEquals(1, fake.requests.size)
    }

    @Test fun invalidSourceIdentityTokenAndWriteOrForeignEndpointsRejectBeforeNetwork() {
        val fake = Fixture()
        for (candidate in listOf("main", "feature/jarvis-developer/task/a", "feature/jarvis-developer-task/a-ABCDEF123456",
            "feature/jarvis-developer-task/../a-012345abcdef")) {
            try { BuildFeedback.Client(fake).read(token, candidate, sha); fail("Invalid branch") }
            catch (_: IllegalArgumentException) { }
        }
        for (candidate in listOf("main", "a".repeat(39), "A".repeat(40))) {
            try { BuildFeedback.Client(fake).read(token, branch, candidate); fail("Invalid SHA") }
            catch (_: IllegalArgumentException) { }
        }
        try { BuildFeedback.Client(fake).read("bad\nheader", branch, sha); fail("Invalid token") }
        catch (_: IllegalArgumentException) { }
        for (url in listOf("$api/git/refs", "$api/actions/workflows/release-jarvis-developer.yml/runs",
            "$api/actions/runs/17/cancel", "$api/releases", "$api/actions/runs/17?other=1",
            "https://api.github.com/repos/someone/else/actions/runs/17", "http://api.github.com/repos/$repository/actions/runs/17",
            "https://user@api.github.com/repos/$repository/actions/runs/17", "https://api.github.com:443/repos/$repository/actions/runs/17")) {
            try { BuildFeedback.requireApiUrl(url); fail("Invalid endpoint") }
            catch (_: IllegalArgumentException) { }
        }
        assertTrue(fake.requests.isEmpty())
    }
}
