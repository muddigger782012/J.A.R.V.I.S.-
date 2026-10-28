package com.jarvis.developer

import org.json.JSONObject
import org.json.JSONException
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.InputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import java.net.URLEncoder
import java.net.SocketTimeoutException
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.nio.charset.CharacterCodingException
import java.util.Timer
import java.util.TimerTask
import java.util.concurrent.atomic.AtomicBoolean

/** GET-only, exact-source feedback. Signing, release and workflow-dispatch APIs are absent. */
object BuildFeedback {
    private const val REPOSITORY = "muddigger782012/J.A.R.V.I.S.-"
    private const val API = "https://api.github.com/repos/$REPOSITORY"
    private fun workflow(target: PatchEngine.Target): String = when (target) {
        PatchEngine.Target.DEVELOPER -> "build-jarvis-developer.yml"
        PatchEngine.Target.PERSONAL -> "local-knowledge-checks.yml"
    }
    internal const val MAX_REQUESTS = 20
    internal const val MAX_JOBS = 10
    internal const val MAX_MILLIS = 180_000L
    internal const val MAX_LOG_BYTES = 1_048_576
    internal const val MAX_SESSION_BYTES = 2_097_152
    // GitHub repeats repository metadata in each of the 20 discovery results. Keep this
    // bounded independently while allowing a normal page; all bytes also share the 2 MiB cap.
    private const val MAX_METADATA_BYTES = 1_048_576
    private val shaPattern = Regex("[a-f0-9]{40}")

    enum class Stage(val label: String) {
        DISCOVERY("selecting the build"), WORKFLOW_METADATA("reading workflow metadata"),
        WORKFLOW_VALIDATION("checking workflow identity"), JOB_METADATA("reading the job inventory"),
        JOB_VALIDATION("checking job identities"), LOG_READ("reading job logs"),
        LOG_STORAGE("reading retained log storage"), REPORT("interpreting build evidence")
    }

    enum class Problem(val label: String) {
        HTTP("The server did not return the required response"),
        CONNECTION("The connection or response stream was interrupted"),
        TIMEOUT("The network request timed out"), FORMAT("The response format could not be read"),
        LIMIT("The read reached its evidence or time limit"),
        VERIFICATION("The build metadata could not be verified against the selected source"),
        LOCATION("The response location was not allowed"), UNKNOWN("The read could not be verified"),
        CANCELLED("The read was cancelled and its evidence was discarded")
    }

    /** Only app-owned enums and a bounded HTTP number cross this error boundary. */
    class Failure internal constructor(val stage: Stage, val problem: Problem, val httpStatus: Int? = null) :
        IllegalStateException("Build feedback could not verify exact-source evidence [${stage.name}/${problem.name}]" +
            (httpStatus?.let { " (HTTP $it)" } ?: "")) {
        init {
            require(if (problem == Problem.HTTP) httpStatus != null && httpStatus in 100..599 else httpStatus == null)
        }
    }

    /** Never render Throwable messages, causes, suppressed exceptions, URLs or server text. */
    fun explainFailure(failure: Throwable): String? {
        if (failure !is Failure) return null
        val next = when (failure.problem) {
            Problem.HTTP -> if (failure.stage == Stage.LOG_STORAGE)
                "Request a fresh read from GitHub for a current log link. If that fails, inspect retained logs in GitHub."
            else when (failure.httpStatus ?: 0) {
                401 -> "Check the GitHub account or read credential used for this build."
                403 -> "Check access to this build and GitHub request limits."
                404 -> "Verify that the run is retained and visible to your GitHub account."
                429 -> "Wait for GitHub request limits to clear before an explicit fresh read."
                in 500..599 -> "Try an explicit fresh read when GitHub is available."
                else -> "Inspect the selected build in GitHub before another explicit read."
            }
            Problem.CONNECTION, Problem.TIMEOUT -> "Check connectivity, then explicitly read this build again."
            Problem.FORMAT, Problem.LOCATION -> "Inspect the build in GitHub; keep evidence verification blocked until a fresh read succeeds."
            Problem.LIMIT -> "Review this build on GitHub; its evidence could not be fully read within the supported limits."
            Problem.VERIFICATION -> "Review the selected product, branch, commit and run in GitHub before a fresh source analysis."
            Problem.UNKNOWN -> "Inspect this build in GitHub, then request a fresh read when ready."
            Problem.CANCELLED -> "Choose and explicitly read the build again when ready."
        }
        val status = failure.httpStatus?.let { " (HTTP $it)" }.orEmpty()
        return "Build feedback stopped while ${failure.stage.label}. ${failure.problem.label}$status. $next " +
            "This read did not start a repair, restore approval or retry automatically."
    }

