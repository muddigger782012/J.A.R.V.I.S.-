package com.jarvis.developer

import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.util.Timer
import java.util.TimerTask

/** Reads verified evaluation evidence. It cannot dispatch, sign, promote, publish or install. */
object CandidateFeedback {
    private const val REPOSITORY = "muddigger782012/J.A.R.V.I.S.-"
    private const val API = "https://api.github.com/repos/$REPOSITORY"
    private const val CONTROLLER_BRANCH = "feature/jarvis-developer"
    private const val WORKFLOW = ".github/workflows/evaluate-jarvis-developer.yml"
    private const val EVIDENCE_JOB = "Record candidate evaluation"
    private const val MARKER = "JARVIS_CANDIDATE_EVIDENCE="
    internal const val MAX_REQUESTS = 20
    internal const val MAX_JOBS = 10
    internal const val MAX_MILLIS = 180_000L
    internal const val MAX_LOG_BYTES = 1_048_576
    internal const val MAX_SESSION_BYTES = 2_097_152
    internal const val MAX_EVIDENCE_BYTES = 65_536
    private const val MAX_METADATA_BYTES = 1_048_576
    private val shaPattern = Regex("[a-f0-9]{40}")

    /** Null authentication is mandatory for the one permitted signed-storage hop. */
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
        override fun close() { try { body.close() } finally { disconnect() } }
    }

    private class HttpFailure(val status: Int) : IllegalStateException()

    private val http = Transport { url, token, timeoutMillis ->
        if (token == null) BuildFeedback.requireStorageUrl(url) else requireApiUrl(url)
        require(timeoutMillis in 1..MAX_MILLIS) { "Invalid candidate transport budget" }
        val connection = URL(url).openConnection() as HttpURLConnection
        val deadline = Timer("JarvisDeveloper-CandidateFeedbackTimeout", true)
        deadline.schedule(object : TimerTask() {
            override fun run() { try { connection.disconnect() } catch (_: Exception) { } }
        }, timeoutMillis)
        try {
            connection.requestMethod = "GET"
            connection.instanceFollowRedirects = false
            connection.doOutput = false
            connection.useCaches = false
            connection.connectTimeout = minOf(15_000L, timeoutMillis).toInt()
            connection.readTimeout = minOf(30_000L, timeoutMillis).toInt()
            if (token != null) {
                connection.setRequestProperty("Accept", "application/vnd.github+json")
                connection.setRequestProperty("User-Agent", "JarvisDeveloper-CandidateFeedback")
                connection.setRequestProperty("X-GitHub-Api-Version", "2022-11-28")
                if (token.isNotBlank()) connection.setRequestProperty("Authorization", "Bearer $token")
            }
            // No authentication or API headers are sent to signed storage.
            val status = connection.responseCode
            val body = if (status in 200..299) connection.inputStream else ByteArrayInputStream(byteArrayOf())
            Response(status, connection.contentLengthLong, connection.url.toString(), body,
                if (status == 302) connection.getHeaderField("Location") else null) {
                deadline.cancel()
                connection.disconnect()
            }
        } catch (_: Exception) {
            deadline.cancel()
            connection.disconnect()
            throw IllegalStateException("Candidate feedback transport failed")
        }
    }

    fun read(token: String, candidateBranch: String, candidateSha: String, runId: Long,
             controllerCommitSha: String): CandidateEvaluation.Evidence =
        Client(http).read(token, candidateBranch, candidateSha, runId, controllerCommitSha)

    internal class Client(
        private val transport: Transport,
        private val nowMillis: () -> Long = { System.nanoTime() / 1_000_000 }
    ) {
        private val started = nowMillis()
        private var requests = 0
        private var receivedBytes = 0

        fun read(token: String, candidateBranch: String, candidateSha: String, runId: Long,
                 controllerCommitSha: String): CandidateEvaluation.Evidence {
            require(BuildDiagnostics.validBranch(candidateBranch) && shaPattern.matches(candidateSha) &&
                shaPattern.matches(controllerCommitSha) && runId > 0) { "Invalid isolated candidate identity" }
            require(token.length <= 4096 && token.none { it.isISOControl() }) { "Invalid in-memory GitHub token" }
            try {
                val run = json("$API/actions/runs/$runId", token)
                validateRun(run, controllerCommitSha, runId)
                require(run.getString("event") != "push" ||
                    (candidateBranch == CONTROLLER_BRANCH && candidateSha == controllerCommitSha)) {
                    "Push candidate differs from trusted controller source"
                }
                val attempt = positiveInt(run, "run_attempt")
                // A queued or running evaluation never supplies partial proof.
                require(run.getString("status") == "completed" && conclusion(run) == "success") {
                    "Candidate evaluation is unfinished or unsuccessful"
                }
                val inventory = json("$API/actions/runs/$runId/attempts/$attempt/jobs?per_page=$MAX_JOBS", token)
                val jobs = inventory.getJSONArray("jobs")
                val total = positiveInt(inventory, "total_count")
                require(total in 1..MAX_JOBS && jobs.length() == total) { "Candidate job inventory is incomplete" }
                val ids = HashSet<Long>()
                var evidenceJobId: Long? = null
                // Verify EVERY job before opening ANY log, including when an earlier job is valid.
                for (index in 0 until jobs.length()) {
                    val job = jobs.getJSONObject(index)
                    val id = positiveLong(job, "id")
                    require(ids.add(id) && positiveLong(job, "run_id") == runId &&
                        positiveInt(job, "run_attempt") == attempt && job.getString("head_sha") == controllerCommitSha &&
                        job.getString("run_url") == "$API/actions/runs/$runId" &&
                        job.getString("status") == "completed" && conclusion(job) == "success") {
                        "Candidate job source identity is incomplete or mismatched"
                    }
                    if (job.getString("name") == EVIDENCE_JOB) {
                        require(evidenceJobId == null) { "Ambiguous candidate evidence job" }
                        evidenceJobId = id
                    }
                }
                var record: String? = null
                // Candidate code can emit arbitrary log text. Only the separate trusted
                // controller job, which executes no candidate code, can supply evidence.
                for (id in listOf(requireNotNull(evidenceJobId) { "Trusted candidate evidence job is missing" })) {
                    val text = log("$API/actions/jobs/$id/logs", token)
                    for (line in text.lineSequence()) {
                        val offset = line.indexOf(MARKER)
                        if (offset < 0) continue
                        // GitHub prefixes actual emitted lines with a timestamp. A workflow's
                        // echoed source (print('MARKER=' + ...)) is not evidence.
                        val prefix = line.substring(0, offset)
                        if (!(prefix.isEmpty() || timestampPrefix.matches(prefix))) continue
                        require(record == null && line.indexOf(MARKER, offset + MARKER.length) < 0) {
                            "Duplicate candidate evidence marker"
                        }
                        val value = line.substring(offset + MARKER.length)
                        require(value.toByteArray(Charsets.UTF_8).size <= MAX_EVIDENCE_BYTES && value.isNotEmpty()) {
                            "Candidate evidence record exceeds limit or is empty"
                        }
                        record = value
                    }
                }
                val expected = CandidateEvaluation.Expected(candidateSha, candidateBranch, controllerCommitSha, runId, attempt)
                return CandidateEvaluation.parseEvidence(requireNotNull(record) { "Candidate evidence marker is missing" }, expected)
            } catch (failure: Exception) {
                val suffix = if (failure is HttpFailure) " (HTTP ${failure.status})" else ""
                // No cause, raw log, signed location, token or server-controlled body escapes.
                throw IllegalStateException("Candidate feedback could not verify exact-source evidence$suffix")
            }
        }

        private fun validateRun(run: JSONObject, controllerCommitSha: String, id: Long) {
            require(positiveLong(run, "id") == id && run.getString("head_sha") == controllerCommitSha &&
                run.getString("head_branch") == CONTROLLER_BRANCH && run.getString("path") == WORKFLOW &&
                run.getString("event") in setOf("push", "workflow_dispatch") &&
                run.getJSONObject("repository").getString("full_name") == REPOSITORY &&
                run.getJSONObject("head_repository").getString("full_name") == REPOSITORY &&
                run.getString("html_url") == "https://github.com/$REPOSITORY/actions/runs/$id") {
                "Candidate controller source identity mismatch"
            }
            positiveInt(run, "run_attempt")
            val status = run.getString("status")
            require(status in statuses && (status != "completed" || conclusion(run) in conclusions)) {
                "Unknown candidate run status"
            }
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
                BuildFeedback.requireStorageUrl(response.location ?: error("Missing signed log location"))
            }
            return open(first.location ?: error("Missing signed log location"), null).use { response ->
                if (response.status != 200) throw HttpFailure(response.status)
                strictUtf8(body(response, MAX_LOG_BYTES))
            }
        }

        private fun open(url: String, token: String?): Response {
            if (token == null) BuildFeedback.requireStorageUrl(url) else requireApiUrl(url)
            val remaining = remaining()
            require(requests < MAX_REQUESTS && receivedBytes <= MAX_SESSION_BYTES) { "Candidate feedback budget exceeded" }
            requests++
            val response = transport.get(url, token, remaining)
            try {
                remaining()
                require(response.finalUrl == url && response.contentLength >= -1) { "Unexpected candidate feedback transport result" }
                return response
            } catch (failure: Exception) { response.close(); throw failure }
        }

        private fun body(response: Response, maximum: Int): ByteArray {
            val limit = minOf(maximum, MAX_SESSION_BYTES - receivedBytes)
            require(limit >= 0 && response.contentLength in -1..limit.toLong()) { "Candidate evidence exceeds byte budget" }
            val output = ByteArrayOutputStream(minOf(8192, limit))
            val buffer = ByteArray(8192)
            var count = 0
            while (true) {
                remaining()
                val read = response.body.read(buffer, 0, minOf(buffer.size, limit - count + 1))
                if (read == -1) break
                require(read > 0) { "Candidate evidence stream made no progress" }
                count += read
                receivedBytes += read
                require(count <= limit && receivedBytes <= MAX_SESSION_BYTES) { "Candidate evidence exceeds byte budget" }
                output.write(buffer, 0, read)
            }
            return output.toByteArray()
        }

        private fun remaining(): Long {
            val elapsed = nowMillis() - started
            require(elapsed in 0 until MAX_MILLIS) { "Candidate feedback time budget exceeded" }
            return MAX_MILLIS - elapsed
        }
    }

    private val statuses = setOf("queued", "requested", "waiting", "pending", "in_progress", "completed")
    private val conclusions = setOf("success", "failure", "neutral", "cancelled", "skipped", "timed_out", "action_required", "stale", "startup_failure")
    private val timestampPrefix = Regex("[0-9]{4}-[0-9]{2}-[0-9]{2}T[0-9:.]+Z[ ]+")
    private fun conclusion(json: JSONObject): String? = if (json.isNull("conclusion")) null else json.getString("conclusion")
    private fun positiveLong(json: JSONObject, field: String): Long {
        val number = json.get(field)
        require(number is Number && number.toString().toLongOrNull()?.let { it > 0 } == true) { "Invalid candidate numeric identity" }
        return number.toString().toLong()
    }
    private fun positiveInt(json: JSONObject, field: String): Int {
        val value = positiveLong(json, field)
        require(value <= Int.MAX_VALUE) { "Invalid candidate numeric identity" }
        return value.toInt()
    }

    internal fun requireApiUrl(value: String) {
        val uri = URI(value)
        require(uri.scheme == "https" && uri.host == "api.github.com" && uri.port == -1 &&
            uri.rawUserInfo == null && uri.rawFragment == null && value.startsWith(API)) {
            "Candidate feedback must use the approved GitHub origin"
        }
        val path = value.removePrefix(API)
        require(path.matches(Regex("/actions/runs/[1-9][0-9]{0,18}")) ||
            path.matches(Regex("/actions/runs/[1-9][0-9]{0,18}/attempts/[1-9][0-9]{0,9}/jobs\\?per_page=10")) ||
            path.matches(Regex("/actions/jobs/[1-9][0-9]{0,18}/logs"))) {
            "Candidate operation is outside the read-only evaluation API"
        }
    }

    private fun strictUtf8(bytes: ByteArray): String = Charsets.UTF_8.newDecoder()
        .onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT)
        .decode(ByteBuffer.wrap(bytes)).toString()
}