    private class LimitFailure : IllegalStateException()
    private class LocationFailure : IllegalStateException()
    private class TransportFailure(val problem: Problem) : IllegalStateException()
    private class CancelledFailure : IllegalStateException()

    /** Null authentication is mandatory for the single signed-storage redirect. */
    internal fun interface Transport {
        fun get(url: String, token: String?, timeoutMillis: Long): Response
    }

    internal class Response(
        val status: Int,
        val contentLength: Long,
        val finalUrl: String,
        val body: InputStream,
        val location: String? = null,
        private val disconnect: () -> Unit = {}
    ) : Closeable {
        private val closed = AtomicBoolean()
        private var cancellationStop: Closeable? = null
        internal fun watch(cancellation: PatchPreviewCancellation?) {
            cancellationStop = cancellation?.registerStop { close() }
            if (closed.get()) cancellationStop?.close()
        }
        override fun close() {
            if (closed.compareAndSet(false, true)) {
                try { body.close() } finally { cancellationStop?.close(); disconnect() }
            }
        }
    }

    private class HttpFailure(val status: Int) : IllegalStateException()

    internal fun http(cancellation: PatchPreviewCancellation? = null) = Transport { url, token, timeoutMillis ->
        if (token == null) requireStorageUrl(url) else requireApiUrl(url)
        require(timeoutMillis in 1..MAX_MILLIS) { "Invalid feedback transport budget" }
        val connection = URL(url).openConnection() as HttpURLConnection
        val stop = cancellation?.registerStop { connection.disconnect() }
        val deadline = Timer("JarvisDeveloper-BuildFeedbackTimeout", true)
        deadline.schedule(object : TimerTask() {
            override fun run() { try { connection.disconnect() } catch (_: Exception) { } }
        }, timeoutMillis)
        try {
            cancellation?.check()
            connection.requestMethod = "GET"
            connection.instanceFollowRedirects = false
            connection.doOutput = false
            connection.useCaches = false
            connection.connectTimeout = minOf(15_000L, timeoutMillis).toInt()
            connection.readTimeout = minOf(30_000L, timeoutMillis).toInt()
            if (token != null) {
                connection.setRequestProperty("Accept", "application/vnd.github+json")
                connection.setRequestProperty("User-Agent", "JarvisDeveloper-BuildFeedback")
                connection.setRequestProperty("X-GitHub-Api-Version", "2022-11-28")
                if (token.isNotBlank()) connection.setRequestProperty("Authorization", "Bearer $token")
            }
            // Storage receives no API, user-agent or authorization headers from this app.
            val status = connection.responseCode
            val body = if (status in 200..299) connection.inputStream else ByteArrayInputStream(byteArrayOf())
            Response(status, connection.contentLengthLong, connection.url.toString(), body,
                if (status == 302) connection.getHeaderField("Location") else null) {
                stop?.close()
                deadline.cancel()
                connection.disconnect()
            }
        } catch (failure: Exception) {
            stop?.close()
            deadline.cancel()
            connection.disconnect()
            throw TransportFailure(when (failure) {
                is SocketTimeoutException -> Problem.TIMEOUT
                is IOException -> Problem.CONNECTION
                else -> Problem.UNKNOWN
            })
        }
    }

    fun read(token: String, branch: String, commitSha: String, runId: Long? = null,
        target: PatchEngine.Target = PatchEngine.Target.DEVELOPER): BuildDiagnostics.Report =
        Client(http()).read(token, branch, commitSha, runId, target)

    internal fun read(token: String, branch: String, commitSha: String,
        target: PatchEngine.Target, cancellation: PatchPreviewCancellation): BuildDiagnostics.Report =
        Client(http(cancellation), cancellation).read(token, branch, commitSha, target = target)

    internal class Client(
        private val transport: Transport,
        private val cancellation: PatchPreviewCancellation? = null,
        private val nowMillis: () -> Long = { System.nanoTime() / 1_000_000 }
    ) {
        private val started = nowMillis()
        private var requests = 0
        private var receivedBytes = 0
        private var stage = Stage.DISCOVERY

        fun read(token: String, branch: String, commitSha: String, runId: Long? = null,
            target: PatchEngine.Target = PatchEngine.Target.DEVELOPER): BuildDiagnostics.Report {
            require(BuildDiagnostics.validBranch(branch, target) && shaPattern.matches(commitSha)) { "Invalid isolated build source identity" }
            require(token.length <= 4096 && token.none { it.isISOControl() }) { "Invalid in-memory GitHub token" }
            require(runId == null || runId > 0) { "Invalid build run identity" }
            stage = if (runId == null) Stage.DISCOVERY else Stage.WORKFLOW_METADATA
            try {
                checkCancellation()
                val selectedId = runId ?: latestRun(token, branch, commitSha, target)
                    ?: return checked(BuildDiagnostics.parse(commitSha, branch, 0, 1, "missing", null, "", target))
                stage = Stage.WORKFLOW_METADATA
                val run = json("$API/actions/runs/$selectedId", token)
                stage = Stage.WORKFLOW_VALIDATION
                validateRun(run, branch, commitSha, selectedId, target)
                val attempt = positiveInt(run, "run_attempt")
                val status = run.getString("status")
                val conclusion = conclusion(run)
                val empty = BuildDiagnostics.parse(commitSha, branch, selectedId, attempt, status, conclusion, "", target)
                // A queued or running job is deliberately not harvested as partial evidence.
                if (status != "completed") return checked(empty)
                stage = Stage.JOB_METADATA
                val jobsResponse = json("$API/actions/runs/$selectedId/attempts/$attempt/jobs?per_page=$MAX_JOBS", token)
                stage = Stage.JOB_VALIDATION
                val jobs = jobsResponse.getJSONArray("jobs")
                val total = positiveInt(jobsResponse, "total_count")
                checkLimit(total <= MAX_JOBS)
                require(total in 1..MAX_JOBS && jobs.length() == total) { "Build job inventory is incomplete" }
                val ids = HashSet<Long>()
                var allCompleted = true
                for (index in 0 until jobs.length()) {
                    val job = jobs.getJSONObject(index)
                    val id = positiveLong(job, "id")
                    require(ids.add(id) && positiveLong(job, "run_id") == selectedId &&
                        positiveInt(job, "run_attempt") == attempt && job.getString("head_sha") == commitSha) {
                        "Build job source identity mismatch"
                    }
                    require(job.getString("run_url") == "$API/actions/runs/$selectedId") { "Build job run identity mismatch" }
                    val jobStatus = job.getString("status")
                    require(jobStatus in statuses) { "Unknown build job status" }
                    if (jobStatus == "completed") require(conclusion(job) in conclusions) { "Unknown build job conclusion" }
                    else allCompleted = false
                }
                if (!allCompleted) return checked(BuildDiagnostics.incomplete(empty, "Completed run contains unfinished jobs; logs were not fetched."))
                val logs = StringBuilder()
                try {
                    for (id in ids.sorted()) {
                        stage = Stage.LOG_READ
                        val text = log("$API/actions/jobs/$id/logs", token)
                        logs.append("\n=== Verified build job ").append(id).append(" ===\n").append(text).append('\n')
                    }
                } catch (failure: Exception) {
                    checkCancellation()
                    val detail = explainFailure(sanitized(failure))!!
                    stage = Stage.REPORT
                    val report = BuildDiagnostics.parse(commitSha, branch, selectedId, attempt, status, conclusion, logs.toString(), target)
                    return checked(BuildDiagnostics.incomplete(report, "Build log evidence is incomplete. $detail"))
                }
                stage = Stage.REPORT
                return checked(BuildDiagnostics.parse(commitSha, branch, selectedId, attempt, status, conclusion, logs.toString(), target))
            } catch (failure: Exception) {
                // Never retain a cause: exceptions may carry signed URLs or credentials.
                throw sanitized(failure)
            }
        }

        private fun sanitized(failure: Exception): Failure {
            if (cancellation?.isCancelled == true || failure is CancelledFailure)
                return Failure(stage, Problem.CANCELLED)
            val status = (failure as? HttpFailure)?.status?.takeIf { it in 100..599 }
            val problem = when {
                status != null -> Problem.HTTP
                failure is LimitFailure -> Problem.LIMIT
                failure is LocationFailure -> Problem.LOCATION
                failure is TransportFailure -> failure.problem
                failure is SocketTimeoutException -> Problem.TIMEOUT
                failure is JSONException || failure is CharacterCodingException -> Problem.FORMAT
                failure is IOException -> Problem.CONNECTION
                stage in setOf(Stage.WORKFLOW_VALIDATION, Stage.JOB_VALIDATION) -> Problem.VERIFICATION
                else -> Problem.UNKNOWN
            }
            return Failure(stage, problem, status)
        }

        private fun checkLimit(allowed: Boolean) { if (!allowed) throw LimitFailure() }
        private fun checkCancellation() { if (cancellation?.isCancelled == true) throw CancelledFailure() }
        private fun checked(report: BuildDiagnostics.Report): BuildDiagnostics.Report {
            checkCancellation()
            return report
        }

        private fun latestRun(token: String, branch: String, commitSha: String, target: PatchEngine.Target): Long? {
            val encoded = URLEncoder.encode(branch, "UTF-8")
            val response = json("$API/actions/workflows/${workflow(target)}/runs?branch=$encoded&head_sha=$commitSha&per_page=20", token)
            val runs = response.getJSONArray("workflow_runs")
            checkLimit(runs.length() <= 20)
            val matching = (0 until runs.length()).map { runs.getJSONObject(it) }.filter {
                it.optString("head_sha") == commitSha && it.optString("head_branch") == branch && it.optString("path") == ".github/workflows/${workflow(target)}"
            }
            val selected = matching.maxWithOrNull(compareBy<JSONObject> { positiveLong(it, "run_number") }
                .thenBy { positiveInt(it, "run_attempt") }) ?: return null
            return positiveLong(selected, "id")
        }

        private fun validateRun(run: JSONObject, branch: String, commitSha: String, id: Long, target: PatchEngine.Target) {
            require(positiveLong(run, "id") == id && run.getString("head_sha") == commitSha &&
                run.getString("head_branch") == branch && run.getString("path") == ".github/workflows/${workflow(target)}" &&
                run.getJSONObject("repository").getString("full_name") == REPOSITORY &&
                run.getJSONObject("head_repository").getString("full_name") == REPOSITORY &&
                run.getString("html_url") == "https://github.com/$REPOSITORY/actions/runs/$id") { "Build run source identity mismatch" }
            positiveInt(run, "run_attempt")
            val status = run.getString("status")
            require(status in statuses) { "Unknown build run status" }
            if (status == "completed") require(conclusion(run) in conclusions) { "Unknown build run conclusion" }
        }

        private fun json(url: String, token: String): JSONObject = open(url, token).use { response ->
            if (response.status != 200) throw HttpFailure(response.status)
            JSONObject(strictUtf8(body(response, MAX_METADATA_BYTES)))
        }

        private fun log(url: String, token: String): String {
            val first = open(url, token)
            first.use { response ->
                if (response.status == 200) return strictUtf8(body(response, MAX_LOG_BYTES))
                if (response.status != 302) throw HttpFailure(response.status)
                val redirect = response.location ?: error("Missing signed log location")
                try { requireStorageUrl(redirect) } catch (_: Exception) { throw LocationFailure() }
                // Close the API response before opening storage. No token or API headers survive this hop.
            }
            val location = first.location ?: error("Missing signed log location")
            stage = Stage.LOG_STORAGE
            return open(location, null).use { response ->
                if (response.status != 200) throw HttpFailure(response.status)
                strictUtf8(body(response, MAX_LOG_BYTES))
            }
        }

        private fun open(url: String, token: String?): Response {
            if (token == null) requireStorageUrl(url) else requireApiUrl(url)
            val remaining = remaining()
            checkLimit(requests < MAX_REQUESTS)
            checkLimit(receivedBytes <= MAX_SESSION_BYTES)
            requests++
            val response = transport.get(url, token, remaining)
            response.watch(cancellation)
            try {
                remaining()
                if (response.finalUrl != url) throw LocationFailure()
                require(response.contentLength >= -1) { "Invalid build feedback length" }
                return response
            } catch (failure: Exception) { response.close(); throw failure }
        }

        private fun body(response: Response, maximum: Int): ByteArray {
            val limit = minOf(maximum, MAX_SESSION_BYTES - receivedBytes)
            checkLimit(limit >= 0 && response.contentLength in -1..limit.toLong())
            val output = ByteArrayOutputStream(minOf(8192, limit))
            val buffer = ByteArray(8192)
            var count = 0
            while (true) {
                remaining()
                val read = response.body.read(buffer, 0, minOf(buffer.size, limit - count + 1))
                remaining()
                if (read == -1) break
                require(read > 0) { "Build evidence stream made no progress" }
                count += read
                receivedBytes += read
                checkLimit(count <= limit && receivedBytes <= MAX_SESSION_BYTES)
                output.write(buffer, 0, read)
            }
            return output.toByteArray()
        }

        private fun remaining(): Long {
            checkCancellation()
            val elapsed = nowMillis() - started
            checkLimit(elapsed in 0 until MAX_MILLIS)
            return MAX_MILLIS - elapsed
        }
    }

    private val statuses = setOf("queued", "requested", "waiting", "pending", "in_progress", "completed")
    private val conclusions = setOf("success", "failure", "neutral", "cancelled", "skipped", "timed_out", "action_required", "stale", "startup_failure")
    private fun conclusion(json: JSONObject): String? = if (json.isNull("conclusion")) null else json.getString("conclusion")
    private fun positiveLong(json: JSONObject, field: String): Long {
        val number = json.get(field)
        require(number is Number && number.toString().toLongOrNull()?.let { it > 0 } == true) { "Invalid build numeric identity" }
        return number.toString().toLong()
    }
    private fun positiveInt(json: JSONObject, field: String): Int {
        val number = positiveLong(json, field)
        require(number <= Int.MAX_VALUE) { "Invalid build numeric identity" }
        return number.toInt()
    }

    internal fun requireApiUrl(value: String) {
        val uri = URI(value)
        require(uri.scheme == "https" && uri.host == "api.github.com" && uri.port == -1 && uri.rawUserInfo == null &&
            uri.rawFragment == null && value.startsWith(API)) { "Build feedback must use the approved GitHub origin" }
        val path = value.removePrefix(API)
        val fixed = path.matches(Regex("/actions/runs/[1-9][0-9]{0,18}")) ||
            path.matches(Regex("/actions/runs/[1-9][0-9]{0,18}/artifacts\\?per_page=30")) ||
            path.matches(Regex("/actions/artifacts/[1-9][0-9]{0,18}/zip")) ||
            path.matches(Regex("/actions/runs/[1-9][0-9]{0,18}/attempts/[1-9][0-9]{0,9}/jobs\\?per_page=10")) ||
            path.matches(Regex("/actions/jobs/[1-9][0-9]{0,18}/logs"))
        val list = PatchEngine.Target.values().any { target ->
            val prefix = "/actions/workflows/${workflow(target)}/runs?branch="
            if (path.startsWith(prefix)) {
                val query = path.removePrefix(prefix)
                val branch = query.substringBefore("&head_sha=")
                val suffix = query.substringAfter("&head_sha=", "")
                try {
                    BuildDiagnostics.validBranch(java.net.URLDecoder.decode(branch, "UTF-8"), target) &&
                        branch == URLEncoder.encode(java.net.URLDecoder.decode(branch, "UTF-8"), "UTF-8") &&
                        suffix.matches(Regex("[a-f0-9]{40}&per_page=20"))
                } catch (_: Exception) { false }
            } else false
        }
        require(fixed || list) { "Build operation is outside the read-only feedback API" }
    }

    internal fun requireStorageUrl(value: String) {
        require(value.length <= 12_000) { "Invalid signed build log location" }
        val uri = URI(value)
        // GitHub Actions' production-results Azure account family only. Unknown hosts fail closed.
        require(uri.scheme == "https" && uri.host?.matches(Regex("productionresults[a-z0-9]{0,24}\\.blob\\.core\\.windows\\.net")) == true &&
            uri.port == -1 && uri.rawUserInfo == null && uri.rawFragment == null &&
            uri.rawPath.startsWith('/') && uri.rawPath.length > 1 && uri.path.split('/').none { it == ".." || it == "." } &&
            !uri.rawQuery.isNullOrEmpty()) { "Unrecognized signed build log storage" }
    }

    private fun strictUtf8(bytes: ByteArray): String = Charsets.UTF_8.newDecoder()
        .onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT)
        .decode(ByteBuffer.wrap(bytes)).toString()
}
